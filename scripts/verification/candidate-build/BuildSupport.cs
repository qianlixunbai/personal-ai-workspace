// Build tooling only. Never reference or load a Workspace product assembly.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;

namespace WorkspaceCandidateBuild
{
    public static class ArtifactReader
    {
        public static string EmbeddedRuntimeManifest(string file)
        {
            using (var stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (var pe = new PEReader(stream))
            {
                if (!pe.HasMetadata || pe.PEHeaders.CorHeader == null) throw new IOException("Managed PE required");
                var metadata = pe.GetMetadataReader();
                var matches = metadata.ManifestResources.Select(h => metadata.GetManifestResource(h))
                    .Where(r => metadata.GetString(r.Name) == "TrustedDevelopmentRuntimeSources").ToArray();
                if (matches.Length != 1 || !matches[0].Implementation.IsNil)
                    throw new IOException("Exactly one embedded Runtime manifest required");
                var directory = pe.PEHeaders.CorHeader.ResourcesDirectory;
                var offset = checked((int)matches[0].Offset);
                if (directory.Size < 4 || offset < 0 || offset > directory.Size - 4)
                    throw new IOException("Invalid resource offset");
                var block = pe.GetSectionData(directory.RelativeVirtualAddress);
                if (directory.Size > block.Length) throw new IOException("Resource directory outside PE section");
                var reader = block.GetReader(offset, directory.Size - offset);
                var length = reader.ReadInt32();
                if (length <= 0 || length > 131072 || length > reader.RemainingBytes)
                    throw new IOException("Invalid resource length");
                return new UTF8Encoding(false, true).GetString(reader.ReadBytes(length));
            }
        }

        public static string GitBlobId(byte[] bytes)
        {
            using (var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA1))
            {
                sha.AppendData(Encoding.ASCII.GetBytes("blob " + bytes.Length + "\0"));
                sha.AppendData(bytes);
                return Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
            }
        }

        // Binary stdout preserves git's NUL records; arguments are never interpreted by a shell.
        public static int RunTool(string executable, string[] arguments, string cwd,
            string stdout, string stderr, string receiptPrefix, int timeoutSeconds)
        {
            var start = new ProcessStartInfo(executable) {
                WorkingDirectory = cwd, UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = true
            };
            foreach (var argument in arguments) start.ArgumentList.Add(argument);
            using (var output = new FileStream(stdout, FileMode.CreateNew, FileAccess.Write, FileShare.Read))
            using (var errors = new FileStream(stderr, FileMode.CreateNew, FileAccess.Write, FileShare.Read))
            using (var process = new Process { StartInfo = start })
            {
                if (!process.Start()) throw new IOException("Tool did not start");
                WriteReceipt(receiptPrefix + "-started.json", "{\"pid\":" + process.Id + "}");
                process.StandardInput.Close();
                var copyOut = process.StandardOutput.BaseStream.CopyToAsync(output);
                var copyErr = process.StandardError.BaseStream.CopyToAsync(errors);
                if (!process.WaitForExit(checked(timeoutSeconds * 1000)))
                {
                    // One cleanup authority: the guardian Job. No PID kill/fallback route.
                    throw new TimeoutException("Tool deadline exceeded; guardian must close its Job");
                }
                WriteReceipt(receiptPrefix + "-process-exited.json", "{\"pid\":" + process.Id + ",\"exitCode\":" + process.ExitCode + "}");
                if (!Task.WaitAll(new[] { copyOut, copyErr }, 10000))
                    throw new TimeoutException("Tool output drain incomplete");
                return process.ExitCode;
            }
        }
        private static void WriteReceipt(string file, string value)
        {
            using (var stream = new FileStream(file, FileMode.CreateNew, FileAccess.Write, FileShare.Read))
            using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
            { writer.Write(value); writer.Flush(); stream.Flush(true); }
        }
    }

    public sealed class OwnedBuildJob : IDisposable
    {
        private IntPtr job;
        public uint WorkerPid { get; private set; }
        public bool ForcedCleanup { get; private set; }
        public uint ActiveBeforeCleanup { get; private set; }
        public bool UnassignedCleanupConfirmed { get; private set; } = true;
        public OwnedBuildJob()
        {
            job = CreateJobObjectW(IntPtr.Zero, null);
            if (job == IntPtr.Zero) throw Error("CreateJobObject");
            var limits = new ExtendedLimits();
            limits.Basic.LimitFlags = 0x2000 | 0x100 | 0x200 | 0x8;
            limits.Basic.ActiveProcessLimit = 12;
            limits.ProcessMemoryLimit = new UIntPtr(768UL * 1024 * 1024);
            limits.JobMemoryLimit = new UIntPtr(2UL * 1024 * 1024 * 1024);
            if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf<ExtendedLimits>()))
            { Dispose(); throw Error("SetInformationJobObject"); }
        }

        public uint ActiveProcesses
        {
            get
            {
                Accounting accounting;
                if (!QueryInformationJobObject(job, 1, out accounting,
                    (uint)Marshal.SizeOf<Accounting>(), IntPtr.Zero)) throw Error("Query owned Job");
                return accounting.ActiveProcesses;
            }
        }

        private static IOException Error(string action) =>
            new IOException(action + " failed: " + Marshal.GetLastWin32Error());

        private static string Quote(string value)
        {
            var text = new StringBuilder("\""); int slashes = 0;
            foreach (char c in value)
            {
                if (c == '\\') { slashes++; continue; }
                if (c == '"') { text.Append('\\', slashes * 2 + 1).Append(c); slashes = 0; continue; }
                text.Append('\\', slashes).Append(c); slashes = 0;
            }
            return text.Append('\\', slashes * 2).Append('"').ToString();
        }

        public int RunWorker(string executable, string[] arguments, string cwd,
            string stdout, string stderr, string startedReceipt, int deadlineSeconds)
        {
            var security = new SecurityAttributes { Length = Marshal.SizeOf<SecurityAttributes>(), Inherit = true };
            var handles = new List<IntPtr>();
            IntPtr attributes = IntPtr.Zero, handleList = IntPtr.Zero;
            bool attributesInitialized = false;
            var process = new ProcessInformation(); bool assigned = false;
            try
            {
                foreach (var file in new[] { stdout, stderr })
                {
                    var handle = CreateFileW(file, 0x40000000, 1, ref security, 1, 0x80, IntPtr.Zero);
                    if (handle == new IntPtr(-1)) throw Error("Create exclusive worker log");
                    handles.Add(handle);
                }
                var input = CreateFileW("NUL", 0x80000000, 3, ref security, 3, 0x80, IntPtr.Zero);
                if (input == new IntPtr(-1)) throw Error("Open empty stdin");
                handles.Add(input);
                IntPtr size = IntPtr.Zero;
                InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref size);
                attributes = Marshal.AllocHGlobal(size);
                if (!InitializeProcThreadAttributeList(attributes, 1, 0, ref size)) throw Error("Initialize handle list");
                attributesInitialized = true;
                handleList = Marshal.AllocHGlobal(IntPtr.Size * handles.Count);
                Marshal.Copy(handles.ToArray(), 0, handleList, handles.Count);
                if (!UpdateProcThreadAttribute(attributes, 0, new IntPtr(0x20002), handleList,
                    new IntPtr(IntPtr.Size * handles.Count), IntPtr.Zero, IntPtr.Zero)) throw Error("Restrict inherited handles");
                var startup = new StartupInfoEx();
                startup.Startup.Size = Marshal.SizeOf<StartupInfoEx>();
                startup.Startup.Flags = 0x100;
                startup.Startup.StdOutput = handles[0]; startup.Startup.StdError = handles[1]; startup.Startup.StdInput = input;
                startup.Attributes = attributes;
                var command = new StringBuilder(Quote(executable));
                foreach (var argument in arguments) command.Append(' ').Append(Quote(argument));
                // No elevation, breakaway, different token or desktop. Never run before assignment.
                if (!CreateProcessW(executable, command, IntPtr.Zero, IntPtr.Zero, true,
                    0x4 | 0x80000 | 0x08000000, IntPtr.Zero, cwd, ref startup, out process))
                    throw Error("Create suspended build worker");
                WorkerPid = process.ProcessId;
                if (!AssignProcessToJobObject(job, process.Process)) throw Error("Assign build Job");
                assigned = true;
                using (var receipt = new FileStream(startedReceipt, FileMode.CreateNew, FileAccess.Write, FileShare.Read))
                using (var writer = new StreamWriter(receipt, new UTF8Encoding(false)))
                {
                    writer.Write("{\"workerPid\":" + WorkerPid + ",\"assignedBeforeResume\":true}");
                    writer.Flush(); receipt.Flush(true);
                }
                if (ResumeThread(process.Thread) == uint.MaxValue) throw Error("Resume owned worker");
                uint waited = WaitForSingleObject(process.Process, checked((uint)deadlineSeconds * 1000));
                if (waited == 258) throw new TimeoutException("Build guardian deadline exceeded");
                if (waited != 0) throw Error("Wait owned worker");
                uint code;
                if (!GetExitCodeProcess(process.Process, out code)) throw Error("Read owned worker exit");
                return unchecked((int)code);
            }
            finally
            {
                // Includes assignment failure: that exact suspended child has not run.
                bool stopFailed = false;
                if (!assigned && process.Process != IntPtr.Zero)
                    stopFailed = !TerminateProcess(process.Process, 125) || WaitForSingleObject(process.Process, 5000) != 0;
                UnassignedCleanupConfirmed = !stopFailed;
                if (process.Thread != IntPtr.Zero) CloseHandle(process.Thread);
                if (process.Process != IntPtr.Zero) CloseHandle(process.Process);
                foreach (var handle in handles) CloseHandle(handle);
                if (attributesInitialized) DeleteProcThreadAttributeList(attributes);
                if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes);
                if (handleList != IntPtr.Zero) Marshal.FreeHGlobal(handleList);
                if (stopFailed) throw new IOException("Cleanup of exact unassigned child is unconfirmed");
            }
        }

        public void Finish()
        {
            ActiveBeforeCleanup = ActiveProcesses;
            if (ActiveBeforeCleanup == 0) return;
            ForcedCleanup = true;
            if (!TerminateJobObject(job, 124)) throw Error("Stop owned build Job");
            var watch = Stopwatch.StartNew();
            while (ActiveProcesses != 0 && watch.Elapsed.TotalSeconds < 10) System.Threading.Thread.Sleep(50);
            if (ActiveProcesses != 0) throw new IOException("Owned build Job cleanup unconfirmed");
        }
        public void Dispose() { if (job != IntPtr.Zero) { CloseHandle(job); job = IntPtr.Zero; } }

        [StructLayout(LayoutKind.Sequential)] private struct SecurityAttributes { internal int Length; internal IntPtr Descriptor; [MarshalAs(UnmanagedType.Bool)] internal bool Inherit; }
        [StructLayout(LayoutKind.Sequential)] private struct BasicLimits { internal long ProcessTime, JobTime; internal uint LimitFlags; internal UIntPtr MinimumWorkingSet, MaximumWorkingSet; internal uint ActiveProcessLimit; internal UIntPtr Affinity; internal uint PriorityClass, SchedulingClass; }
        [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits { internal BasicLimits Basic; internal ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes; internal UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed; }
        [StructLayout(LayoutKind.Sequential)] private struct Accounting { internal long UserTime, KernelTime, PeriodUserTime, PeriodKernelTime; internal uint PageFaultCount, TotalProcesses, ActiveProcesses, TerminatedProcesses; }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct StartupInfo { internal int Size; internal string Reserved, Desktop, Title; internal int X, Y, XSize, YSize, XChars, YChars, FillAttribute, Flags; internal short ShowWindow, ReservedSize; internal IntPtr ReservedData, StdInput, StdOutput, StdError; }
        [StructLayout(LayoutKind.Sequential)] private struct StartupInfoEx { internal StartupInfo Startup; internal IntPtr Attributes; }
        [StructLayout(LayoutKind.Sequential)] private struct ProcessInformation { internal IntPtr Process, Thread; internal uint ProcessId, ThreadId; }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetInformationJobObject(IntPtr job, int kind, ref ExtendedLimits info, uint size);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool QueryInformationJobObject(IntPtr job, int kind, out Accounting info, uint size, IntPtr returned);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateJobObject(IntPtr job, uint code);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateProcess(IntPtr process, uint code);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr CreateFileW(string path, uint access, uint share, ref SecurityAttributes attributes, uint disposition, uint flags, IntPtr template);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool CreateProcessW(string application, StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity, bool inherit, uint flags, IntPtr environment, string cwd, ref StartupInfoEx startup, out ProcessInformation process);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, uint flags, ref IntPtr size);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attribute, IntPtr value, IntPtr size, IntPtr previous, IntPtr returned);
        [DllImport("kernel32.dll")] private static extern void DeleteProcThreadAttributeList(IntPtr list);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetExitCodeProcess(IntPtr process, out uint code);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
    }
}
