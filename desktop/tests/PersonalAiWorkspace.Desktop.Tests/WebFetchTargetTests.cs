using PersonalAiWorkspace.Core;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

public sealed class WebFetchTargetTests
{
    [Fact] public void NativeRawTargetMatchesApprovedRuntimeContract()
    {
        var target = WebFetchTarget.Parse("https://Example.COM.:443");
        Assert.Equal("https://example.com/", target.Url); Assert.Equal("example.com", target.Hostname);
        foreach (var (input, output) in new[] {
            ("https://EXAMPLE.com.:443/a%2fb?x=1&x=2&q=a+b", "https://example.com/a%2fb?x=1&x=2&q=a+b"),
            ("https://example.com?", "https://example.com/?"),
            ("https://example.com/%2E%2e?q=[value]", "https://example.com/%2E%2e?q=[value]") })
            Assert.Equal(output, WebFetchTarget.Parse(input).Url);
        foreach (string bad in new[] { "http://example.com", "https://user@example.com", "https://example.com/#x",
            "https://例子.com", "https://example.com/界", "https://127.0.0.1", "https://[::1]", "https://2130706433",
            "https://0177.1", "https://host.0x7f", "https://host.local", "https://host.arpa", "https://example.com/a/../b",
            "https://example.com/./a", "https://example.com//a", "https://example.com/%zz", "https://example.com/%",
            "https://example.com\\a", "https://exa%6dple.com", "https://example.com:0443", "https://example.com..",
            "https://example.com/a b", "https://example.com/[a]", "https://example.com/" + new string('a', 2030) })
            Assert.Equal(DesktopError.WebTargetInvalid, Assert.Throws<DesktopException>(() => WebFetchTarget.Parse(bad)).Error);
        Assert.DoesNotContain("example.com", target.ToString());
    }
}
