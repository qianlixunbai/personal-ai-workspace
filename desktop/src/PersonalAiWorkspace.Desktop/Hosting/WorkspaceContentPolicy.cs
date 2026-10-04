using System;
using System.Collections.Generic;

namespace PersonalAiWorkspace.Desktop.Hosting;

// No caller-configurable URL. The development origin is compiled out of Release.
internal sealed class WorkspaceContentPolicy
{
    internal const string ProductionOrigin = "https://workspace.personal-ai.invalid";
    internal const string Csp = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'";
    internal string Origin { get; }
    internal string Entry => Origin + "/index.html";
    private readonly HashSet<string> resources;
    internal bool Development { get; }

    internal WorkspaceContentPolicy(IEnumerable<string>? assets = null)
    {
#if WORKSPACE_DEV && DEBUG
        Origin = "http://127.0.0.1:5173";
        Development = true;
#else
        Origin = ProductionOrigin;
#endif
        resources = new HashSet<string>(assets ?? [], StringComparer.Ordinal);
    }

    internal bool TrustedOrigin(string address) => Uri.TryCreate(address, UriKind.Absolute, out var uri)
        && uri.GetLeftPart(UriPartial.Authority) == Origin && uri.UserInfo.Length == 0;

    internal bool Document(string address) => TrustedOrigin(address) && Uri.TryCreate(address, UriKind.Absolute, out var uri)
        && uri.Query.Length == 0 && uri.AbsolutePath is "/" or "/index.html"
        && uri.Fragment is "" or "#/assistant" or "#/conversations" or "#/memory" or "#/translate" or "#/settings";

    internal bool SameDocument(string first, string second) => Document(first) && Document(second)
        && new Uri(first).GetLeftPart(UriPartial.Path) == new Uri(second).GetLeftPart(UriPartial.Path);

    internal bool Resource(string address, string method, string context)
    {
        if (method != "GET" || !TrustedOrigin(address)) return false;
        var uri = new Uri(address);
        if (Development) return context is "Document" or "Script" or "Stylesheet" or "Image" or "Font" or "Other";
        if (uri.Query.Length != 0 || uri.Fragment.Length != 0) return false;
        if (context == "Document") return Document(address);
        return context is "Script" or "Stylesheet" or "Image" or "Font" && resources.Contains(uri.AbsolutePath.TrimStart('/'));
    }

    internal static bool AllowFrame => false;
    internal static bool AllowPopup => false;
    internal static bool AllowDownload => false;
    internal static bool AllowPermission => false;
}
