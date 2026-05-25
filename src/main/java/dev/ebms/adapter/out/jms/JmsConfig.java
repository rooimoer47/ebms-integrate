package dev.ebms.adapter.out.jms;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;

@Configuration
@ConditionalOnProperty("ebms.jms.broker-url")
public class JmsConfig {

    @Value("${ebms.jms.broker-url}")
    private String brokerUrl;

    @Value("${ebms.jms.username:}")
    private String username;

    @Value("${ebms.jms.password:}")
    private String password;

    @Bean
    public ActiveMQConnectionFactory activeMQConnectionFactory() {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        if (!username.isEmpty()) {
            factory.setUserName(username);
            factory.setPassword(password);
        }
        return factory;
    }

    @Bean
    public JmsTemplate jmsTemplate(ActiveMQConnectionFactory connectionFactory) {
        return new JmsTemplate(connectionFactory);
    }

    @Bean("jmsMessageEventPublisher")
    public JmsMessageEventPublisher jmsMessageEventPublisher(JmsTemplate jmsTemplate) {
        return new JmsMessageEventPublisher(jmsTemplate);
    }
}
