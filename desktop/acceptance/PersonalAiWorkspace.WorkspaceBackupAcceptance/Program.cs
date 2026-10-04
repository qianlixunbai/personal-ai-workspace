using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

namespace PersonalAiWorkspace.WorkspaceBackupAcceptance;
internal static class Program
{
    static readonly Application application=new(){ShutdownMode=ShutdownMode.OnExplicitShutdown};
    static readonly List<string> checks=[];static string stage="configuration";static AssistantWindow? assistant;
    static string Setting(string name)=>Environment.GetEnvironmentVariable("M4C_"+name)??throw new InvalidOperationException();
    static void Require(bool condition,string check){stage=check;if(!condition)throw new InvalidOperationException();checks.Add(check);}
    static async Task Wait(Func<bool> condition,string check,int seconds=180){stage=check;var until=DateTime.UtcNow.AddSeconds(seconds);while(!condition()){if(DateTime.UtcNow>until)throw new TimeoutException();await Task.Delay(50);}}
    static void Click(Button b)=>b.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
    sealed class Controller:IAssistantController{
        public bool Busy=>false;public bool Exiting=>false;public Task SubmitAsync()=>Task.CompletedTask;public void CancelOperation(){}public Task CheckHealthAsync()=>Task.CompletedTask;
        public Task ImportCredentialAsync(string file)=>Task.CompletedTask;public void ForgetCredential(){}
    }
    sealed class Files:IWorkspaceBackupFiles{
        readonly NativeWorkspaceBackupFiles native=new();
        public MemoryExportDestination? PickExport(Window w)=>new(Setting("BACKUP_FILE"),false);
        public string? PickBackup(Window w)=>Setting("BACKUP_FILE");public string? PickTarget(Window w)=>Setting("TARGET_DIRECTORY");
        public Stream OpenRead(string path)=>native.OpenRead(path);
        public Task<WorkspaceBackupMetadata> ExportAsync(RuntimeClient runtime,MemoryExportDestination destination,CancellationToken token)=>native.ExportAsync(runtime,destination,token);
    }
    [STAThread] static int Main(string[] args){
        _=application;int code=1;var dispatcher=Dispatcher.CurrentDispatcher;SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        dispatcher.BeginInvoke(new Action(async()=>{
            try{
                using var runtime=new RuntimeClient(()=>File.ReadAllText(Setting("TOKEN_FILE")).Trim());assistant=new AssistantWindow(new Controller(),runtime,new Files());assistant.Show();
                if(args[0]=="recover")await Recover(runtime);else await Backup(args[0]);
                Console.WriteLine(JsonSerializer.Serialize(new{result="PASS",realWpf=true,realHttp=true,realOllama=args[0]=="recover",checks}));code=0;
            }catch(Exception){Console.WriteLine(JsonSerializer.Serialize(new{result="FAIL",check=stage}));}
            finally{foreach(var w in application.Windows.Cast<Window>().ToArray())w.Close();dispatcher.InvokeShutdown();}
        }));Dispatcher.Run();return code;
    }
    static async Task Backup(string phase){
        var done=new TaskCompletionSource();WorkspaceBackupWindow? window=null;
        _=Dispatcher.CurrentDispatcher.BeginInvoke(new Action(async()=>{
            try{
                window=application.Windows.OfType<WorkspaceBackupWindow>().Single();await Wait(()=>window.IsLoaded,"workspace-backup-entry");
                Require(window.PlaintextWarning.Text.Contains("plaintext")&&window.PlaintextWarning.Text.Contains("No encryption"),"plaintext-warning");
                if(phase=="export"){
                    Click(window.ExportButton);await Wait(()=>window.ExportButton.IsEnabled,"streamed-export");Require(window.StatusText.Text.StartsWith("Export complete"),"wpf-export-complete");
                    Require(File.Exists(Setting("BACKUP_FILE")),"real-backup-file");
                }else{
                    Require(!window.RestoreButton.IsEnabled,"explicit-preview-gate");Click(window.ChooseButton);await Wait(()=>window.ChooseButton.IsEnabled,"full-file-validation");
                    Require(window.RestoreButton.IsEnabled&&window.PreviewText.Text.Contains("Turns 6"),"metadata-only-preview");
                    Click(window.RestoreButton);await Wait(()=>window.ChooseButton.IsEnabled,"transactional-restore");Require(window.StatusText.Text.StartsWith("Restore complete"),"wpf-restore-complete");
                    Require(window.StatusText.Text.Contains("Start Runtime/Desktop"),"explicit-start-guidance");
                }
                done.SetResult();
            }catch(Exception e){done.SetException(e);}finally{window?.Close();}
        }),DispatcherPriority.Background);
        Click(assistant!.WorkspaceBackupButton);await done.Task;Require(window!.PreviewText.Text==""&&window.StatusText.Text=="","backup-close-clears-ui");
    }
    static async Task Recover(RuntimeClient runtime){
        var memory=new MemoryWindow(runtime,_=>true){Owner=assistant};memory.Show();await Wait(()=>memory.NewButton.IsEnabled,"restored-memory-window");
        memory.SearchBox.Text="M4C-MEMORY-492";Click(memory.SearchButton);await Wait(()=>memory.SearchButton.IsEnabled,"restored-memory-search");
        Require(memory.MemoryList.Items.Count==1,"restored-fts-search");memory.Close();
        var done=new TaskCompletionSource();ConversationWindow? window=null;
        _=Dispatcher.CurrentDispatcher.BeginInvoke(new Action(async()=>{
            try{
                window=application.Windows.OfType<ConversationWindow>().Single();await Wait(()=>!window.Busy&&window.Detail is not null,"restored-conversation-open");
                Require(window.Detail!.Conversation.Id==Guid.Parse(Setting("CONVERSATION_ID"))&&window.Detail.TotalTurns==5,"restored-terminal-history");
                Require(window.Detail.Turns.All(t=>t.TaskId is null),"no-restored-task-association");
                Require(window.Detail.Turns.Count(t=>t.Status==ConversationTurnStatus.SUCCEEDED)==2&&window.History.Text.Contains("[FAILED"),"restored-outcome-labels");
                window.Input.Text="What synthetic code word did I give you at the start? Reply with only that code word.";Click(window.SendButton);
                await Wait(()=>!window.Busy&&window.Detail.TotalTurns==6,"restored-history-real-ollama");
                Require(window.Detail.Turns.Last().AssistantMessage?.Content.Contains("M4C-HISTORY-731")==true,"restored-context-answer");
                var picked=new TaskCompletionSource();
                _=Dispatcher.CurrentDispatcher.BeginInvoke(new Action(async()=>{
                    MemorySelectionWindow? picker=null;
                    try{
                        picker=application.Windows.OfType<MemorySelectionWindow>().Single();await Wait(()=>picker.MemoryList.Items.Count==1,"restored-explicit-memory-selector");picker.MemoryList.SelectedIndex=0;
                        await Wait(()=>picker.AddButton.IsEnabled,"restored-memory-full-preview");Click(picker.AddButton);Click(picker.UseButton);picked.SetResult();
                    }catch(Exception e){picker?.Close();picked.SetException(e);}
                }),DispatcherPriority.Background);
                Click(window.MemoryButton);await picked.Task;window.Input.Text="What synthetic marker is in the explicit Memory? Reply with only that marker.";Click(window.SendButton);
                await Wait(()=>!window.Busy&&window.Detail.TotalTurns==7,"restored-memory-real-ollama");
                Require(window.Detail.Turns.Last().AssistantMessage?.Content.Contains("M4C-MEMORY-492")==true,"restored-explicit-memory-answer");
                Require(window.Detail.Turns.Last().Memories!.Count==1&&window.MemoryState.Text=="Memory: 0 selected","per-turn-selection-cleared");
                done.SetResult();
            }catch(Exception e){done.SetException(e);}finally{window?.Close();}
        }),DispatcherPriority.Background);
        Click(assistant!.ConversationButton);await done.Task;
    }
}
