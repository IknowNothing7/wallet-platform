package com.waller.wallet_platform.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    public static final String BANK_SUBMISSION_EXECUTOR = "bankSubmissionExecutor";

    /**
     * Sends new deposits to the bank off the request thread (DepositSubmissionListener). Bounded, so a slow bank
     * can't pile up threads: when the queue is full the submission is dropped with a warning, not failed, because
     * the deposit is already committed as PENDING and DepositReconciliationJob submits it once it is stale.
     * Throwing instead would surface after the commit, as a 500 for a deposit that was in fact created.
     */
    @Bean(BANK_SUBMISSION_EXECUTOR)
    public Executor bankSubmissionExecutor(
            @Value("${deposit.submission.threads:4}") int threads,
            @Value("${deposit.submission.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("bank-submit-");
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Bank submission queue full ({}), leaving the deposit to reconciliation", queueCapacity));
        // Let in-flight bank calls finish on shutdown; anything still queued is picked up by reconciliation
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
