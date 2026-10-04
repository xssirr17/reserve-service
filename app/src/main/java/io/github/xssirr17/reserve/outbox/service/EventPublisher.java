package io.github.xssirr17.reserve.outbox.service;

import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;

public interface EventPublisher {

    void publish(OutboxEvent event);
}
