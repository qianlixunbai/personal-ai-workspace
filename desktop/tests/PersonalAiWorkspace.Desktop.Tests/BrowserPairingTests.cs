using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class BrowserPairingTests
{
    private const string Origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
    private static readonly Guid Id = Guid.NewGuid();
    private static readonly DateTimeOffset Expires = DateTimeOffset.UtcNow.AddMinutes(3);
    private static string Token => new('n', 43);
    private static string Secret => new('s', 43);
    private static string Pairing(string? secret = null) => JsonSerializer.Serialize(new
    { pairingId = Id, pairingSecret = secret ?? Secret, expiresAt = Expires });
    private static string Clients() => JsonSerializer.Serialize(new[] { new
    {
        clientId = Id, clientType = "browser-extension", displayName = "Chrome Extension", origin = Origin,
        createdAt = "2026-10-02T10:00:00Z", allowedCapabilities = new[] { "translate" }
    } });
    private sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) => send(request, token);
    }
    private static HttpResponseMessage Response(HttpStatusCode status, string json) => new(status)
    { Content = new StringContent(json, Encoding.UTF8, "application/json") };
    private static RuntimeClient Client(string json, HttpStatusCode status = HttpStatusCode.OK) =>
        new(new Handler((_, _) => Task.FromResult(Response(status, json))), () => Token);

    [Fact]
    public async Task PairingUsesNativeStackApprovalContractAndParsesProofMetadata()
    {
        using var client = new RuntimeClient(new Handler(async (request, ct) =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.Equal(new Uri(RuntimeClient.Endpoint, "/api/v1/security/pairings"), request.RequestUri);
            Assert.Equal("Bearer", request.Headers.Authorization?.Scheme);
            Assert.Equal(Token, request.Headers.Authorization?.Parameter);
            Assert.False(request.Headers.Contains("Origin"));
            Assert.Contains(request.Headers.Accept, x => x.MediaType == "application/json");
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.Equal(3, body.RootElement.EnumerateObject().Count());
            Assert.Equal(Origin, body.RootElement.GetProperty("origin").GetString());
            Assert.Equal("Chrome Extension", body.RootElement.GetProperty("displayName").GetString());
            Assert.True(body.RootElement.GetProperty("userApproved").GetBoolean());
            return Response(HttpStatusCode.OK, Pairing());
        }), () => Token);
        var pairing = await client.CreateBrowserPairingAsync(Origin, CancellationToken.None);
        Assert.Equal(Id, pairing.PairingId);
        Assert.Equal(Secret, pairing.PairingSecret);
        Assert.Equal(Expires, pairing.ExpiresAt);
        Assert.DoesNotContain(Secret, pairing.ToString());
        Assert.DoesNotContain(Token, pairing.ToString());
    }

    [Theory]
    [InlineData("")]
    [InlineData("https://example.invalid")]
    [InlineData("chrome-extension://abcdefghijklmnopabcdefghijklmnop/")]
    [InlineData("chrome-extension://ABCDEFGHIJKLMNOPABCDEFGHIJKLMNOP")]
    [InlineData("chrome-extension://abcdefghijklmnopabcdefghijklmnoq")]
    [InlineData("chrome-extension://abcdefghijklmnopabcdefghijklmnop\n")]
    public async Task InvalidOriginNeverSendsARequest(string origin)
    {
        using var client = new RuntimeClient(new Handler((_, _) => throw new Xunit.Sdk.XunitException("Invalid origin reached HTTP")), () => Token);
        Assert.Equal(DesktopError.InvalidExtensionOrigin,
            (await Assert.ThrowsAsync<DesktopException>(() => client.CreateBrowserPairingAsync(origin, CancellationToken.None))).Error);
    }

    [Theory]
    [InlineData(401, "UNAUTHORIZED", "AUTH", DesktopError.Unauthorized)]
    [InlineData(400, "INVALID_REQUEST", "PAIRING", DesktopError.InvalidExtensionOrigin)]
    [InlineData(429, "QUEUE_FULL", "PAIRING", DesktopError.PairingCapacityFull)]
    [InlineData(500, "INTERNAL_ERROR", "SECURITY_STATE", DesktopError.SecurityStateError)]
    [InlineData(500, "INTERNAL_ERROR", "HTTP", DesktopError.PairingCreationFailed)]
    [InlineData(403, "POLICY_DENIED", "AUTH", DesktopError.PairingCreationFailed)]
    public async Task SecurityErrorsAreControlledAndNeverIncludeRawBody(int status, string code, string phase, DesktopError expected)
    {
        using var client = Client(JsonSerializer.Serialize(new { code, phase, message = Secret + Token + "private-provider-body" }), (HttpStatusCode)status);
        var failure = await Assert.ThrowsAsync<DesktopException>(() => client.CreateBrowserPairingAsync(Origin, CancellationToken.None));
        Assert.Equal(expected, failure.Error);
        Assert.DoesNotContain(Secret, failure.ToString());
        Assert.DoesNotContain(Token, failure.ToString());
        Assert.DoesNotContain("private-provider-body", failure.ToString());
        Assert.Null(failure.InnerException);
    }

    [Fact]
    public async Task OfflineMissingCredentialAndCancellationRemainControlled()
    {
        using var offline = new RuntimeClient(new Handler((_, _) => throw new HttpRequestException(Secret)), () => Token);
        var error = await Assert.ThrowsAsync<DesktopException>(() => offline.CreateBrowserPairingAsync(Origin, CancellationToken.None));
        Assert.Equal(DesktopError.RuntimeUnavailable, error.Error);
        Assert.DoesNotContain(Secret, error.ToString());
        using var missing = new RuntimeClient(new Handler((_, _) => throw new Xunit.Sdk.XunitException("No credential")), () => null);
        Assert.Equal(DesktopError.CredentialMissing, (await Assert.ThrowsAsync<DesktopException>(() => missing.CreateBrowserPairingAsync(Origin, CancellationToken.None))).Error);
        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        using var client = new RuntimeClient(new Handler((_, ct) => Task.FromCanceled<HttpResponseMessage>(ct)), () => Token);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.CreateBrowserPairingAsync(Origin, cancelled.Token));
    }

    [Theory]
    [InlineData("{\"pairingId\":\"bad\",\"pairingSecret\":\"bad\",\"expiresAt\":\"bad\"}")]
    [InlineData("{\"pairingId\":null}")]
    [InlineData("{\"pairingId\":1,\"pairingId\":2}")]
    [InlineData("<private-provider-body>")]
    public async Task InvalidPairingResponseFailsClosed(string json)
    {
        using var client = Client(json);
        Assert.Equal(DesktopError.InvalidResponse, (await Assert.ThrowsAsync<DesktopException>(() => client.CreateBrowserPairingAsync(Origin, CancellationToken.None))).Error);
    }

    [Fact]
    public async Task ProofShapeExpiryExtraFieldsAndResponseLimitsAreValidated()
    {
        foreach (string json in new[] { Pairing("bad"), JsonSerializer.Serialize(new { pairingId = Id, pairingSecret = Secret, expiresAt = "bad" }),
            Pairing()[..^1] + ",\"credential\":\"should-never-be-displayed\"}", new string('x', 1024 * 1024 + 1) })
        {
            using var client = Client(json);
            Assert.Equal(DesktopError.InvalidResponse,
                (await Assert.ThrowsAsync<DesktopException>(() => client.CreateBrowserPairingAsync(Origin, CancellationToken.None))).Error);
        }
    }

    [Fact]
    public async Task ListingOnlyAcceptsSafeMetadataAndRevokeUsesNativeDelete204()
    {
        using var client = new RuntimeClient(new Handler((request, _) =>
        {
            Assert.Equal(Token, request.Headers.Authorization?.Parameter);
            Assert.False(request.Headers.Contains("Origin"));
            if (request.Method == HttpMethod.Get)
            {
                Assert.Equal("/api/v1/security/clients", request.RequestUri!.AbsolutePath);
                return Task.FromResult(Response(HttpStatusCode.OK, Clients()));
            }
            Assert.Equal(HttpMethod.Delete, request.Method);
            Assert.Equal($"/api/v1/security/clients/{Id:D}", request.RequestUri!.AbsolutePath);
            Assert.Null(request.Content);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NoContent));
        }), () => Token);
        var clients = await client.ListBrowserClientsAsync(CancellationToken.None);
        var metadata = Assert.Single(clients);
        Assert.Equal(Id, metadata.ClientId);
        Assert.Equal(Origin, metadata.Origin);
        Assert.Equal("Chrome Extension", metadata.DisplayName);
        Assert.Equal(new DateTimeOffset(2026, 10, 2, 10, 0, 0, TimeSpan.Zero), metadata.CreatedAt);
        Assert.Equal("translate", Assert.Single(metadata.AllowedCapabilities));
        Assert.DoesNotContain(Origin, metadata.ToString());
        Assert.DoesNotContain(typeof(BrowserClientMetadata).GetProperties(), p => p.Name.Contains("Secret") || p.Name.Contains("Credential") || p.Name.Contains("Verifier"));
        await client.RevokeBrowserClientAsync(Id, CancellationToken.None);
    }

    [Theory]
    [InlineData("credential")]
    [InlineData("verifier")]
    public async Task ListingRejectsSecretBearingResponses(string field)
    {
        using var client = Client(Clients().Replace("\"clientType\"", $"\"{field}\":\"{Secret}\",\"clientType\""));
        var error = await Assert.ThrowsAsync<DesktopException>(() => client.ListBrowserClientsAsync(CancellationToken.None));
        Assert.Equal(DesktopError.InvalidResponse, error.Error);
        Assert.DoesNotContain(Secret, error.ToString());
    }

    [Fact]
    public async Task WpfOnlyClickCreatesPairingThenReplacementAndCloseClearSensitiveDisplay()
    {
        await StaAsync(async () =>
        {
            int calls = 0;
            using var client = new RuntimeClient(new Handler((_, _) =>
                Task.FromResult(Response(HttpStatusCode.OK, Pairing(new string(++calls == 1 ? 'a' : 'b', 43))))), () => Token);
            var window = new BrowserPairingWindow(client);
            window.OriginText.Text = Origin;
            Assert.Equal(0, calls);
            Assert.Empty(window.PairingSecretText.Text);
            window.CreatePairingButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            await Dispatcher.Yield();
            Assert.Equal(1, calls);
            Assert.Equal(new string('a', 43), window.PairingSecretText.Text);
            Assert.False(window.PairingSecretText.IsUndoEnabled);
            await window.CreatePairingAsync();
            Assert.Equal(new string('b', 43), window.PairingSecretText.Text);
            Assert.Equal(2, calls);
            window.Close();
            Assert.Empty(window.PairingSecretText.Text); Assert.Empty(window.PairingIdText.Text);
            Assert.Empty(window.ExpiresAtText.Text); Assert.Empty(window.OriginText.Text);
            Assert.False(window.CopySecretButton.IsEnabled);
            await window.CreatePairingAsync();
            Assert.Equal(2, calls);
        });
    }

    [Fact]
    public async Task CloseDuringCreationCancelsAndNeverRepopulatesEvenWithLateResponse()
    {
        await StaAsync(async () =>
        {
            var response = new TaskCompletionSource<HttpResponseMessage>();
            CancellationToken requestToken = default;
            int calls = 0;
            using var client = new RuntimeClient(new Handler((_, ct) => { calls++; requestToken = ct; return response.Task; }), () => Token);
            var window = new BrowserPairingWindow(client);
            window.OriginText.Text = Origin;
            var pending = window.CreatePairingAsync();
            await window.CreatePairingAsync();
            Assert.Equal(1, calls);
            window.Close();
            Assert.True(requestToken.IsCancellationRequested);
            response.SetResult(Response(HttpStatusCode.OK, Pairing()));
            await pending;
            Assert.Empty(window.PairingSecretText.Text);
            Assert.Equal(Visibility.Collapsed, window.PairingDetails.Visibility);
        });
    }

    [Fact]
    public async Task FailedReplacementClearsPreviousProofAndRevokeOnlyRemovesConfirmedClient()
    {
        await StaAsync(async () =>
        {
            int creates = 0, deletes = 0;
            using var client = new RuntimeClient(new Handler((request, _) =>
            {
                if (request.Method == HttpMethod.Get) return Task.FromResult(Response(HttpStatusCode.OK, Clients()));
                if (request.Method == HttpMethod.Delete)
                    return Task.FromResult(++deletes == 1 ? Response(HttpStatusCode.InternalServerError,
                        "{\"code\":\"INTERNAL_ERROR\",\"phase\":\"SECURITY_STATE\"}") : new HttpResponseMessage(HttpStatusCode.NoContent));
                return Task.FromResult(++creates == 1 ? Response(HttpStatusCode.OK, Pairing()) : Response(HttpStatusCode.Unauthorized, Secret));
            }), () => Token);
            var window = new BrowserPairingWindow(client);
            window.OriginText.Text = Origin;
            await window.CreatePairingAsync();
            Assert.NotEmpty(window.PairingSecretText.Text);
            await window.CreatePairingAsync();
            Assert.Empty(window.PairingSecretText.Text);
            Assert.Contains("Unauthorized", window.PairingStatusText.Text);
            await window.RefreshClientsAsync();
            Assert.Single(window.ClientsList.Items.Cast<BrowserClientMetadata>());
            window.ClientsList.SelectedIndex = 0;
            await window.RevokeSelectedAsync();
            Assert.Single(window.ClientsList.Items.Cast<BrowserClientMetadata>());
            Assert.Contains("Security state error", window.PairingStatusText.Text);
            await window.RevokeSelectedAsync();
            Assert.Empty(window.ClientsList.Items);
            window.Close();
        });
    }

    [Fact]
    public async Task InvalidOriginAndExpiredProofNeverBecomeVisibleInWpf()
    {
        await StaAsync(async () =>
        {
            int calls = 0;
            using var client = new RuntimeClient(new Handler((_, _) =>
            {
                calls++;
                return Task.FromResult(Response(HttpStatusCode.OK, JsonSerializer.Serialize(new
                { pairingId = Id, pairingSecret = Secret, expiresAt = DateTimeOffset.UtcNow.AddSeconds(-1) })));
            }), () => Token);
            var window = new BrowserPairingWindow(client);
            window.OriginText.Text = Origin + "/";
            await window.CreatePairingAsync();
            Assert.Equal(0, calls);
            Assert.Contains("Invalid extension origin", window.PairingStatusText.Text);
            window.OriginText.Text = Origin;
            await window.CreatePairingAsync();
            Assert.Equal(1, calls);
            Assert.Empty(window.PairingSecretText.Text);
            Assert.Equal(Visibility.Collapsed, window.PairingDetails.Visibility);
            Assert.False(window.CopySecretButton.IsEnabled);
            window.Close();
        });
    }

    [Fact]
    public async Task BeginningAReplacementImmediatelyClearsPriorVisibleSecret()
    {
        await StaAsync(async () =>
        {
            var response = new TaskCompletionSource<HttpResponseMessage>();
            int calls = 0;
            using var client = new RuntimeClient(new Handler((_, _) => ++calls == 1
                ? Task.FromResult(Response(HttpStatusCode.OK, Pairing())) : response.Task), () => Token);
            var window = new BrowserPairingWindow(client);
            window.OriginText.Text = Origin;
            await window.CreatePairingAsync();
            Assert.Equal(Secret, window.PairingSecretText.Text);
            var pending = window.CreatePairingAsync();
            Assert.Empty(window.PairingSecretText.Text);
            Assert.False(window.CopySecretButton.IsEnabled);
            response.SetResult(Response(HttpStatusCode.OK, Pairing(new string('r', 43))));
            await pending;
            Assert.Equal(new string('r', 43), window.PairingSecretText.Text);
            window.Close();
        });
    }

    private static Task StaAsync(Func<Task> action)
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async () =>
            {
                try { await action(); done.SetResult(); }
                catch (Exception error) { done.SetException(error); }
                finally { dispatcher.InvokeShutdown(); }
            }));
            Dispatcher.Run();
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return done.Task;
    }
}
