using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text.Json;

// Creates a fresh synthetic Profile, or explicitly resumes a prepared memory-usage
// fixture whose recorded host failed before starting Java. Optional PostgreSQL input is a fresh fixture
// whose private path, cluster and process ownership are checked before database mutation.
internal static class MemoryRecallIntegrationProbe
{
    private const BindingFlags Methods = BindingFlags.Static | BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
    [STAThread]
    private static int Main(string[] args)
    {
        string stage = "INPUT";
        try
        {
            bool resume = args.Length == 8 && args[6] == "--resume-prepared";
            bool memoryUsage = (args.Length == 6 || resume) && args[4] == "--scenario" && args[5] == "memory-usage";
            if (args.Length is not (4 or 5) && !memoryUsage) return 2;
            if (args.Length == 5 && args[4] == "--scenario") return 2;
            string java = Path.GetFullPath(args[0]), jna = Path.GetFullPath(args[1]),
                classpath = File.ReadAllText(args[2]).Trim(), evidence = Path.GetFullPath(args[3]);
            var assembly = typeof(PersonalAiWorkspace.Desktop.MemorySelectionWindow).Assembly;
            Type Owner(string name) => assembly.GetType("PersonalAiWorkspace.Desktop." + name, true)!;
            var state = Owner("Mmf3ProfileState");
            if (File.Exists(Path.Combine(evidence, "owned-profile.json"))) throw new IOException(); // Never overwrite a run.
            string id;
            string? preparedRoot = null;
            if (resume)
            {
                string previous = Path.GetFullPath(args[7]);
                if (previous == evidence || Directory.GetParent(previous)?.FullName != Directory.GetParent(evidence)?.FullName)
                    throw new IOException();
                using var owned = JsonDocument.Parse(File.ReadAllText(Path.Combine(previous, "owned-profile.json")));
                using var failure = JsonDocument.Parse(File.ReadAllText(Path.Combine(previous, "failure.json")));
                using var ended = JsonDocument.Parse(File.ReadAllText(Path.Combine(previous, "host-result.json")));
                if (!owned.RootElement.GetProperty("synthetic").GetBoolean()
                    || failure.RootElement.GetProperty("stage").GetString() != "JAVA"
                    || failure.RootElement.GetProperty("type").GetString() != nameof(AmbiguousMatchException)
                    || !ended.RootElement.GetProperty("hostExited").GetBoolean()
                    || ended.RootElement.GetProperty("externallyForced").GetBoolean()
                    || ended.RootElement.GetProperty("exitCode").GetInt32() != 10
                    || File.Exists(Path.Combine(previous, "integration.log"))
                    || File.Exists(Path.Combine(previous, "child-start.json"))
                    || File.Exists(Path.Combine(previous, "child-result.json"))) throw new IOException();
                id = owned.RootElement.GetProperty("id").GetString() ?? throw new IOException();
                preparedRoot = owned.RootElement.GetProperty("root").GetString() ?? throw new IOException();
            }
            else id = Guid.NewGuid().ToString("N");
            string root = (string)state.GetMethod("Root", Methods)!.Invoke(null, [id])!;
            if (resume ? root != preparedRoot : Path.Exists(root)) throw new IOException();
            File.WriteAllText(Path.Combine(evidence, "owned-profile.json"), JsonSerializer.Serialize(new { id, root, synthetic = true, resumedPrepared = resume }));
            if (resume)
            {
                stage = "VERIFY_PREPARED";
                if ((string)state.GetMethod("Verify", Methods)!.Invoke(null, [id])! != root) throw new IOException();
            }
            else
            {
                stage = "PREPARE";
                state.GetMethod("Prepare", Methods)!.Invoke(null, [id]);
                stage = "PROVISION";
                Owner("Mmf3ProfileNative").GetMethod("Provision", Methods)!.Invoke(null, [id, jna]);
            }
            using var pin = (IDisposable)state.GetMethod("Pin", Methods)!.Invoke(null, [id])!;
            string helper = (string)Owner("Mmf3ProfileNative").GetProperty("Helper", Methods)!.GetValue(null)!;
            var start = new ProcessStartInfo(java) { UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = root };
            foreach (string option in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Dworkspace.native.development-profile=" + id,
                "-Dworkspace.native.bootstrap=" + helper, "-Djna.boot.library.path=" + Path.Combine(root, "jna-5.17.0-win32-x86-64"),
                "-Djna.boot.library.name=jnidispatch", "-Djna.nosys=true", "-Djna.noclasspath=true", "-Djna.nounpack=true",
                "-Djava.io.tmpdir=" + Path.Combine(root, "evidence"), "-Djna.tmpdir=" + Path.Combine(root, "evidence"),
                "-cp", classpath }) start.ArgumentList.Add(option);
            // Optional, fresh synthetic PostgreSQL fixture; the Java test verifies its private
            // path, owning process and empty cluster before any database mutation.
            if (args.Length == 5) start.ArgumentList.Add("-Dmemory.postgresql.fixture=" + Path.GetFullPath(args[4]));
            start.ArgumentList.Add("MemoryRecallIntegrationProbe");
            if (memoryUsage) start.ArgumentList.Add("memory-usage");
            stage = "JAVA";
            using var job = (IDisposable)Owner("RuntimeProcessJob").GetMethod("Create", Methods, Type.EmptyTypes)!.Invoke(null, null)!;
            using var child = Process.Start(start) ?? throw new IOException();
            try
            {
                File.WriteAllText(Path.Combine(evidence, "child-start.json"), JsonSerializer.Serialize(new { id, childPid = child.Id }));
                Owner("RuntimeProcessJob").GetMethod("Assign", Methods, [typeof(Process)])!.Invoke(job, [child]);
                using var stdout = File.Create(Path.Combine(evidence, "integration.log"));
                using var stderr = File.Create(Path.Combine(evidence, "integration-errors.log"));
                var output = child.StandardOutput.BaseStream.CopyToAsync(stdout);
                var errors = child.StandardError.BaseStream.CopyToAsync(stderr);
                if (!child.WaitForExit(120000)) throw new TimeoutException();
                Task.WhenAll(output, errors).WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
                File.WriteAllText(Path.Combine(evidence, "child-result.json"), JsonSerializer.Serialize(new {
                    id, childPid = child.Id, exitCode = child.ExitCode, childExited = child.HasExited, existingServicesTouched = false }));
                Console.WriteLine("MEMORY_RECALL_INTEGRATION_EXIT=" + child.ExitCode);
                return child.ExitCode;
            }
            finally
            {
                bool forced = !child.HasExited;
                if (forced) { child.Kill(entireProcessTree: true); child.WaitForExit(5000); }
                File.WriteAllText(Path.Combine(evidence, "child-cleanup.json"), JsonSerializer.Serialize(new {
                    id, childPid = child.Id, childExited = child.HasExited, forced }));
            }
        }
        catch (Exception error)
        {
            var actual = error is TargetInvocationException wrapped ? wrapped.InnerException! : error;
            Console.WriteLine("MEMORY_RECALL_STOP stage=" + stage + " type=" + actual.GetType().Name);
            Console.WriteLine("FRAMES " + string.Join(" | ", new StackTrace(actual).GetFrames().Select(f =>
                f.GetMethod()?.DeclaringType?.Name + "." + f.GetMethod()?.Name)));
            if (args.Length >= 4 && Directory.Exists(args[3]) && !File.Exists(Path.Combine(args[3], "failure.json"))) File.WriteAllText(Path.Combine(args[3], "failure.json"),
                JsonSerializer.Serialize(new { stage, type = actual.GetType().Name, frames = new StackTrace(actual).GetFrames()
                    .Select(f => f.GetMethod()?.DeclaringType?.Name + "." + f.GetMethod()?.Name).ToArray() }));
            return 10;
        }
    }
}
