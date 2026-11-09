#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using PersonalAiWorkspace.WindowsSecurity;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class ProfilePathBindingTests
{
    private const string Id = "0123456789abcdef0123456789abcdef";

    [Theory]
    [InlineData("C:\\safe\\PersonalAIWorkspace.Development\\Profiles\\0123456789abcdef0123456789abcdef")]
    [InlineData("D:\\cache\\PersonalAIWorkspace.Development\\Profiles\\0123456789abcdef0123456789abcdef")]
    public void CanonicalShapeAllowsLocalStorageWithoutHardcodingHost(string path)
        => WindowsProfileSecurity.ValidateProfileRoot(path, Id);

    [Theory]
    [InlineData("C:\\safe\\OtherApp\\Profiles\\0123456789abcdef0123456789abcdef")]
    [InlineData("C:\\safe\\PersonalAIWorkspace.Development\\Other\\0123456789abcdef0123456789abcdef")]
    [InlineData("C:\\safe\\PersonalAIWorkspace.Development\\Profiles\\ffffffffffffffffffffffffffffffff")]
    [InlineData("C:\\safe\\PersonalAIWorkspace.Development\\Profiles\\0123456789abcdef0123456789abcdef\\extra")]
    [InlineData("C:\\safe\\..\\PersonalAIWorkspace.Development\\Profiles\\0123456789abcdef0123456789abcdef")]
    [InlineData("\\\\server\\share\\PersonalAIWorkspace.Development\\Profiles\\0123456789abcdef0123456789abcdef")]
    [InlineData("\\\\?\\C:\\safe\\PersonalAIWorkspace.Development\\Profiles\\0123456789abcdef0123456789abcdef")]
    public void WrongOrUnnormalizedRootShapeFailsClosed(string path)
        => Assert.Throws<IOException>(() => WindowsProfileSecurity.ValidateProfileRoot(path, Id));

    [Fact]
    public void FullVolumeAnd128BitIdentityMustMatch()
    {
        var expected = new WindowsProfileSecurity.DirectoryIdentity(1, 2, 3);
        WindowsProfileSecurity.RequireSameIdentity(expected, expected);
        foreach (var other in new[] { new WindowsProfileSecurity.DirectoryIdentity(9, 2, 3),
            new WindowsProfileSecurity.DirectoryIdentity(1, 9, 3), new WindowsProfileSecurity.DirectoryIdentity(1, 2, 9) })
            Assert.Throws<IOException>(() => WindowsProfileSecurity.RequireSameIdentity(expected, other));
    }

    [Fact]
    public void FreshProfileBindsCreatesChildrenReopensAndAgreesWithNativeHelper()
    {
        // One owned, newly generated fixture. Retain it as failure evidence; never touch old profiles.
        string id = Guid.NewGuid().ToString("N");
        string logical = Path.Combine(WindowsProfileSecurity.Parent(), id);
        string? evidence = Environment.GetEnvironmentVariable("WORKSPACE_BINDING_TEST_EVIDENCE");
        void Record(string? canonical, bool passed) {
            if (evidence is not null) File.WriteAllText(evidence, JsonSerializer.Serialize(new { id, logical, canonical, passed, synthetic = true }));
        }
        Record(null, false);
        Assert.False(Path.Exists(logical));
        string root = WindowsProfileSecurity.State(id, true);
        Record(root, false);
        Assert.Throws<IOException>(() => WindowsProfileSecurity.State(id, true));
        using (var binding = WindowsProfileSecurity.BindRoot(id))
        {
            Assert.Equal(root, binding.Root, ignoreCase: true);
            Assert.Equal(root, WindowsProfileSecurity.Root(id), ignoreCase: true);
            Assert.Equal(root, WindowsProfileSecurity.State(id, false), ignoreCase: true);
            foreach (string child in WindowsProfileSecurity.Directories) Assert.True(Directory.Exists(Path.Combine(root, child)));
            Assert.ThrowsAny<IOException>(() => Directory.Move(root, root + "-unexpected"));
            using var children = WindowsProfileSecurity.HoldDirectories(Path.Combine(root, "data"));
            Assert.ThrowsAny<IOException>(() => Directory.Move(Path.Combine(root, "data"), Path.Combine(root, "data-moved")));
        }
        // The root is still valid after releasing and reacquiring both namespace chains.
        using var reopened = WindowsProfileSecurity.BindRoot(id);
        Assert.Equal(root, reopened.Root, ignoreCase: true);
        ProfileRecoveryCoordinator.RequireOrdinaryProfile(root);
        using var dataPins = WindowsProfileSecurity.HoldDirectories(Path.Combine(root, "data"));
        string helper = Path.Combine(AppContext.BaseDirectory, "NativeBootstrap", "PersonalAiWorkspace.NativeBootstrap.exe");
        var start = new ProcessStartInfo(helper) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        start.ArgumentList.Add("VerifyPreparedRecoveryTarget"); start.ArgumentList.Add(id);
        using var process = Process.Start(start)!;
        if (!process.WaitForExit(15000)) { process.Kill(entireProcessTree: true); process.WaitForExit(); Assert.Fail("Read-only native helper timed out."); }
        string output = process.StandardOutput.ReadToEnd().Trim();
        Assert.True(process.ExitCode == 0, output + process.StandardError.ReadToEnd());
        string[] fields = output.Split('\t');
        Assert.Equal("WINDOWS_PREPARED_RECOVERY_V1", fields[0]);
        Assert.Equal(root, Encoding.UTF8.GetString(Convert.FromBase64String(fields[3])), ignoreCase: true);
        Record(root, true);
    }
}
#endif
