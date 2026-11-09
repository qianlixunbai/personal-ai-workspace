using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows.Automation;
using System.Windows.Interop;
using PersonalAiWorkspace.Core;
using PersonalAiWorkspace.Desktop;

internal static partial class MemoryDesktopAcceptanceProbe
{
    private static async Task VerifyConversationExport(MainWorkspaceWindow shell, RuntimeClient client, JsonElement fixture, CancellationToken ct)
    {
        Guid id = fixture.GetProperty("activeId").GetGuid();
        const string draft = "此草稿未发送，不得导出：合成私人内容😀";
        string exportDirectory = Path.Combine(Path.GetTempPath(), "Workspace-conversation-export-" + Guid.NewGuid().ToString("N"));
        Check(!Directory.Exists(exportDirectory)); Directory.CreateDirectory(exportDirectory);
        string destination = Path.Combine(exportDirectory, "完整会话 with spaces.md"), cancelled = Path.Combine(exportDirectory, "cancelled.md");
        File.WriteAllText(Path.Combine(output, "owned-export.json"), JsonSerializer.Serialize(new { synthetic = true, destination, cancelled }));
        await Ready(shell, ct); await Script(shell, "location.hash='#/conversations'");
        await Wait(() => ScriptIs(shell, "document.getElementById('conversation-input')!==null&&document.querySelector('.conversation-detail').innerText.includes('23 轮')"), ct);
        await Input(shell, "conversation-input", draft, ct); await Click(shell, "更早", ct);
        await Wait(() => ScriptIs(shell, "document.querySelector('.conversation-detail').innerText.includes('第 2 页 / 3')"), ct);
        uint thread = ExportThreadId(); IntPtr owner = new WindowInteropHelper(shell).Handle;
        Task Dialog(bool cancel, string path) => Task.Run(async () =>
        {
            var clock = Stopwatch.StartNew(); AutomationElement? picker = null;
            while (picker is null)
            {
                ExportThreadWindows(thread, (window, _) =>
                {
                    if (ExportGetWindow(window, 4) != owner) return true;
                    var title = new StringBuilder(256); ExportWindowText(window, title, title.Capacity);
                    if (!title.ToString().StartsWith("导出完整会话", StringComparison.Ordinal)) return true;
                    picker = AutomationElement.FromHandle(window); return false;
                }, IntPtr.Zero);
                Check(clock.Elapsed.TotalSeconds < 25); if (picker is null) await Task.Delay(100, ct);
            }
            Check(picker.Current.ProcessId == Environment.ProcessId);
            if (cancel) { ExportSendMessage((IntPtr)picker.Current.NativeWindowHandle, 0x0111, 2, 0); return; }
            ValuePattern? filename = null;
            while (filename is null)
            {
                foreach (AutomationElement edit in picker.FindAll(TreeScope.Descendants, new OrCondition(
                    new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit), new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ComboBox))))
                    if ((edit.Current.Name.StartsWith("文件名", StringComparison.Ordinal) || edit.Current.Name.StartsWith("File name", StringComparison.Ordinal))
                        && edit.TryGetCurrentPattern(ValuePattern.Pattern, out var value)) { filename = (ValuePattern)value; break; }
                Check(clock.Elapsed.TotalSeconds < 25); if (filename is null) await Task.Delay(100, ct);
            }
            filename.SetValue(path); ExportSendMessage((IntPtr)picker.Current.NativeWindowHandle, 0x0111, 1, 0);
        }, ct);
        stage = "EXPORT_CANCEL";
        var dialog = Dialog(true, cancelled); var clicking = Click(shell, "导出完整会话…", ct); await dialog; await clicking;
        await Wait(() => ScriptIs(shell, "document.querySelector('.conversations-layout').innerText.includes('已取消导出。')"), ct);
        Check(!File.Exists(cancelled)); Check(await ScriptIs(shell, "document.getElementById('conversation-input').value===" + Json(draft)));
        stage = "EXPORT_SAVE";
        dialog = Dialog(false, destination); clicking = Click(shell, "导出完整会话…", ct); await dialog; await clicking;
        await Wait(() => ScriptIs(shell, "document.querySelector('.conversations-layout').innerText.includes('已导出完整会话笔记。')"), ct);
        string note = await File.ReadAllTextAsync(destination, ct); var turns = new List<ConversationTurn>();
        for (int page = 0; page < 3; page++) turns.AddRange((await client.GetConversationAsync(id, page, 10, ct)).Turns);
        Check(turns.Count == 23 && !note.Contains(draft, StringComparison.Ordinal));
        foreach (var turn in turns) Check(note.Contains("## 第 " + turn.Sequence + " 轮", StringComparison.Ordinal)
            && note.Contains(turn.UserMessage.Content, StringComparison.Ordinal) && note.Contains(turn.AssistantMessage!.Content, StringComparison.Ordinal));
        Check(await ScriptIs(shell, "document.getElementById('conversation-input').value===" + Json(draft)
            + "&&document.querySelector('.conversation-detail').innerText.includes('第 2 页 / 3')"));
        Check((await File.ReadAllBytesAsync(destination, ct)).SequenceEqual(new UTF8Encoding(false, true).GetBytes(note)));
        File.Copy(destination, Path.Combine(output, "exported-transcript.md"));
        using (var image = File.Create(Path.Combine(output, "conversation-export.png")))
            await shell.Browser.CoreWebView2.CapturePreviewAsync(Microsoft.Web.WebView2.Core.CoreWebView2CapturePreviewImageFormat.Png, image);
        File.WriteAllText(Path.Combine(output, "export-result.json"), JsonSerializer.Serialize(new { passed = true, nativeCancel = true, nativeSave = true,
            allTurns = turns.Count, draftPreservedAndExcluded = true, pagePreserved = true, sha256 = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(note))), modelCalls = 0 }));
    }
    [DllImport("kernel32.dll", EntryPoint="GetCurrentThreadId")] private static extern uint ExportThreadId();
    [DllImport("user32.dll", EntryPoint="EnumThreadWindows")] private static extern bool ExportThreadWindows(uint id, ExportWindowCallback callback, IntPtr data);
    private delegate bool ExportWindowCallback(IntPtr window, IntPtr data);
    [DllImport("user32.dll", EntryPoint="GetWindow")] private static extern IntPtr ExportGetWindow(IntPtr window, uint command);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, EntryPoint="GetWindowTextW")] private static extern int ExportWindowText(IntPtr window, StringBuilder text, int size);
    [DllImport("user32.dll", EntryPoint="SendMessageW")] private static extern IntPtr ExportSendMessage(IntPtr window, uint message, int wParam, int lParam);
}
