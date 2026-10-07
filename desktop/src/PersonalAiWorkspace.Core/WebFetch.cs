using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

// Raw ASCII admission, independent of System.Uri/browser normalization and public networking.
public sealed class WebFetchTarget
{
    public string Url { get; }
    public string Hostname { get; }
    private WebFetchTarget(string url, string hostname) { Url = url; Hostname = hostname; }
    public static WebFetchTarget Parse(string supplied)
    {
        static DesktopException Invalid() => new(DesktopError.WebTargetInvalid);
        if (string.IsNullOrEmpty(supplied) || supplied.Length > 2048
            || supplied.Any(c => c <= 32 || c >= 127 || "#\\\"<>^`{|}".Contains(c))
            || !supplied.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) throw Invalid();
        int end = supplied.IndexOfAny(['/', '?'], 8);
        if (end < 0) end = supplied.Length;
        string authority = supplied[8..end];
        if (authority.IndexOfAny(['@', '%', '[', ']']) >= 0) throw Invalid();
        int colon = authority.IndexOf(':');
        if (colon >= 0 && authority[colon..] != ":443") throw Invalid();
        string host = (colon < 0 ? authority : authority[..colon]).ToLowerInvariant();
        if (host.EndsWith('.')) host = host[..^1];
        string[] labels = host.Split('.');
        if (host.Length > 253 || labels.Length < 2
            || labels.Any(l => !Regex.IsMatch(l, @"\A[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\z"))) throw Invalid();
        string last = labels[^1];
        if (Regex.IsMatch(last, @"\A(?:[0-9]+|0x[0-9a-f]+)\z")
            || new[] { "localhost", "local", "localdomain", "internal", "intranet", "lan", "home", "corp", "test", "invalid", "example", "onion", "alt", "arpa" }.Contains(last)) throw Invalid();
        string suffix = supplied[end..];
        int query = suffix.IndexOf('?');
        string path = query < 0 ? suffix : suffix[..query];
        if (path.Length == 0) path = "/";
        if (!path.StartsWith('/') || path.StartsWith("//", StringComparison.Ordinal)
            || path.IndexOfAny(['[', ']']) >= 0 || path.Split('/').Any(p => p is "." or "..")) throw Invalid();
        for (int i = 0; i < suffix.Length; i++)
            if (suffix[i] == '%' && (i + 2 >= suffix.Length || !Uri.IsHexDigit(suffix[++i]) || !Uri.IsHexDigit(suffix[++i]))) throw Invalid();
        string canonical = "https://" + host + path + (query < 0 ? "" : suffix[query..]);
        if (canonical.Length > 2048) throw Invalid();
        return new(canonical, host);
    }
    public override string ToString() => "WebFetchTarget[redacted]";
}

public enum WebFetchState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
public sealed record WebFetchResult(string RequestedUrl, string FinalUrl, string Hostname, string Title,
    string AcquiredAt, string ContentType, string ExtractionVersion, string Text, bool TitleTruncated, bool TextTruncated)
{ public override string ToString() => "WebFetchResult[redacted]"; }
public sealed record WebFetchOperation(Guid OperationId, WebFetchState State, WebFetchResult? Result, DesktopError? Error)
{ public override string ToString() => $"WebFetchOperation[state={State},redacted]"; }
