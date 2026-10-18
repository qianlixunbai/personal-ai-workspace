using System.Threading;

namespace PersonalAiWorkspace.Desktop;

// Only the fixed protocol from an owned child can select a cause. No raw text is retained.
internal sealed class RuntimeStartupDiagnostic
{
    private string? failure;
    private int records;
    internal string? Failure => Volatile.Read(ref failure);
    internal void Accept(string line)
    {
        string? value = line switch
        {
            "WORKSPACE_STARTUP_V1\tNATIVE_ADMISSION_REJECTED" => "ADMISSION_DENIED",
            "WORKSPACE_STARTUP_V1\tPOSTGRESQL_NOT_CONFIGURED" => "CREDENTIAL_UNAVAILABLE",
            "WORKSPACE_STARTUP_V1\tCONFIGURATION_UNTRUSTED" => "CONFIGURATION_UNTRUSTED",
            "WORKSPACE_STARTUP_V1\tCREDENTIAL_UNAVAILABLE" => "CREDENTIAL_UNAVAILABLE",
            "WORKSPACE_STARTUP_V1\tCONNECTION_FAILED" => "DATABASE_CONNECTION_FAILED",
            "WORKSPACE_STARTUP_V1\tAUTHENTICATION_FAILED" => "DATABASE_AUTHENTICATION_FAILED",
            "WORKSPACE_STARTUP_V1\tSCHEMA_INITIALIZATION_REQUIRED" or "WORKSPACE_STARTUP_V1\tSCHEMA_INCOMPATIBLE"
                or "WORKSPACE_STARTUP_V1\tROLE_REJECTED" => "SCHEMA_NOT_READY",
            "WORKSPACE_STARTUP_V1\tRUNTIME_START_FAILED" => "STARTUP_CAUSE_UNAVAILABLE",
            _ => null
        };
        if (value is not null)
        {
            if (Interlocked.Increment(ref records) == 1) Volatile.Write(ref failure, value);
            else Volatile.Write(ref failure, "STARTUP_CAUSE_UNAVAILABLE");
        }
    }
}
