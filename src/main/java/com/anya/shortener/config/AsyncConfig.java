package com.anya.shortener.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The executor that absorbs click-analytics work off the redirect path.
 *
 * <p>The pool is deliberately bounded and uses {@link ThreadPoolExecutor.DiscardPolicy}.
 * Analytics are lossy-tolerant; redirects are not. If ingestion falls behind, the
 * right failure mode is to drop click records, never to block or fail the user's
 * redirect. An unbounded queue would instead turn a downstream Mongo stall into
 * an out-of-memory crash that takes redirects down with it.
 */
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(5_000);
        executor.setThreadNamePrefix("analytics-");
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("Analytics queue saturated; dropping click event to protect the redirect path"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
