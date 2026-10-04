using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace PersonalAiWorkspace.Desktop.Hosting;

internal sealed class WorkspaceAssets
{
    internal string Folder { get; }
    internal string[] Files { get; }
    internal WorkspaceAssets(string folder)
    {
        Folder = Path.GetFullPath(folder);
        RejectLinks(Folder);
        using var manifest = JsonDocument.Parse(File.ReadAllText(Path.Combine(Folder, "workspace-assets.json")));
        var root = manifest.RootElement;
        if (root.GetProperty("formatVersion").GetInt32() != 1 || root.GetProperty("bridgeVersion").GetInt32() != 1)
            throw new InvalidDataException("Incompatible workspace assets.");
        var files = new List<string>();
        foreach (var entry in root.GetProperty("files").EnumerateObject())
        {
            string name = entry.Name;
            if (name != "index.html" && !System.Text.RegularExpressions.Regex.IsMatch(name, @"\Aassets/[A-Za-z0-9_.-]+\.(js|css|svg|png|woff2)\z"))
                throw new InvalidDataException("Invalid workspace asset.");
            if (files.Contains(name)) throw new InvalidDataException("Duplicate workspace asset.");
            string file = Path.Combine(Folder, name.Replace('/', Path.DirectorySeparatorChar));
            RejectLinks(file);
            using var stream = File.OpenRead(file);
            if (!string.Equals(Convert.ToHexString(SHA256.HashData(stream)), entry.Value.GetString(), StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Workspace asset integrity failed.");
            files.Add(name);
        }
        if (files.Count is < 3 or > 64 || !files.Contains("index.html") || !File.ReadAllText(Path.Combine(Folder, "index.html")).Contains(WorkspaceContentPolicy.Csp, StringComparison.Ordinal))
            throw new InvalidDataException("Missing secured workspace assets.");
        Files = files.ToArray();
    }
    private static void RejectLinks(string path)
    {
        for (string? current = path; current is not null; current = Path.GetDirectoryName(current))
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new InvalidDataException("Linked assets not allowed.");
    }
}
