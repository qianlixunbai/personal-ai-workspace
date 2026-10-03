using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed record MemoryExportDestination(string Path, bool OverwriteApproved)
{ public override string ToString() => "MemoryExportDestination[redacted]"; }
internal interface IMemoryBackupFiles
{
    MemoryExportDestination? PickExport(Window owner);
    string? PickBackup(Window owner);
    string? PickTarget(Window owner);
    Task<byte[]> ReadAsync(string file, CancellationToken token);
    Task WriteAsync(MemoryExportDestination file, ReadOnlyMemory<byte> bytes, CancellationToken token);
}
internal sealed class NativeMemoryBackupFiles : IMemoryBackupFiles
{
    public MemoryExportDestination? PickExport(Window owner)
    {
        var dialog = new SaveFileDialog { Title = "Export Memory — plaintext personal data", Filter = "Memory backup (*.json)|*.json",
            FileName = "memory-backup.json", DefaultExt = ".json", AddExtension = true, OverwritePrompt = true };
        return dialog.ShowDialog(owner) == true ? new(dialog.FileName, File.Exists(dialog.FileName)) : null;
    }
    public string? PickBackup(Window owner)
    {
        var dialog = new OpenFileDialog { Title = "Restore Memory Backup", Filter = "Memory backup (*.json)|*.json", CheckFileExists = true };
        return dialog.ShowDialog(owner) == true ? dialog.FileName : null;
    }
    public string? PickTarget(Window owner)
    {
        var dialog = new OpenFolderDialog { Title = "Choose a NEW / EMPTY restore data directory", Multiselect = false };
        return dialog.ShowDialog(owner) == true ? dialog.FolderName : null;
    }
    public async Task<byte[]> ReadAsync(string file, CancellationToken token)
    {
        try
        {
            using var input = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read, 8192, true);
            if (input.Length > RuntimeClient.MaximumBackupBytes) throw new DesktopException(DesktopError.MemoryBackupTooLarge);
            using var buffer = new MemoryStream(); byte[] chunk = new byte[8192]; int count;
            while ((count = await input.ReadAsync(chunk, token)) != 0)
            {
                if (buffer.Length + count > RuntimeClient.MaximumBackupBytes) throw new DesktopException(DesktopError.MemoryBackupTooLarge);
                buffer.Write(chunk, 0, count);
            }
            return buffer.ToArray();
        }
        catch (DesktopException) { throw; }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { throw new DesktopException(DesktopError.MemoryBackupFileUnavailable); }
    }
    public async Task WriteAsync(MemoryExportDestination file, ReadOnlyMemory<byte> bytes, CancellationToken token)
    {
        string? staging = null;
        try
        {
            staging = System.IO.Path.Combine(System.IO.Path.GetDirectoryName(file.Path)!, ".memory-export-" + Guid.NewGuid().ToString("N"));
            using (var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None, 8192, true))
            { await output.WriteAsync(bytes, token); await output.FlushAsync(token); output.Flush(true); }
            token.ThrowIfCancellationRequested();
            File.Move(staging, file.Path, file.OverwriteApproved); staging = null;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { throw new DesktopException(DesktopError.MemoryBackupFileUnavailable); }
        finally { if (staging is not null) { try { File.Delete(staging); } catch (Exception) { } } }
    }
}

public partial class MemoryBackupWindow : Window
{
    private readonly RuntimeClient runtime;
    private readonly IMemoryBackupFiles files;
    private readonly CancellationTokenSource lifetime = new();
    private bool busy, closed;
    internal MemoryBackupWindow(RuntimeClient runtime, IMemoryBackupFiles? files = null)
    {
        this.runtime = runtime; this.files = files ?? new NativeMemoryBackupFiles(); InitializeComponent();
        Closed += (_, _) => { closed = true; lifetime.Cancel(); StatusText.Text = ""; lifetime.Dispose(); };
    }
    internal Task ExportAsync() => RunAsync(async () =>
    {
        var destination = files.PickExport(this); if (destination is null || closed) return;
        var backup = await runtime.ExportMemoryAsync(lifetime.Token); if (closed) return;
        await files.WriteAsync(destination, backup.Bytes, lifetime.Token);
        if (!closed) StatusText.Text = $"Export complete · format {backup.Metadata.FormatVersion} · schema {backup.Metadata.SchemaVersion} · {backup.Metadata.ItemCount} items.";
    });
    internal Task RestoreAsync() => RunAsync(async () =>
    {
        var file = files.PickBackup(this); if (file is null || closed) return;
        var target = files.PickTarget(this); if (target is null || closed) return;
        var bytes = await files.ReadAsync(file, lifetime.Token); if (closed) return;
        var metadata = await runtime.RestoreMemoryBackupAsync(bytes, target, lifetime.Token);
        if (!closed) StatusText.Text = $"Restore complete · format {metadata.FormatVersion} · schema {metadata.SchemaVersion} · {metadata.ItemCount} items.\nStart Runtime with the restored data directory to use it.";
    });
    private async Task RunAsync(Func<Task> operation)
    {
        if (busy || closed) return;
        busy = true; ExportButton.IsEnabled = RestoreButton.IsEnabled = false;
        StatusText.Text = "Processing Memory backup…";
        try { await operation(); }
        catch (DesktopException error) { if (!closed) StatusText.Text = ErrorText.For(error.Error); }
        catch (OperationCanceledException) { }
        catch (Exception) { if (!closed) StatusText.Text = ErrorText.For(DesktopError.MemoryBackupFileUnavailable); }
        finally { if (!closed) { busy = false; ExportButton.IsEnabled = RestoreButton.IsEnabled = true; if (StatusText.Text == "Processing Memory backup…") StatusText.Text = "Cancelled · no operation completed."; } }
    }
    private async void Export(object sender, RoutedEventArgs e) => await ExportAsync();
    private async void Restore(object sender, RoutedEventArgs e) => await RestoreAsync();
    private void CloseBackup(object sender, RoutedEventArgs e) => Close();
}
