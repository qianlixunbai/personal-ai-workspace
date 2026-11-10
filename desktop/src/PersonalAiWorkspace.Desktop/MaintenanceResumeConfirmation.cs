#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;

namespace PersonalAiWorkspace.Desktop;

// Per-launch native consent, bound to the currently held source/configuration/target.
// No approval boolean is accepted from a caller, browser, old journal or saved PID.
internal sealed class MaintenanceResumeConfirmation
{
    internal string Operation { get; }
    internal string Digest { get; }
    private readonly string summary;
    private int requested;
    private MaintenanceResumeConfirmation(string operation,string digest,string summary)
    { Operation=operation;Digest=digest;this.summary=summary; }

    internal static MaintenanceResumeConfirmation Read(string json,string digest,string profile,string operation)
    {
        void Hex(string value,int count){if(!Regex.IsMatch(value,"\\A[a-f0-9]{"+count+"}\\z"))throw new IOException();}
        Hex(digest,64);Hex(operation,32);Hex(profile,32);
        if(json.Length>12000||Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(json)))!=digest)throw new IOException();
        using var document=JsonDocument.Parse(json,new JsonDocumentOptions{MaxDepth=6});
        var root=document.RootElement;Unique(root);
        string Text(JsonElement value,string name)=>value.GetProperty(name).GetString()??throw new IOException();
        if(root.GetProperty("version").GetInt32()!=1||Text(root,"kind")!="LIVE_WORKSPACE_RESUME"
            ||Text(root,"profile")!=profile||Text(root,"operation")!=operation
            ||Text(root,"scope")!="MODEL_DISABLED_WORKSPACE_SESSION"||root.GetProperty("modelExecutionAllowed").GetBoolean())throw new IOException();
        foreach(string field in new[]{"previousExit","nonce","configuration","modelState"})Hex(Text(root,field),64);
        string predecessor=Text(root,"predecessor");
        if(predecessor!="initial"&&!Regex.IsMatch(predecessor,"\\A[0-9]{8}\\z"))throw new IOException();
        var binding=root.GetProperty("binding");
        if(Text(binding,"operation")!=operation||!Regex.IsMatch(Text(binding,"systemIdentifier"),"\\A[1-9][0-9]{0,19}\\z"))throw new IOException();
        foreach(string field in new[]{"databaseOid","roleOid","ownerOid","schemaOid","receiptOid"})
            if(binding.GetProperty(field).GetInt64()<=0)throw new IOException();
        string database=Text(root,"database"),role=Text(root,"role");int port=root.GetProperty("port").GetInt32();
        foreach(string value in new[]{database,role})
            if(string.IsNullOrWhiteSpace(value)||Encoding.UTF8.GetByteCount(value)>63||value.Any(char.IsControl))throw new IOException();
        if(Text(root,"address")!="127.0.0.1"||port is <1 or >65535)throw new IOException();
        return new(operation,digest,
            "工作区："+profile+"\n当前数据库：127.0.0.1:"+port+" / "+database+"\n应用角色："+role+
            "\n\n打开现有 PostgreSQL 数据，保留迁移后的新增内容；不会重新导入或恢复旧备份。"+
            "\n本次可查看存储状态、读取和新增记忆。对话、知识操作与 Finance 尚未开放；模型执行保持关闭。"+
            "\n来源、备份、配置和目标已重新核对。异常中断后将停止自动重开，保留现场。"+
            "\n\n取消不会启动工作区，也不会消耗上次正常关闭凭证。"+
            "\n本次确认摘要："+digest);
    }
    private static void Unique(JsonElement value)
    {
        if(value.ValueKind==JsonValueKind.Object){
            if(value.EnumerateObject().GroupBy(p=>p.Name,StringComparer.Ordinal).Any(g=>g.Count()!=1))throw new IOException();
            foreach(var property in value.EnumerateObject())Unique(property.Value);
        }else if(value.ValueKind==JsonValueKind.Array)foreach(var item in value.EnumerateArray())Unique(item);
    }
    internal Task<bool> RequestAsync(CancellationToken cancellation)
    {
        if(Interlocked.Exchange(ref requested,1)!=0)throw new IOException();
        var result=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread=new Thread(()=>{
            try{
                cancellation.ThrowIfCancellationRequested();
                var window=new Window{Title="Personal AI Workspace · 打开已迁移工作区",Width=680,SizeToContent=SizeToContent.Height,
                    MaxHeight=Math.Max(420,SystemParameters.WorkArea.Height-60),WindowStartupLocation=WindowStartupLocation.CenterScreen,
                    ResizeMode=ResizeMode.NoResize};
                var body=new StackPanel{Margin=new Thickness(24)};
                body.Children.Add(new TextBlock{Text=summary,TextWrapping=TextWrapping.Wrap});
                var check=new CheckBox{Name="AcknowledgeResume",Content="我已核对当前工作区与数据库，同意在模型关闭状态下打开",Margin=new Thickness(0,16,0,12)};
                var confirm=new Button{Name="ConfirmResume",Content="打开工作区",IsEnabled=false,Padding=new Thickness(12,8,12,8)};
                var cancel=new Button{Name="CancelResume",Content="取消",IsDefault=true,IsCancel=true,Padding=new Thickness(12,8,12,8),Margin=new Thickness(0,8,0,0)};
                check.Checked+=(_,_)=>confirm.IsEnabled=!cancellation.IsCancellationRequested;
                check.Unchecked+=(_,_)=>confirm.IsEnabled=false;
                confirm.Click+=(_,_)=>{if(check.IsChecked==true&&!cancellation.IsCancellationRequested)window.DialogResult=true;};
                cancel.Click+=(_,_)=>window.DialogResult=false;
                body.Children.Add(check);body.Children.Add(confirm);body.Children.Add(cancel);
                window.Content=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
                using var registration=cancellation.Register(()=>{try{window.Dispatcher.BeginInvoke(()=>window.Close());}catch(Exception){}});
                result.TrySetResult(window.ShowDialog()==true&&!cancellation.IsCancellationRequested);
            }catch(OperationCanceledException){result.TrySetResult(false);}catch(Exception failure){result.TrySetException(failure);}
            finally{System.Windows.Threading.Dispatcher.CurrentDispatcher.InvokeShutdown();}
        }){IsBackground=true};
        thread.SetApartmentState(ApartmentState.STA);thread.Start();return result.Task;
    }
}
#endif
