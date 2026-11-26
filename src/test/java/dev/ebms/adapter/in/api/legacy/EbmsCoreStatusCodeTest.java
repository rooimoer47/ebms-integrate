package dev.ebms.adapter.in.api.legacy;

import dev.ebms.adapter.in.api.v19.MessageStatusMapper;
import dev.ebms.adapter.in.api.v19.V19MessageStatus;
import dev.ebms.application.port.out.MessageSearchRepository.StatusMatch;
import dev.ebms.domain.MessageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EbmsCoreStatusCodeTest {

    /** ebms-core's EbMSMessageStatus ids, which cpa-service reads into a Short. */
    @ParameterizedTest
    @CsvSource({
            "UNAUTHORIZED, 0", "NOT_RECOGNIZED, 1", "RECEIVED, 2", "PROCESSED, 3", "FORWARDED, 4",
            "FAILED, 5", "CREATED, 10", "DELIVERY_FAILED, 11", "DELIVERED, 12", "EXPIRED, 13"
    })
    void everyContractStatus_hasEbmsCoresNumber(V19MessageStatus status, short code) {
        assertThat(EbmsCoreStatusCode.of(status)).isEqualTo(code);
    }

    /**
     * Every state a message of ours can be in, and the number the console sees for it. The table
     * is the review surface: a change to the mapping has to change a row here.
     */
    @ParameterizedTest(name = "{0} processed={1} ackRequested={2} -> {3}")
    @CsvSource({
            "RECEIVED,     false, false, 2",
            "RECEIVED,     false, true,  2",
            "RECEIVED,     true,  false, 3",
            "RECEIVED,     true,  true,  3",
            "PENDING_SEND, false, false, 10",
            "PENDING_SEND, false, true,  10",
            "SENT,         false, false, 12",
            "SENT,         false, true,  10",
            "ACKED,        false, true,  12",
            "DELIVERED,    false, false, 12",
            "FAILED,       false, false, 11",
            "FAILED,       false, true,  11"
    })
    void everyInternalState_mapsToOneCode(MessageStatus status, boolean processed,
                                          boolean ackRequested, short code) {
        assertThat(EbmsCoreStatusCode.of(MessageStatusMapper.toV19(status, processed, ackRequested)))
                .isEqualTo(code);
    }

    @Test
    void matching_isTheExactInverse() {
        for (MessageStatus status : MessageStatus.values()) {
            for (boolean processed : new boolean[] {false, true}) {
                for (boolean ack : new boolean[] {false, true}) {
                    short code = EbmsCoreStatusCode.of(MessageStatusMapper.toV19(status, processed, ack));
                    assertThat(EbmsCoreStatusCode.matching(code))
                            .contains(new StatusMatch(status, processed, ack));
                }
            }
        }
        // ...and no code claims a state that maps elsewhere.
        Set<StatusMatch> seen = new HashSet<>();
        for (V19MessageStatus status : V19MessageStatus.values()) {
            for (StatusMatch match : EbmsCoreStatusCode.matching(EbmsCoreStatusCode.of(status))) {
                assertThat(seen.add(match)).as("%s claimed twice", match).isTrue();
            }
        }
        assertThat(seen).hasSize(MessageStatus.values().length * 4);
    }

    @Test
    void sentAwaitingAck_isFoundUnderCreated_notDelivered() {
        StatusMatch awaitingAck = new StatusMatch(MessageStatus.SENT, false, true);

        assertThat(EbmsCoreStatusCode.matching((short) 10)).contains(awaitingAck);
        assertThat(EbmsCoreStatusCode.matching((short) 12)).doesNotContain(awaitingAck);
    }

    /** A filter on a status we never produce must find nothing, not fall back to everything. */
    @ParameterizedTest
    @CsvSource({"0", "1", "4", "5", "13", "99"})
    void codesWeNeverProduce_matchNothing(short code) {
        assertThat(EbmsCoreStatusCode.matching(code)).isEmpty();
    }

    @Test
    void codes_areUnique() {
        short[] codes = new short[V19MessageStatus.values().length];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = EbmsCoreStatusCode.of(V19MessageStatus.values()[i]);
        }
        assertThat(new HashSet<>(Arrays.asList(box(codes)))).hasSize(codes.length);
    }

    private static Short[] box(short[] values) {
        Short[] boxed = new Short[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }
}
