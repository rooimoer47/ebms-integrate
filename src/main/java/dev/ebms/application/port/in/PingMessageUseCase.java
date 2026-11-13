package dev.ebms.application.port.in;

public interface PingMessageUseCase {

    /**
     * Perform the ebMS 2.0 Ping/Pong exchange against the partner named by a CPA.
     *
     * <p>The ping is not persisted. It carries no business content, and storing it would put
     * connectivity checks into the message history the admin console shows.
     *
     * @param cpaId       the CPA naming the partner and its endpoint
     * @param fromPartyId our party id, URN-prefixed as the callers send it
     * @param toPartyId   the partner's party id
     */
    PingOutcome ping(String cpaId, String fromPartyId, String toPartyId);

    enum PingOutcome {
        /** The partner answered with a Pong. */
        PONG,
        /** The partner was reachable but did not answer with a Pong. */
        NO_PONG,
        /** No CPA with that id, so there is nothing to ping. */
        CPA_UNKNOWN
    }
}
