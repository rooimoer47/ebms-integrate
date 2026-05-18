package dev.ebms.domain;

import java.time.Duration;

public record Cpa(
        String cpaId,
        Party fromParty,
        Party toParty,
        String transportUrl,
        boolean ackRequested,
        boolean duplicateElimination,
        int retries,
        Duration retryInterval
) {}
