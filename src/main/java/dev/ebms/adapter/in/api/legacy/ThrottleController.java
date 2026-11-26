package dev.ebms.adapter.in.api.legacy;

import dev.ebms.application.port.in.MessageAdminUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Failed deliveries per partner host, which jms-consumer polls to stop sending to an afnemer
 * that keeps failing. Keyed by host -- not by URL, and not by party id -- because that is what
 * ebms-api-service returned and what jms-consumer reduces its own {@code throttleKey} to.
 */
@RestController
@RequestMapping("/ebms/throttle")
public class ThrottleController {

    /** A week. The query has an index on failures by time, but not one built for months. */
    static final int MAX_MINUTES = (int) Duration.ofDays(7).toMinutes();

    private final MessageAdminUseCase admin;

    public ThrottleController(MessageAdminUseCase admin) {
        this.admin = admin;
    }

    @GetMapping("/failurecounts")
    public ResponseEntity<Map<String, Long>> failureCounts(@RequestParam(defaultValue = "60") int minutes) {
        if (minutes < 1 || minutes > MAX_MINUTES) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(admin.failureCounts(minutes));
    }
}
