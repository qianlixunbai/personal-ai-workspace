package io.github.qianlixunbai.workspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PersonalAiWorkspaceApplication {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("--native-database-setup")) {
			System.exit(io.github.qianlixunbai.workspace.persistence.NativeDatabaseSetup.run(
				java.util.Arrays.copyOfRange(args, 1, args.length), System.in, System.out));
			return;
		}
		String stage = "NATIVE_ADMISSION_REJECTED";
		try {
			io.github.qianlixunbai.workspace.security.WindowsNativeGate.admit();
			io.github.qianlixunbai.workspace.security.WindowsNativeGate.requireRuntimeArguments(args);
			stage = "RUNTIME_START_FAILED";
			var app = new SpringApplication(PersonalAiWorkspaceApplication.class);
			if (java.nio.file.FileSystems.getDefault().getClass().getName().equals("sun.nio.fs.WindowsFileSystem")) {
				var source = io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.load();
				app.addInitializers(context -> {
					try { io.github.qianlixunbai.workspace.persistence.TrustedDatabaseConfiguration.install(context.getEnvironment(), source); }
					catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
				});
			}
			app.run(args);
		} catch (Exception | LinkageError failure) {
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
