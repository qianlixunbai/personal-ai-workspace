using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

namespace PersonalAiWorkspace.ConversationAcceptance;
internal static class Program
{
    static string stage="configuration";static readonly List<string> checks=[];
    static ConversationWindow? window;static AssistantWindow? assistant;
    static Guid conversationId;
    sealed class Controller : IAssistantController {
        public bool Busy=>false;public bool Exiting=>false;
        public Task SubmitAsync()=>Task.CompletedTask;public void CancelOperation(){}public Task CheckHealthAsync()=>Task.CompletedTask;
        public Task ImportCredentialAsync(string file)=>Task.CompletedTask;public void ForgetCredential(){}
    }
    [STAThread] static int Main(string[] args) {
        var phase=args.Length>0?args[0]:"initial";
        _ = application; int code=1;var dispatcher=Dispatcher.CurrentDispatcher;
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        dispatcher.BeginInvoke(new Action(async()=>{
            try {
                using var runtime=new RuntimeClient(()=>File.ReadAllText(Environment.GetEnvironmentVariable("M4B_TEST_TOKEN_FILE")!).Trim());
                stage="assistant-construction";assistant=new AssistantWindow(new Controller(),runtime);assistant.Show();
                var finished=new TaskCompletionSource();
                _ = dispatcher.BeginInvoke(new Action(async()=>{
                    try {
                        stage="conversation-entry";
                        // The production Assistant button opens the real modal window.
                        window=Application.Current?.Windows.OfType<ConversationWindow>().FirstOrDefault()
                            ?? dispatcherWindows().OfType<ConversationWindow>().First();
                        await Wait(()=>!window.Busy && window.IsLoaded,"conversation-window-loaded");
                        await Drive(runtime,phase);finished.SetResult();
                    } catch(Exception ex) {finished.SetException(ex);} finally {window?.Close();}
                }),DispatcherPriority.Background);
                stage="open-conversation-button";assistant.ConversationButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));await finished.Task;
                Require(window!.Input.Text.Length==0&&window.History.Text.Length==0,"close-clears-sensitive-ui");
                Console.WriteLine(JsonSerializer.Serialize(new{result="PASS",realWpf=true,realHttp=true,realOllama=phase is "initial" or "reopen",conversationId,checks}));code=0;
            } catch(Exception) {Console.WriteLine(JsonSerializer.Serialize(new{result="FAIL",check=stage}));}
            finally {window?.Close();assistant?.Close();dispatcher.InvokeShutdown();}
        }));Dispatcher.Run();return code;
    }
    // WPF Window collection belongs to an Application, created for the acceptance process.
    static IEnumerable<Window> dispatcherWindows() => application.Windows.Cast<Window>();
    static readonly Application application=new(){ShutdownMode=ShutdownMode.OnExplicitShutdown};
    static void Require(bool value,string check) {stage=check;if(!value)throw new InvalidOperationException();checks.Add(check);}
    static async Task Wait(Func<bool> condition,string check,int seconds=165) {
        stage=check;var until=DateTime.UtcNow.AddSeconds(seconds);
        while(!condition()){if(DateTime.UtcNow>until)throw new TimeoutException();await Task.Delay(50);}
    }
    static async Task Send(string text,string check) {
        int expected=window!.Detail!.TotalTurns+1;window.Input.Text=text;stage=check;
        window.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        await Wait(()=>!window.Busy&&window.Detail?.TotalTurns==expected,check);
        Require(window.Detail!.Turns.Last().Status==ConversationTurnStatus.SUCCEEDED,check);
    }
    static async Task Drive(RuntimeClient runtime,string phase) {
        if(phase=="initial") {
            window!.NewButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));await Wait(()=>!window.Busy&&window.Detail is not null,"create-conversation");conversationId=window.Detail!.Conversation.Id;
            await Send("Within this conversation the synthetic code word is ORBIT-731. Reply with exactly ORBIT-731.","first-turn-real-ollama");
            await Send("What synthetic code word did I give you? Reply with the code word only.","second-turn-real-ollama");
            Require(window.History.Text.Contains("ORBIT-731",StringComparison.Ordinal),"second-turn-recalls-history");
            Require(window.Detail!.Turns.Last().AssistantMessage!.Content.Contains("ORBIT-731",StringComparison.Ordinal),"second-assistant-context-answer");
            var memory=await runtime.CreateMemoryAsync(new(MemoryType.PROJECT_NOTE,"Synthetic reference","The synthetic memory code is QUARTZ-492."),default);
            var picked=new TaskCompletionSource();
            _ = Dispatcher.CurrentDispatcher.BeginInvoke(new Action(async()=>{
                MemorySelectionWindow? picker=null;
                try {
                    picker=application.Windows.OfType<MemorySelectionWindow>().First();
                    await Wait(()=>picker.MemoryList.Items.Count==1,"memory-selector-list");picker.MemoryList.SelectedIndex=0;
                    await Wait(()=>picker.AddButton.IsEnabled,"memory-complete-preview");
                    picker.AddButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));picker.UseButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));picked.SetResult();
                }catch(Exception ex){picker?.Close();picked.SetException(ex);}
            }),DispatcherPriority.Background);
            window.MemoryButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));await picked.Task;
            Require(window.MemoryState.Text.Contains("1 selected"),"explicit-memory-selection-ui");
            await Send("What is the synthetic memory code? Reply with the code only.","memory-turn-real-ollama");
            Require(window.Detail!.Turns.Last().AssistantMessage!.Content.Contains("QUARTZ-492",StringComparison.Ordinal),"explicit-memory-answer");
            Require(window.Detail.Turns.Last().Memories!.Count==1&&window.MemoryState.Text=="Memory: 0 selected","per-turn-selection-cleared");
            await Send("Reply with exactly READY.","no-memory-next-turn");Require(window.Detail!.Turns.Last().Memories!.Count==0,"no-auto-memory-injection");
            window.Input.Text="Write a long list of 200 synthetic numbered items.";window.SendButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            await Wait(()=>window.ActiveTask is not null,"cancel-active-turn");window.CancelButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            await Wait(()=>!window.Busy&&window.Detail!.TotalTurns==5,"cancel-finalization");
            Require(window.Detail!.Turns.Last().Status==ConversationTurnStatus.CANCELLED&&window.Detail.Turns.Last().AssistantMessage is null,"cancel-no-fake-assistant");
            window.ArchiveButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));await Wait(()=>!window.Busy&&window.Detail!.Conversation.Status==ConversationStatus.ARCHIVED,"archive");
            Require(!window.SendButton.IsEnabled,"archived-send-disabled");await runtime.UnarchiveConversationAsync(conversationId,default);
            await runtime.DeleteMemoryAsync(memory.Id,memory.Revision,default);
        } else if(phase=="reopen") {
            conversationId=Guid.Parse(Environment.GetEnvironmentVariable("M4B_TEST_CONVERSATION_ID")!);
            Require(window!.Detail?.Conversation.Id==conversationId&&window.Detail.TotalTurns==5,"reopen-durable-conversation-after-process-restart");
            Require(window.Detail!.Turns.Select(x=>x.Sequence).SequenceEqual(new long[]{1,2,3,4,5}),"durable-message-order");
            await Send("What synthetic code word did I give you at the start? Reply with the code word only.","continue-after-restart");
            Require(window.Detail!.Turns.Last().AssistantMessage!.Content.Contains("ORBIT-731",StringComparison.Ordinal),"restart-context-answer");
        } else {
            conversationId=Guid.Parse(Environment.GetEnvironmentVariable("M4B_TEST_CONVERSATION_ID")!);
            Require(window!.Detail?.Conversation.Id==conversationId,"reopen-failed-conversation");
            Require(window.Detail!.Turns.All(x=>x.Status==ConversationTurnStatus.FAILED&&x.AssistantMessage is null),"failed-turns-are-execution-state-not-assistant");
            Require(window.History.Text.Contains("[FAILED",StringComparison.Ordinal),"failure-execution-state-ui");
        }
    }
}
