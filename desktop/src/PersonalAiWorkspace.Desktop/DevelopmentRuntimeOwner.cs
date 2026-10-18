using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;

namespace PersonalAiWorkspace.Desktop;

// Desktop lifetime owns one direct child. Bridge requests can observe it, never start another one.
internal sealed class DevelopmentRuntimeOwner : IDisposable
{
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    private readonly Mmf3AcceptanceLaunch profile;
    private readonly RuntimeClient runtime;
    private readonly CancellationTokenSource stop = new();
    private readonly object sync = new();
    private readonly RuntimeStartupDiagnostic diagnostic = new();
    private Task? running;
    private Process? child;
    private string state = "STARTING";
    private bool disposed;
    internal event Action<StorageStartupStatus>? Changed;
    internal int? OwnedProcessId { get { lock (sync) return child?.Id; } }
    internal DevelopmentRuntimeOwner(Mmf3AcceptanceLaunch profile, RuntimeClient runtime)
    { this.profile = profile; this.runtime = runtime; }
    internal void Start(CancellationToken applicationLifetime)
    {
        lock (sync)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            running ??= Task.Run(() => RunAsync(applicationLifetime));
        }
    }
    internal string? ReadCredential()
    {
        lock (sync) if (child is null || child.HasExited || disposed) return null;
        try
        {
            profile.Validate();
            if (!File.Exists(profile.TokenFile)) return null;
            return CredentialStore.ReadPrivateFile(profile.ValidateTokenImport(profile.TokenFile), requireProtected: true);
        }
        catch (IOException) { State("ADMISSION_DENIED"); stop.Cancel(); return null; }
    }
    private void State(string value)
    {
        lock (sync)
        {
            if (state == value) return;
            state = value;
            Changed?.Invoke(Snapshot()); // Serialize publication order across exit/auth/cancellation races.
        }
    }
    internal StorageStartupStatus Snapshot()
    {
        lock (sync)
        {
            string current = state == "READY" && (child is null || child.HasExited) ? "PROCESS_EXITED" : state;
            return new(current == "READY" ? "AVAILABLE" : current == "STARTING" ? "STARTING" : "UNAVAILABLE",
                current == "READY" ? "NONE" : current, "NOT_CHECKED", current == "READY", "TRUSTED_CONTEXT_REQUIRED", true);
        }
    }
    internal async Task<StorageStartupStatus> ReadAsync(CancellationToken cancellation)
    {
        cancellation.ThrowIfCancellationRequested();
        if (Snapshot().CanQueryStorage)
        {
            try
            {
                await runtime.CheckHealthAsync(cancellation).ConfigureAwait(false);
                await runtime.CheckCredentialAsync(cancellation).ConfigureAwait(false);
            }
            catch (DesktopException error)
            {
                if (Snapshot().Failure != "ADMISSION_DENIED") State(error.Error is DesktopError.Unauthorized or DesktopError.CredentialInvalid
                    ? "NATIVE_AUTHENTICATION_UNAVAILABLE" : error.Error == DesktopError.CredentialMissing
                    ? "CREDENTIAL_UNAVAILABLE" : "STARTUP_CAUSE_UNAVAILABLE");
                stop.Cancel();
            }
        }
        cancellation.ThrowIfCancellationRequested();
        return Snapshot();
    }
    private async Task RunAsync(CancellationToken applicationLifetime)
    {
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(applicationLifetime, stop.Token);
        TrustedDevelopmentRuntimeSources? sources = null;
        RuntimeProcessJob? job = null;
        Task output = Task.CompletedTask, errors = Task.CompletedTask;
        bool ready = false;
        try
        {
            lifetime.Token.ThrowIfCancellationRequested();
            profile.Validate(); Mmf3ProfileNative.Verify(profile);
            foreach (System.Collections.DictionaryEntry entry in Environment.GetEnvironmentVariables())
            {
                string key = ((string)entry.Key).ToUpperInvariant();
                if (key is "JAVA_TOOL_OPTIONS" or "_JAVA_OPTIONS" or "JDK_JAVA_OPTIONS"
                    || new[] { "DOTNET_", "COREHOST_", "COMPLUS_", "COR_", "SPRING_", "WORKSPACE_", "SERVER_" }.Any(key.StartsWith))
                    throw new IOException();
            }
            if (IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(p => p.Port == 18765)) throw new IOException();
            sources = TrustedDevelopmentRuntimeSources.Open();
            lifetime.Token.ThrowIfCancellationRequested();
            var start = new ProcessStartInfo(sources.Java)
            {
                UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = profile.Root,
                RedirectStandardError = true, RedirectStandardOutput = true
            };
            string native = Path.Combine(profile.Root, "jna-5.17.0-win32-x86-64");
            string evidence = Path.Combine(profile.Root, "evidence");
            foreach (string option in new[] { "-Dworkspace.native.development-profile=" + profile.RunId,
                "-Dworkspace.native.bootstrap=" + Mmf3ProfileNative.Helper, "-Djna.boot.library.path=" + native,
                "-Djna.boot.library.name=jnidispatch", "-Djna.nosys=true", "-Djna.noclasspath=true", "-Djna.nounpack=true",
                "-Djava.io.tmpdir=" + evidence, "-Djna.tmpdir=" + evidence, "-jar", sources.Jar,
                "--server.address=127.0.0.1", "--server.port=18765", "--workspace.security.token-file=" + profile.TokenFile,
                "--workspace.data-directory=" + Path.Combine(profile.Root, "data"), "--workspace.model-state-directory=" + Path.Combine(profile.Root, "model-state") })
                start.ArgumentList.Add(option);
            job = RuntimeProcessJob.Create();
            var process = Process.Start(start) ?? throw new IOException();
            lock (sync) child = process; // Hold the actual Process handle before any further operation can fail.
            job.Assign(process);
            output = PumpAsync(process.StandardOutput, false, lifetime.Token);
            errors = PumpAsync(process.StandardError, true, lifetime.Token);
            var clock = Stopwatch.StartNew();
            while (clock.Elapsed < TimeSpan.FromSeconds(60))
            {
                lifetime.Token.ThrowIfCancellationRequested();
                if (process.HasExited)
                {
                    await Task.WhenAll(output, errors).ConfigureAwait(false);
                    State(diagnostic.Failure ?? "PROCESS_EXITED"); return;
                }
                bool healthy = false;
                try { await runtime.CheckHealthAsync(lifetime.Token).ConfigureAwait(false); healthy = true; }
                catch (DesktopException) { }
                if (healthy)
                {
                    if (ReadCredential() is null) { if (Snapshot().Failure != "ADMISSION_DENIED") State("CREDENTIAL_UNAVAILABLE"); return; }
                    try { await runtime.CheckCredentialAsync(lifetime.Token).ConfigureAwait(false); }
                    catch (DesktopException) { State("NATIVE_AUTHENTICATION_UNAVAILABLE"); return; }
                    if (process.HasExited) continue;
                    State("READY"); ready = true; break;
                }
                await Task.Delay(250, lifetime.Token).ConfigureAwait(false);
            }
            if (!ready) { State("STARTUP_TIMEOUT"); return; }
            await process.WaitForExitAsync(lifetime.Token).ConfigureAwait(false);
            State("PROCESS_EXITED");
        }
        catch (OperationCanceledException)
        { if (Snapshot().Failure is "STARTING" or "NONE") State(ready ? "PROCESS_EXITED" : "STARTUP_CANCELLED"); }
        catch (DesktopException error)
        { State(error.Error is DesktopError.CredentialMissing or DesktopError.CredentialInvalid ? "CREDENTIAL_UNAVAILABLE" : "STARTUP_CAUSE_UNAVAILABLE"); }
        catch (Exception)
        { State(diagnostic.Failure ?? (child is null ? "ADMISSION_DENIED" : "STARTUP_CAUSE_UNAVAILABLE")); }
        finally
        {
            job?.Dispose();
            Process? owned; lock (sync) owned = child;
            if (owned is not null)
            {
                try
                {
                    if (!owned.HasExited) owned.Kill();
                    using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                    await owned.WaitForExitAsync(cleanup.Token).ConfigureAwait(false);
                }
                catch (InvalidOperationException) { }
            }
            await Task.WhenAny(Task.WhenAll(output, errors), Task.Delay(2000)).ConfigureAwait(false);
            sources?.Dispose();
        }
    }
    private async Task PumpAsync(StreamReader reader, bool parse, CancellationToken cancellation)
    {
        char[] buffer = new char[1024]; var line = new StringBuilder(160);
        int total = 0; bool overlong = false;
        try
        {
            int count;
            while ((count = await reader.ReadAsync(buffer.AsMemory(), cancellation).ConfigureAwait(false)) != 0)
            {
                total += count;
                if (total > 1024 * 1024) { State("STARTUP_CAUSE_UNAVAILABLE"); stop.Cancel(); return; }
                if (!parse) continue;
                for (int i = 0; i < count; i++)
                {
                    char value = buffer[i];
                    if (value == '\n')
                    {
                        if (!overlong)
                        {
                            string message = line.ToString();
                            if (message.EndsWith('\r')) message = message[..^1];
                            diagnostic.Accept(message);
                        }
                        line.Clear(); overlong = false;
                    }
                    else { if (line.Length < 160) line.Append(value); else overlong = true; }
                }
            }
            if (parse && !overlong && line.Length != 0) diagnostic.Accept(line.ToString());
        }
        catch (OperationCanceledException) { }
        catch (IOException) { }
    }
    internal async Task StopAsync()
    {
        stop.Cancel(); Task? work; lock (sync) work = running;
        if (work is not null) await work.ConfigureAwait(false);
    }
    public void Dispose()
    {
        lock (sync) { if (disposed) return; disposed = true; }
        StopAsync().GetAwaiter().GetResult();
        lock (sync) child?.Dispose();
        stop.Dispose();
    }
#else
    public void Dispose() { }
#endif
}
