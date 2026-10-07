package io.github.qianlixunbai.workspace.web;

import java.time.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** API/security fixture runs the real operation owner with no networking. */
@TestConfiguration
public class WebApiFixture {
    @Bean(destroyMethod = "close") Owner webApiFixtureOwner() { return new Owner(); }
    @Bean(destroyMethod = "") @Primary WebFetchService webApiFixtureService(Owner owner) { return owner.service; }
    static final class Owner implements AutoCloseable {
        final java.util.concurrent.ScheduledExecutorService timer = WebFetchService.timer();
        final WebFetchService service = new WebFetchService(WebPolicy.ASK_EVERY_TIME, (target, execution) ->
                new WebFetchResult(target.url(), target.url(), target.hostname(), "Fixture", Instant.EPOCH,
                        "text/plain", "web-extract-1", "Local fixture", false, false), timer, System::nanoTime);
        public void close() { service.close(); timer.shutdownNow(); }
    }
}
