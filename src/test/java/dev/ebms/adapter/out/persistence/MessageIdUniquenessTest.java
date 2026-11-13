package dev.ebms.adapter.out.persistence;

import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The uniqueness of {@code messages.message_id} is enforced by a constraint whose <em>name</em>
 * is part of the integration contract with jms-consumer.
 *
 * <p>{@code JmsMessageConsumer.isDuplicateMessageId()} decides a resubmission is a benign JMS
 * redelivery only when the error text contains both
 * {@code "duplicate key value violates unique constraint"} and {@code "uc_ebms_message"}. If
 * the constraint were left with the name Postgres derives from {@code UNIQUE} in V1
 * ({@code messages_message_id_key}), that check would fail and the consumer's infinite-retry
 * policy would spin forever on a message that can never succeed.
 *
 * <p>If this test fails because the constraint was renamed, fix jms-consumer in the same
 * change rather than relaxing the assertion. A follow-up to replace the string match with a
 * real 409 is tracked separately.
 */
@SpringBootTest
class MessageIdUniquenessTest {

    static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    JpaMessageRepositoryAdapter repository;

    @Test
    void duplicateMessageId_failsWithTheConstraintNameJmsConsumerLooksFor() {
        String messageId = "AFN-dup-001@00000001810220350000";
        repository.save(message(messageId));

        Throwable thrown = catchThrowable(() -> repository.save(message(messageId)));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);

        String text = rootCauseText(thrown);
        assertThat(text)
                .as("jms-consumer matches on this exact phrase")
                .contains("duplicate key value violates unique constraint");
        assertThat(text)
                .as("jms-consumer matches on this exact constraint name")
                .contains("uc_ebms_message");
    }

    @Test
    void distinctMessageIds_bothPersist() {
        repository.save(message("AFN-ok-001@00000001810220350000"));

        assertThatThrownBy(() -> repository.save(message("AFN-ok-001@00000001810220350000")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.findByMessageId("AFN-ok-001@00000001810220350000")).isPresent();
    }

    /** The driver's message is on a nested cause, which is where the client reads it from. */
    private String rootCauseText(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                text.append(t.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private EbmsMessage message(String messageId) {
        return EbmsMessage.newOutbound(messageId, "conv-dup", "cpa-001",
                new Party("urn:osb:oin:00000001822477348001", "urn:osb:oin", "Bronhouder"),
                new Party("urn:osb:oin:00000001810220350000", "urn:osb:oin", "Afnemer"),
                "urn:osb:services:verstrekking", "verstrekkingDoorLV", List.of(), false);
    }
}
