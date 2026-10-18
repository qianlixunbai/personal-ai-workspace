using System;
using System.Collections.Generic;
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
    internal sealed record Manifest(int Format, string JavaHome, string RuntimeJar, Source[] Files);
    private readonly List<FileStream> pins = new();
    internal string Java { get; private set; } = "";
    internal string Jar { get; private set; } = "";
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
            if (manifest.Format != 1 || manifest.Files.Length is < 4 or > 2048) throw new IOException();
            result.Java = Path.Combine(manifest.JavaHome, "bin", "java.exe"); result.Jar = manifest.RuntimeJar;
            if (!manifest.Files.Any(f => f.Path == result.Java) || !manifest.Files.Any(f => f.Path == result.Jar)
                || !manifest.Files.Any(f => f.Path == Path.Combine(manifest.JavaHome, "lib", "modules"))
                || !manifest.Files.Any(f => f.Path == Path.Combine(manifest.JavaHome, "release"))
                || !manifest.Files.Any(f => f.Path == Path.Combine(manifest.JavaHome, "bin", "server", "jvm.dll"))
                || manifest.Files.Select(f => f.Path).Distinct(StringComparer.OrdinalIgnoreCase).Count() != manifest.Files.Length)
                throw new IOException();
            foreach (var source in manifest.Files)
            {
                if (!Path.IsPathFullyQualified(source.Path) || source.Path.StartsWith("\\\\", StringComparison.Ordinal)
                    || Path.GetFullPath(source.Path) != source.Path || source.Sha256.Length != 64
                    || source.Path != result.Jar && !source.Path.StartsWith(manifest.JavaHome + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException();
                for (string? part = source.Path; part is not null; part = Path.GetDirectoryName(part))
                    if ((File.GetAttributes(part) & FileAttributes.ReparsePoint) != 0) throw new IOException();
                var pin = new FileStream(source.Path, FileMode.Open, FileAccess.Read, FileShare.Read);
                result.pins.Add(pin);
                if (!Convert.ToHexString(SHA256.HashData(pin)).Equals(source.Sha256, StringComparison.OrdinalIgnoreCase)) throw new IOException();
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
    public void Dispose() { foreach (var pin in pins) pin.Dispose(); pins.Clear(); }
}
