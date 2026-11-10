#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Globalization;
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

// A native dialog for the live source/target review, never a caller-supplied approval flag.
// The generic personal migration entry remains blocked until its independent prerequisites exist.
internal sealed class MaintenanceMigrationConfirmation
{
    internal string Operation { get; }
    internal string Digest { get; }
    private readonly string summary, details;
    private int requested;
    private MaintenanceMigrationConfirmation(string operation,string digest,string summary,string details)
    { Operation=operation;Digest=digest;this.summary=summary;this.details=details; }

    internal static MaintenanceMigrationConfirmation Read(string json,string digest,string profile,byte[] configuration)
    {
        if(json.Length>12000||!Regex.IsMatch(digest,"\\A[a-f0-9]{64}\\z")
            ||Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(json)))!=digest)throw new IOException();
        using var document=JsonDocument.Parse(json,new JsonDocumentOptions{MaxDepth=8});
        var root=document.RootElement;Unique(root);
        string Text(JsonElement value,string name)=>value.GetProperty(name).GetString()??throw new IOException();
        void Hex(string value,int size){if(!Regex.IsMatch(value,"\\A[a-f0-9]{"+size+"}\\z"))throw new IOException();}
        if(root.GetProperty("version").GetInt32()!=1||Text(root,"kind")!="LIVE_MIGRATION_REVIEW"||Text(root,"profile")!=profile
            ||Text(root,"scope")!="WORKSPACE_KNOWLEDGE_WITH_BACKUP_MODEL_DISABLED"||root.GetProperty("modelExecutionAllowed").GetBoolean())throw new IOException();
        string operation=Text(root,"operation");Hex(operation,32);Hex(Text(root,"nonce"),64);
        Hex(Text(root,"sourceIdentity"),48);Hex(Text(root,"sourceDigest"),64);Hex(Text(root,"modelState"),64);
        var expected=root.GetProperty("expected");var target=expected.GetProperty("target");var binding=expected.GetProperty("binding");
        string database=Text(root,"database"),role=Text(root,"role");
        foreach(string name in new[]{database,role})if(string.IsNullOrWhiteSpace(name)||Encoding.UTF8.GetByteCount(name)>63||name.Any(char.IsControl))throw new IOException();
        int port=target.GetProperty("port").GetInt32();
        if(port is <1 or >65535||Text(target,"address")!="127.0.0.1"||Text(binding,"operation")!=operation
            ||!Regex.IsMatch(Text(binding,"systemIdentifier"),"\\A[1-9][0-9]{0,19}\\z"))throw new IOException();
        foreach(string name in new[]{"databaseOid","roleOid","ownerOid","schemaOid","receiptOid"})if(binding.GetProperty(name).GetInt64()<=0)throw new IOException();
        if(binding.GetProperty("databaseOid").GetInt64()!=target.GetProperty("databaseOid").GetInt64()
            ||binding.GetProperty("roleOid").GetInt64()!=target.GetProperty("roleOid").GetInt64())throw new IOException();
        using(var chosen=JsonDocument.Parse(configuration)){
            var value=chosen.RootElement;
            if(Text(value,"database")!=database||Text(value,"username")!=role||Text(value,"host")!="127.0.0.1"
                ||value.GetProperty("port").GetInt32()!=port)throw new IOException();
        }
        string Counts(string name){var section=expected.GetProperty(name);Hex(Text(section,"contentDigest"),64);
            return string.Join("，",section.GetProperty("rows").EnumerateObject().Select(p=>{
                long count=p.Value.GetInt64();if(count<0||!Regex.IsMatch(p.Name,"\\A[a-z_]{1,40}\\z"))throw new IOException();
                return p.Name+"："+count.ToString(CultureInfo.InvariantCulture);}));}
        string summary="来源工作区："+profile+"\n目标：127.0.0.1:"+port+" / "+database+"\n应用角色："+role+
            "\n\n迁移记忆、对话和知识文档。先创建并核对备份，保留原 SQLite 与知识原文；Finance 不包含在内。"+
            "\n目标业务表已核对为空，并在本次确认期间持有写入排他事务。若来源、目标或当前会话改变，确认作废。"+
            "\n模型保持关闭，不接管旧模型任务。迁移后只接入受限维护会话；日常启动仍未开放。"+
            "\n\n确认后开始备份和导入；取消不创建迁移登记、不导入数据、不发布配置。";
        string details="本次核对摘要："+digest+"\n来源文件摘要："+Text(root,"sourceDigest")+
            "\n目标集群："+Text(binding,"systemIdentifier")+"；数据库 OID："+binding.GetProperty("databaseOid").GetInt64()+
            "；角色 OID："+binding.GetProperty("roleOid").GetInt64()+"\nWorkspace："+Counts("workspace")+"\nKnowledge："+Counts("knowledge");
        return new(operation,digest,summary,details);
    }
    private static void Unique(JsonElement value)
    {
        if(value.ValueKind==JsonValueKind.Object){
            if(value.EnumerateObject().GroupBy(p=>p.Name,StringComparer.Ordinal).Any(g=>g.Count()!=1))throw new IOException();
            foreach(var p in value.EnumerateObject())Unique(p.Value);
        }else if(value.ValueKind==JsonValueKind.Array)foreach(var item in value.EnumerateArray())Unique(item);
    }
    internal Task<bool> RequestAsync(CancellationToken cancellation)
    {
        if(Interlocked.Exchange(ref requested,1)!=0)throw new IOException("Migration review already presented.");
        var result=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread=new Thread(()=>{
            try{
                cancellation.ThrowIfCancellationRequested();
                var window=new Window{Title="Personal AI Workspace · 本次迁移最终确认",Width=700,SizeToContent=SizeToContent.Height,
                    MaxHeight=Math.Max(420,SystemParameters.WorkArea.Height-60),WindowStartupLocation=WindowStartupLocation.CenterScreen,
                    ResizeMode=ResizeMode.NoResize};
                var body=new StackPanel{Margin=new Thickness(24)};
                body.Children.Add(new TextBlock{Text=summary,TextWrapping=TextWrapping.Wrap});
                body.Children.Add(new Expander{Header="查看本次来源、目标与数据数量",Margin=new Thickness(0,12,0,12),
                    Content=new TextBlock{Text=details,TextWrapping=TextWrapping.Wrap}});
                var acknowledge=new CheckBox{Name="AcknowledgeMigration",Content="我已核对本次来源、目标和迁移范围",Margin=new Thickness(0,8,0,12)};
                var confirm=new Button{Name="ConfirmMigration",Content="确认本次迁移",IsEnabled=false,Padding=new Thickness(12,8,12,8)};
                var cancel=new Button{Name="CancelMigration",Content="取消，保留当前工作区",IsCancel=true,IsDefault=true,
                    Padding=new Thickness(12,8,12,8),Margin=new Thickness(0,8,0,0)};
                acknowledge.Checked+=(_,_)=>confirm.IsEnabled=!cancellation.IsCancellationRequested;
                acknowledge.Unchecked+=(_,_)=>confirm.IsEnabled=false;
                confirm.Click+=(_,_)=>{if(acknowledge.IsChecked==true&&!cancellation.IsCancellationRequested)window.DialogResult=true;};
                cancel.Click+=(_,_)=>window.DialogResult=false;
                body.Children.Add(acknowledge);body.Children.Add(confirm);body.Children.Add(cancel);
                window.Content=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
                using var registration=cancellation.Register(()=>{try{window.Dispatcher.BeginInvoke(()=>window.Close());}catch(Exception){}});
                result.TrySetResult(window.ShowDialog()==true&&!cancellation.IsCancellationRequested);
            }catch(OperationCanceledException){result.TrySetResult(false);}catch(Exception error){result.TrySetException(error);}
            finally{System.Windows.Threading.Dispatcher.CurrentDispatcher.InvokeShutdown();}
        }){IsBackground=true};
        thread.SetApartmentState(ApartmentState.STA);thread.Start();return result.Task;
    }
}
#endif
