package dev.ebms.application.port.out;

import dev.ebms.domain.EbmsMessage;

public interface MessageEventPublisher {

    enum EventType { RECEIVED, DELIVERED, FAILED, EXPIRED }

    void publish(EventType event, EbmsMessage message);
}
