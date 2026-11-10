#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class DevelopmentRuntimeOwner
{
    internal Exception? MigratedStartupFailure { get; private set; }
    private async Task RunWorkspaceAsync(CancellationToken applicationLifetime)
    {
        LastStopConfirmed = false;
        MigratedStartupFailure = null;
        string? operation;
        try { operation = FindMigratedOperation(); }
        catch (Exception failure) { MigratedStartupFailure = failure; State("ADMISSION_DENIED"); return; }
        if (operation is null) await RunOwnedAsync(applicationLifetime).ConfigureAwait(false);
        else await RunMigratedAsync(operation, applicationLifetime).ConfigureAwait(false);
    }

    private string? FindMigratedOperation()
    {
        profile.Validate(); Mmf3ProfileNative.Verify(profile);
        string registry = Path.Combine(profile.Root,"evidence","sqlite-activation");
        FileAttributes attributes;
        try { attributes = File.GetAttributes(registry); }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
        if (!attributes.HasFlag(FileAttributes.Directory) || attributes.HasFlag(FileAttributes.ReparsePoint)) throw new IOException();
        var candidates = Directory.EnumerateFileSystemEntries(registry).Take(2).ToArray();
        if (candidates.Length != 1 || !Regex.IsMatch(Path.GetFileName(candidates[0]),"\\A[a-f0-9]{32}\\z")) throw new IOException();
        // This name selects the inspection only. No saved record grants a launch: the native
        // owner still checks the full chain and current pins before presenting consent.
        return Path.GetFileName(candidates[0]);
    }

    private async Task RunMigratedAsync(string operation,CancellationToken applicationLifetime)
    {
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(applicationLifetime,stop.Token);
        MaintenanceRuntimeHandoff? session = null;
        LastStopConfirmed = false;
        try
        {
            lifetime.Token.ThrowIfCancellationRequested();
            session = await MaintenanceRuntimeHandoff.OpenWorkspaceAsync(profile,operation,lifetime.Token).ConfigureAwait(false);
            lock(sync) { child = session.RuntimeProcess; jarSha256 = session.JarSha256; }
            runtime.BindOwnedDevelopmentEndpoint(session.Endpoint.Port,storageCredential:true);
            await runtime.CheckHealthAsync(lifetime.Token).ConfigureAwait(false);
            await runtime.CheckCredentialAsync(lifetime.Token).ConfigureAwait(false);
            var storage = await runtime.GetStorageStatusAsync(lifetime.Token).ConfigureAwait(false);
            if(storage.Backend!="POSTGRESQL"||storage.Connection!="CONNECTED"||storage.ConfigurationTrust!="TRUSTED_LOCAL"
                ||storage.WorkspaceSchema!="V1_V2_READY"||storage.KnowledgeSchema!="READY"||storage.FinanceEnabled
                ||storage.ErrorCode!="NONE"||session.RuntimeProcess.HasExited)throw new IOException();
            lifetime.Token.ThrowIfCancellationRequested();
            State("READY");
            await session.RuntimeProcess.WaitForExitAsync(lifetime.Token).ConfigureAwait(false);
            State("PROCESS_EXITED");
        }
        catch(OperationCanceledException) { State(session is null ? "STARTUP_CANCELLED" : "PROCESS_EXITED"); }
        catch(Exception failure) { MigratedStartupFailure = failure; State("STARTUP_CAUSE_UNAVAILABLE"); }
        finally
        {
            runtime.ClearOwnedDevelopmentEndpoint();
            try
            {
                bool graceful;lock(sync) graceful = gracefulStopRequested && !disposed;
                if(graceful && session is not null && !session.RuntimeProcess.HasExited)
                {
                    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(60));
                    await session.FinishAsync(deadline.Token).ConfigureAwait(false);
                    if(!session.VerifiedStopped||!session.OwnedJobEmpty)throw new IOException();
                    LastStopConfirmed = true;
                }
            }
            catch(Exception) { State("STARTUP_CAUSE_UNAVAILABLE"); }
            finally
            {
                // EOF/Dispose/cancellation never manufacture a stop receipt. The handoff owns
                // only its returned Process objects and Job; no PID adoption or global query.
                lock(sync) { child = null; jarSha256 = null; }
                session?.Dispose();
            }
        }
    }
}
#endif
