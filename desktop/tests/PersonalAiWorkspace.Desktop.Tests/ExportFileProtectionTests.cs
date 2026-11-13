using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
using System.Security.AccessControl;
using System.Security.Principal;
using PersonalAiWorkspace.Core;
using Xunit;
using Xunit.Abstractions;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class ExportFileProtectionTests(ITestOutputHelper output)
{
    private const string TestDirectoryVariable = "WORKSPACE_EXPORT_TEST_DIRECTORY";
    private static SecurityIdentifier User => WindowsIdentity.GetCurrent().User!;
    private static readonly SecurityIdentifier Other = new("S-1-5-21-1-2-3-1001");





    [Fact]
    public void ProtectionDoesNotRequireWriteOwnerAccess()
    {
        using var fixture = new Files();
        using var file = new FileStream(fixture.Destination, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        var ownerBefore = new FileInfo(fixture.Destination).GetAccessControl().GetOwner(typeof(SecurityIdentifier));
        bool configuredRegression = !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(TestDirectoryVariable));
        // Probe only a metadata access right on this newly created synthetic file. Never assign an
        // owner, modify its ACL, elevate privileges, or touch an existing file to induce a denial.
        using var ownerHandle = OpenOwnerAccessProbe(fixture.Destination, 0x00080000, 1 | 2,
            IntPtr.Zero, 3, 0x00200000, IntPtr.Zero); // WRITE_OWNER, OPEN_EXISTING, OPEN_REPARSE_POINT
        int ownerError = ownerHandle.IsInvalid ? Marshal.GetLastWin32Error() : 0;
        output.WriteLine("WRITE_OWNER metadata open: {0}; Win32={1}; configured regression directory={2}",
            ownerHandle.IsInvalid ? "denied" : "allowed", ownerError, configuredRegression);
        if (configuredRegression)
        {
            Assert.True(ownerHandle.IsInvalid, "The configured regression directory must reproduce denied WRITE_OWNER access.");
            Assert.Equal(5, ownerError); // ERROR_ACCESS_DENIED, not an unrelated probe failure.
        }
        else output.WriteLine("Default temp fixture: WRITE_OWNER denial is not required; only protection/owner preservation is asserted.");
        KnowledgeBackupWindow.ProtectFile(file);
        var actual = new FileInfo(fixture.Destination).GetAccessControl();
        Assert.Equal(ownerBefore, actual.GetOwner(typeof(SecurityIdentifier)));
        KnowledgeBackupWindow.ValidateExportSecurity(actual, User);
        Assert.Equal(0, file.Length);
        output.WriteLine("DACL-only protection succeeded; owner unchanged; no content written.");
    }

    [DllImport("kernel32.dll", EntryPoint = "CreateFileW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    private static extern SafeFileHandle OpenOwnerAccessProbe(string path, uint access, uint share, IntPtr attributes, uint disposition, uint flags, IntPtr template);

    [Fact]
    public async Task SuccessfulSaveRetainsPrivateDaclAndNeverOverwrites()
    {
        using var fixture = new Files();
        const string text = "Synthetic private export \u4e2d\u6587\n";
        await NativeKnowledgeAnswerExport.SaveNewAsync(fixture.Destination, text, default);
        Assert.Equal(text, File.ReadAllText(fixture.Destination));
        KnowledgeBackupWindow.ValidateExportSecurity(new FileInfo(fixture.Destination).GetAccessControl(), User);
        var error = await Assert.ThrowsAsync<DesktopException>(() => NativeKnowledgeAnswerExport.SaveNewAsync(fixture.Destination, "replacement", default));
        Assert.Equal(DesktopError.KnowledgeFileUnavailable, error.Error);
        Assert.Equal(text, File.ReadAllText(fixture.Destination));
        Assert.Single(Directory.GetFiles(fixture.Root));
    }




    private sealed class Files : IDisposable
    {
        internal string Root { get; }
        internal string Destination => Path.Combine(Root, "export.md");
        internal Files()
        {
            string? configured = Environment.GetEnvironmentVariable(TestDirectoryVariable);
            string parent = string.IsNullOrWhiteSpace(configured) ? Path.GetTempPath() : configured;
            if (!Path.IsPathFullyQualified(parent) || !Directory.Exists(parent))
                throw new InvalidOperationException(TestDirectoryVariable + " must name an existing absolute test directory.");
            Root = Path.Combine(parent, "export-protection-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(Root);
        }
        public void Dispose() => Directory.Delete(Root, recursive: true);
    }
}
