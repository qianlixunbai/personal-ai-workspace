using System.Text.Json;
using System.Runtime.InteropServices;

namespace PersonalAiWorkspace.NativeBootstrap;

internal static class Program
{
    internal const string Trust = "DEVELOPMENT PROTOTYPE — NOT PRODUCTION TRUSTED";

    private static int Main(string[] args)
    {
        try
        {
            if (!OperatingSystem.IsWindows() || RuntimeInformation.ProcessArchitecture != Architecture.X64)
                throw new Rejected("PLATFORM");
            if (args.Length < 2 || args[0] is not ("ProvisionTrustedNative" or "VerifyTrustedNative"
                    or "ProvisionProfileNative" or "VerifyProfileNative")
                || args.Length != (args[0].StartsWith("Provision", StringComparison.Ordinal) ? 3 : 2))
                throw new Rejected("ARGUMENTS");
            var result = TrustedNative.Run(args[0], args[1], args.Length == 3 ? args[2] : null);
            if (args[0] == "VerifyProfileNative")
                Console.WriteLine(result.JavaAdmission());
            else Console.WriteLine(JsonSerializer.Serialize(new
            {
                result.result, result.mode, result.trust, result.elevated, result.owner,
                result.protectedDacl, result.onlyOwnerFullControl, result.fileIdentity,
                result.length, result.sha256, result.machine
            }));
            return 0;
        }
        catch (Exception error)
        {
            // Native/path/account details never cross the diagnostic boundary.
            Console.WriteLine(JsonSerializer.Serialize(new
            {
                result = "STOP", trust = Trust,
                reason = error is Rejected rejected ? rejected.Reason : "UNEXPECTED",
                win32 = error is Rejected native ? native.Win32 : 0
            }));
            return 10;
        }
    }
}

internal sealed class Rejected(string reason, int win32 = 0) : Exception(reason)
{
    internal string Reason { get; } = reason;
    internal int Win32 { get; } = win32;
}
