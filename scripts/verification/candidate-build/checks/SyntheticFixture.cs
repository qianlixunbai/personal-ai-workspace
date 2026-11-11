// Non-product PE bytes and independent Job harness only. Never load fixture assemblies.
using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Text;
using WorkspaceCandidateBuild;

namespace WorkspaceCandidateChecks
{
    public static class SyntheticFixture
    {
        public static void WritePe(string path, string mode)
        {
            var metadata = new MetadataBuilder();
            metadata.AddModule(0, metadata.GetOrAddString("Synthetic"), metadata.GetOrAddGuid(Guid.NewGuid()), default, default);
            metadata.AddAssembly(metadata.GetOrAddString("Synthetic"), new Version(1, 0), default, default, 0, AssemblyHashAlgorithm.None);
            metadata.AddTypeDefinition(TypeAttributes.NotPublic, default, metadata.GetOrAddString("<Module>"), default,
                MetadataTokens.FieldDefinitionHandle(1), MetadataTokens.MethodDefinitionHandle(1));
            EntityHandle implementation = default;
            if (mode == "external") implementation = metadata.AddAssemblyFile(metadata.GetOrAddString("never-open.fixture"), default, false);
            uint offset = mode == "offset" ? uint.MaxValue : 0;
            var name = metadata.GetOrAddString("TrustedDevelopmentRuntimeSources");
            if (mode != "missing") metadata.AddManifestResource(ManifestResourceAttributes.Public, name, implementation, offset);
            if (mode == "duplicate") metadata.AddManifestResource(ManifestResourceAttributes.Public, name, default, 0);
            byte[] data = mode == "utf8" ? new byte[] { 0xc3, 0x28 } : Encoding.UTF8.GetBytes("{\"synthetic\":true}");
            int length = mode == "negative" ? -1 : mode == "huge" ? 131073 : mode == "truncated" ? 900 : data.Length;
            var resources = new BlobBuilder(); resources.WriteInt32(length); resources.WriteBytes(data);
            var builder = new ManagedPEBuilder(new PEHeaderBuilder(imageCharacteristics: Characteristics.ExecutableImage | Characteristics.Dll),
                new MetadataRootBuilder(metadata), new BlobBuilder(), managedResources: resources, flags: CorFlags.ILOnly);
            var blob = new BlobBuilder(); builder.Serialize(blob); var bytes = blob.ToArray();
            if (mode == "directory") {
                using (var reader = new PEReader(new MemoryStream(bytes, false)))
                    BitConverter.GetBytes(int.MaxValue).CopyTo(bytes, reader.PEHeaders.CorHeaderStartOffset + 28);
            }
            using (var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write)) file.Write(bytes);
        }

        public static Dictionary<string, object> CheckJob(string host, string childScript, string root, string mode)
        {
            // Limit only processes we create: no global PID lookup, port probe or cleanup.
            var job = new OwnedBuildJob(3, 256, 512);
            var field = typeof(OwnedBuildJob).GetField("job", BindingFlags.Instance | BindingFlags.NonPublic);
            IntPtr saved = IntPtr.Zero; bool replaced = false;
            string failure = null; int? code = null; bool cleaned = false; uint active = uint.MaxValue;
            try {
                if (mode == "assignment") {
                    // Deterministic native AssignProcessToJobObject failure with an invalid
                    // Job handle. Keep the real handle and restore it before cleanup.
                    // This never substitutes or targets another process or an existing Job.
                    saved = (IntPtr)field.GetValue(job); field.SetValue(job, new IntPtr(-1)); replaced = true;
                }
                string childMode = mode == "assignment" ? "Normal" : mode;
                code = job.RunWorker(host, new[] { "-NoLogo", "-NoProfile", "-NonInteractive", "-File", childScript,
                    "-Mode", childMode, "-Root", root }, root, Path.Combine(root, mode + ".stdout"),
                    Path.Combine(root, mode + ".stderr"), Path.Combine(root, mode + "-started.json"), mode == "Timeout" ? 1 : 8);
            } catch (Exception error) { failure = error.Message; }
            finally {
                if (replaced) field.SetValue(job, saved);
                try { job.Finish(); active = job.ActiveProcesses; cleaned = active == 0 && job.UnassignedCleanupConfirmed && job.AuxiliaryHandlesClosed; }
                finally { job.Dispose(); }
            }
            bool expected = mode == "assignment"
                ? failure != null && failure.StartsWith("Assign build Job failed:") && job.AssignmentAttempted && !job.AssignmentConfirmed && !job.WorkerResumed && job.UnassignedCleanupConfirmed
                : mode == "Timeout" ? failure != null && failure.Contains("deadline exceeded") && job.ForcedCleanup
                : failure == null && code == 0 && job.AssignmentConfirmed && job.WorkerResumed && (mode != "Descendant" || job.ForcedCleanup);
            return new Dictionary<string, object> {
                ["mode"] = mode, ["passed"] = expected && cleaned && job.JobCloseConfirmed,
                ["failure"] = failure, ["exitCode"] = code, ["workerPid"] = job.WorkerPid,
                ["assignmentAttempted"] = job.AssignmentAttempted, ["assignmentConfirmed"] = job.AssignmentConfirmed,
                ["workerResumed"] = job.WorkerResumed, ["unassignedCleanupConfirmed"] = job.UnassignedCleanupConfirmed,
                ["activeBeforeCleanup"] = job.ActiveBeforeCleanup, ["activeAfterCleanup"] = active,
                ["forcedCleanup"] = job.ForcedCleanup, ["cleanupConfirmed"] = cleaned,
                ["jobCloseAttempted"] = job.JobCloseAttempted, ["jobCloseConfirmed"] = job.JobCloseConfirmed,
                ["auxiliaryHandlesClosed"] = job.AuxiliaryHandlesClosed
            };
        }
    }
}
