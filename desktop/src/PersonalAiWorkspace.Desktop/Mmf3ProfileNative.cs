#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace PersonalAiWorkspace.Desktop;

// Development-only: reviewed local .NET/JVM/build sources remain an explicit prerequisite.
// No GUI, credential, Java Runtime, or model operation is performed here.
internal static class Mmf3ProfileNative
{
    internal const string DllHash = "5a7ff949f6d93d86491eb5b26b1cfc60051168a60622650224b89995ac420023";
    internal static string Helper => Path.Combine(AppContext.BaseDirectory, "NativeBootstrap", "PersonalAiWorkspace.NativeBootstrap.exe");

    internal static void Verify(Mmf3AcceptanceLaunch launch)
    {
        try
        {
            ValidateHelper();
            var start = new ProcessStartInfo(Helper) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = Path.GetDirectoryName(Helper)! };
            launch.RecoveryActivation?.Validate();
            start.ArgumentList.Add(launch.RecoveryActivation is null?"VerifyProfileNative":"VerifyRecoveryOwnerNative"); start.ArgumentList.Add(launch.RunId);
            using var process = Process.Start(start) ?? throw new IOException();
            try
            {
                // Read one bounded response from this direct child only. A full pipe fails by timeout.
                if (!process.WaitForExit(15000) || process.ExitCode != 0) throw new IOException();
                char[] buffer = new char[16385];
                int count = process.StandardOutput.ReadBlock(buffer, 0, buffer.Length);
                if (count > 16384) throw new IOException();
                string[] fields = new string(buffer, 0, count).Trim().Split('\t');
                string Decode(string value) => Encoding.UTF8.GetString(Convert.FromBase64String(value));
                string dll = Path.Combine(launch.Root, "jna-5.17.0-win32-x86-64", "jnidispatch.dll");
                if (fields.Length != 7 || fields[0] != "WINDOWS_PROFILE_V2" || fields[1] != "DEVELOPMENT_ONLY"
                    || fields[2] != launch.RunId || Decode(fields[3]) != launch.Root || Decode(fields[4]) != dll
                    || !System.Text.RegularExpressions.Regex.IsMatch(fields[5], "\\A[0-9a-f]{16}:[0-9a-f]{32}\\z")
                    || fields[6] != DllHash || Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(dll))) != DllHash)
                    throw new IOException();
            }
            finally { if (!process.HasExited) { process.Kill(); process.WaitForExit(5000); } }
        }
        catch (Exception) { throw new IOException("MMF3 native admission STOP (Development-only)."); }
    }

    internal static void Provision(string runId, string pinnedJnaJar)
        =>ProvisionCore(runId,pinnedJnaJar,false);
    internal static void ProvisionRecovery(ProfileRecoveryCoordinator.SyntheticActivation activation,string pinnedJnaJar)
    {activation.Validate();ProvisionCore(activation.TargetId,pinnedJnaJar,true);}
    private static void ProvisionCore(string runId,string pinnedJnaJar,bool recovery)
    {
        Mmf3ProfileState.Verify(runId); ValidateHelper();
        var start = new ProcessStartInfo(Helper) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = Path.GetDirectoryName(Helper)! };
        start.ArgumentList.Add(recovery?"ProvisionRecoveryNative":"ProvisionProfileNative"); start.ArgumentList.Add(runId); start.ArgumentList.Add(pinnedJnaJar);
        using var child = Process.Start(start) ?? throw new IOException();
        try { if (!child.WaitForExit(15000) || child.ExitCode != 0) throw new IOException(); }
        finally { if (!child.HasExited) { child.Kill(); child.WaitForExit(5000); } }
        // Parse subsequently runs the original Verify and checks the exact returned identity.
    }

    private static void ValidateHelper()
    {
        foreach (System.Collections.DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            string name = ((string)entry.Key).ToUpperInvariant();
            if (name.StartsWith("DOTNET_", StringComparison.Ordinal) || name.StartsWith("COREHOST_", StringComparison.Ordinal)
                || name.StartsWith("COMPLUS_", StringComparison.Ordinal) || name.StartsWith("COR_", StringComparison.Ordinal))
                throw new IOException();
        }
        (string suffix, string hash)[] payload = [
            (".exe", "816d253e5f707b1813eabc25a29a8ee5ba3da366fcf27e679cc7964a6c2abcf2"),
            (".dll", "4a8536eca1f7c85e893003c3079a7bd3a7e4dffd6f9bcfbf37cd3b8bad6134fb"),
            (".deps.json", "27af3a8da9f3b6155d86236c59339053f077e3ad33724e8f5e40281fb0375885"),
            (".runtimeconfig.json", "3e965cd7cfd553c2ff4e842d8d8019aaae2da0a21d92ff85a87573dd8d1b95c6")];
        foreach (var item in payload)
        {
            string file = Path.Combine(Path.GetDirectoryName(Helper)!, "PersonalAiWorkspace.NativeBootstrap" + item.suffix);
            if ((File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0 || new FileInfo(file).Length > 8 * 1024 * 1024
                || Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(file))) != item.hash) throw new IOException();
        }
    }
}
#endif
