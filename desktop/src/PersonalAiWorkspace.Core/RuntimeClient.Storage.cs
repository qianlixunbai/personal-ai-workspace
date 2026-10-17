using System.Net;
using System.Text.Json;

namespace PersonalAiWorkspace.Core;

public sealed record StorageStatus(string CheckedAt,string Backend,string ConfigurationTrust,bool PostgresqlConfigured,string Connection,
    string WorkspaceSchema,string KnowledgeSchema,bool FinanceEnabled,string FinanceSchema,string Migration,
    string EncryptedBackup,string Restore,string[] SecurityRequirements,string ErrorCode);

public sealed partial class RuntimeClient
{
    public async Task<StorageStatus> GetStorageStatusAsync(CancellationToken token)
    {
        using var body=await SendAsync(HttpMethod.Get,"/api/v1/storage/status",null,true,HttpStatusCode.OK,token,
            maximumResponse:8192,requestTimeout:TimeSpan.FromSeconds(20));
        var r=body.RootElement;
        MemoryFields(r,"checkedAt","backend","configurationTrust","postgresqlConfigured","connection","workspaceSchema","knowledgeSchema",
            "financeEnabled","financeSchema","migration","encryptedBackup","restore","securityRequirements","errorCode");
        string Choice(string field,params string[] allowed) {string value=String(r,field);if(!allowed.Contains(value,StringComparer.Ordinal))throw Invalid();return value;}
        bool Flag(string field) {var value=Property(r,field);if(value.ValueKind is not (JsonValueKind.True or JsonValueKind.False))throw Invalid();return value.GetBoolean();}
        string time=String(r,"checkedAt");if(!DateTimeOffset.TryParse(time,System.Globalization.CultureInfo.InvariantCulture,System.Globalization.DateTimeStyles.RoundtripKind,out _))throw Invalid();
        string backend=Choice("backend","SQLITE","POSTGRESQL","UNSUPPORTED");
        string trust=Choice("configurationTrust","TRUSTED_LOCAL","NOT_CHECKED");
        if(trust=="TRUSTED_LOCAL"&&(backend!="POSTGRESQL"||!Flag("postgresqlConfigured")))throw Invalid();
        string connection=Choice("connection","NOT_CONFIGURED","CONFIGURATION_INVALID","CONNECTED","CONNECTION_FAILED","AUTHENTICATION_FAILED","ACCESS_DENIED","ROLE_REJECTED");
        string workspace=Choice("workspaceSchema","NOT_CHECKED","INITIALIZATION_REQUIRED","INCOMPATIBLE","ACCESS_DENIED","V1_READY","V1_V2_READY");
        string knowledge=Choice("knowledgeSchema","NOT_CHECKED","INITIALIZATION_REQUIRED","INCOMPATIBLE","ACCESS_DENIED","READY");
        string finance=Choice("financeSchema","DISABLED","NOT_CONFIGURED","NOT_CHECKED","READY","INITIALIZATION_REQUIRED","INCOMPATIBLE","ACCESS_DENIED","CONNECTION_FAILED","AUTHENTICATION_FAILED","ROLE_REJECTED");
        string migration=Choice("migration","EXPLICIT_APPROVAL_REQUIRED"),backup=Choice("encryptedBackup","MAINTENANCE_ONLY"),restore=Choice("restore","BLOCKED_SECURITY_REQUIREMENTS");
        string error=Choice("errorCode","NONE","CONFIGURATION_INCOMPLETE","CONFIGURATION_INVALID","CONNECTION_FAILED","AUTHENTICATION_FAILED","PERMISSION_DENIED","SCHEMA_INCOMPATIBLE","ROLE_REJECTED");
        var requirements=Property(r,"securityRequirements");
        string[] expected=["WINDOWS_PROFILE_APPROVAL_REQUIRED","VOLUME_PROTECTION_REQUIRED","FINANCE_IDENTITY_CONFIRMATION_REQUIRED","CROSS_DEVICE_VERIFICATION_REQUIRED"];
        if(requirements.ValueKind!=JsonValueKind.Array||requirements.GetArrayLength()!=expected.Length)throw Invalid();
        var values=requirements.EnumerateArray().Select(x=>x.ValueKind==JsonValueKind.String?x.GetString()!:throw Invalid()).ToArray();
        if(!values.SequenceEqual(expected,StringComparer.Ordinal)||knowledge=="READY"&&workspace!="V1_V2_READY"||!Flag("financeEnabled")&&finance!="DISABLED")throw Invalid();
        return new(time,backend,trust,Flag("postgresqlConfigured"),connection,workspace,knowledge,Flag("financeEnabled"),finance,migration,backup,restore,values,error);
    }
}
