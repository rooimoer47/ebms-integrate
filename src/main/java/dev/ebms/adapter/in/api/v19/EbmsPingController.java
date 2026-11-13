package dev.ebms.adapter.in.api.v19;

import dev.ebms.application.port.in.PingMessageUseCase;
import dev.ebms.application.port.in.PingMessageUseCase.PingOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ebMS ping of the v19 contract, which cpa-service exposes to the admin console.
 *
 * <p>Mapped as POST because that is what the client sends. The two vendored copies of the
 * contract disagree -- the v18 document in jms-producer declares this a GET, ebms-core 2.20.x
 * declares it a POST -- and cpa-service's {@code EbmsCoreClient} uses {@code @PostExchange},
 * so POST is the one that has to work.
 *
 * <p>Party ids arrive URN-prefixed: cpa-service concatenates {@code urn:osb:oin:} onto the
 * bare OIN before calling.
 */
@RestController
@RequestMapping("/service/rest/v19/ebms/ping")
public class EbmsPingController {

    private final PingMessageUseCase pingMessageUseCase;

    public EbmsPingController(PingMessageUseCase pingMessageUseCase) {
        this.pingMessageUseCase = pingMessageUseCase;
    }

    @PostMapping(value = "/{cpaId}/from/{fromPartyId}/to/{toPartyId}",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ping(@PathVariable String cpaId,
                                       @PathVariable String fromPartyId,
                                       @PathVariable String toPartyId) {
        PingOutcome outcome = pingMessageUseCase.ping(cpaId, fromPartyId, toPartyId);

        return switch (outcome) {
            case PONG -> ResponseEntity.ok().build();
            case CPA_UNKNOWN -> ResponseEntity.notFound().build();
            // The partner, not this request, is what went wrong.
            case NO_PONG -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("No Pong from the partner for CPA " + cpaId);
        };
    }
}
