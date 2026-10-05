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




    [Fact]
    public async Task MalformedPairingResponsesFailClosed()
    {
        string valid = Pairing();
        (string CaseName, string Json)[] malformed =
        [
            ("invalid-proof-fields", "{\"pairingId\":\"bad\",\"pairingSecret\":\"bad\",\"expiresAt\":\"bad\"}"),
            ("incomplete-null-id", "{\"pairingId\":null}"),
            ("duplicate-wrong-type-id", "{\"pairingId\":1,\"pairingId\":2}"),
            ("non-json-body", "<private-provider-body>"),
            ("malformed-json", "{broken"),
            ("null-root", "null"),
            ("null-proof-id", valid.Replace($"\"{Id:D}\"", "null")),
            ("wrong-proof-id-type", valid.Replace($"\"{Id:D}\"", "1")),
            ("invalid-proof-id", valid.Replace($"\"{Id:D}\"", "\"bad\"")),
            ("duplicate-proof-id", valid.Replace("\"pairingId\":", $"\"pairingId\":\"{Id:D}\",\"pairingId\":")),
            ("invalid-secret", Pairing("bad")),
            ("invalid-expiry", JsonSerializer.Serialize(new { pairingId = Id, pairingSecret = Secret, expiresAt = "bad" })),
            ("extra-secret-bearing-field", valid[..^1] + ",\"credential\":\"should-never-be-displayed\"}"),
            ("oversized-response", new string('x', 1024 * 1024 + 1))
        ];
        foreach (var (caseName, json) in malformed)
        {
            using var client = Client(json);
            var error = await Record.ExceptionAsync(() => client.CreateBrowserPairingAsync(Origin, CancellationToken.None));
            Assert.True(error is DesktopException { Error: DesktopError.InvalidResponse },
                $"{caseName}: expected InvalidResponse; actual {error?.GetType().Name ?? "no exception"}.");
            Assert.True(new[] { Secret, Token, "private-provider-body", "should-never-be-displayed" }
                .All(marker => !error!.ToString().Contains(marker, StringComparison.Ordinal)),
                $"{caseName}: error exposed sensitive response content.");
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
            AssertProofHidden(window);
            Assert.Empty(window.OriginText.Text);
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
            AssertProofHidden(window);
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
            AssertProofHidden(window);
            AssertSafeStatus(window);
            Assert.True(window.CreatePairingButton.IsEnabled);
            await window.RefreshClientsAsync();
            Assert.Single(window.ClientsList.Items.Cast<BrowserClientMetadata>());
            window.ClientsList.SelectedIndex = 0;
            await window.RevokeSelectedAsync();
            Assert.Single(window.ClientsList.Items.Cast<BrowserClientMetadata>());
            Assert.Equal(Id, ((BrowserClientMetadata)window.ClientsList.SelectedItem).ClientId);
            Assert.True(window.RevokeClientButton.IsEnabled);
            AssertSafeStatus(window);
            await window.RevokeSelectedAsync();
            Assert.Empty(window.ClientsList.Items);
            Assert.False(window.RevokeClientButton.IsEnabled);
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
            AssertProofHidden(window);
            AssertSafeStatus(window);
            Assert.True(window.CreatePairingButton.IsEnabled);
            window.OriginText.Text = Origin;
            await window.CreatePairingAsync();
            Assert.Equal(1, calls);
            AssertProofHidden(window);
            AssertSafeStatus(window);
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
            AssertProofHidden(window);
            Assert.False(window.CreatePairingButton.IsEnabled);
            response.SetResult(Response(HttpStatusCode.OK, Pairing(new string('r', 43))));
            await pending;
            Assert.Equal(new string('r', 43), window.PairingSecretText.Text);
            window.Close();
        });
    }

    private static void AssertProofHidden(BrowserPairingWindow window)
    {
        Assert.Empty(window.PairingSecretText.Text);
        Assert.Empty(window.PairingIdText.Text);
        Assert.Empty(window.ExpiresAtText.Text);
        Assert.Equal(Visibility.Collapsed, window.PairingDetails.Visibility);
        Assert.False(window.CopySecretButton.IsEnabled);
        Assert.False(window.CopyPairingIdButton.IsEnabled);
    }

    private static void AssertSafeStatus(BrowserPairingWindow window)
    {
        Assert.False(string.IsNullOrWhiteSpace(window.PairingStatusText.Text));
        Assert.DoesNotContain(Secret, window.PairingStatusText.Text);
        Assert.DoesNotContain(Token, window.PairingStatusText.Text);
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
