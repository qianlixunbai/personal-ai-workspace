using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop.Bridge;

internal sealed partial class WorkspaceKnowledge
{
    private bool worksetFileBusy;
    internal async Task<object> WorksetFileAsync(string session, string? query, KnowledgeSourceScope[]? scopes, CancellationToken ct)
    {
        if (query is not null && !KnowledgeWorksetFormat.ValidInput(query, scopes)) throw KnowledgeWorksetFormat.Invalid();
        Authority known;
        lock (sync)
        {
            known = Require(session);
            if (worksetFiles is null) throw new WorkspaceOperationException("NATIVE_UNAVAILABLE", "检索工作集文件暂时不可用。");
            if (worksetFileBusy) throw new DesktopException(DesktopError.QueueFull);
            if (scopes is not null) foreach (var scope in scopes) Require(session, scope.DocumentId);
            worksetFileBusy = true;
        }
        void Check() { ct.ThrowIfCancellationRequested(); lock (sync) Current(session, known); }
        bool saving = false;
        try
        {
            Check();
            if (query is null)
            {
                var loaded = await worksetFiles!.OpenAsync(ct); Check();
                if (loaded is null) return new { outcome = "CANCELLED", workset = (KnowledgeSearchWorkset?)null };
                KnowledgeWorksetFormat.Validate(loaded);
                // Untrusted file IDs grant no document or task authority. The UI must
                // match the current authorized document list before requesting details.
                return new { outcome = "OPENED", workset = loaded };
            }
            var sources = new List<KnowledgeWorksetSource>();
            foreach (var scope in scopes!)
            {
                Check(); lock (sync) Require(session, scope.DocumentId);
                var detail = await runtime.GetKnowledgeAsync(scope.DocumentId, ct); Check();
                if (!detail.Revisions.Any(revision => revision.SourceRevision == scope.SourceRevision))
                    throw new DesktopException(DesktopError.KnowledgeRevisionConflict);
                sources.Add(new(scope.DocumentId, scope.SourceRevision, detail.Document.Title, detail.Document.CreatedAt));
            }
            var workset = new KnowledgeSearchWorkset(query, sources.ToArray(), DateTimeOffset.UtcNow);
            KnowledgeWorksetFormat.Validate(workset); Check(); saving = true;
            bool saved = await worksetFiles!.SaveAsync(workset, ct); Check();
            return new { outcome = saved ? "SAVED" : "CANCELLED" };
        }
        catch (WorkspaceOperationException) { throw; }
        catch (Exception) when (saving)
        { throw new WorkspaceOperationException("KnowledgeWorksetSaveUnknown", "工作集保存结果未确认，请先检查所选位置；不会自动重试或覆盖已有文件。"); }
        finally { lock (sync) worksetFileBusy = false; }
    }
}
