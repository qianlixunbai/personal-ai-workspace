using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal interface IWorkspaceBackupFiles
{
    MemoryExportDestination? PickExport(Window owner);
    string? PickBackup(Window owner);
    string? PickTarget(Window owner);
    Stream OpenRead(string file);
    Task<WorkspaceBackupMetadata> ExportAsync(RuntimeClient runtime, MemoryExportDestination destination, CancellationToken token);
}
internal sealed class NativeWorkspaceBackupFiles : IWorkspaceBackupFiles
{
    public MemoryExportDestination? PickExport(Window owner)
    {
        var dialog = new SaveFileDialog { Title="Export Workspace — plaintext personal data", Filter="Workspace backup (*.workspace-backup.json)|*.workspace-backup.json",
            FileName="workspace-backup.json",DefaultExt=".workspace-backup.json",AddExtension=true,OverwritePrompt=true };
        return dialog.ShowDialog(owner)==true ? new(dialog.FileName,File.Exists(dialog.FileName)) : null;
    }
    public string? PickBackup(Window owner)
    {
        var dialog=new OpenFileDialog { Title="Validate Workspace Backup",Filter="Workspace backup (*.json)|*.json",CheckFileExists=true };
        return dialog.ShowDialog(owner)==true ? dialog.FileName : null;
    }
    public string? PickTarget(Window owner)
    {
        var dialog=new OpenFolderDialog { Title="Choose a NEW / EMPTY Workspace data directory",Multiselect=false };
        return dialog.ShowDialog(owner)==true ? dialog.FolderName : null;
    }
    public Stream OpenRead(string file)
    {
        try
        {
            var stream=new FileStream(file,FileMode.Open,FileAccess.Read,FileShare.Read,65536,true);
            if(stream.Length>RuntimeClient.MaximumWorkspaceBackupBytes) { stream.Dispose(); throw new DesktopException(DesktopError.WorkspaceBackupTooLarge); }
            return stream;
        }
        catch(DesktopException) { throw; }
        catch(Exception) { throw new DesktopException(DesktopError.WorkspaceBackupFileUnavailable); }
    }
    public async Task<WorkspaceBackupMetadata> ExportAsync(RuntimeClient runtime,MemoryExportDestination destination,CancellationToken token)
    {
        string? staging=null;
        try
        {
            staging=Path.Combine(Path.GetDirectoryName(destination.Path)!,".workspace-export-"+Guid.NewGuid().ToString("N"));
            WorkspaceBackupMetadata metadata;
            using(var file=new FileStream(staging,FileMode.CreateNew,FileAccess.ReadWrite,FileShare.None,65536,true))
            {
                await runtime.DownloadWorkspaceBackupAsync(file,token);await file.FlushAsync(token);file.Flush(true);
                metadata=await runtime.ValidateWorkspaceBackupAsync(file,token);
            }
            token.ThrowIfCancellationRequested();File.Move(staging,destination.Path,destination.OverwriteApproved);staging=null;return metadata;
        }
        catch(DesktopException) { throw; }
        catch(OperationCanceledException) { throw; }
        catch(Exception) { throw new DesktopException(DesktopError.WorkspaceBackupFileUnavailable); }
        finally { if(staging is not null) { try { File.Delete(staging); } catch(Exception) { } } }
    }
}
public partial class WorkspaceBackupWindow : Window
{
    private readonly RuntimeClient runtime;
    private readonly IWorkspaceBackupFiles files;
    private readonly CancellationTokenSource lifetime=new();
    private Stream? selected;
    private WorkspaceBackupMetadata? metadata;
    private bool busy,closed;
    internal WorkspaceBackupWindow(RuntimeClient runtime,IWorkspaceBackupFiles? files=null)
    {
        this.runtime=runtime;this.files=files??new NativeWorkspaceBackupFiles();InitializeComponent();
        Closed+=(_,_)=>{closed=true;lifetime.Cancel();selected?.Dispose();selected=null;metadata=null;PreviewText.Text=StatusText.Text="";lifetime.Dispose();};
    }
    internal Task ExportAsync()=>RunAsync(async()=>
    {
        var destination=files.PickExport(this);if(destination is null || closed)return;
        var result=await files.ExportAsync(runtime,destination,lifetime.Token);
        if(!closed)StatusText.Text="Export complete · "+Summary(result);
    });
    internal Task ChooseAsync()=>RunAsync(async()=>
    {
        selected?.Dispose();selected=null;metadata=null;PreviewText.Text="No validated file selected.";
        var file=files.PickBackup(this);if(file is null || closed)return;
        selected=files.OpenRead(file);var result=await runtime.ValidateWorkspaceBackupAsync(selected,lifetime.Token);
        if(!closed){metadata=result;PreviewText.Text=Summary(result);StatusText.Text="Validated · choose Restore explicitly to publish a new Workspace.";}
    });
    internal Task RestoreAsync()=>RunAsync(async()=>
    {
        if(selected is null || metadata is null)return;
        var target=files.PickTarget(this);if(target is null || closed)return;
        var result=await runtime.RestoreWorkspaceBackupAsync(selected,target,lifetime.Token);
        if(!closed){
            if(result!=metadata)throw new DesktopException(DesktopError.WorkspaceRestoreFailed);
            StatusText.Text="Restore complete · "+Summary(result)+"\nStart Runtime/Desktop with the restored directory to use recovered data.";
        }
    });
    private static string Summary(WorkspaceBackupMetadata m)=>$"format {m.FormatVersion} · created {m.CreatedAt}\nMemory {m.MemoryCount} · Conversations {m.ConversationCount} · Turns {m.TurnCount} · Messages {m.MessageCount}";
    private async Task RunAsync(Func<Task> operation)
    {
        if(busy || closed)return;busy=true;ExportButton.IsEnabled=ChooseButton.IsEnabled=RestoreButton.IsEnabled=false;StatusText.Text="Processing Workspace backup…";
        try { await operation(); }
        catch(DesktopException e){if(!closed)StatusText.Text=ErrorText.For(e.Error);}
        catch(OperationCanceledException){}
        catch(Exception){if(!closed)StatusText.Text=ErrorText.For(DesktopError.WorkspaceBackupFileUnavailable);}
        finally{if(!closed){busy=false;ExportButton.IsEnabled=ChooseButton.IsEnabled=true;RestoreButton.IsEnabled=metadata is not null;
            if(StatusText.Text=="Processing Workspace backup…")StatusText.Text="Cancelled · no operation completed.";}}
    }
    private async void Export(object sender,RoutedEventArgs e)=>await ExportAsync();
    private async void Choose(object sender,RoutedEventArgs e)=>await ChooseAsync();
    private async void Restore(object sender,RoutedEventArgs e)=>await RestoreAsync();
    private void CloseBackup(object sender,RoutedEventArgs e)=>Close();
}
