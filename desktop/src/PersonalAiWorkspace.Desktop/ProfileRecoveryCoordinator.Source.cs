#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    // Consumer contract only. There is deliberately no product API to mint this receipt from
    // caller metadata, a password, a package completion marker or a maintenance inspection.
    // ReserveBackupIntent now allocates the non-authorizing ID and pins the source directories.
    // The future backup owner must additionally bind the actual database before backup and
    // publish only after ciphertext completion AND successful temporary-role reclamation.
    // SourceSystemIdentifier + SourceDatabaseOid identify the PG17 dataset at the recorded
    // loopback port; the 48-hex native identities encode the existing Windows FILE_ID_INFO.
    internal sealed record SourceRegistration(int Version, string Kind, string State, string BackupId,
        string SourceProfileId, string DatabaseLogicalIdentity, string SourceRootIdentity, string SourceDataIdentity,
        ulong SourceSystemIdentifier, uint SourceDatabaseOid, int SourcePort, string SourceDatabase, string SourceRole,
        string Format, int FormatVersion, int PostgresqlMajor, string Scope, long PayloadBytes,
        string PayloadSha256, string ManifestSha256, string CredentialCleanup)
    {
        public override string ToString() => "SourceRegistration[private]";
    }

    private sealed class RegisteredSource(SourceRegistration registration, string sha256, List<IDisposable> pins) : IDisposable
    {
        internal SourceRegistration Registration { get; } = registration;
        internal string Sha256 { get; } = sha256;
        public void Dispose() { for (int i = pins.Count - 1; i >= 0; i--) pins[i].Dispose(); }
    }

    private RegisteredSource PinRegisteredSource(string backupId, string payloadSha256, string manifestSha256)
    {
        RequireHex(backupId, 32); RequireHex(payloadSha256, 64); RequireHex(manifestSha256, 64);
        string recovery = Path.Combine(applicationRoot, "Recovery");
        string sources = Path.Combine(recovery, "sources"), source = Path.Combine(sources, backupId);
        var pins = new List<IDisposable>();
        try
        {
            using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
            // Missing/unreadable/incomplete means unavailable. Never initialize or repair here.
            foreach (string directory in new[] { recovery, sources, source })
            {
                var pin = WindowsProfileSecurity.HoldDirectories(directory);
                pins.Add(pin);
                WindowsProfileSecurity.ValidatePrivate(pin.Handles[^1], user.User!, directory: true);
            }
            string[] files = Directory.EnumerateFileSystemEntries(source).Take(2).ToArray();
            if (files.Length != 1 || Path.GetFileName(files[0]) != "source.dpapi") throw Stop();
            var input = OpenRecord(Path.Combine(source, "source.dpapi"));
            pins.Add(input); // No write/delete sharing, including during plan publication/readback.
            var registration = ReadRecord<SourceRegistration>(input, SourceEntropy(backupId));
            Validate(registration, backupId);
            if (registration.PayloadSha256 != payloadSha256 || registration.ManifestSha256 != manifestSha256) throw Stop();
            input.Position = 0;
            string sha256 = Convert.ToHexStringLower(SHA256.HashData(input));
            return new RegisteredSource(registration, sha256, pins);
        }
        catch
        {
            for (int i = pins.Count - 1; i >= 0; i--) pins[i].Dispose();
            throw;
        }
    }

    private static void Validate(SourceRegistration source, string backupId)
    {
        if (source.Kind != "WORKSPACE_BACKUP_SOURCE" || source.State != "BACKUP_REGISTERED") throw Stop();
        ValidateSourceFields(source, backupId);
    }

    private static void ValidateSourceFields(SourceRegistration source, string backupId)
    {
        RequireHex(source.BackupId, 32); RequireHex(source.SourceProfileId, 32);
        RequireHex(source.SourceRootIdentity, 48); RequireHex(source.SourceDataIdentity, 48);
        RequireHex(source.PayloadSha256, 64); RequireHex(source.ManifestSha256, 64);
        if (source.Version != 1 || source.BackupId != backupId || source.CredentialCleanup != "RECLAIMED"
            || source.Format != "personal-ai-workspace.postgresql-encrypted-stream" || source.FormatVersion != 3
            || source.PostgresqlMajor != 17 || source.Scope != "WORKSPACE_KNOWLEDGE_ONLY"
            || !Guid.TryParseExact(source.DatabaseLogicalIdentity, "D", out var logical) || logical == Guid.Empty
            || logical.ToString("D") != source.DatabaseLogicalIdentity
            || source.SourceRootIdentity == source.SourceDataIdentity || source.SourceSystemIdentifier == 0
            || source.SourceDatabaseOid == 0 || source.SourcePort is < 1 or > 65535
            || source.PayloadBytes is < 1 || source.PayloadBytes > (1L << 40) + (1L << 40) / 100) throw Stop();
        // These names are provenance only; they are never interpolated into SQL or used to connect.
        foreach (string name in new[] { source.SourceDatabase, source.SourceRole })
            if (string.IsNullOrWhiteSpace(name) || Encoding.UTF8.GetByteCount(name) > 63 || name.Any(char.IsControl)) throw Stop();
    }

    private static byte[] SourceEntropy(string backupId) =>
        Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/source/v1/" + backupId);
}
#endif
