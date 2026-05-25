package dev.ebms.adapter.out.cpa;

/** Jackson-mapped structure for a YAML CPA file. */
public class CpaFileConfig {

    public String cpaId;
    public PartyConfig fromParty;
    public PartyConfig toParty;
    public String transportUrl;
    public boolean ackRequested;
    public boolean duplicateElimination;
    public int retries = 3;
    public long retryIntervalSeconds = 60;
    /** Optional path to the partner's X.509 certificate (PEM or DER) for payload encryption. */
    public String recipientCertPath;

    public static class PartyConfig {
        public String partyId;
        public String partyIdType;
    }
}
