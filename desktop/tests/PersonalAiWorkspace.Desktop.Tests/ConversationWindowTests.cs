using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows.Controls;
using System.Windows.Threading;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;
using Xunit;
using static PersonalAiWorkspace.Desktop.Tests.MemoryClientTests;

namespace PersonalAiWorkspace.Desktop.Tests;
public sealed class ConversationWindowTests
{
    private static readonly Guid Id=Guid.NewGuid();
    private static string Metadata()=>JsonSerializer.Serialize(new{id=Id,title=PrivateTitle,status="ACTIVE",createdAt="2026-10-04T00:00:00Z",updatedAt="2026-10-04T00:00:00Z"});
    private static async Task Drain(){await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);}
    [Fact] public Task StaleSelectionBlocksDirectResendUntilExplicitClearAndCloseClearsContent()=>Sta(async()=>{
        int sends=0;
        using var runtime=new RuntimeClient(new Handler((request,_)=>{
            string path=request.RequestUri!.AbsolutePath;
            if(request.Method==HttpMethod.Post){sends++;return Task.FromResult(Response(Error("MEMORY_SELECTION_STALE"),HttpStatusCode.Conflict));}
            return Task.FromResult(Response(path=="/api/v1/conversations"?"{\"items\":["+Metadata()+"],\"total\":1,\"page\":0,\"limit\":10}":"{\"conversation\":"+Metadata()+",\"turns\":[],\"totalTurns\":0,\"page\":0,\"limit\":10}"));
        }),()=>Token);
        var window=new ConversationWindow(runtime);window.Show();for(int i=0;i<5;i++)await Drain();
        Assert.NotNull(window.Detail);window.ApplySelection([new MemorySelection(MemoryClientTests.Id,1,PrivateTitle,MemoryType.PROJECT_NOTE)]);
        window.Input.Text=PrivateContent;await window.SendAsync();Assert.True(window.NeedsReview);Assert.False(window.SendButton.IsEnabled);
        await window.SendAsync();Assert.Equal(1,sends);Assert.Equal(PrivateContent,window.Input.Text);
        window.ClearMemoryButton.RaiseEvent(new System.Windows.RoutedEventArgs(Button.ClickEvent));Assert.False(window.NeedsReview);Assert.True(window.SendButton.IsEnabled);
        window.Close();Assert.Empty(window.Input.Text);Assert.Empty(window.History.Text);Assert.Empty(window.MemoryState.Text);Assert.Null(window.Detail);
        Assert.False(window.Input.IsUndoEnabled);Assert.False(window.History.IsUndoEnabled);
    });
    [Fact] public Task ClosingDuringRefreshCancelsAndLateResponseCannotRepopulateHistory()=>Sta(async()=>{
        var pending=new TaskCompletionSource<HttpResponseMessage>();CancellationToken requestToken=default;
        using var runtime=new RuntimeClient(new Handler((_,ct)=>{requestToken=ct;return pending.Task;}),()=>Token);
        var window=new ConversationWindow(runtime);window.Show();for(int i=0;i<3;i++)await Drain();Assert.True(window.Busy);
        window.Close();Assert.True(requestToken.IsCancellationRequested);pending.SetResult(Response("{\"items\":[],\"total\":0,\"page\":0,\"limit\":10}"));
        for(int i=0;i<5;i++)await Drain();Assert.Null(window.Detail);Assert.Empty(window.History.Text);Assert.Empty(window.Input.Text);Assert.Null(window.ActiveTask);
    });
    private static Task Sta(Func<Task> action) {
        var done=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread=new Thread(()=>{
            var dispatcher=Dispatcher.CurrentDispatcher;SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async()=>{try{await action();done.SetResult();}catch(Exception ex){done.SetException(ex);}finally{dispatcher.InvokeShutdown();}}));Dispatcher.Run();
        });thread.SetApartmentState(ApartmentState.STA);thread.Start();return done.Task;
    }
}
