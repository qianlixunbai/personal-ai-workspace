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
    public void DaclDescriptorDoesNotSpecifyAnOwnerOrGroup()
    {
        var security = KnowledgeBackupWindow.CreateExportDacl(User);
        var descriptor = new RawSecurityDescriptor(security.GetSecurityDescriptorBinaryForm(), 0);
        Assert.Null(descriptor.Owner);
        Assert.Null(descriptor.Group);
        Assert.Null(descriptor.SystemAcl);
        Assert.True(security.AreAccessRulesProtected);
        Assert.True(security.AreAccessRulesCanonical);
        Assert.Single(descriptor.DiscretionaryAcl!.Cast<GenericAce>());
    }

    [Fact]
    public void OwnerMismatchIsRejectedWithoutChangingTheDescriptor()
    {
        var security = KnowledgeBackupWindow.CreateExportDacl(User);
        security.SetOwner(Other); // In-memory only; never assign another owner on disk.
        var before = security.GetSecurityDescriptorBinaryForm();
        Assert.Throws<IOException>(() => KnowledgeBackupWindow.RequireExportOwner(security, User));
        Assert.Equal(before, security.GetSecurityDescriptorBinaryForm());
        Assert.Throws<IOException>(() => KnowledgeBackupWindow.ValidateExportSecurity(security, User));
    }

    [Theory]
    [InlineData("unprotected")]
    [InlineData("foreign")]
    [InlineData("empty")]
    [InlineData("partial")]
    [InlineData("inheritOnly")]
    [InlineData("null")]
    [InlineData("noncanonical")]
    public void UnsafeDescriptorsFailClosedInMemory(string scenario)
    {
        var user = User;
        var security = KnowledgeBackupWindow.CreateExportDacl(user);
        security.SetOwner(user);
        if (scenario == "unprotected") security.SetAccessRuleProtection(false, false);
        if (scenario == "foreign") security.AddAccessRule(new(Other, FileSystemRights.Read, AccessControlType.Allow));
        if (scenario is "empty" or "partial" or "inheritOnly")
        {
            security.PurgeAccessRules(user);
            if (scenario == "partial") security.AddAccessRule(new(user, FileSystemRights.Read, AccessControlType.Allow));
        }
        if (scenario is "null" or "noncanonical" or "inheritOnly")
        {
            var raw = new RawSecurityDescriptor(security.GetSecurityDescriptorBinaryForm(), 0);
            if (scenario == "null") raw.DiscretionaryAcl = null;
            // FileSecurity.AddAccessRule disallows file inheritance flags. Construct the malformed
            // descriptor directly; import may normalize away an ineffective inherit-only ACE.
            else if (scenario == "inheritOnly") raw.DiscretionaryAcl!.InsertAce(0,
                new CommonAce(AceFlags.ObjectInherit | AceFlags.InheritOnly, AceQualifier.AccessAllowed,
                    (int)FileSystemRights.FullControl, user, false, null));
            else raw.DiscretionaryAcl!.InsertAce(1, new CommonAce(AceFlags.None, AceQualifier.AccessDenied,
                (int)FileSystemRights.Write, user, false, null));
            var bytes = new byte[raw.BinaryLength]; raw.GetBinaryForm(bytes, 0);
            security.SetSecurityDescriptorBinaryForm(bytes);
        }
        Assert.Throws<IOException>(() => KnowledgeBackupWindow.ValidateExportSecurity(security, user));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void PrivateProtectionWorksWithExclusiveWriterAndPreservesOwner(bool legacyPath)
    {
        using var fixture = new Files();
        using (var file = new FileStream(fixture.Destination, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            var owner = new FileInfo(fixture.Destination).GetAccessControl().GetOwner(typeof(SecurityIdentifier));
            if (legacyPath) KnowledgeBackupWindow.ProtectFile(fixture.Destination);
            else KnowledgeBackupWindow.ProtectFile(file);
            Assert.Equal(0, file.Length);
            Assert.Equal(owner, new FileInfo(fixture.Destination).GetAccessControl().GetOwner(typeof(SecurityIdentifier)));
            KnowledgeBackupWindow.ValidateExportSecurity(new FileInfo(fixture.Destination).GetAccessControl(), User);
            Assert.Throws<IOException>(() => File.Move(fixture.Destination, fixture.Destination + ".replaced"));
            file.Write(new byte[] { 1, 2, 3 });
        }
        Assert.Equal(new byte[] { 1, 2, 3 }, File.ReadAllBytes(fixture.Destination));
    }

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

    [Fact]
    public async Task FailedProtectionWritesNoContentAndCleansStaging()
    {
        using var fixture = new Files();
        bool called = false;
        var error = await Assert.ThrowsAsync<DesktopException>(() => NativeKnowledgeAnswerExport.SaveNewAsync(
            fixture.Destination, "must never be written", default, file =>
            {
                called = true;
                Assert.Equal(0, file.Length);
                var descriptor = KnowledgeBackupWindow.CreateExportDacl(User);
                descriptor.SetOwner(Other); // Synthetic mismatch, no owner reassignment on disk.
                KnowledgeBackupWindow.RequireExportOwner(descriptor, User);
            }));
        Assert.True(called);
        Assert.Equal(DesktopError.KnowledgeFileUnavailable, error.Error);
        Assert.Empty(Directory.GetFiles(fixture.Root));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CancellationBeforeOrAfterProtectionLeavesNoFiles(bool afterProtection)
    {
        using var fixture = new Files();
        using var cancellation = new CancellationTokenSource();
        if (!afterProtection) cancellation.Cancel();
        bool called = false;
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => NativeKnowledgeAnswerExport.SaveNewAsync(
            fixture.Destination, "must never be published", cancellation.Token, file =>
            {
                called = true;
                KnowledgeBackupWindow.ProtectFile(file);
                Assert.Equal(0, file.Length);
                cancellation.Cancel();
            }));
        Assert.Equal(afterProtection, called);
        Assert.Empty(Directory.GetFiles(fixture.Root));
    }

    [Fact]
    public async Task DestinationAppearingDuringSaveIsNotReplacedAndStagingIsCleaned()
    {
        using var fixture = new Files();
        await Assert.ThrowsAsync<DesktopException>(() => NativeKnowledgeAnswerExport.SaveNewAsync(
            fixture.Destination, "export content", default, file =>
            {
                KnowledgeBackupWindow.ProtectFile(file);
                File.WriteAllText(fixture.Destination, "synthetic competing document");
            }));
        Assert.Equal("synthetic competing document", File.ReadAllText(fixture.Destination));
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
