using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public enum DesktopError
{
    RuntimeUnavailable, Unauthorized, CredentialMissing, CredentialInvalid, CredentialStorage,
    QueueFull, ProviderUnavailable, ModelUnavailable, PolicyDenied, InvalidRequest,
    InvalidResponse, TaskNotFound, Cancelled, TimedOut, ClientTimeout, ProviderResponseInvalid, InternalError,
    InvalidExtensionOrigin, PairingCapacityFull, SecurityStateError, PairingCreationFailed, BrowserManagementFailed
}

public sealed class DesktopException(DesktopError error) : Exception(ErrorText.For(error))
{
    public DesktopError Error { get; } = error;
}

public static class ErrorText
{
    public static string For(DesktopError error) => error switch
    {
        DesktopError.RuntimeUnavailable => "Runtime unavailable：请先启动本机 Runtime。",
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
