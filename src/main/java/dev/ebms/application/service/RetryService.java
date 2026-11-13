package dev.ebms.application.service;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.exception.CpaNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryService.class);

    private final MessageRepository messageRepository;
    private final CpaRepository cpaRepository;
    private final SendMessageService sendMessageService;
    private final MeterRegistry meterRegistry;

    public RetryService(MessageRepository messageRepository, CpaRepository cpaRepository,
                        SendMessageService sendMessageService, MeterRegistry meterRegistry) {
        this.messageRepository = messageRepository;
        this.cpaRepository = cpaRepository;
        this.sendMessageService = sendMessageService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelay = 30_000)
    public void retryPending() {
        List<UUID> pending = messageRepository.findPendingRetryIds(Instant.now());
        if (pending.isEmpty()) return;

        log.debug("Retrying {} pending message(s)", pending.size());
        for (UUID id : pending) {
            // Loaded one at a time, with its payloads, rather than as a batch: a backlog of
            // large messages would otherwise sit in the heap all at once.
            messageRepository.findById(id).ifPresent(message -> {
                Cpa cpa = cpaRepository.findByCpaId(message.cpaId())
                        .orElseThrow(() -> new CpaNotFoundException(message.cpaId()));
                meterRegistry.counter("ebms.retries.attempted", "cpa_id", message.cpaId()).increment();
                sendMessageService.attemptSend(message, cpa);
            });
        }
    }
}
