#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Reflection;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop.Bridge;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    [Fact]
    public static async Task ReadOnlyFailureKeepsLiveOwnerAndLaterReadUsesSameGeneration()
    {
        using var fixture = new ObservationOwnerFixture();
        var owner = fixture.Owner;
        Task generation = fixture.Running;
        int pid = owner.OwnedProcessId!.Value;
        fixture.Send = async (_, token) =>
        {
            await Task.Delay(Timeout.Infinite, token); // Exercise RuntimeClient's real eight-second GET deadline.
            throw new InvalidOperationException();
        };
        var failed = await owner.ReadAsync(CancellationToken.None);
        Assert.False(failed.CanQueryStorage);
        Assert.Equal("UNAVAILABLE", failed.Runtime);
        // On the unfixed owner, observe the cancellation and actual Job-owned child exit before failing.
        if (fixture.Stop.IsCancellationRequested) await generation.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.False(fixture.Child.HasExited);
        Assert.False(fixture.Stop.IsCancellationRequested);

        using (var cancellation = new CancellationTokenSource())
        {
            var cancelledRead = owner.ReadAsync(cancellation.Token);
            cancellation.Cancel();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cancelledRead);
            Assert.False(fixture.Stop.IsCancellationRequested);
            Assert.False(fixture.Child.HasExited);
        }

        fixture.Send = (request, _) => request.RequestUri!.AbsolutePath == "/actuator/health"
            ? Task.FromResult(ObservationHealthy(request))
            : throw new HttpRequestException(HttpRequestError.ConnectionError, "synthetic connection interruption");
        Assert.False((await owner.ReadAsync(CancellationToken.None)).CanQueryStorage);
        Assert.False(fixture.Child.HasExited);
        Assert.False(fixture.Stop.IsCancellationRequested);
        fixture.Send = (request, _) => Task.FromResult(ObservationHealthy(request));
        Assert.True((await owner.ReadAsync(CancellationToken.None)).CanQueryStorage);
        owner.Start(CancellationToken.None); // A normal later query/start cannot create a second child.
        Assert.Same(generation, fixture.Running);
        Assert.Equal(pid, owner.OwnedProcessId);

        fixture.Send = (_, _) => Task.FromResult(RuntimeClientTests.Response(HttpStatusCode.Unauthorized, "{}"));
        Assert.Equal("NATIVE_AUTHENTICATION_UNAVAILABLE", (await owner.ReadAsync(CancellationToken.None)).Failure);
        await generation.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(fixture.Child.HasExited);
        Assert.True(fixture.Stop.IsCancellationRequested);
        Assert.False(owner.Snapshot().CanQueryStorage);
    }

    [Fact]
    public static async Task ObservationRejectsStaleRepliesAndPreservesExitAndAdmissionFailures()
    {
        using var fixture = new ObservationOwnerFixture();
        var owner = fixture.Owner;
        var delayed = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Send = (_, _) => delayed.Task;
        Task<StorageStartupStatus> staleFailure = owner.ReadAsync(CancellationToken.None);
        await owner.StopAsync().WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(fixture.Child.HasExited);
        fixture.NextGeneration();
        int nextPid = owner.OwnedProcessId!.Value;
        delayed.SetResult(RuntimeClientTests.Response(HttpStatusCode.Unauthorized, "{}"));
        Assert.False((await staleFailure).CanQueryStorage);
        Assert.False(fixture.Stop.IsCancellationRequested);
        Assert.Equal(nextPid, owner.OwnedProcessId);

        delayed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        Task<StorageStartupStatus> staleSuccess = owner.ReadAsync(CancellationToken.None);
        await owner.StopAsync().WaitAsync(TimeSpan.FromSeconds(10));
        fixture.NextGeneration();
        delayed.SetResult(RuntimeClientTests.Response(HttpStatusCode.OK, "{\"status\":\"UP\"}"));
        Assert.False((await staleSuccess).CanQueryStorage);
        Assert.False(fixture.Stop.IsCancellationRequested);

        delayed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        Task<StorageStartupStatus> exiting = owner.ReadAsync(CancellationToken.None);
        await fixture.Child.StandardInput.WriteLineAsync(); // Only this fixture child exits of its own accord.
        await fixture.Running.WaitAsync(TimeSpan.FromSeconds(10));
        delayed.SetResult(RuntimeClientTests.Response(HttpStatusCode.OK, "{\"status\":\"UP\"}"));
        Assert.Equal("PROCESS_EXITED", (await exiting).Failure);
        Assert.False(owner.Snapshot().CanQueryStorage);

        fixture.NextGeneration();
        var lateAuthentication = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        fixture.Send = (request, _) => request.RequestUri!.AbsolutePath == "/actuator/health"
            ? Task.FromResult(ObservationHealthy(request)) : lateAuthentication.Task;
        Task<StorageStartupStatus> invalidated = owner.ReadAsync(CancellationToken.None);
        fixture.Send = (request, _) => Task.FromResult(ObservationHealthy(request));
        fixture.Credential = owner.ReadCredential; // An absent synthetic Profile fails the real source check.
        Assert.Equal("ADMISSION_DENIED", (await owner.ReadAsync(CancellationToken.None)).Failure);
        await fixture.Running.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(fixture.Child.HasExited);
        using var readinessRequest = new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1/api/v1/providers/readiness");
        lateAuthentication.SetResult(ObservationHealthy(readinessRequest));
        Assert.Equal("ADMISSION_DENIED", (await invalidated).Failure);
        Assert.False(owner.Snapshot().CanQueryStorage);

        fixture.NextGeneration();
        fixture.Credential = () => new string('t', 43);
        fixture.Send = (_, _) => Task.FromResult(RuntimeClientTests.Response(HttpStatusCode.OK, "{\"status\":\"DOWN\"}"));
        Assert.Equal("STARTUP_CAUSE_UNAVAILABLE", (await owner.ReadAsync(CancellationToken.None)).Failure);
        await fixture.Running.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(fixture.Child.HasExited); // RuntimeUnavailable from a response is not a transport failure.

        // Neither an observed HTTP denial nor a malformed successful HTTP body is a transient failure.
        foreach (var (status, interruption) in new (HttpStatusCode, IOException)[]
        {
            (HttpStatusCode.Forbidden, new IOException("synthetic interrupted response")),
            (HttpStatusCode.OK, new HttpIOException(HttpRequestError.InvalidResponse, "synthetic malformed chunk"))
        })
        {
            fixture.NextGeneration();
            fixture.Send = (_, _) =>
            {
                var rejected = new HttpResponseMessage(status) { Content = new StreamContent(new InterruptedObservationStream(interruption)) };
                rejected.Content.Headers.ContentType = new("application/json");
                return Task.FromResult(rejected);
            };
            Assert.False((await owner.ReadAsync(CancellationToken.None)).CanQueryStorage);
            Assert.True(fixture.Stop.IsCancellationRequested);
            await fixture.Running.WaitAsync(TimeSpan.FromSeconds(10));
            Assert.True(fixture.Child.HasExited);
        }
    }

    private sealed class InterruptedObservationStream(IOException interruption) : MemoryStream
    {
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
            => ValueTask.FromException<int>(interruption);
    }

    private static HttpResponseMessage ObservationHealthy(HttpRequestMessage request)
    {
        Assert.Equal(HttpMethod.Get, request.Method);
        return RuntimeClientTests.Response(HttpStatusCode.OK, request.RequestUri!.AbsolutePath == "/actuator/health"
            ? "{\"status\":\"UP\"}"
            : "{\"provider\":\"ollama\",\"profile\":\"translate.fast\",\"available\":false,\"modelAvailable\":false}");
    }

    // Isolated post-admission assembly, following the existing lifecycle probe's reflection approach.
    // No production startup bypass/injection hook: RunAsync, Java, Profile creation, networking and models
    // are not exercised. The test owns a fresh hidden stdin-waiting process and the real kill-on-close Job.
    private sealed class ObservationOwnerFixture : IDisposable
    {
        private const BindingFlags Fields = BindingFlags.Instance | BindingFlags.NonPublic;
        private readonly RuntimeClient client;
        internal DevelopmentRuntimeOwner Owner { get; }
        internal Process Child => (Process)Get("child");
        internal Task Running => (Task)Get("running");
        internal CancellationTokenSource Stop => (CancellationTokenSource)Get("stop");
        internal Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> Send =
            (request, _) => Task.FromResult(ObservationHealthy(request));
        internal Func<string?> Credential = () => new string('t', 43);

        internal ObservationOwnerFixture()
        {
            var profile = (Mmf3AcceptanceLaunch)typeof(Mmf3AcceptanceLaunch)
                .GetConstructor(Fields, null, [typeof(string)], null)!.Invoke([Guid.NewGuid().ToString("N")]);
            Assert.False(Directory.Exists(profile.Root));
            client = new RuntimeClient(new RuntimeClientTests.Handler((request, token) => Send(request, token)), () => Credential());
            Owner = new DevelopmentRuntimeOwner(profile, client);
            NextGeneration();
        }

        internal void NextGeneration()
        {
            if (typeof(DevelopmentRuntimeOwner).GetField("child", Fields)!.GetValue(Owner) is Process previous)
            {
                Assert.True(Running.IsCompletedSuccessfully);
                Assert.True(previous.HasExited);
                previous.Dispose(); Stop.Dispose(); Set("stop", new CancellationTokenSource());
            }
            var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe"))
                { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true };
            foreach (string arg in new[] { "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "[Console]::ReadLine() | Out-Null" })
                start.ArgumentList.Add(arg);
            var job = RuntimeProcessJob.Create();
            var child = Process.Start(start)!;
            try { job.Assign(child); }
            catch { job.Dispose(); child.Kill(); child.Dispose(); throw; }
            Set("child", child); Set("state", "READY");
            Set("running", MonitorAsync(child, job, Stop.Token));
        }

        private async Task MonitorAsync(Process child, RuntimeProcessJob job, CancellationToken stop)
        {
            try { await child.WaitForExitAsync(stop); }
            catch (OperationCanceledException) { }
            finally
            {
                job.Dispose();
                await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
                lock (Get("sync"))
                    if ((string)Get("state") == "READY") Set("state", "PROCESS_EXITED");
            }
        }

        private object Get(string name) => typeof(DevelopmentRuntimeOwner).GetField(name, Fields)!.GetValue(Owner)!;
        private void Set(string name, object value) => typeof(DevelopmentRuntimeOwner).GetField(name, Fields)!.SetValue(Owner, value);
        public void Dispose() { Owner.Dispose(); client.Dispose(); }
    }
}
#endif
