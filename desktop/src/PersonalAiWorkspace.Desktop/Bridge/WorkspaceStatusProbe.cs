using System;
using System.Linq;
using System.Reflection;
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
        string version = typeof(WorkspaceStatusProbe).Assembly.GetName().Version?.ToString() ?? "1.0.0.0";
        return new(WorkspaceBridge.Version, version, reachable, credential, "Available",
            WorkspaceBridge.NativeMethods.Keys.ToArray());
    }
}
