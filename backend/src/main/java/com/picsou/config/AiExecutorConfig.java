package com.picsou.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Thread-pool executors for the AI categorization background job.
 *
 * <ul>
 *   <li>{@code aiJobExecutor} — drives the outer job loop; one job per member at a time
 *       with a small burst capacity.</li>
 *   <li>{@code aiInferenceExecutor} — fans out concurrent model calls within each chunk;
 *       sized for the heaviest expected concurrency (up to 16 in-flight LLM calls).</li>
 * </ul>
 */
@Configuration
public class AiExecutorConfig {

    @Bean
    public Executor aiJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-job-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Executor aiInferenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("ai-infer-");
        executor.initialize();
        return executor;
    }
}
