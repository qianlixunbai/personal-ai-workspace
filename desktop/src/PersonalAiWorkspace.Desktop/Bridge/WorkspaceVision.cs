using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal sealed class WorkspaceVision(RuntimeClient runtime,IVisionImages images,Func<VisionValidationIntent,CancellationToken,Task<bool>> confirm)
{
    private sealed class Authority(string session)
    {
        internal readonly string Session=session;
        internal readonly CancellationTokenSource Lifetime=new();
        internal long Generation;
        internal CancellationTokenSource? PendingCancellation;
        internal PickedVisionImage? Image;internal string? Handle;internal Guid? TaskId;internal bool Consumed,Pending;
    }
    private readonly object sync=new();private Authority? current;
    private readonly HashSet<Guid> cancellations=new();
    internal event Action<string,string>? Cleared;
    internal void PreviewClosed() {string? session;lock(sync)session=current?.Session;if(session is not null)Clear(session);}
    internal void BeginSession(string session) {lock(sync) current=new(session);}
    internal void EndSession(string session) {
        Authority? old;lock(sync){old=current;if(old?.Session!=session)return;current=null;old.Lifetime.Cancel();old.Image=null;old.Handle=null;}
        images.ClearPreview();if(old.TaskId is {} id)_=CancelBestEffort(id);
    }
    private Authority Require(string session) {lock(sync)return current?.Session==session?current:throw new OperationCanceledException();}
    private void Check(Authority known) {if(!ReferenceEquals(current,known)||known.Lifetime.IsCancellationRequested)throw new OperationCanceledException();}
    internal async Task<object> ReadinessAsync(string session,CancellationToken token) {
        var known=Require(session);var result=await runtime.GetVisionReadinessAsync(token);lock(sync){Check(known);return result;}
    }
    internal async Task<object> PickAsync(string session,CancellationToken token) {
        var known=Require(session);lock(sync){if(known.Pending)throw new DesktopException(DesktopError.QueueFull);known.Pending=true;}
        try {
            Clear(session);long generation;using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,known.Lifetime.Token);
            lock(sync){Check(known);generation=known.Generation;known.PendingCancellation=linked;}
            var selected=await images.PickAsync(linked.Token);
            lock(sync){Check(known);if(known.Generation!=generation)throw new OperationCanceledException();if(selected is null)return new {changed=false,handle=(string?)null,mediaType=(string?)null,width=0,height=0};
                known.Image=selected;known.Handle=Guid.NewGuid().ToString("D");known.Consumed=false;
                images.ShowPreview(selected);return new {changed=true,handle=known.Handle,mediaType=selected.MediaType,width=selected.Width,height=selected.Height};}
        } finally {lock(sync){known.Pending=false;known.PendingCancellation=null;}}
    }
    internal object Clear(string session) {
        var known=Require(session);Guid? id;string? handle;lock(sync){Check(known);known.Generation++;try{known.PendingCancellation?.Cancel();}catch(ObjectDisposedException){}id=known.TaskId;handle=known.Handle;known.TaskId=null;known.Handle=null;known.Image=null;known.Consumed=true;}
        images.ClearPreview();if(id is {} task)_=CancelBestEffort(task);if(handle is not null)Cleared?.Invoke(session,handle);return new {cleared=true};
    }
    internal async Task<object> ValidateAsync(string session,CancellationToken token) {
        var known=Require(session);lock(sync){if(known.Pending)throw new DesktopException(DesktopError.QueueFull);known.Pending=true;}
        try {
            using var confirmation=CancellationTokenSource.CreateLinkedTokenSource(token,known.Lifetime.Token);confirmation.CancelAfter(TimeSpan.FromSeconds(60));
            long generation;lock(sync){Check(known);generation=known.Generation;known.PendingCancellation=confirmation;}
            var frozen=await runtime.PrepareVisionValidationAsync(Guid.Parse(session),confirmation.Token);long started=Stopwatch.GetTimestamp();
            if(!await confirm(frozen,confirmation.Token))return new {state="CANCELLED",readiness=(VisionReadiness?)null};
            Task<VisionReadiness> mutation;
            lock(sync){Check(known);if(known.Generation!=generation)throw new OperationCanceledException();confirmation.Token.ThrowIfCancellationRequested();if(Stopwatch.GetElapsedTime(started)>=TimeSpan.FromSeconds(60))throw new OperationCanceledException();
                // Freeze and consume exactly one native intent; no retry on an unknown validation outcome.
                mutation=runtime.ValidateVisionAsync(frozen,known.Lifetime.Token);}
            try {var result=await mutation;lock(sync){Check(known);return new {state="COMPLETED",readiness=result};}}
            catch(DesktopException e) when(e.Error is DesktopError.RuntimeUnavailable or DesktopError.ClientTimeout or DesktopError.InvalidResponse) {
                var result=await runtime.GetVisionReadinessAsync(known.Lifetime.Token);lock(sync){Check(known);return new {state="UNKNOWN",readiness=result};}
            }
        } finally {lock(sync){known.Pending=false;known.PendingCancellation=null;}}
    }
    internal async Task<object> SubmitAsync(string session,string handle,string question,CancellationToken token) {
        var known=Require(session);PickedVisionImage image;Guid id;
        lock(sync){Check(known);if(known.Pending||known.Handle!=handle||known.Consumed||known.Image is null)throw new DesktopException(DesktopError.InvalidRequest);
            known.Pending=true;image=known.Image;id=Guid.NewGuid();known.Consumed=true;}
        try {
            using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,known.Lifetime.Token);
            var ready=await runtime.GetVisionReadinessAsync(linked.Token);
            if(!ready.Available)throw new DesktopException(DesktopError.ModelUnavailable);
            Task<RuntimeTask> submission;lock(sync){Check(known);if(known.Handle!=handle)throw new OperationCanceledException();
                known.TaskId=id;submission=runtime.SubmitVisionAsync(id,question,image.MediaType,image.Bytes,ready.SelectionRevision,linked.Token);known.Image=null;}
            try {var task=await submission;lock(sync){Check(known);if(known.TaskId!=id)throw new OperationCanceledException();return View(task);}}
            catch(DesktopException e) when(e.Error==DesktopError.OutcomeUnknown) {
                // Read the frozen ID only; 404/restart/retention cannot prove that the POST did not execute.
                try {var task=await runtime.VisionTaskAsync(id,false,linked.Token);lock(sync){Check(known);if(known.TaskId!=id)throw new OperationCanceledException();return View(task);}}
                catch(DesktopException){lock(sync){Check(known);if(known.TaskId!=id)throw new OperationCanceledException();return new {taskId=id.ToString("D"),status="UNKNOWN",result=(string?)null,error=new WorkspaceSafeError("OutcomeUnknown",ErrorText.For(DesktopError.OutcomeUnknown))};}}
            }
        } finally {
            bool stale;lock(sync){known.Pending=false;stale=!ReferenceEquals(current,known)||known.TaskId!=id;}
            if(stale)_=CancelBestEffort(id);
        }
    }
    internal async Task<object> TaskAsync(string session,string? id,bool cancel,CancellationToken token) {
        var known=Require(session);Guid task;lock(sync){Check(known);task=id is null?known.TaskId??throw new DesktopException(DesktopError.TaskNotFound):Guid.ParseExact(id,"D");if(known.TaskId!=task)throw new DesktopException(DesktopError.TaskNotFound);}
        var result=await runtime.VisionTaskAsync(task,cancel,token);lock(sync){Check(known);if(known.TaskId!=task)throw new OperationCanceledException();return View(result);}
    }
    private static object View(RuntimeTask task)=>new {taskId=task.TaskId.ToString("D"),status=task.Status.ToString(),result=task.Result,
        error=task.Error is {} error?new WorkspaceSafeError(error.ToString(),ErrorText.For(error)):null};
    private async Task CancelBestEffort(Guid id) {
        lock(sync){if(cancellations.Count>=8||!cancellations.Add(id))return;}
        try {
            using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(8));
            while(!deadline.IsCancellationRequested) {
                try {await runtime.VisionTaskAsync(id,false,deadline.Token);await runtime.VisionTaskAsync(id,true,deadline.Token);return;}
                catch(DesktopException error) when(error.Error==DesktopError.TaskNotFound) {await Task.Delay(350,deadline.Token);}
            }
        } catch(Exception) {} finally {lock(sync)cancellations.Remove(id);}
    }
}

internal static class NativeVisionConfirmation
{
    internal static Task<bool> Confirm(Window? owner,VisionValidationIntent intent,CancellationToken token) {
        token.ThrowIfCancellationRequested();if(owner is null||!owner.IsVisible)return Task.FromResult(false);
        var window=new Window {Owner=owner,Title="Vision exact-intent confirmation",Width=700,SizeToContent=SizeToContent.Height,WindowStartupLocation=WindowStartupLocation.CenterOwner,ShowInTaskbar=false,ResizeMode=ResizeMode.NoResize};
        var panel=new StackPanel {Margin=new Thickness(20)};
        panel.Children.Add(new TextBox {IsReadOnly=true,TextWrapping=TextWrapping.Wrap,Text=$"VISION_VALIDATE\nModel: {intent.Model}\nRunner: {intent.Runner}\nChild: {intent.Digest}\nProjector: {intent.ProjectorDigest}\nRevision: {intent.ExpectedSelectionRevision}\nGeneration: {intent.Generation}\nPolicy: {intent.Policy}"});
        panel.Children.Add(new TextBlock {Text=ModelConfirmationWindow.EvictionWarning+"\n本次会发送配对合成图；60 秒内有效。取消、Escape 或关闭均拒绝。",TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,12,0,12)});
        var buttons=new StackPanel {Orientation=Orientation.Horizontal,HorizontalAlignment=HorizontalAlignment.Right};
        var cancel=new Button {Content="Cancel / 取消",IsCancel=true,IsDefault=true,MinWidth=120};var allow=new Button {Content="仅允许本次 Vision 验证",MinWidth=180,Margin=new Thickness(12,0,0,0)};
        cancel.Click+=(_,_)=>window.DialogResult=false;allow.Click+=(_,_)=>{allow.IsEnabled=false;window.DialogResult=true;};buttons.Children.Add(cancel);buttons.Children.Add(allow);panel.Children.Add(buttons);window.Content=panel;window.Loaded+=(_,_)=>cancel.Focus();
        using var registration=token.Register(()=>window.Dispatcher.BeginInvoke(new Action(()=>{if(window.IsVisible)window.Close();})));
        return Task.FromResult(window.ShowDialog()==true&&!token.IsCancellationRequested);
    }
}
