package dev.ebms.domain;

public enum MessageStatus {
    RECEIVED,
    DELIVERED,
    PENDING_SEND,
    SENT,
    ACKED,
    FAILED
}
