namespace PersonalAiWorkspace.Core;

public enum SelectionStatus { Success, Unsupported, Empty, Protected, Untrusted, ForegroundChanged, Timeout, Failed, TooLarge }
public sealed record SelectionResult(SelectionStatus Status, string? Text = null, long FocusWindow = 0, bool CopyAllowed = false)
{
    public override string ToString() => $"SelectionResult[status={Status},characters={Text?.Length ?? 0}]";
}

public static class SelectionRules
{
    public static SelectionResult Classify(bool protectedField, bool foregroundStable, bool patternSupported, string? selectedText, long focusWindow = 0, bool copyAllowed = false)
    {
        if (protectedField) return new(SelectionStatus.Protected);
        if (!foregroundStable) return new(SelectionStatus.ForegroundChanged);
        if (!patternSupported) return new(SelectionStatus.Unsupported, FocusWindow: focusWindow, CopyAllowed: copyAllowed);
        if (string.IsNullOrWhiteSpace(selectedText)) return new(SelectionStatus.Empty);
        if (selectedText.Length > 4000) return new(SelectionStatus.TooLarge);
        return new(SelectionStatus.Success, selectedText, focusWindow);
    }

    public static bool FreshClipboard(uint before, uint observed, uint lockedSequence, bool expectedOwner, bool foregroundStable)
        => observed != before && observed == lockedSequence && expectedOwner && foregroundStable;
}

public static class SelectionText
{
    public static string For(SelectionStatus status) => status switch
    {
        SelectionStatus.Protected => "Protected/password control：已拒绝读取和复制。请使用手动输入。",
        SelectionStatus.Empty => "没有选中文字。请先选择文字，或在此手动输入。",
        SelectionStatus.Unsupported => "当前控件不支持安全选区读取。请使用手动输入。",
        SelectionStatus.ForegroundChanged => "前台应用或焦点已变化，已停止捕获。请重新选择后触发。",
        SelectionStatus.Timeout => "Selection timed out：UIA provider 超时，已终止隔离进程。请手动输入。",
        SelectionStatus.TooLarge => "选区超过 4000 字符，请缩短后重试。",
        _ => "无法验证当前控件或读取选区；已停止捕获。请使用手动输入。"
    };
}
