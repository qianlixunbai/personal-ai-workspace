using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.Principal;
using System.Threading.Tasks;

// Explicit integration probe for a new temporary tree and one directly owned Java child.
// Calls private OS seams only; no Known Folder, real Native Gate/Profile, credentials or SQL.
internal static class RecoveryMaintenanceExclusionProbe
{
    private static async Task Main(string[] args)
    {
        if (args.Length != 3) throw new IOException("Explicit fixture tooling required");
        var assembly = Assembly.LoadFrom(args[0]);
        var coordinator = assembly.GetType("PersonalAiWorkspace.Desktop.ProfileRecoveryCoordinator", true)!;
        var security = assembly.GetType("PersonalAiWorkspace.WindowsSecurity.WindowsProfileSecurity", true)!;
        var create = security.GetMethod("CreateDirectory", BindingFlags.Static | BindingFlags.NonPublic)!;
        var open = coordinator.GetMethod("OpenRecord", BindingFlags.Static | BindingFlags.NonPublic)!;
        var duplicate = coordinator.GetMethod("DuplicateExclusion", BindingFlags.Static | BindingFlags.NonPublic)!;
        string app = Path.Combine(Path.GetTempPath(), "paw-maintenance-transfer-" + Guid.NewGuid().ToString("N"));
        if (Path.Exists(app)) throw new IOException();
        string profiles = Path.Combine(app, "Profiles"), root = Path.Combine(profiles, Guid.NewGuid().ToString("N"));
        string evidence = Path.Combine(root, "evidence"), model = Path.Combine(root, "model-state");
        foreach (string path in new[] { app, profiles, root, evidence, model })
            create.Invoke(null, [path, WindowsIdentity.GetCurrent().User!]);
        string runtime = Path.Combine(evidence, "runtime-admission.lock"), modelFile = Path.Combine(model, ".lock");
        File.WriteAllBytes(runtime, []); File.WriteAllBytes(modelFile, []);
        using var original = (FileStream)open.Invoke(null, [runtime, true])!;
        var start = new ProcessStartInfo(args[1]) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = app };
        foreach (string value in new[] { "-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", args[2],
            "io.github.qianlixunbai.workspace.persistence.RecoveryMaintenanceExclusionProbe", root }) start.ArgumentList.Add(value);
        using var child = Process.Start(start) ?? throw new IOException();
        var errors = child.StandardError.ReadToEndAsync();
        async Task Expect(string value)
        {
            string? actual = await child.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(15));
            if (actual != value)
            {
                string detail = actual is null ? await errors : actual;
                throw new IOException("Unexpected synthetic child result: " + detail);
            }
        }
        void Refused(Action action)
        {
            try { action(); } catch (IOException) { return; }
            throw new IOException("Synthetic exclusion unexpectedly released");
        }
        try
        {
            await Expect("CHILD_PID=" + child.Id.ToString(System.Globalization.CultureInfo.InvariantCulture));
            long remote = (long)duplicate.Invoke(null, [original, child])!;
            await child.StandardInput.WriteLineAsync(remote.ToString(System.Globalization.CultureInfo.InvariantCulture));
            await child.StandardInput.FlushAsync(); await Expect("EXCLUSION_HELD");
            Refused(() => new FileStream(runtime, FileMode.Open, FileAccess.Read, FileShare.Read).Dispose());
            original.Dispose(); // Child now retains the writer without any parent handle.
            Refused(() => new FileStream(runtime, FileMode.Open, FileAccess.Read, FileShare.Read).Dispose());
            Refused(() => new FileStream(modelFile, FileMode.Open, FileAccess.Write, FileShare.Read).Dispose());
            Refused(() => File.Delete(runtime));
            Refused(() => Directory.Move(model, model + "-moved"));
            await child.StandardInput.WriteLineAsync("CHECK"); await child.StandardInput.FlushAsync(); await Expect("EXCLUSION_STILL_HELD");
            await child.StandardInput.WriteLineAsync("RELEASE"); await child.StandardInput.FlushAsync();
            await Expect("RELEASED_AND_READ_ONLY_REJECTED"); child.StandardInput.Close();
            await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(15));
            if (child.ExitCode != 0 || await child.StandardOutput.ReadToEndAsync() != "" || await errors != "") throw new IOException("Synthetic child failed");
            using (new FileStream(runtime, FileMode.Open, FileAccess.Read, FileShare.Read)) { }
            using (new FileStream(modelFile, FileMode.Open, FileAccess.Write, FileShare.Read)) { }
            Console.WriteLine("PASS: real C# -> Java handle transfer; exclusion survives parent close; read-only rejected; all locks released on child exit.");
            Console.WriteLine("Retained synthetic fixture: " + app);
        }
        finally
        {
            if (!child.HasExited) { child.Kill(); child.WaitForExit(5000); } // This directly created probe only.
        }
    }
}
