package io.github.qianlixunbai.workspace.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static io.github.qianlixunbai.workspace.backup.MaintenanceFiles.*;

/** Read-only maintenance admission, independent of Runtime/MMF-3. No ACL repair or secure-erase claim. */
final class MaintenanceStaging {
    private MaintenanceStaging() {}
    static void admit(Path staging,boolean syntheticOnly) throws Exception {
        PortableEncryptedBackup.stagingParent(staging);
        require(System.getProperty("os.name","").startsWith("Windows"));
        require(staging.toString().matches("[A-Za-z]:\\\\.*"));
        String script="""
            $ErrorActionPreference='Stop'
            try {
                Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
                Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Management/Microsoft.PowerShell.Management.psd1') -ErrorAction Stop
                $user=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
                $trusted=@($user,'S-1-5-18','S-1-5-32-544','S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')
                $path=$env:WORKSPACE_MAINTENANCE_PARENT
                for($part=$path;$part;$part=[IO.Path]::GetDirectoryName($part)) {
                    $item=Get-Item -LiteralPath $part -Force
                    if(-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){exit 2}
                    $acl=Get-Acl -LiteralPath $part
                    if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $trusted){exit 3}
                    foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
                        if($rule.AccessControlType -eq 'Deny' -or ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly)){continue}
                        if(([long]$rule.FileSystemRights -band 0x500d0040) -and $rule.IdentityReference.Value -notin $trusted){exit 4}
                    }
                }
                if($env:WORKSPACE_MAINTENANCE_SYNTHETIC -ne 'true') {
                    $volume=Get-BitLockerVolume -MountPoint ([IO.Path]::GetPathRoot($path)) -ErrorAction Stop
                    if(@($volume).Count -ne 1 -or $volume.VolumeStatus -ne 'FullyEncrypted' -or
                       $volume.ProtectionStatus -ne 'On' -or $volume.EncryptionPercentage -ne 100 -or $volume.LockStatus -ne 'Unlocked'){exit 5}
                }
                [Console]::Write('MAINTENANCE_STAGING_ADMITTED')
            }catch{exit 6}
            """;
        Path powershell=Path.of(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe");noLinks(powershell);
        var builder=new ProcessBuilder(powershell.toString(),"-NoProfile","-NonInteractive","-Command",script);
        builder.environment().put("PSModulePath",powershell.getParent().resolve("Modules").toString());
        builder.environment().put("WORKSPACE_MAINTENANCE_PARENT",staging.getParent().toString());
        builder.environment().put("WORKSPACE_MAINTENANCE_SYNTHETIC",Boolean.toString(syntheticOnly));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();
        try {
            process.getOutputStream().close();require(process.waitFor(20,TimeUnit.SECONDS));
            require(process.exitValue()==0&&new String(process.getInputStream().readNBytes(128),StandardCharsets.US_ASCII).equals("MAINTENANCE_STAGING_ADMITTED"));
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;
        }finally{
            boolean interrupted=Thread.interrupted();
            try {
                if(process.isAlive())process.destroyForcibly();
                try{require(process.waitFor(5,TimeUnit.SECONDS));}catch(InterruptedException e){interrupted=true;throw new IOException("Staging admission cancelled");}
                process.getInputStream().close();process.getErrorStream().close();
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }

    /** Only the newly created operation's exact 'backup' subtree; candidate/DB evidence is never removed.
     * First inspect the bounded tree without following links, then delete deepest first.
     * A failure is surfaced and leaves a fixed cleanup-required diagnostic for local recovery.
     */
    static void clean(Path staging) throws IOException {
        boolean interrupted=Thread.interrupted();
        try {
            privatePath(staging,true);privatePath(staging.resolve("DECRYPTION_INCOMPLETE"),false);
            Path backup=staging.resolve("backup");
            if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS)) {
                List<Path> paths;
                try(var walk=Files.walk(backup,6)){paths=walk.limit(4005).toList();}
                require(paths.size()<4005);
                for(Path path:paths){require(path.startsWith(backup));privatePath(path,Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS));}
                for(Path path:paths.stream().sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
            Path marker=staging.resolve("PLAINTEXT_CLEANED");if(!Files.exists(marker))publish(marker,"TEMPORARY_BACKUP_REMOVED; ORDINARY_DELETE_ONLY".getBytes(StandardCharsets.US_ASCII));
        }catch(IOException failure){
            Path marker=staging.resolve("PLAINTEXT_CLEANUP_REQUIRED");
            try{if(!Files.exists(marker))publish(marker,"LOCAL_CLEANUP_REQUIRED; NO_ACTIVATION".getBytes(StandardCharsets.US_ASCII));}catch(IOException ignored){}
            throw new IOException("Temporary plaintext cleanup incomplete; protected staging requires local attention");
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
}
