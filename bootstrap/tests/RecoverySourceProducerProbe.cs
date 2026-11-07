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
using System.Threading;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using PersonalAiWorkspace.Core;

// One explicit native -> Java -> native integration in the new cluster owned by the runner.
// It never creates a personal source registration. Prepared/activation modes use the real
// Gate only for newly allocated synthetic authority/target Profiles.
internal static class RecoverySourceProducerProbe
{
    private sealed record InterruptedAuthorization(int Version,string Kind,string State,string OperationId,string TargetProfileId,string ReadySha256,string RequestId,string Mode);
    private static async Task Main(string[] args)
    {
        if (args.Length is not (4 or 5) || args.Length==5 && args[4] is not ("prepared" or "ready" or "activate" or "interrupt" or "desktop")) throw new IOException("Explicit synthetic fixture/tooling required");
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
        // Automated native button action for explicitly authorized fresh synthetic data only.
        // Real product code never reads fixture.json or auto-fills/auto-confirms this window.
        string passphrase = Convert.ToBase64String(RandomNumberGenerator.GetBytes(48));
        int confirmations = 0;
        EventManager.RegisterClassHandler(typeof(Window), FrameworkElement.LoadedEvent, new RoutedEventHandler((sender, _) =>
        {
            if (sender is not Window window || window.Title != "Personal AI Workspace · 合成备份授权") return;
            if (Interlocked.Increment(ref confirmations) != 1 || window.Content is not StackPanel panel
                || !panel.Children.OfType<TextBlock>().Any(text => text.Text.Contains(backupId, StringComparison.Ordinal))) throw new IOException();
            panel.Children.OfType<TextBox>().Single(box => box.Name == "SourceAdministrator").Text = config.RootElement.GetProperty("adminUsername").GetString()!;
            panel.Children.OfType<PasswordBox>().Single(box => box.Name == "SourceAdministratorPassword").Password = config.RootElement.GetProperty("adminPassword").GetString()!;
            foreach (var box in panel.Children.OfType<PasswordBox>().Where(box => box.Name.StartsWith("SourceBackupPassphrase", StringComparison.Ordinal))) box.Password = passphrase;
            panel.Children.OfType<Button>().Single(button => button.Name == "AuthorizeSourceBackup").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        }));
        var start = new ProcessStartInfo(args[1]) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = app };
        foreach (string value in new[] { "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", args[2],
            "io.github.qianlixunbai.workspace.backup.RecoverySourceProducerProbe", args[3] }) start.ArgumentList.Add(value);
        var run = coordinator.GetMethod("ExecuteSyntheticSourceBackupAsync", flags)!;
        var pending = (Task)run.Invoke(owner, [start])!;
        await pending;
        if (confirmations != 1) throw new IOException();
        object registration = pending.GetType().GetProperty("Result")!.GetValue(pending)!;
        // Persist only this generated fixture passphrase so the separate restoration process
        // can consume the new backup. No production secret is written by the native window.
        byte[] passphraseBytes = Encoding.UTF8.GetBytes(passphrase); passphrase = "";
        try
        {
            byte[] protectedPhrase = ProtectedData.Protect(passphraseBytes, null, DataProtectionScope.CurrentUser);
            try
            {
                var sid = WindowsIdentity.GetCurrent().User!;
                var security = new FileSecurity(); security.SetOwner(sid); security.SetAccessRuleProtection(true, false);
                security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl, AccessControlType.Allow));
                using var file = new FileInfo(Path.Combine(isolated, "fixture-passphrase.dpapi")).Create(FileMode.CreateNew,
                    FileSystemRights.Read | FileSystemRights.Write, FileShare.None, 4096, FileOptions.None, security);
                file.Write(protectedPhrase); file.Flush(true);
            }
            finally { CryptographicOperations.ZeroMemory(protectedPhrase); }
        }
        finally { CryptographicOperations.ZeroMemory(passphraseBytes); }
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
        if(args.Length==5)
        {
            Exception? failure=null;
            var entry=new Thread(()=>{try{RunPrepared(assembly,coordinator,args,app,backupId,payload,manifest,isolated);}catch(Exception error){failure=error;}});
            entry.SetApartmentState(ApartmentState.STA);entry.Start();entry.Join();
            if(failure is not null)throw new IOException("Prepared lifecycle failed",failure);
        }
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
            nativeAuthorizationWindowConfirmed = confirmations == 1, sourceSecretsViaPrivatePipe = true,
            authenticatedFreshSyntheticTargetRestored = true, targetLoginsDisabled = args.Length!=5||args[4]=="prepared",
            applicationCredentialRetainedForReady = args.Length==5&&args[4]!="prepared",
            restoredByNewProcessFromDurableRegistration = true, originalSourceProfileRetainedAt = retired,
            personalSourceRegistration = false, productPlanRejected = true, duplicateAttemptRejected = true,
            sourceRecord = record, sourceRecordSha256 = Convert.ToHexStringLower(SHA256.HashData(original)) }));
        Console.WriteLine("PASS: protected Workspace configuration -> app/admin database binding -> owned backup/role cleanup -> native configuration/ciphertext verification -> DPAPI readback.");
        Console.WriteLine("PASS: synthetic receipt cannot authorize a product plan; duplicate attempt rejected; exclusion released after child exit.");
        Console.WriteLine("PASS: producer exited -> DPAPI registration consumed by new JVM -> source database unavailable/Profile absent -> authenticated fresh target -> stores/index verified -> expected credential disposition checked.");
        Console.WriteLine(args.Length==5?"PASS: real authority Native Gate -> pinned prepared target -> canonical fixed data; explicit mode result recorded separately.":
            "Personal maintenance remains BLOCKED; real Native Gate, personal target publication and activation were not exercised.");
    }

    private static void RunDesktopLifecycle(Assembly assembly,Type coordinator,object owner,string target,string targetRoot,
        string entry,string operation,string readyHash,FileStream runtime,FileStream model,string isolated)
    {
        const BindingFlags methods=BindingFlags.NonPublic|BindingFlags.Public|BindingFlags.Static|BindingFlags.Instance;
        Type Owner(string name)=>assembly.GetType("PersonalAiWorkspace.Desktop."+name,true)!;
        var confirm=coordinator.GetMethod("ConfirmSyntheticActivation",methods)!;
        Action<object> Dialog(bool click)=>value=>
        {
            var window=(Window)value;window.Opacity=0;window.ShowInTaskbar=false;
            window.Loaded+=(_,_)=>
            {
                if(!click){window.Close();return;}
                var button=((StackPanel)window.Content).Children.OfType<Button>().Single(b=>b.Name=="ActivateSyntheticRecovery");
                if(button.IsDefault||!button.IsEnabled)throw new IOException("Explicit activation button required");
                button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            };
        };
        if(confirm.Invoke(owner,[target,runtime,model,Dialog(false)]) is not null
            ||File.Exists(Path.Combine(entry,"authorization.dpapi"))||Directory.Exists(Path.Combine(targetRoot,"jna-5.17.0-win32-x86-64")))throw new IOException("Cancelled confirmation mutated target");
        using var activation=(IDisposable)confirm.Invoke(owner,[target,runtime,model,Dialog(true)])!;
        if(activation is null)throw new IOException("Explicit synthetic click missing");
        try{confirm.Invoke(owner,[target,runtime,model,Dialog(true)]);throw new Exception("Confirmation replayed");}
        catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
        var launch=Owner("Mmf3AcceptanceLaunch");string argument=(string)launch.GetField("Argument",methods)!.GetRawConstantValue()!;
        try{launch.GetMethod("Parse",methods)!.Invoke(null,[new[]{argument,target}]);throw new Exception("Authorized ordinary startup admitted");}
        catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
        string preference=Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(targetRoot)!)!,"last-workspace");
        string? PreferenceHash()=>File.Exists(preference)?Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(preference))):null;
        string? before=PreferenceHash();Exception? failure=null;int? runtimePid=null;int revisions=0;bool callbackCompleted=false;
        Action<object> observe=value=>
        {
            var app=(Application)value;
            app.Startup+=async(_,_)=>
            {
                try
                {
                    string result=await ((Task<string>)value.GetType().GetProperty("DevelopmentStartupCompletion",methods)!.GetValue(value)!).WaitAsync(TimeSpan.FromSeconds(75));
                    if(result!="ACTIVE_SYNTHETIC")throw new IOException("Desktop startup result: "+result);
                    var runtimeOwner=value.GetType().GetProperty("DevelopmentRuntime",methods)!.GetValue(value)!;
                    var runtimeType=runtimeOwner.GetType();runtimePid=(int?)runtimeType.GetProperty("OwnedProcessId",methods)!.GetValue(runtimeOwner);
                    if(runtimePid is null)throw new IOException();
                    var client=(RuntimeClient)value.GetType().GetField("runtime",methods)!.GetValue(value)!;
                    var memory=await client.ListMemoryAsync(new MemoryQuery(),CancellationToken.None);
                    var conversations=await client.ListConversationsAsync(ConversationStatus.ACTIVE,0,10,CancellationToken.None);
                    var knowledge=await client.ListKnowledgeAsync("ACTIVE",0,CancellationToken.None);
                    if(memory.Items.Count==0||conversations.Items.Count==0||knowledge.Items.Length==0)throw new IOException("Desktop stores missing");
                    foreach(var conversation in conversations.Items)
                        if((await client.GetConversationAsync(conversation.Id,0,10,CancellationToken.None)).Turns.Count==0)throw new IOException();
                    foreach(var document in knowledge.Items)
                    foreach(var revision in (await client.GetKnowledgeAsync(document.DocumentId,CancellationToken.None)).Revisions)
                    {
                        var preview=await client.PreviewKnowledgeAsync(document.DocumentId,revision.SourceRevision,0,CancellationToken.None);
                        using var input=new FileStream(Path.Combine(targetRoot,"data","knowledge","sources",document.DocumentId,revision.SourceRevision+".source"),FileMode.Open,FileAccess.Read,FileShare.Read);
                        if(input.Length!=revision.ByteLength||Convert.ToHexStringLower(SHA256.HashData(input))!=revision.SourceDigest||preview.Text.Length==0)throw new IOException();
                        revisions++;
                    }
                    if(revisions==0||!File.Exists(Path.Combine(entry,"active.dpapi")))throw new IOException();
                    launch.GetMethod("Parse",methods)!.Invoke(null,[new[]{argument,target}]);
                    try{activation.GetType().GetMethod("Provision",methods)!.Invoke(activation,null);throw new Exception("Provision replayed");}
                    catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
                    try{await (Task)runtimeType.GetMethod("RestartAsync",methods)!.Invoke(runtimeOwner,[CancellationToken.None])!;throw new Exception("Runtime restarted");}
                    catch(IOException){}
                    bool conflictingPrimary=true;
                    var competitor=new Thread(()=>
                    {
                        using var single=(IDisposable)Activator.CreateInstance(Owner("SingleInstance"),methods,null,[".MMF3."+target],null)!;
                        conflictingPrimary=(bool)single.GetType().GetProperty("IsPrimary",methods)!.GetValue(single)!;
                    });competitor.Start();competitor.Join();
                    if(conflictingPrimary||PreferenceHash()!=before)throw new IOException("Desktop ownership/selection mismatch");
                    callbackCompleted=true;
                    await (Task)runtimeType.GetMethod("StopAsync",methods)!.Invoke(runtimeOwner,null)!;
                }
                catch(Exception error){failure=error;}
                finally{app.Shutdown(failure is null?0:1);}
            };
        };
        int exit=(int)Owner("Program").GetMethod("RunSyntheticRecoveryDesktop",methods)!.Invoke(null,[activation,observe])!;
        if(exit!=0||failure is not null||!callbackCompleted||PreferenceHash()!=before)throw new IOException("Desktop lifecycle failed",failure);
        if(readyHash!=Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(Path.Combine(entry,"ready.dpapi")))))throw new IOException();
        File.WriteAllText(Path.Combine(isolated,"synthetic-activation-evidence.json"),JsonSerializer.Serialize(new{
            targetProfileId=target,operationId=operation,readySha256=readyHash,runtimePid,
            cancelledConfirmationUnchanged=true,nativeExplicitSyntheticClick=true,confirmationReplayRejected=true,
            ordinaryStartupRejectedBeforeActive=true,actualAssistantAppRun=true,assistantStartupPublishedActive=true,
            ownedTargetMutexRetained=true,oneRuntimeOnly=true,activationRestartRejected=true,nativeProvisionReplayRejected=true,
            desktopClientStoresRead=true,sourceDigestsVerified=revisions,lastWorkspaceSelectionUnchanged=true,
            shellPresentationExercised=false,personalAuthorization=false,finalState="ACTIVE_SYNTHETIC_DESKTOP_STOPPED"}));
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
        if(args[4]!="prepared")start.ArgumentList.Add("publish-ready");
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
            if(Read()!=(args[4]!="prepared"?"SYNTHETIC_CANDIDATE_CLOSED":"PREPARED_SYNTHETIC_RECOVERY_VERIFIED")||!child.WaitForExit(15000)||child.ExitCode!=0||child.StandardOutput.Read()!=-1)throw new IOException();
            errors.WaitAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
            if(Directory.Exists(Path.Combine(targetRoot,"jna-5.17.0-win32-x86-64"))||!File.Exists(Path.Combine(targetRoot,"data","RESTORE_VERIFIED.json")))throw new IOException();
            try{launchType.GetMethod("Parse",methods)!.Invoke(null,[new[]{argument,target}]);throw new Exception("Candidate admitted");}
            catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
            var verify=new ProcessStartInfo(helper){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
            verify.ArgumentList.Add("VerifyProfileNative");verify.ArgumentList.Add(target);
            using var normal=Process.Start(verify)??throw new IOException();
            if(!normal.WaitForExit(15000)||normal.ExitCode!=10)throw new IOException();
            if(args[4]!="prepared")
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
                if(args[4]=="desktop")
                {
                    RunDesktopLifecycle(assembly,coordinator,owner,target,targetRoot,entry,operation,readyHash,runtime,model,isolated);
                    return;
                }
                if(args[4] is "activate" or "interrupt")
                {
                    var authorize=coordinator.GetMethod("AuthorizeSyntheticActivation",methods)!;
                    using var activation=(IDisposable)authorize.Invoke(owner,[target,Guid.NewGuid().ToString("N"),runtime,model])!;
                    RefuseStartupAndProvision(); // Even this live owner's authorization is not ordinary admission.
                    try{authorize.Invoke(owner,[target,Guid.NewGuid().ToString("N"),runtime,model]);throw new Exception("Authorization reused");}
                    catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
                    if(args[4]=="interrupt")
                    {
                        File.WriteAllText(Path.Combine(isolated,"synthetic-activation-evidence.json"),JsonSerializer.Serialize(new{
                            targetProfileId=target,operationId=operation,readySha256=readyHash,ownerPid=Environment.ProcessId,
                            authorizedOnce=true,ordinaryStartupRejected=true,authorizationReplayRejected=true,
                            finalState="BLOCKED_AUTHORIZATION_OWNER_EXIT",activation=false,targetJnaAbsent=true,activeRecordAbsent=true}));
                        return; // Owner exits normally without consuming authorization; caller verifies stale-owner rejection afterwards.
                    }
                    object activationProfile=activation.GetType().GetMethod("Provision",methods)!.Invoke(activation,null)!;
                    try{activation.GetType().GetMethod("Provision",methods)!.Invoke(activation,null);throw new Exception("Native publication replayed");}
                    catch(TargetInvocationException denied)when(denied.InnerException is IOException){}
                    Type runtimeType=Owner("DevelopmentRuntimeOwner");object? actualRuntime=null;
                    using var client=(RuntimeClient)typeof(RuntimeClient).GetMethod("CreateMmf3Acceptance",methods)!.Invoke(null,
                        [new Func<string?>(()=>actualRuntime is null?null:(string?)runtimeType.GetMethod("ReadCredential",methods)!.Invoke(actualRuntime,null))])!;
                    using var ownedRuntime=(IDisposable)Activator.CreateInstance(runtimeType,methods,null,[activationProfile,client],null)!;
                    actualRuntime=ownedRuntime;
                    runtimeType.GetMethod("Start",methods)!.Invoke(actualRuntime,[CancellationToken.None]);
                    var clock=Stopwatch.StartNew();bool ready=false;
                    do{
                        var startup=runtimeType.GetMethod("Snapshot",methods)!.Invoke(actualRuntime,null)!;
                        ready=(bool)startup.GetType().GetProperty("CanQueryStorage")!.GetValue(startup)!;
                        if(ready)break;if((string)startup.GetType().GetProperty("Runtime")!.GetValue(startup)! !="STARTING")
                            throw new IOException("Activation runtime "+startup.GetType().GetProperty("Failure")!.GetValue(startup));
                        Thread.Sleep(100);
                    }while(clock.Elapsed<TimeSpan.FromSeconds(65));
                    if(!ready)throw new IOException("Activation startup timeout");
                    var memory=client.ListMemoryAsync(new MemoryQuery(),CancellationToken.None).GetAwaiter().GetResult();
                    var conversations=client.ListConversationsAsync(ConversationStatus.ACTIVE,0,10,CancellationToken.None).GetAwaiter().GetResult();
                    var knowledge=client.ListKnowledgeAsync("ACTIVE",0,CancellationToken.None).GetAwaiter().GetResult();
                    if(memory.Items.Count==0||conversations.Items.Count==0||knowledge.Items.Length==0)throw new IOException("Restored stores missing");
                    foreach(var conversation in conversations.Items)
                        if(client.GetConversationAsync(conversation.Id,0,10,CancellationToken.None).GetAwaiter().GetResult().Turns.Count==0)throw new IOException();
                    int revisions=0;
                    foreach(var document in knowledge.Items)
                    foreach(var revision in client.GetKnowledgeAsync(document.DocumentId,CancellationToken.None).GetAwaiter().GetResult().Revisions)
                    {
                        var preview=client.PreviewKnowledgeAsync(document.DocumentId,revision.SourceRevision,0,CancellationToken.None).GetAwaiter().GetResult();
                        string source=Path.Combine(targetRoot,"data","knowledge","sources",document.DocumentId,revision.SourceRevision+".source");
                        using var input=new FileStream(source,FileMode.Open,FileAccess.Read,FileShare.Read);
                        if(input.Length!=revision.ByteLength||Convert.ToHexStringLower(SHA256.HashData(input))!=revision.SourceDigest||preview.Text.Length==0)throw new IOException();
                        revisions++;
                    }
                    if(revisions==0)throw new IOException();
                    // A helper launched by the coordinator cannot impersonate the claimed JVM.
                    var impostor=new ProcessStartInfo(helper){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
                    impostor.ArgumentList.Add("VerifyRecoveryRuntimeNative");impostor.ArgumentList.Add(target);
                    using(var rejected=Process.Start(impostor)??throw new IOException())
                        if(!rejected.WaitForExit(15000)||rejected.ExitCode!=10)throw new IOException("Wrong process admitted");
                    ((Task)runtimeType.GetMethod("CompleteRecoveryActivationAsync",methods)!.Invoke(actualRuntime,[CancellationToken.None])!).GetAwaiter().GetResult();
                    using(var active=(IDisposable)inspect.Invoke(null,[target])!)
                        if((string)active.GetType().GetField("Stage",methods)!.GetValue(active)! !="ACTIVE")throw new IOException();
                    launchType.GetMethod("Parse",methods)!.Invoke(null,[new[]{argument,target}]); // Verify only; no second Runtime.
                    try{((Task)runtimeType.GetMethod("RestartAsync",methods)!.Invoke(actualRuntime,[CancellationToken.None])!).GetAwaiter().GetResult();throw new Exception("Activation restarted");}
                    catch(IOException){}
                    File.WriteAllText(Path.Combine(isolated,"synthetic-activation-evidence.json"),JsonSerializer.Serialize(new{
                        targetProfileId=target,operationId=operation,readySha256=readyHash,runtimePid=runtimeType.GetProperty("OwnedProcessId",methods)!.GetValue(actualRuntime),
                        authorizedOnce=true,ordinaryStartupRejectedBeforeActive=true,authorizationReplayRejected=true,nativeProvisionReplayRejected=true,
                        wrongProcessRejected=true,activationRestartRejected=true,actualRuntimeStorageReady=true,memoryConversationKnowledgeReadback=true,
                        sourceDigestsVerified=revisions,activePublishedAfterStorage=true,ordinaryActiveVerification=true,lastWorkspaceSelectionWritten=false,
                        finalState="ACTIVE_SYNTHETIC",activation=true,personalAuthorization=false}));
                    return;
                }
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
