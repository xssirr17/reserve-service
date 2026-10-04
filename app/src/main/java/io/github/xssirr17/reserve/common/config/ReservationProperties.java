package io.github.xssirr17.reserve.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "reserve")
public class ReservationProperties {

    /**
     * Duration for which a PENDING reservation holds slot capacity before expiring.
     */
    private Duration holdDuration = Duration.ofMinutes(15);

    private ExpiryProperties expiry = new ExpiryProperties();
    private OutboxProperties outbox = new OutboxProperties();
    private IdempotencyProperties idempotency = new IdempotencyProperties();
    private CacheProperties cache = new CacheProperties();

    public Duration getHoldDuration() {
        return holdDuration;
    }

    public void setHoldDuration(Duration holdDuration) {
        this.holdDuration = holdDuration;
    }

    public ExpiryProperties getExpiry() {
        return expiry;
    }

    public void setExpiry(ExpiryProperties expiry) {
        this.expiry = expiry;
    }

    public OutboxProperties getOutbox() {
        return outbox;
    }

    public void setOutbox(OutboxProperties outbox) {
        this.outbox = outbox;
    }

    public IdempotencyProperties getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(IdempotencyProperties idempotency) {
        this.idempotency = idempotency;
    }

    public CacheProperties getCache() {
        return cache;
    }

    public void setCache(CacheProperties cache) {
        this.cache = cache;
    }

    public static class ExpiryProperties {
        private int batchSize = 50;
        private long intervalMs = 5000;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class OutboxProperties {
        private int batchSize = 50;
        private long intervalMs = 2000;
        private int maxAttempts = 5;
        private Duration leaseDuration = Duration.ofSeconds(30);
        private Duration retryBackoff = Duration.ofSeconds(5);

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }
    }

    public static class IdempotencyProperties {
        private Duration inProgressTimeout = Duration.ofMinutes(2);
        private Duration retention = Duration.ofHours(24);

        public Duration getInProgressTimeout() {
            return inProgressTimeout;
        }

        public void setInProgressTimeout(Duration inProgressTimeout) {
            this.inProgressTimeout = inProgressTimeout;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }

    public static class CacheProperties {
        private Duration ttl = Duration.ofMinutes(10);
        private long jitterMaxSeconds = 30;
        private Duration waitTimeout = Duration.ofSeconds(5);

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public long getJitterMaxSeconds() {
            return jitterMaxSeconds;
        }

        public void setJitterMaxSeconds(long jitterMaxSeconds) {
            this.jitterMaxSeconds = jitterMaxSeconds;
        }

        public Duration getWaitTimeout() {
            return waitTimeout;
        }

        public void setWaitTimeout(Duration waitTimeout) {
            this.waitTimeout = waitTimeout;
        }
    }
}
