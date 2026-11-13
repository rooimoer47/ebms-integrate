-- The CPA store of record (P2-1).
--
-- Decision A on the roadmap: cpa-service owns the CPA lifecycle and pushes agreements to us as
-- XML, so the database is where they live. Files survive only as a bootstrap for environments
-- with no cpa-service (docker-compose, CI).
--
-- The row keeps the document itself, not our projection of it. Our Cpa record is a flattened
-- nine-field view; GET /cpas/{cpaId} has to answer with the agreement, and everything we do not
-- model yet -- PersistDuration, sync-reply mode, signing and encryption requirements,
-- compression -- is in the document and nowhere else.
CREATE TABLE cpas (
    -- The cpaId is the identity everywhere else in the system, so it is the key here too
    -- rather than a surrogate id with a unique index beside it.
    cpa_id       TEXT        PRIMARY KEY,
    -- TEXT, not BYTEA: it is XML, it gets read by a human in psql during an incident, and
    -- Postgres stores it as UTF-8 either way. cpa-service's own cpa.cpa_content is TEXT too,
    -- so the two stores stay comparable.
    document     TEXT        NOT NULL,
    -- Which parser projects this document. CPPA 2.0 XML is the upload format; the YAML form is
    -- accepted only from the file bootstrap, never over the API.
    content_type TEXT        NOT NULL DEFAULT 'application/xml',
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Who uploaded it. Null until there is a principal on the request to record.
    uploaded_by  TEXT
);
