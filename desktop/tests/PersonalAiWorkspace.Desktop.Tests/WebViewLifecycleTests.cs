#if DEBUG
using System.IO;
using System.Windows.Threading;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

[CollectionDefinition("WebView lifecycle console", DisableParallelization = true)]
public sealed class WebViewLifecycleCollection { }

[Collection("WebView lifecycle console")]
public sealed class WebViewLifecycleTests
{
    [Fact]
    public async Task ClosingAfterStartupDisposalDoesNotAccessDisposedControlOrClaimClearSuccess()
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            var dispatcher = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
            dispatcher.BeginInvoke(new Action(async () =>
            {
                var original = Console.Out;
                using var output = new StringWriter();
                // Missing assets fail before any Profile, browser, bridge or Runtime is created.
                var window = new MainWorkspaceWindow(null!, Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N")));
                try
                {
                    Console.SetOut(output);
                    await window.Host.InitializeAsync();
                    Assert.Equal("INITIALIZATION_FAILED", window.Host.StartupFailureCode);
                    Assert.Null(window.Host.UserDataFolder);
                    await window.Host.CloseAsync();
                    window.DisposeImmediately();
                    Assert.False(window.Host.CleanupPassed);
                    string log = output.ToString();
                    Assert.Contains("CLOSE_CLEAR_UNAVAILABLE_DISPOSED", log);
                    Assert.DoesNotContain("CLOSE_CLEAR_FAILED", log);
                    Assert.DoesNotContain("CLOSE_CLEAR_COMPLETED", log);
                    Assert.Equal(1, log.Split("\"code\":\"CONTROL_DISPOSE_START\"").Length - 1);
                    done.SetResult();
                }
                catch (Exception error) { done.SetException(error); }
                finally
                {
                    window.DisposeImmediately();
                    Console.SetOut(original);
                    dispatcher.InvokeShutdown();
                }
            }));
            Dispatcher.Run();
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;
        thread.Start();
        await done.Task.WaitAsync(TimeSpan.FromSeconds(10));
    }
}
#endif
