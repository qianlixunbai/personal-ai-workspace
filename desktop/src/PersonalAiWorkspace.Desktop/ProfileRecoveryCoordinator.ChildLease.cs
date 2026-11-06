#if MMF3_PROFILE_ACCEPTANCE && DEBUG
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace PersonalAiWorkspace.Desktop;

internal sealed partial class ProfileRecoveryCoordinator
{
    private bool exclusionTransferAttempted;

    // Resource handoff only, NOT maintenance admission or permission to send credentials.
    // The future native transport must pass the Process returned by its own pinned-JAR launch,
    // after that child completes the original Native Gate. Never reopen a PID from a record.
    internal long TransferRuntimeExclusion(Process ownedChild)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (authority is null || maintenanceRuntimeLock is null || exclusionTransferAttempted) throw Stop();
        authority.Validate();
        Mmf3ProfileNative.Verify(authority);
        exclusionTransferAttempted = true; // A lost response is not permission to duplicate again.
        return DuplicateExclusion(maintenanceRuntimeLock, ownedChild);
    }

    // Private primitive also exercised against a freshly created synthetic file/direct child.
    // DuplicateHandle preserves access and sharing on the same kernel file object. The parent
    // keeps its original; only child termination/child close releases the remote duplicate.
    private static long DuplicateExclusion(FileStream original, Process child)
    {
        if (!original.CanRead || !original.CanWrite || original.Length != 0 || child.HasExited) throw Stop();
        using var owner = Process.GetCurrentProcess();
        if (child.Id == owner.Id || child.SessionId != owner.SessionId) throw Stop();
        if (!DuplicateHandle(owner.SafeHandle, original.SafeFileHandle, child.SafeHandle,
            out IntPtr remote, 0, false, 2)) throw Stop(); // DUPLICATE_SAME_ACCESS, not inheritable.
        // This numeric value belongs to the already-opened child process, never this process.
        // It is sent only down that child's private stdin; no file/env/command-line token.
        return remote.ToInt64();
    }

    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DuplicateHandle(SafeProcessHandle sourceProcess, SafeFileHandle source,
        SafeProcessHandle targetProcess, out IntPtr target, uint access, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint options);
}
#endif
