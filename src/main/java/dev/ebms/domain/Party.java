package dev.ebms.domain;

/**
 * A party in an ebMS message header. {@code role} is the ebMS 2.0 {@code eb:Role} of the
 * party within the CPA's collaboration; it is optional, and omitted from the header when null
 * rather than written as an empty element.
 */
public record Party(String partyId, String partyIdType, String role) {

    /** Party with no role, for call sites that do not know one. */
    public Party(String partyId, String partyIdType) {
        this(partyId, partyIdType, null);
    }

    public static Party of(String partyId) {
        return new Party(partyId, null, null);
    }

    public Party withRole(String newRole) {
        return new Party(partyId, partyIdType, newRole);
    }
}
