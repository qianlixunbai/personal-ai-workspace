using System;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal sealed class WorkspaceStatusProbe(RuntimeClient runtime, Func<string?> loadCredential)
{
    internal async Task<ShellStatus> ReadAsync(CancellationToken cancellation)
    {
        var reachable = ShellRuntimeState.Unavailable;
        var credential = ShellCredentialState.Unavailable;
        try { await runtime.CheckHealthAsync(cancellation); reachable = ShellRuntimeState.Available; }
        catch (DesktopException) { }
        cancellation.ThrowIfCancellationRequested();
        try
        {
            if (loadCredential() is null) credential = ShellCredentialState.Missing;
            else
            {
                // This establishes authentication only. It does not establish all-model readiness.
                await runtime.CheckCredentialAsync(cancellation);
                credential = ShellCredentialState.Ready;
            }
        }
        catch (DesktopException error)
        {
            credential = error.Error is DesktopError.CredentialInvalid or DesktopError.Unauthorized
                ? ShellCredentialState.Invalid : error.Error == DesktopError.CredentialMissing
                ? ShellCredentialState.Missing : ShellCredentialState.Unavailable;
        }
        var assembly = typeof(WorkspaceStatusProbe).Assembly;
        string version = assembly.GetName().Version?.ToString() ?? "Unknown";
        // Read the running assembly's embedded identity, never the checkout or executable path.
        string? build = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (build is null || build.Length > 100 || !Regex.IsMatch(build, @"\A[0-9]+(?:\.[0-9]+){2,3}\+(?:[0-9a-f]{40}|[0-9a-f]{64})\z")) build = null;
        return new(WorkspaceBridge.Version, version, reachable, credential, "Available",
            WorkspaceBridge.NativeMethods.Keys.ToArray(), build);
    }
}
