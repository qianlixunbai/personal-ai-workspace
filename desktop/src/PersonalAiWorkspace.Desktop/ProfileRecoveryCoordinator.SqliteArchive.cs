#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Buffers.Binary;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    internal sealed record ArchiveSummary(long Memory, long Conversations, long Messages, long Documents, long Revisions, long Answers)
    {
        internal static ArchiveSummary Read(JsonElement result)
        {
            if (result.GetProperty("stage").GetString() != "VERIFIED") throw new IOException();
            long Count(string name) => result.GetProperty(name).GetInt64();
            return new(Count("memory"), Count("conversations"), Count("messages"), Count("documents"), Count("revisions"), Count("answers"));
        }
    }

    internal async Task<(ArchiveSummary Summary, string? ProfileId)> RunSqliteArchiveAsync(
        string file, char[] password, bool restore, Func<ArchiveSummary, bool> confirmRestore)
    {
        if (authority is null || disposed || password.Length is < 16 or > 1024) throw Stop();
        var profile = authority;
        string evidence = Path.Combine(profile.Root, "evidence");
        string operation = Guid.NewGuid().ToString("N");
        string stage = Path.Combine(evidence, "profile-archive-" + operation);
        using var user = WindowsIdentity.GetCurrent(TokenAccessLevels.Query);
        using var evidencePin = WindowsProfileSecurity.HoldDirectories(evidence);
        WindowsProfileSecurity.ValidatePrivate(evidencePin.Handles[^1], user.User!, directory: true);
        string profileParent = WindowsProfileSecurity.Parent();
        if (!Path.IsPathFullyQualified(file) || !string.Equals(Path.GetFullPath(file), file, StringComparison.Ordinal)
            || !file.EndsWith(".workspace-profile.pgp", StringComparison.OrdinalIgnoreCase)
            || file.StartsWith(@"\\", StringComparison.Ordinal) || file.AsSpan(2).Contains(':')
            || file.StartsWith(Path.GetDirectoryName(profileParent)! + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw Stop();
        using var fileParentPin = WindowsProfileSecurity.HoldDirectories(Path.GetDirectoryName(file)!);
        using var archiveInput = restore ? OpenRecord(file) : null;
        if (!restore && Exists(file)) throw Stop();
        if (!restore && Exists(Path.Combine(profile.Root, "data", "database.dpapi")))
            throw new IOException("POSTGRESQL_SOURCE");

        using var runtimeLock = OpenRecord(Path.Combine(evidence, "runtime-admission.lock"), write: true);
        ValidateFileAcl(runtimeLock, user.User!);
        if (runtimeLock.Length != 0) throw Stop();
        if (!restore)
        {
            var history = ReadRuntimeHistory(profile.RunId);
            if (history.State != "EXIT_OBSERVATIONS_COMPLETE" || history.NativeContextClosed != history.RegisteredAttempts)
                throw new IOException("PROFILE_CLOSE_UNCONFIRMED");
        }
        using var sources = TrustedDevelopmentRuntimeSources.Open();
        WindowsProfileSecurity.CreateDirectory(stage, user.User!);
        try
        {
            using var stagePin = WindowsProfileSecurity.HoldDirectories(stage);
            ArchiveSummary summary = await RunArchiveChildAsync(profile, sources, runtimeLock, operation, file, password, restore);
            if (!restore)
            {
                await PublishArchiveAsync(Path.Combine(stage, "backup.pgp"), file);
                return (summary, null);
            }
            if (!confirmRestore(summary)) return (summary, null);
            var id = Guid.NewGuid().ToString("N");
            string target = Mmf3ProfileState.Prepare(id);
            using var targetPin = Mmf3ProfileState.Pin(id);
            string data = Path.Combine(target, "data");
            // This freshly reserved Profile has no native library. It cannot pass admission
            // until the fully verified data directory is in place and Provision succeeds.
            Directory.Delete(data);
            Directory.Move(Path.Combine(stage, "verified"), data);
            Mmf3ProfileNative.Provision(id, sources.JnaJar);
            _ = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.OpenOnceArgument, id]);
            return (summary, id);
        }
        finally { CleanupArchiveStage(stage); }
    }

    private static async Task<ArchiveSummary> RunArchiveChildAsync(Mmf3AcceptanceLaunch profile,
        TrustedDevelopmentRuntimeSources sources, FileStream runtimeLock, string operation,
        string file, char[] password, bool restore)
    {
        using var deadline = new CancellationTokenSource(TimeSpan.FromHours(2));
        var token = deadline.Token;
        using var child = new Process { StartInfo = sources.StartInfo(profile, sqliteProfileArchive: true) };
        using var job = RuntimeProcessJob.Create();
        if (!child.Start()) throw Stop();
        try
        {
            job.Assign(child);
            var hello = await MaintenanceRuntimeHandoff.Read(child, token);
            if (hello.GetProperty("stage").GetString() != "ARCHIVE_HELLO"
                || hello.GetProperty("pid").GetInt32() != child.Id
                || hello.GetProperty("profile").GetString() != profile.RunId) throw Stop();
            var handle = DuplicateExclusion(runtimeLock, child);
            await MaintenanceRuntimeHandoff.Write(child, new { action = restore ? "RESTORE" : "BACKUP",
                handle, operation, file = restore ? file : "" }, token);
            byte[] secret = new byte[2 + password.Length * 2];
            try
            {
                BinaryPrimitives.WriteUInt16BigEndian(secret, (ushort)password.Length);
                for (int i = 0; i < password.Length; i++) BinaryPrimitives.WriteUInt16BigEndian(secret.AsSpan(2 + i * 2), (ushort)password[i]);
                await child.StandardInput.BaseStream.WriteAsync(secret, token);
            }
            finally { CryptographicOperations.ZeroMemory(secret); }
            child.StandardInput.Close();
            var result = ArchiveSummary.Read(await MaintenanceRuntimeHandoff.Read(child, token));
            await child.WaitForExitAsync(token);
            if (!child.WaitForExit(10000) || child.ExitCode != 0 || job.ActiveProcessCount != 0
                || child.StandardOutput.BaseStream.ReadByte() != -1 || child.StandardError.BaseStream.ReadByte() != -1) throw Stop();
            return result;
        }
        finally
        {
            job.Dispose();
            if (!child.WaitForExit(10000))
            {
                child.Kill();
                if (!child.WaitForExit(10000)) throw Stop();
            }
        }
    }

    private static async Task PublishArchiveAsync(string verified, string destination)
    {
        string staging = Path.Combine(Path.GetDirectoryName(destination)!, ".workspace-backup-" + Guid.NewGuid().ToString("N"));
        bool created = false;
        try
        {
            using (var source = OpenRecord(verified))
            using (var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
            {
                created = true;
                await source.CopyToAsync(output);
                output.Flush(flushToDisk: true);
            }
            File.Move(staging, destination, overwrite: false);
            created = false;
        }
        finally { if (created) File.Delete(staging); }
    }

    private static void CleanupArchiveStage(string path)
    {
        foreach (var child in Directory.EnumerateFileSystemEntries(path))
        {
            var attributes = File.GetAttributes(child);
            if ((attributes & FileAttributes.ReparsePoint) != 0) throw Stop();
            if ((attributes & FileAttributes.Directory) != 0) CleanupArchiveStage(child);
            else File.Delete(child);
        }
        Directory.Delete(path);
    }
}
#endif
