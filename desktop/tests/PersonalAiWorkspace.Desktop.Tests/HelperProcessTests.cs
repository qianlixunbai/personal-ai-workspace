using System.Diagnostics;
using System.IO;
using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class HelperProcessTests
{
    [Fact]
    public async Task RealSelectionHelperRejectsZeroForegroundWithoutReadingUserSelection()
    {
        var result = await HelperProcess.RunAsync<SelectionResult>("--selection-worker", ["0"], 32768,
            TimeSpan.FromSeconds(3), CancellationToken.None);
        Assert.Equal(SelectionStatus.ForegroundChanged, result!.Status);
        Assert.Null(result.Text);
    }

    [Fact]
    public async Task HungHelperIsTerminatedAndReapedAtDeadline()
    {
        int pid = 0;
        string executable = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe");
        await Assert.ThrowsAsync<HelperTimeoutException>(() => HelperProcess.RunAsync<SelectionResult>("-NoLogo",
            ["-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 30"], 128, TimeSpan.FromMilliseconds(400),
            CancellationToken.None, testExecutable: executable, onStarted: processId => pid = processId));
        Assert.NotEqual(0, pid);
        try { using var process = Process.GetProcessById(pid); Assert.True(process.HasExited); }
        catch (ArgumentException) { /* PID no longer exists: helper was reaped. */ }
    }
}
