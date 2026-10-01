namespace PersonalAiWorkspace.Core;

public sealed record ClipboardSnapshot(uint Sequence, string? Text, bool Supported)
{
    public override string ToString() => $"ClipboardSnapshot[supported={Supported}]";
}
public enum ClipboardRestore { Restored, ExternalChange, Failed }
public sealed record CopyResult(SelectionResult Selection, string Notice)
{
    public override string ToString() => $"CopyResult[status={Selection.Status}]";
}
public interface ICopyPort
{
    bool ContextStable { get; }
    bool ModifiersReleased { get; }
    Task<ClipboardSnapshot?> SnapshotAsync(CancellationToken cancellationToken);
    bool SendCopy(uint expectedSequence);
    uint Sequence { get; }
    bool ExpectedCopy(uint before, uint observed);
    Task<string?> ReadFreshAsync(uint before, uint observed, CancellationToken cancellationToken);
    Task<ClipboardRestore> RestoreAsync(ClipboardSnapshot snapshot, uint copiedSequence);
}

public static class ControlledCopy
{
    public static async Task<CopyResult> CaptureAsync(SelectionResult proof, ICopyPort port, CancellationToken cancellationToken)
    {
        if (proof.Status != SelectionStatus.Unsupported || !proof.CopyAllowed) return new(proof, "");
        // A protected/untrusted/empty/timed-out UIA result can never authorize Copy.
        var elapsed = System.Diagnostics.Stopwatch.StartNew();
        while (!port.ModifiersReleased && elapsed.ElapsedMilliseconds < 700)
            await Task.Delay(20, cancellationToken);
        if (!port.ContextStable) return new(new(SelectionStatus.ForegroundChanged), "");
        if (!port.ModifiersReleased) return new(new(SelectionStatus.Failed), "请释放快捷键后重试，或手动输入。");
        var snapshot = await port.SnapshotAsync(cancellationToken);
        if (snapshot is null || !snapshot.Supported)
            return new(proof, "复制备用仅支持空或纯文本剪贴板；当前剪贴板保持不动，请手动输入。");
        if (!port.SendCopy(snapshot.Sequence)) return new(new(SelectionStatus.Failed), "未能安全发送 Copy；请手动输入。");
        uint copied = snapshot.Sequence;
        string? text = null;
        string notice = "";
        try
        {
            elapsed.Restart();
            while (elapsed.ElapsedMilliseconds < 600)
            {
                if (!port.ContextStable)
                {
                    notice = "焦点已变化，未接受复制内容；剪贴板可能已变化，请检查并手动输入。";
                    break;
                }
                uint observed = port.Sequence;
                if (observed != snapshot.Sequence)
                {
                    // Native port validates owner, sequence and foreground under clipboard lock.
                    if (port.ExpectedCopy(snapshot.Sequence, observed))
                    {
                        copied = observed; // Preserve restoration obligation even if reading hangs/cancels.
                        text = await port.ReadFreshAsync(snapshot.Sequence, observed, cancellationToken);
                        if (text is null) notice = "无法安全读取本次复制内容，已尝试恢复剪贴板；请手动输入。";
                        break;
                    }
                    // Do not restore an unverified third party clipboard update.
                    notice = "剪贴板变化无法归属本次 Copy，已保留当前内容；请手动输入。";
                    break;
                }
                await Task.Delay(20, cancellationToken);
            }
        }
        finally
        {
            if (copied != snapshot.Sequence)
            {
                var restored = await port.RestoreAsync(snapshot, copied);
                if (restored != ClipboardRestore.Restored)
                {
                    text = null;
                    notice = restored == ClipboardRestore.ExternalChange
                        ? "剪贴板已被其他操作更新，未覆盖新内容；请手动输入。"
                        : "Clipboard restore failed：复制内容可能仍在剪贴板，请检查并手动输入。";
                }
            }
        }
        if (copied == snapshot.Sequence && notice.Length == 0)
            notice = "未在有限时间内取得新复制内容；原应用迟到的 Copy 仍可能更新剪贴板，请检查或手动输入。";
        return new(SelectionRules.Classify(false, port.ContextStable, true, text), notice);
    }
}
