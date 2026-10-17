package io.github.qianlixunbai.workspace.persistence;

import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Explicit opt-in only. Credentials come from the existing external configuration mechanism. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "workspace.persistence.backend", havingValue = "postgresql")
public class WorkspacePostgresqlConfiguration {
    @Bean(name = "workspacePostgresqlDataSource", defaultCandidate = false)
    PGSimpleDataSource workspacePostgresqlDataSource(Environment environment) {
        String prefix = "workspace.persistence.postgresql.";
        String url = environment.getRequiredProperty(prefix + "url");
        var match = java.util.regex.Pattern.compile(
                "jdbc:postgresql://(127\\.0\\.0\\.1|localhost|\\[::1\\]):([0-9]{1,5})/([A-Za-z0-9_][A-Za-z0-9_.-]*)").matcher(url);
        if (!match.matches()) throw new IllegalArgumentException("Explicit loopback PostgreSQL URL required");
        int port = Integer.parseInt(match.group(2));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid PostgreSQL port");
        var data = new PGSimpleDataSource();
        data.setServerNames(new String[]{match.group(1)}); data.setPortNumbers(new int[]{port});
        data.setDatabaseName(match.group(3)); data.setCurrentSchema("workspace,pg_catalog");
        data.setUser(environment.getRequiredProperty(prefix + "username"));
        data.setPassword(environment.getRequiredProperty(prefix + "password"));
        data.setConnectTimeout(5); data.setSocketTimeout(30);
        return data;
    }
}
