using Microsoft.Win32.SafeHandles;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.NativeBootstrap;

internal static class TrustedNative
{
    internal const string JarHash = "b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0";
    internal const string DllHash = "5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023";
    internal const int DllLength = 273408;
    internal const string Entry = "com/sun/jna/win32-x86-64/jnidispatch.dll";
    internal const string FixturePrefix = ".personal-ai-workspace-native-bootstrap-fixture-";
    internal const string NativeDirectory = "jna-5.17.0-win32-x86-64";

    internal sealed record Result(string result, string mode, string trust, bool elevated,
        string owner, bool protectedDacl, bool onlyOwnerFullControl, string fileIdentity,
        int length, string sha256, string machine, string root, string dll, string runId)
    {
        // Bounded, fixed child-process protocol; never a reusable PASS ticket.
        internal string JavaAdmission() => string.Join('\t', "MMF3_NATIVE_V1", "DEVELOPMENT_ONLY", runId,
            Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(root)),
            Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(dll)), fileIdentity, sha256);
    }

    internal static Result Run(string mode, string fixtureId, string? artifact)
    {
        // This candidate cannot target production paths or arbitrary USERPROFILE input.
        if (!Regex.IsMatch(fixtureId, "\\A[0-9a-f]{32}\\z", RegexOptions.CultureInvariant))
            throw new Rejected("FIXTURE_ID");
        using var user = Win32.CurrentUser();
        bool profileMode = mode is "ProvisionProfileNative" or "VerifyProfileNative";
        if (profileMode && user.Elevated) throw new Rejected("ELEVATED_PROFILE");
        string profile = Win32.Profile(user.Token);
        Win32.ValidatePath(profile);
        string parent = Path.Combine(profile, ".personal-ai-workspace-mmf3-tests");
        string root = profileMode ? Path.Combine(parent, fixtureId) : Path.Combine(profile, FixturePrefix + fixtureId);
        string native = Path.Combine(root, NativeDirectory);
        string final = Path.Combine(native, "jnidispatch.dll");
        var pins = new List<SafeFileHandle>();
        try
        {
            // Root-to-leaf identity/ACL checks; metadata directory handles are NOT rename pins.
            string part = Path.GetPathRoot(profile)!;
            var ancestor = Win32.Open(part, true);
            pins.Add(ancestor);
            Win32.AncestorAcl(ancestor, user.Sid);
            foreach (string name in profile[part.Length..].Split('\\', StringSplitOptions.RemoveEmptyEntries))
            {
                part = Path.Combine(part, name);
                ancestor = Win32.Open(part, true);
                pins.Add(ancestor);
                Win32.AncestorAcl(ancestor, user.Sid);
            }
            bool provision = mode.StartsWith("Provision", StringComparison.Ordinal);
            if (profileMode)
            {
                // The published Profile Prepare owns creation. Never create/repair its root here.
                foreach (string directory in new[] { parent, root })
                {
                    var handle = Win32.Open(directory, true);
                    pins.Add(handle);
                    Win32.PrivateAcl(handle, user.Sid, true);
                }
                foreach (string name in new[] { "credentials", "model-state", "data", "webview2-profile",
                    "chrome-profile", "browser-test-extension", "evidence", "logs" })
                {
                    using var handle = Win32.Open(Path.Combine(root, name), true);
                    Win32.PrivateAcl(handle, user.Sid, true);
                }
            }
            // Authenticate source bytes before creating anything. Hash is compiled from the approved request.
            byte[]? bytes = provision ? Artifact(artifact!) : null;
            foreach (string directory in profileMode ? new[] { native } : new[] { root, native })
            {
                SafeFileHandle handle;
                if (provision)
                {
                    // New fixture only: no reuse, overwrite, permission repair or implicit update.
                    Win32.CreatePrivateDirectory(directory, user.Sid);
                }
                handle = Win32.Open(directory, true);
                pins.Add(handle);
                Win32.PrivateAcl(handle, user.Sid, true);
            }
            string identity;
            if (provision)
            {
                string staging = Path.Combine(native, ".staging-" + Guid.NewGuid().ToString("N") + ".dll");
                using var stage = Win32.CreatePrivateFile(staging, user.Sid);
                Win32.PrivateAcl(stage, user.Sid, false);
                string initial = Win32.Identity(stage, staging, false);
                RandomAccess.Write(stage, bytes!, 0);
                RandomAccess.FlushToDisk(stage);
                ValidateDll(ReadDll(stage));
                // Rename the same pinned object, never a path-based replacement; ReplaceIfExists=false.
                Win32.Publish(stage, final);
                identity = Win32.Identity(stage, final, false);
                if (identity != initial) throw new Rejected("PUBLISH_IDENTITY");
                Win32.PrivateAcl(stage, user.Sid, false);
                ValidateDll(ReadDll(stage));
                // A separate path reopen must address the very same published file.
                using var reopened = Win32.Open(final, false, true);
                if (Win32.Identity(reopened, final, false) != identity)
                    throw new Rejected("PUBLISH_IDENTITY");
                Win32.PrivateAcl(reopened, user.Sid, false);
                ValidateDll(ReadDll(reopened));
            }
            else
            {
                // Read data/attributes/security only. No write sharing: a concurrent writer fails closed.
                using var file = Win32.Open(final, false);
                identity = Win32.Identity(file, final, false);
                Win32.PrivateAcl(file, user.Sid, false);
                ValidateDll(ReadDll(file));
                if (Win32.Identity(file, final, false) != identity)
                    throw new Rejected("VERIFY_IDENTITY");
                Win32.PrivateAcl(file, user.Sid, false);
            }
            // Recheck held ancestor and directory descriptors before declaring the operation complete.
            int privateStart = pins.Count - (profileMode ? 3 : 2);
            for (int i = 0; i < privateStart; i++) Win32.AncestorAcl(pins[i], user.Sid);
            for (int i = privateStart; i < pins.Count; i++) Win32.PrivateAcl(pins[i], user.Sid, true);
            return new Result("PASS", mode, Program.Trust, user.Elevated, "CURRENT_EFFECTIVE_SID",
                true, true, identity, DllLength, DllHash, "0x8664", root, final, fixtureId);
        }
        finally
        {
            for (int i = pins.Count - 1; i >= 0; i--) pins[i].Dispose();
            // Failure evidence is deliberately retained. No ACL repair or filesystem cleanup.
        }
    }

    private static byte[] Artifact(string input)
    {
        Win32.ValidatePath(input);
        // Pin the source file against writes/deletes while checking the whole bounded JAR and extracting.
        using var handle = Win32.Open(input, false);
        long length = RandomAccess.GetLength(handle);
        if (length < DllLength || length > 8 * 1024 * 1024) throw new Rejected("ARTIFACT_SIZE");
        byte[] jar = new byte[(int)length];
        ReadExactly(handle, jar);
        if (Hash(jar) != JarHash) throw new Rejected("ARTIFACT_HASH");
        using var archive = new ZipArchive(new MemoryStream(jar), ZipArchiveMode.Read);
        var entries = archive.Entries.Where(entry => entry.FullName == Entry).ToArray();
        if (entries.Length != 1 || entries[0].Length != DllLength) throw new Rejected("ARTIFACT_ENTRY");
        using var stream = entries[0].Open();
        byte[] bytes = new byte[DllLength];
        stream.ReadExactly(bytes);
        if (stream.ReadByte() != -1) throw new Rejected("ARTIFACT_ENTRY");
        ValidateDll(bytes);
        return bytes;
    }

    private static byte[] ReadDll(SafeFileHandle handle)
    {
        if (RandomAccess.GetLength(handle) != DllLength) throw new Rejected("DLL_LENGTH");
        byte[] bytes = new byte[DllLength];
        ReadExactly(handle, bytes);
        return bytes;
    }

    private static void ReadExactly(SafeFileHandle handle, byte[] bytes)
    {
        int offset = 0;
        while (offset < bytes.Length)
        {
            int count = RandomAccess.Read(handle, bytes.AsSpan(offset), offset);
            if (count == 0) throw new Rejected("SHORT_READ");
            offset += count;
        }
    }

    private static string Hash(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    private static void ValidateDll(byte[] bytes)
    {
        if (bytes.Length != DllLength) throw new Rejected("DLL_LENGTH");
        if (Hash(bytes) != DllHash) throw new Rejected("DLL_HASH");
        int pe = BitConverter.ToInt32(bytes, 0x3c);
        if (bytes[0] != 'M' || bytes[1] != 'Z' || pe < 64 || pe > bytes.Length - 26
            || BitConverter.ToUInt32(bytes, pe) != 0x4550
            || BitConverter.ToUInt16(bytes, pe + 4) != 0x8664
            || BitConverter.ToUInt16(bytes, pe + 24) != 0x20b
            || (BitConverter.ToUInt16(bytes, pe + 22) & 0x2000) == 0)
            throw new Rejected("DLL_PE");
    }
}
