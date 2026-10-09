#if MMF3_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using PersonalAiWorkspace.Core;

namespace PersonalAiWorkspace.Desktop;

// Native-only identity. No caller-provided endpoint, target or filesystem location.
internal sealed class Mmf3AcceptanceLaunch
{
    internal const string Label = "MMF3 TEST / 18765";
    private static readonly string[] RequiredDirectories = ["credentials", "model-state", "data",
        "webview2-profile", "chrome-profile", "browser-test-extension", "evidence", "logs"];
    internal string RunId { get; }
    internal string Root { get; }
    internal string Profile => Path.Combine(Root, "webview2-profile");
    internal string TokenFile => Path.Combine(Root, "credentials", "client-token");
    internal string CredentialTarget => $"PersonalAiWorkspace/Desktop/MMF3/{RunId}/Runtime/127.0.0.1:18765";
    internal string InstanceSuffix => ".MMF3." + RunId;

    private Mmf3AcceptanceLaunch(string runId)
    {
        RunId = runId;
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (!Path.IsPathFullyQualified(local) || local.StartsWith(@"\\", StringComparison.Ordinal))
            throw new IOException("Account-local MMF3 location required.");
        Root = Path.GetFullPath(Path.Combine(local, "PersonalAiWorkspace-Mmf3", runId));
    }

    internal static Mmf3AcceptanceLaunch Parse(string[] args)
    {
        if (args.Length != 2 || args[0] != "--mmf3-acceptance" || args[1].Length != 32)
            throw new IOException("Explicit MMF3 run identity required.");
        foreach (char c in args[1])
            if (c is not (>= '0' and <= '9') and not (>= 'a' and <= 'f'))
                throw new IOException("Invalid MMF3 run identity.");
        var launch = new Mmf3AcceptanceLaunch(args[1]);
        launch.Validate();
        return launch;
    }

    // Recheck at resource use as well as admission; never create or repair a directory.
    internal void Validate()
    {
        for (string? current = Root; current is not null; current = Path.GetDirectoryName(current))
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Linked MMF3 path rejected.");
        ValidatePrivateDirectory(Root);
        foreach (string name in RequiredDirectories) ValidatePrivateDirectory(Path.Combine(Root, name));
    }

    private static void ValidatePrivateDirectory(string path)
    {
        var attributes = File.GetAttributes(path);
        if ((attributes & FileAttributes.Directory) == 0 || (attributes & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Invalid MMF3 directory.");
        var user = WindowsIdentity.GetCurrent().User!;
        var security = new DirectoryInfo(path).GetAccessControl();
        if (!user.Equals(security.GetOwner(typeof(SecurityIdentifier))) || !security.AreAccessRulesProtected)
            throw new IOException("MMF3 directory owner/ACL mismatch.");
        bool fullControl = false;
        foreach (FileSystemAccessRule rule in security.GetAccessRules(true, true, typeof(SecurityIdentifier)))
        {
            if (rule.AccessControlType != AccessControlType.Allow || !user.Equals(rule.IdentityReference))
                throw new IOException("MMF3 directory is not account private.");
            if ((rule.PropagationFlags & PropagationFlags.InheritOnly) == 0
                && (rule.FileSystemRights & FileSystemRights.FullControl) == FileSystemRights.FullControl) fullControl = true;
        }
        if (!fullControl) throw new IOException("MMF3 directory permission missing.");
    }

    internal string ValidateTokenImport(string path)
    {
        try
        {
            // Require an absolute native-picker path; normalization cannot grant another source.
            if (!Path.IsPathFullyQualified(path)) throw new IOException();
            string normalized = Path.GetFullPath(path);
            if (!string.Equals(normalized, TokenFile, StringComparison.OrdinalIgnoreCase)) throw new IOException();
            Validate();
            return normalized;
        }
        catch (Exception) { throw new DesktopException(DesktopError.CredentialInvalid); }
    }
}
#endif
