using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalAiWorkspace.Desktop;

// Explicitly reviewed local build/toolchain trust, not publisher authentication or Production GO.
internal sealed class TrustedDevelopmentRuntimeSources : IDisposable
{
    internal sealed record Source(string Path, string Sha256);
    internal sealed record Manifest(int Format, string JavaHome, string RuntimeJar, string JnaJar, Source[] Files);
    private readonly List<FileStream> pins = new();
    internal string Java { get; private set; } = "";
    internal string Jar { get; private set; } = "";
    internal string JarSha256 { get; private set; } = "";
    internal string JnaJar { get; private set; } = "";
    internal static TrustedDevelopmentRuntimeSources Open()
    {
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
        var result = new TrustedDevelopmentRuntimeSources();
        try
        {
            using var resource = Assembly.GetExecutingAssembly().GetManifestResourceStream("TrustedDevelopmentRuntimeSources")
                ?? throw new IOException("Explicit reviewed development build required");
            if (resource.Length > 131072) throw new IOException();
            var manifest = JsonSerializer.Deserialize<Manifest>(resource, new JsonSerializerOptions
                { UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow }) ?? throw new IOException();
            if (manifest.Format is not (2 or 3) || manifest.Files.Length is < 5 or > 2048) throw new IOException();
            if (manifest.Format == 3)
            {
                // Only this opt-in Debug build accepts bundled sources. The expected hashes
                // remain embedded in Desktop, never read from a sidecar or command line.
                if (manifest.JavaHome != "runtime/java" || manifest.RuntimeJar != "runtime/personal-ai-workspace.jar"
                    || manifest.JnaJar != "runtime/jna-5.17.0.jar") throw new IOException();
                string Resolve(string path)
                {
                    if (!System.Text.RegularExpressions.Regex.IsMatch(path, @"\Aruntime/[A-Za-z0-9_./+-]+\z")
                        || path.Split('/').Any(p => p is "" or "." or "..")) throw new IOException();
                    return Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, path.Replace('/', Path.DirectorySeparatorChar)));
                }
                manifest = manifest with { JavaHome = Resolve(manifest.JavaHome), RuntimeJar = Resolve(manifest.RuntimeJar),
                    JnaJar = Resolve(manifest.JnaJar), Files = manifest.Files.Select(f => f with { Path = Resolve(f.Path) }).ToArray() };
            }
            result.Java = Path.Combine(manifest.JavaHome, "bin", "java.exe"); result.Jar = manifest.RuntimeJar; result.JnaJar = manifest.JnaJar;
            if (!manifest.Files.Any(f => f.Path == result.Java) || !manifest.Files.Any(f => f.Path == result.Jar)
                || !manifest.Files.Any(f => f.Path == result.JnaJar) || Path.GetFileName(result.JnaJar) != "jna-5.17.0.jar"
                || !manifest.Files.Any(f => f.Path == Path.Combine(manifest.JavaHome, "lib", "modules"))
                || !manifest.Files.Any(f => f.Path == Path.Combine(manifest.JavaHome, "release"))
                || !manifest.Files.Any(f => f.Path == Path.Combine(manifest.JavaHome, "bin", "server", "jvm.dll"))
                || manifest.Files.Select(f => f.Path).Distinct(StringComparer.OrdinalIgnoreCase).Count() != manifest.Files.Length)
                throw new IOException();
            foreach (var source in manifest.Files)
            {
                if (!Path.IsPathFullyQualified(source.Path) || source.Path.StartsWith("\\\\", StringComparison.Ordinal)
                    || Path.GetFullPath(source.Path) != source.Path || source.Sha256.Length != 64
                    || source.Path != result.Jar && source.Path != result.JnaJar && !source.Path.StartsWith(manifest.JavaHome + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException();
                for (string? part = source.Path; part is not null; part = Path.GetDirectoryName(part))
                    if ((File.GetAttributes(part) & FileAttributes.ReparsePoint) != 0) throw new IOException();
                var pin = new FileStream(source.Path, FileMode.Open, FileAccess.Read, FileShare.Read);
                result.pins.Add(pin);
                if (!Convert.ToHexString(SHA256.HashData(pin)).Equals(source.Sha256, StringComparison.OrdinalIgnoreCase)) throw new IOException();
                if (source.Path == result.Jar) result.JarSha256 = source.Sha256.ToLowerInvariant();
            }
            string release = File.ReadAllText(Path.Combine(manifest.JavaHome, "release"));
            if (!release.Contains("JAVA_VERSION=\"21.", StringComparison.Ordinal) && !release.Contains("JAVA_VERSION=\"21\"", StringComparison.Ordinal)) throw new IOException();
            return result;
        }
        catch { result.Dispose(); throw; }
#else
        throw new IOException("Development-only runtime owner unavailable");
#endif
    }
#if MMF3_PROFILE_ACCEPTANCE && DEBUG
    internal ProcessStartInfo StartInfo(Mmf3AcceptanceLaunch profile, bool databaseSetup = false, bool financeSetup = false)
    {
        profile.Validate(); Mmf3ProfileNative.Verify(profile);
        foreach (System.Collections.DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            string key = ((string)entry.Key).ToUpperInvariant();
            if (key is "JAVA_TOOL_OPTIONS" or "_JAVA_OPTIONS" or "JDK_JAVA_OPTIONS"
                || new[] { "DOTNET_", "COREHOST_", "COMPLUS_", "COR_", "SPRING_", "WORKSPACE_", "SERVER_" }.Any(key.StartsWith))
                throw new IOException();
        }
        var start = new ProcessStartInfo(Java) { UseShellExecute = false, CreateNoWindow = true,
            WorkingDirectory = profile.Root, RedirectStandardOutput = true, RedirectStandardError = true,
            RedirectStandardInput = databaseSetup || financeSetup };
        string native = Path.Combine(profile.Root, "jna-5.17.0-win32-x86-64"), evidence = Path.Combine(profile.Root, "evidence");
        foreach (string option in new[] { "-Dworkspace.native.development-profile=" + profile.RunId,
            "-Dworkspace.native.bootstrap=" + Mmf3ProfileNative.Helper, "-Djna.boot.library.path=" + native,
            "-Djna.boot.library.name=jnidispatch", "-Djna.nosys=true", "-Djna.noclasspath=true", "-Djna.nounpack=true",
            "-Djava.io.tmpdir=" + evidence, "-Djna.tmpdir=" + evidence, "-jar", Jar }) start.ArgumentList.Add(option);
        if (databaseSetup) start.ArgumentList.Add("--native-database-setup");
        if (financeSetup) start.ArgumentList.Add("--native-finance-setup");
        foreach (string option in new[] { "--server.address=127.0.0.1", "--server.port=18765", "--workspace.security.token-file=" + profile.TokenFile,
            "--workspace.data-directory=" + Path.Combine(profile.Root, "data"), "--workspace.model-state-directory=" + Path.Combine(profile.Root, "model-state") })
            start.ArgumentList.Add(option);
        return start;
    }
#endif
    public void Dispose() { foreach (var pin in pins) pin.Dispose(); pins.Clear(); }
}
