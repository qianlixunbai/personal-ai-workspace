#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32.SafeHandles;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

// Plans, source intents, registered-source preflight and owned-child observations. No SQL authority, target creation,
// maintenance admission, READY or activation. A plan never proves stopped execution or backup trust.
internal sealed partial class ProfileRecoveryCoordinator : IDisposable
{
    internal const string InspectionArgument = "--inspect-profile-recovery-v1";

    // Parse before deriving any Known Folder, opening a Profile or starting the native helper.
    // This version exposes inspection only; paths, credentials and mutation actions are invalid.
    internal static string ParseInspectionRequest(string[] args)
    {
        if (args.Length != 2 || args[0] != InspectionArgument) throw Stop();
        RequireHex(args[1], 32);
        return args[1];
    }

    private const int MaximumRecordBytes = 16384;
    private readonly string applicationRoot;
    private readonly List<IDisposable> held = new();
    private bool disposed;
    private Mmf3AcceptanceLaunch? authority;
    private static readonly JsonSerializerOptions Json = new()
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        RespectRequiredConstructorParameters = true
    };

    internal sealed record Plan(int Version, string Kind, string State, string OperationId,
        string TargetProfileId, string SourceProfileId, string BackupId, string PayloadSha256,
        string ManifestSha256, string DatabaseName, string OwnerRole, string AppRole,
        string? SourceRegistrationSha256 = null);

    internal sealed class ReservationIncomplete(Plan plan, Exception cause)
        : IOException("Recovery reservation result requires inspection.", cause)
    {
        // Preserve the exact attempt identity even if force/rename/readback throws after publication.
        // The caller may inspect this target; this is never permission to replay the operation.
        internal Plan Attempt { get; } = plan;
    }

    // Only the native owner can open the canonical registry. No command/HTTP/bridge entry exists.
    // Holding this session mutex does NOT prove absence of other sessions/manual Java/model work.
    internal static ProfileRecoveryCoordinator OpenForPlanning(Mmf3AcceptanceLaunch authority)
    {
        authority.Validate();
        Mmf3ProfileNative.Verify(authority);
        var single = new SingleInstance(authority.InstanceSuffix);
        if (!single.IsPrimary) { single.Dispose(); throw Stop(); }
        IDisposable? authorityPin = null;
        ProfileRecoveryCoordinator? result = null;
        try
        {
            authorityPin = Mmf3ProfileState.Pin(authority.RunId);
            authority.Validate();
            Mmf3ProfileNative.Verify(authority);
            result = new(Path.GetDirectoryName(WindowsProfileSecurity.Parent())!);
            result.authority = authority;
            result.held.Add(single);
            result.held.Add(authorityPin);
            return result;
        }
        catch
        {
            result?.Dispose(); authorityPin?.Dispose(); single.Dispose(); throw;
        }
    }

    // Private to prevent a caller path from becoming a product registry. Tests reflect this
    // constructor only for an explicitly created synthetic application directory.
    private ProfileRecoveryCoordinator(string applicationRoot)
    {
        this.applicationRoot = applicationRoot;
        try { HoldPrivateDirectory(applicationRoot); }
        catch { Dispose(); throw; }
    }

    internal Plan ReservePlan(string backupId, string payloadSha256, string manifestSha256)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        // The source is taken only from the independent local registration. No source Profile/DB
        // need be online, and the admitted maintenance authority need not be that source.
        // Keep the receipt and its directory chain pinned until the new plan has been read back.
        using var source = PinRegisteredSource(backupId, payloadSha256, manifestSha256);
        string operation = Guid.NewGuid().ToString("N"), target = Guid.NewGuid().ToString("N");
        var plan = new Plan(2, "RECOVERY_PLAN_ONLY", "PREPARING", operation, target, source.Registration.SourceProfileId,
            backupId, payloadSha256, manifestSha256, "workspace_recovery_" + operation,
            "workspace_recovery_owner_" + operation, "workspace_recovery_app_" + operation, source.Sha256);
        Validate(plan, target);
        // This method never touches the source/target Profile, database, credentials or package.
        // This receipt preflight is NOT authentication of a selected package or proof of stopped
        // execution. Future provision must re-pin this exact receipt and authenticate the package.
        try
        {
            if (Exists(Path.Combine(applicationRoot, "Profiles", target))) throw Stop();
            PublishPlan(plan);
        }
        catch (Exception failure) { throw new ReservationIncomplete(plan, failure); }
        return plan;
    }

    private void PublishPlan(Plan plan)
    {
        string recovery = Path.Combine(applicationRoot, "Recovery");
        string targets = Path.Combine(recovery, "targets");
        EnsurePrivateDirectory(recovery);
        EnsurePrivateDirectory(targets);
        string reservation = Path.Combine(targets, plan.TargetProfileId);
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        // Reserve the name before writing. An empty/partial reservation still blocks startup.
        // No existing operation is reused, repaired, deleted or replayed after a failure.
        WindowsProfileSecurity.CreateDirectory(reservation, user.User!);
        using var pins = WindowsProfileSecurity.HoldDirectories(reservation);
        WindowsProfileSecurity.ValidatePrivate(pins.Handles[^1], user.User!, directory: true);
        PublishRecord(reservation, "plan", Entropy(plan.TargetProfileId), plan);
        if (Inspect(plan.TargetProfileId) != plan) throw Stop();
    }

    private static void PublishRecord<T>(string directory, string name, byte[] entropy, T record)
    {
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        byte[] plaintext = JsonSerializer.SerializeToUtf8Bytes(record, Json);
        byte[] encrypted;
        try
        {
            if (plaintext.Length > MaximumRecordBytes) throw Stop();
            encrypted = ProtectedData.Protect(plaintext, entropy, DataProtectionScope.CurrentUser);
            if (encrypted.Length > MaximumRecordBytes) throw Stop();
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
        string pending = Path.Combine(directory, name + ".pending"), final = Path.Combine(directory, name + ".dpapi");
        // CREATE_NEW, force then same-directory no-replace publication. Leave interrupted evidence.
        using (var output = new FileStream(pending, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None))
        {
            ValidateFileAcl(output, user.User!);
            output.Write(encrypted);
            output.Flush(flushToDisk: true);
        }
        File.Move(pending, final, overwrite: false);
    }

    internal Plan Inspect(string targetProfileId)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        RequireHex(targetProfileId, 32);
        string recovery = Path.Combine(applicationRoot, "Recovery"), targets = Path.Combine(recovery, "targets");
        string reservation = Path.Combine(targets, targetProfileId);
        using var pins = WindowsProfileSecurity.HoldDirectories(reservation);
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        foreach (string folder in new[] { applicationRoot, recovery, targets, reservation })
        {
            using var directoryPins = WindowsProfileSecurity.HoldDirectories(folder);
            WindowsProfileSecurity.ValidatePrivate(directoryPins.Handles[^1], user.User!, directory: true);
        }
        // A pending or unknown file makes the whole operation unknown, even beside a valid plan.
        string[] files = Directory.EnumerateFileSystemEntries(reservation).Take(2).ToArray();
        if (files.Length != 1 || Path.GetFileName(files[0]) != "plan.dpapi") throw Stop();
        var plan = ReadRecord<Plan>(files[0], Entropy(targetProfileId));
        Validate(plan, targetProfileId);
        return plan;
    }

    private static T ReadRecord<T>(string path, byte[] entropy) where T : class
    {
        using var input = OpenRecord(path);
        return ReadRecord<T>(input, entropy);
    }

    private static T ReadRecord<T>(FileStream input, byte[] entropy) where T : class
    {
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        ValidateFileAcl(input, user.User!);
        if (input.Length is < 1 or > MaximumRecordBytes) throw Stop();
        byte[] encrypted = new byte[checked((int)input.Length)];
        input.ReadExactly(encrypted);
        byte[] plaintext = ProtectedData.Unprotect(encrypted, entropy, DataProtectionScope.CurrentUser);
        try
        {
            if (plaintext.Length > MaximumRecordBytes) throw Stop();
            using var document = JsonDocument.Parse(plaintext, new JsonDocumentOptions { MaxDepth = 4 });
            var names = new HashSet<string>(StringComparer.Ordinal);
            if (document.RootElement.ValueKind != JsonValueKind.Object) throw Stop();
            foreach (var field in document.RootElement.EnumerateObject())
                if (!names.Add(field.Name)) throw Stop();
            return document.Deserialize<T>(Json) ?? throw Stop();
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }

    // Additive denial only. Until the verified publication/activation slice exists, NO registered
    // recovery target is startable, including a caller-written "ACTIVE" or READY-looking file.
    // The caller supplies only an already-derived Profile root; this never reads record contents.
    internal static void RequireOrdinaryProfile(string profileRoot)
    {
        string id = Path.GetFileName(profileRoot);
        RequireHex(id, 32);
        string profiles = Path.GetDirectoryName(profileRoot) ?? throw Stop();
        if (Path.GetFileName(profiles) != "Profiles") throw Stop();
        string app = Path.GetDirectoryName(profiles) ?? throw Stop();
        string recovery = Path.Combine(app, "Recovery");
        if (!Exists(recovery)) return; // Only actual not-found counts; access errors propagate.
        using var pins = WindowsProfileSecurity.HoldDirectories(recovery);
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        WindowsProfileSecurity.ValidatePrivate(pins.Handles[^1], user.User!, directory: true);
        string targets = Path.Combine(recovery, "targets");
        // Interrupted registry initialization is unknown, never "no registration".
        using var targetPins = WindowsProfileSecurity.HoldDirectories(targets);
        WindowsProfileSecurity.ValidatePrivate(targetPins.Handles[^1], user.User!, directory: true);
        if (Exists(Path.Combine(targets, id))) throw Stop();
    }

    private void EnsurePrivateDirectory(string path)
    {
        if (!Exists(path))
        {
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            WindowsProfileSecurity.CreateDirectory(path, user.User!);
        }
        HoldPrivateDirectory(path);
    }

    private void HoldPrivateDirectory(string path)
    {
        var pins = WindowsProfileSecurity.HoldDirectories(path);
        try
        {
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            WindowsProfileSecurity.ValidatePrivate(pins.Handles[^1], user.User!, directory: true);
            held.Add(pins);
        }
        catch { pins.Dispose(); throw; }
    }

    private static bool Exists(string path)
    {
        try { _ = File.GetAttributes(path); return true; }
        catch (FileNotFoundException) { return false; }
        catch (DirectoryNotFoundException) { return false; }
    }

    private static void Validate(Plan plan, string target)
    {
        RequireHex(plan.OperationId, 32); RequireHex(plan.TargetProfileId, 32);
        RequireHex(plan.SourceProfileId, 32); RequireHex(plan.BackupId, 32);
        RequireHex(plan.PayloadSha256, 64); RequireHex(plan.ManifestSha256, 64);
        // Historical v1 plans remain inspectable intentions, never registered-source authority.
        if (plan.Version == 2) RequireHex(plan.SourceRegistrationSha256!, 64);
        else if (plan.Version != 1 || plan.SourceRegistrationSha256 is not null) throw Stop();
        if (plan.Kind != "RECOVERY_PLAN_ONLY" || plan.State != "PREPARING"
            || plan.TargetProfileId != target || plan.TargetProfileId == plan.SourceProfileId
            || plan.DatabaseName != "workspace_recovery_" + plan.OperationId
            || plan.OwnerRole != "workspace_recovery_owner_" + plan.OperationId
            || plan.AppRole != "workspace_recovery_app_" + plan.OperationId) throw Stop();
    }

    private static void RequireHex(string value, int length)
    {
        if (value is null || value.Length != length || value.Any(c => c is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
            throw Stop();
    }

    private static byte[] Entropy(string id) => Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/plan/v1/" + id);
    private static IOException Stop() => new("Recovery candidate or registration unavailable.");

    private static void ValidateFileAcl(FileStream stream, SecurityIdentifier user)
    {
        var security = stream.GetAccessControl();
        // Like the existing selection store: the private, pinned parent owns inherited grants.
        security.SetAccessRuleProtection(true, preserveInheritance: true);
        WindowsProfileSecurity.ValidatePrivate(security, user);
    }

    private static FileStream OpenRecord(string path, bool write = false)
    {
        // OPEN_REPARSE_POINT: reject links on the opened object, not via a racy path precheck.
        // Optional WRITE is used only for the empty early-admission lock, never a record write.
        var handle = CreateFileW(@"\\?\" + path, write ? 0xC0020080 : 0x80020080, 1, IntPtr.Zero, 3, 0x00200000, IntPtr.Zero);
        try
        {
            if (handle.IsInvalid || !GetFileInformationByHandle(handle, out FileInformation info)
                || (info.Attributes & 0x410) != 0 || info.Links != 1) throw Stop();
            var final = new StringBuilder(32768);
            uint length = GetFinalPathNameByHandleW(handle, final, (uint)final.Capacity, 0);
            if (length == 0 || length >= final.Capacity || !string.Equals(final.ToString(), @"\\?\" + path, StringComparison.OrdinalIgnoreCase)) throw Stop();
            return new FileStream(handle, write ? FileAccess.ReadWrite : FileAccess.Read);
        }
        catch { handle.Dispose(); throw; }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        for (int i = held.Count - 1; i >= 0; i--) held[i].Dispose();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct FileInformation
    {
        public uint Attributes, CreationLow, CreationHigh, AccessLow, AccessHigh, WriteLow, WriteHigh;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr attributes, uint disposition, uint flags, IntPtr template);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll")]
    private static extern bool GetFileInformationByHandle(SafeFileHandle handle, out FileInformation info);
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path, uint size, uint flags);
}
#endif
