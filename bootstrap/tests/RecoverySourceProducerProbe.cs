using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.Json;
using System.Threading.Tasks;

// One explicit native -> Java -> native integration in the new cluster owned by the runner.
// It deliberately never creates a product source registration or invokes a real Profile Gate.
internal static class RecoverySourceProducerProbe
{
    private static async Task Main(string[] args)
    {
        if (args.Length != 4) throw new IOException("Explicit synthetic fixture/tooling required");
        var assembly = Assembly.LoadFrom(args[0]);
        var coordinator = assembly.GetType("PersonalAiWorkspace.Desktop.ProfileRecoveryCoordinator", true)!;
        var flags = BindingFlags.NonPublic | BindingFlags.Instance;
        using var config = JsonDocument.Parse(File.ReadAllBytes(args[3]));
        string isolated = config.RootElement.GetProperty("root").GetString()!;
        if (!System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(isolated), "\\APAW-SourceProducer-[0-9a-f]{32}\\z")) throw new IOException();
        string app = Path.Combine(isolated, "paw-source-producer-" + Guid.NewGuid().ToString("N"));
        string profileId = Guid.NewGuid().ToString("N"), root = Path.Combine(app, "Profiles", profileId);
        foreach (string path in new[] { app, Path.Combine(app, "Profiles"), root, Path.Combine(root, "data"),
            Path.Combine(root, "evidence"), Path.Combine(root, "model-state") })
        {
            var user = WindowsIdentity.GetCurrent().User!;
            var acl = new DirectorySecurity(); acl.SetOwner(user); acl.SetAccessRuleProtection(true, false);
            acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            FileSystemAclExtensions.Create(new DirectoryInfo(path), acl);
        }
        File.WriteAllBytes(Path.Combine(root, "evidence", "runtime-admission.lock"), []);
        File.WriteAllBytes(Path.Combine(root, "model-state", ".lock"), []);
        using var owner = (IDisposable)Activator.CreateInstance(coordinator, flags, null, [app], null)!;
        object intent = coordinator.GetMethod("ReserveBackupIntentForProfile", flags)!.Invoke(owner, [profileId])!;
        string backupId = (string)intent.GetType().GetProperty("BackupId")!.GetValue(intent)!;
        var start = new ProcessStartInfo(args[1]) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = app };
        foreach (string value in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", args[2],
            "io.github.qianlixunbai.workspace.backup.RecoverySourceProducerProbe", args[3] }) start.ArgumentList.Add(value);
        var run = coordinator.GetMethod("ExecuteSyntheticSourceBackupAsync", flags)!;
        var pending = (Task)run.Invoke(owner, [start])!;
        await pending;
        object registration = pending.GetType().GetProperty("Result")!.GetValue(pending)!;
        string record = Path.Combine(app, "Recovery", "synthetic-sources", backupId, "source.dpapi");
        byte[] original = File.ReadAllBytes(record);
        if (original.Length == 0 || Directory.Exists(Path.Combine(app, "Recovery", "sources"))) throw new IOException();
        string payload = (string)registration.GetType().GetProperty("PayloadSha256")!.GetValue(registration)!;
        string manifest = (string)registration.GetType().GetProperty("ManifestSha256")!.GetValue(registration)!;
        // A persisted synthetic result must not reserve a product target or satisfy its kind check.
        try { coordinator.GetMethod("ReservePlan", flags)!.Invoke(owner, [backupId, payload, manifest]); throw new Exception("Synthetic result authorized SQL"); }
        catch (TargetInvocationException denied) when (denied.InnerException is IOException) { }
        var validate = coordinator.GetMethod("Validate", BindingFlags.NonPublic | BindingFlags.Static, null, [registration.GetType(), typeof(string)], null)!;
        try { validate.Invoke(null, [registration, backupId]); throw new Exception("Synthetic kind accepted"); }
        catch (TargetInvocationException denied) when (denied.InnerException is IOException) { }
        if (Directory.GetFileSystemEntries(Path.Combine(app, "Recovery", "targets")).Length != 0) throw new IOException();
        // Same attempt cannot launch again or replace its DPAPI record.
        try { await (Task)run.Invoke(owner, [start])!; throw new Exception("Duplicate attempt executed"); }
        catch (IOException) { }
        if (!CryptographicOperations.FixedTimeEquals(original, File.ReadAllBytes(record))) throw new IOException();
        using (new FileStream(Path.Combine(root, "evidence", "runtime-admission.lock"), FileMode.Open, FileAccess.Read, FileShare.Read)) { }
        using (new FileStream(Path.Combine(root, "model-state", ".lock"), FileMode.Open, FileAccess.Write, FileShare.Read)) { }
        // End the producer's native owner too. Only persisted registration/ciphertext may
        // authorize the next JVM; neither Completed nor the source configuration survives.
        owner.Dispose();
        string retired = Path.Combine(app, "retained-source-" + profileId);
        if (Path.GetFullPath(root) != root || Path.GetFullPath(retired) != retired
            || !root.StartsWith(app + Path.DirectorySeparatorChar, StringComparison.Ordinal)
            || Path.GetDirectoryName(retired) != app || Path.Exists(retired)) throw new IOException();
        Directory.Move(root, retired); // This newly owned synthetic tree only; retain all bytes.
        var restore = new ProcessStartInfo(args[1]) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = app };
        foreach (string value in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", args[2],
            "io.github.qianlixunbai.workspace.backup.RecoverySourceProducerProbe", "restore-registered", args[3], app, backupId }) restore.ArgumentList.Add(value);
        using (var child = Process.Start(restore) ?? throw new IOException())
        {
            var errors = child.StandardError.BaseStream.CopyToAsync(Stream.Null);
            try
            {
                string result = await child.StandardOutput.ReadToEndAsync().WaitAsync(TimeSpan.FromSeconds(90));
                await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10)); await errors.WaitAsync(TimeSpan.FromSeconds(5));
                if (child.ExitCode != 0 || result != "REGISTERED_SYNTHETIC_RECOVERY_VERIFIED") throw new IOException("Registered synthetic recovery failed");
            }
            finally { if (!child.HasExited) { child.Kill(); await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)); } }
        }
        if (!CryptographicOperations.FixedTimeEquals(original, File.ReadAllBytes(record))) throw new IOException();
        using var recovery = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(isolated, "synthetic-recovery-evidence.json")));
        foreach (string field in new[] { "authenticatedFreshTarget", "wrongPasswordCreatedNoDatabaseOrRoles",
            "memoryConversationKnowledgeVerified", "financeExcluded", "sourceSnapshotUnchanged", "existingAttemptRejected", "plaintextBackupCleaned",
            "durableRegistrationConsumed", "sourceDatabaseUnavailableDuringRestore", "sourceProfileAbsent", "missingRegistrationRejected", "registrationAndPayloadPinned" })
            if (!recovery.RootElement.GetProperty(field).GetBoolean()) throw new IOException();
        File.WriteAllText(Path.Combine(isolated, "native-source-evidence.json"), JsonSerializer.Serialize(new {
            status = "SYNTHETIC_NATIVE_SOURCE_PRODUCER_VERIFIED", backupId, payloadSha256 = payload, manifestSha256 = manifest,
            nativeDpapiReadback = true, sourceRoleReclaimed = true, authenticatedPayload = true,
            protectedConfigurationBound = true, nativeConfigurationIdentityAndDigestVerified = true,
            authenticatedFreshSyntheticTargetRestored = true, targetLoginsDisabled = true,
            restoredByNewProcessFromDurableRegistration = true, originalSourceProfileRetainedAt = retired,
            personalSourceRegistration = false, productPlanRejected = true, duplicateAttemptRejected = true,
            sourceRecord = record, sourceRecordSha256 = Convert.ToHexStringLower(SHA256.HashData(original)) }));
        Console.WriteLine("PASS: protected Workspace configuration -> app/admin database binding -> owned backup/role cleanup -> native configuration/ciphertext verification -> DPAPI readback.");
        Console.WriteLine("PASS: synthetic receipt cannot authorize a product plan; duplicate attempt rejected; exclusion released after child exit.");
        Console.WriteLine("PASS: producer exited -> DPAPI registration consumed by new JVM -> source database unavailable/Profile absent -> authenticated fresh target -> stores/index verified -> target logins disabled.");
        Console.WriteLine("Personal maintenance remains BLOCKED; real Native Gate, personal target publication and activation were not exercised.");
    }
}
