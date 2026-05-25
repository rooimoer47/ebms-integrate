package dev.ebms.adapter.out.jms;

import dev.ebms.application.port.out.MessageEventPublisher;
import dev.ebms.domain.EbmsMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;

public class JmsMessageEventPublisher implements MessageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JmsMessageEventPublisher.class);

    private final JmsTemplate jmsTemplate;

    public JmsMessageEventPublisher(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    @Override
    public void publish(EventType event, EbmsMessage message) {
        String queue = event.name();
        try {
            jmsTemplate.send(queue, session -> {
                Message msg = session.createMessage();
                setProperty(msg, "cpaId", message.cpaId());
                setProperty(msg, "fromPartyId", message.from().partyId());
                setProperty(msg, "fromRole", "");
                setProperty(msg, "toPartyId", message.to().partyId());
                setProperty(msg, "toRole", "");
                setProperty(msg, "service", message.service());
                setProperty(msg, "action", message.action());
                setProperty(msg, "conversationId", message.conversationId());
                setProperty(msg, "messageId", message.messageId());
                setProperty(msg, "refToMessageId", message.refToMessageId() != null ? message.refToMessageId() : "");
                return msg;
            });
        } catch (Exception e) {
            log.warn("Failed to publish {} event for message {}: {}", event, message.messageId(), e.getMessage());
        }
    }

    private void setProperty(Message msg, String name, String value) throws JMSException {
        msg.setStringProperty(name, value != null ? value : "");
    }
}
