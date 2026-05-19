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

    public static class PartyConfig {
        public String partyId;
        public String partyIdType;
    }
}
