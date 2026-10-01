using System;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace PersonalAiWorkspace.Desktop;

internal sealed class HelperTimeoutException : Exception;
internal static class HelperProcess
{
    internal static async Task<T?> RunAsync<T>(string mode, string[] arguments, int maximumBytes,
        TimeSpan timeout, CancellationToken cancellationToken, object? input = null, string? testExecutable = null, Action<int>? onStarted = null)
    {
        var start = new ProcessStartInfo(testExecutable ?? Path.Combine(AppContext.BaseDirectory, "PersonalAiWorkspace.Desktop.exe"))
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true,
            RedirectStandardError = true, RedirectStandardInput = true, WindowStyle = ProcessWindowStyle.Hidden
        };
        start.ArgumentList.Add(mode);
        foreach (string argument in arguments) start.ArgumentList.Add(argument);
        using var worker = new Process { StartInfo = start };
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        try
        {
            if (!worker.Start()) throw new InvalidOperationException("Helper unavailable.");
            onStarted?.Invoke(worker.Id);
            if (input is not null)
            {
                await JsonSerializer.SerializeAsync(worker.StandardInput.BaseStream, input, cancellationToken: deadline.Token);
                await worker.StandardInput.BaseStream.FlushAsync(deadline.Token);
            }
            worker.StandardInput.Close();
            using var buffer = new MemoryStream();
            var chunk = new byte[4096];
            int count;
            while ((count = await worker.StandardOutput.BaseStream.ReadAsync(chunk, deadline.Token)) > 0)
            {
                if (buffer.Length + count > maximumBytes) throw new InvalidOperationException("Helper response invalid.");
                buffer.Write(chunk, 0, count);
            }
            await worker.WaitForExitAsync(deadline.Token);
            if (worker.ExitCode != 0) throw new InvalidOperationException("Helper unavailable.");
            return JsonSerializer.Deserialize<T>(buffer.ToArray());
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { throw new HelperTimeoutException(); }
        finally
        {
            try { if (!worker.HasExited) { worker.Kill(entireProcessTree: true); await worker.WaitForExitAsync(); } }
            catch (InvalidOperationException) { }
        }
    }
}
