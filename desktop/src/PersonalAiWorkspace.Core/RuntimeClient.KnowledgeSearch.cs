using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed record KnowledgeHighlight(int Start,int End);
public sealed record KnowledgeSearchHit(string DocumentId,string Title,string SourceRevision,string SourceType,
    int StartOffset,int EndOffset,int StartLine,int EndLine,string? Heading,string Snippet,KnowledgeHighlight[] HighlightRanges)
{ public override string ToString()=>$"KnowledgeSearchHit[documentId={DocumentId}]"; }
public sealed record KnowledgeSearchResult(KnowledgeSearchHit[] Hits)
{ public override string ToString()=>$"KnowledgeSearchResult[count={Hits.Length}]"; }
public sealed record KnowledgeSearchStatus(string State,int IndexedDocuments,int IndexedChunks);

public sealed partial class RuntimeClient
{
    public static void ValidateKnowledgeQuery(string query)
    {
        ValidateUnicode(query);
        if(string.IsNullOrWhiteSpace(query)||query.Any(char.IsControl))throw new DesktopException(DesktopError.KnowledgeSearchInvalid);
        if(query.EnumerateRunes().Count()>128)throw new DesktopException(DesktopError.KnowledgeQueryTooComplex);
    }
    public async Task<KnowledgeSearchResult> SearchKnowledgeAsync(string query,int limit,CancellationToken token)
    {
        ValidateKnowledgeQuery(query);if(limit is <1 or >10)throw new DesktopException(DesktopError.KnowledgeSearchInvalid);
        using var body=await KnowledgeJson(HttpMethod.Post,"/search",new{query,limit},token);var root=body.RootElement;MemoryFields(root,"hits");
        var hits=Property(root,"hits");if(hits.ValueKind!=JsonValueKind.Array||hits.GetArrayLength()>limit)throw Invalid();
        var rows=hits.EnumerateArray().Select(KSearchHit).ToArray();
        if(rows.Select(r=>(r.DocumentId,r.SourceRevision,r.StartOffset)).Distinct().Count()!=rows.Length)throw Invalid();
        return new(rows);
    }
    public async Task<KnowledgeSearchStatus> KnowledgeSearchStatusAsync(CancellationToken token)
    {using var body=await KnowledgeJson(HttpMethod.Get,"/search/status",null,token);return KSearchStatus(body.RootElement);}
    public async Task<KnowledgeSearchStatus> RebuildKnowledgeSearchAsync(CancellationToken token)
    {using var body=await KnowledgeJson(HttpMethod.Post,"/search/rebuild",new{},token);return KSearchStatus(body.RootElement);}
    private static KnowledgeSearchStatus KSearchStatus(JsonElement root)
    {
        MemoryFields(root,"state","indexedDocuments","indexedChunks");string state=String(root,"state");
        if(state is not ("READY" or "BUILDING" or "STALE" or "FAILED"))throw Invalid();
        int docs=(int)KNumber(root,"indexedDocuments",0,500),chunks=(int)KNumber(root,"indexedChunks",0,100000);
        if(state!="READY"&&(docs!=0||chunks!=0)||docs>chunks)throw Invalid();return new(state,docs,chunks);
    }
    private static KnowledgeSearchHit KSearchHit(JsonElement r)
    {
        MemoryFields(r,"documentId","title","sourceRevision","sourceType","startOffset","endOffset","startLine","endLine","heading","snippet","highlightRanges");
        string id=String(r,"documentId"),title=String(r,"title"),revision=String(r,"sourceRevision"),type=String(r,"sourceType"),snippet=String(r,"snippet");
        KId(id);ValidateUnicode(title);ValidateUnicode(snippet);
        if(KVersion(revision)>10||type is not ("TXT" or "MARKDOWN")||string.IsNullOrWhiteSpace(title)||title.Length>160||title.Any(char.IsControl)||title.IndexOfAny(['/', '\\', ':'])>=0||snippet.Length>384)throw Invalid();
        int start=(int)KNumber(r,"startOffset",0,1000000),end=(int)KNumber(r,"endOffset",start+1,1000000);
        int first=(int)KNumber(r,"startLine",1,100000),last=(int)KNumber(r,"endLine",first,100000);
        string? heading=KNullable(r,"heading");if(heading is not null){ValidateUnicode(heading);if(heading.Length>96)throw Invalid();}
        var ranges=Property(r,"highlightRanges");if(ranges.ValueKind!=JsonValueKind.Array||ranges.GetArrayLength()>16)throw Invalid();
        int previous=0;var marks=ranges.EnumerateArray().Select(x=>{
            MemoryFields(x,"start","end");int from=(int)KNumber(x,"start",previous,snippet.Length),to=(int)KNumber(x,"end",from+1,snippet.Length);
            if(from<snippet.Length&&char.IsLowSurrogate(snippet[from])||to<snippet.Length&&char.IsLowSurrogate(snippet[to]))throw Invalid();previous=to;return new KnowledgeHighlight(from,to);
        }).ToArray();return new(id,title,revision,type,start,end,first,last,heading,snippet,marks);
    }
}
