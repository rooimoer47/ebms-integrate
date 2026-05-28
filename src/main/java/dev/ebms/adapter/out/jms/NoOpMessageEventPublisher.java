package dev.ebms.adapter.out.jms;

import dev.ebms.application.port.out.MessageEventPublisher;
import dev.ebms.domain.EbmsMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(name = "jmsMessageEventPublisher")
public class NoOpMessageEventPublisher implements MessageEventPublisher {

    @Override
    public void publish(EventType event, EbmsMessage message) {
        // no-op: event publishing is disabled when ebms.jms.broker-url is not configured
    }
}
