package com.spotifyyoutube.migrator.common.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Task decorator propagating SLF4J MDC context from calling threads to asynchronous worker threads (Phase 18F).
 * <p>
 * Ensures that asynchronous migration tasks inherit the request correlation ID and logging context
 * established during the originating HTTP request, while guaranteeing thread-safe context cleanup
 * when worker threads are recycled in bounded thread pools.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            if (callerContext != null && !callerContext.isEmpty()) {
                MDC.setContextMap(callerContext);
            } else {
                MDC.clear();
            }

            try {
                runnable.run();
            } finally {
                if (previousContext != null && !previousContext.isEmpty()) {
                    MDC.setContextMap(previousContext);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
