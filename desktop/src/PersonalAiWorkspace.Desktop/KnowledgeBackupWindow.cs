using System;
using System.IO;
using System.Linq;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

internal sealed class KnowledgeBackupWindow:Window
{
    private readonly RuntimeClient runtime;private readonly CancellationTokenSource lifetime=new();private Stream? selected;private KnowledgeBackupMetadata? validated;
    private readonly TextBlock status=new(){Text="选择导出，或先验证备份再明确恢复。",TextWrapping=TextWrapping.Wrap};
    private readonly Button export=new(){Content="导出 Knowledge…"},choose=new(){Content="选择并验证备份…"},restore=new(){Content="恢复到新 / 空目录…",IsEnabled=false};
    private bool closed,busy;
    internal KnowledgeBackupWindow(RuntimeClient runtime)
    {
        this.runtime=runtime;Title="Knowledge Backup v1";Width=660;Height=360;MinWidth=500;MinHeight=320;
        var panel=new StackPanel{Margin=new Thickness(24)};Content=panel;
        panel.Children.Add(new TextBlock{Text="Knowledge Backup v1",FontSize=22,Margin=new Thickness(0,0,0,16)});
        panel.Children.Add(new TextBlock{Text="独立备份 Knowledge 文档、保留的源版本、私有原始字节及 normalized text / locators。Workspace Backup v1 只包含 Memory + Conversation。",TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,0,0,12)});
        panel.Children.Add(new TextBlock{Text="备份为明文个人数据，请妥善保管。仅恢复到新 / 空 Workspace 数据目录；恢复后须显式以该目录重启 Runtime/Desktop。",TextWrapping=TextWrapping.Wrap});
        var buttons=new WrapPanel{Margin=new Thickness(0,16,0,16)};foreach(var button in new[]{export,choose,restore}){button.Margin=new Thickness(0,0,8,8);button.Padding=new Thickness(10,6,10,6);buttons.Children.Add(button);}panel.Children.Add(buttons);panel.Children.Add(status);
        export.Click+=async(_,_)=>await Run(Export);choose.Click+=async(_,_)=>await Run(Choose);restore.Click+=async(_,_)=>await Run(Restore);
        Closed+=(_,_)=>{closed=true;lifetime.Cancel();selected?.Dispose();selected=null;validated=null;status.Text="";lifetime.Dispose();};
    }
    private async Task Run(Func<Task> action){if(busy||closed)return;busy=true;export.IsEnabled=choose.IsEnabled=restore.IsEnabled=false;status.Text="正在处理 Knowledge 备份…";
        try{await action();}catch(DesktopException e){if(!closed)status.Text=ErrorText.For(e.Error);}catch(OperationCanceledException){}catch(Exception){if(!closed)status.Text=ErrorText.For(DesktopError.KnowledgeFileUnavailable);}
        finally{if(!closed){busy=false;export.IsEnabled=choose.IsEnabled=true;restore.IsEnabled=validated is not null;if(status.Text=="正在处理 Knowledge 备份…")status.Text="已取消。";}}}
    private async Task Export(){var dialog=new SaveFileDialog{Title="导出 Knowledge · 明文个人数据",Filter="Knowledge backup (*.knowledge-backup)|*.knowledge-backup",FileName="knowledge.knowledge-backup",DefaultExt=".knowledge-backup",AddExtension=true,OverwritePrompt=true};
        if(dialog.ShowDialog(this)!=true||closed)return;bool overwrite=File.Exists(dialog.FileName);string? staging=null;
        try{string parent=Path.GetDirectoryName(dialog.FileName)!;NoLinks(parent);staging=Path.Combine(parent,".knowledge-export-"+Guid.NewGuid().ToString("N"));KnowledgeBackupMetadata metadata;
            using(var file=new FileStream(staging,FileMode.CreateNew,FileAccess.ReadWrite,FileShare.None,65536,true)){
                ProtectFile(staging);await runtime.DownloadKnowledgeBackupAsync(file,lifetime.Token);await file.FlushAsync(lifetime.Token);file.Flush(true);
                metadata=await runtime.ValidateKnowledgeBackupAsync(file,lifetime.Token);
            }
            lifetime.Token.ThrowIfCancellationRequested();if(File.Exists(dialog.FileName))NoLinks(dialog.FileName);File.Move(staging,dialog.FileName,overwrite);staging=null;
            if(!closed)status.Text="导出完成 · "+Summary(metadata);
        }finally{if(staging is not null){try{NoLinks(staging);File.Delete(staging);}catch(Exception){}}}}
    private async Task Choose(){selected?.Dispose();selected=null;validated=null;var dialog=new OpenFileDialog{Title="验证 Knowledge Backup",Filter="Knowledge backup (*.knowledge-backup)|*.knowledge-backup",CheckFileExists=true};
        if(dialog.ShowDialog(this)!=true||closed)return;selected=NativeKnowledgeSourceFiles.OpenBackup(dialog.FileName);var result=await runtime.ValidateKnowledgeBackupAsync(selected,lifetime.Token);
        if(!closed){validated=result;status.Text="验证通过 · "+Summary(result)+"\n选择恢复按钮后才会发布到新目标。";}}
    private async Task Restore(){if(selected is null||validated is null)return;var dialog=new OpenFolderDialog{Title="选择新的 / 空的 Workspace 数据目录",Multiselect=false};if(dialog.ShowDialog(this)!=true||closed)return;
        var result=await runtime.RestoreKnowledgeBackupAsync(selected,dialog.FolderName,lifetime.Token);if(result!=validated)throw new DesktopException(DesktopError.KnowledgeRestoreFailed);
        if(!closed)status.Text="恢复完成 · "+Summary(result)+"\n请显式以恢复目录重启 Runtime/Desktop；当前 Runtime 未切换。";}
    private static string Summary(KnowledgeBackupMetadata m)=>$"format {m.FormatVersion} · 文档 {m.DocumentCount} · 源版本 {m.RevisionCount}";
    private static void NoLinks(string path){if(!Path.IsPathFullyQualified(path)||path.StartsWith(@"\\",StringComparison.Ordinal))throw new IOException();
        for(string? p=path;p is not null;p=Path.GetDirectoryName(p))if((File.GetAttributes(p)&FileAttributes.ReparsePoint)!=0)throw new IOException();}
    internal static void ProtectFile(string path){var user=WindowsIdentity.GetCurrent().User!;var security=new FileSecurity();security.SetOwner(user);security.SetAccessRuleProtection(true,false);
        security.AddAccessRule(new FileSystemAccessRule(user,FileSystemRights.FullControl,AccessControlType.Allow));var file=new FileInfo(path);file.SetAccessControl(security);
        var actual=file.GetAccessControl();if(!user.Equals(actual.GetOwner(typeof(SecurityIdentifier)))||actual.GetAccessRules(true,true,typeof(SecurityIdentifier)).Cast<FileSystemAccessRule>().Any(r=>r.AccessControlType==AccessControlType.Allow&&!user.Equals(r.IdentityReference)))throw new IOException();}
}
