using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop.Bridge;

// Host observations only. This host does not own the Java process or a trusted startup-error channel.
internal sealed record StorageStartupStatus(string Runtime, string Failure, string ConfigurationTrust,
    bool CanQueryStorage, string ConfigurationAction, bool ConfirmationRequired);

internal static class StorageStartupProbe
{
    internal static async Task<StorageStartupStatus> ReadAsync(WorkspaceStatusProbe probe, CancellationToken cancellation)
    {
        var status = await probe.ReadAsync(cancellation);
        bool running = status.Runtime == ShellRuntimeState.Available;
        bool authenticated = running && status.Credential == ShellCredentialState.Ready;
        return new(running ? "AVAILABLE" : "UNAVAILABLE", !running ? "STARTUP_CAUSE_UNAVAILABLE"
            : authenticated ? "NONE" : "NATIVE_AUTHENTICATION_UNAVAILABLE", "NOT_CHECKED", authenticated,
            "TRUSTED_CONTEXT_REQUIRED", true);
    }
}
