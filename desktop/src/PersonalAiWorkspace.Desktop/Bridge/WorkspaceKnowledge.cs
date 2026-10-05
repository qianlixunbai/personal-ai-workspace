using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal sealed record KnowledgeImportResult(string Outcome,string? RequestId,KnowledgeJob? Job);
internal sealed record KnowledgeRevisionView(string SourceRevision,string SourceType,long ByteLength);
internal sealed record KnowledgeDetailView(KnowledgeDocument Document,KnowledgeRevisionView[] Revisions,KnowledgeJob? Job);
internal sealed class WorkspaceKnowledge(RuntimeClient runtime,IKnowledgeSourceFiles files)
{
    private sealed class Authority {internal readonly HashSet<string> Documents=new(StringComparer.Ordinal);internal readonly HashSet<string> Imports=new(StringComparer.Ordinal);}
    private readonly Dictionary<string,Authority> sessions=new(StringComparer.Ordinal);
    private readonly object sync=new();
    internal void BeginSession(string session){lock(sync)sessions[session]=new();}
    internal void EndSession(string session){lock(sync)sessions.Remove(session);}
    internal int AuthorizationCount(string session){lock(sync)return Require(session).Documents.Count;}
    private Authority Require(string session,string? document=null,string? request=null)
    {
        if(!sessions.TryGetValue(session,out var known)||document is not null&&!known.Documents.Contains(document)||request is not null&&!known.Imports.Contains(request))
            throw new DesktopException(DesktopError.KnowledgeNotFound);return known;
    }
    private void Authorize(string session,Authority known,string document,string? request=null)
    {
        if(!sessions.TryGetValue(session,out var current)||!ReferenceEquals(current,known))return;
        if(!known.Documents.Contains(document)&&known.Documents.Count>=500)known.Documents.Remove(known.Documents.First());known.Documents.Add(document);
        if(request is not null){if(known.Imports.Count>=505)known.Imports.Remove(known.Imports.First());known.Imports.Add(request);}
    }
    internal async Task<KnowledgeList> ListAsync(string session,string status,int page,CancellationToken ct)
    {Authority known;lock(sync)known=Require(session);var result=await runtime.ListKnowledgeAsync(status,page,ct);lock(sync)foreach(var d in result.Items)Authorize(session,known,d.DocumentId,d.RequestId);return result;}
    internal async Task<KnowledgeDetailView> GetAsync(string session,string id,CancellationToken ct)
    {
        lock(sync)Require(session,id);var detail=await runtime.GetKnowledgeAsync(id,ct);
        return new(detail.Document,detail.Revisions.Select(r=>new KnowledgeRevisionView(r.SourceRevision,r.SourceType,r.ByteLength)).ToArray(),detail.Job);
    }
    internal async Task<KnowledgePreview> PreviewAsync(string session,string id,string revision,int offset,CancellationToken ct)
    {lock(sync)Require(session,id);return await runtime.PreviewKnowledgeAsync(id,revision,offset,ct);}
    internal async Task<KnowledgeImportResult> ImportAsync(string session,string? id,string? expected,CancellationToken ct)
    {
        Authority known;lock(sync)known=Require(session,id);using var selected=await files.PickAsync(ct);ct.ThrowIfCancellationRequested();
        if(selected is null)return new("CANCELLED",null,null);
        lock(sync){if(!ReferenceEquals(Require(session,id),known))throw new OperationCanceledException();}
        string request=Guid.NewGuid().ToString("D");lock(sync){if(known.Imports.Count>=505)known.Imports.Remove(known.Imports.First());known.Imports.Add(request);}
        try {
            var job=await runtime.UploadKnowledgeAsync(selected.Stream,selected.Filename,request,id,expected,ct);
            lock(sync)Authorize(session,known,job.DocumentId,request);return new("ACCEPTED",request,job);
        }catch(DesktopException e)when(e.Error is DesktopError.OutcomeUnknown or DesktopError.InvalidResponse or DesktopError.ClientTimeout or DesktopError.RuntimeUnavailable){
            // Read-only reconciliation is permitted; source bytes are never automatically replayed.
            try{var job=await runtime.GetKnowledgeImportAsync(request,ct);lock(sync)Authorize(session,known,job.DocumentId,request);return new("ACCEPTED",request,job);}
            catch(DesktopException){return new("UNKNOWN",request,null);}
        }
    }
    internal async Task<KnowledgeJob> ImportStateAsync(string session,string request,CancellationToken ct)
    {Authority known;lock(sync)known=Require(session,request:request);var job=await runtime.GetKnowledgeImportAsync(request,ct);lock(sync)Authorize(session,known,job.DocumentId,request);return job;}
    internal async Task<KnowledgeJob> CancelAsync(string session,string id,string request,CancellationToken ct)
    {lock(sync)Require(session,id,request);return await runtime.CancelKnowledgeImportAsync(request,id,ct);}
    internal async Task<object> LifecycleAsync(string session,string id,string version,string method,CancellationToken ct)
    {
        Authority known;lock(sync)known=Require(session,id);
        if(method=="knowledge.delete"){
            await runtime.DeleteKnowledgeAsync(id,version,ct);lock(sync)if(sessions.TryGetValue(session,out var current)&&ReferenceEquals(current,known))known.Documents.Remove(id);
            return new{deleted=true};
        }
        return await runtime.KnowledgeLifecycleAsync(id,version,method=="knowledge.archive"?"archive":"restore",ct);
    }
}
