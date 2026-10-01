using System.Text.RegularExpressions;

namespace PersonalAiWorkspace.Core;

public enum DesktopError
{
    RuntimeUnavailable, Unauthorized, CredentialMissing, CredentialInvalid, CredentialStorage,
    QueueFull, ProviderUnavailable, ModelUnavailable, PolicyDenied, InvalidRequest,
    InvalidResponse, TaskNotFound, Cancelled, TimedOut, ClientTimeout, ProviderResponseInvalid, InternalError
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
        DesktopError.InvalidRequest => "Invalid request：检查输入与目标语言；正文最多 4000 字符、5632 UTF-8 字节。",
        DesktopError.InvalidResponse => "Malformed Runtime response：响应不符合契约，已停止处理。",
        DesktopError.TaskNotFound => "Task not found：任务已过期或 Runtime 已重启。",
        DesktopError.Cancelled => "Cancelled：任务已取消；这不保证 GPU 立即停止。",
        DesktopError.TimedOut => "Timed out：任务超出 Runtime 时间预算。",
        DesktopError.ClientTimeout => "Runtime response timed out：通信超时，请检查 Runtime。",
        DesktopError.ProviderResponseInvalid => "Provider response invalid：本机模型响应无效。",
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
