package com.mymicroservice.product.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class ProductIndexStartupRebuilder {
    private static final Logger log = LoggerFactory.getLogger(ProductIndexStartupRebuilder.class);

    private final ProductIndexRebuildService rebuildService;
    private final TaskExecutor executor;
    private final boolean enabled;

    public ProductIndexStartupRebuilder(ProductIndexRebuildService rebuildService,
            @Qualifier("searchRebuildExecutor") TaskExecutor executor,
            @Value("${search.rebuild.on-startup:true}") boolean enabled) {
        this.rebuildService = rebuildService;
        this.executor = executor;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexAfterStartup() {
        if (enabled) {
            rebuildIfNeeded();
        }
    }

    public void rebuildIfNeeded() {
        executor.execute(() -> {
            try {
                if (rebuildService.isCurrentIndexReady()) {
                    log.info("Product search index is ready; startup rebuild is not required");
                    return;
                }
                log.info("Product search index is missing or has a different schema; starting background rebuild");
                rebuildService.rebuild(false);
            } catch (Exception failure) {
                log.error("Could not prepare product search index during startup", failure);
            }
        });
    }
}
