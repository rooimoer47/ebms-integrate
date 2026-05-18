package dev.ebms.application.service;

import dev.ebms.application.port.out.CpaRepository;
import dev.ebms.application.port.out.MessageRepository;
import dev.ebms.domain.Cpa;
import dev.ebms.domain.EbmsMessage;
import dev.ebms.domain.exception.CpaNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryService.class);

    private final MessageRepository messageRepository;
    private final CpaRepository cpaRepository;
    private final SendMessageService sendMessageService;

    public RetryService(MessageRepository messageRepository, CpaRepository cpaRepository,
                        SendMessageService sendMessageService) {
        this.messageRepository = messageRepository;
        this.cpaRepository = cpaRepository;
        this.sendMessageService = sendMessageService;
    }

    @Scheduled(fixedDelay = 30_000)
    public void retryPending() {
        List<EbmsMessage> pending = messageRepository.findPendingRetries(Instant.now());
        if (pending.isEmpty()) return;

        log.debug("Retrying {} pending message(s)", pending.size());
        for (EbmsMessage message : pending) {
            Cpa cpa = cpaRepository.findByCpaId(message.cpaId())
                    .orElseThrow(() -> new CpaNotFoundException(message.cpaId()));
            sendMessageService.attemptSend(message, cpa);
        }
    }
}
