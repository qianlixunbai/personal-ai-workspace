using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public static class BrowserOrigin
{
    // UX check only. Runtime remains the security authority.
    public static bool Valid(string? origin) => origin is not null
        && Regex.IsMatch(origin, @"\Achrome-extension://[a-p]{32}\z");
}

public sealed record BrowserPairing(Guid PairingId, string PairingSecret, DateTimeOffset ExpiresAt)
{
    public override string ToString() => "BrowserPairing[redacted]";
}

public sealed record BrowserClientMetadata(Guid ClientId, string DisplayName, string Origin,
    DateTimeOffset CreatedAt, IReadOnlyList<string> AllowedCapabilities)
{
    public string CapabilitiesText => string.Join(", ", AllowedCapabilities);
    public override string ToString() => $"BrowserClientMetadata[clientId={ClientId}]";
}
