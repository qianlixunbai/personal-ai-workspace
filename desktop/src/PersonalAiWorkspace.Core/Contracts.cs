using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public enum DesktopError
{
    RuntimeUnavailable, Unauthorized, CredentialMissing, CredentialInvalid, CredentialStorage,
    QueueFull, ProviderUnavailable, ModelUnavailable, PolicyDenied, InvalidRequest,
    InvalidResponse, TaskNotFound, Cancelled, TimedOut, ClientTimeout, ProviderResponseInvalid, InternalError, OutcomeUnknown,
    InvalidExtensionOrigin, PairingCapacityFull, SecurityStateError, PairingCreationFailed, BrowserManagementFailed,
    MemoryNotFound, MemoryRevisionConflict, MemoryLimitExceeded, MemoryInvalid, MemoryStorageUnavailable, MemorySchemaUnsupported,
    MemorySelectionStale, MemoryAskBudget,
    WorkspaceBackupInvalid, WorkspaceBackupUnsupported, WorkspaceBackupTooLarge, WorkspaceBackupConflict,
    WorkspaceRestoreTargetNotEmpty, WorkspaceExportFailed, WorkspaceRestoreFailed, WorkspaceBackupFileUnavailable,
    MemoryBackupInvalid, MemoryBackupUnsupported, MemoryBackupTooLarge, MemoryRestoreTargetNotEmpty,
    MemoryExportFailed, MemoryRestoreFailed, MemoryBackupFileUnavailable,
    ConversationNotFound, ConversationInvalid, ConversationConflict, ConversationLimitExceeded, ConversationStorageUnavailable,
    KnowledgeInvalidSource, KnowledgeUnsupportedType, KnowledgeInvalidUtf8, KnowledgeSourceTooLarge,
    KnowledgeLimitExceeded, KnowledgeDuplicateSource, KnowledgeRevisionConflict, KnowledgeNotFound,
    KnowledgeQueueFull, KnowledgeIngestionFailed, KnowledgeInterrupted, KnowledgeCancelled,
    KnowledgeStorageUnavailable, KnowledgeSchemaUnsupported, KnowledgeDeleteIncomplete,
    KnowledgeBackupInvalid, KnowledgeBackupUnsupported, KnowledgeBackupTooLarge, KnowledgeBackupConflict,
    KnowledgeRestoreTargetNotEmpty, KnowledgeRestoreFailed, KnowledgeExportFailed, KnowledgeFileUnavailable
}

public sealed class DesktopException(DesktopError error) : Exception(ErrorText.For(error))
{
    public DesktopError Error { get; } = error;
}

public static class ErrorText
{
    public static string For(DesktopError error) => error switch
    {
        DesktopError.ConversationNotFound => "Conversation or turn does not exist.",
        DesktopError.KnowledgeInvalidSource => "源文件须为本机普通文件，包含有效、非空的纯文本；不接受链接或二进制内容。",
        DesktopError.KnowledgeUnsupportedType => "仅支持严格 UTF-8 的 TXT、MD 或 Markdown 文件。",
        DesktopError.KnowledgeInvalidUtf8 => "文件不是有效 UTF-8；请先明确转换文件编码。",
        DesktopError.KnowledgeSourceTooLarge => "源文件超过 8 MiB 导入上限。",
        DesktopError.KnowledgeLimitExceeded => "Knowledge 容量或文本结构超出限制。",
        DesktopError.KnowledgeDuplicateSource => "相同源字节已属于另一个 Knowledge 文档。",
        DesktopError.KnowledgeRevisionConflict => "文档版本已变化或正在处理，请刷新后显式重试。",
        DesktopError.KnowledgeNotFound => "Knowledge 文档、版本或导入记录不存在。",
        DesktopError.KnowledgeQueueFull => "导入容量已满，请等待当前导入完成。",
        DesktopError.KnowledgeIngestionFailed => "导入处理失败，旧 READY 版本仍可使用。",
        DesktopError.KnowledgeInterrupted => "导入已中断，请先检查记录再显式重试。",
        DesktopError.KnowledgeCancelled => "导入已取消。",
        DesktopError.KnowledgeStorageUnavailable => "Knowledge 存储不可用，请检查 Runtime。",
        DesktopError.KnowledgeSchemaUnsupported => "Knowledge 数据或解析版本不受支持。",
        DesktopError.KnowledgeDeleteIncomplete => "删除未完成，请解除文件占用后显式重试。",
        DesktopError.KnowledgeBackupInvalid => "Knowledge 备份无效或已损坏。",
        DesktopError.KnowledgeBackupUnsupported => "Knowledge 备份版本不受支持。",
        DesktopError.KnowledgeBackupTooLarge => "Knowledge 备份超过容量限制。",
        DesktopError.KnowledgeBackupConflict => "请先等待导入或删除操作结束，再导出备份。",
        DesktopError.KnowledgeRestoreTargetNotEmpty => "恢复目标须为新的或空的 Workspace 数据目录。",
        DesktopError.KnowledgeRestoreFailed => "无法确认 Knowledge 恢复完成，请检查目标目录。",
        DesktopError.KnowledgeExportFailed => "Knowledge 导出未完成，请显式重试。",
        DesktopError.KnowledgeFileUnavailable => "无法安全读取或保存所选文件。",
        DesktopError.ConversationInvalid => "Conversation request is invalid.",
        DesktopError.ConversationConflict => "Conversation or turn state does not allow this operation.",
        DesktopError.ConversationLimitExceeded => "Conversation capacity or size limit exceeded.",
        DesktopError.ConversationStorageUnavailable => "Conversation storage is unavailable.",
        DesktopError.RuntimeUnavailable => "Runtime unavailable：请先启动本机 Runtime。",
        DesktopError.OutcomeUnknown => "Outcome unknown：提交可能已被接受，但未收到可验证的响应。请检查 Runtime；不会自动重发。",
        DesktopError.Unauthorized => "Unauthorized：凭据已失效，请显式重新导入 Runtime token。",
        DesktopError.CredentialMissing => "Credential missing：请首次导入本机 Runtime 的私有 token 文件。",
        DesktopError.CredentialInvalid => "Credential invalid：token 格式、所有者或私有权限不符合要求。",
        DesktopError.CredentialStorage => "Credential storage unavailable：Windows 安全凭据存储不可用。",
        DesktopError.QueueFull => "Queue full：Runtime 任务容量已满，请稍后重试。",
        DesktopError.ProviderUnavailable => "Provider unavailable：本机 Ollama 不可用。",
        DesktopError.ModelUnavailable => "Model unavailable：Runtime 配置的本机模型不可用。",
        DesktopError.PolicyDenied => "Policy denied：本地执行策略拒绝此次请求。",
        DesktopError.InvalidRequest => "Invalid request：检查当前 Action 的输入、语言与预算。",
        DesktopError.InvalidResponse => "Malformed Runtime response：响应不符合契约，已停止处理。",
        DesktopError.TaskNotFound => "Task not found：任务已过期或 Runtime 已重启。",
        DesktopError.Cancelled => "Cancelled：任务已取消；这不保证 GPU 立即停止。",
        DesktopError.TimedOut => "Timed out：任务超出 Runtime 时间预算。",
        DesktopError.ClientTimeout => "Runtime response timed out：通信超时，请检查 Runtime。",
        DesktopError.ProviderResponseInvalid => "Provider response invalid：本机模型响应无效。",
        DesktopError.InvalidExtensionOrigin => "Invalid extension origin：请输入 chrome-extension:// 加 32 个 a-p 小写字符，无尾斜线。",
        DesktopError.PairingCapacityFull => "Pairing capacity full：配对或注册容量已满，请等待配对过期或撤销不用的客户端。",
        DesktopError.SecurityStateError => "Security state error：Runtime 无法更新安全状态，请检查 Runtime 后重试。",
        DesktopError.PairingCreationFailed => "Pairing creation failed：未能创建配对，请检查 Runtime 后显式重试。",
        DesktopError.BrowserManagementFailed => "Browser management failed：未确认列表或撤销结果，请刷新后检查。",
        DesktopError.MemorySelectionStale => "Selected Memory changed. Review and select Memory again.",
        DesktopError.MemoryAskBudget => "Combined input exceeds the budget or is invalid. Reduce selected Memory or shorten the question/Memory.",
        DesktopError.MemoryNotFound => "Memory 已不存在；请新建或刷新列表。",
        DesktopError.MemoryRevisionConflict => "Memory 自加载后已被修改。请重新加载最新版本后再保存。",
        DesktopError.MemoryLimitExceeded => "Memory 容量或输入长度超限；请检查输入或删除不用的条目。",
        DesktopError.MemoryInvalid => "Memory 输入无效：标题须非空且不超过 160 个 Unicode 字符；正文须非空且不超过 2000 个 UTF-16 单位和 8192 UTF-8 字节；搜索不超过 160 个 Unicode 字符。",
        DesktopError.MemoryStorageUnavailable => "Memory 存储不可用；请检查 Runtime 后显式重试。",
        DesktopError.MemorySchemaUnsupported => "Memory 数据版本不受当前 Runtime 支持。",
        DesktopError.WorkspaceBackupInvalid => "Workspace backup is invalid or damaged. Restore stopped.",
        DesktopError.WorkspaceBackupUnsupported => "Workspace backup version is unsupported.",
        DesktopError.WorkspaceBackupTooLarge => "Workspace backup exceeds the size budget.",
        DesktopError.WorkspaceBackupConflict => "Wait for pending turns to finish or Cancel before backing up.",
        DesktopError.WorkspaceRestoreTargetNotEmpty => "Choose a NEW / EMPTY directory outside the current Workspace.",
        DesktopError.WorkspaceExportFailed => "Workspace export could not be completed.",
        DesktopError.WorkspaceRestoreFailed => "Workspace restore could not be confirmed. Current Workspace is unchanged.",
        DesktopError.WorkspaceBackupFileUnavailable => "Workspace backup file could not be read or saved.",
        DesktopError.MemoryBackupInvalid => "Memory backup is invalid or damaged. 已停止恢复。",
        DesktopError.MemoryBackupUnsupported => "Memory backup version is unsupported. 已停止恢复。",
        DesktopError.MemoryBackupTooLarge => "Memory backup exceeds the size budget. 已停止处理。",
        DesktopError.MemoryRestoreTargetNotEmpty => "Restore requires a NEW / EMPTY data directory. 请选择新的或空的目录。",
        DesktopError.MemoryExportFailed => "Memory export could not be completed. 请显式重试。",
        DesktopError.MemoryRestoreFailed => "Memory restore could not be confirmed. 请检查所选目标目录后显式重试。",
        DesktopError.MemoryBackupFileUnavailable => "Memory backup file could not be read or saved. 请检查所选文件后显式重试。",
        _ => "Runtime internal error：Runtime 未能完成此次请求。"
    };
}

public sealed record TranslateInput(string Text, string TargetLanguage)
{
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(Text) || Text.Length > 4000 || System.Text.Encoding.UTF8.GetByteCount(Text) > 5632
            || !Regex.IsMatch(TargetLanguage, @"\A[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}\z"))
            throw new DesktopException(DesktopError.InvalidRequest);
    }
    public override string ToString() => "TranslateInput[redacted]";
}

public enum TaskState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }

public sealed record RuntimeTask(Guid TaskId, TaskState Status, string? Result, DesktopError? Error)
{
    public bool Terminal => Status is not (TaskState.QUEUED or TaskState.RUNNING);
    public override string ToString() => $"RuntimeTask[taskId={TaskId},status={Status}]";
}

public static class CredentialFormat
{
    public static bool Valid(string? token) => token is not null && Regex.IsMatch(token, @"\A[A-Za-z0-9_-]{43}\z");
}

public enum AssistantAction { Translate, Summarize, Ask }

public sealed record SummarizeInput(string Text)
{
    public void Validate() => InputBudget.Validate(Text, 6000, 6656);
    public override string ToString() => "SummarizeInput[redacted]";
}
public sealed record AskInput(string Question)
{
    public void Validate() => InputBudget.Validate(Question, 3000, 5632);
    public override string ToString() => "AskInput[redacted]";
}
internal static class InputBudget
{
    public static void Validate(string text, int characters, int bytes)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Length > characters || System.Text.Encoding.UTF8.GetByteCount(text) > bytes)
            throw new DesktopException(DesktopError.InvalidRequest);
    }
}
public sealed record AssistantInput(AssistantAction Action, string Text, string TargetLanguage = "zh-CN")
{
    public void Validate()
    {
        switch (Action)
        {
            case AssistantAction.Translate: new TranslateInput(Text, TargetLanguage).Validate(); break;
            case AssistantAction.Summarize: new SummarizeInput(Text).Validate(); break;
            case AssistantAction.Ask: new AskInput(Text).Validate(); break;
            default: throw new DesktopException(DesktopError.InvalidRequest);
        }
    }
    public override string ToString() => $"AssistantInput[action={Action},redacted]";
}
