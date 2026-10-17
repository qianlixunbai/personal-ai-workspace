package io.github.qianlixunbai.workspace.security;

import io.github.qianlixunbai.workspace.api.StorageStatusController;
import io.github.qianlixunbai.workspace.persistence.StorageStatusService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageStatusTest {
    private static final String PREFIX="workspace.persistence.postgresql.";
    private static StorageStatusService service(MockEnvironment environment) {
        return new StorageStatusService(environment,new DefaultListableBeanFactory().getBeanProvider(DataSource.class));
    }
    @Test void nativeOnlyStatusUsesSafeConfigurationFactsWithoutSecretsOrDatabaseMutations() throws Exception {
        var env=new MockEnvironment();var service=service(env);var json=JsonMapper.builder().build();
        assertEquals("SQLITE",service.inspect().backend());assertEquals("NOT_CONFIGURED",service.inspect().connection());assertFalse(service.inspect().financeEnabled());
        env.withProperty(PREFIX+"url","jdbc:postgresql://remote.invalid:5432/private_database");
        assertEquals("CONFIGURATION_INCOMPLETE",service.inspect().errorCode());
        String secret=UUID.randomUUID().toString();env.withProperty(PREFIX+"username","fixture").withProperty(PREFIX+"password",secret);
        var invalid=service.inspect();assertEquals("CONFIGURATION_INVALID",invalid.connection());
        String safe=json.writeValueAsString(invalid);assertFalse(safe.contains(secret));assertFalse(safe.contains("remote.invalid"));assertFalse(safe.contains("private_database"));
        env.withProperty(PREFIX+"username","postgres");assertEquals("ROLE_REJECTED",service.inspect().connection());
        var token=mock(LocalClientToken.class);var browser=mock(BrowserClients.class);
        when(token.matches("Bearer synthetic")).thenReturn(true);
        when(browser.authenticateCredential("Bearer br1.synthetic")).thenReturn(new ClientIdentity("fixture","browser","fixture",null,Instant.EPOCH,Set.of("translate")));
        var filter=new LocalClientFilter(token,browser);var controller=new StorageStatusController(service);
        try {
            for(String credential:List.of("Bearer incorrect","Bearer br1.synthetic","Bearer synthetic")) {
                SecurityContextHolder.clearContext();var request=new MockHttpServletRequest("GET","/api/v1/storage/status");
                request.setServletPath("/api/v1/storage/status");request.setRemoteAddr("127.0.0.1");request.addHeader("Authorization",credential);
                request.addHeader("Sec-Fetch-Site","none");request.addHeader("Sec-Fetch-Mode","cors");request.addHeader("Sec-Fetch-Dest","empty");
                var response=new MockHttpServletResponse();
                filter.doFilter(request,response,(incoming,outgoing)->response.getOutputStream().write(json.writeValueAsBytes(controller.status(request))));
                assertEquals(credential.equals("Bearer synthetic")?200:credential.startsWith("Bearer br1.")?403:401,response.getStatus());
                assertEquals("no-store",response.getHeader("Cache-Control"));
                for(String forbidden:List.of(secret,"private_database","remote.invalid","password","username"))assertFalse(response.getContentAsString().contains(forbidden));
            }
        }finally{SecurityContextHolder.clearContext();}
    }
    @Test @EnabledIfSystemProperty(named="storage.status.test",matches="true")
    void realIsolatedPostgresqlReportsInitializationThenPreparedSchemasAndAuthenticationFailure() throws Exception {
        Path config=Path.of(".runtime/workspace/b4a-test-credentials.json").toAbsolutePath();
        for(Path part=config;part!=null;part=part.getParent())assertFalse(Files.isSymbolicLink(part));
        var user=config.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        assertEquals(user,Files.getOwner(config));assertTrue(Files.getFileAttributeView(config,AclFileAttributeView.class).getAcl().stream().allMatch(rule->rule.principal().equals(user)));
        var json=JsonMapper.builder().build();var c=json.readTree(Files.readAllBytes(config));
        String database=c.path("database").asString(),owner=c.path("ownerUsername").asString(),app=c.path("appUsername").asString();
        assertTrue(database.matches("personal_ai_workspace_b4a_[0-9a-f]{12}"));assertTrue(owner.matches("workspace_b4a_owner_[0-9a-f]{12}"));assertTrue(app.matches("workspace_b4a_app_[0-9a-f]{12}"));
        var source=new PGSimpleDataSource();source.setServerNames(new String[]{"127.0.0.1"});source.setPortNumbers(new int[]{5432});source.setDatabaseName(database);source.setUser(owner);source.setPassword(c.path("ownerPassword").asString());
        try(var db=source.getConnection();var statement=db.createStatement()) {
            try(var rows=statement.executeQuery("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()")){assertTrue(rows.next());assertEquals(c.path("ownershipReceipt").asString(),rows.getString(1));}
            try(var rows=statement.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','workspace')")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }
        var env=new MockEnvironment().withProperty(PREFIX+"url","jdbc:postgresql://127.0.0.1:5432/"+database)
            .withProperty(PREFIX+"username",app).withProperty(PREFIX+"password",c.path("appPassword").asString());
        var service=service(env);var empty=service.inspect();assertEquals("CONNECTED",empty.connection());assertEquals("INITIALIZATION_REQUIRED",empty.workspaceSchema());
        // This test-only explicit migration uses only this fresh database. The product status service never migrates.
        Flyway.configure().dataSource(source).defaultSchema("workspace").schemas("workspace").createSchemas(false)
            .locations("classpath:db/workspace/migration").cleanDisabled(true).load().migrate();
        try(var db=source.getConnection();var statement=db.createStatement()){statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA workspace TO "+app);}
        var prepared=service.inspect();assertEquals("CONNECTED",prepared.connection());assertEquals("V1_V2_READY",prepared.workspaceSchema());assertEquals("READY",prepared.knowledgeSchema());
        assertEquals("SQLITE",prepared.backend());assertEquals("DISABLED",prepared.financeSchema());assertEquals("NONE",prepared.errorCode());
        env.withProperty("workspace.persistence.backend","postgresql");assertEquals("POSTGRESQL",service.inspect().backend());
        env.withProperty(PREFIX+"password",UUID.randomUUID().toString());assertEquals("AUTHENTICATION_FAILED",service.inspect().connection());
        try(var db=source.getConnection();var statement=db.createStatement()) {
            try(var rows=statement.executeQuery("SELECT (SELECT count(*) FROM workspace.memory_items)+(SELECT count(*) FROM workspace.conversations)+(SELECT count(*) FROM workspace.knowledge_documents)")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            try(var rows=statement.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }
        Path evidence=Path.of(c.path("maintenanceRoot").asString()).resolve("status-evidence.json");
        Files.write(evidence,json.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("database",database,"before",empty,"prepared",prepared,"financeMigrationsExecuted",false,"productionDataTouched",false)),StandardOpenOption.CREATE_NEW);
        System.out.println("B4A real isolated storage status evidence: "+evidence);
    }
}
