# ebms-integrate 0.1.0 — Release Design

> Status: **reviewed 2026-09-08** — the five decisions in section 3 are settled and folded in.
> The stories themselves remain proposals; nothing has been built yet.
> Companion to `docs/design.md` (the original MVP architecture), which remains accurate for the
> hexagonal structure. This document defines what has to change to get from "working MVP" to
> "deployable 0.1.0 that Logius can run in place of ebms-core".

---

## 1. Goal of 0.1.0

Ship a Message Service Handler that a team currently running **ebms-core** can deploy with a Helm
chart, point their existing services at, and operate with confidence.

Three things define "done":

1. **Safe to expose.** Signature verification actually proves origin, the management API is
   authenticated, and no unbounded input can take the process down.
2. **Behaviourally familiar.** Client applications written against ebms-core's REST and JMS
   contracts keep working with a URL change and, at most, a trivial adapter — not a rewrite.
3. **Provably correct.** Coverage is measured, static analysis is reproducible, and there is a
   performance baseline rather than a guess.

### Explicit non-goal

We are **not** re-implementing ebms-core. We are matching its *edges* — the REST contract, the JMS
event shape, the message lifecycle vocabulary — while keeping the inside small, modern, and ready
for ebMS3/AS4 to be added alongside ebMS2. Where ebms-core has a feature we do not need
(MTOM, URL mappings, certificate mappings, five database dialects), we say so and leave it out.

---

## 2. Where we are today

Verified against the current `master` (build green, 51 tests across 10 classes, ~2,800 lines of
main code).

**Works:** inbound `POST /ebms/msh` (SOAP 1.1, single-part and multipart/related), duplicate
elimination, synchronous acknowledgment generation, inbound ack processing, outbound send with
retry, CPA loading from YAML and XML files, XML signing and verification (mechanically), XML
payload encryption, Prometheus metrics, a JMS event publisher, a four-endpoint ebms-core shim.

**Missing or wrong:** everything in sections 4–12 below.

---

## 3. Decisions — settled 2026-09-08

All five are decided. The stories below assume these answers; two facts are still outstanding from
the engineer and are called out in the table rather than left implicit.

| # | Decision | Settled as | What that means for the work |
|---|---|---|---|
| D1 | **CPA source of truth** | **The database.** `cpa-service` uploads a CPA to us, we persist it, and every pod in every availability zone honours it immediately. | Reverses the earlier read-only answer (revised 2026-09-08). Adds story F5 for the store itself and turns D5 into a full read/write implementation of ebms-core's CPA endpoints; F1 becomes a bootstrap rather than the source of truth. Two consequences worth naming rather than discovering: **B3 stops being optional**, because an unauthenticated `POST /cpas` lets anyone register a trading partner and point our outbound traffic at themselves; and **the per-pod CPA cache has to go**, because it is the only thing that would make two pods disagree. |
| D2 | **ebms-core API base path** | **Configurable prefix** — one property, `ebms.compat.base-path`. | No code change to re-point, and the default can be set to whatever the Logius deployment uses today. **Still open with the engineer:** what that path currently is. Clients then change only the hostname. |
| D3 | **Compat API vs. our own API** | **Keep both.** | `/api/**` is the API we own and evolve for ebMS3/AS4; the compat API is a frozen adapter over ebms-core's contract, deprecable once clients migrate. The rule that keeps this honest: ebms-core's vocabulary must not leak inward — every translation lives in the adapter, never in a service or the domain (see story D3). |
| D4 | **Auth for the management API** | **Use the mechanism we already have (mTLS), behind a pluggable configuration.** | Story B3 is written so that adding or swapping a mechanism — OAuth2 resource server, basic auth — is a configuration class and a property, not a controller change. |
| D5 | **Message retention / TTL** | **Configurable TTL; `-1` (or absent) means never expires.** | Per-CPA with a global default, `-1` preserving today's keep-forever behaviour so nothing changes on upgrade. This turns out to be exactly what both specs and ebms-core already do — see story C4, which also picks up the missing `TimeToLive` handling (E8). |

---

## 4. Epic A — Platform and build

### A1. Move to Spring Boot 4
**As** a maintainer, **I want** the project on the current Spring Boot GA release **so that** we
are on a supported baseline and not on a version we already tried to leave.

Spring Boot **4.1.1** is GA (verified on Maven Central, as are `spring-boot-starter-activemq`,
`-artemis`, `-security` and `-web` at that version). The Java baseline is 17; we stay on **Java 21
LTS**. The Spring milestone repository is no longer needed and must be removed.

**Acceptance criteria**
- `pom.xml` parent is `spring-boot-starter-parent:4.1.1`; no milestone/snapshot repositories remain.
- `mvn package` is green with no new warnings; all 51 existing tests still pass.
- `spring-boot-starter-activemq` still resolves and the JMS publisher still works against ActiveMQ Classic (Logius runs Classic, not Artemis — confirm).
- `io.zonky.test:embedded-postgres` and `wiremock-standalone` are on versions compatible with the SB4 dependency set.
- `CLAUDE.md` is corrected — it currently claims Spring Boot 4 while the pom says 3.4.5.
- A short note in this doc records anything that had to change (RestClient API, Jackson 3, config property renames).

**Watch for:** Jackson 3 in the SB4 dependency set (`tools.jackson` package move) affects
`YamlCpaRepository`'s `ObjectMapper`; `spring.http.client.*` property names for the timeouts added
in C2; `@ConfigurationProperties` binding of `EbmsSecurityProperties`.

**Done.** All 51 tests pass on 4.1.1. Four things had to change, and the first two are the ones to
know about when adding any dependency from here on:

1. **Boot 4 split the auto-configurations out of `spring-boot-autoconfigure` into per-technology
   modules.** Declaring a third-party library no longer brings its auto-configuration, and nothing
   warns you — the context simply fails later, in our case with `Schema validation: missing table
   [messages]` because Flyway never ran. `spring-boot-flyway` and `spring-boot-restclient` are now
   explicit dependencies. Starters are unaffected: they pull their own module.
2. **`TestRestTemplate` is gone**, with no drop-in replacement on the Boot 4 classpath.
   `EndToEndTest` now uses `RestClient`, which is what production code already uses. One behavioural
   trap: `RestClient` throws on 4xx/5xx where `TestRestTemplate` returned the response, so the tests
   that assert on `400` need a permissive status handler. Spring Framework 7's `RestTestClient` is
   the more idiomatic replacement if we later want fluent assertions.
3. **Jackson 3 confirmed**, and Boot 4 imports both Jackson BOMs — so Jackson 2 stays resolvable and
   the duplicate goes unnoticed. `YamlCpaRepository` moved to `tools.jackson.dataformat.yaml.YAMLMapper`
   and the Jackson 2 dependency is dropped; only `jackson-annotations` remains, which is Jackson 3's
   own (it keeps the `com.fasterxml.jackson.annotation` package). `CpaFileConfig` needed no change.
4. `PrometheusMetricsExportAutoConfiguration`, `JmsAutoConfiguration` and `ActiveMQAutoConfiguration`
   were each verified present on the resolved classpath, since no test covers them and their absence
   would be silent.

Untouched by the upgrade: JPA entities and Hibernate mappings, the MIME and XML-signature code, and
every configuration property we set. No compiler deprecation warnings.

---

### A2. Measure test coverage
**As** a reviewer, **I want** a coverage report **so that** "sufficient tests" is a number, not an
opinion.

**Acceptance criteria**
- JaCoCo is bound to `verify`; `mvn package` produces `target/site/jacoco/`.
- CI publishes the report as a build artifact.
- A coverage floor is enforced (**start at 70% line / 60% branch**, raised once the gaps in Epic H are closed). The build fails below the floor.
- Domain records and generated code are excluded from the denominator.

---

### A3. Reproducible SonarQube run
**As** a developer, **I want** one command that spins up SonarQube and scans the project **so that**
static-analysis results are reproducible instead of ad hoc.

Today there is a two-line `sonar-project.properties`, an S3776 suppression in the pom, and nothing
that runs a scan. The "zero issues" claim in recent commits is not reproducible by anyone else.

**Acceptance criteria**
- `docker-compose.yml` gains a `sonarqube` service (community edition) behind a compose profile so
  it does not start during normal local development.
- `scripts/sonar.sh` brings the container up, waits for health, runs `mvn verify sonar:sonar` with
  the JaCoCo report wired in as coverage evidence, and prints the dashboard URL.
- The script is idempotent and works from a cold start with no manual token creation (bootstrap the
  token, or document the one manual step precisely).
- `sonar-project.properties` records the real settings: sources, tests, coverage report path,
  exclusions.
- The S3776 project-wide suppression is reviewed. Blanket-disabling cognitive complexity across
  `**` hides real problems; narrow it to the parser/serializer classes that genuinely need it, or
  drop it and refactor.
- A CI job runs the scan on `master` (optional for PRs if no server is available).

---

### A4. Release hygiene
**As** an operator, **I want** the artifact to identify itself **so that** I know what is running.

**Acceptance criteria**
- Version becomes `0.1.0-SNAPSHOT` on `master` and the release workflow stamps `0.1.0` from the tag (already wired — verify it works end to end).
- `/actuator/info` exposes version, git commit, and build time.
- `.idea/` is removed from version control and added to `.gitignore`.
- `README.md` is replaced (see J1).

---

## 5. Epic B — Security (release blockers)

### B1. Signature verification must prove origin
**As** a trading partner, **I want** my counterparty to reject messages signed by anyone else **so
that** a signature means something.

`XmlSignatureService.verify()` (`XmlSignatureService.java:107-116`) extracts the X.509 certificate
from the message's own `KeyInfo` and validates the signature against it. Any party can self-sign
and pass. This is the single most serious defect in the codebase: signing currently provides
integrity but **no authentication whatsoever**.

**Acceptance criteria**
- The signing certificate is validated against a configured trust anchor before the signature is checked.
- The certificate is bound to the sending party: each CPA names the partner's expected certificate (or its subject DN / fingerprint), and a signature from any other certificate is rejected even if that certificate chains to a trusted CA.
- Certificate validity dates are checked; expired certificates are rejected.
- Revocation checking (CRL or OCSP) is configurable and **off by default** in 0.1.0, with the hook in place.
- Rejection returns an ebMS `SecurityFailure` error, and increments a metric.
- Tests: valid signature from the pinned cert passes; valid signature from an untrusted self-signed cert is rejected; expired cert is rejected; cert not matching the CPA's party is rejected.

---

### B2. Per-CPA security policy, enforced
**As** an operator, **I want** to declare that a partner must sign and encrypt **so that** an
attacker cannot downgrade the message by stripping security elements.

`verify()` returns silently when no `Signature` element is present (`XmlSignatureService.java:100`),
and `Cpa` has no security flags at all. A signed-traffic partner can be stripped to plaintext today
and we will accept it.

**Acceptance criteria**
- `Cpa` gains `signingRequired`, `encryptionRequired`, and the partner certificate from B1; all are settable from both the YAML and the XML CPA formats.
- An unsigned message on a CPA with `signingRequired` is rejected with `SecurityFailure`.
- An unencrypted payload on a CPA with `encryptionRequired` is rejected.
- Outbound messages are signed/encrypted when the CPA requires it, and the send fails loudly (not silently unsigned) if no key is configured.
- Tests cover each of the four reject paths.

---

### B3. Authenticate the management and compat APIs
**As** an operator, **I want** the APIs that can send messages on my behalf to require credentials
**so that** anyone with network access cannot impersonate us to our trading partners.

There is no `spring-boot-starter-security` in the pom. `/api/**`, `/ebms-core/**` and
`/actuator/prometheus` are open to anyone who can reach the port.

Decision D1 raises the stakes considerably. Once `POST /cpas` writes to the database, an
unauthenticated caller can register a trading partner and choose its `transportUrl` — which
redirects our outbound messages to a destination of their choosing, and does so persistently, across
every pod. **The CPA write endpoints must not be exposed before this story lands.**

**Acceptance criteria**
- Spring Security is added. `/api/**` and the compat API require authentication.
- Per decision D4 the mechanism is **mTLS**, reusing the keystore plumbing that already exists for outbound calls, and it is selected by configuration: the authentication mechanism is a swappable `SecurityFilterChain` configuration chosen by property, so adding OAuth2 resource-server or basic auth later touches no controller and no test of business behaviour.
- `/ebms/msh` stays open to unauthenticated HTTP (partners authenticate at the message and TLS layers, per B1/B4) but is subject to B5.
- `/actuator/health/liveness` and `/readiness` stay open for kubelet probes; `/actuator/prometheus` is either restricted or bound to a separate management port (preferred in k8s).
- Credentials come from environment/secret, never from a checked-in file.
- Tests assert 401 on each protected endpoint without credentials.

---

### B4. Authenticate inbound partners at the transport layer
**As** an operator, **I want** inbound TLS client-certificate authentication **so that** the MSH is
not reachable by arbitrary internet hosts.

mTLS today is outbound-only (`HttpMessageTransport`).

**Acceptance criteria**
- Optional inbound mTLS is configurable (server truststore, `client-auth: want|need`).
- When enabled, the presented client certificate is checked against the CPA's expected partner certificate.
- Documented as optional, because Logius may terminate TLS at the ingress — in which case the story is instead: document the required ingress configuration and trust the `X-Forwarded-*`/cert-forwarding headers only from the ingress.

---

### B5. Bound all untrusted input
**As** an operator, **I want** request size limits **so that** a single large POST cannot exhaust
the heap.

`MshController.java:36` reads the entire body into a `byte[]` with no cap, and payloads then go
into `BYTEA` in one piece.

**Acceptance criteria**
- A configurable maximum inbound message size (default proposal: **64 MB**), enforced before the body is fully buffered, returning a clear ebMS error.
- A configurable maximum payload count per message.
- MIME parsing is bounded (part count, per-part size).
- XXE protections in `SoapMimeParser.parseXml` are extended with entity-expansion and total-size limits (`FEATURE_SECURE_PROCESSING`, `jdk.xml.*` limits).
- A load test (H4) demonstrates the limit holds and the process survives.

---

### B6. Secret handling review
**As** an operator, **I want** no secrets in logs or config files **so that** the deployment passes
review.

**Acceptance criteria**
- Keystore passwords are read from environment/secret; `application.yml` has no defaults that could work in production.
- No secret is logged at any level; a test asserts this for the startup path.
- `EbmsSecurityProperties` is annotated so Spring's config-props actuator masks the values.

---

## 6. Epic C — Reliability and correctness (release blockers)

### C1. Take the network call out of the database transaction
**As** an operator, **I want** the outbound HTTP call to happen after the message is committed **so
that** a message on the wire always has a record.

`SendMessageService.send()` is `@Transactional` and calls `attemptSend()` inside it
(`SendMessageService.java:60-79`). Two consequences: a DB connection is held for the full duration
of the partner's socket, and a throw after a successful POST rolls back the row — message delivered,
no record, no retry, no audit.

**Acceptance criteria**
- Persist-and-commit is separated from send. The send is triggered after commit (transaction synchronisation, an outbox row, or an explicit two-phase service method).
- The status update after the send happens in its own short transaction.
- A test proves that a failure during/after transport does not lose the persisted message.
- No HTTP call is made while a transaction is open anywhere in the codebase.

---

### C2. Timeouts on every outbound call
**As** an operator, **I want** connect and read timeouts **so that** one unresponsive partner
cannot consume all our threads and connections.

Neither `RestClient` path in `HttpMessageTransport` sets a timeout, and the JDK client has no
default read timeout.

**Acceptance criteria**
- Configurable connect timeout (proposal: 10 s) and read timeout (proposal: 60 s), globally and overridable per CPA.
- A timeout is treated as a retryable failure, not a permanent one.
- A test with a deliberately slow WireMock stub proves the call returns within the timeout and schedules a retry.

---

### C3. Make retry safe for more than one replica
**As** an operator, **I want** to run two replicas **so that** the MSH is not a single point of
failure — without sending everything twice.

`RetryService.retryPending()` (`RetryService.java:41`) selects pending rows with no lock and no
scheduler coordination. Two pods deliver every message twice. This currently makes the service
single-instance-only, which is a poor fit for a Helm deployment.

**Acceptance criteria**
- Pending messages are claimed with `SELECT ... FOR UPDATE SKIP LOCKED` (or an equivalent claim column with a lease and expiry).
- A message is only ever in flight on one instance.
- The scheduler is safe to run on every replica; no leader election required.
- Test: two concurrent retry cycles against the same dataset deliver each message exactly once.
- Deployment note: `replicas: 2` becomes supported and the Helm chart should default to it.

---

### C4. Exponential backoff and expiry
**As** an operator, **I want** retries to back off and eventually stop **so that** a permanently
broken partner does not generate constant load forever.

Retry today is a fixed interval, `retryCount >= cpa.retries` → `FAILED`, and nothing ever produces
the `EXPIRED` event ebms-core clients may be listening for.

**Acceptance criteria**
- Exponential backoff with jitter, bounded by a per-CPA maximum interval; the fixed interval remains configurable for parity.
- A time-to-live per CPA with a global default, where **`-1` or an absent value means "never expires"** (decision D5). A message not delivered within it becomes `EXPIRED` and emits the `EXPIRED` event (D6).
- `FAILED` (retries exhausted) and `EXPIRED` (TTL elapsed) are distinct and both reachable.
- Tests for both terminal transitions, and for a `-1` TTL that never expires.

**This is not ours to invent — both specs already define it, and it is how ebms-core works.**
ebMS 2.0 has an optional `eb:MessageHeader/eb:TimeToLive` (a `dateTime`); CPPA 2.0 has
`tp:PersistDuration` on the receiver binding. ebms-core derives the one from the other in
`CPAUtils.getPersistTime`, and `DeliveryTaskHandler` expires a task once `Instant.now()` passes it —
`CREATED → EXPIRED`, plus an `onMessageExpired` event. When `PersistDuration` is absent,
`getPersistTime` returns null and the task never expires. **Our `-1` is exactly that null**, so the
chosen semantics match ebms-core precisely.

Two consequences: the TTL should be modelled as a duration on the CPA named for the spec concept
rather than as a bare configuration knob, and this story now depends on **E8**, because we neither
emit nor honour `TimeToLive` today.

---

### C5. Handle partner error responses
**As** an operator, **I want** an `eb:ErrorList` in the partner's response to mark the send as
failed **so that** rejected messages are visible.

`SendMessageService.isSynchronousAck()` only looks for an `Acknowledgment`. An HTTP 200 whose body
is an ebMS `ErrorList` is recorded as `SENT`.

**Acceptance criteria**
- A response containing `eb:ErrorList` marks the message failed, stores the error code and description, and emits a `FAILED` event.
- Severity is honoured: `Warning` does not fail the message, `Error` does.
- The error is exposed on the message detail API and in the compat status.
- Tests for both severities and for a SOAP Fault response.

---

### C6. Make duplicate elimination race-safe
**As** a trading partner, **I want** a redelivery to get an acknowledgment **so that** a retry storm
does not produce 500s.

`ReceiveMessageService.java:50` does check-then-insert. Two concurrent copies of the same message
race, the loser hits the unique constraint, and the partner receives an HTTP 500 — which makes them
retry, indefinitely.

**Acceptance criteria**
- The insert is the point of truth: catch the unique-constraint violation and treat it as a duplicate, returning the same acknowledgment the first delivery got.
- A concurrent test (N threads, same MessageId) yields exactly one stored row and N acknowledgments, zero 5xx.

---

### C7. Do not load payloads on list queries
**As** an operator, **I want** listing messages to be cheap **so that** the API stays usable as the
table grows.

`JpaMessageRepositoryAdapter.toDomain()` (`JpaMessageRepositoryAdapter.java:111`) always maps
payloads. The association is `LAZY`, so every list call N+1s and materialises every payload's bytes
for every row. `GET /api/messages` and `/ebms-core/unprocessedMessages` currently read the entire
payload table.

**Acceptance criteria**
- List queries project to a summary without touching payload content.
- Payload bytes are fetched only by the detail and download endpoints.
- All list endpoints are paginated with a bounded default page size.
- A test asserts the query count for a list of N messages is constant.

---

### C8. Store large payloads out of the row
**As** an operator, **I want** large payloads not to sit in the message row **so that** the database
stays healthy.

**Acceptance criteria**
- A `PayloadStore` outbound port with a database implementation (default) and a filesystem/S3 implementation.
- Payload content is streamed rather than fully buffered on the download path.
- Selection is configuration-driven; no domain or service change to switch.
- *(Deferrable past 0.1.0 if payloads at Logius are small — decide with real numbers from H4.)*

---

## 7. Epic D — ebms-core compatibility

This is the epic that decides whether the transition is smooth. The engineer's current position is
that `cpa-service`, `jms-producer` and `jms-consumer` must be adapted because "changing only the
URLs is insufficient". **The intent of this epic is to flip that**: we implement ebms-core's
contract closely enough that a URL change is, in fact, sufficient for the common paths.

The contract below was read from ebms-core `2.20.x`
(`core/resources/api/rest/ebms.json`, `cpas.json`, `EbMSRestController`, `CPARestController`,
`JMSMessageEventListener`). Anything not listed is deliberately out of scope.

### D1. Replace the shim with the real ebms-core message contract
**As** the owner of `jms-producer`, **I want** to keep posting the request body I post today **so
that** I only change a hostname.

Our current `EbmsCompatController` invents its own shapes (`POST /ebms-core/message` with
`{cpaId, conversationId, service, action, dataSources[{contentId, mimeType, content}]}`). Real
ebms-core is different in path, in nesting, and in field names.

**Target contract**

`POST {base}/messages` — body `MessageRequest`:
```json
{
  "properties": {
    "cpaId": "...", "fromPartyId": "...", "fromRole": "...",
    "toPartyId": "...", "toRole": "...", "service": "...", "action": "...",
    "conversationId": "...", "messageId": "...", "refToMessageId": "..."
  },
  "dataSources": [
    { "name": "...", "contentId": "...", "contentType": "...", "content": "<base64>" }
  ]
}
```
Required by ebms-core: `cpaId`, `fromPartyId`, `fromRole`, `service`, `action`.
**Response: `text/plain`, the messageId** — not JSON.

**Acceptance criteria**
- `POST {base}/messages` accepts exactly that body and returns the messageId as `text/plain`.
- `fromRole` / `toRole` are accepted and persisted (see F2 — the domain has no role concept today).
- A client-supplied `messageId` is honoured; absent, we generate one.
- `refToMessageId` is persisted and echoed back.
- Unknown fields are ignored rather than rejected, so minor client-version drift does not break.
- Contract tests assert the wire format field-by-field against the ebms-core OpenAPI document, which is committed to the repo under `docs/compat/`.

---

### D2. Message retrieval and processing endpoints
**As** the owner of `jms-consumer`, **I want** the same fetch-and-acknowledge loop **so that** my
consumer logic is unchanged.

| ebms-core | Semantics |
|---|---|
| `GET {base}/messages/unprocessed` | Returns a **JSON array of messageId strings** (not objects). Filters: `cpaId`, `fromPartyId`, `fromRole`, `toPartyId`, `toRole`, `service`, `action`, `conversationId`, `messageId`, `refToMessageId`, `maxNr` (default 0 = unlimited) |
| `GET {base}/messages/{messageId}?process=false` | Returns `Message` = `{properties: MessageProperties, dataSources: [...]}`; `process=true` marks it processed in the same call |
| `PATCH {base}/messages/{messageId}` | Marks the message processed |
| `PUT {base}/messages/{messageId}` | **Resends** the message; returns the new messageId as `text/plain` |
| `GET {base}/messages/{messageId}/status` | Returns `{timestamp, status}` |

**Acceptance criteria**
- All five endpoints implemented with those exact shapes, methods and content types.
- `maxNr` and every filter parameter work; `maxNr=0` means unlimited (bounded internally by C7's page cap, documented).
- `process=true` on GET is atomic with the read.
- `PUT` (resend) creates a new outbound message referencing the original.
- Contract tests per endpoint.

---

### D3. Message status vocabulary
**As** the owner of `jms-consumer`, **I want** the status values I already switch on **so that** my
state machine is unchanged.

**Where these values come from — half spec, half ebms-core.** Verified against the OASIS
`msg-header-2_0.xsd` and `nl.clockwork.ebms.EbMSMessageStatus` at the vendored commit:

| ebms-core value | id | On the wire as | Origin |
|---|---|---|---|
| `UNAUTHORIZED` | 0 | `UnAuthorized` | ebMS 2.0 `messageStatus.type` |
| `NOT_RECOGNIZED` | 1 | `NotRecognized` | ebMS 2.0 `messageStatus.type` |
| `RECEIVED` | 2 | `Received` | ebMS 2.0 `messageStatus.type` |
| `PROCESSED` | 3 | `Processed` | ebMS 2.0 `messageStatus.type` |
| `FORWARDED` | 4 | `Forwarded` | ebMS 2.0 `messageStatus.type` |
| `FAILED` | 5 | `Received` (aliased) | ebms-core |
| `CREATED` | 10 | — never on the wire | ebms-core |
| `DELIVERY_FAILED` | 11 | — | ebms-core |
| `DELIVERED` | 12 | — | ebms-core |
| `EXPIRED` | 13 | — | ebms-core |

The five spec values are the `StatusResponse/@messageStatus` a partner asks us for over ebMS
(story E2). They describe what a *receiving* MSH did with a message someone sent it — that is the
entire scope of the enum in the specification. ebMS 2.0 has no vocabulary at all for the sending
side, so ebms-core added five values of its own to the same enum and left `statusCode` null on four
of them. `FAILED` is the awkward one: an inbound failure that reports on the wire as `Received`,
which is lossy but spec-legal. ebms-core itself splits the enum back apart at runtime into
`getReceiveStatus()` and `getSendStatus()` — a good sign that the two concepts wanted to be two
types.

**So: copy core, or do better?** Both, in different places — which is precisely what decision D3
(keep both APIs) buys us.

- **Our domain and `/api/**` keep the two concepts separate and honest.** A lifecycle status
  (direction-aware, ours to evolve, extensible for ebMS3/AS4) and a `WireStatus` of exactly the five
  spec values, used only by the status service in E2. Conflating a local lifecycle with a wire enum
  is the one thing here we should not inherit.
- **The compat adapter projects our lifecycle onto ebms-core's ten values, exactly.** Same names,
  same numeric ids — they are persisted in ebms-core's own database and show up in operator
  tooling — and the same `FAILED → Received` aliasing.

Proposed projection:

| Ours | Direction | ebms-core | id |
|---|---|---|---|
| `RECEIVED`, `processed=false` | inbound | `RECEIVED` | 2 |
| `RECEIVED`, `processed=true` | inbound | `PROCESSED` | 3 |
| rejected: bad signature / untrusted certificate | inbound | `UNAUTHORIZED` | 0 |
| rejected: unknown CPA / party | inbound | `NOT_RECOGNIZED` | 1 |
| rejected: processing error | inbound | `FAILED` | 5 |
| `PENDING_SEND` | outbound | `CREATED` | 10 |
| `SENT` (awaiting ack) | outbound | `CREATED` | 10 |
| `ACKED` | outbound | `DELIVERED` | 12 |
| `FAILED` (retries exhausted) | outbound | `DELIVERY_FAILED` | 11 |
| `EXPIRED` (new, C4) | outbound | `EXPIRED` | 13 |

**Acceptance criteria**
- Two types exist and never mix: the lifecycle status on the domain, and a five-value wire status used by E2 only.
- The projection onto ebms-core's ten values lives in exactly one class in the compat adapter, is exhaustive over our lifecycle, and is unit-tested case by case; nothing outside that adapter mentions an ebms-core status name.
- Numeric ids are emitted wherever ebms-core emits them.
- `FORWARDED` is unused and documented as never emitted — we do not forward.
- `MessageStatus.DELIVERED` exists in our enum today and is set nowhere in the codebase. It is removed, or given a defined meaning distinct from `ACKED`, as part of this story.
- Review point: is `SENT → CREATED` right for your consumers, or should an unacknowledged send be distinguishable from one not yet attempted? ebms-core cannot tell them apart; we can, on `/api/**`, without breaking compat.

---

### D4. Ping service
**As** an operator, **I want** the ebms-core ping endpoint **so that** existing health checks and
partner connectivity tests keep working.

`POST {base}/ping/{cpaId}/from/{fromPartyId}/to/{toPartyId}` — returns 200 on a successful ebMS
Ping/Pong exchange with the partner. Depends on E1.

---

### D5. CPA endpoints
**As** the owner of `cpa-service`, **I want** the CPA endpoints to answer **so that** my service
does not need restructuring.

ebms-core exposes: `GET /cpas` (list ids), `GET /cpas/{cpaId}` (the CPA document), `POST /cpas`
(`text/plain` body, `?overwrite=`), `POST /cpas/validate`, `DELETE /cpas/{cpaId}`,
`DELETE /cpas/cache`.

**Acceptance criteria** — all six are real endpoints under decision D1. Depends on F5.
- All six implemented with ebms-core's shapes: `text/plain` request bodies, the `overwrite` query parameter, and the same status codes.
- `POST /cpas` with `overwrite=false` against an existing `cpaId` conflicts rather than silently replacing; with `overwrite=true` it replaces.
- `POST /cpas/validate` validates without persisting (F3).
- `DELETE /cpas/cache` succeeds and is documented as a no-op: with no per-pod cache (F5) there is nothing to clear. Keeping the endpoint means `cpa-service` needs no change for it.
- Every write endpoint sits behind authentication (B3) before it is exposed anywhere.
- Contract-tested against `docs/compat/ebms-core-cpas-api.json` (H3, J4).

---

### D6. Message events, JMS and REST
**As** the owner of `jms-consumer`, **I want** the same JMS messages on the same destinations **so
that** the consumer binding is unchanged.

Good news: `JmsMessageEventPublisher` already matches ebms-core's `JMSMessageEventListener` — same
ten string properties (`cpaId`, `fromPartyId`, `fromRole`, `toPartyId`, `toRole`, `service`,
`action`, `conversationId`, `messageId`, `refToMessageId`), same destination-per-event-type naming.
The remaining gaps:

**Acceptance criteria**
- **Destination type:** ebms-core supports `QUEUE` (destination named `RECEIVED`, `DELIVERED`, `FAILED`, `EXPIRED`) and `TOPIC` (`VirtualTopic.RECEIVED`, …). We only do queues. Add the topic option and a `ebms.jms.destination-type` property.
- **Message body type:** ebms-core has `JMS` (empty `Message`) and `JMS_TEXT` (`TextMessage` with body `"EbMS Message Context"`). We only produce the empty variant. Add the text variant behind a property. *Trivial to add, and a consumer expecting a `TextMessage` will fail without it.*
- **Roles:** we currently publish `fromRole`/`toRole` as empty strings. They must carry the real CPA roles (depends on F2).
- **Event filter:** ebms-core's `eventListener.filter` restricts which event types are published. Add the equivalent.
- **`EXPIRED` is never emitted today.** Fix with C4.
- **Event REST API:** implement `GET {base}/events/unprocessed` (same filter set as D2, returns `[{messageId, type}]`) and `PATCH {base}/events/{messageId}` (mark processed). This needs a `message_events` table with its own processed flag — events and messages are acknowledged independently in ebms-core.
- **Delivery guarantee:** events are published transactionally with the state change, or via an outbox. Today `JmsMessageEventPublisher` swallows failures with a warning, so an event can be silently lost. Decide and implement: at-least-once with an outbox is the safe default.
- Integration test with an embedded broker asserting destination, type and every property, for each event type.

---

### D7. Base path and coexistence
**As** the engineer writing the Helm chart, **I want** the compat API on the path the clients
already use **so that** only the service hostname changes.

**Acceptance criteria**
- The compat API's base path is a single configuration property (per D2 in section 3).
- Both APIs coexist: `/api/**` (ours) and `{base}/**` (ebms-core-shaped).
- A compatibility matrix (appendix A) is kept current, marking every ebms-core endpoint as
  implemented / not-implemented / deliberately-out-of-scope.
- The ebms-core OpenAPI documents are committed under `docs/compat/` as the reference the contract
  tests assert against.

---

### D8. Explicitly out of scope
Documented, not built, with a one-line rationale each: MTOM endpoints (`/messages/mtom`,
`/messages/mtom/{messageId}`), URL mappings, certificate mappings, the SOAP/WSDL variant of the
service, and non-PostgreSQL database dialects. If any of these turn out to be in use at Logius, they
become stories — **ask before assuming they are not**.

---

## 8. Epic E — ebMS 2.0 protocol completeness

### E1. Ping/Pong service
The ebMS 2.0 Ping service (`urn:oasis:names:tc:ebxml-msg:service` / `Ping` → `Pong`). Required by
D4 and commonly used for partner connectivity monitoring.
**AC:** inbound `Ping` is answered with `Pong` without persisting a business message; outbound ping
via the API returns success/failure with timing; not counted in business metrics.

### E2. Message Status service
The ebMS 2.0 `StatusRequest` / `StatusResponse` service, so partners can query the status of a
message they sent us.
**AC:** inbound `StatusRequest` returns a spec-compliant `StatusResponse` with `messageStatus` and
timestamp; unknown messages return `NotRecognized`; outbound status requests exposed on the API.

### E3. Synchronous vs. asynchronous reply
Today we always return the acknowledgment in the HTTP response, regardless of what the CPA says —
a deviation from `docs/design.md`, which specified async, and from the spec's `SyncReply` semantics.
**AC:** CPA carries the sync-reply mode; sync mode returns the ack in the HTTP response (current
behaviour); async mode returns an empty 200 and delivers the ack as a separate outbound message;
the inbound `SyncReply` header is honoured; both modes tested end to end.

### E4. Validate the Manifest on receive
We generate `eb:Manifest` on send but never check it on receive.
**AC:** every `eb:Reference` resolves to a MIME part present in the message; a mismatch is rejected
with `MimeProblem`/`Inconsistent`; parts not referenced by the manifest are rejected or logged per
configuration.

### E5. Payload compression
The Digikoppeling profile ebms-core targets uses gzip payload compression
(`eb:CompressionType: application/gzip`).
**AC:** inbound compressed payloads are transparently decompressed; outbound compression is enabled
per CPA; the compression property appears in the manifest reference; round-trip test.
**Confirm with Logius whether their partners use it** — if yes, this is a blocker, not an
enhancement.

### E6. Message ordering
`eb:MessageOrder` — sequence numbers per conversation.
**AC:** *(proposed for 0.2.0 unless Logius needs it — confirm.)* If needed: sequence numbers are
enforced per `ConversationId`, out-of-order messages are held, and gaps are surfaced.

### E7. Timestamp robustness
`Instant.parse` handles `Z` and numeric offsets, but rejects a zone-less `xsd:dateTime`. Real-world
partners send imperfect timestamps.
**AC:** lenient parsing with a defined fallback; a malformed timestamp produces a clear ebMS error
rather than a generic parse failure; tests for `Z`, `+02:00`, fractional seconds, and zone-less.

### E8. `TimeToLive` on the wire
We neither emit nor honour `eb:MessageHeader/eb:TimeToLive`. Decision D5 and story C4 both need it,
and a partner sending it today is silently ignored — we would happily process a message the sender
considers dead.
**AC:** outbound messages carry `TimeToLive` derived from the CPA's persist duration when one is
configured, and omit the element entirely when the TTL is `-1`; an inbound message whose
`TimeToLive` has already passed is rejected with the spec's `TimeToLive` error rather than
processed; clock-skew tolerance is configurable; tests for emit, omit, expired-inbound, and skew.

---

## 9. Epic F — CPA model

### F1. CPAs bootstrapped from a ConfigMap
**As** the engineer deploying the chart, **I want** the ConfigMap I have already built to seed a
fresh environment **so that** a new namespace comes up with known CPAs and the chart work is not
wasted.

Under decision D1 the database is the source of truth, so the mounted files are no longer *the* CPAs
— they are a bootstrap. That keeps the `ebms-integrate-cpas` ConfigMap useful (item 1 on the
deployment list) without creating two competing sources of truth.

**AC:** on startup, **and only when the CPA table is empty**, CPAs in the mounted directory are
loaded into the database and logged as a bootstrap. Seeding never updates or resurrects an existing
CPA — otherwise deleting a CPA through the API would be silently undone by the next pod restart,
which is the obvious trap in this design. Seeding is disabled by a property, and with it disabled a
missing directory is not an error. A malformed file names itself in the log and stops neither
startup nor the other CPAs. An example ConfigMap ships in `docs/deploy/`.

### F2. Party roles
ebMS 2 `From`/`To` carry a `Role`; ebms-core's API requires `fromRole` and exposes `toRole`; our
`Party` record has only `partyId` and `partyIdType`, and the JMS publisher sends empty roles.
**AC:** `Party` gains `role`; roles are parsed from inbound headers, written to outbound headers,
persisted, exposed on both APIs, and published on JMS events; CPA files carry the roles; a DB
migration adds the columns.

### F3. CPA validation
Now on the critical path rather than a nicety: under D1 the XML arrives from a client over the
network, so validation is the gate between an upload and the database.
**AC:** XML CPAs are validated against the CPPA 2.0 XSD on upload, on bootstrap load, and via `POST /cpas/validate`;
validation errors name the file and the failing element; YAML CPAs are validated against our own
schema with the same quality of error message; a CPA whose `transportUrl` is not HTTPS produces a
warning (or an error, configurable).

### F4. CPA-driven behaviour audit
Several CPA-level settings are read but not honoured, or not present at all: sync-reply mode (E3),
signing/encryption requirements (B2), per-CPA timeouts (C2), TTL (C4), compression (E5), roles (F2).
**AC:** one table in the README listing every CPA setting, where it is honoured in the code, and its
default; a test per setting proving it changes behaviour.

### F5. Database-backed CPA store
**As** the owner of `cpa-service`, **I want** to upload a CPA once and have every pod honour it
**so that** onboarding a partner does not require a deploy.

This is decision D1, and the storage is the easy half. `YamlCpaRepository` keeps CPAs in a per-pod
`ConcurrentHashMap` filled at `@PostConstruct`; two pods started at different times can disagree
about which partners exist, and nothing propagates a change between them. Fixing *that* is the
story.

**Store the uploaded document verbatim.** Our `Cpa` record is a flattened nine-field projection, and
ebms-core's `GET /cpas/{cpaId}` returns the CPA document itself — so the original bytes have to be
kept and the projection derived from them on read. Persisting only the projection makes that
endpoint unimplementable and throws away everything in the agreement we do not model yet.

**Acceptance criteria**
- Flyway `V3` adds a `cpa` table: unique `cpa_id`, the raw document and its content type, a version or updated-at column for optimistic locking, and upload metadata (when, and by whom once B3 lands).
- `CpaRepository` gains `save` and `deleteByCpaId`; a `JpaCpaRepositoryAdapter` implements it. `YamlCpaRepository` is reduced to the F1 bootstrap loader and no longer implements the port.
- **No per-pod cache in 0.1.0.** Lookups read through to the database, so pods and availability zones cannot disagree and there is no invalidation protocol to get wrong. This is one indexed primary-key read per message against a table with tens of rows; H4 measures it, and a short-TTL cache is added *only* if that measurement says it matters. Cross-pod cache invalidation is explicitly not what we reach for first.
- **CPPA 2.0 XML is the upload format** — confirmed with the engineer, 2026-09-08, and it is what ebms-core accepts too. YAML stays supported for the F1 bootstrap only, not for uploads.
- Two pods uploading the same `cpaId` concurrently resolve deterministically through the unique constraint: one wins, the other gets a clear conflict, and neither leaves a partial write.
- A test proves the actual requirement: two application contexts against one database, one uploads a CPA, the other resolves it on the next message with no restart and no reload call.

**What `CpaXmlParser` needs.** It is in better shape for this than expected: namespace-aware over
the CPPA 2.0 namespace, reads the certificate from an embedded `ds:X509Certificate` rather than a
file reference, and already hardened against XXE and entity expansion
(`disallow-doctype-decl`, external general and parameter entities off, XInclude off) — which matters
considerably more now that the document arrives over the network instead of from a mounted volume.
Two mechanical changes: it takes a `Path` and needs to take bytes, since an upload never touches the
filesystem, and `extractCertificate` uses that path only to name the file in a log message.

It is, however, a *partial* reader of the agreement. `PersistDuration` (C4, E8), roles (F2),
sync-reply mode (E3), signing and encryption requirements (B2) and compression (E5) all live in the
CPPA document and none are extracted today. That is the F4 audit, and with XML upload confirmed the
CPA document is unambiguously where each of those settings has to come from — not a parallel set of
properties.

**Note on certificates.** Our YAML format resolves `recipientCertPath` relative to the CPA file,
which cannot survive a move into the database. Uploads are unaffected because CPPA XML embeds the
certificate; the constraint applies only to the F1 bootstrap, where a referenced certificate file
has to be mounted alongside the CPA.

---

## 10. Epic G — Operations

### G1. Kubernetes-shaped health and lifecycle
**AC:** liveness and readiness probes are separate (`/actuator/health/liveness`, `/readiness`);
readiness is false until Flyway has migrated and CPAs are loaded; graceful shutdown is enabled with
a configurable grace period; in-flight inbound requests complete before shutdown; the retry
scheduler stops accepting new work on shutdown.

### G2. Metrics for everything that can go wrong
**AC:** existing counters keep working; add counters/gauges for signature failures, rejected
messages by error code, retry-queue depth, oldest pending message age, expired messages, JMS publish
failures, and payload bytes stored; the Grafana dashboard in `config/grafana/` is updated; an alert
rules file ships with sensible defaults (retry queue growing, oldest pending age, signature failure
rate).

### G3. Structured logging and traceability
**AC:** JSON logging profile for cluster deployment; `messageId`, `conversationId` and `cpaId` in
the MDC for every log line in a message's path; no payload content and no secrets in logs at any
level; a correlation id flows from the API through to the outbound send.

### G4. Database migration safety
**AC:** every schema change in this document ships as a Flyway migration (roles, message events,
expiry, error details, payload store); migrations are tested against a database with existing data;
`ddl-auto` stays `validate`; a rollback note per migration.

---

## 11. Epic H — Testing

### H1. Close the unit-test gaps
Zero coverage today on: `RetryService` (the core reliability feature), `EbmsCompatController` (the
entire drop-in surface), `AdminController` error paths, `JmsMessageEventPublisher`,
`YamlCpaRepository` malformed/reload paths.
**AC:** each has meaningful tests, including failure paths; the A2 coverage floor is met without
excluding these classes.

### H2. End-to-end scenarios
**AC:** add E2E coverage for a signed + encrypted round trip through the real HTTP endpoint;
multipart with real attachments; the full retry lifecycle to `FAILED` and to `EXPIRED`; a partner
returning `ErrorList` and a SOAP Fault; async ack mode (E3); ping (E1); status request (E2);
concurrent duplicate delivery (C6).

### H3. Contract tests against the ebms-core API
Once J4 lands, most of this is a specification diff rather than hand-written assertions.
**AC:** a test per compat endpoint asserting the exact wire format against the committed ebms-core
OpenAPI documents — status codes, content types (`text/plain` where ebms-core returns text/plain),
field names, and the array-of-strings shape of `/messages/unprocessed`.

### H4. Performance baseline
**As** an operator, **I want** to know where this breaks **so that** I can size it and set alerts.

The concerns in C7, B5 and C8 are all load-shaped and cannot be settled by reading code.

**AC** — a repeatable harness (Gatling, k6, or JMeter) in `perf/`, plus a documented baseline for:
- sustained inbound throughput at `/ebms/msh`, with concurrency (this is also the C6 race test)
- payload size curve: 1 MB → 10 MB → 100 MB, measuring latency and peak heap
- retry-scan cost with 100k and 1M rows in a pending state
- `GET /messages/unprocessed` at 50k unprocessed messages (this is the C7 regression test)
- behaviour against a slow and against a hung partner (C2)
- sustained soak: 1 hour, watching heap, connections and DB size

**Proposed initial targets, to be confirmed against real Logius volumes:** 50 inbound messages/second
sustained; p95 under 200 ms for a 100 KB payload; a 100 MB payload handled without OOM at a 1 GB
heap; retry scan under 1 s at 1M rows; no connection-pool exhaustion with a hung partner.

### H5. Interoperability test
**AC:** a documented test against a real ebMS 2 counterparty — ideally ebms-core itself, run from
the published container in `docker-compose` — exchanging signed, encrypted, acknowledged messages in
both directions. **This is the single most valuable test for the "drop-in" claim** and should be
part of the release checklist, not a nice-to-have.

---

## 12. Epic I — Deployment (Logius / Helm)

Responding directly to the five prerequisites raised by the engineer building the chart.

### I1. Publish the container image
Item 3. **AC:** the release workflow publishes `ghcr.io/<org>/ebms-integrate:0.1.0-SNAPSHOT` (and
`:0.1.0` on tag); the image is multi-arch if the cluster needs it; it runs as a non-root user; it
starts with a read-only root filesystem; the Dockerfile pins a digest for the base image; the image
is scanned in CI. *The release workflow currently builds only on `v*` tags — a snapshot publish job
on `master` is needed for the engineer to pull anything today.*

### I2. Ship a reference CPA ConfigMap
Item 1. **AC:** `docs/deploy/` contains a working example ConfigMap with a documented CPA YAML and a
documented CPA XML; every field is explained; the ConfigMap name (`ebms-integrate-cpas`) and mount
path are documented as the chart's contract; F1's fail-fast-on-empty behaviour is documented so a
missing ConfigMap is an obvious startup failure rather than silent zero-CPA operation.

### I3. Client adaptation — minimise it
Item 2. The engineer's assessment is correct **against the current shim**. Epic D exists to remove
most of that work.
**AC:** after Epic D, a written migration note per client service — `cpa-service`, `jms-producer`,
`jms-consumer` — stating exactly what changes: ideally hostname only; where a difference remains
(e.g. `POST /cpas` becoming read-only under decision D1), it is named explicitly with the reason and
the workaround. **This note is the deliverable the engineer is actually waiting for.**

### I4. Chart validation
Item 4. **AC:** `helm dependency build`, `helm lint` and `helm template` run clean; the rendered
manifests are reviewed against this document (probes from G1, secrets from B6, ConfigMap from I2,
`replicas: 2` once C3 lands); a CI job in the chart repository runs lint and template on every
change.

### I5. Staged rollout plan
Item 5. **AC:** a written runbook covering: deploy to a separate namespace alongside the existing
Core chart; verify Flyway migrations against an empty and a restored database; verify CPA loading
(count, and one message per CPA); verify inbound `/ebms/msh` with a real partner message; verify
outbound delivery and acknowledgment; verify JMS events reach `jms-consumer`; run one message
through every client service; only then remove the Core chart. Includes a rollback procedure and
the specific metrics/log lines to watch at each step.

### I6. Configuration reference
**AC:** every environment variable and property is documented in one table: name, meaning, default,
and whether it is required in production. The chart's `values.yaml` should be derivable from this
table alone.

---

## 13. Epic J — Documentation

### J1. README
**AC:** replace the current two lines with: what this is, what ebMS 2 is in three sentences, quick
start via docker-compose, configuration reference (I6), the API surfaces (ours and the compat one),
the ebms-core migration guide (I3), the status-mapping table (D3), and the CPA settings table (F4).

### J2. Keep `docs/design.md` honest
**AC:** the original design doc is updated where 0.1.0 diverges (sync vs async ack, Spring Boot
version, the added JMS/compat/security surfaces), or clearly marked as the historical MVP design
with this document as the current one.

### J3. Architecture decision records
**AC:** short ADRs for the decisions in section 3, plus: why hexagonal, why the compat layer is an
adapter rather than the core, and why file-based CPAs. Cheap to write now, valuable when someone
adds AS4.

### J4. Publish OpenAPI documents
**As** a client developer, **I want** a machine-readable contract **so that** I can generate a client
and see exactly what changed between releases.

**Yes — and for a reason beyond documentation.** We have committed ebms-core's own OpenAPI documents
in `docs/compat/`. If we generate ours, story H3 stops being hand-written assertions and becomes a
**diff between two specification files in CI**: the cheapest available proof of the drop-in claim,
and one that fails loudly the day someone renames a field. Publishing the internal API's document is
then close to free.

**AC**
- `springdoc-openapi` is added; `/v3/api-docs` is served and Swagger UI is available but disabled by default outside development. Both sit behind B3 authentication.
- Two grouped documents: `internal` (`/api/**`) and `compat` (the ebms-core-shaped API).
- The generated `compat` document is written to `docs/api/` by the build and committed, so any contract change appears as a reviewable diff in the pull request that causes it.
- A test compares the generated `compat` document against `docs/compat/ebms-core-*.json` for the endpoints we claim to implement — paths, methods, status codes, media types, field names — with an explicitly enumerated and justified list of allowed deviations. That list starts with the `DataSource` schema described in `docs/compat/README.md`, where ebms-core's *own* generated document does not match the JSON it actually puts on the wire.
- Reference documentation is generated from the code, not hand-maintained alongside it.

---

## 14. Ready for ebMS3/AS4

**AS2 is out of scope** (decided 2026-09-08): AS4 supersedes it for the profiles we care about, so
the second protocol to land here is ebMS3/AS4 and there is no third. That simplifies K2 — the
agreement abstraction has to span CPA and PMode, and nothing else.

The port structure is already right: `InboundMessageParser`, `OutboundMessageSerializer` and
`MessageTransport` are the correct seams, and a second protocol slots in as another
parser/serializer pair without touching the services or the domain. Two things will fight a second
protocol, and both are cheaper to fix now at ~2,800 lines than later.

### K1. Make the domain protocol-neutral
`EbmsMessage` hard-codes ebMS 2 concepts (`ackRequested`, `refToMessageId`, and the magic string
`"Acknowledgment"` compared in `ReceiveMessageService.java:41`). `Cpa` is an ebMS-2-shaped record.
**AC:** protocol-specific fields move behind a per-protocol extension or a sealed hierarchy; no
service compares protocol string constants; a `Protocol` discriminator exists on the message and the
agreement; adding a protocol requires no change to `ReceiveMessageService` or `SendMessageService`.
**Not a 0.1.0 blocker** — but do it before the first AS4 story, not after.

### K2. Name the agreement abstraction
`Cpa` is ebMS-2 vocabulary; AS4 uses PModes.
**AC:** the port is named for the concept (`AgreementRepository` or similar) with `Cpa` as the ebMS 2
implementation. *(0.2.0.)*

---

## 15. Suggested sequencing

**Milestone 1 — Foundation (unblocks everything else)**
A1 Spring Boot 4 · A2 JaCoCo · A3 Sonar script · A4 release hygiene · I1 publish snapshot image

*I1 early: the engineer is blocked on an image today and can start chart work while the rest lands.*

**Milestone 2 — Safe to expose**
B1 signature trust · B2 CPA security policy · B3 API auth · B5 input limits · B6 secrets
C1 transaction boundary · C2 timeouts · C3 retry locking · C5 error responses · C6 duplicate race

**Milestone 3 — Smooth transition**
D1–D7 compat API · F5 CPA store · F1 CPA bootstrap · F2 roles · E1 ping · E3 sync/async
I2 reference ConfigMap · I3 migration note · C4 backoff and TTL · E8 `TimeToLive`

*Order within the milestone: B3 before F5, and F5 before D5. The CPA write endpoints must not exist
before the authentication that guards them.*

**Milestone 4 — Prove it**
J4 OpenAPI documents (do this before H3) · H1–H3 tests · H4 performance baseline · H5 interop against ebms-core · C7 list queries
G1 probes · G2 metrics

**Milestone 5 — Release**
J1–J3 docs · I4 chart validation · I5 staged rollout · tag `v0.1.0`

Items deliberately deferred past 0.1.0 unless Logius says otherwise: C8 (payload store), E2 (status
service), E5 (compression — **confirm first**), E6 (message ordering), K1/K2 (protocol neutrality),
B4 (inbound mTLS, if the ingress terminates TLS).

---

## Appendix A — ebms-core compatibility matrix

`{base}` = the configurable compat base path (decision D2).

| ebms-core endpoint | Method | Story | 0.1.0 |
|---|---|---|---|
| `{base}/messages` | POST | D1 | Planned |
| `{base}/messages/unprocessed` | GET | D2 | Planned |
| `{base}/messages/{messageId}` | GET | D2 | Planned |
| `{base}/messages/{messageId}` | PATCH | D2 | Planned |
| `{base}/messages/{messageId}` | PUT (resend) | D2 | Planned |
| `{base}/messages/{messageId}/status` | GET | D2 | Planned |
| `{base}/events/unprocessed` | GET | D6 | Planned |
| `{base}/events/{messageId}` | PATCH | D6 | Planned |
| `{base}/ping/{cpaId}/from/{fromPartyId}/to/{toPartyId}` | POST | D4 | Planned |
| `{base}/messages/mtom` | POST | D8 | Out of scope |
| `{base}/messages/mtom/{messageId}` | GET | D8 | Out of scope |
| `/cpas` | GET | D5 | Planned |
| `/cpas/{cpaId}` | GET | D5 | Planned |
| `/cpas/validate` | POST | D5 | Planned |
| `/cpas/cache` | DELETE | D5 | Planned (no-op; no per-pod cache) |
| `/cpas` | POST | D5 | Planned |
| `/cpas/{cpaId}` | DELETE | D5 | Planned |
| `/urlMappings/*` | — | D8 | Out of scope |
| `/certificateMappings/*` | — | D8 | Out of scope |
| SOAP/WSDL service | — | D8 | Out of scope |

**JMS events** — destinations `RECEIVED`, `DELIVERED`, `FAILED`, `EXPIRED` (queues) or
`VirtualTopic.<TYPE>` (topics); ten string properties as listed in D6. Our publisher already matches
the property set and queue naming; topic support, the `TextMessage` variant, real roles, the event
filter, and the `EXPIRED` event are the gaps.

---

## Appendix B — Findings this document is built on

Concrete defects verified in the current code, each mapped to the story that fixes it.

| Location | Finding | Story |
|---|---|---|
| `XmlSignatureService.java:107-116` | Trusts any certificate embedded in the message's `KeyInfo` — signing proves integrity but not origin | B1 |
| `XmlSignatureService.java:100` | Missing signature silently accepted; no per-CPA requirement | B2 |
| `pom.xml` | No `spring-boot-starter-security`; `/api/**`, `/ebms-core/**`, `/actuator/prometheus` all unauthenticated | B3 |
| `SendMessageService.java:60-79` | Outbound HTTP call inside the `@Transactional` boundary; a throw after a successful POST loses the record | C1 |
| `HttpMessageTransport.java` | No connect or read timeout on either RestClient path | C2 |
| `RetryService.java:41` | Pending select with no lock; two replicas double-deliver | C3 |
| `SendMessageService.java:86` | HTTP 200 carrying an `eb:ErrorList` is recorded as `SENT` | C5 |
| `ReceiveMessageService.java:50` | Check-then-insert duplicate race → unique violation → HTTP 500 to the partner | C6 |
| `JpaMessageRepositoryAdapter.java:111` | `toDomain` always maps payloads; list endpoints N+1 and read every payload's bytes | C7 |
| `MshController.java:36` | Whole request body buffered into a `byte[]` with no size cap | B5 |
| `EbmsCompatController.java` | Invented API shapes that do not match ebms-core's contract | D1, D2 |
| `JmsMessageEventPublisher.java:29-31` | `fromRole`/`toRole` published as empty strings | F2, D6 |
| `JmsMessageEventPublisher.java:40` | Publish failures swallowed with a warning — events can be lost silently | D6 |
| `MessageEventPublisher.java` | `EXPIRED` is declared but never emitted | C4 |
| `MessageStatus.java` | `DELIVERED` is declared but never set anywhere in the codebase | D3 |
| `SoapMimeSerializer` / `SoapMimeParser` | `eb:TimeToLive` is neither emitted nor honoured | E8 |
| `pom.xml` | No `springdoc-openapi`; no generated API contract to diff against ebms-core's | J4 |
| `YamlCpaRepository.java:49` | Missing CPA directory logs a warning and continues with zero CPAs | F1 |
| `YamlCpaRepository.java` | CPAs cached per pod in a `ConcurrentHashMap` filled at `@PostConstruct`; pods disagree and nothing propagates a change | F5 |
| `Cpa.java` | Flattened projection only; the uploaded document is not retained, so `GET /cpas/{cpaId}` cannot return it | F5 |
| `SoapMimeParser.java` | No `eb:Manifest` validation on receive | E4 |
| `pom.xml:23-25` | `java:S3776` suppressed project-wide across `**` | A3 |
| `pom.xml` | Spring Boot 3.4.5 while `CLAUDE.md` claims Spring Boot 4 | A1 |
