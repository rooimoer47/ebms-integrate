package dev.ebms.domain;

public record Party(String partyId, String partyIdType) {

    public static Party of(String partyId) {
        return new Party(partyId, null);
    }
}
