#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    internal sealed record NativeMaintenanceInspection(int Version, string Kind, long ChildPid, string ProfileId,
        string Admission, string WriterCoverage, string ModelState, string SourceState,
        string? SourceConfigurationIdentity, string? SourceConfigurationSha256, uint SourceDatabaseOid, uint SourceApplicationRoleOid);

    internal sealed record SourceReadiness(NativeMaintenanceInspection Native, RuntimeHistoryInspection RuntimeHistory)
    {
        public int? ObservedExitedChildren => RuntimeHistory.State == "EXIT_OBSERVATIONS_COMPLETE"
            ? RuntimeHistory.ObservedExitedChildren : null;
    }

    // Uses the reviewed embedded source manifest, the original authority Gate and our direct
    // Process handle. This is a read-only product preflight, not the synthetic backup launcher.
    internal async Task<SourceReadiness> InspectSourceReadinessAsync()
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (authority is null || maintenanceRuntimeLock is not null) throw Stop();
        authority.Validate(); Mmf3ProfileNative.Verify(authority);
        foreach (string directory in new[] { "evidence", "model-state" }) HoldPrivateDirectory(Path.Combine(authority.Root, directory));
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        var runtime = OpenRecord(Path.Combine(authority.Root, "evidence", "runtime-admission.lock"), write: true);
        FileStream? model = null;
        try
        {
            model = OpenRecord(Path.Combine(authority.Root, "model-state", ".lock"));
            ValidateFileAcl(runtime, user.User!); ValidateFileAcl(model, user.User!);
            if (runtime.Length != 0 || model.Length != 0) throw Stop();
            held.Add(runtime); held.Add(model); maintenanceRuntimeLock = runtime;
        }
        catch { model?.Dispose(); runtime.Dispose(); throw; }
        var history = InspectRuntimeHistory(authority.RunId);
        using var sources = TrustedDevelopmentRuntimeSources.Open();
        var launch = sources.StartInfo(authority, recoveryInspection: true);
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        using var job = RuntimeProcessJob.Create();
        using var child = Process.Start(launch) ?? throw Stop();
        _ = child.SafeHandle;
        Task errors = DrainSourceErrors(child.StandardError.BaseStream, deadline.Token);
        try
        {
            job.Assign(child);
            byte[] hello = await ReadSourceFrame(child.StandardOutput.BaseStream, deadline.Token).ConfigureAwait(false);
            if (Encoding.ASCII.GetString(hello) != "WORKSPACE_MAINTENANCE_V1\t" + child.Id.ToString(CultureInfo.InvariantCulture)
                + "\t" + authority.RunId) throw Stop();
            long handle = TransferRuntimeExclusion(child);
            byte[] request = JsonSerializer.SerializeToUtf8Bytes(new { Version = 1, Action = "INSPECT_SOURCE", ExclusionHandle = handle });
            await WriteSourceFrame(child.StandardInput.BaseStream, request, deadline.Token).ConfigureAwait(false);
            child.StandardInput.Close();
            var result = ParseSourceFrame<NativeMaintenanceInspection>(await ReadSourceFrame(child.StandardOutput.BaseStream, deadline.Token).ConfigureAwait(false));
            if (await child.StandardOutput.BaseStream.ReadAsync(new byte[1], deadline.Token).ConfigureAwait(false) != 0) throw Stop();
            await child.WaitForExitAsync(deadline.Token).ConfigureAwait(false);
            await errors.ConfigureAwait(false);
            if (child.ExitCode != 0 || result.Version != 1 || result.Kind != "WORKSPACE_MAINTENANCE_INSPECTION"
                || result.ChildPid != child.Id || result.ProfileId != authority.RunId || result.Admission != "BLOCKED"
                || result.WriterCoverage != "UNREGISTERED_WRITERS_UNPROVEN"
                || result.ModelState is not ("NO_GUARD_FILE_OBSERVED" or "UNRESOLVED_MODEL_STATE")) throw Stop();
            if (result.SourceState == "BOUND_READ_ONLY")
            {
                if (result.ModelState != "NO_GUARD_FILE_OBSERVED" || result.SourceDatabaseOid == 0 || result.SourceApplicationRoleOid == 0) throw Stop();
                VerifySourceConfiguration(authority.Root, result.SourceConfigurationIdentity!, result.SourceConfigurationSha256!);
            }
            else if (result.SourceConfigurationIdentity is not null || result.SourceConfigurationSha256 is not null
                || result.SourceDatabaseOid != 0 || result.SourceApplicationRoleOid != 0
                || result.SourceState is not ("NOT_READ_MODEL_STATE_UNRESOLVED" or "CONFIGURATION_UNAVAILABLE"
                    or "CONFIGURATION_UNTRUSTED" or "CREDENTIAL_UNAVAILABLE")) throw Stop();
            authority.Validate();
            return new(result, history);
        }
        finally
        {
            job.Dispose(); // Only this direct maintenance child and its native helper belong here.
            if (!child.HasExited) { child.Kill(); await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10)).ConfigureAwait(false); }
            deadline.Cancel();
            try { await errors.WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false); } catch (Exception) { }
        }
    }

    private void VerifySourceConfiguration(string root, string identity, string digest)
    {
        RequireHex(identity, 48); RequireHex(digest, 64);
        var configuration = OpenRecord(Path.Combine(root, "data", "database.dpapi"));
        try
        {
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            ValidateFileAcl(configuration, user.User!);
            if (configuration.Length is < 1 or > MaximumRecordBytes
                || !GetFileInformationByHandleEx(configuration.SafeFileHandle, 18, out SourceFileId id, 24)) throw Stop();
            Span<byte> bytes = stackalloc byte[24];
            BinaryPrimitives.WriteUInt64LittleEndian(bytes, id.Volume);
            BinaryPrimitives.WriteUInt64LittleEndian(bytes[8..], id.Low);
            BinaryPrimitives.WriteUInt64LittleEndian(bytes[16..], id.High);
            if (Convert.ToHexStringLower(bytes) != identity || Convert.ToHexStringLower(SHA256.HashData(configuration)) != digest) throw Stop();
            held.Add(configuration);
        }
        catch { configuration.Dispose(); throw; }
    }

    private sealed record SourceChildResult(int Version, string Kind, long ChildPid, string BackupId,
        string DatabaseLogicalIdentity, string SourceSystemIdentifier, uint SourceDatabaseOid, int SourcePort,
        string SourceDatabase, string SourceRole, long PayloadBytes, string PayloadSha256,
        string ManifestSha256, string CredentialCleanup, string SourceConfigurationIdentity,
        string SourceConfigurationSha256, uint SourceApplicationRoleOid);

    private sealed record BoundSyntheticSource(int Version, SourceRegistration Source,
        string SourceConfigurationIdentity, string SourceConfigurationSha256, uint SourceApplicationRoleOid);

    // This is an executable development seam, not a product maintenance admission. The only
    // current Java producer is synthetic. Its records have a distinct kind, entropy AND namespace;
    // PinRegisteredSource cannot consume them even if a file is copied into sources/<id>.
    // Do not expose a generic PublishSource(metadata) or upgrade this by changing its kind.
    // A product owner still needs complete stop proof, a pinned Gate-admitted launch and protected
    // source configuration binding before personal execution/publication can be wired here.
    private async Task<SourceRegistration> ExecuteSyntheticSourceBackupAsync(ProcessStartInfo launch)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        var intent = backupIntent ?? throw Stop();
        if (authority is not null || !Path.GetFileName(applicationRoot).StartsWith("paw-source-producer-", StringComparison.Ordinal)
            || !launch.RedirectStandardInput || !launch.RedirectStandardOutput || !launch.RedirectStandardError
            || launch.UseShellExecute || !launch.CreateNoWindow || !Path.IsPathFullyQualified(launch.FileName)) throw Stop();
        string root = Path.Combine(applicationRoot, "Profiles", intent.SourceProfileId);
        RecheckSourceIntent(intent, root);
        string payloads = Path.Combine(applicationRoot, "Recovery", "synthetic-payloads");
        // The existing synthetic backup engine requires user-only ACLs (narrower than the
        // Profile's user/System/Admin ACL). Create this new output boundary to that contract;
        // never rewrite a source/Profile ACL or loosen either validator.
        if (!Exists(payloads))
        {
            using var creator = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            var acl = new DirectorySecurity(); acl.SetOwner(creator.User!); acl.SetAccessRuleProtection(true, false);
            acl.AddAccessRule(new FileSystemAccessRule(creator.User!, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            FileSystemAclExtensions.Create(new DirectoryInfo(payloads), acl);
        }
        HoldPrivateDirectory(payloads);
        string encrypted = Path.Combine(payloads, intent.BackupId);
        if (Exists(encrypted)) throw Stop();
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        HoldPrivateDirectory(Path.Combine(root, "evidence"));
        HoldPrivateDirectory(Path.Combine(root, "model-state"));
        using var runtime = OpenRecord(Path.Combine(root, "evidence", "runtime-admission.lock"), write: true);
        using var model = OpenRecord(Path.Combine(root, "model-state", ".lock"));
        ValidateFileAcl(runtime, user.User!); ValidateFileAcl(model, user.User!);
        if (runtime.Length != 0 || model.Length != 0) throw Stop();
        using var deadline = new CancellationTokenSource(TimeSpan.FromMinutes(2));
        using var job = RuntimeProcessJob.Create();
        using var child = Process.Start(launch) ?? throw Stop();
        // Process.Start's handle is retained throughout; a reported PID never opens a process.
        _ = child.SafeHandle;
        Task errors = DrainSourceErrors(child.StandardError.BaseStream, deadline.Token);
        try
        {
            job.Assign(child);
            byte[] hello = await ReadSourceFrame(child.StandardOutput.BaseStream, deadline.Token);
            if (Encoding.ASCII.GetString(hello) != "SYNTHETIC_SOURCE_V1\t" + child.Id.ToString(CultureInfo.InvariantCulture)) throw Stop();
            long handle = DuplicateExclusion(runtime, child);
            byte[] request = JsonSerializer.SerializeToUtf8Bytes(new { Version = 1, Action = "BACKUP_SYNTHETIC",
                intent.BackupId, intent.DatabaseLogicalIdentity, SourceRoot = root, Encrypted = encrypted, ExclusionHandle = handle });
            await WriteSourceFrame(child.StandardInput.BaseStream, request, deadline.Token);
            child.StandardInput.Close();
            var result = ParseSourceFrame<SourceChildResult>(await ReadSourceFrame(child.StandardOutput.BaseStream, deadline.Token));
            // A result followed by a hang, abnormal exit or extra stdout never authorizes a receipt.
            if (await child.StandardOutput.BaseStream.ReadAsync(new byte[1], deadline.Token) != 0) throw Stop();
            await child.WaitForExitAsync(deadline.Token);
            await errors;
            if (child.ExitCode != 0 || result.ChildPid != child.Id || result.Version != 2
                || result.Kind != "SYNTHETIC_WORKSPACE_BACKUP_RESULT" || result.BackupId != intent.BackupId
                || result.DatabaseLogicalIdentity != intent.DatabaseLogicalIdentity || result.SourcePort == 5432
                || result.SourceRole != "workspace_backup_src_" + intent.BackupId
                || !System.Text.RegularExpressions.Regex.IsMatch(result.SourceDatabase, "\\Apersonal_ai_workspace_b3_[0-9a-f]{12}\\z")
                || !ulong.TryParse(result.SourceSystemIdentifier, NumberStyles.None, CultureInfo.InvariantCulture, out ulong system)
                || system.ToString(CultureInfo.InvariantCulture) != result.SourceSystemIdentifier) throw Stop();
            var source = new SourceRegistration(1, "SYNTHETIC_WORKSPACE_BACKUP_RESULT", "SYNTHETIC_BACKUP_RECORDED",
                intent.BackupId, intent.SourceProfileId, intent.DatabaseLogicalIdentity, intent.SourceRootIdentity, intent.SourceDataIdentity,
                system, result.SourceDatabaseOid, result.SourcePort, result.SourceDatabase, result.SourceRole,
                "personal-ai-workspace.postgresql-encrypted-stream", 3, 17, "WORKSPACE_KNOWLEDGE_ONLY",
                result.PayloadBytes, result.PayloadSha256, result.ManifestSha256, result.CredentialCleanup);
            ValidateSourceFields(source, intent.BackupId);
            RecheckSourceIntent(intent, root);
            RequireHex(result.SourceConfigurationIdentity, 48); RequireHex(result.SourceConfigurationSha256, 64);
            if (result.SourceApplicationRoleOid == 0) throw Stop();
            // The child bound the live app/admin sessions while holding this exact file. Read
            // and pin it independently now; no child-selected configuration path or credential.
            using var configuration = OpenRecord(Path.Combine(root, "data", "database.dpapi"));
            ValidateFileAcl(configuration, user.User!);
            if (configuration.Length is < 1 or > MaximumRecordBytes
                || !GetFileInformationByHandleEx(configuration.SafeFileHandle, 18, out SourceFileId configurationId, 24)) throw Stop();
            byte[] configurationIdentity = new byte[24];
            BinaryPrimitives.WriteUInt64LittleEndian(configurationIdentity, configurationId.Volume);
            BinaryPrimitives.WriteUInt64LittleEndian(configurationIdentity.AsSpan(8), configurationId.Low);
            BinaryPrimitives.WriteUInt64LittleEndian(configurationIdentity.AsSpan(16), configurationId.High);
            if (Convert.ToHexStringLower(configurationIdentity) != result.SourceConfigurationIdentity
                || Convert.ToHexStringLower(await SHA256.HashDataAsync(configuration, deadline.Token)) != result.SourceConfigurationSha256) throw Stop();
            // Parent independently pins and hashes the actual ciphertext at its own planned path.
            // Never accept a child-selected path, a package-side SHA or an outer success marker alone.
            HoldSyntheticBackupOutput(encrypted, payloads);
            if (!Directory.EnumerateFileSystemEntries(encrypted).Select(Path.GetFileName).Order(StringComparer.Ordinal)
                .SequenceEqual(new[] { "ENCRYPTED_BACKUP_COMPLETE.json", "ENCRYPTED_BACKUP_INCOMPLETE", "backup.pgp" })) throw Stop();
            using var payload = OpenRecord(Path.Combine(encrypted, "backup.pgp"));
            using var completion = OpenRecord(Path.Combine(encrypted, "ENCRYPTED_BACKUP_COMPLETE.json"));
            ValidateFileAcl(payload, user.User!); ValidateFileAcl(completion, user.User!);
            if (payload.Length != source.PayloadBytes || completion.Length is < 1 or > MaximumRecordBytes
                || Convert.ToHexStringLower(await SHA256.HashDataAsync(payload, deadline.Token)) != source.PayloadSha256) throw Stop();
            byte[] complete = new byte[checked((int)completion.Length)]; completion.ReadExactly(complete);
            using var doc = ParseSourceDocument(complete);
            var fields = doc.RootElement;
            if (fields.EnumerateObject().Count() != 7 || fields.GetProperty("format").GetString() != "personal-ai-workspace.openpgp-backup"
                || fields.GetProperty("version").GetInt32() != 3 || fields.GetProperty("status").GetString() != "ENCRYPTED_BACKUP_COMPLETE"
                || fields.GetProperty("profile").GetString() != "RFC9580-AES256-GCM-ARGON2ID"
                || fields.GetProperty("payload").GetString() != "backup.pgp"
                || fields.GetProperty("bytes").GetInt64() != source.PayloadBytes
                || fields.GetProperty("sha256").GetString() != source.PayloadSha256) throw Stop();
            string sources = Path.Combine(applicationRoot, "Recovery", "synthetic-sources");
            EnsurePrivateDirectory(sources);
            string directory = Path.Combine(sources, intent.BackupId);
            WindowsProfileSecurity.CreateDirectory(directory, user.User!); // No overwrite/replay.
            HoldPrivateDirectory(directory);
            byte[] entropy = SyntheticSourceEntropy(intent.BackupId);
            var bound = new BoundSyntheticSource(2, source, result.SourceConfigurationIdentity,
                result.SourceConfigurationSha256, result.SourceApplicationRoleOid);
            PublishRecord(directory, "source", entropy, bound);
            if (ReadRecord<BoundSyntheticSource>(Path.Combine(directory, "source.dpapi"), entropy) != bound) throw Stop();
            return source;
        }
        catch (Exception failure)
        {
            // Retain intent, ciphertext and any publication evidence. No retry, role/DB cleanup,
            // success inference or publication from an incomplete/unknown child's result.
            throw new IOException("Synthetic source execution incomplete; inspect retained attempt; no personal registration.", failure);
        }
        finally
        {
            job.Dispose(); // Owned pg_dump descendants must not survive an interrupted probe.
            if (!child.HasExited)
            {
                child.Kill(); // Only the directly started synthetic child; no existing service.
                await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
            }
            deadline.Cancel();
            try { await errors.WaitAsync(TimeSpan.FromSeconds(2)); } catch (Exception) { /* Never log raw stderr. */ }
        }
    }

    private void RecheckSourceIntent(BackupIntent intent, string root)
    {
        if (InspectBackupIntent(intent.BackupId) != intent
            || HoldSourceDirectoryIdentity(root) != intent.SourceRootIdentity
            || HoldSourceDirectoryIdentity(Path.Combine(root, "data")) != intent.SourceDataIdentity) throw Stop();
    }

    private void HoldSyntheticBackupOutput(string directory, string parent)
    {
        if (Path.GetDirectoryName(directory) != parent || Path.GetFileName(parent) != "synthetic-payloads") throw Stop();
        // MaintenanceFiles creates ciphertext directories by inheritance under the already-pinned
        // private output parent. Inspect the real descriptor from the opened directory handle,
        // require only current-user ALLOW entries, then validate the inherited grants in memory.
        // This follows the existing inherited-file read rule; no on-disk ACL repair/relaxation.
        var pins = WindowsProfileSecurity.HoldDirectories(directory);
        try
        {
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            using var borrowed = new Microsoft.Win32.SafeHandles.SafeFileHandle(pins.Handles[^1].DangerousGetHandle(), ownsHandle: false);
            using var stream = new FileStream(borrowed, FileAccess.Read, 1);
            var acl = stream.GetAccessControl();
            foreach (FileSystemAccessRule rule in acl.GetAccessRules(true, true, typeof(SecurityIdentifier)))
                if (rule.AccessControlType != AccessControlType.Allow || !user.User!.Equals(rule.IdentityReference)) throw Stop();
            acl.SetAccessRuleProtection(true, preserveInheritance: true);
            WindowsProfileSecurity.ValidatePrivate(acl, user.User!, directory: true);
            held.Add(pins);
        }
        catch { pins.Dispose(); throw; }
    }

    private static byte[] SyntheticSourceEntropy(string backupId) =>
        Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/synthetic-source/v1/" + backupId);

    private static JsonDocument ParseSourceDocument(byte[] bytes)
    {
        var doc = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 4 });
        try
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            if (doc.RootElement.ValueKind != JsonValueKind.Object) throw Stop();
            foreach (var field in doc.RootElement.EnumerateObject()) if (!names.Add(field.Name)) throw Stop();
            return doc;
        }
        catch { doc.Dispose(); throw; }
    }

    private static T ParseSourceFrame<T>(byte[] bytes) where T : class
    {
        using var doc = ParseSourceDocument(bytes);
        return doc.Deserialize<T>(Json) ?? throw Stop();
    }

    private static async Task<byte[]> ReadSourceFrame(Stream pipe, CancellationToken cancellation)
    {
        byte[] prefix = new byte[4]; await pipe.ReadExactlyAsync(prefix, cancellation);
        int size = BinaryPrimitives.ReadInt32BigEndian(prefix);
        if (size is < 1 or > MaximumRecordBytes) throw Stop();
        byte[] bytes = new byte[size]; await pipe.ReadExactlyAsync(bytes, cancellation); return bytes;
    }

    private static async Task WriteSourceFrame(Stream pipe, byte[] bytes, CancellationToken cancellation)
    {
        if (bytes.Length is < 1 or > MaximumRecordBytes) throw Stop();
        byte[] prefix = new byte[4]; BinaryPrimitives.WriteInt32BigEndian(prefix, bytes.Length);
        await pipe.WriteAsync(prefix, cancellation); await pipe.WriteAsync(bytes, cancellation); await pipe.FlushAsync(cancellation);
    }

    private static async Task DrainSourceErrors(Stream pipe, CancellationToken cancellation)
    {
        byte[] buffer = new byte[4096]; int total = 0, count;
        while ((count = await pipe.ReadAsync(buffer, cancellation)) != 0)
            if ((total += count) > 1024 * 1024) throw Stop();
    }
}
#endif
