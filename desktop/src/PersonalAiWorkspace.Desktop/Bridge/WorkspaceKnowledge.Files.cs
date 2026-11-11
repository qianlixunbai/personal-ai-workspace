using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal sealed record KnowledgeSelectedFile(string RequestId,string Filename,long ByteLength);
internal sealed record KnowledgeFileSelection(KnowledgeSelectedFile[] Files);

internal sealed partial class WorkspaceKnowledge
{
    private static void ClearSelectedFiles(Authority known)
    {
        foreach(var file in known.SelectedFiles.Values)file.Dispose();
        known.SelectedFiles.Clear();
    }

    internal async Task<KnowledgeFileSelection> SelectFilesAsync(string session,CancellationToken ct)
    {
        Authority known;
        lock(sync){
            known=Require(session);
            if(known.SelectingFiles||known.ImportingFile||known.SelectedFiles.Count!=0)throw new DesktopException(DesktopError.QueueFull);
            known.SelectingFiles=true;
        }
        KnowledgeSourceFile[] selected=[];bool transferred=false;
        try{
            selected=await files.PickManyAsync(ct);ct.ThrowIfCancellationRequested();
            if(selected.Length>NativeKnowledgeSourceFiles.MaximumSelectedFiles)throw new DesktopException(DesktopError.KnowledgeLimitExceeded);
            long total=0;
            foreach(var file in selected){
                RuntimeClient.ValidateKnowledgeFilename(file.Filename);
                if(!file.Stream.CanRead||!file.Stream.CanSeek||file.Stream.Position!=0||file.Stream.Length<=0||file.Stream.Length>RuntimeClient.MaximumKnowledgeSourceBytes)
                    throw new DesktopException(DesktopError.KnowledgeInvalidSource);
                total+=file.Stream.Length;
            }
            if(total>NativeKnowledgeSourceFiles.MaximumSelectedBytes)throw new DesktopException(DesktopError.KnowledgeSourceTooLarge);
            var items=selected.Select(file=>new KnowledgeSelectedFile(Guid.NewGuid().ToString("D"),file.Filename,file.Stream.Length)).ToArray();
            lock(sync){
                Current(session,known);ct.ThrowIfCancellationRequested();
                for(int i=0;i<items.Length;i++){
                    known.SelectedFiles.Add(items[i].RequestId,selected[i]);
                    if(known.Imports.Count>=505)known.Imports.Remove(known.Imports.First());
                    known.Imports.Add(items[i].RequestId);
                }
                transferred=true;
            }
            return new(items);
        }finally{
            if(!transferred)foreach(var file in selected)file.Dispose();
            lock(sync)known.SelectingFiles=false;
        }
    }

    internal async Task<KnowledgeImportResult> ImportSelectedAsync(string session,string request,CancellationToken ct)
    {
        Authority known;KnowledgeSourceFile selected;
        lock(sync){
            known=Require(session,request:request);ct.ThrowIfCancellationRequested();
            if(known.SelectingFiles||known.ImportingFile)throw new DesktopException(DesktopError.QueueFull);
            // Consume before any await. A duplicate request cannot replay the selected stream.
            if(!known.SelectedFiles.Remove(request,out selected!))throw new DesktopException(DesktopError.InvalidRequest);
            known.ImportingFile=true;
        }
        using(selected){
            try{return await UploadSelectedAsync(session,known,selected,request,null,null,ct);}
            finally{lock(sync)known.ImportingFile=false;}
        }
    }

    internal object DiscardSelectedFiles(string session)
    {
        lock(sync){
            var known=Require(session);
            if(known.SelectingFiles||known.ImportingFile)throw new DesktopException(DesktopError.QueueFull);
            ClearSelectedFiles(known);return new{cleared=true};
        }
    }
}
