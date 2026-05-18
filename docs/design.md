# ebMS 2 MSH — Design

## What we are building

A **Message Service Handler (MSH)** — the infrastructure component that sits between your application and your trading partners. It speaks ebMS 2.0 over HTTP on the outside and exposes a clean internal API to your application on the inside.

```
[Trading Partner MSH] <── HTTP/SOAP ──> [Our MSH] <── Internal API ──> [Your Application]
```

---

## MVP Scope

**In:**
- Receive an ebMS 2 message over HTTP (SOAP 1.1 with MIME attachments)
- Send an ebMS 2 message to a remote MSH over HTTP
- CPA-driven configuration (transport URL, reliability settings per CPA)
- Acknowledgment generation (async) and reception
- Duplicate elimination (idempotent receive by MessageId)
- Retry with configurable interval and max retries (driven by CPA)
- Internal API: submit outbound message, query received messages, upload CPA

**Out of MVP (growth path):**
- XML Digital Signatures and encryption
- Synchronous reply
- Message ordering (MessageOrder header)
- SMTP transport
- CPA negotiation (CPPA)

---

## Architecture

Hexagonal architecture (ports & adapters). The domain holds the ebMS protocol logic and knows nothing about HTTP, databases, or Spring. Adapters plug in around it.

```
┌──────────────────────────────────────────────────────────────┐
│                     Adapters (Inbound)                       │
│  MshController (SOAP/HTTP)   AdminController (REST/JSON)     │
└───────────────────────┬──────────────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────────────┐
│                   Application Services                        │
│  ReceiveMessageService  SendMessageService  RetryService      │
└───────┬─────────────────────────────┬────────────────────────┘
        │   Domain                    │
┌───────▼─────────────────────────────▼────────────────────────┐
│  EbmsMessage  MessageHeader  Payload  Cpa  MessageStatus      │
└───────┬─────────────────────────────┬────────────────────────┘
        │                             │
┌───────▼──────────┐   ┌─────────────▼──────────────────────────┐
│  HttpTransport   │   │  JpaMessageRepository                  │
│  (outbound)      │   │  FileCpaRepository / JpaCpaRepository  │
└──────────────────┘   └────────────────────────────────────────┘
```

---

## Domain Model

```
EbmsMessage
  messageId        String          -- globally unique, assigned by sender
  conversationId   String
  cpaId            String          -- links to a CPA
  from             Party           -- partyId + partyIdType
  to               Party
  service          String
  action           String
  timestamp        Instant
  payloads         List<Payload>
  direction        INBOUND | OUTBOUND
  status           MessageStatus

MessageStatus
  RECEIVED         -- inbound, persisted, not yet delivered to app
  DELIVERED        -- inbound, handed to app
  PENDING_SEND     -- outbound, queued
  SENT             -- outbound, HTTP 200 received
  ACKED            -- outbound, acknowledgment received from partner
  FAILED           -- outbound, all retries exhausted

Payload
  contentId        String          -- MIME Content-Id
  mimeType         String
  content          byte[]          -- stored in DB for MVP; file/object store later

Cpa
  cpaId            String
  fromParty        String
  toParty          String
  transportUrl     String          -- where to POST outbound messages
  ackRequested     boolean
  duplicateElim    boolean
  retries          int
  retryInterval    Duration
  rawXml           String          -- full CPA XML kept for auditability
```

---

## Components

### MshController
Spring MVC endpoint at `POST /ebms/msh`. Receives `multipart/related` (SOAP with Attachments), delegates to `SoapMimeParser` to extract the `EbmsMessage`, then calls `ReceiveMessageService`. Returns HTTP 200 with an async acknowledgment SOAP envelope, or empty 200 if ack is not requested.

### SoapMimeParser / SoapMimeSerializer
Translates between raw HTTP bodies and `EbmsMessage`. The only place that touches JAXB and MIME parsing. Keeping this isolated means adding signature verification later is a step added to the parser pipeline — nothing else changes.

### ReceiveMessageService
1. Parse → validate required header fields
2. Load CPA by `cpaId`
3. Check `MessageId` in the store — if already seen, re-send original ack and stop (duplicate elimination)
4. Persist with status `RECEIVED`
5. If `AckRequested`, build and return acknowledgment message

### SendMessageService
Accepts a send request from the internal API, looks up the CPA, assigns `MessageId` and `Timestamp`, persists as `PENDING_SEND`, then triggers an immediate delivery attempt.

### RetryService
Scheduled job (Spring `@Scheduled`, every 30 s). Loads all `PENDING_SEND` messages where `nextRetryAt <= now`. For each: calls `HttpTransport`, updates status. On HTTP success → `SENT`. On failure → increment `retryCount`; if `retryCount >= cpa.retries` → `FAILED`; else update `nextRetryAt = now + cpa.retryInterval`.

### HttpTransport
Outbound adapter. Uses Spring's `RestClient`. Single responsibility: `POST` a serialized ebMS message to a URL and return the raw response. Timeout configuration lives here.

### CpaRepository
MVP: load CPAs from a watched directory of XML files on startup, with a reload endpoint. Switch to database-backed storage later with no domain changes.

---

## Data Model (PostgreSQL)

```sql
messages (
  id              UUID PRIMARY KEY,
  message_id      TEXT UNIQUE NOT NULL,      -- ebMS MessageId
  conversation_id TEXT NOT NULL,
  cpa_id          TEXT NOT NULL,
  from_party_id   TEXT NOT NULL,
  from_party_type TEXT,
  to_party_id     TEXT NOT NULL,
  to_party_type   TEXT,
  service         TEXT NOT NULL,
  action          TEXT NOT NULL,
  direction       TEXT NOT NULL,             -- INBOUND | OUTBOUND
  status          TEXT NOT NULL,
  timestamp       TIMESTAMPTZ NOT NULL,      -- from ebMS header
  created_at      TIMESTAMPTZ NOT NULL,
  retry_count     INT NOT NULL DEFAULT 0,
  next_retry_at   TIMESTAMPTZ
)

payloads (
  id          UUID PRIMARY KEY,
  message_id  UUID NOT NULL REFERENCES messages(id),
  content_id  TEXT NOT NULL,
  mime_type   TEXT NOT NULL,
  content     BYTEA NOT NULL
)

cpas (
  id          UUID PRIMARY KEY,
  cpa_id      TEXT UNIQUE NOT NULL,
  from_party  TEXT NOT NULL,
  to_party    TEXT NOT NULL,
  raw_xml     TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL
)
```

Schema managed with Flyway.

---

## Internal API (REST/JSON)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/messages` | Submit a message to send |
| `GET` | `/api/messages?direction=&status=` | List messages |
| `GET` | `/api/messages/{id}` | Get message + payloads |
| `GET` | `/api/messages/{id}/payloads/{contentId}` | Download a payload |
| `POST` | `/api/cpas` | Upload a CPA (XML body) |
| `GET` | `/api/cpas` | List loaded CPAs |
| `POST` | `/api/cpas/reload` | Reload CPAs from disk |

---

## Technology Stack

| Concern | Choice | Reason |
|---|---|---|
| Language | Java 25 (LTS) | Records, sealed classes, virtual threads |
| Framework | Spring Boot 4 (milestone) | Forward-looking for a new project; Spring Framework 7 baseline |
| Build | Maven | Already established |
| Database | PostgreSQL | Reliable; native BYTEA for payload storage |
| Migrations | Flyway | SQL-first, no surprises |
| HTTP client | Spring `RestClient` | Already on classpath |
| XML/SOAP | JAXB + Jakarta XML Binding | Standard; avoids pulling in a heavy WS stack |
| MIME parsing | Jakarta Mail / Apache James MIME4J | For `multipart/related` |

> **Spring Boot 4 note:** As of project start, Spring Boot 4 is in milestone phase (GA expected late 2025). We adopt it early to avoid migrating later. If a milestone introduces a blocking issue, pin to the latest milestone and upgrade when fixed.

---

## Growth Path

### XML Digital Signatures

ebMS 2 uses **XML Digital Signatures (XMLDSig)** — a W3C standard for signing XML documents. The signing MSH uses its RSA or EC **private key** to sign the SOAP header and payload references. The receiving MSH verifies the signature using the sender's **X.509 certificate**.

This gives two guarantees:
- **Origin**: the message genuinely came from the claimed trading partner
- **Integrity**: nothing was changed in transit (beyond what TLS already provides, but message-level signing survives logging and intermediaries)

Implementation: add a `SignatureVerifier` step in `SoapMimeParser` and a `Signer` step in `SoapMimeSerializer`. The crypto is done by **Apache Santuario** (XML Security for Java). Keys live in a **PKCS12 KeyStore**; for production, this moves to **HashiCorp Vault** or a hardware HSM. Trading partners exchange X.509 certificates out-of-band (often self-signed in B2B contexts).

### XML Encryption

The sending MSH encrypts the payload with the **recipient's public certificate** before sending. Only the recipient can decrypt it. This provides confidentiality at the message level, which matters when messages are logged, stored, or relayed through intermediaries. Same libraries as signing; adds a `PayloadEncryptor` / `PayloadDecryptor` step.

### Other growth path items

| Feature | What it adds |
|---|---|
| SMTP transport | New `SmtpTransport` adapter implementing the same outbound port — zero domain changes |
| Large payloads | Swap BYTEA storage for S3/filesystem by replacing only the `PayloadStore` adapter |
| Synchronous reply | Handle the ebMS `SyncReply` header; return response message in same HTTP response |
| Message ordering | Enforce `MessageOrder` sequence numbers per `ConversationId` |
| Metrics & tracing | Micrometer counters in application services; no domain changes |
| Multi-tenancy | Partition `messages` and `cpas` by `tenant_id`; inject tenant context in adapters |
