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
    [Fact] public Task SwitchingAndCreatingNeverMoveUnsentTextToAnotherConversationOrDiscardItOnReadFailure()=>Sta(async()=>{
        var secondId=Guid.NewGuid(); bool allow=false, failRead=false, omitCurrent=false; int creates=0, confirmations=0;
        string Meta(Guid id)=>JsonSerializer.Serialize(new{id,title=id==Id?"Original":"Other",status="ACTIVE",createdAt="2026-10-04T00:00:00Z",updatedAt=id==Id?"2026-10-05T00:00:00Z":"2026-10-04T00:00:00Z"});
        using var runtime=new RuntimeClient(new Handler((request,_)=>{
            string path=request.RequestUri!.AbsolutePath;
            if(request.Method==HttpMethod.Post){creates++;var response=Response(Meta(secondId),HttpStatusCode.Created);response.Headers.Location=new Uri($"/api/v1/conversations/{secondId:D}",UriKind.Relative);return Task.FromResult(response);}
            if(path=="/api/v1/conversations")return Task.FromResult(Response("{\"items\":["+(omitCurrent?"":Meta(Id)+",")+Meta(secondId)+"],\"total\":"+(omitCurrent?1:2)+",\"page\":0,\"limit\":10}"));
            if(failRead)return Task.FromResult(Response(Error("CONVERSATION_NOT_FOUND"),HttpStatusCode.NotFound));
            var id=Guid.Parse(path.Split('/').Last());
            return Task.FromResult(Response("{\"conversation\":"+Meta(id)+",\"turns\":[],\"totalTurns\":0,\"page\":0,\"limit\":10}"));
        }),()=>Token);
        var window=new ConversationWindow(runtime,_=>{confirmations++;return allow;});
        try {
            window.Show();for(int i=0;i<5;i++)await Drain();Assert.True(window.Detail is not null,window.State.Text);Assert.Equal(Id,window.Detail.Conversation.Id);
            var other=window.Conversations.Items.Cast<Conversation>().Single(x=>x.Id==secondId);
            window.Input.Text="未发送的原会话草稿\n😀";
            window.ApplySelection([new MemorySelection(MemoryClientTests.Id,1,PrivateTitle,MemoryType.PROJECT_NOTE)]);
            window.Conversations.SelectedItem=other;for(int i=0;i<3;i++)await Drain();
            Assert.Equal(Id,((Conversation)window.Conversations.SelectedItem).Id);Assert.Contains("原会话草稿",window.Input.Text);Assert.Contains(PrivateTitle,window.MemoryState.Text);
            await window.NewAsync();Assert.Equal(0,creates);Assert.Equal(2,confirmations);
            allow=true;failRead=true;
            await window.SelectAsync(other);Assert.Equal(Id,window.Detail.Conversation.Id);Assert.Contains("原会话草稿",window.Input.Text);
            Assert.Equal(Id,((Conversation)window.Conversations.SelectedItem).Id);Assert.Contains(PrivateTitle,window.MemoryState.Text);
            await window.NewAsync();Assert.Equal(1,creates);Assert.Contains("新会话已创建，但未能打开",window.State.Text);Assert.Contains("原会话草稿",window.Input.Text);
            // Refresh must not fall back to another list item when the current record is missing.
            omitCurrent=true;window.RefreshButton.RaiseEvent(new System.Windows.RoutedEventArgs(Button.ClickEvent));for(int i=0;i<5;i++)await Drain();
            Assert.Equal(Id,window.Detail.Conversation.Id);Assert.Equal(Id,((Conversation)window.Conversations.SelectedItem).Id);Assert.Contains("原会话草稿",window.Input.Text);
            failRead=false;omitCurrent=false;
            window.RefreshButton.RaiseEvent(new System.Windows.RoutedEventArgs(Button.ClickEvent));for(int i=0;i<5;i++)await Drain();
            Assert.Contains("原会话草稿",window.Input.Text);Assert.Equal(Id,window.Detail.Conversation.Id);
            await window.SelectAsync(other);Assert.Equal(secondId,window.Detail.Conversation.Id);Assert.Empty(window.Input.Text);Assert.Equal("Memory: 0 selected",window.MemoryState.Text);
            window.Input.Text="同一会话草稿";int before=confirmations;
            await window.SelectAsync(other);Assert.Equal(before,confirmations);Assert.Equal("同一会话草稿",window.Input.Text);
            // A fresh ID after explicit consent clears the old draft only after its read succeeds.
            secondId=Guid.NewGuid();await window.NewAsync();Assert.Equal(2,creates);Assert.Equal(secondId,window.Detail.Conversation.Id);Assert.Empty(window.Input.Text);
        } finally { window.Close(); }
    });
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
