package io.github.qianlixunbai.workspace.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(RuntimeProperties.class)
public class RuntimeConfiguration {
    public RuntimeConfiguration(RuntimeProperties p) {
        for (Duration d : new Duration[]{p.ollama().connectTimeout(), p.ollama().requestTimeout(),
                p.ollama().healthTimeout(), p.tasks().queueTimeout(),
                p.tasks().executionTimeout(), p.tasks().retention()}) {
            if (d.isNegative() || d.isZero() || d.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("Runtime time budgets must be positive and at most 10 minutes");
            }
        }
        if (p.tasks().maxRetained() < p.tasks().concurrency() + p.tasks().queueCapacity()) {
            throw new IllegalArgumentException("Task retention capacity must cover active capacity");
        }
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> loopbackOnly() {
        return factory -> {
            if (factory.getAddress() == null || !factory.getAddress().isLoopbackAddress()) {
                throw new IllegalArgumentException("Runtime requires a loopback bind address");
            }
        };
    }
}
