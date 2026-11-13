package dev.ebms.application.service;

import dev.ebms.application.port.in.PingMessageUseCase;
import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.MessageTransport;
import dev.ebms.application.port.out.OutboundMessageSerializer;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ebMS 2.0 Ping/Pong service, which the admin console uses to check that a partner is
 * reachable before anyone sends real traffic through a CPA.
 */
@Service
public class PingService implements PingMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(PingService.class);

    /** Reserved service and actions of the ebMS 2.0 message service, not of any CPA. */
    private static final String EBMS_SERVICE = "urn:oasis:names:tc:ebxml-msg:service";
    private static final String PING_ACTION = "Ping";
    private static final String PONG_ACTION = "Pong";

    private final CpaRepository cpaRepository;
    private final OutboundMessageSerializer serializer;
    private final MessageTransport transport;

    @Value("${ebms.msh-id}")
    private String mshId;

    public PingService(CpaRepository cpaRepository, OutboundMessageSerializer serializer,
                       MessageTransport transport) {
        this.cpaRepository = cpaRepository;
        this.serializer = serializer;
        this.transport = transport;
    }

    @Override
    public PingOutcome ping(String cpaId, String fromPartyId, String toPartyId) {
        Optional<Cpa> cpa = cpaRepository.findByCpaId(cpaId);
        if (cpa.isEmpty()) {
            log.info("Cannot ping: no CPA with id {}", cpaId);
            return PingOutcome.CPA_UNKNOWN;
        }

        EbmsMessage ping = buildPing(cpa.get(), fromPartyId, toPartyId);
        OutboundMessageSerializer.SerializedMessage serialized = serializer.serialize(ping, null);

        MessageTransport.TransportResult result = transport.send(
                cpa.get().transportUrl(), serialized.body(), serialized.contentType());

        if (!result.success()) {
            log.warn("Ping to {} failed at the transport", cpa.get().transportUrl());
            return PingOutcome.NO_PONG;
        }
        if (!isPong(result.responseBody())) {
            log.warn("Ping to {} answered without a Pong", cpa.get().transportUrl());
            return PingOutcome.NO_PONG;
        }

        log.info("Ping to {} answered with a Pong", cpa.get().transportUrl());
        return PingOutcome.PONG;
    }

    /**
     * A ping carries no payload and requests no acknowledgement: the Pong is the answer, so an
     * eb:AckRequested would ask the partner for a second, pointless round trip.
     */
    private EbmsMessage buildPing(Cpa cpa, String fromPartyId, String toPartyId) {
        Party from = resolveParty(cpa.fromParty(), fromPartyId);
        Party to = resolveParty(cpa.toParty(), toPartyId);
        return EbmsMessage.newOutbound(
                UUID.randomUUID() + "@" + mshId,
                UUID.randomUUID().toString(),
                cpa.cpaId(), from, to,
                EBMS_SERVICE, PING_ACTION,
                List.of(), false);
    }

    private Party resolveParty(Party fromCpa, String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return fromCpa;
        }
        return new Party(partyId.trim(), fromCpa.partyIdType(), fromCpa.role());
    }

    /**
     * Checked by looking for the Pong action in the response rather than by parsing it as an
     * inbound message: a Pong is not addressed to any CPA conversation, so it would not survive
     * the stricter validation the inbound parser applies.
     */
    private boolean isPong(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        return new String(body, StandardCharsets.UTF_8).contains(PONG_ACTION);
    }
}
