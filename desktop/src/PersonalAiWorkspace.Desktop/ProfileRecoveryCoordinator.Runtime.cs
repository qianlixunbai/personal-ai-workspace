#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Principal;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    private FileStream? maintenanceRuntimeLock;
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
            if (!ObserveKernelExit(0)) return; // No exit assertion. Cleanup must never fill this event in later.
            var exited = observation with { State = "EXIT_OBSERVED", ExitCode = child.ExitCode,
                ExitUtcTicks = child.ExitTime.ToUniversalTime().Ticks,
                ExitKind = ownerStopRequested ? "OWNER_STOP_REQUESTED" : "EXIT_BEFORE_OWNER_CLEANUP" };
            Publish("exited", exited);
            observation = exited;
        }

        internal void BeginOwnerCleanup()
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (observation.State != "STARTED" || child is null || ObserveKernelExit(0)) return;
            // Persist before Job disposal/Kill. Failure must not prevent the owner's existing
            // cleanup, and neither this intent nor a later exit proves remote model completion.
            var cleanup = observation with { State = "OWNER_CLEANUP_INTENT" };
            Publish("cleanup-intent", cleanup);
            observation = cleanup;
        }

        internal void ObserveExitAfterOwnerCleanup()
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (observation.State != "OWNER_CLEANUP_INTENT" || child is null) return;
            if (!ObserveKernelExit(10000)) return; // A timeout retains only the cleanup intent.
            var exited = observation with { State = "OWNER_CLEANUP_EXIT_OBSERVED", ExitCode = child.ExitCode,
                ExitUtcTicks = child.ExitTime.ToUniversalTime().Ticks, ExitKind = "OWNER_CLEANUP" };
            Publish("cleanup", exited);
            observation = exited;
        }

        private bool ObserveKernelExit(int timeoutMilliseconds)
        {
            if (child is null || child.SafeHandle.IsInvalid || child.SafeHandle.IsClosed) throw Stop();
            // HasExited/WaitForExitAsync may accept an exit code before the process object is
            // signaled (notably Job termination). The bounded synchronous overload waits on
            // our retained kernel handle. Refresh drops any early cached exit code/time.
            if (!child.WaitForExit(timeoutMilliseconds)) return false;
            child.Refresh();
            long exited = child.ExitTime.ToUniversalTime().Ticks;
            if (exited < observation.RuntimeStartUtcTicks) throw Stop();
            return true;
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
        string evidence = Path.Combine(profileRoot, "evidence");
        HoldPrivateDirectory(evidence);
        string model = Path.Combine(profileRoot, "model-state");
        HoldPrivateDirectory(model);
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        // Existing file only: absence cannot be upgraded into evidence of participating clients.
        // Java holds READ + NOSHARE_WRITE/DELETE before returning its Profile/reading credentials.
        var runtimeLock = OpenRecord(Path.Combine(evidence, "runtime-admission.lock"), write: true);
        FileStream? modelLock = null;
        try
        {
            ValidateFileAcl(runtimeLock, user.User!);
            if (runtimeLock.Length != 0) throw Stop();
            modelLock = OpenRecord(Path.Combine(model, ".lock"));
            ValidateFileAcl(modelLock, user.User!);
            if (modelLock.Length != 0) throw Stop();
            // The existing Java writer requests WRITE + NOSHARE_WRITE/DELETE. Our existing-file
            // read handle shares READ only: excludes that writer across sessions, without repair.
            // The early lock above covers participating Java before ModelStateStore. Neither
            // file proves absence of older clients, unregistered executions or external SQL.
            string[] files = Directory.EnumerateFileSystemEntries(model).Take(4).ToArray();
            if (files.Length > 3 || files.Any(file => Path.GetFileName(file)
                is not (".lock" or "active-model.json" or "validation-required.json"))) throw Stop();
            int count = InspectRuntimeStops(profileId);
            held.Add(runtimeLock);
            held.Add(modelLock); // Retain the exclusion until the native coordinator releases its mutex.
            maintenanceRuntimeLock = runtimeLock;
            return new MaintenanceInspection(count);
        }
        catch { modelLock?.Dispose(); runtimeLock.Dispose(); throw; }
    }

    private int InspectRuntimeStops(string profileId)
    {
        var history = ReadRuntimeHistory(profileId);
        if (history.State != "EXIT_OBSERVATIONS_COMPLETE") throw Stop();
        return history.ObservedExitedChildren!.Value;
    }

    // Counts describe only the registered direct children. Even a complete history does not
    // cover legacy/manual writers or prove a provider finished an outstanding model request.
    internal sealed record RuntimeHistoryInspection(string State, int? RegisteredAttempts,
        int? ObservedExitedChildren, int? OwnerCleanupObserved, string[] Issues);

    private RuntimeHistoryInspection InspectRuntimeHistory(string profileId)
    {
        try { return ReadRuntimeHistory(profileId); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or CryptographicException or JsonException)
        {
            // Do not preserve partial counts from an unreadable/corrupt inventory.
            return new("HISTORY_UNAVAILABLE", null, null, null, ["HISTORY_MISSING_OR_UNVERIFIABLE"]);
        }
    }

    private RuntimeHistoryInspection ReadRuntimeHistory(string profileId)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        RequireHex(profileId, 32);
        string recovery = Path.Combine(applicationRoot, "Recovery"), runtimes = Path.Combine(recovery, "runtimes");
        string profile = Path.Combine(runtimes, profileId);
        foreach (string directory in new[] { recovery, runtimes, profile }) HoldPrivateDirectory(directory);
        string[] attempts = Directory.EnumerateFileSystemEntries(profile).Take(4097).ToArray();
        // Missing/empty history is UNKNOWN, not "never ran". No latest-file shortcut over old gaps.
        if (attempts.Length is < 1 or > 4096) throw Stop();
        int exitedCount = 0, cleanupCount = 0;
        var issues = new SortedSet<string>(StringComparer.Ordinal);
        foreach (string directory in attempts)
        {
            string attempt = Path.GetFileName(directory);
            RequireHex(attempt, 32);
            using var pin = WindowsProfileSecurity.HoldDirectories(directory);
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            WindowsProfileSecurity.ValidatePrivate(pin.Handles[^1], user.User!, directory: true);
            string[] files = Directory.EnumerateFileSystemEntries(directory).Take(5).Select(Path.GetFileName).ToArray()!;
            string layout = string.Join(",", files.Order(StringComparer.Ordinal));
            if (layout is not ("intent.dpapi" or "intent.dpapi,started.dpapi" or "exited.dpapi,intent.dpapi,started.dpapi"
                or "cleanup-intent.dpapi,intent.dpapi,started.dpapi" or "cleanup-intent.dpapi,cleanup.dpapi,intent.dpapi,started.dpapi")) throw Stop();
            // Keep every event pinned while comparing the chain. Never reopen a PID from disk.
            var records = new List<FileStream>();
            RuntimeObservation Read(string name)
            {
                var file = OpenRecord(Path.Combine(directory, name + ".dpapi"));
                records.Add(file);
                return ReadRecord<RuntimeObservation>(file, RuntimeEntropy(profileId, attempt, name));
            }
            try
            {
                var intent = Read("intent");
                RequireHex(intent.JarSha256, 64);
                if (intent.Version != 1 || intent.State != "START_INTENT" || intent.ProfileId != profileId || intent.AttemptId != attempt
                    || intent.DesktopPid <= 0 || intent.DesktopStartUtcTicks <= 0 || intent.DesktopSessionId < 0
                    || intent.RuntimePid != 0 || intent.RuntimeStartUtcTicks != 0 || intent.RuntimeSessionId != 0
                    || intent.ExitCode is not null || intent.ExitUtcTicks is not null || intent.ExitKind is not null) throw Stop();
                if (!files.Contains("started.dpapi")) { issues.Add("START_OUTCOME_UNCONFIRMED"); continue; }
                var started = Read("started");
                if (started.RuntimePid <= 0 || started.RuntimeStartUtcTicks <= 0 || started.RuntimeSessionId != intent.DesktopSessionId
                    || started != (intent with { State = "STARTED", RuntimePid = started.RuntimePid,
                        RuntimeStartUtcTicks = started.RuntimeStartUtcTicks, RuntimeSessionId = started.RuntimeSessionId })) throw Stop();
                if (files.Contains("exited.dpapi"))
                {
                    var exited = Read("exited");
                    if (exited.ExitCode is null || exited.ExitUtcTicks is null || exited.ExitUtcTicks < started.RuntimeStartUtcTicks
                        || exited.ExitUtcTicks > DateTime.MaxValue.Ticks
                        || exited.ExitKind is not ("EXIT_BEFORE_OWNER_CLEANUP" or "OWNER_STOP_REQUESTED")
                        || exited != (started with { State = "EXIT_OBSERVED", ExitCode = exited.ExitCode,
                            ExitUtcTicks = exited.ExitUtcTicks, ExitKind = exited.ExitKind })) throw Stop();
                    if (exited.ExitKind == "OWNER_STOP_REQUESTED") issues.Add("OWNER_STOP_REQUESTED");
                    if (exited.ExitCode != 0) issues.Add("CHILD_EXIT_FAILED");
                    if (exited.ExitKind == "EXIT_BEFORE_OWNER_CLEANUP" && exited.ExitCode == 0) exitedCount++;
                }
                else if (files.Contains("cleanup-intent.dpapi"))
                {
                    var cleanup = Read("cleanup-intent");
                    if (cleanup != (started with { State = "OWNER_CLEANUP_INTENT" })) throw Stop();
                    if (!files.Contains("cleanup.dpapi")) { issues.Add("OWNER_CLEANUP_EXIT_UNOBSERVED"); continue; }
                    var exited = Read("cleanup");
                    if (exited.ExitCode is null || exited.ExitUtcTicks is null || exited.ExitUtcTicks < started.RuntimeStartUtcTicks
                        || exited.ExitUtcTicks > DateTime.MaxValue.Ticks
                        || exited != (started with { State = "OWNER_CLEANUP_EXIT_OBSERVED", ExitCode = exited.ExitCode,
                            ExitUtcTicks = exited.ExitUtcTicks, ExitKind = "OWNER_CLEANUP" })) throw Stop();
                    cleanupCount++;
                    issues.Add("OWNER_CLEANUP_NOT_SAFE_STOP");
                }
                else issues.Add("CHILD_EXIT_UNOBSERVED");
            }
            finally { foreach (var file in records) file.Dispose(); }
        }
        return new(issues.Count == 0 ? "EXIT_OBSERVATIONS_COMPLETE" : "RUNTIME_HISTORY_UNRESOLVED",
            attempts.Length, exitedCount, cleanupCount, issues.ToArray());
    }

    private static byte[] RuntimeEntropy(string profileId, string attempt, string name) =>
        Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/runtime/v1/" + profileId + "/" + attempt + "/" + name);
}
#endif
