#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.Principal;
using PersonalAiWorkspace.WindowsSecurity;
using Xunit;
using Rules = PersonalAiWorkspace.Desktop.ProfileRecoveryCoordinator.LegacyRuleChange;

namespace PersonalAiWorkspace.Desktop.Tests;

// One focused native integration test and a manual bridge used only by the fresh PG fixture.
public sealed class LegacyBoundaryTests
{
    private static object Invoke(Type type, string name, params object[] args) =>
        type.GetMethod(name, BindingFlags.NonPublic | BindingFlags.Static)!.Invoke(null, args)!;
    private static Rules Prepare(string root, byte[] rules) => (Rules)Invoke(typeof(Rules), "PrepareSynthetic", root, rules);
    private static Rules Open(string root) => (Rules)Invoke(typeof(Rules), "OpenSynthetic", root);
    private static string NewRoot()
    {
        string root = Path.Combine(Path.GetTempPath(), "paw-legacy-fence-" + Guid.NewGuid().ToString("N"));
        var sid = WindowsIdentity.GetCurrent().User!;
        WindowsProfileSecurity.CreateDirectory(root, sid);
        WindowsProfileSecurity.CreateDirectory(Path.Combine(root, "pgdata"), sid);
        return root;
    }
    private static void InterruptBeforePublication(Rules owner, bool restoring)
    {
        var type = typeof(Rules);
        var intent = (Rules.Intent)type.GetField("intent", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(owner)!;
        string PathOf(string name) => (string)type.GetProperty(name, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(owner)!;
        Invoke(type, "MoveExact", PathOf("Hba"), PathOf(restoring ? "Retired" : "Original"), restoring ? intent.Installed : intent.Original);
    }

    [Fact]
    public async Task InterruptedRulesAndOwnedModelTreeRequireActualCurrentEvidence()
    {
        string root = NewRoot(), hba = Path.Combine(root, "pgdata", "pg_hba.conf");
        byte[] original = "# SYNTHETIC ORIGINAL\r\n"u8.ToArray(), installed = "# SYNTHETIC FENCE\r\n"u8.ToArray();
        File.WriteAllBytes(hba, original);
        using (var owner = Prepare(root, installed))
        {
            Assert.Equal(Rules.State.Prepared, owner.Inspect());
            Assert.Throws<TargetInvocationException>(() => Open(root));
            InterruptBeforePublication(owner, restoring: false);
        }
        using (var reopened = Open(root))
        {
            Assert.Equal(Rules.State.InstallInterrupted, reopened.Inspect());
            Assert.False(File.Exists(hba)); // inspection did not replay or fix anything
            Assert.Equal(Rules.State.Prepared, reopened.Restore());
            reopened.Install();
        }
        string moved = hba + ".third-party-original";
        File.Move(hba, moved); File.WriteAllBytes(hba, installed); // same bytes, different identity
        using (var conflicted = Open(root))
        {
            Assert.Equal(Rules.State.Conflict, conflicted.Inspect());
            Assert.Throws<IOException>(() => conflicted.Restore());
            Assert.Equal(installed, File.ReadAllBytes(hba));
        }
        File.Move(hba, hba + ".third-party-retained"); File.Move(moved, hba);
        using (var owner = Open(root)) InterruptBeforePublication(owner, restoring: true);
        using (var reopened = Open(root))
        {
            Assert.Equal(Rules.State.RestoreInterrupted, reopened.Inspect());
            Assert.Equal(Rules.State.Restored, reopened.Restore());
            Assert.Equal(original, File.ReadAllBytes(hba));
        }

        string executable = Path.Combine(AppContext.BaseDirectory, "PersonalAiWorkspace.Desktop.Tests.exe");
        Guid operation = Guid.NewGuid();
        using var execution = (RecoveryOwnedModelExecution)Invoke(typeof(RecoveryOwnedModelExecution), "StartSynthetic",
            operation, executable, new[] { "--legacy-model-fixture", root, "server" });
        Assert.Throws<IOException>(() => execution.TryConsumeCompletedTree(Guid.NewGuid()));
        var deadline = Stopwatch.StartNew();
        while (!execution.RootExited && deadline.Elapsed < TimeSpan.FromSeconds(15)) await Task.Delay(20);
        Assert.True(execution.RootExited);
        Assert.True(File.Exists(Path.Combine(root, "runner-ready")));
        Assert.False(execution.TryConsumeCompletedTree(operation)); // server exited; runner is still executing
        File.WriteAllText(Path.Combine(root, "release-runner"), "SYNTHETIC_COMPLETION");
        bool completed = false;
        while (!completed && deadline.Elapsed < TimeSpan.FromSeconds(20))
        { completed = execution.TryConsumeCompletedTree(operation); if (!completed) await Task.Delay(20); }
        Assert.True(completed);
        Assert.Throws<IOException>(() => execution.TryConsumeCompletedTree(operation)); // no replay
        execution.Dispose();
        Assert.Throws<ObjectDisposedException>(() => execution.TryConsumeCompletedTree(operation));
    }

    internal static int RunRuleFixture(string root, string action)
    {
        try
        {
            using var owner = action == "install" ? Prepare(root, File.ReadAllBytes(Path.Combine(root, "synthetic-rules.input"))) : Open(root);
            switch (action)
            {
                case "install": owner.Install(); break;
                case "restore": owner.Restore(); break;
                case "restore-interrupted":
                    if (owner.Inspect() != Rules.State.Installed) return 10;
                    InterruptBeforePublication(owner, restoring: true); break;
                case "inspect": break;
                default: return 2;
            }
            Console.WriteLine(owner.Inspect()); return 0;
        }
        catch (Exception error) { Console.WriteLine("BLOCKED_RULE_FIXTURE_" + (error.InnerException ?? error).GetType().Name); return 10; }
    }
    internal static int RunModelFixture(string root, string mode)
    {
        if (Path.GetDirectoryName(root) != Path.TrimEndingDirectorySeparator(Path.GetTempPath())
            || !System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(root), "\\Apaw-legacy-fence-[0-9a-f]{32}\\z")) return 2;
        if (mode == "server")
        {
            var start = new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = false, CreateNoWindow = true };
            foreach (string argument in new[] { "--legacy-model-fixture", root, "runner" }) start.ArgumentList.Add(argument);
            using var child = Process.Start(start)!;
            var wait = Stopwatch.StartNew();
            while (!File.Exists(Path.Combine(root, "runner-ready")) && wait.Elapsed < TimeSpan.FromSeconds(10)) Thread.Sleep(10);
            return File.Exists(Path.Combine(root, "runner-ready")) ? 0 : 11;
        }
        if (mode == "runner")
        {
            File.WriteAllText(Path.Combine(root, "runner-ready"), "SYNTHETIC_EXECUTION");
            var wait = Stopwatch.StartNew();
            while (!File.Exists(Path.Combine(root, "release-runner")) && wait.Elapsed < TimeSpan.FromSeconds(25)) Thread.Sleep(10);
            return File.Exists(Path.Combine(root, "release-runner")) ? 0 : 12;
        }
        return 2;
    }
}
#endif
