package io.github.qianlixunbai.workspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PersonalAiWorkspaceApplication {

	public static void main(String[] args) throws java.io.IOException {
		io.github.qianlixunbai.workspace.security.WindowsNativeGate.admit();
		io.github.qianlixunbai.workspace.security.WindowsNativeGate.requireRuntimeArguments(args);
		SpringApplication.run(PersonalAiWorkspaceApplication.class, args);
	}

}
