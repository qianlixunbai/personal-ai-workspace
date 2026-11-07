using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

// One manual end-to-end case: a newly created canonical synthetic Profile, reviewed native
// helper and pinned application JAR. Never enumerate or open an existing user's Profile.
internal static class RecoveryTrustedInspectionProbe
{
    private const BindingFlags Static = BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Static;
    private const BindingFlags Instance = BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Instance;
    private static string stage = "SOURCES";

    [STAThread]
    private static int Main(string[] args)
    {
        try { Run(args); return 0; }
        catch (Exception error)
        {
            var failure = error is TargetInvocationException wrapped ? wrapped.InnerException! : error;
            Console.WriteLine("STOP: trusted inspection probe; stage=" + stage + "; type=" + failure.GetType().Name);
            Console.WriteLine("Frames: " + string.Join(" | ", new StackTrace(failure).GetFrames() is { } frames
                ? Array.ConvertAll(frames, frame => frame.GetMethod()?.DeclaringType?.Name + "." + frame.GetMethod()?.Name) : []));
            return 10; // No exception messages, raw pipes, SQL or credential values.
        }
    }

    private static void Run(string[] args)
    {
        if (args.Length != 4) throw new IOException();
        var assembly = Assembly.LoadFrom(args[0]);
        Type Owner(string name) => assembly.GetType("PersonalAiWorkspace.Desktop." + name, true)!;
        using var fixture = JsonDocument.Parse(File.ReadAllBytes(args[3]));
        string isolated = fixture.RootElement.GetProperty("root").GetString()!;
        if (!System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(isolated), "\\APAW-SourceProducer-[0-9a-f]{32}\\z")) throw new IOException();
        var sourcesType = Owner("TrustedDevelopmentRuntimeSources");
        using var sources = (IDisposable)sourcesType.GetMethod("Open", Static)!.Invoke(null, null)!;
        string jna = (string)sourcesType.GetProperty("JnaJar", Instance)!.GetValue(sources)!;
        string profileId = Guid.NewGuid().ToString("N");
        var state = Owner("Mmf3ProfileState");
        string root = (string)state.GetMethod("Root", Static)!.Invoke(null, [profileId])!;
        if (Path.Exists(root)) throw new IOException();
        // Record the exact new ID before creation; partial state is retained, never reused.
        File.WriteAllText(Path.Combine(isolated, "owned-synthetic-profile.json"), JsonSerializer.Serialize(new { profileId, root, newlyAllocated = true }));
        stage = "PROFILE_PREPARATION";
        if ((string)state.GetMethod("Prepare", Static)!.Invoke(null, [profileId])! != root) throw new IOException();
        stage = "PROFILE_PROVISION";
        Owner("Mmf3ProfileNative").GetMethod("Provision", Static)!.Invoke(null, [profileId, jna]);
        stage = "PROFILE_VERIFY";
        var launchType = Owner("Mmf3AcceptanceLaunch");
        string argument = (string)launchType.GetField("Argument", Static)!.GetRawConstantValue()!;
        object profile = launchType.GetMethod("Parse", Static)!.Invoke(null, [new[] { argument, profileId }])!;

        stage = "DATABASE_FIXTURE";
        byte[] configuration = RunFixture(args, "prepare-native-inspection");
        try
        {
            stage = "REAL_NATIVE_CONFIGURATION";
            var setup = (ProcessStartInfo)sourcesType.GetMethod("StartInfo", Instance)!.Invoke(sources, [profile, true, false, false])!;
            using var child = Process.Start(setup) ?? throw new IOException();
            var errors = child.StandardError.BaseStream.CopyToAsync(Stream.Null);
            try
            {
                string? hello = child.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(30)).GetAwaiter().GetResult();
                if (hello != "WORKSPACE_DATABASE_V1\tADMITTED") throw new IOException();
                child.StandardInput.BaseStream.Write(Encoding.ASCII.GetBytes("SAVE!\n"));
                child.StandardInput.BaseStream.Write(configuration); child.StandardInput.Close();
                string? result = child.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(30)).GetAwaiter().GetResult();
                if (result != "WORKSPACE_DATABASE_V1\tSAVED" || !child.WaitForExit(15000) || child.ExitCode != 0
                    || child.StandardOutput.Read() != -1) throw new IOException();
                errors.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
            }
            finally { if (!child.HasExited) { child.Kill(); child.WaitForExit(5000); } }
        }
        finally { CryptographicOperations.ZeroMemory(configuration); }

        string runtimeLock = Path.Combine(root, "evidence", "runtime-admission.lock");
        string modelLock = Path.Combine(root, "model-state", ".lock");
        if (!File.Exists(runtimeLock) || new FileInfo(runtimeLock).Length != 0) throw new IOException();
        // Only fixture setup creates an empty model lock. No runtime history or safe-stop
        // record is invented; the product must report that history as unknown.
        stage = "MODEL_LOCK_FIXTURE";
        CreateFixtureFile(modelLock, []);
        string configFile = Path.Combine(root, "data", "database.dpapi");
        byte[] before = SHA256.HashData(File.ReadAllBytes(configFile));
        stage = "TRUSTED_MAINTENANCE";
        var coordinator = Owner("ProfileRecoveryCoordinator");
        object? observation;
        using (var owner = (IDisposable)coordinator.GetMethod("OpenForPlanning", Static)!.Invoke(null, [profile])!)
        {
            var task = (Task)coordinator.GetMethod("InspectSourceReadinessAsync", Instance)!.Invoke(owner, null)!;
            task.GetAwaiter().GetResult();
            observation = task.GetType().GetProperty("Result")!.GetValue(task)!;
            var preliminary = observation.GetType().GetProperty("Native")!.GetValue(observation)!;
            if ((string)preliminary.GetType().GetProperty("SourceState")!.GetValue(preliminary)! != "NOT_READ_SOURCE_BINDING_NOT_AUTHORIZED"
                || (long)preliminary.GetType().GetProperty("ChildPid")!.GetValue(preliminary)! != 0) throw new IOException();
            // Only this newly created, explicitly synthetic Profile is auto-confirmed by the
            // manual probe. Product default inspection cannot authorize credential/SQL access.
            System.Windows.EventManager.RegisterClassHandler(typeof(System.Windows.Window), System.Windows.FrameworkElement.LoadedEvent,
                new System.Windows.RoutedEventHandler((sender, _) =>
                {
                    if (sender is not System.Windows.Window window || window.Content is not System.Windows.Controls.StackPanel panel
                        || window.Title != "Personal AI Workspace · 只读来源连接授权") return;
                    bool matches = false;
                    foreach (object item in panel.Children)
                        if (item is System.Windows.Controls.TextBlock text && text.Text.Contains(profileId, StringComparison.Ordinal)) matches = true;
                    if (!matches) return;
                    foreach (object item in panel.Children)
                        if (item is System.Windows.Controls.Button button && button.Name == "AuthorizeSourceInspection")
                            window.Dispatcher.BeginInvoke(new Action(() => button.RaiseEvent(new System.Windows.RoutedEventArgs(System.Windows.Controls.Button.ClickEvent))));
                }));
            task = (Task)coordinator.GetMethod("RequestSourceBindingInspectionAsync", Instance)!.Invoke(owner, null)!;
            task.GetAwaiter().GetResult();
            observation = task.GetType().GetProperty("Result")!.GetValue(task)!;
            var native = observation.GetType().GetProperty("Native")!.GetValue(observation)!;
            object? Field(string name) => native.GetType().GetProperty(name)!.GetValue(native);
            if ((string)Field("Admission")! != "BLOCKED" || (string)Field("SourceState")! != "BOUND_READ_ONLY"
                || (string)Field("WriterCoverage")! != "UNREGISTERED_WRITERS_UNPROVEN"
                || observation.GetType().GetProperty("ObservedExitedChildren")!.GetValue(observation) is not null) throw new IOException();
            try { using var wrong = new FileStream(runtimeLock, FileMode.Open, FileAccess.Read, FileShare.Read); throw new InvalidOperationException(); }
            catch (IOException) { }
            try { using var wrong = new FileStream(configFile, FileMode.Open, FileAccess.Write, FileShare.ReadWrite); throw new InvalidOperationException(); }
            catch (IOException) { }
        }
        using (new FileStream(runtimeLock, FileMode.Open, FileAccess.Read, FileShare.Read)) { }
        stage = "UNRESOLVED_GUARD_ENTRY";
        string guard = Path.Combine(root, "model-state", "execution-guard.pending");
        CreateFixtureFile(guard, Encoding.ASCII.GetBytes("synthetic unresolved execution; retain evidence"));
        string message = "";
        int exit = (int)Owner("Program").GetMethod("InspectRecoveryMaintenance", Static)!.Invoke(null,
            [new[] { "--inspect-profile-recovery-v1", profileId }, (Action<string>)(text => message = text)])!;
        if (exit != 3 || !message.Contains("BLOCKED", StringComparison.Ordinal)
            || !message.Contains("NOT_READ_MODEL_STATE_UNRESOLVED", StringComparison.Ordinal)
            || !File.Exists(guard) || !CryptographicOperations.FixedTimeEquals(before, SHA256.HashData(File.ReadAllBytes(configFile)))) throw new IOException();
        stage = "SOURCE_UNCHANGED";
        if (Encoding.UTF8.GetString(RunFixture(args, "verify-native-inspection")) != "VERIFIED_UNCHANGED") throw new IOException();
        File.WriteAllText(Path.Combine(isolated, "native-source-evidence.json"), JsonSerializer.Serialize(new {
            status = "TRUSTED_NATIVE_SOURCE_INSPECTION_VERIFIED", profileId, root, pinnedRuntimeSources = true,
            realNativeGateExercised = true, protectedConfigurationSavedByNativeSetup = true, realMaintenanceHandleTransfer = true,
            applicationConnectionBound = true, runtimeHistoryMissingRemainsUnknown = true, unresolvedGuardPreserved = true,
            productEntryTerminalBlocked = true, sourceDatabaseUnchanged = true, personalSourceRegistration = false,
            configurationSha256 = Convert.ToHexStringLower(before), observation }));
        Console.WriteLine("PASS: pinned application JAR/JVM -> real Native Gate -> owned maintenance handle -> protected source/app connection -> native read-only result.");
        Console.WriteLine("PASS: missing runtime history remains UNKNOWN; unresolved guard preserved; product entry returns BLOCKED; source unchanged.");
    }

    private static byte[] RunFixture(string[] args, string action)
    {
        var start = new ProcessStartInfo(args[1]) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (string value in new[] { "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp", args[2],
            "io.github.qianlixunbai.workspace.backup.RecoverySourceProducerProbe", action, args[3] }) start.ArgumentList.Add(value);
        using var child = Process.Start(start) ?? throw new IOException();
        var errors = child.StandardError.BaseStream.CopyToAsync(Stream.Null);
        try
        {
            byte[] result = new byte[16385]; int size = 0, count;
            while (size < result.Length && (count = child.StandardOutput.BaseStream.ReadAsync(result.AsMemory(size))
                .AsTask().WaitAsync(TimeSpan.FromSeconds(30)).GetAwaiter().GetResult()) != 0) size += count;
            if (size == 0 || size == result.Length || !child.WaitForExit(10000) || child.ExitCode != 0) throw new IOException();
            errors.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
            byte[] bounded = result.AsSpan(0, size).ToArray(); CryptographicOperations.ZeroMemory(result); return bounded;
        }
        finally { if (!child.HasExited) { child.Kill(); child.WaitForExit(5000); } }
    }

    private static void CreateFixtureFile(string path, byte[] bytes)
    {
        var user = WindowsIdentity.GetCurrent().User!;
        var acl = new FileSecurity(); acl.SetOwner(user); acl.SetAccessRuleProtection(true, false);
        acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl, AccessControlType.Allow));
        // Install the private descriptor at CREATE_NEW. A normal ReadWrite FileStream
        // does not request WRITE_DAC, so SetAccessControl on that handle fails on Windows.
        using var file = new FileInfo(path).Create(FileMode.CreateNew, FileSystemRights.Read | FileSystemRights.Write,
            FileShare.None, 4096, FileOptions.None, acl);
        file.Write(bytes); file.Flush(true);
    }
}
