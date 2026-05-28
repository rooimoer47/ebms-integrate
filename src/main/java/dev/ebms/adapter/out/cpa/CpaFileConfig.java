package dev.ebms.adapter.out.cpa;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/** Jackson-mapped structure for a YAML CPA file. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CpaFileConfig {

    String cpaId;
    PartyConfig fromParty;
    PartyConfig toParty;
    String transportUrl;
    boolean ackRequested;
    boolean duplicateElimination;
    int retries = 3;
    long retryIntervalSeconds = 60;
    /** Optional path to the partner's X.509 certificate (PEM or DER) for payload encryption. */
    String recipientCertPath;

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class PartyConfig {
        String partyId;
        String partyIdType;
    }
}
