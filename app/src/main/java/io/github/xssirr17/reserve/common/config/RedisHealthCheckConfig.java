package io.github.xssirr17.reserve.common.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import reactor.core.publisher.Mono;

/**
 * Replaces the default Spring Boot Redis health indicators with a safe PING-based check.
 * <p>
 * Spring Boot's default Redis health indicators execute the Redis 'INFO' command and parse
 * the output using Properties.load(). On Windows systems where Redis is executed from a user
 * directory, the path in Redis INFO triggers "Malformed unicode encoding" because backslash-u
 * is parsed as an invalid Unicode escape sequence.
 * </p>
 * <p>
 * Using Redis PING verifies end-to-end connectivity without executing or parsing the INFO command.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RedisConnectionFactory.class)
public class RedisHealthCheckConfig {

    @Bean(name = {"redisHealthIndicator", "redisHealthContributor"})
    public HealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return () -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                String pong = connection.ping();
                return Health.up().withDetail("status", pong != null ? pong : "PONG").build();
            } catch (Exception ex) {
                return Health.down(ex).build();
            }
        };
    }

    @Bean(name = "redisReactiveHealthIndicator")
    @ConditionalOnClass(ReactiveRedisConnectionFactory.class)
    public ReactiveHealthIndicator redisReactiveHealthIndicator(ReactiveRedisConnectionFactory reactiveConnectionFactory) {
        return () -> reactiveConnectionFactory.getReactiveConnection()
            .ping()
            .map(pong -> Health.up().withDetail("status", pong != null ? pong : "PONG").build())
            .onErrorResume(ex -> Mono.just(Health.down(ex).build()));
    }
}
