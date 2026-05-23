package dev.ebms.adapter.out.persistence;

import dev.ebms.domain.EbmsMessage.Direction;
import dev.ebms.domain.MessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MessageJpaRepository extends JpaRepository<MessageEntity, UUID> {

    Optional<MessageEntity> findByMessageId(String messageId);

    @Query("SELECT m FROM MessageEntity m WHERE (:direction IS NULL OR m.direction = :direction) AND (:status IS NULL OR m.status = :status)")
    List<MessageEntity> findByDirectionAndStatus(Direction direction, MessageStatus status);

    @Query("SELECT m FROM MessageEntity m WHERE m.status = 'PENDING_SEND' AND (m.nextRetryAt IS NULL OR m.nextRetryAt <= :now)")
    List<MessageEntity> findPendingRetries(Instant now);
}
