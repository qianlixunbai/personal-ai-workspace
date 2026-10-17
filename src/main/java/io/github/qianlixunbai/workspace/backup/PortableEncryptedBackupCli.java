package io.github.qianlixunbai.workspace.backup;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;

/** Interactive local terminal only. No stdin/env/file fallback for recovery passphrases. */
public final class PortableEncryptedBackupCli {
    private PortableEncryptedBackupCli() {}
    public static void main(String[] args) throws IOException {
        char[] password=null,confirmation=null;
        try {
            boolean synthetic=args.length>0&&args[args.length-1].equals("--synthetic-unencrypted-volume");
            if(synthetic)args=Arrays.copyOf(args,args.length-1);
            boolean encrypt=args.length==3&&args[0].equals("encrypt");
            boolean backup=args.length==4&&args[0].equals("backup")&&args[3].equals("--writers-stopped");
            boolean decrypt=args.length==3&&args[0].equals("decrypt");
            boolean restore=args.length==5&&args[0].equals("restore")&&args[4].equals("--trust-sql-source");
            boolean cleanup=args.length==2&&args[0].equals("cleanup-staging");
            if(!encrypt&&!backup&&!decrypt&&!restore&&!cleanup)throw new IOException();
            Console console=System.console();if(console==null)throw new IOException();
            var protection=synthetic?PortableEncryptedBackup.StagingProtection.SYNTHETIC_ONLY:PortableEncryptedBackup.StagingProtection.REQUIRE_ENCRYPTED_VOLUME;
            if(cleanup){Path path=Path.of(args[1]).toAbsolutePath().normalize();MaintenanceStaging.admit(path,synthetic);MaintenanceStaging.clean(path);console.printf("PLAINTEXT_CLEANED; ordinary deletion only%n");return;}
            console.printf("恢复口令不会保存；丢失口令可能导致备份永久无法解密。明文暂存与 Windows 产品准入仍 PENDING。%n");
            password=console.readPassword("恢复口令（至少 16 个字符）: ");
            if(encrypt||backup){confirmation=console.readPassword("再次输入恢复口令: ");if(!Arrays.equals(password,confirmation))throw new IOException();}
            var maintenance=new PortableEncryptedBackup(Path.of("D:/PostgreSQL/bin"),protection);
            if(backup){
                Path config=Path.of(args[1]);MaintenanceFiles.privatePath(config,false);MaintenanceFiles.require(Files.size(config)<16384);
                var c=UnifiedPostgresqlBackup.JSON.readTree(Files.readAllBytes(config));
                maintenance=new PortableEncryptedBackup(Path.of(c.path("tools").asString()),protection);
                maintenance.backup(new UnifiedPostgresqlBackup.Endpoint(c.path("sourceDatabase").asString(),c.path("sourceUsername").asString(),c.path("sourcePassword").asString()),
                    Path.of(c.path("knowledgeRoot").asString()),Path.of(args[2]),true,password);
            }
            else if(encrypt)maintenance.encrypt(Path.of(args[1]),Path.of(args[2]),password);
            else if(decrypt)maintenance.decrypt(Path.of(args[1]),Path.of(args[2]),password);
            else maintenance.restore(Path.of(args[1]),Path.of(args[2]),password,Path.of(args[3]),true);
            console.printf("%s%n",encrypt||backup?"ENCRYPTED_BACKUP_COMPLETE":decrypt?"DECRYPTION_VERIFIED / EXPLICIT_PLAINTEXT_EXPORT_RETAINED":"RESTORE_VERIFIED / IDENTITY_REBIND_REQUIRED / WINDOWS_ADMISSION_PENDING");
        }catch(Exception failure){throw new IOException("Encrypted maintenance stopped; use an interactive local console and private paths; retain incomplete evidence. No secret diagnostics.");}
        finally{if(password!=null)Arrays.fill(password,'\0');if(confirmation!=null)Arrays.fill(confirmation,'\0');}
    }
}
