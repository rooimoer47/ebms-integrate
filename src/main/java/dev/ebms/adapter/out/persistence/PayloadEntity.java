package dev.ebms.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "payloads")
public class PayloadEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private MessageEntity message;

    @Column(name = "content_id", nullable = false)
    private String contentId;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private byte[] content;

    protected PayloadEntity() {}

    public PayloadEntity(UUID id, MessageEntity message, String contentId, String mimeType, byte[] content) {
        this.id = id;
        this.message = message;
        this.contentId = contentId;
        this.mimeType = mimeType;
        this.content = content;
    }

    public UUID getId() { return id; }
    public MessageEntity getMessage() { return message; }
    public String getContentId() { return contentId; }
    public String getMimeType() { return mimeType; }
    public byte[] getContent() { return content; }
}
