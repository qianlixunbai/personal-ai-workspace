using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public sealed partial class RuntimeClient
{
    public async Task<BrowserPairing> CreateBrowserPairingAsync(string origin, CancellationToken cancellationToken)
    {
        if (!BrowserOrigin.Valid(origin)) throw new DesktopException(DesktopError.InvalidExtensionOrigin);
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(new { origin, displayName = "Chrome Extension", userApproved = true });
        using var body = await SendAsync(HttpMethod.Post, "/api/v1/security/pairings", payload, true,
            HttpStatusCode.OK, cancellationToken, errorMap: root => SecurityError(root, true));
        var root = body.RootElement;
        Fields(root, "pairingId", "pairingSecret", "expiresAt");
        var id = SecurityId(root, "pairingId");
        string secret = String(root, "pairingSecret");
        if (!CredentialFormat.Valid(secret)) throw Invalid();
        var expires = SecurityTime(root, "expiresAt");
        return new BrowserPairing(id, secret, expires);
    }

    public async Task<IReadOnlyList<BrowserClientMetadata>> ListBrowserClientsAsync(CancellationToken cancellationToken)
    {
        using var body = await SendAsync(HttpMethod.Get, "/api/v1/security/clients", null, true,
            HttpStatusCode.OK, cancellationToken, errorMap: root => SecurityError(root, false));
        var root = body.RootElement;
        if (root.ValueKind != JsonValueKind.Array || root.GetArrayLength() > 32) throw Invalid();
        var clients = new List<BrowserClientMetadata>();
        var ids = new HashSet<Guid>();
        foreach (var item in root.EnumerateArray())
        {
            // Explicit allowlist: credential/verifier or any other extra field fails closed.
            Fields(item, "clientId", "clientType", "displayName", "origin", "createdAt", "allowedCapabilities");
            var id = SecurityId(item, "clientId");
            string name = String(item, "displayName"), origin = String(item, "origin");
            var capabilities = Property(item, "allowedCapabilities");
            if (!ids.Add(id) || String(item, "clientType") != "browser-extension" || !BrowserOrigin.Valid(origin)
                || string.IsNullOrWhiteSpace(name) || !Regex.IsMatch(name, @"\A[A-Za-z0-9 ._-]{1,64}\z")
                || capabilities.ValueKind != JsonValueKind.Array || capabilities.GetArrayLength() != 1
                || capabilities[0].ValueKind != JsonValueKind.String || capabilities[0].GetString() != "translate") throw Invalid();
            clients.Add(new(id, name, origin, SecurityTime(item, "createdAt"), Array.AsReadOnly(new[] { "translate" })));
        }
        return clients.AsReadOnly();
    }

    public async Task RevokeBrowserClientAsync(Guid clientId, CancellationToken cancellationToken)
    {
        if (clientId == Guid.Empty) throw new DesktopException(DesktopError.BrowserManagementFailed);
        using var body = await SendAsync(HttpMethod.Delete, $"/api/v1/security/clients/{clientId:D}", null, true,
            HttpStatusCode.NoContent, cancellationToken, errorMap: root => SecurityError(root, false));
    }

    private static DesktopError SecurityError(JsonElement root, bool pairing)
    {
        string code = String(root, "code");
        string phase = String(root, "phase");
        return (code, phase) switch
        {
            ("INVALID_REQUEST", "PAIRING") when pairing => DesktopError.InvalidExtensionOrigin,
            ("QUEUE_FULL", "PAIRING") => DesktopError.PairingCapacityFull,
            ("INTERNAL_ERROR", "SECURITY_STATE") => DesktopError.SecurityStateError,
            _ => pairing ? DesktopError.PairingCreationFailed : DesktopError.BrowserManagementFailed
        };
    }

    private static Guid SecurityId(JsonElement root, string name) =>
        Guid.TryParseExact(String(root, name), "D", out var id) && id != Guid.Empty ? id : throw Invalid();
    private static DateTimeOffset SecurityTime(JsonElement root, string name) =>
        DateTimeOffset.TryParse(String(root, name), System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.None, out var time) ? time : throw Invalid();
    private static void Fields(JsonElement root, params string[] names)
    {
        if (root.ValueKind != JsonValueKind.Object || root.EnumerateObject().Count() != names.Length
            || root.EnumerateObject().Any(field => !names.Contains(field.Name, StringComparer.Ordinal))) throw Invalid();
    }
}
