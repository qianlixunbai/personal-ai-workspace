#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.IO;
using System.Text.Json;
using System.Threading;

namespace PersonalAiWorkspace.Desktop;

internal enum RuntimeStartupPhase
{
    NotStarted, SourceValidation, StartInfo, JobCreation, AttemptRegistration, ProcessStart,
    JobAssignment, ChildRegistration, ProtocolAttempt, ProtocolHeader, ProtocolProfile,
    ProtocolPid, ProtocolChallenge, ProtocolReply, ProtocolReady, ProtocolReadyAttempt,
    ProtocolReadyChallenge, ProtocolPort, RecordReady, BindEndpoint, HealthCheck,
    CredentialCheck, ProcessCheck, PublishReady, Ready
}

// Diagnostic output only: never a launch receipt, admission input or shutdown decision.
// No protocol bytes, exception text, profile identity, paths or credentials are serialized.
internal sealed class RuntimeStartupTrace : IDisposable
{
    private StreamWriter? writer;
    private RuntimeStartupPhase phase;
    private int sequence;
    private int firstFailure;
    private int? runtimePid;
    private bool completed;

    internal void OpenLog(string admittedRoot)
    {
        try
        {
            // Called only after the existing StartInfo/Profile/native validation succeeds.
            // Do not create directories, repair permissions or touch the strict attempt journal.
            writer = new StreamWriter(new FileStream(Path.Combine(admittedRoot, "logs",
                $"runtime-startup-{Guid.NewGuid():N}.jsonl"), FileMode.CreateNew, FileAccess.Write, FileShare.Read));
            writer.AutoFlush = true;
            Write("LOG_OPENED");
        }
        catch (Exception) { Dispose(); } // Diagnostic I/O must not change the product outcome.
    }

    internal void At(RuntimeStartupPhase value)
    {
        if (completed || Volatile.Read(ref firstFailure) != 0) return;
        phase = value;
        Write("STAGE");
    }

    internal void ChildStarted(int pid) => runtimePid = pid;

    internal void Failed(Exception error)
    {
        if (completed || Interlocked.CompareExchange(ref firstFailure, 1, 0) != 0) return;
        string kind = error switch
        {
            NativeRuntimeSession.Failure => "REMOTE_STOP",
            OperationCanceledException => "CANCELLED",
            EndOfStreamException => "END_OF_STREAM",
            IOException => "IO_ERROR",
            PersonalAiWorkspace.Core.DesktopException => "DESKTOP_ERROR",
            _ => "UNEXPECTED_ERROR"
        };
        Write("FIRST_FAILURE", kind, error is NativeRuntimeSession.Failure remote ? remote.RawCode : null, error.HResult);
    }

    internal void Completed()
    {
        if (Volatile.Read(ref firstFailure) != 0) return;
        phase = RuntimeStartupPhase.Ready;
        Write("READY");
        completed = true; // A later ordinary stop is not a startup failure.
        Dispose();
    }

    private void Write(string eventName, string? failureKind = null, string? rawCode = null, int? hresult = null)
    {
        try
        {
            if (sequence >= 40) return;
            string record = JsonSerializer.Serialize(new {
                contract = "WORKSPACE_RUNTIME_STARTUP_V1", stage = phase.ToString(), eventName,
                failureKind, rawCode, hresult, desktopPid = Environment.ProcessId, runtimePid,
                sequence = ++sequence, utc = DateTime.UtcNow });
            try { Console.WriteLine(record); } catch (Exception) { }
            try { writer?.WriteLine(record); } catch (Exception) { }
        }
        catch (Exception) { }
    }

    public void Dispose()
    {
        try { writer?.Dispose(); } catch (Exception) { }
        writer = null;
    }
}
#endif
