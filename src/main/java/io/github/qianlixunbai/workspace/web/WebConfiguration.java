package io.github.qianlixunbai.workspace.web;

import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class WebConfiguration {
    @Bean(destroyMethod = "close") WebResolver webResolver() { return new WebResolver(); }
    @Bean(destroyMethod = "shutdownNow") ScheduledExecutorService webTimer() { return WebFetchService.timer(); }
    @Bean(destroyMethod = "close") WebFetchService webFetchService(
            @Value("${workspace.web.policy:ASK_EVERY_TIME}") WebPolicy policy,
            WebResolver resolver, ScheduledExecutorService webTimer) {
        PublicWebTransport transport = new PublicWebTransport(resolver, webTimer);
        return new WebFetchService(policy, transport::fetch, webTimer, System::nanoTime);
    }
}
