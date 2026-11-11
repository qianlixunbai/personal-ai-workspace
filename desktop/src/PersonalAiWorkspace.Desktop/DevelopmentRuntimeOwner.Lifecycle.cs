#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class DevelopmentRuntimeOwner
{
    private bool gracefulStopRequested;
    internal bool LastStopConfirmed { get; private set; }

    private async Task RunOwnedAsync(CancellationToken applicationLifetime)
    {
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(applicationLifetime, stop.Token);
        TrustedDevelopmentRuntimeSources? sources = null;
        RuntimeProcessJob? job = null;
        ProfileRecoveryCoordinator.RuntimeAttempt? ownership = null;
        NativeRuntimeSession? session = null;
        Process? process = null;
        LastStopConfirmed = false;
        using var trace = new RuntimeStartupTrace();
        try
        {
            lifetime.Token.ThrowIfCancellationRequested();
            trace.At(RuntimeStartupPhase.SourceValidation);
            sources = TrustedDevelopmentRuntimeSources.Open();
            trace.At(RuntimeStartupPhase.StartInfo);
            var start = sources.StartInfo(profile, ownedLifecycle: true);
            trace.OpenLog(profile.Root);
            trace.At(RuntimeStartupPhase.JobCreation);
            job = RuntimeProcessJob.Create();
            trace.At(RuntimeStartupPhase.AttemptRegistration);
            ownership = ProfileRecoveryCoordinator.RuntimeAttempt.Begin(profile, sources.JarSha256, ownedLifecycle: true);
            lifetime.Token.ThrowIfCancellationRequested();
            trace.At(RuntimeStartupPhase.ProcessStart);
            process = Process.Start(start) ?? throw new IOException();
            lock (sync) { child = process; jarSha256 = sources.JarSha256; }
            trace.ChildStarted(process.Id);
            trace.At(RuntimeStartupPhase.JobAssignment);
            job.Assign(process);
            trace.At(RuntimeStartupPhase.ChildRegistration);
            ownership.RecordStarted(process);
            using (var startup = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
            {
                startup.CancelAfter(TimeSpan.FromSeconds(60));
                session = await NativeRuntimeSession.OpenAsync(process, profile.RunId, ownership.AttemptId, startup.Token, trace).ConfigureAwait(false);
                trace.At(RuntimeStartupPhase.RecordReady);
                ownership.RecordReady(session);
                trace.At(RuntimeStartupPhase.BindEndpoint);
                runtime.BindOwnedDevelopmentEndpoint(session.Port);
                trace.At(RuntimeStartupPhase.HealthCheck);
                await runtime.CheckHealthAsync(startup.Token).ConfigureAwait(false);
                trace.At(RuntimeStartupPhase.CredentialCheck);
                await runtime.CheckCredentialAsync(startup.Token).ConfigureAwait(false);
                trace.At(RuntimeStartupPhase.ProcessCheck);
                startup.Token.ThrowIfCancellationRequested();
                if (process.HasExited) throw new IOException();
                trace.At(RuntimeStartupPhase.PublishReady);
                State("READY");
                trace.Completed();
            }
            await process.WaitForExitAsync(lifetime.Token).ConfigureAwait(false);
            State("PROCESS_EXITED");
        }
        catch (OperationCanceledException failure)
        { trace.Failed(failure); lock (sync) if (state is "STARTING" or "READY") State("PROCESS_EXITED"); }
        catch (NativeRuntimeSession.Failure failure) { trace.Failed(failure); State(failure.Code); }
        catch (PersonalAiWorkspace.Core.DesktopException failure)
        { trace.Failed(failure); State(failure.Error is PersonalAiWorkspace.Core.DesktopError.CredentialMissing or PersonalAiWorkspace.Core.DesktopError.CredentialInvalid
            ? "CREDENTIAL_UNAVAILABLE" : failure.Error == PersonalAiWorkspace.Core.DesktopError.Unauthorized
            ? "NATIVE_AUTHENTICATION_UNAVAILABLE" : "STARTUP_CAUSE_UNAVAILABLE"); }
        catch (Exception failure)
        { trace.Failed(failure); State(process is null ? "ADMISSION_DENIED" : "STARTUP_CAUSE_UNAVAILABLE"); }
        finally
        {
            runtime.ClearOwnedDevelopmentEndpoint();
            try
            {
                bool graceful;
                lock (sync) graceful = gracefulStopRequested && !disposed;
                if (graceful && session is not null && process is not null && !process.HasExited)
                {
                    // Persist intent before writing CLOSE. Use an independent bounded deadline:
                    // the application token is already cancelled during a normal exit.
                    ownership!.RecordStopRequested(session);
                    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(30));
                    await session.CloseAsync(deadline.Token).ConfigureAwait(false);
                    if (job!.ActiveProcessCount != 0) throw new IOException();
                    ownership.ObserveGracefulExit(session);
                    LastStopConfirmed = true;
                }
                else ownership?.ObserveExitBeforeOwnerCleanup(lifetime.IsCancellationRequested);
            }
            catch (Exception) { State("STARTUP_CAUSE_UNAVAILABLE"); }
            finally
            {
                try
                {
                    try { ownership?.BeginOwnerCleanup(); } catch (Exception) { State("STARTUP_CAUSE_UNAVAILABLE"); }
                    job?.Dispose();
                    if (process is not null)
                    {
                        // Only this returned Process; a failed Job assignment cannot leak it.
                        if (!process.HasExited) process.Kill(entireProcessTree: true);
                        using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                        await process.WaitForExitAsync(cleanup.Token).ConfigureAwait(false);
                    }
                    try { ownership?.ObserveExitAfterOwnerCleanup(); } catch (Exception) { State("STARTUP_CAUSE_UNAVAILABLE"); }
                }
                finally { ownership?.Dispose(); sources?.Dispose(); }
            }
        }
    }
}
#endif
