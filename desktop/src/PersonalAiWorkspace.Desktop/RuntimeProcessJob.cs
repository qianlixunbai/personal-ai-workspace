using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace PersonalAiWorkspace.Desktop;

// Unnamed, process-local job: closing its sole handle also covers abrupt Desktop termination.
internal sealed class RuntimeProcessJob : SafeHandleZeroOrMinusOneIsInvalid
{
    private RuntimeProcessJob() : base(true) { }
    internal static RuntimeProcessJob Create()
    {
        var job = CreateJobObjectW(IntPtr.Zero, null);
        if (job.IsInvalid) { job.Dispose(); throw new Win32Exception(); }
        var limits = new ExtendedLimits(); limits.Basic.LimitFlags = 0x2000; // KILL_ON_JOB_CLOSE
        if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf<ExtendedLimits>()))
        { job.Dispose(); throw new Win32Exception(); }
        return job;
    }
    internal void Assign(Process process)
    {
        if (!AssignProcessToJobObject(this, process.SafeHandle)) throw new Win32Exception();
    }
    protected override bool ReleaseHandle() => CloseHandle(handle);
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits
    {
        internal long ProcessTime, JobTime; internal uint LimitFlags;
        internal UIntPtr MinimumWorkingSet, MaximumWorkingSet; internal uint ActiveProcessLimit;
        internal UIntPtr Affinity; internal uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits
    {
        internal BasicLimits Basic;
        internal ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
        internal UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern RuntimeProcessJob CreateJobObjectW(IntPtr attributes, string? name);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetInformationJobObject(RuntimeProcessJob job, int kind, ref ExtendedLimits limits, uint length);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool AssignProcessToJobObject(RuntimeProcessJob job, SafeProcessHandle process);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
