#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Buffers.Binary;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using Microsoft.Win32.SafeHandles;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    // Preparation only, before any database access, role creation or backup. This is deliberately
    // separate from sources/<id>/source.dpapi: an intent must never become SQL-source authority.
    internal sealed record BackupIntent(int Version, string Kind, string State, string BackupId,
        string SourceProfileId, string DatabaseLogicalIdentity, string SourceRootIdentity, string SourceDataIdentity)
    {
        public override string ToString() => "BackupIntent[private]";
    }

    internal sealed class BackupIntentIncomplete(BackupIntent attempt, Exception cause)
        : IOException("Backup preparation result requires inspection.", cause)
    {
        internal BackupIntent Attempt { get; } = attempt;
    }

    private BackupIntent? backupIntent;

    internal BackupIntent ReserveBackupIntent()
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (authority is null) throw Stop();
        authority.Validate();
        Mmf3ProfileNative.Verify(authority);
        if (!string.Equals(authority.Root, Path.Combine(applicationRoot, "Profiles", authority.RunId),
            StringComparison.OrdinalIgnoreCase)) throw Stop();
        return ReserveBackupIntentForProfile(authority.RunId);
    }

    // Private synthetic-test seam; product callers cannot choose a source/path/identity/digest.
    private BackupIntent ReserveBackupIntentForProfile(string sourceProfileId)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (backupIntent is not null) throw Stop(); // No replay even when publication was uncertain.
        RequireHex(sourceProfileId, 32);
        string profiles = Path.Combine(applicationRoot, "Profiles"), root = Path.Combine(profiles, sourceProfileId);
        RequireOrdinaryProfile(root);
        HoldPrivateDirectory(profiles);
        string rootIdentity = HoldSourceDirectoryIdentity(root);
        string dataIdentity = HoldSourceDirectoryIdentity(Path.Combine(root, "data"));
        authority?.Validate(); // The retained handles protect the checked source from rename.
        var attempt = new BackupIntent(1, "WORKSPACE_BACKUP_INTENT_ONLY", "PREPARING",
            Guid.NewGuid().ToString("N"), sourceProfileId, Guid.NewGuid().ToString("D"), rootIdentity, dataIdentity);
        Validate(attempt, attempt.BackupId);
        backupIntent = attempt;
        try
        {
            string recovery = Path.Combine(applicationRoot, "Recovery"), intents = Path.Combine(recovery, "source-intents");
            EnsurePrivateDirectory(recovery);
            // An incomplete Recovery root continues to deny ordinary startup. Initialize the
            // empty target registry as the existing runtime journal does, never repair records.
            EnsurePrivateDirectory(Path.Combine(recovery, "targets"));
            EnsurePrivateDirectory(intents);
            string directory = Path.Combine(intents, attempt.BackupId);
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            WindowsProfileSecurity.CreateDirectory(directory, user.User!);
            HoldPrivateDirectory(directory);
            PublishRecord(directory, "intent", BackupIntentEntropy(attempt.BackupId), attempt);
            if (InspectBackupIntent(attempt.BackupId) != attempt) throw Stop();
            return attempt;
        }
        catch (Exception failure) { throw new BackupIntentIncomplete(attempt, failure); }
    }

    // Read-only reconciliation of this exact attempt, including after an uncertain publication.
    // Does not reopen the source, promote state, resume backup or mint a completed registration.
    internal BackupIntent InspectBackupIntent(string backupId)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        RequireHex(backupId, 32);
        string recovery = Path.Combine(applicationRoot, "Recovery"), intents = Path.Combine(recovery, "source-intents");
        string directory = Path.Combine(intents, backupId);
        using var pins = WindowsProfileSecurity.HoldDirectories(directory);
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        foreach (string folder in new[] { applicationRoot, recovery, intents, directory })
        {
            using var folderPins = WindowsProfileSecurity.HoldDirectories(folder);
            WindowsProfileSecurity.ValidatePrivate(folderPins.Handles[^1], user.User!, directory: true);
        }
        string[] files = Directory.EnumerateFileSystemEntries(directory).Take(2).ToArray();
        if (files.Length != 1 || Path.GetFileName(files[0]) != "intent.dpapi") throw Stop();
        var intent = ReadRecord<BackupIntent>(files[0], BackupIntentEntropy(backupId));
        Validate(intent, backupId);
        return intent;
    }

    private string HoldSourceDirectoryIdentity(string directory)
    {
        var pin = WindowsProfileSecurity.HoldDirectories(directory);
        try
        {
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            var handle = pin.Handles[^1];
            WindowsProfileSecurity.ValidatePrivate(handle, user.User!, directory: true);
            if (!GetFileInformationByHandleEx(handle, 18, out SourceFileId id, 24)) throw Stop();
            // Raw FILE_ID_INFO bytes, matching TrustedDatabaseConfiguration.Identity.read.
            // Do not use the helper DLL's different colon-delimited display representation.
            Span<byte> bytes = stackalloc byte[24];
            BinaryPrimitives.WriteUInt64LittleEndian(bytes, id.Volume);
            BinaryPrimitives.WriteUInt64LittleEndian(bytes[8..], id.Low);
            BinaryPrimitives.WriteUInt64LittleEndian(bytes[16..], id.High);
            string identity = Convert.ToHexStringLower(bytes);
            held.Add(pin);
            return identity;
        }
        catch { pin.Dispose(); throw; }
    }

    private static void Validate(BackupIntent intent, string backupId)
    {
        RequireHex(intent.BackupId, 32); RequireHex(intent.SourceProfileId, 32);
        RequireHex(intent.SourceRootIdentity, 48); RequireHex(intent.SourceDataIdentity, 48);
        if (intent.Version != 1 || intent.Kind != "WORKSPACE_BACKUP_INTENT_ONLY" || intent.State != "PREPARING"
            || intent.BackupId != backupId || intent.SourceRootIdentity == intent.SourceDataIdentity
            || !Guid.TryParseExact(intent.DatabaseLogicalIdentity, "D", out var logical) || logical == Guid.Empty
            || logical.ToString("D") != intent.DatabaseLogicalIdentity) throw Stop();
    }

    private static byte[] BackupIntentEntropy(string backupId) =>
        Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/source-intent/v1/" + backupId);

    [StructLayout(LayoutKind.Sequential)] private struct SourceFileId { public ulong Volume, Low, High; }
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle, int kind, out SourceFileId info, uint size);
}
#endif
