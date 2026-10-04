package io.github.xssirr17.reserve.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RedisHealthCheckConfigTest {

    private final RedisHealthCheckConfig config = new RedisHealthCheckConfig();

    @Test
    void redisHealthIndicator_upWhenPingSucceeds() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        HealthIndicator indicator = config.redisHealthIndicator(connectionFactory);
        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("status", "PONG");
        verify(connection).close();
    }

    @Test
    void redisHealthIndicator_downWhenPingFails() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection()).thenThrow(new RuntimeException("Connection refused"));

        HealthIndicator indicator = config.redisHealthIndicator(connectionFactory);
        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void redisReactiveHealthIndicator_upWhenPingSucceeds() {
        ReactiveRedisConnectionFactory connectionFactory = mock(ReactiveRedisConnectionFactory.class);
        ReactiveRedisConnection connection = mock(ReactiveRedisConnection.class);
        when(connectionFactory.getReactiveConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(Mono.just("PONG"));

        ReactiveHealthIndicator indicator = config.redisReactiveHealthIndicator(connectionFactory);
        Health health = indicator.health().block();

        assertThat(health).isNotNull();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("status", "PONG");
    }

    @Test
    void redisReactiveHealthIndicator_downWhenPingFails() {
        ReactiveRedisConnectionFactory connectionFactory = mock(ReactiveRedisConnectionFactory.class);
        ReactiveRedisConnection connection = mock(ReactiveRedisConnection.class);
        when(connectionFactory.getReactiveConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(Mono.error(new RuntimeException("Connection error")));

        ReactiveHealthIndicator indicator = config.redisReactiveHealthIndicator(connectionFactory);
        Health health = indicator.health().block();

        assertThat(health).isNotNull();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }
}
