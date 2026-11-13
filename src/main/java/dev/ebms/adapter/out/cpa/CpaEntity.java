package dev.ebms.adapter.out.cpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A stored CPA: the document as it arrived, plus what is needed to project and audit it. */
@Entity
@Table(name = "cpas")
public class CpaEntity {

    @Id
    @Column(name = "cpa_id", nullable = false)
    private String cpaId;

    @Column(name = "document", nullable = false)
    private String document;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    protected CpaEntity() {
    }

    public CpaEntity(String cpaId, String document, String contentType, Instant updatedAt,
                     String uploadedBy) {
        this.cpaId = cpaId;
        this.document = document;
        this.contentType = contentType;
        this.updatedAt = updatedAt;
        this.uploadedBy = uploadedBy;
    }

    public String getCpaId() { return cpaId; }

    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
}
