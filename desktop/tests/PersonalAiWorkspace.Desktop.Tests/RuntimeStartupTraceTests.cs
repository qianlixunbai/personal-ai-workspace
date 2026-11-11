#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System.IO;
using System.Text;
using System.Text.Json;
using Xunit;

namespace PersonalAiWorkspace.Desktop.Tests;

// Reuse the existing nonparallel console collection. These tests never create a
// window, Profile, process, endpoint or log file; only the diagnostic object runs.
[Collection("WebView lifecycle console")]
public sealed class RuntimeStartupTraceTests
{
    [Fact]
    public void FirstFailureKeepsItsProtocolPhaseAndUncollapsedCause()
    {
        Capture(output =>
        {
            using var trace = new RuntimeStartupTrace();
            trace.At(RuntimeStartupPhase.ProtocolReady);
            trace.Failed(new NativeRuntimeSession.Failure("SCHEMA_NOT_READY", "ROLE_REJECTED"));
            string first = output.ToString();
            trace.At(RuntimeStartupPhase.RecordReady);
            trace.Failed(new IOException("later cleanup failure"));
            trace.Completed();

            Assert.Equal(first, output.ToString());
            var failure = Assert.Single(Records(output), IsFailure);
            Assert.Equal("ProtocolReady", failure.GetProperty("stage").GetString());
            Assert.Equal("REMOTE_STOP", failure.GetProperty("failureKind").GetString());
            Assert.Equal("ROLE_REJECTED", failure.GetProperty("rawCode").GetString());
        });
    }

    [Fact]
    public void UnknownRemoteTextAndLocalExceptionMessagesStayOutOfRecords()
    {
        const string secret = "private-token-and-user-text-should-never-be-logged";
        Capture(output =>
        {
            using (var remote = new RuntimeStartupTrace())
            {
                remote.At(RuntimeStartupPhase.ProtocolHeader);
                remote.Failed(new NativeRuntimeSession.Failure("STARTUP_CAUSE_UNAVAILABLE", "ROLE_REJECTED\n" + secret));
            }
            using (var local = new RuntimeStartupTrace())
            {
                local.At(RuntimeStartupPhase.RecordReady);
                local.Failed(new IOException(secret, new Exception(secret)));
            }

            var failures = Records(output).Where(IsFailure).ToArray();
            Assert.Equal(2, failures.Length);
            Assert.Equal("UNRECOGNIZED", failures[0].GetProperty("rawCode").GetString());
            Assert.Equal("IO_ERROR", failures[1].GetProperty("failureKind").GetString());
            Assert.Equal(JsonValueKind.Null, failures[1].GetProperty("rawCode").ValueKind);
            Assert.DoesNotContain(secret, output.ToString());
            Assert.DoesNotContain("ROLE_REJECTED", output.ToString());
        });
    }

    [Fact]
    public void CompletedStartupDoesNotTurnLaterCancellationIntoStartupFailure()
    {
        Capture(output =>
        {
            using var trace = new RuntimeStartupTrace();
            trace.At(RuntimeStartupPhase.PublishReady);
            trace.Completed();
            string ready = output.ToString();
            trace.Failed(new OperationCanceledException());
            trace.At(RuntimeStartupPhase.ProcessCheck);

            Assert.Equal(ready, output.ToString());
            Assert.DoesNotContain(Records(output), IsFailure);
            Assert.Single(Records(output), record => record.GetProperty("eventName").GetString() == "READY");
        });
    }

    [Fact]
    public void UnavailableConsoleDoesNotEscapeIntoTheOwnerLifecycle()
    {
        var original = Console.Out;
        using var unavailable = new UnavailableWriter();
        try
        {
            Console.SetOut(unavailable);
            using var failed = new RuntimeStartupTrace();
            failed.At(RuntimeStartupPhase.ProtocolHeader);
            failed.Failed(new EndOfStreamException());
            using var ready = new RuntimeStartupTrace();
            ready.At(RuntimeStartupPhase.PublishReady);
            ready.Completed();
        }
        finally { Console.SetOut(original); }
    }

    private static bool IsFailure(JsonElement record) => record.GetProperty("eventName").GetString() == "FIRST_FAILURE";

    private static JsonElement[] Records(StringWriter output) => output.ToString()
        .Split(Environment.NewLine, StringSplitOptions.RemoveEmptyEntries)
        .Select(line => { using var document = JsonDocument.Parse(line); return document.RootElement.Clone(); }).ToArray();

    private static void Capture(Action<StringWriter> action)
    {
        var original = Console.Out;
        using var output = new StringWriter();
        try { Console.SetOut(output); action(output); }
        finally { Console.SetOut(original); }
    }

    private sealed class UnavailableWriter : TextWriter
    {
        public override Encoding Encoding => Encoding.UTF8;
        public override void WriteLine(string? value) => throw new IOException("synthetic diagnostic sink failure");
    }
}
#endif
