package dev.ebms.domain.exception;

public class MessageParseException extends RuntimeException {

    private final String ebmsErrorCode;

    public MessageParseException(String message) {
        super(message);
        this.ebmsErrorCode = "OtherXml";
    }

    public MessageParseException(String message, String ebmsErrorCode) {
        super(message);
        this.ebmsErrorCode = ebmsErrorCode;
    }

    public MessageParseException(String message, Throwable cause) {
        super(message, cause);
        this.ebmsErrorCode = "OtherXml";
    }

    public MessageParseException(String message, String ebmsErrorCode, Throwable cause) {
        super(message, cause);
        this.ebmsErrorCode = ebmsErrorCode;
    }

    public String getEbmsErrorCode() {
        return ebmsErrorCode;
    }
}
