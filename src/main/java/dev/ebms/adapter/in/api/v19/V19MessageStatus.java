package dev.ebms.adapter.in.api.v19;

/**
 * The {@code MessageStatus} of the ebms-core v19 REST contract, which our clients deserialise
 * into their generated models.
 *
 * <p>Only the first three values come from ebMS 2.0 itself, where the Message Status Service
 * answers whether a <em>remote</em> MSH recognises a message id. The rest are ebms-core's own
 * extension for tracking its send lifecycle, so the mapping in {@link MessageStatusMapper} is
 * a compatibility decision rather than anything the specification dictates.
 *
 * <p>Modelled as a bare string enum to match the copy of the contract our clients are built
 * against ({@code jms-producer/src/main/resources/openapi/ebms.yml}, version 18). Note that
 * ebms-core 2.20.x models this as an object of {@code {timestamp, status}} instead; the
 * clients' copy is the one that has to deserialise.
 */
public enum V19MessageStatus {
    UNAUTHORIZED,
    NOT_RECOGNIZED,
    RECEIVED,
    PROCESSED,
    FORWARDED,
    FAILED,
    CREATED,
    DELIVERY_FAILED,
    DELIVERED,
    EXPIRED
}
