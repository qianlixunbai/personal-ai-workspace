#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using Microsoft.Win32.SafeHandles;
using PersonalAiWorkspace.WindowsSecurity;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    // There is deliberately no personal factory. A future factory must bind a reviewed native
    // Profile/SQL identity and specific authorization, including the connection outage window.
    // A journal is an intention, not writer exclusion, model completion, or source authority.
    internal sealed class LegacyRuleChange : IDisposable
    {
        internal sealed record Identity(string FileId, string Sha256);
        internal sealed record Intent(int Version, string Kind, string OperationId, string Root,
            Identity Original, Identity Installed);
        internal enum State { Prepared, InstallInterrupted, Installed, RestoreInterrupted, Restored, Conflict }
        private readonly ProfileRecoveryCoordinator pins;
        private readonly FileStream lease;
        private readonly Intent intent;
        private bool closed;
        private string Hba => Path.Combine(intent.Root, "pgdata", "pg_hba.conf");
        private string Original => Hba + ".paw-" + intent.OperationId + ".original";
        private string Candidate => Hba + ".paw-" + intent.OperationId + ".candidate";
        private string Retired => Hba + ".paw-" + intent.OperationId + ".retired";
        private static byte[] Purpose(string root) => Encoding.UTF8.GetBytes("PersonalAIWorkspace/Recovery/synthetic-rule-intent/v1/" + root);

        private LegacyRuleChange(ProfileRecoveryCoordinator pins, FileStream lease, Intent intent, string directory)
        { this.pins = pins; this.lease = lease; this.intent = intent; }

        // Only new, private synthetic roots are admitted by the manual acceptance caller.
        private static LegacyRuleChange PrepareSynthetic(string root, byte[] rules)
        {
            ValidateSyntheticRoot(root);
            if (rules.Length is < 1 or > 262144) throw Stop();
            var pins = new ProfileRecoveryCoordinator(root);
            FileStream? lease = null;
            try
            {
                pins.HoldPrivateDirectory(Path.Combine(root, "pgdata"));
                string directory = Path.Combine(root, "rule-change");
                if (Exists(directory)) throw Stop();
                pins.EnsurePrivateDirectory(directory);
                lease = new FileStream(Path.Combine(directory, "owner.lock"), FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None);
                using var user = WindowsIdentity.GetCurrent(); ValidateFileAcl(lease, user.User!); lease.Flush(true);
                string hba = Path.Combine(root, "pgdata", "pg_hba.conf"), operation = Guid.NewGuid().ToString("N");
                using var original = OpenRule(hba, mutate: true);
                Identity originalIdentity = Identify(original);
                string candidate = hba + ".paw-" + operation + ".candidate";
                using (var output = new FileStream(candidate, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None))
                { ValidateFileAcl(output, user.User!); output.Write(rules); output.Flush(true); }
                using var next = OpenRule(candidate, mutate: true);
                var intent = new Intent(1, "SYNTHETIC_RULE_CHANGE_ONLY", operation, root, originalIdentity, Identify(next));
                PublishRecord(directory, "intent", Purpose(root), intent);
                if (ReadRecord<Intent>(Path.Combine(directory, "intent.dpapi"), Purpose(root)) != intent) throw Stop();
                return new LegacyRuleChange(pins, lease, intent, directory);
            }
            catch { lease?.Dispose(); pins.Dispose(); throw; }
        }

        private static LegacyRuleChange OpenSynthetic(string root)
        {
            ValidateSyntheticRoot(root);
            var pins = new ProfileRecoveryCoordinator(root);
            FileStream? lease = null;
            try
            {
                pins.HoldPrivateDirectory(Path.Combine(root, "pgdata"));
                string directory = Path.Combine(root, "rule-change"); pins.HoldPrivateDirectory(directory);
                lease = OpenRecord(Path.Combine(directory, "owner.lock"), write: true);
                using var user = WindowsIdentity.GetCurrent(); ValidateFileAcl(lease, user.User!);
                if (lease.Length != 0 || !Directory.GetFileSystemEntries(directory).Select(Path.GetFileName).Order()
                    .SequenceEqual(new[] { "intent.dpapi", "owner.lock" })) throw Stop();
                var intent = ReadRecord<Intent>(Path.Combine(directory, "intent.dpapi"), Purpose(root));
                RequireHex(intent.OperationId, 32); ValidateIdentity(intent.Original); ValidateIdentity(intent.Installed);
                if (intent.Version != 1 || intent.Kind != "SYNTHETIC_RULE_CHANGE_ONLY" || intent.Root != root) throw Stop();
                return new LegacyRuleChange(pins, lease, intent, directory);
            }
            catch { lease?.Dispose(); pins.Dispose(); throw; }
        }

        private static void ValidateSyntheticRoot(string root)
        {
            if (Path.GetFullPath(root) != root || Path.GetDirectoryName(root) != Path.TrimEndingDirectorySeparator(Path.GetTempPath())
                || !Path.GetFileName(root).StartsWith("paw-legacy-fence-", StringComparison.Ordinal)) throw Stop();
            RequireHex(Path.GetFileName(root)["paw-legacy-fence-".Length..], 32);
        }
        private static void ValidateIdentity(Identity identity)
        { RequireHex(identity.FileId, 24); RequireHex(identity.Sha256, 64); }

        // Open all four objects before classifying. ACL, links, file IDs and bytes are checked;
        // even identical replacement bytes with a different file ID are a conflict.
        internal State Inspect()
        {
            ObjectDisposedException.ThrowIf(closed, this);
            using var hba = OpenOptional(Hba); using var original = OpenOptional(Original);
            using var candidate = OpenOptional(Candidate); using var retired = OpenOptional(Retired);
            Identity? h = hba is null ? null : Identify(hba), o = original is null ? null : Identify(original);
            Identity? c = candidate is null ? null : Identify(candidate), r = retired is null ? null : Identify(retired);
            if (h == intent.Original && o is null && c == intent.Installed && r is null) return State.Prepared;
            if (h is null && o == intent.Original && c == intent.Installed && r is null) return State.InstallInterrupted;
            if (h == intent.Installed && o == intent.Original && c is null && r is null) return State.Installed;
            if (h is null && o == intent.Original && c is null && r == intent.Installed) return State.RestoreInterrupted;
            if (h == intent.Original && o is null && c is null && r == intent.Installed) return State.Restored;
            return State.Conflict;
        }

        internal void Install()
        {
            if (Inspect() != State.Prepared) throw Stop();
            MoveExact(Hba, Original, intent.Original);
            // No automatic rollback on failure: interruption is independently inspectable.
            MoveExact(Candidate, Hba, intent.Installed);
            if (Inspect() != State.Installed) throw Stop();
        }

        // Explicit synthetic reconciliation only. Opening/disposing never changes a rule file.
        // No replay of install, no deletion, and no overwrite of any intervening admin change.
        internal State Restore()
        {
            State state = Inspect();
            if (state == State.Installed)
            {
                MoveExact(Hba, Retired, intent.Installed);
                state = Inspect();
            }
            if (state is State.InstallInterrupted or State.RestoreInterrupted)
                MoveExact(Original, Hba, intent.Original);
            else if (state is not (State.Prepared or State.Restored)) throw Stop();
            state = Inspect();
            if (state is not (State.Prepared or State.Restored)) throw Stop();
            return state;
        }

        private static FileStream? OpenOptional(string path)
        { try { return OpenRule(path, mutate: false); } catch (FileNotFoundException) { return null; } }

        private static FileStream OpenRule(string path, bool mutate)
        {
            // DELETE on this exact opened object plus no WRITE/DELETE sharing: the checked
            // object cannot be swapped before its handle-based rename. Destination is CREATE_NEW.
            var handle = OpenRuleHandle(@"\\?\" + path, 0x80020080u | (mutate ? 0x10000u : 0), 1, IntPtr.Zero, 3, 0x00200000, IntPtr.Zero);
            try
            {
                if (handle.IsInvalid)
                {
                    if (Marshal.GetLastPInvokeError() == 2) throw new FileNotFoundException();
                    throw Stop();
                }
                if (!GetFileInformationByHandle(handle, out var info) || (info.Attributes & 0x410) != 0 || info.Links != 1) throw Stop();
                var final = new StringBuilder(32768);
                uint length = GetFinalPathNameByHandleW(handle, final, (uint)final.Capacity, 0);
                if (length == 0 || length >= final.Capacity || !string.Equals(final.ToString(), @"\\?\" + path, StringComparison.OrdinalIgnoreCase)) throw Stop();
                var stream = new FileStream(handle, FileAccess.Read);
                try { using var user = WindowsIdentity.GetCurrent(); ValidateFileAcl(stream, user.User!); return stream; }
                catch { stream.Dispose(); throw; }
            }
            catch { handle.Dispose(); throw; }
        }
        private static Identity Identify(FileStream stream)
        {
            if (stream.Length is < 1 or > 262144 || !GetFileInformationByHandle(stream.SafeFileHandle, out var info)) throw Stop();
            stream.Position = 0;
            return new Identity($"{info.Volume:x8}{info.IndexHigh:x8}{info.IndexLow:x8}", Convert.ToHexStringLower(SHA256.HashData(stream)));
        }
        private static void MoveExact(string from, string to, Identity expected)
        {
            using var source = OpenRule(from, mutate: true);
            if (Identify(source) != expected) throw Stop();
            RenameNoReplace(source.SafeFileHandle, to);
        }

        // Two no-overwrite renames preserve file identity and ACL, unlike path-check + replace.
        // The name can be temporarily absent: this can deny ALL new cluster connections, and
        // a crash can extend that outage. This tradeoff MUST be approved before any real use.
        private static void RenameNoReplace(SafeFileHandle source, string destination)
        {
            byte[] name = Encoding.Unicode.GetBytes(@"\\?\" + destination);
            int offset = checked((int)Marshal.OffsetOf<RenameInfo>(nameof(RenameInfo.FirstCharacter)));
            int size = offset + name.Length + 2; // FILE_RENAME_INFO FileName is also NUL terminated.
            IntPtr memory = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.Copy(new byte[size], 0, memory, size); // ReplaceIfExists = FALSE, RootDirectory = NULL
                Marshal.WriteInt32(memory, checked((int)Marshal.OffsetOf<RenameInfo>(nameof(RenameInfo.FileNameLength))), name.Length);
                Marshal.Copy(name, 0, memory + offset, name.Length);
                if (!SetFileInformationByHandle(source, 3, memory, checked((uint)size))) throw Stop();
            }
            finally { Marshal.FreeHGlobal(memory); }
        }
        [StructLayout(LayoutKind.Sequential)] private struct RenameInfo
        { internal uint ReplaceIfExists; internal IntPtr RootDirectory; internal uint FileNameLength; internal char FirstCharacter; }
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetFileInformationByHandle(SafeFileHandle handle, int kind, IntPtr data, uint size);
        [DllImport("kernel32.dll", EntryPoint = "CreateFileW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
        private static extern SafeFileHandle OpenRuleHandle(string path, uint access, uint share, IntPtr attributes, uint disposition, uint flags, IntPtr template);
        public void Dispose() { if (closed) return; closed = true; lease.Dispose(); pins.Dispose(); }
    }
}
#endif
