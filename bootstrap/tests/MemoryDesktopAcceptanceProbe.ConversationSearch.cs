using System.IO;
using System.Text.Json;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

internal static partial class MemoryDesktopAcceptanceProbe
{
    private static async Task SearchScope(MainWorkspaceWindow shell, string scope, CancellationToken ct)
    {
        await Wait(() => ScriptIs(shell, "!document.getElementById('conversation-search-scope')?.disabled"), ct);
        await Script(shell, "(()=>{const e=document.getElementById('conversation-search-scope');Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(e," + Json(scope) + ");e.dispatchEvent(new Event('change',{bubbles:true}));})()");
        await Wait(() => ScriptIs(shell, "document.getElementById('conversation-search-scope').value===" + Json(scope)), ct);
    }
    private static Task SearchRows(MainWorkspaceWindow shell, int count, CancellationToken ct) => Wait(() => ScriptIs(shell,
        "document.querySelectorAll('[aria-label=历史消息查找结果] li').length===" + count), ct);
    private static Task SearchTurn(MainWorkspaceWindow shell, string title, int sequence, CancellationToken ct) => Wait(() => ScriptIs(shell,
        "document.getElementById('conversation-detail-title').textContent===" + Json(title) + "&&document.activeElement?.dataset.turnSequence===" + Json(sequence.ToString())), ct);
    private static async Task OpenSearch(MainWorkspaceWindow shell, string query, CancellationToken ct)
    {
        await Ready(shell, ct); await Script(shell, "location.hash='#/conversations'");
        await Wait(() => ScriptIs(shell, "document.getElementById('conversation-input')!==null&&!document.getElementById('conversation-search-query').disabled"), ct);
        Check(await ScriptIs(shell, "document.getElementById('conversation-search-query').value===''&&!document.querySelector('[aria-label=历史消息查找结果]')"));
        await Script(shell, "document.querySelector('.conversation-search summary').click()");
        await Input(shell, "conversation-search-query", query, ct);
    }
    private static async Task VerifyConversationSearch(MainWorkspaceWindow shell, RuntimeClient client, JsonElement fixture, CancellationToken ct)
    {
        string query = fixture.GetProperty("query").GetString()!, draft = "合成未发送草稿 😀：取消切换后应保留。";
        Guid active = fixture.GetProperty("activeId").GetGuid(), archived = fixture.GetProperty("archivedId").GetGuid();
        await OpenSearch(shell, query, ct); stage = "SEARCH_ALL_AND_ACTIVE";
        Check(!(await client.GetModelStatusAsync(ct)).Ready);
        await Input(shell, "conversation-input", draft, ct); await Click(shell, "查找消息", ct); await SearchRows(shell, 10, ct);
        Check(await ScriptIs(shell, "document.getElementById('conversation-search-scope').value==='ALL'"));
        await SearchScope(shell, "ACTIVE", ct); await Click(shell, "查找消息", ct); await SearchRows(shell, 10, ct);
        Check(await ScriptIs(shell, "[...document.querySelectorAll('[aria-label=历史消息查找结果] mark')].every(m=>m.textContent===" + Json(query) + ")"));
        Check(await ScriptIs(shell, "!document.querySelector('[aria-label=历史消息查找结果] script')&&document.querySelector('[aria-label=历史消息查找结果]').textContent.includes('<script>只作文本</script>')"));
        Check(await ScriptIs(shell, "!document.querySelector('[aria-label=历史消息查找结果]').textContent.includes('合成归档会话')"));
        await Click(shell, "下一页匹配", ct); await SearchRows(shell, 3, ct);
        stage = "SEARCH_SAME_CONVERSATION_JUMP";
        await Click(shell, "合成活动会话 · 第 11 轮 · 你", ct); await SearchTurn(shell, "合成活动会话", 11, ct);
        Check(await ScriptIs(shell, "document.getElementById('conversation-input').value===" + Json(draft)));
        var page = await client.GetConversationAsync(active, 1, 10, ct);
        string expected = page.Turns.Single(t => t.Sequence == 11).UserMessage.Content;
        Check(await ScriptIs(shell, "document.querySelector('[data-turn-sequence=\"11\"] pre').textContent===" + Json(expected)));
        await Capture(shell, "same-conversation-jump.png");
        Pass("SEARCH_LITERAL_PAGINATION_EXACT_TURN_DRAFT_RETAINED");

        stage = "SEARCH_ARCHIVED_CANCEL";
        await SearchScope(shell, "ARCHIVED", ct); await Click(shell, "查找消息", ct); await SearchRows(shell, 1, ct);
        Check(await ScriptIs(shell, "document.querySelector('[aria-label=历史消息查找结果]').textContent.includes('合成归档会话')&&!document.querySelector('[aria-label=历史消息查找结果]').textContent.includes('合成活动会话')"));
        await Click(shell, "合成归档会话 · 第 1 轮 · 你 · 已归档", ct);
        await Wait(() => ScriptIs(shell, "Boolean(document.querySelector('dialog[open]'))"), ct);
        Check(await ScriptIs(shell, "document.querySelector('dialog[open]').textContent.includes('当前未发送的消息将被清空')&&document.activeElement?.textContent==='取消'"));
        await Capture(shell, "draft-confirmation.png"); await Click(shell, "取消", ct);
        await Wait(() => ScriptIs(shell, "!document.querySelector('dialog[open]')"), ct);
        Check(await ScriptIs(shell, "document.getElementById('conversation-detail-title').textContent==='合成活动会话'&&document.getElementById('conversation-input').value===" + Json(draft)));
        Pass("ARCHIVED_FILTER_CANCEL_PRESERVES_ACTIVE_DRAFT");

        stage = "SEARCH_ARCHIVED_CONFIRM";
        await Click(shell, "合成归档会话 · 第 1 轮 · 你 · 已归档", ct);
        await Click(shell, "清空草稿并打开", ct); await SearchTurn(shell, "合成归档会话", 1, ct);
        Check(await ScriptIs(shell, "document.getElementById('conversation-input').value===''&&document.getElementById('conversation-input').readOnly"));
        Check(await ScriptIs(shell, "Boolean(" + Button("恢复") + ")&&" + Button("发送", false) + "?.disabled===true"));
        Check((await client.GetConversationAsync(archived, 0, 10, ct)).Conversation.Status == ConversationStatus.ARCHIVED);
        await Capture(shell, "archived-result-opened.png"); Pass("CONFIRMED_SWITCH_OPENS_ARCHIVED_TURN_WITHOUT_RESTORING");
        File.WriteAllText(Path.Combine(output, "workflow.json"), JsonSerializer.Serialize(new { literalText = true, activeMatches = 13, archivedMatches = 1,
            exactTurn = 11, sameConversationDraftRetained = true, cancelledSwitchRetainsDraft = true, confirmedSwitchClearsDraft = true, archivePreserved = true }));
    }
    private static async Task VerifyConversationSearchReopen(MainWorkspaceWindow shell, RuntimeClient client, JsonElement fixture, CancellationToken ct)
    {
        await OpenSearch(shell, fixture.GetProperty("query").GetString()!, ct); stage = "SEARCH_WINDOW_REOPEN";
        Check(await ScriptIs(shell, "document.getElementById('conversation-input').value===''"));
        await SearchScope(shell, "ACTIVE", ct); await Click(shell, "查找消息", ct); await SearchRows(shell, 10, ct);
        await Click(shell, "下一页匹配", ct); await SearchRows(shell, 3, ct);
        await Click(shell, "合成活动会话 · 第 11 轮 · 你", ct); await SearchTurn(shell, "合成活动会话", 11, ct);
        Check(!(await client.GetModelStatusAsync(ct)).Ready);
        await Capture(shell, "reopened-search.png"); Pass("WINDOW_REOPEN_CLEARS_SEARCH_AND_RELOADS_SAVED_HISTORY");
    }
}
