using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.Json;
using System.Threading.Tasks;
using System.Buffers.Binary;
using System.Text;

// One explicit native -> Java -> native integration in the new cluster owned by the runner.
// It deliberately never creates a product source registration or invokes a real Profile Gate.
internal static class RecoverySourceProducerProbe
{
    private sealed record InterruptedAuthorization(int Version,string Kind,string State,string OperationId,string TargetProfileId,string ReadySha256,string RequestId,string Mode);
    private static async Task Main(string[] args)
    {
        if (args.Length is not (4 or 5) || args.Length==5 && args[4] is not ("prepared" or "ready")) throw new IOException("Explicit synthetic fixture/tooling required");
        var assembly = Assembly.LoadFrom(args[0]);
        var coordinator = assembly.GetType("PersonalAiWorkspace.Desktop.ProfileRecoveryCoordinator", true)!;
        var flags = BindingFlags.NonPublic | BindingFlags.Instance;
        using var config = JsonDocument.Parse(File.ReadAllBytes(args[3]));
        string isolated = config.RootElement.GetProperty("root").GetString()!;
        if (!System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(isolated), "\\APAW-SourceProducer-[0-9a-f]{32}\\z")) throw new IOException();
        string app = Path.Combine(isolated, "paw-source-producer-" + Guid.NewGuid().ToString("N"));
        string profileId = Guid.NewGuid().ToString("N"), root = Path.Combine(app, "Profiles", profileId);
        foreach (string path in new[] { app, Path.Combine(app, "Profiles"), root, Path.Combine(root, "data"),
            Path.Combine(root, "evidence"), Path.Combine(root, "model-state") })
        {
            var user = WindowsIdentity.GetCurrent().User!;
            var acl = new DirectorySecurity(); acl.SetOwner(user); acl.SetAccessRuleProtection(true, false);
            acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            FileSystemAclExtensions.Create(new DirectoryInfo(path), acl);
        }
        File.WriteAllBytes(Path.Combine(root, "evidence", "runtime-admission.lock"), []);
        File.WriteAllBytes(Path.Combine(root, "model-state", ".lock"), []);
        using var owner = (IDisposable)Activator.CreateInstance(coordinator, flags, null, [app], null)!;
        object intent = coordinator.GetMethod("ReserveBackupIntentForProfile", flags)!.Invoke(owner, [profileId])!;
        string backupId = (string)intent.GetType().GetProperty("BackupId")!.GetValue(intent)!;
        var start = new ProcessStartInfo(args[1]) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = app };
        foreach (string value in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", args[2],
            "io.github.qianlixunbai.workspace.backup.RecoverySourceProducerProbe", args[3] }) start.ArgumentList.Add(value);
        var run = coordinator.GetMethod("ExecuteSyntheticSourceBackupAsync", flags)!;
        var pending = (Task)run.Invoke(owner, [start])!;
        await pending;
        object registration = pending.GetType().GetProperty("Result")!.GetValue(pending)!;
        string record = Path.Combine(app, "Recovery", "synthetic-sources", backupId, "source.dpapi");
        byte[] original = File.ReadAllBytes(record);
        if (original.Length == 0 || Directory.Exists(Path.Combine(app, "Recovery", "sources"))) throw new IOException();
        string payload = (string)registration.GetType().GetProperty("PayloadSha256")!.GetValue(registration)!;
        string manifest = (string)registration.GetType().GetProperty("ManifestSha256")!.GetValue(registration)!;
        // A persisted synthetic result must not reserve a product target or satisfy its kind check.
        try { coordinator.GetMethod("ReservePlan", flags)!.Invoke(owner, [backupId, payload, manifest]); throw new Exception("Synthetic result authorized SQL"); }
        catch (TargetInvocationException denied) when (denied.InnerException is IOException) { }
        var validate = coordinator.GetMethod("Validate", BindingFlags.NonPublic | BindingFlags.Static, null, [registration.GetType(), typeof(string)], null)!;
        try { validate.Invoke(null, [registration, backupId]); throw new Exception("Synthetic kind accepted"); }
        catch (TargetInvocationException denied) when (denied.InnerException is IOException) { }
        if (Directory.GetFileSystemEntries(Path.Combine(app, "Recovery", "targets")).Length != 0) throw new IOException();
        // Same attempt cannot launch again or replace its DPAPI record.
        try { await (Task)run.Invoke(owner, [start])!; throw new Exception("Duplicate attempt executed"); }
        catch (IOException) { }
        if (!CryptographicOperations.FixedTimeEquals(original, File.ReadAllBytes(record))) throw new IOException();
        using (new FileStream(Path.Combine(root, "evidence", "runtime-admission.lock"), FileMode.Open, FileAccess.Read, FileShare.Read)) { }
        using (new FileStream(Path.Combine(root, "model-state", ".lock"), FileMode.Open, FileAccess.Write, FileShare.Read)) { }
        // End the producer's native owner too. Only persisted registration/ciphertext may
        // authorize the next JVM; neither Completed nor the source configuration survives.
        owner.Dispose();
        string retired = Path.Combine(app, "retained-source-" + profileId);
        if (Path.GetFullPath(root) != root || Path.GetFullPath(retired) != retired
            || !root.StartsWith(app + Path.DirectorySeparatorChar, StringComparison.Ordinal)
            || Path.GetDirectoryName(retired) != app || Path.Exists(retired)) throw new IOException();
        Directory.Move(root, retired); // This newly owned synthetic tree only; retain all bytes.
        var restore = new ProcessStartInfo(args[1]) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = app };
        foreach (string value in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", args[2],
            "io.github.qianlixunbai.workspace.backup.RecoverySourceProducerProbe", "restore-registered", args[3], app, backupId }) restore.ArgumentList.Add(value);
        if(args.Length==5) RunPrepared(assembly,coordinator,args,app,backupId,payload,manifest,isolated);
        else using (var child = Process.Start(restore) ?? throw new IOException())
        {
            var errors = child.StandardError.BaseStream.CopyToAsync(Stream.Null);
            try
            {
                string result = await child.StandardOutput.ReadToEndAsync().WaitAsync(TimeSpan.FromSeconds(90));
                await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10)); await errors.WaitAsync(TimeSpan.FromSeconds(5));
                if (child.ExitCode != 0 || result != "REGISTERED_SYNTHETIC_RECOVERY_VERIFIED") throw new IOException("Registered synthetic recovery failed");
            }
            finally { if (!child.HasExited) { child.Kill(); await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)); } }
        }
        if (!CryptographicOperations.FixedTimeEquals(original, File.ReadAllBytes(record))) throw new IOException();
        using var recovery = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(isolated, "synthetic-recovery-evidence.json")));
        foreach (string field in new[] { "authenticatedFreshTarget", "wrongPasswordCreatedNoDatabaseOrRoles",
            "memoryConversationKnowledgeVerified", "financeExcluded", "sourceSnapshotUnchanged", "existingAttemptRejected", "plaintextBackupCleaned",
            "durableRegistrationConsumed", "sourceDatabaseUnavailableDuringRestore", "sourceProfileAbsent", "missingRegistrationRejected", "registrationAndPayloadPinned" })
            if (!recovery.RootElement.GetProperty(field).GetBoolean()) throw new IOException();
        File.WriteAllText(Path.Combine(isolated, "native-source-evidence.json"), JsonSerializer.Serialize(new {
            status = "SYNTHETIC_NATIVE_SOURCE_PRODUCER_VERIFIED", backupId, payloadSha256 = payload, manifestSha256 = manifest,
            nativeDpapiReadback = true, sourceRoleReclaimed = true, authenticatedPayload = true,
            protectedConfigurationBound = true, nativeConfigurationIdentityAndDigestVerified = true,
            authenticatedFreshSyntheticTargetRestored = true, targetLoginsDisabled = args.Length!=5||args[4]!="ready",
            applicationCredentialRetainedForReady = args.Length==5&&args[4]=="ready",
            restoredByNewProcessFromDurableRegistration = true, originalSourceProfileRetainedAt = retired,
            personalSourceRegistration = false, productPlanRejected = true, duplicateAttemptRejected = true,
            sourceRecord = record, sourceRecordSha256 = Convert.ToHexStringLower(SHA256.HashData(original)) }));
        Console.WriteLine("PASS: protected Workspace configuration -> app/admin database binding -> owned backup/role cleanup -> native configuration/ciphertext verification -> DPAPI readback.");
        Console.WriteLine("PASS: synthetic receipt cannot authorize a product plan; duplicate attempt rejected; exclusion released after child exit.");
        Console.WriteLine("PASS: producer exited -> DPAPI registration consumed by new JVM -> source database unavailable/Profile absent -> authenticated fresh target -> stores/index verified -> expected credential disposition checked.");
        Console.WriteLine(args.Length==5?"PASS: real authority Native Gate -> pinned prepared-target helper -> canonical fixed data restore; no target JNA; candidate startup/replay denied.":
            "Personal maintenance remains BLOCKED; real Native Gate, personal target publication and activation were not exercised.");
    }

    // Keep native mutex acquisition/disposal on this one thread. All processes and Profiles
    // here are newly allocated fixture objects; no existing Profile is enumerated or opened.
    private static void RunPrepared(Assembly assembly,Type coordinator,string[] args,string app,string backupId,string payload,string manifest,string isolated)
    {
        const BindingFlags methods=BindingFlags.NonPublic|BindingFlags.Public|BindingFlags.Static|BindingFlags.Instance;
        Type Owner(string name)=>assembly.GetType("PersonalAiWorkspace.Desktop."+name,true)!;
        var state=Owner("Mmf3ProfileState");string id=Guid.NewGuid().ToString("N");
        string root=(string)state.GetMethod("Root",methods)!.Invoke(null,[id])!;
        if(Path.Exists(root))throw new IOException();
        File.WriteAllText(Path.Combine(isolated,"owned-native-authority.json"),JsonSerializer.Serialize(new{profileId=id,root,newlyAllocated=true}));
        var sourcesType=Owner("TrustedDevelopmentRuntimeSources");
        using var sources=(IDisposable)sourcesType.GetMethod("Open",methods)!.Invoke(null,null)!;
        if((string)sourcesType.GetProperty("Java",methods)!.GetValue(sources)! !=args[1])throw new IOException();
        state.GetMethod("Prepare",methods)!.Invoke(null,[id]);
        Owner("Mmf3ProfileNative").GetMethod("Provision",methods)!.Invoke(null,[id,(string)sourcesType.GetProperty("JnaJar",methods)!.GetValue(sources)!]);
        var launchType=Owner("Mmf3AcceptanceLaunch");string argument=(string)launchType.GetField("Argument",methods)!.GetRawConstantValue()!;
        object profile=launchType.GetMethod("Parse",methods)!.Invoke(null,[new[]{argument,id}])!;
        void NewLock(string path)
        {
            var user=WindowsIdentity.GetCurrent().User!;var acl=new FileSecurity();acl.SetOwner(user);acl.SetAccessRuleProtection(true,false);
            acl.AddAccessRule(new FileSystemAccessRule(user,FileSystemRights.FullControl,AccessControlType.Allow));
            using var file=new FileInfo(path).Create(FileMode.CreateNew,FileSystemRights.Read|FileSystemRights.Write,FileShare.None,4096,FileOptions.None,acl);
            file.Flush(true);
        }
        NewLock(Path.Combine(root,"evidence","runtime-admission.lock"));NewLock(Path.Combine(root,"model-state",".lock"));
        // Exercise the actual pinned application-JAR product entry after updating helper pins.
        using(var inspect=(IDisposable)coordinator.GetMethod("OpenForPlanning",methods)!.Invoke(null,[profile])!)
        {
            var task=(Task)coordinator.GetMethod("InspectSourceReadinessAsync",methods)!.Invoke(inspect,null)!;task.GetAwaiter().GetResult();
            var observed=task.GetType().GetProperty("Result")!.GetValue(task)!;
            var native=observed.GetType().GetProperty("Native")!.GetValue(observed)!;
            if((string)native.GetType().GetProperty("Admission")!.GetValue(native)! !="BLOCKED"
                ||observed.GetType().GetProperty("ObservedExitedChildren")!.GetValue(observed) is not null)throw new IOException();
            File.WriteAllText(Path.Combine(isolated,"product-maintenance-observation.json"),JsonSerializer.Serialize(observed));
        }
        using var owner=(IDisposable)coordinator.GetMethod("OpenForPlanning",methods)!.Invoke(null,[profile])!;
        using var runtime=(FileStream)coordinator.GetMethod("OpenRecord",methods)!.Invoke(null,[Path.Combine(root,"evidence","runtime-admission.lock"),true])!;
        using var model=(FileStream)coordinator.GetMethod("OpenRecord",methods)!.Invoke(null,[Path.Combine(root,"model-state",".lock"),false])!;
        var start=new ProcessStartInfo(args[1]){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,
            RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=root};
        string helper=(string)Owner("Mmf3ProfileNative").GetProperty("Helper",methods)!.GetValue(null)!;
        foreach(string value in new[]{"-Xmx384m","-XX:ActiveProcessorCount=2","-Dworkspace.native.development-profile="+id,
            "-Dworkspace.native.bootstrap="+helper,"-Djna.boot.library.path="+Path.Combine(root,"jna-5.17.0-win32-x86-64"),
            "-Djna.boot.library.name=jnidispatch","-Djna.nosys=true","-Djna.noclasspath=true","-Djna.nounpack=true",
            "-Djava.io.tmpdir="+Path.Combine(root,"evidence"),"-Djna.tmpdir="+Path.Combine(root,"evidence"),"-cp",args[2],
            "io.github.qianlixunbai.workspace.backup.RecoverySourceProducerProbe","restore-registered",args[3],app,backupId})start.ArgumentList.Add(value);
        if(args[4]=="ready")start.ArgumentList.Add("publish-ready");
        using var job=(IDisposable)Owner("RuntimeProcessJob").GetMethod("Create",methods)!.Invoke(null,null)!;
        using var child=Process.Start(start)??throw new IOException();
        Owner("RuntimeProcessJob").GetMethod("Assign",methods)!.Invoke(job,[child]);
        var errors=child.StandardError.BaseStream.CopyToAsync(Stream.Null);
        string Read()
        {
            var pipe=child.StandardOutput.BaseStream;byte[] prefix=new byte[4];pipe.ReadExactlyAsync(prefix).AsTask().WaitAsync(TimeSpan.FromSeconds(90)).GetAwaiter().GetResult();
            int size=BinaryPrimitives.ReadInt32BigEndian(prefix);if(size is <1 or >16384)throw new IOException();
            byte[] bytes=new byte[size];pipe.ReadExactlyAsync(bytes).AsTask().WaitAsync(TimeSpan.FromSeconds(15)).GetAwaiter().GetResult();return Encoding.ASCII.GetString(bytes);
        }
        void Write(string value)
        {
            byte[] bytes=Encoding.ASCII.GetBytes(value),prefix=new byte[4];BinaryPrimitives.WriteInt32BigEndian(prefix,bytes.Length);
            child.StandardInput.BaseStream.Write(prefix);child.StandardInput.BaseStream.Write(bytes);child.StandardInput.BaseStream.Flush();
        }
        try
        {
            if(Read()!="SYNTHETIC_TARGET_NATIVE_V1\t"+child.Id+"\t"+id)throw new IOException();
            long remote=(long)coordinator.GetMethod("DuplicateExclusion",methods)!.Invoke(null,[runtime,child])!;Write(remote.ToString(System.Globalization.CultureInfo.InvariantCulture));
            if(Read()!="SYNTHETIC_SOURCE_AUTHENTICATED_V1\t"+backupId+"\t"+payload+"\t"+manifest)throw new IOException();
            object plan=coordinator.GetMethod("PrepareSyntheticTarget",methods)!.Invoke(owner,[app,backupId,child])!;
            string target=(string)plan.GetType().GetProperty("TargetProfileId")!.GetValue(plan)!;
            string targetRoot=(string)state.GetMethod("Root",methods)!.Invoke(null,[target])!;
            File.WriteAllText(Path.Combine(isolated,"owned-prepared-target.json"),JsonSerializer.Serialize(new{targetProfileId=target,root=targetRoot,plan}));
            Write(target);child.StandardInput.Close();
            if(Read()!=(args[4]=="ready"?"SYNTHETIC_CANDIDATE_CLOSED":"PREPARED_SYNTHETIC_RECOVERY_VERIFIED")||!child.WaitForExit(15000)||child.ExitCode!=0||child.StandardOutput.Read()!=-1)throw new IOException();
            errors.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
            if(Directory.Exists(Path.Combine(targetRoot,"jna-5.17.0-win32-x86-64"))||!File.Exists(Path.Combine(targetRoot,"data","RESTORE_VERIFIED.json")))throw new IOException();
            try{launchType.GetMethod("Parse",methods)!.Invoke(null,[new[]{argument,target}]);throw new Exception("Candidate admitted");}
            catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
            var verify=new ProcessStartInfo(helper){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
            verify.ArgumentList.Add("VerifyProfileNative");verify.ArgumentList.Add(target);
            using var normal=Process.Start(verify)??throw new IOException();
            if(!normal.WaitForExit(15000)||normal.ExitCode!=10)throw new IOException();
            if(args[4]=="ready")
            {
                var publish=coordinator.GetMethod("PublishSyntheticReady",methods)!;
                publish.Invoke(owner,[target]);
                Type reader=assembly.GetType("PersonalAiWorkspace.WindowsSecurity.SyntheticRecoveryPublication",true)!;
                var inspect=reader.GetMethod("Inspect",methods)!;
                string entry,operation,readyHash;
                using(var observed=(IDisposable)inspect.Invoke(null,[target])!)
                {
                    var type=observed.GetType();
                    if((string)type.GetField("Stage",methods)!.GetValue(observed)! !="READY")throw new IOException();
                    entry=(string)type.GetField("Entry",methods)!.GetValue(observed)!;
                    var offline=(JsonElement)type.GetProperty("Offline",methods)!.GetValue(observed)!;
                    operation=offline.GetProperty("OperationId").GetString()!;
                    readyHash=Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(Path.Combine(entry,"ready.dpapi"))));
                }
                try{publish.Invoke(owner,[target]);throw new Exception("READY replayed");}
                catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
                void RefuseStartupAndProvision()
                {
                    try{launchType.GetMethod("Parse",methods)!.Invoke(null,[new[]{argument,target}]);throw new Exception("Recovery candidate admitted");}
                    catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
                    foreach(string action in new[]{"VerifyProfileNative","ProvisionProfileNative"})
                    {
                        var attempt=new ProcessStartInfo(helper){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
                        attempt.ArgumentList.Add(action);attempt.ArgumentList.Add(target);
                        if(action.StartsWith("Provision",StringComparison.Ordinal))attempt.ArgumentList.Add((string)sourcesType.GetProperty("JnaJar",methods)!.GetValue(sources)!);
                        using var blocked=Process.Start(attempt)??throw new IOException();
                        if(!blocked.WaitForExit(15000)||blocked.ExitCode!=10)throw new IOException();
                    }
                    if(Directory.Exists(Path.Combine(targetRoot,"jna-5.17.0-win32-x86-64")))throw new IOException();
                }
                RefuseStartupAndProvision();
                // Model the disconnected draft's durable authorization in this newly owned
                // synthetic candidate only. It is deliberately retained as UNKNOWN, never
                // removed to retry or promoted to ACTIVE. This is not personal authorization.
                var authorization=new InterruptedAuthorization(1,"SYNTHETIC_ACTIVATION_AUTHORIZATION","ACTIVATION_AUTHORIZED",
                    operation,target,readyHash,Guid.NewGuid().ToString("N"),"EXPLICIT_ISOLATED_SYNTHETIC");
                coordinator.GetMethod("PublishRecord",methods)!.MakeGenericMethod(typeof(InterruptedAuthorization)).Invoke(null,
                    [entry,"authorization",Encoding.ASCII.GetBytes("PersonalAIWorkspace/Recovery/synthetic-target/v1/"+target+"/authorization"),authorization]);
                RefuseStartupAndProvision();
                try{using var unexpected=(IDisposable)inspect.Invoke(null,[target])!;throw new Exception("Interrupted authorization promoted");}
                catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
                try{publish.Invoke(owner,[target]);throw new Exception("Interrupted attempt replayed");}
                catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
                if(readyHash!=Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(Path.Combine(entry,"ready.dpapi"))))
                    ||File.Exists(Path.Combine(entry,"active.dpapi")))throw new IOException();
                File.WriteAllText(Path.Combine(isolated,"synthetic-ready-evidence.json"),JsonSerializer.Serialize(new {
                    targetProfileId=target,operationId=operation,readySha256=readyHash,readyPublishedAfterChildExit=true,
                    independentReadyReadback=true,readyReplayRejected=true,readyStartupAndProvisionRejected=true,
                    interruptedAuthorizationRejected=true,interruptedReplayRejected=true,readyRecordUnchanged=true,
                    targetJnaAbsent=true,activeRecordAbsent=true,finalState="BLOCKED_INTERRUPTED_AUTHORIZATION_FIXTURE",
                    applicationCredentialRetained=true,activation=false,personalAuthorization=false }));
                Console.WriteLine("PASS: offline verification/cleanup -> child exit -> independent DPAPI READY; READY and interrupted authorization cannot start/provision/replay.");
            }
        }
        finally{if(!child.HasExited){job.Dispose();if(!child.HasExited)child.Kill();child.WaitForExit(5000);}}
    }
}
