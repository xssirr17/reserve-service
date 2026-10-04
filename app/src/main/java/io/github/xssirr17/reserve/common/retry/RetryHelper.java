package io.github.xssirr17.reserve.common.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.function.Supplier;

public final class RetryHelper {

    private static final Logger log = LoggerFactory.getLogger(RetryHelper.class);

    private RetryHelper() {}

    public static <T> T withRetry(int maxAttempts, long initialBackoffMs, Supplier<T> operation) {
        int attempt = 0;
        long backoff = initialBackoffMs;
        while (true) {
            attempt++;
            try {
                return operation.get();
            } catch (PessimisticLockingFailureException | ObjectOptimisticLockingFailureException ex) {
                if (attempt >= maxAttempts) {
                    log.error("Operation failed after {} attempts due to concurrency/lock failure: {}", attempt, ex.getMessage());
                    throw ex;
                }
                log.warn("Transient concurrency failure on attempt {}/{}, retrying in {} ms: {}",
                    attempt, maxAttempts, backoff, ex.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
                backoff *= 2;
            }
        }
    }
}
