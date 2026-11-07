package io.github.qianlixunbai.workspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PersonalAiWorkspaceApplication {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("--native-profile-recovery-v1")) {
			System.exit(io.github.qianlixunbai.workspace.persistence.NativeProfileRecovery.runInspection(
				java.util.Arrays.copyOfRange(args, 1, args.length), System.in, System.out));
			return;
		}
		if (args.length > 0 && args[0].equals("--native-finance-setup")) {
			System.exit(io.github.qianlixunbai.workspace.finance.NativeFinanceSetup.run(
				java.util.Arrays.copyOfRange(args, 1, args.length), System.in, System.out));
			return;
		}
		if (args.length > 0 && args[0].equals("--native-database-setup")) {
			System.exit(io.github.qianlixunbai.workspace.persistence.NativeDatabaseSetup.run(
				java.util.Arrays.copyOfRange(args, 1, args.length), System.in, System.out));
			return;
		}
		String stage = "NATIVE_ADMISSION_REJECTED";
		boolean syntheticActivation = false;
		try {
			if(args.length>0&&args[0].equals("--synthetic-recovery-activation-v1")) {
				io.github.qianlixunbai.workspace.security.WindowsNativeGate.receiveSyntheticActivation(System.in);
				syntheticActivation = true;
				args=java.util.Arrays.copyOfRange(args,1,args.length);
			}
			io.github.qianlixunbai.workspace.security.WindowsNativeGate.admit();
			io.github.qianlixunbai.workspace.security.WindowsNativeGate.requireRuntimeArguments(args);
			stage = "RUNTIME_START_FAILED";
			var app = new SpringApplication(PersonalAiWorkspaceApplication.class);
			if (java.nio.file.FileSystems.getDefault().getClass().getName().equals("sun.nio.fs.WindowsFileSystem")) {
				var source = io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.load();
				var finance = io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.loadFinance();
				app.addInitializers(context -> {
					try {
						io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.install(context.getEnvironment(), source);
						io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.installFinance(context.getEnvironment(), finance);
					}
					catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
				});
			}
			app.run(args);
		} catch (Exception | LinkageError failure) {
			if(syntheticActivation)try {
				var root=io.github.qianlixunbai.workspace.security.WindowsNativeGate.profileRoot();
				var file=root.resolve("evidence/recovery-startup-failure.txt");
				var details=new StringBuilder();int depth=0;
				for(Throwable cause=failure;cause!=null&&depth++<8;cause=cause.getCause()) {
					details.append(cause.getClass().getName()).append('\n');
					for(var frame:java.util.Arrays.stream(cause.getStackTrace()).limit(6).toList())details.append(frame).append('\n');
				}
				// Code locations only; never exception messages, SQL, credentials or business content.
				io.github.qianlixunbai.workspace.security.WindowsPrivateAcl.createFile(file);
				try(var output=java.nio.channels.FileChannel.open(file,java.nio.file.StandardOpenOption.WRITE)) {
					var bytes=java.nio.ByteBuffer.wrap(details.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
					while(bytes.hasRemaining())output.write(bytes);output.force(true);
				}
			}catch(Exception ignored){/* Diagnostics cannot repair admission or promote a target. */}
			// Fixed codes only. No raw causes, paths, URLs or credential-bearing exception messages.
			for (Throwable cause = failure; cause != null; cause = cause.getCause())
				if (cause instanceof io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.Failure safe) {
					stage = safe.reason().name(); break;
				}
			System.err.println("WORKSPACE_STARTUP_V1\t" + stage);
			System.exit(1);
		}
	}

}
