package dev.ebms.domain.exception;

public class DuplicateMessageException extends RuntimeException {

    public DuplicateMessageException(String messageId) {
        super("Duplicate message received: " + messageId);
    }
}
