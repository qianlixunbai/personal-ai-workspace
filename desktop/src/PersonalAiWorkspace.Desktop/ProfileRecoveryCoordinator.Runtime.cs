#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Principal;
using System.Text;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    // Observations of one owned child, never a certificate that all writers/models stopped.
    // Immutable events retain interrupted starts and owner-forced shutdown ambiguity.
    private sealed record RuntimeObservation(int Version, string State, string ProfileId, string AttemptId,
        string JarSha256, int DesktopPid, long DesktopStartUtcTicks, int DesktopSessionId,
        int RuntimePid, long RuntimeStartUtcTicks, int RuntimeSessionId,
        int? ExitCode, long? ExitUtcTicks, string? ExitKind);

    internal sealed class RuntimeRegistrationIncomplete(string profileId, string attemptId, Exception cause)
        : IOException("Runtime ownership registration requires inspection.", cause)
    {
        internal string ProfileId { get; } = profileId;
        internal string AttemptId { get; } = attemptId;
    }

    internal sealed class RuntimeAttempt : IDisposable
    {
        private readonly ProfileRecoveryCoordinator journal;
        private readonly string directory;
        private RuntimeObservation observation;
        private Process? child;
        private bool disposed;

        private RuntimeAttempt(ProfileRecoveryCoordinator journal, string profileId, string jarSha256)
        {
            this.journal = journal;
            RequireHex(profileId, 32); RequireHex(jarSha256, 64);
            using var desktop = Process.GetCurrentProcess();
            string attempt = Guid.NewGuid().ToString("N");
            observation = new(1, "START_INTENT", profileId, attempt, jarSha256, desktop.Id,
                desktop.StartTime.ToUniversalTime().Ticks, desktop.SessionId, 0, 0, 0, null, null, null);
            directory = Path.Combine(journal.applicationRoot, "Recovery", "runtimes", profileId, attempt);
            try
            {
                journal.EnsurePrivateDirectory(Path.Combine(journal.applicationRoot, "Recovery"));
                // Preserve Slice 2a's rule: an incomplete Recovery root denies ordinary startup.
                journal.EnsurePrivateDirectory(Path.Combine(journal.applicationRoot, "Recovery", "targets"));
                journal.EnsurePrivateDirectory(Path.GetDirectoryName(Path.GetDirectoryName(directory)!)!);
                journal.EnsurePrivateDirectory(Path.GetDirectoryName(directory)!);
                using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
                WindowsProfileSecurity.CreateDirectory(directory, user.User!);
                journal.HoldPrivateDirectory(directory);
                Publish("intent", observation);
            }
            catch (Exception failure) { throw new RuntimeRegistrationIncomplete(profileId, attempt, failure); }
        }

        internal static RuntimeAttempt Begin(Mmf3AcceptanceLaunch profile, string jarSha256)
        {
            // Called by the already admitted native owner before Process.Start. No second mutex
            // acquisition on the worker thread, and no caller-supplied product filesystem root.
            profile.Validate();
            Mmf3ProfileNative.Verify(profile);
            var journal = new ProfileRecoveryCoordinator(Path.GetDirectoryName(WindowsProfileSecurity.Parent())!);
            try
            {
                journal.held.Add(Mmf3ProfileState.Pin(profile.RunId));
                profile.Validate();
                return new RuntimeAttempt(journal, profile.RunId, jarSha256);
            }
            catch { journal.Dispose(); throw; }
        }

        internal void RecordStarted(Process process)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (child is not null || observation.State != "START_INTENT") throw Stop();
            // Retain the Process returned by Start, never reopen a PID from disk. Its OS handle
            // binds StartTime/ExitTime even if Windows later reuses that PID.
            _ = process.SafeHandle;
            child = process;
            var started = observation with { State = "STARTED", RuntimePid = process.Id,
                RuntimeStartUtcTicks = process.StartTime.ToUniversalTime().Ticks,
                RuntimeSessionId = process.SessionId };
            if (started.RuntimePid <= 0 || started.RuntimeStartUtcTicks <= 0
                || started.RuntimeSessionId != started.DesktopSessionId) throw Stop();
            Publish("started", started);
            observation = started;
        }

        internal void ObserveExitBeforeOwnerCleanup(bool ownerStopRequested)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (observation.State != "STARTED" || child is null) throw Stop();
            if (!child.HasExited) return; // No exit assertion. Cleanup/kill must never fill it in later.
            var exited = observation with { State = "EXIT_OBSERVED", ExitCode = child.ExitCode,
                ExitUtcTicks = child.ExitTime.ToUniversalTime().Ticks,
                ExitKind = ownerStopRequested ? "OWNER_STOP_REQUESTED" : "EXIT_BEFORE_OWNER_CLEANUP" };
            Publish("exited", exited);
            observation = exited;
        }

        private void Publish(string name, RuntimeObservation value)
        {
            byte[] entropy = RuntimeEntropy(value.ProfileId, value.AttemptId, name);
            PublishRecord(directory, name, entropy, value);
            if (ReadRecord<RuntimeObservation>(Path.Combine(directory, name + ".dpapi"), entropy) != value) throw Stop();
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            // Disposal records nothing, kills nothing, and never disposes the owner's child.
            journal.Dispose();
        }
    }

    // No successful admission value/capability exists in this slice. This bounded, read-only
    // inspection can explain missing prerequisites but cannot authorize source/target operations.
    internal sealed record MaintenanceInspection(int ObservedExitedChildren)
    {
        internal string Blocker => "UNREGISTERED_WRITERS_UNPROVEN";
    }

    internal MaintenanceInspection InspectMaintenancePrerequisites()
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (authority is null) throw Stop();
        authority.Validate();
        Mmf3ProfileNative.Verify(authority);
        return InspectMaintenancePrerequisites(authority.RunId, authority.Root);
    }

    // Private surrogate seam for the owning test; production always derives and verifies authority.
    private MaintenanceInspection InspectMaintenancePrerequisites(string profileId, string profileRoot)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        RequireHex(profileId, 32);
        string model = Path.Combine(profileRoot, "model-state");
        HoldPrivateDirectory(model);
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        var modelLock = OpenRecord(Path.Combine(model, ".lock"));
        try
        {
            ValidateFileAcl(modelLock, user.User!);
            if (modelLock.Length != 0) throw Stop();
            // The existing Java writer requests WRITE + NOSHARE_WRITE/DELETE. Our existing-file
            // read handle shares READ only: excludes that writer across sessions, without repair.
            // It does NOT exclude Java before ModelStateStore, older clients or external SQL.
            string[] files = Directory.EnumerateFileSystemEntries(model).Take(4).ToArray();
            if (files.Length > 3 || files.Any(file => Path.GetFileName(file)
                is not (".lock" or "active-model.json" or "validation-required.json"))) throw Stop();
            int count = InspectRuntimeStops(profileId);
            held.Add(modelLock); // Retain the exclusion until the native coordinator releases its mutex.
            return new MaintenanceInspection(count);
        }
        catch { modelLock.Dispose(); throw; }
    }

    private int InspectRuntimeStops(string profileId)
    {
        RequireHex(profileId, 32);
        string recovery = Path.Combine(applicationRoot, "Recovery"), runtimes = Path.Combine(recovery, "runtimes");
        string profile = Path.Combine(runtimes, profileId);
        foreach (string directory in new[] { recovery, runtimes, profile }) HoldPrivateDirectory(directory);
        string[] attempts = Directory.EnumerateFileSystemEntries(profile).Take(4097).ToArray();
        // Missing/empty history is UNKNOWN, not "never ran". No latest-file shortcut over old gaps.
        if (attempts.Length is < 1 or > 4096) throw Stop();
        foreach (string directory in attempts)
        {
            string attempt = Path.GetFileName(directory);
            RequireHex(attempt, 32);
            using var pin = WindowsProfileSecurity.HoldDirectories(directory);
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            WindowsProfileSecurity.ValidatePrivate(pin.Handles[^1], user.User!, directory: true);
            string[] files = Directory.EnumerateFileSystemEntries(directory).Take(4).Select(Path.GetFileName).ToArray()!;
            if (files.Length != 3 || !files.Order(StringComparer.Ordinal).SequenceEqual(
                new[] { "exited.dpapi", "intent.dpapi", "started.dpapi" })) throw Stop();
            RuntimeObservation Read(string name) => ReadRecord<RuntimeObservation>(Path.Combine(directory, name + ".dpapi"),
                RuntimeEntropy(profileId, attempt, name));
            var intent = Read("intent"); var started = Read("started"); var exited = Read("exited");
            RequireHex(intent.JarSha256, 64);
            if (intent.Version != 1 || intent.State != "START_INTENT" || intent.ProfileId != profileId || intent.AttemptId != attempt
                || intent.DesktopPid <= 0 || intent.DesktopStartUtcTicks <= 0 || intent.DesktopSessionId < 0
                || intent.RuntimePid != 0 || intent.RuntimeStartUtcTicks != 0 || intent.RuntimeSessionId != 0
                || intent.ExitCode is not null || intent.ExitUtcTicks is not null || intent.ExitKind is not null) throw Stop();
            if (started.RuntimePid <= 0 || started.RuntimeStartUtcTicks <= 0 || started.RuntimeSessionId != intent.DesktopSessionId
                || started != (intent with { State = "STARTED", RuntimePid = started.RuntimePid,
                    RuntimeStartUtcTicks = started.RuntimeStartUtcTicks, RuntimeSessionId = started.RuntimeSessionId })) throw Stop();
            if (exited.ExitCode != 0 || exited.ExitUtcTicks is null || exited.ExitUtcTicks < started.RuntimeStartUtcTicks
                || exited != (started with { State = "EXIT_OBSERVED", ExitCode = 0, ExitUtcTicks = exited.ExitUtcTicks,
                    ExitKind = "EXIT_BEFORE_OWNER_CLEANUP" })) throw Stop();
        }
        return attempts.Length;
    }

    private static byte[] RuntimeEntropy(string profileId, string attempt, string name) =>
        Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/runtime/v1/" + profileId + "/" + attempt + "/" + name);
}
#endif
