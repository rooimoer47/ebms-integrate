package dev.ebms.domain.exception;

public class CpaNotFoundException extends RuntimeException {

    public CpaNotFoundException(String cpaId) {
        super("CPA not found: " + cpaId);
    }
}
