package dev.ebms.adapter.in.api.legacy;

import dev.ebms.adapter.in.api.v19.MessageStatusMapper;
import dev.ebms.adapter.in.api.v19.V19MessageStatus;
import dev.ebms.application.port.out.MessageSearchRepository.StatusMatch;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.MessageStatus;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The numeric status codes ebms-core stores in {@code ebms_message.status}, which cpa-service
 * reads into a {@code Short} from the paths we took over from ebms-api-service.
 *
 * <p>These numbers are ebms-core's, not the ebMS 2.0 specification's. The spec's Message Status
 * Service has five string values ({@code UnAuthorized} to {@code Forwarded}); ebms-core numbered
 * them 0-4 for its database, added 5 for an inbound processing failure, and 10-13 for its own
 * outbound lifecycle. ebms-api-service even calls 10 {@code SENDING} where ebms-core calls it
 * {@code CREATED}: only the number is stored, so only the number is the contract.
 *
 * <p>Both directions go through {@link MessageStatusMapper}, so the v19 API and these paths can
 * never disagree about a message. The inverse is computed by enumerating the mapper's inputs
 * rather than written out by hand.
 */
public final class EbmsCoreStatusCode {

    private EbmsCoreStatusCode() {
    }

    public static short of(V19MessageStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> 0;
            case NOT_RECOGNIZED -> 1;
            case RECEIVED -> 2;
            case PROCESSED -> 3;
            case FORWARDED -> 4;
            case FAILED -> 5;
            case CREATED -> 10;
            case DELIVERY_FAILED -> 11;
            case DELIVERED -> 12;
            case EXPIRED -> 13;
        };
    }

    public static short of(EbmsMessage message) {
        return of(MessageStatusMapper.toV19(message));
    }

    /**
     * Every combination of the columns the mapping reads that produces {@code code}. Empty for a
     * code no message of ours can have -- 13 EXPIRED, for one, since expiry is not modelled -- so
     * a filter on it finds nothing, as it should, rather than everything.
     */
    public static Set<StatusMatch> matching(short code) {
        Set<StatusMatch> matches = new LinkedHashSet<>();
        for (MessageStatus status : MessageStatus.values()) {
            for (boolean processed : new boolean[] {false, true}) {
                for (boolean ackRequested : new boolean[] {false, true}) {
                    if (of(MessageStatusMapper.toV19(status, processed, ackRequested)) == code) {
                        matches.add(new StatusMatch(status, processed, ackRequested));
                    }
                }
            }
        }
        return matches;
    }
}
