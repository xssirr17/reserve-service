package io.github.xssirr17.reserve.idempotency;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/reserve}",
    "spring.datasource.username=${TEST_DB_USERNAME:postgres}",
    "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.data.redis.host=${TEST_REDIS_HOST:localhost}",
    "spring.data.redis.port=${TEST_REDIS_PORT:6379}",
    "spring.datasource.hikari.maximum-pool-size=20",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class IdempotencyCleanupJobIntegrationTest {

    @Autowired
    private IdempotencyKeyRepository repository;

    @Autowired
    private IdempotencyCleanupJob cleanupJob;

    @Autowired
    private ReservationProperties properties;

    @Autowired
    private Clock clock;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("cleanupExpiredKeys scheduled entry point deletes keys older than retention")
    void testCleanupExpiredKeysScheduledEntryPoint() {
        Instant now = clock.instant();
        Instant expiredCreatedAt = now.minus(properties.getIdempotency().getRetention()).minus(1, ChronoUnit.HOURS);
        Instant activeCreatedAt = now.minus(1, ChronoUnit.HOURS);

        String scope = "user:test:POST:/api/reservations";
        String expiredKey = UUID.randomUUID().toString();
        String activeKey = UUID.randomUUID().toString();

        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.tryInsertInProgress(scope, expiredKey, "hash-expired", expiredCreatedAt);
            repository.tryInsertInProgress(scope, activeKey, "hash-active", activeCreatedAt);
        });

        int deleted = cleanupJob.cleanupExpiredKeys();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByScopeAndKey(scope, expiredKey)).isEmpty();
        assertThat(repository.findByScopeAndKey(scope, activeKey)).isPresent();
    }
}
