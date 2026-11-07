#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace PersonalAiWorkspace.Desktop;

// A live, single-owner observation of one newly launched dedicated process tree. This does
// NOT cover shared/remote providers, old executions, or durable guards. No PID reopen, JSON
// receipt, or personal maintenance factory can construct completion from historical metadata.
internal sealed class RecoveryOwnedModelExecution : IDisposable
{
    private readonly object sync = new();
    private readonly Guid operation;
    private readonly RuntimeProcessJob job;
    private readonly SafeProcessHandle root;
    private readonly FileStream executablePin;
    private bool disposed, consumed;
    private RecoveryOwnedModelExecution(Guid operation, RuntimeProcessJob job, SafeProcessHandle root, FileStream pin)
    { this.operation = operation; this.job = job; this.root = root; executablePin = pin; }

    // The acceptance caller supplies only its reviewed synthetic executable. A product
    // provider integration must pin its entire trusted artifact and bind Profile/request IDs.
    private static RecoveryOwnedModelExecution StartSynthetic(Guid operation, string executable, string[] arguments)
    {
        if (operation == Guid.Empty || !Path.IsPathFullyQualified(executable) || arguments.Any(a => a.Contains('\0'))) throw new IOException();
        var pin = new FileStream(executable, FileMode.Open, FileAccess.Read, FileShare.Read);
        RuntimeProcessJob? job = null; SafeProcessHandle? process = null;
        try
        {
            job = RuntimeProcessJob.Create();
            var startup = new StartupInfo { Size = (uint)Marshal.SizeOf<StartupInfo>() };
            string command = string.Join(" ", new[] { executable }.Concat(arguments).Select(Quote));
            if (!CreateProcessW(executable, new StringBuilder(command), IntPtr.Zero, IntPtr.Zero, false,
                0x00000004 | 0x08000000, IntPtr.Zero, Path.GetDirectoryName(executable), ref startup, out var created)) throw new Win32Exception();
            process = new SafeProcessHandle(created.Process, ownsHandle: true);
            using var thread = new SafeWaitHandle(created.Thread, ownsHandle: true);
            // CREATE_SUSPENDED closes the spawn-before-assignment race. The job is unnamed,
            // not inherited, cannot break away, and has no API that adds another root later.
            job.Assign(process);
            if (ResumeThread(thread) == uint.MaxValue) throw new Win32Exception();
            return new RecoveryOwnedModelExecution(operation, job, process, pin);
        }
        catch
        {
            // Only our actual returned handle: failed launch cleanup is never completion.
            if (process is not null && !process.IsInvalid) { TerminateProcess(process, 10); WaitForSingleObject(process, 5000); }
            job?.Dispose(); process?.Dispose(); pin.Dispose(); throw;
        }
    }

    internal bool RootExited
    {
        get { lock (sync) { ObjectDisposedException.ThrowIf(disposed, this); return KernelExited(); } }
    }
    private bool KernelExited()
    {
        uint wait = WaitForSingleObject(root, 0);
        if (wait == 0) return true;
        if (wait == 258) return false;
        throw new Win32Exception();
    }
    internal bool TryConsumeCompletedTree(Guid expectedOperation)
    {
        lock (sync)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (consumed || expectedOperation != operation) throw new IOException();
            if (!KernelExited() || job.ActiveProcessCount != 0) return false;
            if (!GetExitCodeProcess(root, out uint code) || code != 0) throw new IOException();
            consumed = true;
            return true; // Valid only to this live owner; cannot become a personal source receipt.
        }
    }
    public void Dispose()
    {
        lock (sync)
        {
            if (disposed) return;
            disposed = true; consumed = true; // Cleanup/owner death can never yield completion.
            job.Dispose(); root.Dispose(); executablePin.Dispose();
        }
    }
    private static string Quote(string value)
    {
        var quoted = new StringBuilder("\""); int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') { quoted.Append('\\', slashes * 2 + 1).Append(c); slashes = 0; continue; }
            quoted.Append('\\', slashes).Append(c); slashes = 0;
        }
        return quoted.Append('\\', slashes * 2).Append('"').ToString();
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct StartupInfo
    {
        internal uint Size; internal IntPtr Reserved, Desktop, Title;
        internal uint X, Y, XSize, YSize, XChars, YChars, FillAttribute, Flags;
        internal ushort ShowWindow, ReservedSize; internal IntPtr ReservedBytes, Input, Output, Error;
    }
    [StructLayout(LayoutKind.Sequential)] private struct ProcessInfo
    { internal IntPtr Process, Thread; internal uint ProcessId, ThreadId; }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    private static extern bool CreateProcessW(string application, StringBuilder command, IntPtr processAttributes,
        IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string? directory,
        ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint ResumeThread(SafeWaitHandle thread);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint WaitForSingleObject(SafeProcessHandle process, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetExitCodeProcess(SafeProcessHandle process, out uint code);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateProcess(SafeProcessHandle process, uint code);
}
#endif
