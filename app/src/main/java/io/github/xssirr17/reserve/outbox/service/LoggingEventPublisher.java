package io.github.xssirr17.reserve.outbox.service;

import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("[OUTBOX-PUBLISHED] eventId={}, aggregateType={}, aggregateId={}, eventType={}, payload={}",
            event.getId(), event.getAggregateType(), event.getAggregateId(), event.getEventType(), event.getPayload());
    }
}
