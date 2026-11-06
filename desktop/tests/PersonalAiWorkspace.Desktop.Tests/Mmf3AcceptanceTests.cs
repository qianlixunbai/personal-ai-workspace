using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Principal;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Hosting;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class Mmf3AcceptanceTests
{
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    [Fact]
    public async Task RuntimeExitEvidenceNeverAuthorizesMaintenanceOrHidesInterruptedOwners()
    {
        // The same entry handler used before normal startup rejects malformed/versioned requests
        // before Known Folder/Profile/helper access. The reporter never opens an actual window.
        string requestId = new string('a', 32);
        Assert.Equal(requestId, ProfileRecoveryCoordinator.ParseInspectionRequest(
            [ProfileRecoveryCoordinator.InspectionArgument, requestId])); // Pure syntax; no admission.
        foreach (string[] args in new[] { Array.Empty<string>(), new[] { ProfileRecoveryCoordinator.InspectionArgument },
            new[] { "--inspect-profile-recovery-v2", requestId },
            new[] { ProfileRecoveryCoordinator.InspectionArgument, requestId.ToUpperInvariant() },
            new[] { ProfileRecoveryCoordinator.InspectionArgument, "../" + requestId },
            new[] { ProfileRecoveryCoordinator.InspectionArgument, requestId, "--restore" },
            new[] { ProfileRecoveryCoordinator.InspectionArgument, requestId, "--root", @"C:\arbitrary" } })
        {
            string? message = null;
            Assert.Equal(2, Program.InspectRecoveryMaintenance(args, value => message = value));
            Assert.Contains("INVALID_REQUEST", message);
            Assert.DoesNotContain(requestId, message);
        }
        // Only newly created private surrogate folders and one hidden, stdin-controlled cmd
        // child. No Profile Known Folder/helper, Java, model, database or existing process access.
        string app = Path.Combine(Path.GetTempPath(), "paw-recovery-runtime-" + Guid.NewGuid().ToString("N"));
        var sid = WindowsIdentity.GetCurrent().User!;
        void DirectoryNew(string path) => PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.CreateDirectory(path, sid);
        DirectoryNew(app);
        string id = Guid.NewGuid().ToString("N");
        string profiles = Path.Combine(app, "Profiles"), profile = Path.Combine(profiles, id), model = Path.Combine(profile, "model-state");
        DirectoryNew(profiles); DirectoryNew(profile); DirectoryNew(model);
        string evidence = Path.Combine(profile, "evidence"); DirectoryNew(evidence);
        string earlyLock = Path.Combine(evidence, "runtime-admission.lock");
        string modelLock = Path.Combine(model, ".lock");
        File.WriteAllBytes(modelLock, []);
        Console.WriteLine("Synthetic runtime ownership fixture retained: " + app);
        ProfileRecoveryCoordinator Journal() => (ProfileRecoveryCoordinator)typeof(ProfileRecoveryCoordinator)
            .GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!.Invoke([app]);
        ProfileRecoveryCoordinator.RuntimeAttempt Attempt() => (ProfileRecoveryCoordinator.RuntimeAttempt)
            typeof(ProfileRecoveryCoordinator.RuntimeAttempt).GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null,
                [typeof(ProfileRecoveryCoordinator), typeof(string), typeof(string)], null)!.Invoke([Journal(), id, new string('a', 64)]);
        ProfileRecoveryCoordinator.MaintenanceInspection Inspect(ProfileRecoveryCoordinator journal)
        {
            try
            {
                return (ProfileRecoveryCoordinator.MaintenanceInspection)typeof(ProfileRecoveryCoordinator)
                    .GetMethod("InspectMaintenancePrerequisites", BindingFlags.NonPublic | BindingFlags.Instance, null,
                        [typeof(string), typeof(string)], null)!.Invoke(journal, [id, profile])!;
            }
            catch (TargetInvocationException error)
            { System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException!).Throw(); throw; }
        }
        void Denied() { using var journal = Journal(); Assert.ThrowsAny<IOException>(() => Inspect(journal)); }
        Denied(); Assert.False(File.Exists(earlyLock)); // Maintenance never creates missing enrollment state.
        File.WriteAllBytes(earlyLock, []);
        Denied(); // Missing history is not "never ran".
        using (var attempt = Attempt())
        {
            Denied(); // Durable intent, but no child identity yet.
            using var child = new Process { StartInfo = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"))
                { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
                    RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = app } };
            child.StartInfo.ArgumentList.Add("/d"); child.StartInfo.ArgumentList.Add("/q");
            Assert.True(child.Start());
            try
            {
                attempt.RecordStarted(child);
                attempt.ObserveExitBeforeOwnerCleanup(false);
                Denied(); // A live handle produces no exit event, regardless of a free port.
                await child.StandardInput.WriteLineAsync("exit 0"); await child.StandardInput.FlushAsync();
                using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                await child.WaitForExitAsync(deadline.Token);
                attempt.ObserveExitBeforeOwnerCleanup(false);
                Assert.Throws<IOException>(() => attempt.ObserveExitBeforeOwnerCleanup(false)); // No repeat publication.
            }
            finally { if (!child.HasExited) { child.Kill(); child.WaitForExit(5000); } } // Only our synthetic child.
        }
        // Close/reopen the DPAPI journal. Even intact exit observations do not yield admission.
        using (var journal = Journal())
        {
            var result = Inspect(journal);
            Assert.Equal(1, result.ObservedExitedChildren);
            Assert.Equal("UNREGISTERED_WRITERS_UNPROVEN", result.Blocker);
            // Same READ/share-READ contract used by Java's early lease: the maintenance WRITE
            // handle excludes it before model-state or credential reads can begin.
            Assert.Throws<IOException>(() => new FileStream(earlyLock, FileMode.Open, FileAccess.Read, FileShare.Read));
            Assert.Throws<IOException>(() => new FileStream(modelLock, FileMode.Open, FileAccess.Write, FileShare.ReadWrite));
            Assert.Throws<IOException>(() => Directory.Move(model, Path.Combine(profile, "moved")));
            Assert.Throws<IOException>(() => Directory.Move(evidence, Path.Combine(profile, "moved-evidence")));
        }
        using (var earlyReader = new FileStream(earlyLock, FileMode.Open, FileAccess.Read, FileShare.Read)) Denied();
        File.WriteAllText(earlyLock, "synthetic malformed lock"); Denied();
        Assert.Equal("synthetic malformed lock", File.ReadAllText(earlyLock));
        File.WriteAllBytes(earlyLock, []); // Restore only this test-owned fixture for guard checks.
        using (var writer = new FileStream(modelLock, FileMode.Open, FileAccess.Write, FileShare.Read)) Denied();
        foreach (string name in new[] { "execution-guard.json", "execution-guard.pending", "validation-required.pending", "unknown" })
        {
            string guard = Path.Combine(model, name);
            File.WriteAllText(guard, "synthetic unresolved evidence");
            Denied(); Assert.Equal("synthetic unresolved evidence", File.ReadAllText(guard));
            File.Delete(guard); // Only the exact newly created test artifact; production never clears it.
        }
        string history = Path.Combine(app, "Recovery", "runtimes", id);
        string directory = Assert.Single(Directory.GetDirectories(history));
        string exitFile = Path.Combine(directory, "exited.dpapi");
        byte[] encrypted = File.ReadAllBytes(exitFile);
        byte[] entropy = System.Text.Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/runtime/v1/" + id + "/" + Path.GetFileName(directory) + "/exited");
        byte[] plain = System.Security.Cryptography.ProtectedData.Unprotect(encrypted, entropy, System.Security.Cryptography.DataProtectionScope.CurrentUser);
        string json = System.Text.Encoding.UTF8.GetString(plain);
        System.Security.Cryptography.CryptographicOperations.ZeroMemory(plain);
        Assert.DoesNotContain(id, System.Text.Encoding.UTF8.GetString(encrypted));
        foreach (string altered in new[] { json.Replace("EXIT_BEFORE_OWNER_CLEANUP", "OWNER_STOP_REQUESTED"),
            json.Replace("\"ExitCode\":0", "\"ExitCode\":1"), json.Replace(id, Guid.NewGuid().ToString("N")),
            json.Replace("\"Version\":1", "\"Version\":2"),
            json.Replace("\"Version\":1", "\"Version\":1,\"Version\":1"),
            json.Replace("\"Version\":1", "\"Version\":1,\"Unknown\":true") })
        {
            File.WriteAllBytes(exitFile, System.Security.Cryptography.ProtectedData.Protect(System.Text.Encoding.UTF8.GetBytes(altered), entropy,
                System.Security.Cryptography.DataProtectionScope.CurrentUser));
            using var journal = Journal();
            var failure = Record.Exception(() => Inspect(journal));
            Assert.True(failure is IOException or System.Text.Json.JsonException);
        }
        File.WriteAllBytes(exitFile, [1, 2, 3]);
        using (var journal = Journal()) Assert.Throws<System.Security.Cryptography.CryptographicException>(() => Inspect(journal));
        File.WriteAllBytes(exitFile, encrypted);
        string pending = Path.Combine(directory, "exited.pending");
        File.WriteAllText(pending, "synthetic interrupted publication"); Denied(); File.Delete(pending);
        using (var journal = Journal()) Assert.Equal(1, Inspect(journal).ObservedExitedChildren);
        // An unfinished later intent is retained; disposal publishes no exit.
        using (Attempt()) { }
        Denied();
        ProfileRecoveryCoordinator.RequireOrdinaryProfile(profile); // No change to ordinary Profile selection.
        Assert.Empty(Directory.EnumerateFileSystemEntries(Path.Combine(app, "Recovery", "targets")));
        Assert.False(Directory.Exists(Path.Combine(profile, "data")));
        Assert.False(Directory.Exists(Path.Combine(profile, "credentials")));
    }

    [Fact]
    public void BackupIntentPinsSyntheticSourceAndNeverAuthorizesBackupOrRecovery()
    {
        // New private temporary directories only. No Known Folder, real Profile, native helper,
        // database, Knowledge content, Runtime or model process is accessed by this test.
        string app = Path.Combine(Path.GetTempPath(), "paw-backup-intent-" + Guid.NewGuid().ToString("N"));
        Assert.False(Path.Exists(app));
        void DirectoryNew(string path) => PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.CreateDirectory(path, WindowsIdentity.GetCurrent().User!);
        DirectoryNew(app);
        Console.WriteLine("Synthetic backup intent fixture retained: " + app);
        string source = Guid.NewGuid().ToString("N"), profiles = Path.Combine(app, "Profiles");
        string root = Path.Combine(profiles, source), data = Path.Combine(root, "data");
        DirectoryNew(profiles); DirectoryNew(root); DirectoryNew(data);
        ProfileRecoveryCoordinator Open() => (ProfileRecoveryCoordinator)typeof(ProfileRecoveryCoordinator)
            .GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!.Invoke([app]);
        var reserve = typeof(ProfileRecoveryCoordinator).GetMethod("ReserveBackupIntentForProfile", BindingFlags.NonPublic | BindingFlags.Instance)!;
        ProfileRecoveryCoordinator.BackupIntent Reserve(ProfileRecoveryCoordinator journal) =>
            (ProfileRecoveryCoordinator.BackupIntent)reserve.Invoke(journal, [source])!;
        string recovery = Path.Combine(app, "Recovery"), intents = Path.Combine(recovery, "source-intents");
        ProfileRecoveryCoordinator.BackupIntent intent;
        using (var journal = Open())
        {
            Assert.Throws<IOException>(() => journal.ReserveBackupIntent()); // No admitted authority in a surrogate.
            Assert.False(Directory.Exists(recovery));
            intent = Reserve(journal);
            Assert.Matches("^[0-9a-f]{32}$", intent.BackupId);
            Assert.NotEqual(source, intent.BackupId);
            Assert.Equal(source, intent.SourceProfileId);
            Assert.Matches("^[0-9a-f]{48}$", intent.SourceRootIdentity);
            Assert.Matches("^[0-9a-f]{48}$", intent.SourceDataIdentity);
            Assert.NotEqual(intent.SourceRootIdentity, intent.SourceDataIdentity);
            Assert.Equal("BackupIntent[private]", intent.ToString());
            Assert.Equal(intent, journal.InspectBackupIntent(intent.BackupId));
            Assert.Throws<IOException>(() => Directory.Move(data, data + "-moved"));
            Assert.Throws<IOException>(() => Directory.Move(root, root + "-moved"));
            Assert.IsAssignableFrom<IOException>(Assert.Throws<TargetInvocationException>(() => Reserve(journal)).InnerException);
            Assert.Single(Directory.GetDirectories(intents));
            Assert.Throws<IOException>(() => journal.ReservePlan(intent.BackupId, new string('a', 64), new string('b', 64)));
            Assert.False(Directory.Exists(Path.Combine(recovery, "sources")));
            Assert.Empty(Directory.EnumerateFileSystemEntries(Path.Combine(recovery, "targets")));
            Assert.Empty(Directory.EnumerateFileSystemEntries(data));
            ProfileRecoveryCoordinator.RequireOrdinaryProfile(root);
        }
        string directory = Path.Combine(intents, intent.BackupId), file = Path.Combine(directory, "intent.dpapi");
        byte[] encrypted = File.ReadAllBytes(file);
        Assert.DoesNotContain(source, System.Text.Encoding.UTF8.GetString(encrypted));
        // Reconciliation does not require the original source to remain present or to be opened.
        Directory.Move(profiles, profiles + "-offline");
        using (var journal = Open())
        {
            Assert.Equal(intent, journal.InspectBackupIntent(intent.BackupId));
            byte[] entropy = System.Text.Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/source-intent/v1/" + intent.BackupId);
            foreach (var altered in new[] { intent with { State = "BACKUP_REGISTERED" },
                intent with { BackupId = Guid.NewGuid().ToString("N") } })
            {
                File.WriteAllBytes(file, System.Security.Cryptography.ProtectedData.Protect(
                    System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(altered), entropy, System.Security.Cryptography.DataProtectionScope.CurrentUser));
                Assert.Throws<IOException>(() => journal.InspectBackupIntent(intent.BackupId));
            }
            File.WriteAllBytes(file, encrypted); // Only restore this new synthetic fixture.
            string pending = Path.Combine(directory, "intent.pending");
            File.WriteAllText(pending, "synthetic interrupted publication");
            Assert.Throws<IOException>(() => journal.InspectBackupIntent(intent.BackupId));
            Assert.Equal("synthetic interrupted publication", File.ReadAllText(pending));
            Assert.Equal(encrypted, File.ReadAllBytes(file));
        }
        Directory.Move(profiles + "-offline", profiles);
        // Force failure before the new intent directory can be published. The generated identity
        // must be recoverable from the error, and the same owner cannot silently allocate another.
        Directory.Move(intents, intents + "-retained");
        File.WriteAllText(intents, "synthetic malformed intent registry");
        using var broken = Open();
        var incomplete = Assert.IsType<ProfileRecoveryCoordinator.BackupIntentIncomplete>(
            Assert.Throws<TargetInvocationException>(() => Reserve(broken)).InnerException);
        Assert.Equal("PREPARING", incomplete.Attempt.State);
        Assert.NotEqual(intent.BackupId, incomplete.Attempt.BackupId);
        Assert.Equal(intent.SourceRootIdentity, incomplete.Attempt.SourceRootIdentity);
        Assert.Equal(intent.SourceDataIdentity, incomplete.Attempt.SourceDataIdentity);
        Assert.IsAssignableFrom<IOException>(Assert.Throws<TargetInvocationException>(() => Reserve(broken)).InnerException);
        Assert.Equal("synthetic malformed intent registry", File.ReadAllText(intents));
        Assert.False(Directory.Exists(Path.Combine(recovery, "sources")));
    }

    [Fact]
    public void RecoveryPlanIsPrivateDurableNonAuthorizingAndInterruptedCandidatesCannotStart()
    {
        // A fresh temporary surrogate application tree only; never call Parent/Root/Prepare,
        // OpenForPlanning, NativeBootstrap, a Runtime, a database, or the user's preferences.
        string app = Path.Combine(Path.GetTempPath(), "paw-recovery-plan-" + Guid.NewGuid().ToString("N"));
        Assert.False(Path.Exists(app));
        PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.CreateDirectory(app, WindowsIdentity.GetCurrent().User!);
        Console.WriteLine("Synthetic recovery plan fixture retained: " + app);
        string source = Guid.NewGuid().ToString("N"), backup = Guid.NewGuid().ToString("N");
        string preference = Path.Combine(app, "last-workspace");
        File.WriteAllText(preference, source);
        string Profile(string id) => Path.Combine(app, "Profiles", id);
        ProfileRecoveryCoordinator Open() => (ProfileRecoveryCoordinator)typeof(ProfileRecoveryCoordinator)
            .GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!.Invoke([app]);
        void DirectoryNew(string path) => PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.CreateDirectory(path, WindowsIdentity.GetCurrent().User!);
        string recovery = Path.Combine(app, "Recovery"), targets = Path.Combine(recovery, "targets");
        string sources = Path.Combine(recovery, "sources"), sourceDirectory = Path.Combine(sources, backup);
        string sourceFile = Path.Combine(sourceDirectory, "source.dpapi");
        var registration = new ProfileRecoveryCoordinator.SourceRegistration(1, "WORKSPACE_BACKUP_SOURCE", "BACKUP_REGISTERED",
            backup, source, Guid.NewGuid().ToString("D"), new string('1', 48), new string('2', 48), 123, 456, 15432,
            "synthetic_source", "synthetic_app", "personal-ai-workspace.postgresql-encrypted-stream", 3, 17,
            "WORKSPACE_KNOWLEDGE_ONLY", 512, new string('a', 64), new string('b', 64), "RECLAIMED");
        byte[] sourceEntropy = System.Text.Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/source/v1/" + backup);
        // Test-only fixture writer. The product has no API which can sign off caller-supplied
        // metadata as a completed backup; real publication still depends on the maintenance owner.
        void WriteSource(string json) => File.WriteAllBytes(sourceFile, System.Security.Cryptography.ProtectedData.Protect(
            System.Text.Encoding.UTF8.GetBytes(json), sourceEntropy, System.Security.Cryptography.DataProtectionScope.CurrentUser));
        string sourceJson = System.Text.Json.JsonSerializer.Serialize(registration);
        ProfileRecoveryCoordinator.Plan plan;
        ProfileRecoveryCoordinator.RequireOrdinaryProfile(Profile(source));
        using (var journal = Open())
        {
            Assert.Throws<IOException>(() => journal.ReservePlan(backup, new string('a', 64), new string('b', 64)));
            Assert.False(Directory.Exists(recovery)); // No repair or candidate side effects on missing trust.
            DirectoryNew(recovery); DirectoryNew(targets); DirectoryNew(sources); DirectoryNew(sourceDirectory);
            Assert.Throws<IOException>(() => journal.ReservePlan(backup, new string('a', 64), new string('b', 64)));
            foreach (string altered in new[] { sourceJson.Replace("BACKUP_REGISTERED", "INCOMPLETE"),
                sourceJson.Replace("RECLAIMED", "CREDENTIAL_CLEANUP_REQUIRED"),
                sourceJson.Replace("WORKSPACE_KNOWLEDGE_ONLY", "FINANCE_AND_WORKSPACE"),
                sourceJson.Replace("\"FormatVersion\":3", "\"FormatVersion\":2"),
                sourceJson.Replace(backup, Guid.NewGuid().ToString("N")),
                sourceJson.Replace("\"Version\":1", "\"Version\":1,\"Version\":1"),
                sourceJson.Replace("\"Version\":1", "\"Version\":1,\"Unknown\":true"),
                sourceJson.Replace("\"SourcePort\":15432", "\"SourcePort\":0") })
            {
                WriteSource(altered);
                var failure = Record.Exception(() => journal.ReservePlan(backup, new string('a', 64), new string('b', 64)));
                Assert.True(failure is IOException or System.Text.Json.JsonException);
                Assert.Empty(Directory.EnumerateFileSystemEntries(targets));
            }
            File.WriteAllBytes(sourceFile, [1, 2, 3]);
            Assert.Throws<System.Security.Cryptography.CryptographicException>(() => journal.ReservePlan(backup, new string('a', 64), new string('b', 64)));
            WriteSource(sourceJson);
            Assert.Throws<IOException>(() => journal.ReservePlan(backup, new string('c', 64), new string('b', 64)));
            Assert.Throws<IOException>(() => journal.ReservePlan(backup, new string('a', 64), new string('c', 64)));
            string sourcePending = Path.Combine(sourceDirectory, "source.pending");
            File.WriteAllText(sourcePending, "synthetic interrupted source publication");
            Assert.Throws<IOException>(() => journal.ReservePlan(backup, new string('a', 64), new string('b', 64)));
            Assert.Empty(Directory.EnumerateFileSystemEntries(targets));
            File.Delete(sourcePending); // Only this fresh synthetic fixture; product never repairs it.
            var pinSource = typeof(ProfileRecoveryCoordinator).GetMethod("PinRegisteredSource", BindingFlags.NonPublic | BindingFlags.Instance)!;
            using ((IDisposable)pinSource.Invoke(journal, [backup, new string('a', 64), new string('b', 64)])!)
            {
                Assert.Throws<IOException>(() => new FileStream(sourceFile, FileMode.Open, FileAccess.Write, FileShare.Read));
                Assert.Throws<IOException>(() => File.Delete(sourceFile));
                Assert.Throws<IOException>(() => Directory.Move(sourceDirectory, sourceDirectory + "-moved"));
            }
            plan = journal.ReservePlan(backup, new string('a', 64), new string('b', 64));
            Assert.Equal(2, plan.Version);
            Assert.Equal(source, plan.SourceProfileId);
            Assert.Equal(Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(sourceFile))), plan.SourceRegistrationSha256);
            Assert.Equal("SourceRegistration[private]", registration.ToString());
            Assert.Equal(plan, journal.Inspect(plan.TargetProfileId));
            Assert.Throws<IOException>(() => Directory.Move(Path.Combine(app, "Recovery"), Path.Combine(app, "moved")));
            Assert.Throws<IOException>(() => ProfileRecoveryCoordinator.RequireOrdinaryProfile(Profile(plan.TargetProfileId)));
            ProfileRecoveryCoordinator.RequireOrdinaryProfile(Profile(source));
        }
        Assert.False(Directory.Exists(Path.Combine(app, "Profiles"))); // Reservation creates no actual target.
        Assert.Equal(source, File.ReadAllText(preference));
        string reservation = Path.Combine(app, "Recovery", "targets", plan.TargetProfileId);
        string file = Path.Combine(reservation, "plan.dpapi");
        byte[] ciphertext = File.ReadAllBytes(file);
        Assert.DoesNotContain(plan.SourceProfileId, System.Text.Encoding.UTF8.GetString(ciphertext));
        using var reopened = Open();
        Assert.Equal(plan, reopened.Inspect(plan.TargetProfileId)); // Real DPAPI after handles close/reopen.
        var publish = typeof(ProfileRecoveryCoordinator).GetMethod("PublishPlan", BindingFlags.NonPublic | BindingFlags.Instance)!;
        Assert.IsAssignableFrom<IOException>(Assert.Throws<TargetInvocationException>(() => publish.Invoke(reopened, [plan])).InnerException);
        Assert.Equal(ciphertext, File.ReadAllBytes(file)); // No-replace, even for an identical completed plan.

        byte[] entropy = System.Text.Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/plan/v1/" + plan.TargetProfileId);
        byte[] plain = System.Security.Cryptography.ProtectedData.Unprotect(ciphertext, entropy, System.Security.Cryptography.DataProtectionScope.CurrentUser);
        string json = System.Text.Encoding.UTF8.GetString(plain);
        System.Security.Cryptography.CryptographicOperations.ZeroMemory(plain);
        // Correct DPAPI cannot turn unknown/duplicate fields, another target or ACTIVE into authority.
        foreach (string altered in new[] { json.Replace("PREPARING", "ACTIVE"), json.Replace("\"Version\":2", "\"Version\":99"),
            json.Replace("\"Version\":2", "\"Version\":2,\"Version\":2"), json.Replace("\"Version\":2", "\"Version\":2,\"Unknown\":true"),
            json.Replace(plan.SourceRegistrationSha256!, "invalid"),
            json.Replace(plan.TargetProfileId, Guid.NewGuid().ToString("N")) })
        {
            File.WriteAllBytes(file, System.Security.Cryptography.ProtectedData.Protect(System.Text.Encoding.UTF8.GetBytes(altered), entropy,
                System.Security.Cryptography.DataProtectionScope.CurrentUser));
            var failure = Record.Exception(() => reopened.Inspect(plan.TargetProfileId));
            Assert.True(failure is IOException or System.Text.Json.JsonException);
            Assert.Throws<IOException>(() => ProfileRecoveryCoordinator.RequireOrdinaryProfile(Profile(plan.TargetProfileId)));
        }
        File.WriteAllBytes(file, [1, 2, 3]);
        Assert.Throws<System.Security.Cryptography.CryptographicException>(() => reopened.Inspect(plan.TargetProfileId));
        // v1 intentions remain inspectable without a source binding, but cannot start a target.
        var legacy = plan with { Version = 1, SourceRegistrationSha256 = null };
        string legacyJson = System.Text.Json.JsonSerializer.Serialize(legacy).Replace(",\"SourceRegistrationSha256\":null", "");
        File.WriteAllBytes(file, System.Security.Cryptography.ProtectedData.Protect(System.Text.Encoding.UTF8.GetBytes(legacyJson), entropy,
            System.Security.Cryptography.DataProtectionScope.CurrentUser));
        Assert.Equal(legacy, reopened.Inspect(plan.TargetProfileId));
        Assert.Throws<IOException>(() => ProfileRecoveryCoordinator.RequireOrdinaryProfile(Profile(plan.TargetProfileId)));
        File.WriteAllBytes(file, ciphertext); // Restore only this test's synthetic record for interrupted-publication check.
        File.WriteAllText(Path.Combine(reservation, "plan.pending"), "synthetic interrupted publication");
        Assert.Throws<IOException>(() => reopened.Inspect(plan.TargetProfileId));
        Assert.Throws<IOException>(() => ProfileRecoveryCoordinator.RequireOrdinaryProfile(Profile(plan.TargetProfileId)));
        string emptyTarget = Guid.NewGuid().ToString("N");
        PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.CreateDirectory(Path.Combine(app, "Recovery", "targets", emptyTarget), WindowsIdentity.GetCurrent().User!);
        Assert.Throws<IOException>(() => reopened.Inspect(emptyTarget));
        Assert.Throws<IOException>(() => ProfileRecoveryCoordinator.RequireOrdinaryProfile(Profile(emptyTarget)));
        Assert.Equal(source, File.ReadAllText(preference));

        // A failure before/during publication carries a stable attempt identity for inspection.
        // This fresh surrogate has a malformed registry; no existing application files are used.
        string brokenApp = Path.Combine(Path.GetTempPath(), "paw-recovery-plan-" + Guid.NewGuid().ToString("N"));
        PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.CreateDirectory(brokenApp, WindowsIdentity.GetCurrent().User!);
        string brokenRecovery = Path.Combine(brokenApp, "Recovery"), brokenSources = Path.Combine(brokenRecovery, "sources");
        DirectoryNew(brokenRecovery); DirectoryNew(brokenSources); DirectoryNew(Path.Combine(brokenSources, backup));
        File.Copy(sourceFile, Path.Combine(brokenSources, backup, "source.dpapi"));
        File.WriteAllText(Path.Combine(brokenRecovery, "targets"), "synthetic malformed targets registry");
        using var broken = (ProfileRecoveryCoordinator)typeof(ProfileRecoveryCoordinator)
            .GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!.Invoke([brokenApp]);
        var incomplete = Assert.Throws<ProfileRecoveryCoordinator.ReservationIncomplete>(() =>
            broken.ReservePlan(backup, new string('a', 64), new string('b', 64)));
        Assert.Equal("PREPARING", incomplete.Attempt.State);
        Assert.Equal(32, incomplete.Attempt.OperationId.Length);
        Assert.Equal(32, incomplete.Attempt.TargetProfileId.Length);
        Assert.ThrowsAny<IOException>(() => ProfileRecoveryCoordinator.RequireOrdinaryProfile(Path.Combine(brokenApp, "Profiles", source)));
        Assert.False(Directory.Exists(Path.Combine(brokenApp, "Profiles")));
    }
#endif

    // Read-only compiled-artifact check also usable for B1 while its real LocalAppData gate is STOP.
    [Fact]
    public void CompiledVariantIdentityAndCrossModeArgumentsAreIsolated()
    {
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        string profileId = Guid.NewGuid().ToString("N");
        Assert.Equal("开发工作区 · Development-only", Mmf3AcceptanceLaunch.Label);
        Assert.EndsWith(Path.Combine("PersonalAIWorkspace.Development", "Profiles", profileId), Mmf3ProfileState.Root(profileId));
        foreach (string[] arguments in new[] { Array.Empty<string>(), new[] { "--mmf3-acceptance", profileId },
            new[] { Mmf3AcceptanceLaunch.Argument, "invalid" }, new[] { Mmf3AcceptanceLaunch.Argument, profileId, "--root", @"C:\arbitrary" } })
            Assert.ThrowsAny<IOException>(() => Mmf3AcceptanceLaunch.Parse(arguments));
#else
        var assembly = typeof(AssistantApp).Assembly;
        var main = assembly.GetType("PersonalAiWorkspace.Desktop.Program")!
            .GetMethod("Main", BindingFlags.Static | BindingFlags.NonPublic)!;
        string id = Guid.NewGuid().ToString("N");
        foreach (string mode in new[] { "--mmf3-acceptance", "--mmf3-profile-acceptance" })
        {
            Assert.Equal(2, main.Invoke(null, [new[] { mode }]));
            Assert.Equal(2, main.Invoke(null, [new[] { mode, "invalid" }]));
            Assert.Equal(2, main.Invoke(null, [new[] { mode, id, "--root", "C:\\arbitrary" }]));
        }
#if MMF3_ACCEPTANCE && DEBUG
        Assert.NotNull(typeof(RuntimeClient).GetMethod("CreateMmf3Acceptance", BindingFlags.Static | BindingFlags.NonPublic));
        // Constructor derives identity only; Parse/Program reject the absent root without creating it.
        var launch = (Mmf3AcceptanceLaunch)typeof(Mmf3AcceptanceLaunch)
            .GetConstructor(BindingFlags.Instance | BindingFlags.NonPublic, null, [typeof(string)], null)!.Invoke([id]);
        Assert.Null(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3ProfileState"));
        Assert.Equal(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PersonalAiWorkspace-Mmf3", id), launch.Root);
        Assert.Equal("MMF3 TEST / 18765", Mmf3AcceptanceLaunch.Label);
        Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-profile-acceptance", id }]));
        Assert.Equal($"PersonalAiWorkspace/Desktop/MMF3/{id}/Runtime/127.0.0.1:18765", launch.CredentialTarget);
        Assert.Equal(Path.Combine(launch.Root, "webview2-profile"), launch.Profile);
        Assert.ThrowsAny<IOException>(() => Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]));
        Assert.Equal(2, main.Invoke(null, [Array.Empty<string>()]));
        Assert.Equal(2, main.Invoke(null, [new[] { Mmf3AcceptanceLaunch.Argument, id }]));
        Assert.False(Directory.Exists(launch.Root));
        using var test = RuntimeClient.CreateMmf3Acceptance(() => throw new InvalidOperationException("No credential reads."));
        Transport(test, 18765);
#else
        Assert.Null(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3AcceptanceLaunch"));
        Assert.Null(assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3ProfileState"));
        Assert.Null(typeof(RuntimeClient).GetMethod("CreateMmf3Acceptance", BindingFlags.Static | BindingFlags.NonPublic));
        foreach (string mode in new[] { "--mmf3-acceptance", "--mmf3-profile-acceptance" })
            Assert.Equal(2, main.Invoke(null, [new[] { mode, id }]));
#endif
#endif
    }

    [Fact]
    public void CompiledEntryAndRunResourcesFailClosedWithoutOpeningFormalState()
    {
        var main = typeof(AssistantApp).Assembly.GetType("PersonalAiWorkspace.Desktop.Program")!
            .GetMethod("Main", BindingFlags.Static | BindingFlags.NonPublic)!;
        // Malformed helper requests return before any clipboard/window/resource access.
        foreach (string mode in new[] { "--clipboard-snapshot", "--clipboard-read", "--clipboard-restore" })
            Assert.Equal(2, main.Invoke(null, [new[] { mode }]));
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        ProfileFixture();
#elif MMF3_ACCEPTANCE && DEBUG
        string id = Guid.NewGuid().ToString("N");
        string parent = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PersonalAiWorkspace-Mmf3");
        string root = Path.GetFullPath(Path.Combine(parent, id));
        // Fixture only: no token, Credential Manager access, WebView environment or app startup.
        Assert.False(Directory.Exists(root));
        foreach (string[] args in new[] { Array.Empty<string>(), new[] { "--mmf3-acceptance" },
            new[] { "--mmf3-acceptance", id.ToUpperInvariant() }, new[] { "--mmf3-acceptance", "../" + id },
            new[] { "--mmf3-acceptance", id, "http://127.0.0.1:8765" }, new[] { "--mmf3-acceptance", id } })
            Assert.Equal(2, main.Invoke(null, [args]));
        Assert.False(Directory.Exists(root));
        Directory.CreateDirectory(root);
        try
        {
            Private(root);
            foreach (string name in new[] { "credentials", "model-state", "data", "webview2-profile", "chrome-profile", "browser-test-extension", "evidence", "logs" })
            { string path = Path.Combine(root, name); Directory.CreateDirectory(path); Private(path); }
            var launch = Mmf3AcceptanceLaunch.Parse(["--mmf3-acceptance", id]);
            Assert.Equal(root, launch.Root);
            Assert.Equal($"PersonalAiWorkspace/Desktop/MMF3/{id}/Runtime/127.0.0.1:18765", launch.CredentialTarget);
            Assert.NotEqual(CredentialStore.Target, launch.CredentialTarget);
            var store = new CredentialStore(launch.CredentialTarget);
            Assert.Equal(launch.CredentialTarget, typeof(CredentialStore).GetField("target", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(store));
            Assert.Equal(Path.Combine(root, "webview2-profile"), WorkspaceProfile.Mmf3PrivateFolder(launch));
            Assert.Empty(Directory.EnumerateFileSystemEntries(launch.Profile));
            Assert.Equal(launch.TokenFile, launch.ValidateTokenImport(Path.Combine(root, "credentials", "..", "credentials", "client-token")));
            foreach (string path in new[] { Path.Combine(parent, "other", "credentials", "client-token"),
                Path.Combine(root, "client-token"), "credentials/client-token", launch.TokenFile + ":stream" })
                Assert.Equal(DesktopError.CredentialInvalid, Assert.Throws<DesktopException>(() => launch.ValidateTokenImport(path)).Error);
            Assert.False(File.Exists(launch.TokenFile));
            using (var single = new SingleInstance(launch.InstanceSuffix))
            {
                Assert.True(single.IsPrimary);
                string name = (string)typeof(SingleInstance).GetField("name", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(single)!;
                Assert.Equal(@"Local\PersonalAiWorkspace.Desktop." + WindowsIdentity.GetCurrent().User!.Value + ".MMF3." + id, name);
                using var activation = EventWaitHandle.OpenExisting(name + ".Open");
            }
            using var normal = new RuntimeClient(() => throw new InvalidOperationException("Must not load credentials."));
            using var test = RuntimeClient.CreateMmf3Acceptance(() => throw new InvalidOperationException("Must not load credentials."));
            Assert.Equal(new Uri("http://127.0.0.1:8765"), RuntimeClient.Endpoint);
            Transport(normal, 8765); Transport(test, 18765);
            Assert.False(new WorkspaceContentPolicy().Development);

            string data = Path.Combine(root, "data");
            Directory.Delete(data);
            Assert.ThrowsAny<IOException>(launch.Validate); // Previously valid descriptor is now stale.
            Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", id }]));
            var start = new ProcessStartInfo("cmd.exe") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (string arg in new[] { "/c", "mklink", "/J", data, Path.Combine(root, "logs") }) start.ArgumentList.Add(arg);
            using (var junction = Process.Start(start)!) { junction.WaitForExit(); Assert.Equal(0, junction.ExitCode); }
            try { Assert.ThrowsAny<IOException>(launch.Validate); Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", id }])); }
            finally { Directory.Delete(data); }
            Directory.CreateDirectory(data); Private(data);
            var security = new DirectoryInfo(data).GetAccessControl();
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), FileSystemRights.ReadData, AccessControlType.Allow));
            new DirectoryInfo(data).SetAccessControl(security);
            Assert.ThrowsAny<IOException>(launch.Validate);
            Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", id }]));
            Private(data);
            security = new DirectoryInfo(data).GetAccessControl(); security.SetAccessRuleProtection(false, true);
            new DirectoryInfo(data).SetAccessControl(security);
            Assert.ThrowsAny<IOException>(launch.Validate);
            Private(data); launch.Validate();
        }
        finally
        {
            // Delete only this newly generated, checked fixture root; never the shared parent.
            Assert.Equal(Path.GetFullPath(Path.Combine(parent, id)), root);
            Assert.StartsWith(Path.GetFullPath(parent) + Path.DirectorySeparatorChar, root, StringComparison.OrdinalIgnoreCase);
            Directory.Delete(root, recursive: true);
        }
#else
        Assert.Null(typeof(AssistantApp).Assembly.GetType("PersonalAiWorkspace.Desktop.Mmf3AcceptanceLaunch"));
        Assert.Null(typeof(RuntimeClient).GetMethod("CreateMmf3Acceptance", BindingFlags.Static | BindingFlags.NonPublic));
        Assert.Equal(2, main.Invoke(null, [new[] { "--mmf3-acceptance", Guid.NewGuid().ToString("N") }]));
#endif
    }

#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    [Fact]
    public void DevelopmentPrivatePolicyAcceptsWindowsAuthoritiesAndRejectsForeignDataAccess()
    {
        var sid = WindowsIdentity.GetCurrent().User!;
        var security = new DirectorySecurity();
        security.SetSecurityDescriptorSddlForm($"O:{sid.Value}D:P(A;OICI;FA;;;{sid.Value})(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)");
        PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(security, sid, directory: true);
        // Normal system/admin ACEs and harmless traversal do not require exact ACE count.
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null),
            FileSystemRights.ReadAttributes | FileSystemRights.Traverse, AccessControlType.Allow));
        PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(security, sid, directory: true);
        foreach (FileSystemRights right in new[] { FileSystemRights.ReadData, FileSystemRights.WriteData,
            FileSystemRights.DeleteSubdirectoriesAndFiles, FileSystemRights.ChangePermissions })
        {
            var unsafeAcl = new DirectorySecurity();
            unsafeAcl.SetSecurityDescriptorBinaryForm(security.GetSecurityDescriptorBinaryForm());
            unsafeAcl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), right, AccessControlType.Allow));
            Assert.Throws<IOException>(() => PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(unsafeAcl, sid, directory: true));
        }
        security.SetOwner(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null));
        Assert.Throws<IOException>(() => PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity.ValidatePrivate(security, sid));
    }

    private static void ProfileFixture()
    {
        string[] sdkVariables = ["DOTNET_CLI_TELEMETRY_SESSIONID", "DOTNET_ROOT_X64", "DOTNET_HOST_PATH"];
        var previous = sdkVariables.ToDictionary(name => name, Environment.GetEnvironmentVariable);
        foreach (string name in sdkVariables) Environment.SetEnvironmentVariable(name, null);
        try
        {
            string id = Guid.NewGuid().ToString("N");
            string root = Mmf3ProfileState.Prepare(id);
            Assert.ThrowsAny<IOException>(() => Mmf3ProfileState.Prepare(id));
            Assert.Equal(root, Mmf3ProfileState.Verify(id));
            // Actual production directory-handle mode excludes rename until release.
            using (Mmf3ProfileState.Pin(id))
                Assert.Throws<IOException>(() => Directory.Move(root, root + "-renamed"));
            Mmf3ProfileNative.Provision(id, Environment.GetEnvironmentVariable("MMF3_JNA_ARTIFACT")
                ?? throw new IOException("Explicit reviewed JNA artifact required."));
            var launch = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            Assert.Equal(root, launch.Root);
            var sid = WindowsIdentity.GetCurrent().User!;
            foreach (string path in new[] { root, Path.Combine(root, "data") })
            {
                var actual = new DirectoryInfo(path).GetAccessControl();
                Assert.Equal(sid, actual.GetOwner(typeof(SecurityIdentifier)));
                Assert.True(actual.AreAccessRulesProtected);
                Assert.Equal(3, actual.GetAccessRules(true, true, typeof(SecurityIdentifier)).Count);
            }
            string[] variables = ["USERPROFILE", "HOME", "LOCALAPPDATA"];
            var old = variables.ToDictionary(name => name, Environment.GetEnvironmentVariable);
            try
            {
                foreach (string name in variables) Environment.SetEnvironmentVariable(name, @"Z:\untrusted");
                Assert.Equal(root, Mmf3ProfileState.Verify(id));
            }
            finally { foreach (string name in variables) Environment.SetEnvironmentVariable(name, old[name]); }
            string data = Path.Combine(root, "data");
            var security = new DirectoryInfo(data).GetAccessControl();
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null),
                FileSystemRights.ReadData, AccessControlType.Allow));
            new DirectoryInfo(data).SetAccessControl(security);
            Assert.ThrowsAny<IOException>(launch.Validate);
            Assert.ThrowsAny<IOException>(() => Mmf3ProfileNative.Verify(launch));
            security.RemoveAccessRuleSpecific(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null),
                FileSystemRights.ReadData, AccessControlType.Allow));
            new DirectoryInfo(data).SetAccessControl(security); // Only this test's fresh synthetic child.
            Directory.Delete(data);
            var start = new ProcessStartInfo("cmd.exe") { UseShellExecute = false, CreateNoWindow = true };
            foreach (string arg in new[] { "/c", "mklink", "/J", data, Path.Combine(root, "logs") }) start.ArgumentList.Add(arg);
            using (var junction = Process.Start(start)!) { junction.WaitForExit(); Assert.Equal(0, junction.ExitCode); }
            Assert.ThrowsAny<IOException>(launch.Validate); // Reparse remains rejected; fixture is retained.
        }
        finally { foreach (string name in sdkVariables) Environment.SetEnvironmentVariable(name, previous[name]); }
    }

#endif

#if MMF3_ACCEPTANCE && DEBUG
    private static void Private(string path)
    {
        var user = WindowsIdentity.GetCurrent().User!;
        var acl = new DirectorySecurity(); acl.SetOwner(user); acl.SetAccessRuleProtection(true, false);
        acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(path).SetAccessControl(acl);
    }
    private static void Transport(RuntimeClient client, int port)
    {
        var http = (HttpClient)typeof(RuntimeClient).GetField("http", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(client)!;
        Assert.Equal(new Uri($"http://127.0.0.1:{port}"), http.BaseAddress);
        var handler = Assert.IsType<HttpClientHandler>(typeof(HttpMessageInvoker).GetField("_handler", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(http));
        Assert.False(handler.UseProxy); Assert.False(handler.AllowAutoRedirect); Assert.False(handler.UseCookies);
    }
#endif
}
