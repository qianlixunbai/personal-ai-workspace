#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public static partial class RuntimeLifecycleProbe
{
    private static int RunOrdinaryNativeLifecycle(string id, string mode, string receipt)
    {
        string stage = "PREPARE";
        try
        {
            if (mode is not ("first" or "reopen" or "interrupt") || File.Exists(receipt)) throw new IOException();
            if (mode == "first")
            {
                using var sources = TrustedDevelopmentRuntimeSources.Open();
                Mmf3ProfileState.Prepare(id); Mmf3ProfileNative.Provision(id, sources.JnaJar);
            }
            var profile = Mmf3AcceptanceLaunch.Parse([Mmf3AcceptanceLaunch.Argument, id]);
            using var pins = Mmf3ProfileState.Pin(id);
            using var single = new SingleInstance(profile.InstanceSuffix);
            Assert.True(single.IsPrimary);
            DevelopmentRuntimeOwner? owner = null;
            using var client = RuntimeClient.CreateMmf3Acceptance(() => owner?.ReadCredential());
            owner = new DevelopmentRuntimeOwner(profile, client);
            Guid row;
            int pid;
            try
            {
                stage = "NATIVE_START";
                owner.Start(CancellationToken.None);
                var clock = Stopwatch.StartNew();
                while (!owner.Snapshot().CanQueryStorage)
                {
                    Assert.Equal("STARTING", owner.Snapshot().Runtime);
                    Assert.True(clock.Elapsed < TimeSpan.FromSeconds(70));
                    Thread.Sleep(100);
                }
                pid = owner.OwnedProcessId!.Value;
                stage = "REAL_DATA";
                if (mode == "first") row = client.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE, "Native lifecycle", "Preserve ordinary lifecycle data"), CancellationToken.None).GetAwaiter().GetResult().Id;
                else
                {
                    using var first = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(Path.GetDirectoryName(receipt)!, "first-result.json")));
                    row = first.RootElement.GetProperty("row").GetGuid();
                    Assert.Equal("Preserve ordinary lifecycle data", client.GetMemoryAsync(row, CancellationToken.None).GetAwaiter().GetResult().Content);
                }
                stage = "NATIVE_CLOSE";
                if (mode == "interrupt") owner.Dispose(); // Actual Job cleanup, never request CLOSE.
                else owner.StopAsync().GetAwaiter().GetResult();
                Assert.Equal(mode != "interrupt", owner.LastStopConfirmed);
                Assert.False(owner.Snapshot().CanQueryStorage);
            }
            finally { owner.Dispose(); }

            stage = "HISTORY";
            using (var released = new FileStream(Path.Combine(profile.Root, "model-state", ".lock"), FileMode.Open, FileAccess.ReadWrite, FileShare.None))
                Assert.Equal(0, released.Length);
            // Read the real product's protected records, with the same live exclusion used by
            // maintenance. The fixture does not create a record or infer one from a PID.
            using var coordinator = ProfileRecoveryCoordinator.OpenForPlanning(profile);
            var evidence = coordinator.InspectSourceReadinessAsync().GetAwaiter().GetResult();
            var history = evidence.RuntimeHistory;
            int expected = mode == "first" ? 1 : mode == "reopen" ? 2 : 3;
            Assert.Equal(expected, history.RegisteredAttempts);
            Assert.Equal(Math.Min(expected, 2), history.NativeContextClosed);
            Assert.Equal(mode == "interrupt" ? "RUNTIME_HISTORY_UNRESOLVED" : "EXIT_OBSERVATIONS_COMPLETE", history.State);
            if (mode == "interrupt") Assert.Contains("OWNER_CLEANUP_NOT_SAFE_STOP", history.Issues);
            File.WriteAllText(receipt, JsonSerializer.Serialize(new { passed=true, profile=id, mode, pid, row,
                history, productionGo=false, externalModelExecutionProven=false }));
            Console.WriteLine("ORDINARY_NATIVE_LIFECYCLE_PASS " + mode); return 0;
        }
        catch (Exception failure)
        {
            Console.WriteLine("ORDINARY_NATIVE_LIFECYCLE_STOP " + stage + " " + failure.GetType().Name);
            // Code locations only, no application credentials, paths or business contents.
            Console.WriteLine(failure.StackTrace); return 10;
        }
    }
}
#endif
