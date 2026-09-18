---
name: subscription-hub-state
description: What is built in Subscription Hub, why each decision was made, and what is deliberately not done. Read before proposing any new module, refactor, schema change, or "improvement" — many apparent gaps are deliberate and already argued.
---

# Current state

**Done:**
- Docker Compose + PostgreSQL, Spring Boot baseline
- Tenant resolution, request context, MDC
- Problem+JSON error handling (`common/api`), shared MDC keys (`common/logging/MdcKeys`)
- Flyway V1 baseline schema; V2 adds `PAUSED`; V3 splits `plan.interval` into
  `interval_unit` + `interval_count` so a plan can bill every N units
  (quarterly, semi-annual, biennial), not just a fixed period of 1
- OpenAPI/Swagger — `/swagger-ui/index.html`, `/v3/api-docs`, reusable Problem
  schema attached to error responses
- **Structural tenant isolation** — Hibernate `@TenantId` on
  `TenantScoped.tenantId`, resolved via `tenancy/infra/jpa/TenantIdentifierResolver`
  (wraps `TenantContext`), wired through `JpaConfig`'s
  `HibernatePropertiesCustomizer`. Spring Boot does **not** auto-detect a
  `CurrentTenantIdentifierResolver` bean — `hibernate.tenant_identifier_resolver`
  has to be set explicitly, or repository bootstrap itself fails at startup.
  Adds an automatic `tenant_id =` predicate to every Hibernate-mediated query,
  on top of (not instead of) the explicit `findByTenantId...` convention.
- **Catalog** — Product, Plan, PlanEntitlement: full trio, DTOs, mappers,
  validation, services, controllers. Tested via `.http`.
- **Customer** — same, `POST`/`GET /api/customers`. Tested via `.http`.
- **Subscription** — entity, trio, DTOs, mapper, service, controller. Creation
  logic: if `plan.trialDays > 0` → `TRIALING`, period ends `now + trialDays`;
  else `ACTIVE`, period ends `now + 1 billing period` (`plan.intervalUnit` ×
  `plan.intervalCount`, via `BillingPeriods.addInterval`). `nextRenewal =
  currentPeriodEnd`. Cancel/pause/resume with real transition guards
  (`InvalidSubscriptionStateException` → 409 `INVALID_SUBSCRIPTION_STATE`;
  can't cancel a canceled subscription, can't resume one that isn't paused).
  Verified end-to-end via `subscription.http` and integration tests (below).
- **Renewal processing** — `SubscriptionRenewalService.renewIfDue`, called from
  `BillingCycleJob` (see below), rolls `TRIALING`/`ACTIVE` subscriptions
  forward one billing period once `nextRenewal` is due. Anchored to the old
  `currentPeriodEnd`, not `now`, so job lag never drifts the schedule going
  forward; self-healing (a subscription months overdue still only advances
  once per run, staying due for the next). Runs per-tenant under
  `TenantContext.runAs`, since a scheduled job has no request to populate the
  ThreadLocal from. `SubscriptionRenewalService` itself is unchanged since it
  was first built — only its caller changed, from `RenewalJob` to
  `BillingCycleJob`.
- Mapper/unit tests committed for Product, Plan, PlanEntitlement, Customer,
  Subscription, `BillingPeriods`, `SubscriptionRenewalService`, with shared
  validator setup extracted.
- **Integration tests** — Testcontainers-backed (`testsupport/AbstractIntegrationTest`:
  real Postgres via `@ServiceConnection` started once as a JVM-wide singleton —
  see the Testcontainers rules in CLAUDE.md §5 — `TestRestTemplate` on a random port
  so requests pass through the full servlet filter chain, not just the
  controller layer). `TenantIsolationIntegrationTest` proves `@TenantId`
  actually blocks a crafted cross-tenant lookup against a real database (a
  cross-tenant cancel 404s instead of leaking or mutating the row; the same
  code under two tenants doesn't collide). `SubscriptionLifecycleIntegrationTest`
  covers creation, state-transition guards, and renewal end-to-end against
  the real DB — the load/save path the pure-function unit tests skip.
- CI — GitHub Actions workflow running `mvn verify` on every push/PR to
  `main`; tests self-provision Postgres via Testcontainers, no fixed service
  container needed.
- README covering architecture, multi-tenancy model, and local setup.
- **API hardening pass** — closed the three gaps Known gaps and improvements flagged as mattering more
  than the next feature module:
  - `ProblemDetailsAdvice` now maps a unique-constraint
    `DataIntegrityViolationException` to the same 409 the check-then-save
    path returns, instead of falling through to a 500. Matches on constraint
    name via `org.hibernate.exception.ConstraintViolationException`; V4
    renames Postgres's auto-generated constraint names
    (`product_tenant_id_code_key`, ...) to the `uk_*` names the entities'
    `@UniqueConstraint(name = ...)` already declared but that
    `ddl-auto: validate` never enforced, so the two were silently out of
    sync. An unrecognized constraint (FK, not-null) still falls through to
    the generic 500 — this only catches the uniqueness case. Both the
    generic and this handler now log the exception; previously
    `handleGeneric` swallowed it entirely.
  - `common/api/PagedResponse<T>` replaces raw `Page<T>` on every list
    endpoint (`products`, `plans`, `customers`, `subscriptions`) — `PageImpl`
    serialization isn't a documented contract.
  - `GET /{code}` (Product, Plan) / `GET /{id}` (Customer, Subscription)
    single-resource reads, and 201 responses now carry a real `Location`
    header via `UriComponentsBuilder`.
- **Usage metering** — `usage/` module, full trio, nested under
  `/api/subscriptions/{subscriptionId}/usage` the same way `PlanEntitlement`
  nests under `/api/plans/{planCode}/entitlements`. `period_start`/
  `period_end` are read off the subscription (`currentPeriodStart`/
  `currentPeriodEnd`), never accepted from the request — a client can't
  backdate usage into a period renewal has already rolled past, and that's
  what makes the `(tenant_id, subscription_id, meter_key, period_start)`
  unique key meaningful. Recording usage against a `CANCELED` subscription
  is a 409 `INVALID_SUBSCRIPTION_STATE` (reusing the same exception
  `cancel`/`pause`/`resume` use); `PAUSED`/`PAST_DUE`/`TRIALING` can still
  accrue usage.
  - Recording usage is a running-total increment, not a create — a plain
    check-then-save read-modify-write would silently lose increments under
    concurrent requests for the same meter+period, so
    `UsageCounterJpaRepository.upsertAndIncrement` is a single atomic
    `INSERT ... ON CONFLICT ON CONSTRAINT uk_usage_counter_tenant_sub_meter_period
    DO UPDATE SET amount = usage_counter.amount + EXCLUDED.amount ... RETURNING *`.
    This is the project's **first native/`nativeQuery = true` query** — see
    the tenancy note in CLAUDE.md §4: `@TenantId`'s automatic predicate doesn't apply
    here, so `tenantId` is bound explicitly and is part of the upsert's
    identity. `UsageMeteringIntegrationTest` includes a 20-thread
    concurrent-increment test that converges to the exact expected total
    against a real Postgres, which is the actual justification for the
    native query over a simpler JPA read-modify-write.
  - V5 adds `usage_counter.created_at` (missing since V1 — `TenantScoped`
    requires it) and renames the table's unique constraint, following the
    same V4 pattern, to a name short enough and stable enough to be the
    `ON CONFLICT ON CONSTRAINT` target (Postgres had silently truncated the
    auto-generated name to its 63-character limit).
  - `BigDecimal` (`usage_counter.amount numeric(20,6)`, for fractional
    usage) is the first use of that type in the codebase — deliberately
    the one place `amountCents`-style integer-cents money doesn't apply,
    since this isn't money yet, just a metered quantity.
  - No rollover logic needed: because `SubscriptionRenewalService` already
    sets `currentPeriodStart = oldPeriodEnd` on renewal, a new billing
    period naturally gets a fresh counter row (`period_start` is part of
    the unique key) — the renewal path itself needed no changes for billing.
- **Billing** — `billing/` module, activates the `invoice`/`invoice_line`
  tables that had existed unused since V1. `POST
  /api/subscriptions/{subscriptionId}/invoices` generates an invoice for the
  subscription's *current* period — read as `[currentPeriodStart,
  currentPeriodEnd)` straight off the subscription row, the same two fields
  `usage_counter` is keyed by, so usage lines join to counters exactly rather
  than by reconstructing a closed period's boundaries after renewal has
  already overwritten them. A period must be closed (`now >=
  currentPeriodEnd`) before it can be invoiced — 409 `INVOICE_PERIOD_NOT_CLOSED`
  otherwise — since both the base charge and usage charges land on one
  invoice for that period.
  - One `BASE` line for the plan's recurring `amountCents`, plus one `USAGE`
    line per metered counter that has a matching priced entitlement and
    usage over its included quantity. Usage prices live in the existing
    `plan_entitlement` jsonb (`{"includedQuantity": N, "unitAmountCents": N}`)
    rather than a new table — an entitlement without `unitAmountCents` is
    simply not a priced meter and is skipped, not an error, since
    `plan_entitlement` is a general-purpose bag most entries don't use for
    pricing. This is the table's first real use beyond being stored and
    echoed back.
  - `InvoiceCalculator` (pure, package-private, mirrors
    `SubscriptionRenewalService.renewalFor`'s split from load/save) rounds
    the `BigDecimal` quantity × `long unitAmountCents` product `HALF_UP` to
    whole cents, exactly once per line — `total_cents` is the sum of
    already-rounded line amounts, never a separately rounded total, so the
    two always agree.
  - Invoice numbers (`INV-000001`, per tenant) come from
    `InvoiceJpaRepository.allocateNextNumber`, the project's **second**
    native query, for the same reason as the first: a read-modify-write on
    `invoice_number_sequence` would race two concurrent invoice generations
    for one tenant into the same number. `tenantId` bound explicitly, same
    as `upsertAndIncrement`.
  - Idempotency is enforced twice: `generateForCurrentPeriod` checks for an
    existing invoice for that period before generating, and
    `uk_invoice_tenant_sub_period` (V6) catches the concurrent race the
    check can't — both map to 409 `INVOICE_ALREADY_EXISTS` via
    `ProblemDetailsAdvice`.
  - Only `InvoiceStatus.OPEN` is produced today. Issuing straight to `OPEN`
    (never `DRAFT`) makes an invoice immutable once generated, which is what
    makes the period-based unique key a sufficient idempotency guard —
    `PAID`/`VOID`/`UNCOLLECTIBLE` wait for payments and dunning.
  - `InvoiceEntity.lines` is the codebase's first `@OneToMany`
    (`cascade = ALL, orphanRemoval = true`) — justified because an invoice
    and its lines are one aggregate, written together, with no independent
    lifecycle for a line. `@BatchSize(32)` avoids the N+1 a paged list of
    invoices would otherwise cause.
  - V6 migration fixes five pre-existing defects in the V1 `invoice`/
    `invoice_line` tables (missing `created_at`/`updated_at` on
    `invoice_line`, `currency char(3)` vs. Hibernate's expected `varchar`,
    `ON DELETE SET NULL` on a `NOT NULL` FK, an auto-named unique
    constraint, a missing index) plus adds `period_start`/`period_end` and
    `invoice_number_sequence`.
  - **`BillingCycleJob`** replaces `RenewalJob` (cron property renamed
    `subscription.renewal.cron` → `billing.cycle.cron`). Invoicing and
    renewal are two steps of one process, not two independent jobs: once
    a renewal advances a subscription, the
    closed period's start is gone from the row, so running the two on
    separate crons races and the losing side is unbilled revenue.
    `BillingCycleJob` invoices then renews, per subscription, reusing the
    existing `findByTenantIdAndStatusInAndNextRenewalLessThanEqual` finder
    unchanged (a subscription it selects always has a closed current
    period, since `nextRenewal == currentPeriodEnd` at every write site). A
    `ResourceAlreadyExistsException` from invoicing means the period was
    already billed on a prior partial run, so renewal proceeds; any other
    invoicing failure leaves the subscription due rather than renewing past
    an unbilled period.
- **Invoice PDFs** — `billing/infra/pdf` + `billing/infra/storage`, activating
  the `pdf_url` column that had been unused since V1 (V7 renames it to
  `pdf_object_key`, since it holds an object key like `acme/INV-000001.pdf`,
  never a URL).
  - Rendering is HTML → PDF: `InvoicePdfRenderer` builds an `InvoicePdfView`,
    runs it through a Thymeleaf template (`resources/templates/invoice.html`),
    and pipes the HTML through openhtmltopdf. Drawing to a PDF canvas with
    PDFBox directly would mean hand-computing row offsets, column widths and
    string widths to right-align money, plus page breaks — all free from the
    HTML/CSS engine, and the layout ends up in a template a non-Java reader
    can edit. The renderer owns its `TemplateEngine` privately rather than
    exposing a bean, so nothing else can accidentally couple to Thymeleaf.
  - **Money leaves integer minor units in exactly one place**: the view model,
    via `BigDecimal.valueOf(cents, 2)` — which repositions the decimal point on
    an exact integer, no division and no floating point. The template does no
    arithmetic at all, so the CLAUDE.md §5 money rule holds right up to the moment the
    number becomes text.
  - `POST /api/invoices/{id}/pdf` generates (409 `INVOICE_PDF_ALREADY_GENERATED`
    on a second call — an invoice is immutable once issued, so its PDF is too);
    `GET /api/invoices/{id}/pdf` streams it back as `application/pdf` with a
    `Content-Disposition` filename of the invoice number. 404
    `INVOICE_PDF_NOT_GENERATED` if it hasn't been generated yet.
  - **Bytes stream back through the API, never via a presigned object-store
    URL.** That keeps every download inside the tenant-scoped request path
    (`TenantResolverFilter` plus the `findByTenantIdAndId` ownership check),
    and means the bucket is unreachable from outside the application. The
    controller returns an `InputStreamResource` so bytes go from the store to
    the socket without being buffered whole.
  - Both PDF errors are distinct `BusinessRuleViolationException` subclasses
    rather than the generic resource shapes. CLAUDE.md §5 says not to invent per-entity
    classes for exists/not-found — but the PDF has no identifier of its own, so
    these describe a *state of the invoice* ("exists but not generated yet" is
    genuinely different from "no such invoice", and callers act differently on
    each), and the generic types would emit mangled codes like
    `INVOICEPDF_NOT_FOUND`.
  - The bucket is created **lazily on first write**, not `@PostConstruct`: a
    startup check would stop the whole application booting whenever the object
    store is down, coupling every endpoint to a dependency only this feature
    needs. It also means neither local dev nor Testcontainers needs a bucket
    provisioning step.
  - Object keys are tenant-prefixed (`{tenantId}/{number}.pdf`) because invoice
    numbers restart per tenant. That is a storage-layout convenience, **not** a
    security boundary — isolation comes from loading the invoice through
    `findByTenantIdAndId` before its key is ever read.
  - Generation is deliberately *not* folded into
    `InvoiceService.generateForCurrentPeriod`: that would put a remote call
    inside the billing transaction and let a storage outage fail a revenue
    path. `InvoicePdfService.generatePdf` **used to** hold a transaction across
    the upload itself, excused on the grounds that storing the bytes *is* the
    operation and a failure would roll back a transaction that had written
    nothing. That excuse was about rollback only, and it hid a lost update —
    see "Invoice PDF lost update" below. It is now the same three-step split as
    `PaymentService`: load and render, upload with no transaction open, record
    the key with a conditional UPDATE.
  - `InvoiceResponse.pdfAvailable` is a boolean, not the object key: the key is
    internal storage layout and is tenant-prefixed, so exposing it would leak
    both where bytes live and the tenant id that never appears in a body.

- **Authentication & RBAC** — `auth/` module. Callers obtain a token from
  `POST /api/auth/token` (tenantId + email + password) and send it as
  `Authorization: Bearer`. **The tenant comes from the token's signed `tenant_id`
  claim; the `X-Tenant-Id` header is gone.** Before this, tenant isolation rested
  on a string the caller typed — `@TenantId` and the isolation tests protected
  tenant A from tenant B's *bugs*, but nothing stopped B from simply claiming to
  be A.
  - HS256, secret from `JWT_SECRET`, ≥32 bytes enforced at startup rather than at
    first login. `JwtConfig` also validates the issuer, so a token signed with the
    same secret by another service can't be replayed.
  - Every login failure — unknown tenant, unknown email, disabled account, wrong
    password — returns one code, and the password hash is computed even when no
    user was found, so neither the message nor the response time reveals which
    tenants and emails exist.
  - **Roles** live in the `roles` claim and are mapped to `ROLE_`-prefixed
    authorities by a custom `JwtAuthenticationConverter` — Spring's default reads
    the OAuth2 `scope`/`scp` claims, so without it every token arrives with no
    authorities and every rule denies uniformly, which looks identical to the
    rules simply being strict.
  - **Reads are open to any authenticated role; writes carry an explicit
    `@PreAuthorize`** (see `auth/api/Authorize`). Catalog writes are `ADMIN` only —
    deliberately narrower than commercial operations, since someone who can run
    billing shouldn't be able to change what the prices are. Customers,
    subscriptions, usage and invoices accept `ADMIN` or `BILLING`. The convention:
    an unannotated write is a bug, an unannotated read is deliberate.
  - `AppUserEntity` deliberately does not extend `TenantScoped` — see CLAUDE.md §4 for the
    chicken-and-egg that forces it.
  - Actuator: only `health` and `info` are exposed, and only `/actuator/health` is
    public. `httptrace` was removed from the exposure list — a Boot 2 endpoint id
    needing a bean nothing defines, so already dead, but it records request and
    response *headers*, which now means bearer tokens.
  - Seeded dev logins (`admin@acme.test`, `admin@demo.test`, `support@acme.test`,
    password `subscriptionhub`) live in `db/seed` under the `dev` profile, not in
    `db/migration` — see CLAUDE.md §5.
- **Payments** — `payment/` module, and the first thing to move an invoice off
  `OPEN`: `POST /api/invoices/{id}/payments` settles it `PAID`.
  - **Shaped after Stripe's PaymentIntent + webhook model from the start**, before
    any Stripe code existed. `PaymentGateway.createPayment` returns only the
    provider's reference, never the outcome; the outcome arrives as a
    `PaymentEvent` and `PaymentSettlementService` is the only code that acts on
    one. A synchronous "charge and tell me the result" port would have been
    simpler and would have had to be rebuilt the day a real provider landed.
  - **Three steps around one remote call** (`PaymentService`): reserve a `PENDING`
    payment in a short transaction (amount and currency copied from the invoice,
    never from the request), call the provider with no transaction open, record the
    reference in a second. Deliberately not `@Transactional` — the same rule that
    keeps PDF rendering out of the billing transaction, plus the fact that a
    rollback cannot un-charge a card.
  - **Double-charge prevention is structural**: `ux_payment_invoice_in_flight_or_succeeded`
    (partial unique index, V11) allows one `PENDING`-or-`SUCCEEDED` payment per
    invoice. The code checks the invoice is `OPEN` first, but two concurrent
    requests both pass that check; the index is what makes the loser fail before
    reaching the provider. A 10-thread test asserts exactly one charge, mirroring
    the usage-counter and invoice concurrency proofs.
  - **`Idempotency-Key` is required, not generated.** It replays the first result
    (200 rather than 201), refuses reuse for a different invoice or method
    (409 `IDEMPOTENCY_KEY_REUSED`), and — the reason it is mandatory — is the only
    way to *resume* a payment the provider never acknowledged: a provider outage
    leaves the payment `PENDING`, which blocks the invoice's in-flight slot until
    the same key resubmits it. Our payment id doubles as the provider's idempotency
    key, so the resubmission cannot charge twice.
  - **Settlement is idempotent by state, not by a table of processed event ids.**
    Only a `PENDING` payment settles, and it settles once, so duplicates, late
    deliveries and out-of-order events (all three of which providers explicitly do
    not rule out) change nothing. A `processed_event` table was planned and dropped:
    it would have added a third native query for behaviour the state machine already
    guarantees. It becomes necessary only for events that aren't naturally
    idempotent — refunds, disputes — which don't exist here.
  - **Two adapters, one settlement path.** `FakePaymentGateway` (default,
    `payment.provider=fake`) is deterministic from the payment method and uses
    *Stripe's own test ids* (`pm_card_visa`, `pm_card_visa_chargeDeclined`,
    `pm_card_visa_chargeDeclinedInsufficientFunds`, plus a fake-only
    `pm_fake_provider_unavailable`), so the same `.http` requests work against
    either provider. It delivers its event before returning, which means every
    fake payment also exercises the awkward real-world ordering: an event arriving
    before the create call's reference has been stored. That works because events
    match on *our* payment id from metadata, not the provider's reference.
  - **`StripePaymentGateway`** (`payment.provider=stripe`) creates and confirms a
    PaymentIntent in one call with `off_session` and `error_on_requires_action`
    (no UI exists to answer a 3-D Secure challenge, so failing fast beats parking
    a payment that would hold the invoice's slot forever) and
    `allow_redirects=never` (otherwise Stripe demands a `return_url`). A
    `CardException` is **not** treated as a gateway failure: the PaymentIntent
    exists and its webhook will settle it FAILED, so the adapter returns the
    intent's id. Only an unreachable provider raises `PaymentGatewayException`.
  - **`POST /api/webhooks/stripe`** is public and unauthenticated — the caller holds
    no token of ours — so the signature *is* the authentication, and
    `permitAll` in `SecurityConfig` is matched by an entry in
    `TenantResolverFilter.shouldNotFilter`. The body is taken as a raw `String`:
    the signature covers exact bytes, so letting Jackson parse and re-serialize
    would break verification. Unknown event types and PaymentIntents without our
    metadata get 200 and are ignored, because a provider retries every non-2xx for
    days; only an unverifiable signature is a 400.
  - **The webhook's tenancy problem**, and the one genuine surprise in this work:
    with `open-in-view`, the request's Hibernate session opened before the tenant
    was knowable (it is inside the signed body), pinning every query to the
    `__no_tenant__` sentinel. `PROPAGATION_REQUIRES_NEW` did not fix it: with no
    active transaction there was nothing to suspend, so the new transaction adopted
    the bound `EntityManager`. `PaymentWebhookService` unbound it, set the tenant,
    settled in a fresh session, and rebound it. Caught by a test, not by reading.
    Open-in-view is off now (see the open-in-view entry in Known gaps), so only
    "set the tenant, then open the transaction" remains.
  - **No Stripe account exists**, and none is needed: Stripe supports neither
    Kosovo nor Albania, and the signup country is permanent and must match a real
    entity there. `StripePaymentGatewayTest` runs the real SDK against
    `stripe-mock` in a container (a plain JUnit test, no Spring context, so it
    neither forks the context cache nor needs the Stripe profile), and
    `StripeWebhookIntegrationTest` signs its own payloads with a test
    `whsec_` secret — verification is HMAC, so the production path runs unchanged.
    This is why the Stripe adapter has CI coverage that a real-account integration
    could never have.
- **Dunning** — `dunning/` module (`app`, `domain`, `infra/jpa`; no `api` — it is a
  job, not endpoints) plus `customer.default_payment_method` (V12) and
  `dunning_state` (V13). Closes the three things payments deliberately left:
  nothing charged automatically, `PAST_DUE` was never set, `UNCOLLECTIBLE` was
  unreachable.
  - **Its shape is dictated by asynchronous settlement.** "Charge, then decide
    whether to renew" is impossible when the outcome arrives by webhook after
    the run ends, so `BillingCycleJob` was left *completely untouched* and
    `DunningJob` reacts to state instead: any invoice still `OPEN` whose
    `next_attempt_at` has passed. The job starts attempts and never reads a
    result; `PaymentSettlementService` notifies `PaymentOutcomeListener`, and
    `DunningService` decides what an outcome means. Three responsibilities, three
    classes, because the middle one is the only place an outcome is known.
  - **It is its own module out of necessity, not taste.** `billing` cannot depend
    on `payment` (payment already depends on billing; ArchUnit's no-cycle rule
    catches it), and putting subscription-lifecycle rules in `payment` would give
    that module opinions about a concept it has no business knowing. The listener
    port lives in `payment/domain` and is implemented in `dunning/app`, so the
    dependency arrow still points dunning → payment.
  - **`dunning_state` is a separate table**, not columns on `invoice`: an invoice
    is immutable once issued, and a retry schedule is operational state, not part
    of a financial document. It is deleted when the invoice settles; the payments
    remain as the record of what was tried.
  - **The attempt is counted and committed before the provider is called.** The
    reverse order looks tidier and is a trap: a crash mid-attempt would leave the
    invoice due again immediately, which on an hourly cron means charging the
    customer every hour. The cost of this ordering is one wasted retry after a
    crash, which is the cheaper mistake. The key is
    `dunning:{invoiceId}:{attempt}`, so a repeated run resumes the same attempt
    at the provider rather than charging twice.
  - **Transitions**: first failure → `PAST_DUE` (but never from `PAUSED` or
    `CANCELED` — pausing is a deliberate customer choice). A successful retry
    deletes the schedule and returns the subscription to `ACTIVE`; the next
    `BillingCycleJob` run then renews it through the existing
    already-invoiced path, so nothing new was needed for recovery. After
    `dunning.max-attempts` the invoice is `UNCOLLECTIBLE` and the subscription
    `CANCELED` — which is what finally makes both of those enum values reachable.
  - **Skips are deliberate and logged, not silent**: an invoice with a `PENDING`
    payment (an awaited webhook is not a failure, and the in-flight index would
    reject the attempt anyway), and a customer with no stored payment method
    (left `OPEN` for a human rather than invented behaviour — see the gaps below).
  - `DunningSchedule` is pure and unit-tested as a table of attempt → delay, the
    same split `renewalFor` and `InvoiceCalculator` use. Defaults: retries after
    1d, 3d, 5d, four attempts total.
  - Verified live as well as in tests: with the cron shortened, a declining card
    produced `PAST_DUE` on the first run, no attempt on the second (the schedule
    genuinely holds), attempt 2 after forcing it due, then
    `UNCOLLECTIBLE`/`CANCELED` at the limit, and `PAID`/`ACTIVE` when the card was
    fixed mid-cycle.
- **Notifications** — `notification/` module (`app`, `domain`,
  `infra/{jpa, sqs, mail, template}`; no `api`, same as dunning — this is an
  outbox and a queue consumer, not endpoints). Closes dunning's biggest
  documented gap: *"Nobody is told anything."*
  - **A transactional outbox, not a direct send.** Publishing to SQS inside
    the business transaction and rolling back would email a customer about
    something that never happened; publishing after commit and crashing
    before the message goes out would lose it silently. You cannot commit a
    database row and publish to a queue atomically (the dual-write
    problem), so `NotificationService` renders the email and writes a
    `notification` row (V14) in the *same* transaction as the invoice or
    dunning state change, and a separate relay moves it onward. Rendering
    itself runs inside that transaction without breaking the "no remote
    call in a transaction" rule, because Thymeleaf rendering is local CPU
    work, not a remote call.
  - **The transport is the outbox relayed to SQS, not a direct send or a
    DB-polling consumer** — chosen deliberately over both simpler
    alternatives, to mirror how a production system decouples "something
    happened" from "deliver it": `NotificationRelayJob` moves `PENDING` rows
    to SQS (`NotificationRelayService.claimPending`/`markPublished`, split
    across two short transactions with the publish call between them and no
    transaction open — the same "reserve, call with nothing open, record"
    shape as `PaymentService` and `InvoicePdfService`), and
    `SqsNotificationListener` (`@SqsListener`, Spring Cloud AWS) consumes
    it and calls `NotificationDeliveryService.deliver`. SQS replaces
    hand-written retry/backoff: a failed delivery simply isn't
    acknowledged, so SQS redelivers it after the queue's visibility
    timeout, and the redrive policy in `docker/elasticmq/elasticmq.conf`
    (`maxReceiveCount = 5`) moves it to `notifications-dlq` after repeated
    failure. **ElasticMQ stands in for real SQS** locally and in tests —
    same "code against the standard, keep the vendor a config value" shape
    as MinIO/S3 and fake/Stripe; only `spring.cloud.aws.sqs.endpoint`
    changes for production. SNS was deliberately left out: fan-out needs at
    least two subscribers, and today there is exactly one (email); it
    becomes the right addition once outbound tenant webhooks give it a
    second.
  - **Delivery is at-least-once, not exactly-once, end to end** — the relay
    can publish twice, SQS can redeliver, SMTP can accept a message right
    before a crash. `NotificationDeliveryService.deliver` treats an
    already-`SENT` row as a no-op, which is what makes a duplicate message
    harmless rather than a duplicate email; the one gap that can't be
    closed this way is a crash in the split second after SMTP accepts the
    email but before the row is marked `SENT`, which is documented rather
    than hidden.
  - **Every email is HTML with a plain-text alternative**, rendered
    together by `ThymeleafEmailRenderer` (`notification/infra/template`,
    the second Thymeleaf adapter alongside `billing/infra/pdf` — the
    vendor-containment ArchUnit rule was split in two so both are allowed
    without opening Thymeleaf up everywhere). One `layout.html` fragment
    (inline styles, table layout, no `<style>` block, no external CSS or
    images — Gmail/Outlook strip most of the rest) is shared via
    `th:replace="~{layout.html :: layout(~{::section})}"`, the vanilla
    "pass a fragment as an argument" pattern, no layout-dialect dependency.
    `th:text` only, never `th:utext` — a customer name is tenant-supplied
    input going into HTML. The subject is a plain Java string the caller
    puts straight into the model and the renderer returns unchanged, rather
    than parsed back out of the rendered `<title>` — simpler and no HTML
    parsing needed.
  - **SMTP is the last hop, and is itself a config value**: Mailpit locally
    and in tests, Amazon SES's SMTP interface in production, a host change
    only. `SmtpNotificationSender` (`notification/infra/mail`) is the one
    adapter allowed to import `jakarta.mail`/`spring-mail`, same containment
    pattern as everything else vendor-shaped in this codebase.
  - **Four emails today**: invoice issued, payment failed (dunning's non-final
    branch, with the schedule's own `nextAttemptAt`), payment recovered
    (dunning's `onPaymentSucceeded`, and only on a real PAST_DUE -> ACTIVE
    transition — see the Notifications entry below), and subscription canceled
    (dunning's `giveUp`).
  - **All four carry the invoice PDF**, not just the invoice-issued one.
    `NotificationDeliveryService` attaches it to any notification whose row has
    an `invoice_id`, which today is all of them, and generates it first if
    `BillingCycleJob` has not yet — self-healing the same way that job already
    tolerates a storage outage. Attaching the invoice to the emails *about* that
    invoice is reasonable, but it is a property of the dispatcher rather than a
    per-template choice: a future notification type that should not carry the
    PDF needs the condition to become something narrower than "has an invoice".
  - `billing/domain/InvoiceIssuedListener` is a port implemented by
    `NotificationService`, so `billing` still never depends on `notification` —
    the same `PaymentOutcomeListener` shape payment already uses for dunning,
    used a second time.
  - **Deduplication is a caller-checked existence test backed by
    `uk_notification_tenant_dedup_key`** (`{type}:{invoiceId}[:{attempt}]`),
    the same two-layer idempotency shape as invoice generation
    (`uk_invoice_tenant_sub_period`) and payments (the in-flight partial
    index): the check is what prevents a fired constraint from rolling back
    the settlement transaction it joined in practice, and callers are
    already idempotent by state, so the constraint is a backstop, not the
    mechanism.
  - Verified against real containers, not mocks: `NotificationIntegrationTest`
    proves the full path (outbox row → relay → ElasticMQ → listener →
    Mailpit, PDF attached), that a redelivered message doesn't send twice,
    and that a notification cannot be delivered under the wrong tenant —
    the same category of proof `TenantIsolationIntegrationTest` gives the
    rest of the schema. No test class introduces Mockito; the codebase's
    existing style (pure unit tests or real containers, no mocks) held for
    this module too.
  - **Also verified live, and only live** — see CLAUDE.md §5's "Spring Cloud
    AWS SQS + Java records" entry. `SqsTemplate`'s default payload
    conversion sent a record's `toString()` instead of JSON against a real
    ElasticMQ container, and `NotificationIntegrationTest` passed both
    before and after that bug was fixed — a real gap between the automated
    suite and a live `spring-boot:run` that only running the app for real
    surfaced. Both `SqsNotificationPublisher` and `SqsNotificationListener`
    now serialize/deserialize the queue payload by hand with `ObjectMapper`
    rather than trust the framework's automatic conversion for a record.
- **Tenant provisioning** — a platform-level principal (`auth/`) plus a new
  `platform/` module (`api`, `app`). Closes two gaps at once: there was no way to
  create a tenant, and no principal that could act across tenants.
  - **Platform administrators live in `platform_user` (V15)**, not in `app_user`
    with a nullable `tenant_id`. A nullable tenant turns "forgot to set the
    tenant" into "is a platform admin", and NULLs never collide in
    `uk_app_user_tenant_email`. No role table: one role (`PLATFORM_ADMIN`), issued
    in the `roles` claim so the existing `JwtAuthenticationConverter` maps it
    unchanged. `PlatformRole` is deliberately not a value of `Role`, which is the
    tenant vocabulary guarded by `chk_app_user_role_value` — kept apart, a tenant
    account cannot hold it even by a stray row insert.
  - **Separate login, `POST /api/platform/auth/token`**, rather than "omit
    `tenantId` to become a platform admin" on the tenant login. The token has no
    `tenant_id`. Both logins share `PasswordVerifier` (always hash, one error
    code) and `TokenIssuer` (issuer, lifetime, subject), extracted from
    `AuthService` so the enumeration defence exists once rather than as two copies
    that could drift.
  - **Two-way isolation in `SecurityConfig`**: `/api/platform/**` requires
    `PLATFORM_ADMIN`; `anyRequest()` requires a `tenant_id` claim. A platform token
    on `/api/products` is 403, not the 400 `TENANT_MISSING` it would otherwise
    reach — `MissingTenantException` is now a backstop no real request hits.
    `/actuator/**` stays `authenticated()` for either token until Observability.
  - **`platform/` exists because of a cycle, not taste.** Provisioning writes a
    tenant and an `app_user`; `auth` already depends on `tenancy`
    (`AuthService` checks the tenant is active), so the service in `tenancy` would
    close `tenancy → auth → tenancy`. Same forced shape as `dunning/`. It has no
    `domain` or `infra`: it owns no data, it orchestrates two modules that do.
  - **Provisioning creates the tenant and its first ADMIN in one transaction**,
    with a `SecureRandom` 192-bit base64url password returned once in the 201 and
    stored only as a bcrypt hash. Generated rather than supplied, so nobody picks a
    password on a tenant's behalf or sends one in a request body; show-once like an
    API key. A tenant without an admin would be unusable — there is no
    user-management API — so a failed user insert must take the tenant with it.
  - **`TenantRepository.create` uses `EntityManager.persist` + `flush`, not
    `save`.** The tenant id is an assigned slug, and Spring Data's `save` merges
    any entity whose id isn't null: a create that raced past the existence check
    would silently overwrite the other tenant's name. With persist, the duplicate
    fails on `tenant_pkey`, which `ProblemDetailsAdvice` maps to 409 — the same
    check-then-constraint pattern as everywhere else, and the first repository
    adapter that needed more than delegation (the seam the trio exists for). An
    8-thread concurrent-create test asserts one 201 and seven 409s.
  - **Slug rule `^[a-z][a-z0-9-]{1,62}$`**: lowercase so `Acme`/`acme` can't be
    two tenants; no underscores, which also makes the `__no_tenant__` sentinel
    impossible to provision; safe in a JWT claim, MDC and an object key.
  - **Deactivate/activate are idempotent POST commands**, not a PATCH of `active`.
    Deactivation is instant even for issued tokens, because `TenantResolverFilter`
    already re-checked `active` on every request — this is the first code path
    that ever exercised that check. Nothing is deleted: every tenant table
    cascades from `tenant`.
  - **Bootstrap**: `db/seed/V9002` seeds `platform@subscriptionhub.test` under
    `dev`; elsewhere `PlatformAdminBootstrap` (`ApplicationRunner`) creates one from
    `PLATFORM_ADMIN_EMAIL`/`PLATFORM_ADMIN_PASSWORD` **only while `platform_user`
    is empty**, so the variables are a way in, not a standing credential. Both
    unset is a no-op; one set, or a password under 12 characters, fails startup.
    Unit-tested with an in-memory repository, because every test database already
    holds the seed and the empty-table branch is unreachable there.
  - Verified live as well as in tests: a provisioned admin logged in with the
    generated password and created a product; deactivation turned that token into
    401 `TENANT_UNKNOWN` and activation restored it; the jar started against an
    empty Postgres without the dev profile created the bootstrap admin. One thing
    only the live run showed: **Boot accepts HTTP before `ApplicationRunner`s
    finish**, so a login fired the instant "Started" is logged can 401. Boot's
    readiness state only flips to accepting traffic after runners, so a deployment
    gated on the readiness probe never sees it.
- **Deactivation policy: suspended, not billed, not forgiven.** Deactivating a
  tenant stops the platform acting on its behalf, still records what already
  happened, loses nothing, and resumes on reactivation. Pinned by
  `tenancy/TenantDeactivationPolicyIntegrationTest`.

  | Path | While inactive | On reactivation |
  |---|---|---|
  | API with the tenant's tokens | 401 `TENANT_UNKNOWN` | works again, same tokens |
  | Renewal + invoicing (`BillingCycleJob`) | skipped | missed periods invoiced |
  | Dunning charges (`DunningJob`) | skipped | open invoices charged |
  | Outbox relay (`NotificationRelayJob`) | skipped, rows stay `PENDING` | published |
  | Payment reconciliation (`PaymentReconciliationJob`) | skipped, payments stay `PENDING` | asked about and settled |
  | Messages already on SQS | returned to `PENDING`, acknowledged | republished by the relay |
  | Provider webhooks | **still settle** | — |

  - **Why not keep billing:** the usual reasons to deactivate are an ended
    contract, the tenant not paying the platform, or fraud. Charging cards and
    emailing customers for a business the platform has cut off is a liability,
    and in the fraud case exactly wrong.
  - **Why not forgive the gap:** deactivation cuts the tenant off from *this* API,
    not from its own customers, who almost certainly kept using the tenant's
    product. The periods are owed; waiving them is the tenant's decision, not one
    the platform should make silently. Subscription pause/resume doesn't re-anchor
    periods either (`SubscriptionService.resume`), so this is consistent.
  - **Why webhooks still settle:** the money already moved at the provider.
    Refusing to record it would not undo the charge, only make the books disagree
    with Stripe's.
  - **No deactivation "reason" or second inactive state.** A permanent offboarding
    is simply never reactivated, so the one flag already gives the right outcome.
  - **The one real bug it surfaced**: the relay stopped publishing for inactive
    tenants, but messages *already on the queue* were still delivered, so a
    deactivated tenant's customers kept getting email. `NotificationDeliveryService`
    now re-checks the tenant and hands the row back to the outbox. It returns
    normally rather than throwing: a throw would have SQS redeliver the message
    until the redrive policy dead-letters something that did nothing wrong. The
    check runs **after** the already-`SENT` check, never before, or a redelivered
    duplicate of a sent email would be re-queued and sent twice.
- **Audit events** — `audit/` module (`api`, `app`, `domain`, `infra/jpa`) and
  V16, which activates `audit_event` (unused since V1). It waited on
  authentication, because an audit log exists to record *who*.
  - **Written in the change's transaction, by an explicit call.**
    `AuditService.record` is `@Transactional(propagation = MANDATORY)`. After
    commit (`@TransactionalEventListener`) a crash loses the event; in its own
    transaction a rolled-back change leaves one behind. MANDATORY makes a caller
    with no transaction fail immediately, and `AuditIntegrationTest` asserts both
    that and the rollback case. An aspect or a Hibernate listener was rejected
    because it sees a row update, not intent: an admin's cancel and dunning's
    cancel are the same `UPDATE`. Envers was rejected because it versions rows,
    and almost nothing here is ever updated.
  - **That surfaced a real gap first**: `ProductService`, `PlanService`,
    `PlanEntitlementService`, `CustomerService` and `SubscriptionService` had no
    `@Transactional` at all, so each repository call committed on its own. That
    was harmless with one write per use case, and MANDATORY refused it as soon
    as a second write (the event) existed. Made transactional in a separate
    refactor commit. This does not prevent lost updates between concurrent
    requests; at the time there was no `@Version` anywhere (see Customer
    updates below for the first).
  - **Actor = kind + id, resolved from the security context** (`AuditActors`),
    never passed by the caller. A `tenant_id` claim means `USER`, the
    `PLATFORM_ADMIN` role means `PLATFORM_ADMIN`, and no JWT (a job, or a webhook's
    anonymous token) means `SYSTEM`. A JWT of neither shape throws. The id is the
    token subject (a UUID), never an email. A USER recording into a tenant other
    than its token's throws too; no caller can do that today, so it is a guard,
    not a code path.
  - **Settlement and dunning record `SYSTEM` explicitly** (`recordSystem`). The
    fake gateway settles inside the paying admin's request, while Stripe settles
    in an unauthenticated webhook. Deriving the actor would log the same outcome
    as USER under one provider and SYSTEM under the other. The fake is meant to
    be the same shape as the real thing, and its audit trail is part of that
    shape. `DunningIntegrationTest` pins it with a manual declined payment.
  - **`AuditEventEntity` does not extend `TenantScoped`**, making it the second
    entity after `AppUserEntity`, for a different reason. A platform request's
    session is pinned to `__no_tenant__`, `@TenantId` rejects an insert for any
    other tenant, and the provisioning event must commit in the provisioning
    transaction, so a fresh session is not an option. As a side effect the
    platform read endpoint needs no session handling at all.
  - **Events** (`AuditEventType`, each bound to its `AuditEntityType` so a caller
    cannot file an event under the wrong kind of record): product/plan/entitlement
    created (the plan's price in `data`), customer created, payment method
    set/removed (never the token), subscription created/canceled/paused/resumed,
    plus `PAST_DUE`/`RECOVERED` from dunning, invoice issued/paid/uncollectible,
    payment succeeded/failed, and tenant provisioned/deactivated/activated.
    **Not audited**: renewals and usage increments (high volume, fully
    derivable), reads, and logins (an unknown tenant cannot satisfy the FK, and
    authentication events belong in logs and metrics).
  - **Only real changes.** A refused transition (409), deactivating an inactive
    tenant, or clearing an unset payment method writes nothing. A dunning
    cancellation reuses `SUBSCRIPTION_CANCELED` with `reason = DUNNING_EXHAUSTED`,
    and the actor tells the two cancellations apart.
  - **Reads**: `GET /api/audit-events[?entityType=&entityId=]`, restricted to
    `ADMIN`. This is the one read carrying a `@PreAuthorize` (`Authorize.AUDIT_READ`),
    a deliberate exception to "unannotated reads are deliberate". Also
    `GET /api/platform/tenants/{id}/audit-events`, where an unknown tenant is a
    404 rather than an empty page. Half a filter is 400
    `AUDIT_FILTER_INCOMPLETE`: silently returning the whole timeline would look
    like one record's history. A tenant's admins see what the platform did to
    their tenant as well.
- **Subscription transitions as compare-and-set** (roadmap step 4b). Every write
  to a subscription after creation used to be load, set a field, save. A read-then-
  write loses to a concurrent writer, and a subscription has more of those than
  anything else: requests, `BillingCycleJob`, and dunning inside payment settlement.
  Reading the writers turned up four lost updates in shipped code:

  | Concurrent writes | What happened |
  |---|---|
  | Customer pauses while settlement marks `PAST_DUE` | The pause was overwritten |
  | Customer cancels while `BillingCycleJob` renews | `applyRenewal` set `status = ACTIVE`, **undoing the cancellation** and billing on |
  | Customer cancels while a recovery settles | `onPaymentSucceeded` read `PAST_DUE`, wrote `ACTIVE`: **a canceled subscription reactivated** |
  | A request transitions from a status that just changed | Applied from the stale status, with a wrong `from` in the audit event |

  - **Not `@Version`, which was the plan.** Every one of these writers is a state
    command, and CLAUDE.md §5 already says state commands use conditional updates.
    `@Version` would only have detected each conflict, leaving five callers to
    re-read and re-decide. The worst of them was inside `FakePaymentGateway`'s
    synchronous settlement: a version exception there rolls back the payment. A
    dunning payment then stays `PENDING` with no provider reference, and
    `hasPaymentInFlight` skips that invoice forever.
  - **Compare-and-set on the exact observed status**, not on the set of allowed
    statuses. `updateStatusIfStatus`, `cancelIfStatus` and `renewIfCurrent` are
    JPQL bulk updates with `WHERE status = :expected`; renewal also matches
    `current_period_end = :expectedPeriodEnd`. With `IN (allowed)`, a cancel that
    raced dunning would have succeeded, but recorded `from: ACTIVE` for a row that
    was `PAST_DUE`.
  - **A miss re-reads and decides again**, up to three times. The re-read uses
    `findCurrentByTenantIdAndId`, which refreshes the entity: a plain find returns
    the stale persistence-context instance.
    - **Requests:** a cancel that lost to `PAST_DUE` still cancels. A pause that
      lost to a cancellation is 409 `INVALID_SUBSCRIPTION_STATE`. Three misses in a
      row is 409 `CONCURRENT_MODIFICATION`.
    - **Dunning:** a status that no longer allows the change is a correct no-op, not
      an exception, because this runs inside settlement's transaction and must not
      roll back money that moved.
    - **Renewal:** a miss simply returns false; the next run decides from the new
      state.
  - **Renewal became a pure calculation.** `renewalFor(subscription, now)` returns
    the next period and no longer modifies the entity. A modified managed entity is
    written at commit whether or not the conditional update applied, which would
    have reintroduced the bug through the back door. `SubscriptionRenewalServiceTest`
    pins that it never modifies its input.
  - **No `clearAutomatically`**, unlike `TenantJpaRepository`. These updates run
    inside dunning's settlement transaction, and clearing the persistence context
    there would detach the invoice and payment still in use. The adapter instead
    refreshes just the updated subscription, and `flushAutomatically` writes the
    caller's pending changes first.
  - **Proven by forcing each race**, not by threads hoping to collide.
    `SubscriptionTransitionRaceIntegrationTest` runs each scenario the same way:
    1. A transaction reads the subscription.
    2. Another connection commits the competing change.
    3. The real service method runs in the now-stale transaction.

    It covers renewal vs cancel, past-due vs pause, recovery vs cancel, a refused
    request, and a request that applies from the new status and records the real
    `from`. Not shown: these tests failing against the old code. They call
    repository methods that did not exist before, so the old failures are argued
    from the code, not demonstrated.
  - **No schema change.**
- **Customer updates and optimistic locking** — `PUT /api/customers/{id}`, the
  first update endpoint in the codebase, plus V17 (`customer.version`). Closes
  the update half of "no update or delete anywhere", and the
  concurrent-deactivation double-record.
  - **Customer, not Plan or Product.** A plan's price is read when an invoice is
    generated, so editing it would reprice every subscriber's current, unbilled
    period. That is a plan-versioning design problem, not an endpoint. Product
    was safe but too trivial to show anything.
  - **`@Version` alone does not stop a lost update between two clients.** It
    only catches overlap within one request's read-modify-write, a
    milliseconds-wide window. The real lost update is two people who each GET,
    edit for a minute, and PUT. So the version goes out as a strong `ETag` on
    every single-customer response, including the payment-method ones, and the
    PUT requires it back as `If-Match`:

    | Case | Status | `code` |
    |---|---|---|
    | `If-Match` missing, or `*` | 428 | `PRECONDITION_REQUIRED` |
    | Not the current version, detected before writing | 412 | `PRECONDITION_FAILED` |
    | Version matched at load, then another write committed before this flush | 409 | `CONCURRENT_MODIFICATION` |

    `*` counts as missing because RFC 9110 lets it mean "any version", an
    unconditional overwrite by another name. Refusing a missing `If-Match` rather
    than applying it makes the protection mandatory: opt-in would protect only
    the careful clients.
  - **Two checks for two windows.** `CustomerService.update` compares the loaded
    version with the one the client sent (412), and `@Version` adds
    `and version = ?` to the UPDATE for the gap between that load and the flush.
    The version is compared explicitly because writing it onto a managed entity
    does not work: Hibernate checks the version it loaded.
    `ObjectOptimisticLockingFailureException` surfaces at commit, still inside
    dispatch, and `ProblemDetailsAdvice` maps it.
  - **Proven deterministically, not only by racing.** The 8-thread HTTP test
    accepts 412 or 409 for the losers, so it cannot show the 409 path ever ran.
    A second test loads the customer in a transaction, commits a versioned
    UPDATE from another thread's connection, then commits, and asserts the
    exception type. It must be another thread: on the same thread `JdbcTemplate`
    joins the JPA transaction.
  - **Only real changes.** An identical PUT writes nothing, so the version and
    every other client's ETag stay valid. `CUSTOMER_UPDATED` lists
    `changedFields` by name, never the values, which are personal data.
  - **PUT, full replacement**, with the same fields and validation as create. An
    omitted `externalId` clears it. The payment method is not part of the
    representation; it keeps its own sub-resource. PATCH was rejected because a
    record cannot tell an absent field from an explicit null without
    `JsonNullable` or JSON Merge Patch.
  - **Tenant activation got a conditional UPDATE instead of `@Version`.**
    `setActive` used to read the flag, then write it, so two concurrent
    deactivations could each see "active" and each record `TENANT_DEACTIVATED`.
    `@Version` would have fixed the double record, but by giving the loser a 409
    for a command whose intent was already satisfied, and deactivation is
    documented as idempotent. Now `UPDATE tenant SET active = :active ... WHERE
    id = :id AND active <> :active` reports whether it changed a row, and only
    that call records. Under READ COMMITTED, Postgres re-evaluates the WHERE on
    the committed row once the first transaction releases its lock, so exactly
    one call sees a change. An 8-thread test asserts eight 200s and one event.
    The rule it leaves behind: optimistic locking is for edits made from
    something the caller read earlier; conditional updates are for state
    commands.
- **Observability** — `micrometer-registry-prometheus`, a second security filter
  chain, `common/metrics`, and Prometheus + Grafana in Compose
  (`docker/prometheus`, `docker/grafana`). It was preceded by two roadmap steps
  so its numbers would mean something: the error-mapping sweep, so a caller's typo
  is not a 5xx, and a logging fix, so log lines carry the tenant.
  - **The log pattern had never shown a tenant.** `logging.pattern.console` read
    `%X{tenant}`, while every writer (`TenantResolverFilter`, the three jobs) uses
    `MdcKeys.TENANT_ID`, `"tenantId"`. A pattern naming a missing key prints an
    empty string, so every line said `tenant=` and nothing failed. Found while
    reading today's live-run log for the observability survey.
    `LogPatternIntegrationTest` logs through the configured pattern with the real
    constants.
  - **Who reads metrics: a dedicated scrape account, through its own chain.**
    `MetricsSecurityConfig` orders a `SecurityFilterChain` ahead of the main one,
    matching only `/actuator/prometheus`, with HTTP Basic against one in-memory
    account from `METRICS_SCRAPE_USERNAME`/`PASSWORD`.
    - **Not a JWT:** Prometheus holds a static secret, and tokens expire hourly.
    - **Not the platform principal:** operating the platform is not scraping it,
      and a leaked scrape secret should open metrics and nothing else.
    - **A separate chain, not a rule in the main one:** enabling Basic there would
      make it an accepted scheme on every tenant endpoint.
    - **Unconfigured means closed.** With neither variable set, every scrape is
      401. With one set but not the other, or a password under 16 characters,
      startup fails (`MetricsScrapeCredential`).
    - **The rest of `/actuator/**` moved from any token to `PLATFORM_ADMIN`.**
    - **Rejected:** a separate `management.server.port` behind network isolation,
      because it would have needed verifying how Boot 3.5 applies a custom chain
      to the management context.
  - **Probes:** `/actuator/health/liveness` and `/readiness` are enabled and
    public. Readiness only reports UP after `ApplicationRunner`s finish, which
    covers the documented race where a login fired at "Started" could 401 before
    `PlatformAdminBootstrap` ran.
  - **`http.server.requests` publishes histogram buckets**, so p95 is computed in
    Prometheus. Client-side percentiles cannot be aggregated across instances.
  - **Job health: `JobMetrics`.** All three jobs run through it:
    - `jobs.run` timer, tagged `scheduled.job` and `outcome`
    - `jobs.item.failures`, tagged `scheduled.job` and `step`, for the failures
      each job catches and logs so one bad item does not stop the run
    - `jobs.last.success`, a gauge of epoch seconds

    That gauge is the alertable one: a job that stops produces no error, only an
    absence, and `time() - jobs_last_success_seconds` turns an absence into a
    number that grows.
  - **Business events come from the audit log.** `AuditService` increments
    `audit.events{type, actor}` for every recorded event. Audit events are
    already exactly the real, committed business changes (invoices issued, paid
    and written off, subscriptions past due, recovered and canceled, payments
    succeeded and failed), so a parallel set of increments next to each audit call
    would only be something to drift. Separate counters exist only for:
    - what is deliberately not audited: `subscription.renewals`,
      `auth.login.failures{principal}`
    - a dimension an event lacks: `payments.settled{outcome, provider}`,
      `dunning.recoveries{attempts}`
    - things that are not events at all: `dunning.attempts.started`,
      `notification.published`, `notification.deliveries{outcome}`
  - **Business counters count only committed work.** `AfterCommit.run` defers
    the increment to `afterCommit`, so a rolled-back invoice is not counted.
    `BusinessMetricsIntegrationTest` asserts a rollback leaves the counter
    unchanged. This is the opposite choice from the audit row on purpose: the row
    must commit with its change, while a metric only describes what did. A crash
    between commit and increment loses one count, and counters already reset on
    restart.
  - **Outbox health is a gauge read at scrape time.**
    `notification.outbox.oldest.age{status}` is the seconds since the oldest
    `PENDING` row (the relay is stuck) and the oldest `PUBLISHED` row (published
    but never delivered, or dead-lettered). Nothing reads `notifications-dlq`, so
    this gauge is the only thing that notices a dead-lettered message.
    - Read from the database on each scrape rather than cached by the relay, so it
      stays honest exactly when the relay is what stopped.
    - It is the codebase's third native query, and the first cross-tenant one:
      `NotificationJpaRepository.oldestAgeSecondsAcrossActiveTenants` returns one
      number and no rows, excludes inactive tenants (whose rows are held on
      purpose), and computes age with the database clock.
  - **No tenant labels anywhere.** Every label value is a separate series, and
    tenants are unbounded. Per-tenant questions go to the logs. Failed logins
    carry no reason tag either: one reason for every failure is the enumeration
    defence, and `/actuator/prometheus` must not undo it.
  - **One real bug only a live scrape showed.** `JobMetrics` first tagged its
    meters `job`. Prometheus attaches its own `job` label (the scrape target) to
    every series and renamed the application's to `exported_job`, so every
    `{job="billing-cycle"}` in the alert rules and dashboard would have matched
    nothing. The full suite passed with it, because the tests read the registry,
    not Prometheus. Renamed to `scheduled.job`, and
    `noMeter_usesALabelPrometheusReservesForTheScrapeTarget` now fails on `job`
    or `instance`.
  - **In Compose:**
    - **Prometheus** (`prom/prometheus:v3.5.0`) scrapes `host.docker.internal:8080`.
      `prometheus.yml` cannot read environment variables, so the entrypoint writes
      `METRICS_SCRAPE_PASSWORD` to the `password_file` it points at. The username
      is fixed as `prometheus` and must match `.env`.
    - **Ten alert rules** (`alerts.yml`): app down or the scrape credential wrong,
      5xx ratio, each job stale, items failing, outbox not published or not
      delivered, login spike, connection pool saturated.
    - **Grafana** (`grafana/grafana:12.1.1`) is provisioned with the datasource
      and one dashboard: HTTP, Hikari, jobs, billing and collection,
      notifications, JVM.
  - **Verified live, not only in tests:**
    - the Prometheus target is `up` with the scrape account
    - all ten rules loaded
    - queries return the application series
    - Grafana's datasource health check passes, and the dashboard is provisioned
      under its folder
    - a request log line reads `tenant=acme requestId=...`
- **Payment reconciliation** — `PaymentReconciliationJob` /
  `PaymentReconciliationService` (`payment/app`), one new method on
  `PaymentGateway`, and V18's partial index. Closes the gap this module's
  Known gaps called "the honest next step ... the first thing a real system
  would add".
  - **The bug it fixes is unattended and total, not cosmetic.** A payment left
    `PENDING` by an unreachable provider holds its invoice's slot in
    `ux_payment_invoice_in_flight_or_succeeded`, and `DunningService.startAttempt`
    skips any invoice with a payment in flight. So that invoice was never
    collected again, never became `UNCOLLECTIBLE`, and its customer was never
    emailed — and nothing anywhere reported a failure, because nothing failed.
    The only route back was re-POSTing with the original `Idempotency-Key`, which
    needs a caller who still has it.
  - **Polling is the backstop, not the mechanism.** Webhooks still settle
    virtually everything; this only picks up what delivery dropped. It is the
    concrete argument for why at-least-once delivery is never sufficient on its
    own — worth being able to make in an interview.
  - **One new port method, and no new settlement path.**
    `PaymentGateway.reconcile(PaymentLookup)` returns
    `Optional<PaymentEvent>` — present is a terminal outcome, empty is "still in
    progress, ask again", and `PaymentGatewayException` is "could not ask". What
    comes back goes to `PaymentSettlementService.handle` like any webhook, so
    audit (`PAYMENT_SUCCEEDED`/`PAYMENT_FAILED`, `SYSTEM`), `payments.settled`
    and `DunningService`'s listener are all reached unchanged. "One place settles
    money" survives intact.
  - **It never abandons a payment it could not ask about**, and that was the one
    real design decision. Marking an unreachable payment `FAILED` to free the
    slot is a double-charge bug: a create can time out *after* the card was
    charged, and dunning's next attempt uses a **new** idempotency key
    (`dunning:{invoiceId}:{attempt+1}`). So it stays `PENDING` and escalates to a
    human through the gauge and alert below. Rejecting the automatic fix in
    favour of an alert is the defensible choice, not a missing feature.
  - **A null `providerReference` is resolved by re-issuing the create under the
    same idempotency key**, not by a search API and not by assuming the create
    never landed. Stripe returns the original PaymentIntent if one exists and
    creates it otherwise, so the customer is charged exactly once either way —
    the whole reason our payment id is the key. Stripe's search API would be
    read-only but is eventually consistent and one more endpoint `stripe-mock`
    may not implement, for no extra safety.
  - **The fake answers it without storing anything**, as a pure function of the
    request, the same trick that makes its references deterministic: a reference
    means the create landed, so the outcome is whatever the payment method says;
    no reference re-runs the create path. It *returns* the event rather than
    delivering it through the handler the way `createPayment` does — a gateway
    that settled on the side would be a second path into the money.
  - **A late settlement must not re-count a dunning attempt.**
    `DunningService.onPaymentFailed` reads the count `startAttempt` already
    committed before the provider was called, so a failure settled hours later
    schedules the next retry without inflating the attempt number. Pinned by the
    integration test, since it is the kind of thing a refactor would quietly break.
  - **`payments.pending.oldest.age`** (`PaymentPendingMetrics`) is the escalation
    path, mirroring `NotificationOutboxMetrics` exactly: a counter cannot show a
    stuck payment, because nothing failing looks identical to nothing happening,
    while the age of the oldest one grows without bound. Read from the database
    on each scrape rather than cached by the job, so it stays honest precisely
    when the job is what stopped. Backed by the fourth native query,
    cross-tenant on purpose (a scrape has no tenant) — `PENDING` is written
    literally rather than bound, because `payment.status` is a Postgres enum and
    a bound string would need an explicit cast. `payments.reconciled{outcome,
    provider}` counts what only settled because we asked, which is the health of
    the webhook path — a dimension `payments.settled` cannot carry.
  - **V18 is an index and nothing else.** No new column, no new
    `PaymentStatus` value, no new audit event type: a reconciled payment is
    `SUCCEEDED` or `FAILED` like any other. `idx_payment_pending_created` is
    partial on `PENDING`, so it holds only payments still in flight rather than
    growing with every payment ever made.
  - **Tests build every fixture from a real stuck payment** — paying with
    `pm_fake_provider_unavailable` leaves exactly what an unreachable provider
    leaves — and then edit only what time and a recovered provider would have
    changed. One test walks the whole defect: dunning strands a payment, a second
    dunning run does nothing at all, reconciliation clears it, and dunning
    collects. `stripe-mock` answers every request with the same
    `requires_payment_method` fixture, so the status mapping is unit-tested
    against constructed `PaymentIntent`s (`outcomeOf` is package-private for
    this) and the container proves only that the retrieve and re-create calls
    are wired.
  - **Verified live as well as in tests**, which is where this project's last two
    surprises came from. Against a real `spring-boot:run`: V18 applied
    out-of-order under `dev`; the six-field cron parsed from
    `PAYMENT_RECONCILIATION_CRON`; a stranded payment settled and its invoice
    went `PAID`; and a live scrape showed
    `jobs_last_success_seconds{scheduled_job="payment-reconciliation"}`,
    `payments_pending_oldest_age_seconds` and `payments_reconciled_total` with no
    reserved label names. The run also turned up **two genuinely stuck payments
    already sitting in the dev database** from earlier manual testing, which the
    job correctly refused to guess at and which pushed `PaymentStuckPending` to
    pending — the alert proving itself on data nobody planted.

- **Invoice PDF lost update** — a `fix`, and the only bug in this project so far
  that was destroying committed financial state rather than merely risking it.
  - **Symptom, seen live:** an invoice with a `SUCCEEDED` payment against it and
    `INVOICE_PAID` in the audit log sat at `status = OPEN, paid_at = NULL`. The
    customer had paid and the invoice looked unpaid, so dunning would collect it
    again — a double charge.
  - **Cause:** `InvoicePdfService.generatePdf` was one `@Transactional` method
    that loaded the invoice, rendered, **uploaded to the object store**, then set
    `pdfObjectKey` on the loaded entity and saved it. Hibernate writes every
    column of a dirty entity from the snapshot it loaded, so a payment settling
    during the slow upload was overwritten by the stale `OPEN`/`NULL` values when
    the PDF transaction committed second. The row proved the ordering:
    `pdf_object_key` set, `updated_at` later than the settlement, `paid_at` null.
  - **The reasoning that hid it.** The method's javadoc explicitly defended
    holding a transaction across the upload: storing the bytes *is* the
    operation, and a storage failure would roll back a transaction that had
    written nothing. That is true and entirely about rollback. It says nothing
    about what else might commit during the window, which is the actual risk of
    a long transaction.
  - **Why invoice alone was unprotected.** Subscriptions got compare-and-set,
    customer got `@Version`, tenant got a conditional UPDATE. Invoice got none,
    on the documented belief that it is "immutable once issued" — but `status`,
    `paid_at` and `pdf_object_key` are all written after issue. The belief, not
    an oversight, is what left the gap.
  - **Fix:** the same three-step split as `PaymentService` — load and render in a
    short read-only transaction (rendering is local CPU work and needs the lazy
    associations), upload with nothing open, then record through
    `InvoiceRepository.attachPdfObjectKeyIfAbsent`, a conditional UPDATE naming
    one column. `status` and `paid_at` are now untouchable from this path however
    stale the caller's view. The `pdf_object_key is null` guard also makes
    concurrent generation idempotent: one caller wins, the other gets the
    existing 409 rather than both claiming success.
  - **All three callers were exposed, and all three are fixed by the one change.**
    `NotificationDeliveryService` (self-healing a missing PDF at delivery) is
    where it was seen, but `BillingCycleJob.generatePdfSafely` uploads in the
    same way right after invoicing, and so does the `POST /api/invoices/{id}/pdf`
    endpoint; a payment settling during any of those uploads hit the same window.
  - **Only a live run could find it.** `notification.relay.delay` is parked at a
    day in `AbstractIntegrationTest`, so no test ever generated a PDF while a
    payment settled. It surfaced during the payment-recovered email's manual
    check, when the SQS listener self-healed a missing PDF in the two seconds
    between a declined payment and a successful one. The third such find after
    the SQS record payload and the `job` label — all three from running the app,
    none from a green suite.
  - **`InvoicePdfRaceIntegrationTest` forces the race** the way
    `SubscriptionTransitionRaceIntegrationTest` does: a transaction reads the
    invoice, another connection commits the settlement, the key is recorded from
    inside the stale transaction. Unlike the subscription race tests, this one
    was **demonstrated to fail against the old code** — reverting the adapter to
    load-modify-save reproduces exactly the live symptom, `expected "PAID" but
    was "OPEN"`.
  - `PaymentIntegrationTest.pay_afterADecline_canBeRetriedWithANewKeyAndSucceed`
    covered this exact sequence but asserted only the payment statuses, never the
    invoice. The missing assertion is now there. It passes either way — it takes
    the forced race to catch the bug — but a succeeded payment's invoice is worth
    asserting wherever it is claimed.

- **Architecture tests** — `architecture/ArchitectureTest` (ArchUnit,
  `com.tngtech.archunit:archunit-junit5`) turns CLAUDE.md §3/§5's layering and naming
  rules into executable checks: no `..domain..`/`..api..` dependency on
  `..infra..` beyond the `*Entity` carve-out below, `common` never depends on
  a feature module, Spring Data types stay behind `..infra.jpa..`, the domain
  stays free of `jakarta.persistence`, Thymeleaf (split across its two
  adapters, `billing.infra.pdf` and `notification.infra.template`),
  openhtmltopdf, the AWS SDK (S3 confined to `billing.infra.storage`; SQS
  and Spring Cloud AWS confined to `notification.infra.sqs`) and
  `jakarta.mail`/`spring-mail` (confined to `notification.infra.mail`) each
  stay inside their one adapter package (the Stripe SDK too, confined to
  `payment.infra.gateway`), `*Controller`/`*RepositoryImpl`/
  `*Entity` sit where their name says, no cycles between feature-module
  slices, and no field-level `@Autowired`. Pure bytecode analysis — no Spring
  context, no containers — so it runs in milliseconds alongside the unit
  tests. Rules 1a/1c (`domain`/`api` → `infra`) explicitly allow a dependency
  on a class ending in `Entity` inside `..infra.jpa..`: CLAUDE.md §3 already documents
  that catalog/customer/subscription have no separate domain model, so ports
  and mappers seeing the entity directly is the deliberate consequence of
  that choice, not drift. ArchUnit checks declared dependencies (fields,
  parameters, return types, method bodies), so it can't see an entity that
  only passes through as an uncaptured local variable — a couple of
  controllers that read an entity's `getId()`/`getCode()` to build a
  `Location` header get flagged where others that only forward the entity to
  a mapper don't; the rule undercounts that coupling, never overcounts it.

**Endpoints:**
```
POST          /api/auth/token          (public)
POST          /api/platform/auth/token (public)
POST GET      /api/platform/tenants     (PLATFORM_ADMIN)
GET            /api/platform/tenants/{id}
GET            /api/platform/tenants/{id}/audit-events
POST          /api/platform/tenants/{id}/deactivate
POST          /api/platform/tenants/{id}/activate
GET  /api/health                       (public)
GET  /actuator/health, /actuator/health/liveness, /actuator/health/readiness   (public)
GET  /actuator/prometheus              (scrape account only, HTTP Basic)
GET  /actuator/info                    (PLATFORM_ADMIN)
POST GET      /api/products
GET            /api/products/{code}
POST GET      /api/plans
GET            /api/plans/{code}
POST GET      /api/plans/{planCode}/entitlements
POST GET      /api/customers
GET PUT        /api/customers/{id}          (PUT needs If-Match with the ETag from a read)
POST GET      /api/subscriptions[?customerId=]
GET            /api/subscriptions/{id}
POST GET      /api/subscriptions/{subscriptionId}/usage
POST GET      /api/subscriptions/{subscriptionId}/invoices
GET            /api/invoices[?subscriptionId=]
GET            /api/invoices/{id}
POST GET      /api/invoices/{id}/pdf
PUT DELETE    /api/customers/{id}/payment-method
POST GET      /api/invoices/{id}/payments   (POST needs an Idempotency-Key header)
GET            /api/payments/{id}
POST          /api/webhooks/stripe          (public; authenticated by signature)
GET            /api/audit-events[?entityType=&entityId=]   (ADMIN)
```

---

# Known gaps and improvements

Honest assessment. Several of these matter more for the portfolio than the next
feature module does.

**Architectural**

- **Tenant isolation now rests on a signed claim, not the caller's word.** Until
  authentication landed, `X-Tenant-Id` was client-supplied and unverified: anyone
  who could reach the API was any tenant they liked. `@TenantId` and
  `TenantIsolationIntegrationTest` protected tenant A from tenant B's *bugs*, never
  from B claiming to be A. That is closed — but note `AppUserEntity` is now an
  entity with no `@TenantId` predicate at all (CLAUDE.md §4), so its single repository method
  is the *only* thing scoping it. A second query added there without
  `tenantId` would not be caught by the backstop.

- **Tenant isolation has a structural backstop, now verified but not fully
  closed.** Hibernate `@TenantId` (not `@Filter` — that's opt-in per `Session`
  and easy to forget to enable, so it's not worth using once `@TenantId` is in
  place) adds an automatic `tenant_id =` predicate to every Hibernate-mediated
  query, resolved from the same `TenantContext` via `TenantIdentifierResolver`.
  `TenantIsolationIntegrationTest` now proves this holds against a real
  Postgres — a crafted cross-tenant lookup 404s rather than leaking or
  mutating another tenant's row, and the same natural key succeeds under two
  tenants without colliding. It still does **not** protect
  native/`nativeQuery = true` queries — there are now two,
  `UsageCounterJpaRepository.upsertAndIncrement` and
  `InvoiceJpaRepository.allocateNextNumber` (see Current state), which bind `tenantId`
  by hand instead — or anything outside Hibernate entirely (a raw JDBC
  script, a future reporting tool). Postgres row-level security remains
  the stronger, DB-level option for those cases — deliberately not done yet;
  the Testcontainers harness that was the prerequisite for verifying it
  properly now exists, so RLS is next up if the `SET LOCAL`/HikariCP wiring
  is worth it.
- ~~Check-then-save uniqueness race~~ — closed. See Current state's API hardening pass.
  `findByTenantIdAndCode(...).ifPresent(throw)` followed by `save(...)` is
  still not atomic, but the losing side of the race now gets the same 409
  a synchronous duplicate does, instead of a 500.
- ~~`Page<T>` serialization~~ — closed. See Current state's API hardening pass.
- ~~`spring.jpa.open-in-view` is on, by default~~ — turned off (roadmap step 4a).
  - **Why:** Spring's `HibernateJpaVendorAdapter` sets Hibernate's connection
    handling to `DELAYED_ACQUISITION_AND_HOLD` (confirmed in its source). With
    open-in-view on, a request therefore kept the JDBC connection from
    `PaymentService`'s reserve step checked out through the provider HTTP call and
    through PDF streaming, until the response was written. The "no remote call
    inside a transaction" rule held for transactions, but not for the connection
    pool. This was not demonstrated under load with a slowed provider; the source
    is the evidence.
  - **Measured before changing anything.** With only the flag flipped, 11 of 343
    tests failed with `LazyInitializationException`, from two mappers:
    `SubscriptionMapper` reading `plan.getCode()`, and `InvoiceMapper` reading
    `lines`. A live probe of every read endpoint then found three 500s the suite
    could not see, because no test read those endpoints:
    - the plan list and single plan (`PlanMapper` reads `product.getCode()`)
    - the subscription list
    - the invoice list

    The entitlement list has the same shape (`plan.getCode()`), found by reading.
  - **Fixed with explicit fetch plans, not transactions around mapping.** Mappers
    stay in the controller. `@EntityGraph` was added to the finders behind those
    responses, following the one that already existed
    (`InvoiceJpaRepository.findByTenantIdAndId` → `lines`):
    - `PlanJpaRepository` → `product`
    - `PlanEntitlementJpaRepository` → `plan`
    - `SubscriptionJpaRepository`'s three request finders → `plan`

    The paged invoice finders are the exception. A collection fetch with
    LIMIT/OFFSET makes Hibernate paginate in memory (HHH90003004), so
    `InvoiceRepositoryImpl` initializes `lines` inside a read-only transaction,
    and `@BatchSize(32)` loads a page's lines in one extra query. That batch loading
    only ever worked because open-in-view kept the session alive.
  - **Pinned by two tests.** `AssociationReadsIntegrationTest` reads every one of
    those endpoints and asserts the associated field.
    `OpenInViewDisabledIntegrationTest` fails if the interceptor is registered
    again.
  - **Verified after the change:** 349 tests pass, the build log has no
    `LazyInitializationException` or HHH90003004, and the live probe returns 200
    with `productCode`, `planCode` and `lines` populated.
  - **What it removed, and what it did not.** It removed `PaymentWebhookService`'s
    unbind/rebind. It did not remove the `AppUserEntity` and `AuditEventEntity`
    exceptions: login and platform requests still have no tenant in context when
    their transaction opens. `AuditEventEntity` *could* now extend `TenantScoped`,
    if every platform use case wrapped its transaction in `runAs(targetTenant)`. It
    is left as it is, deliberately (see its javadoc).

**Testing**

- Mapper/unit tests, CI, and Testcontainers-backed integration tests are all
  in place now — tenant isolation, subscription state transitions, and
  renewal period math are verified against a real Postgres end-to-end, not
  just unit-tested in isolation (see Current state). `ProblemDetailsAdviceTest` covers
  the check-then-save race's 409 mapping deterministically (constructing the
  `DataIntegrityViolationException` directly, rather than racing two real
  requests). ~~Billing totals aren't testable until Billing is built~~ —
  closed: `InvoiceCalculatorTest` covers the cent-rounding arithmetic in
  isolation, and `InvoiceGenerationIntegrationTest`/`BillingCycleIntegrationTest`
  prove the usage-counter-to-invoice join and the invoice-before-renew
  ordering against a real Postgres, including a 10-thread concurrent
  generation test mirroring usage metering's concurrency proof.
- **The auth tests assert the negative on purpose.** After authentication landed,
  every integration test authenticates for real, so the suite passing shows tokens
  *work* — it would look identical if security were switched off entirely.
  `AuthenticationIntegrationTest` therefore checks that no token, a garbage token
  and another tenant's token are all rejected, and `AuthorizationIntegrationTest`
  uses the seeded `SUPPORT` user to check a lesser role is genuinely refused —
  in both directions, so a globally broken role mapping (which would deny
  everyone) fails too.
- Remaining gap: nothing tests an **expired** token. The TTL is an hour, so it
  would need either clock manipulation or a separately minted short-lived token;
  the tampered-token case covers signature rejection but not expiry.

**API completeness**

- ~~No single-resource reads~~ — closed. See Current state's API hardening pass.
  `GET /{code}` (Product, Plan) / `GET /{id}` (Customer, Subscription) plus
  `Location` on 201.
- **Updates exist for Customer only, and deletes nowhere.** See Current state's
  Customer updates. Product and Plan remain create-only on purpose until plan
  price changes have a versioning answer (a price edit would reprice unbilled
  periods). Subscription changes go through state commands
  (cancel/pause/resume), not a general update. Delete is absent everywhere,
  because every tenant table cascades and invoices, payments and audit events
  hang off these rows.
- No idempotency on create endpoints. Worth at least being able to discuss.

**Smaller**

- ~~No currency consistency rule~~ — moot for now, not solved: every
  invoice covers exactly one subscription → one plan → one currency, so
  nothing can mix *by construction*, not because a rule prevents it. The
  gap returns the day invoices are ever consolidated across a customer's
  subscriptions, and it's latent one level down too — a
  `plan_entitlement` unit price (`unitAmountCents`) carries no currency of
  its own, implicitly inheriting the parent plan's; that's the one place a
  mismatch could hide today, invisible because the two are always read
  together.
- ~~`audit_event` table exists but nothing writes to it~~ — closed by Audit
  events (see Current state).
- ~~Only `InvoiceStatus.OPEN` is ever set~~ — partly closed: payments produce
  `PAID`. `DRAFT`, `VOID` and `UNCOLLECTIBLE` remain declared and unreachable,
  still deliberately: `VOID` needs a cancellation path and `UNCOLLECTIBLE`
  belongs to dunning.
- No update or delete on invoices either, same as everywhere else. An invoice is
  often described here as "immutable once issued", and that is true only of what
  a *client* can change: internally `status`, `paid_at` and `pdf_object_key` are
  all written after issue, by settlement, dunning and PDF generation. Taking the
  slogan literally is what left invoice the one financial row with neither
  `@Version` nor compare-and-set, and cost a paid invoice its status (see
  "Invoice PDF lost update"). The remaining writers are narrow and each names its
  columns; a new one must do the same rather than save a loaded entity.

**Invoice PDFs**

- **Presigned URLs are the deliberate non-choice.** Every download streams
  through the application so it stays inside the tenant-scoped request path.
  At real scale you'd hand out a short-lived presigned object-store URL and
  let the store serve the bytes — that removes the app from the data path
  entirely, at the cost of a URL that authorizes by possession rather than by
  tenant context. Worth being able to argue both ways.
- **No regeneration path.** A second `POST` is a 409, deliberately: an invoice
  is immutable once issued, so its PDF is too. If the *template* changes,
  existing PDFs keep the old layout and there is no way to re-render them
  short of clearing `pdf_object_key` by hand. That's arguably correct for a
  financial document, but it is a real constraint, not an oversight.
- **PDFs are never deleted**, so object storage grows without bound. No
  lifecycle policy, no cleanup when a tenant is removed (the `invoice` row
  cascades from `tenant`, but the stored object does not).
- **Fonts are not embedded.** The template uses `sans-serif`, which
  openhtmltopdf maps to a standard-14 PDF font; readers substitute a local
  face. Fine for a portfolio, but a real invoice PDF would embed a font so it
  renders identically everywhere and passes PDF/A.
- The renderer and the storage adapter are both exercised against real
  dependencies, but there is **no test for the storage failure path** — e.g.
  that `BillingCycleJob` really does continue when MinIO is unreachable. That
  behaviour is asserted only by reading the code.

**Payments**

- ~~Nothing charges automatically~~ — closed by dunning (see Current state), along
  with the missing stored payment method.
- ~~A stuck `PENDING` payment blocks its invoice, and nothing cleans it up~~ —
  closed by Payment reconciliation (see Current state). What remains is
  deliberate: a payment whose provider cannot be reached at all is still never
  resolved automatically, because freeing its slot without the provider's word
  risks a second charge. It escalates through `payments.pending.oldest.age` and
  the `PaymentStuckPending` alert, and a human clears it.
- **Nothing charges automatically.** `BillingCycleJob` invoices and renews but
  never pays, and `customer` has no stored payment method — the method comes from
  the request body. Both arrive with dunning, which needs to retry on its own.
- **No refunds, disputes, partial payments or multi-currency settlement.** The
  payment amount is always the invoice total in the invoice's currency. Refunds
  and disputes are also the point at which settlement's state-based idempotency
  stops being sufficient and a processed-event table becomes necessary.
- **Stripe's minimum charge is $0.50** (or equivalent). An invoice below it would
  be rejected by the real API; nothing validates this before calling, and
  `stripe-mock` accepts anything, so no test catches it.
- **The Stripe adapter has never run against real Stripe.** `stripe-mock` verifies
  the request shape and the response parsing, and it always succeeds — so declines,
  3-D Secure, rate limits and real webhook delivery are exercised only by the fake
  and by hand-signed payloads. Worth stating plainly rather than implying the
  integration is proven end to end.
- **No webhook endpoint for anything but payments**, and no event replay tooling:
  if the app is down for longer than the provider's retry window, those events are
  simply lost, and only the reconciliation job above would notice.

**Dunning**

- ~~A concurrent pause can be overwritten with `PAST_DUE`~~ — closed, along with
  three siblings found while fixing it. See Current state's "Subscription
  transitions as compare-and-set".
- **A customer with no stored payment method is never chased, and now never
  emailed either.** Their invoices stay `OPEN` forever, no dunning row is
  created, and `DunningService.startAttempt` returns before a notification
  could be enqueued (there is no dunning state to hang a "payment failed"
  email off in this branch). Notifications exist now, but this specific
  path still isn't wired to one; a real system would email the customer to
  add a payment method, or eventually write the invoice off.
- ~~Nobody is told anything~~ — closed by Notifications (see Current state):
  invoice-issued, payment-failed and subscription-canceled all email the
  customer now. There is still no recovery/"payment succeeded" email — see
  the Notifications gaps below.
- **One dunning policy for every tenant.** The delays and attempt count are
  application config, not per-tenant or per-plan, so a tenant selling €5/month
  cannot retry differently from one selling €5,000/month. Making it per-tenant is
  a schema change and a policy-resolution step, not a config tweak.
- **The final cancellation is immediate and unrecoverable through the API.**
  `CANCELED` has no transition back, so a customer who pays after the cycle ends
  needs a new subscription. A grace period, or a reactivation endpoint, is the
  obvious next refinement.
- **`DunningJob` scans every `OPEN` invoice per tenant on each run**, rather than
  querying only rows whose `next_attempt_at` is due. The set is bounded (an
  invoice is eventually paid or written off) and the check is cheap, but a
  tenant with many uncollected invoices does more work per tick than it needs to.
- ~~No metrics~~ — closed by Observability: `dunning.attempts.started`,
  `dunning.recoveries{attempts}`, and `audit.events` for `SUBSCRIPTION_PAST_DUE`,
  `SUBSCRIPTION_RECOVERED` and `INVOICE_UNCOLLECTIBLE`. Still missing: how many
  invoices are *currently* in dunning, which needs a gauge over `dunning_state`,
  and that would be one more cross-tenant query.

**Notifications**

- ~~No recovery email~~ — closed. `enqueuePaymentRecovered` sends the other
  half of the payment-failed email. Two things the old note underestimated:
  it needed a **migration** as well (V19 widens `notification_type_check`,
  since `notification.type` is varchar + CHECK, not a Postgres enum), and the
  trigger is not "a dunning row exists". It fires only on a real
  `PAST_DUE` -> `ACTIVE` transition, because `startAttempt` creates a dunning
  row *before* calling the provider: a first attempt that simply succeeds has
  a row and a recovery counter, yet the customer was never told anything had
  failed, and "your subscription is active again" would also be false if a
  pause or cancellation had refused the transition. Pinned in both directions
  by `DunningNotificationIntegrationTest`. The attempt count is deliberately
  kept out of the model — how often we retried their card is our business.
- **The relay is single-instance**, the same assumption `BillingCycleJob`
  and `DunningJob` already make: `NotificationRelayJob` claims `PENDING`
  rows with a plain `SELECT`, not `FOR UPDATE SKIP LOCKED`, so two
  instances would race to publish the same row. The consumer side
  (`SqsNotificationListener`) has no such limit — SQS is exactly what makes
  *that* half horizontally scalable.
- **SQS's redrive policy is a fixed visibility timeout, not exponential
  backoff.** A message that fails redelivers at the same interval every
  time until `maxReceiveCount` is reached, rather than the lengthening
  delays `DunningSchedule` uses for payment retries. Changing the
  visibility timeout per failed receive is the refinement, deliberately not
  built for three email templates' worth of traffic.
- **Nothing reads `notifications-dlq`.** A message that exhausts its
  retries lands there and stays there — no redrive tooling, no alert, no
  endpoint. `notification.delivery_attempts`/`last_error` on the row are
  the only trace, and only until someone thinks to look.
- **Deduplication can under-count a manual dunning failure.** A failed
  *manual* payment (`DunningService.onPaymentFailed`'s fresh-state branch)
  doesn't increment `attempt_count`, so its dedup key
  (`payment-failed:{invoiceId}:0`) is reused if a second manual attempt
  fails before the job ever runs — the second failure's email is silently
  skipped. Narrow and already documented at the call site; not worth a
  schema change for how rarely a manual retry repeats before the next job
  tick.
- **Text and HTML only, one language, no per-tenant branding.** The sender
  address, subject copy and template wording are all global config, the
  same simplification the rest of the platform makes (one dunning policy,
  one currency-per-invoice) until a second tenant's requirements actually
  diverge.
- **No template preview or visual regression testing.** `ThymeleafEmailRendererTest`
  checks the rendered strings (escaping, no HTML in the text part, correct
  substitutions) but nothing renders the HTML in an actual mail client —
  Mailpit's web inbox is the closest thing, and it's a manual check.

**Authentication and authorization**

- **No refresh tokens, no logout, no revocation.** A token is valid until it
  expires (1h); a stolen one cannot be recalled. Real systems pair a short access
  token with a refresh token and a revocation list, which needs server-side state
  this deliberately doesn't have.
- **No password reset, no password change, no user management at all.** A tenant
  user exists only because `db/seed` created it or tenant provisioning created a
  new tenant's first admin. Provisioning always inserts into a brand-new tenant,
  so `uk_app_user_tenant_email` is still unreachable through the API and is still
  deliberately *not* registered in `ProblemDetailsAdvice`'s constraint map.
- **No rate limiting on login.** The endpoint is public and does a bcrypt
  verification per call, so it is both brute-forceable and a cheap way to burn CPU.
  The constant-time-ish behaviour (always hashing, one error code) stops
  enumeration, not volume.
- **The signing secret is symmetric and shared.** Anything holding it can mint
  tokens, so it is exactly as sensitive as the database password. An external IdP
  with RSA + JWKS removes that, and the resource-server side is already ready for
  it.
- **`USER` is declared but not meaningfully distinct from `SUPPORT`.** Both are
  read-only. It only becomes different once an `app_user` can be linked to a
  `customer` — "read *your own* subscriptions" — and no schema exists for that
  link. Declared because CLAUDE.md §7 named it, not because it does anything.
- ~~No platform-level (cross-tenant) principal~~ — closed by Tenant provisioning
  (see Current state). ~~Who may read metrics is still undecided~~ — decided by
  Observability: a dedicated scrape account for `/actuator/prometheus`, and
  `PLATFORM_ADMIN` for the rest of `/actuator/**`.
- ~~No way to create a tenant~~ — closed by Tenant provisioning. Four options were
  weighed: self-service signup, a platform-admin API, out-of-band tooling, and a
  payment-provider webhook. A B2B billing backend points at the platform-admin API,
  since customers here are onboarded through a sales process rather than a signup
  form. **There is no admin dashboard**, deliberately: this is a JSON API, and the
  platform endpoints are what a back-office UI would call — `requests/platform.http`
  stands in for one.
- **The seeded `acme`/`demo` tenants still ship in `V1`, in every environment.**
  The seeded *logins* were moved to a profile-gated `db/seed` because their
  password is public; the tenants stayed. They carry no credentials, and all twelve
  tables referencing `tenant` cascade, so dropping them in a migration would delete
  their data wherever they had been used. A provisioning API now exists, so a clean
  deployment no longer *needs* them — they could be deactivated through it rather
  than deleted.

**Audit events**

- **Isolation is explicit only.** `audit_event` has no `@TenantId` backstop (see
  Current state), so a future repository method that forgets `tenantId` would
  leak across tenants with nothing to catch it. This is the same trade
  `AppUserEntity` makes, and the reason the port has only three methods.
- **Nothing makes it tamper-evident.** Append-only is a property of the
  application code, not the database: anyone with SQL access can update or
  delete a row. A real audit log would revoke `UPDATE`/`DELETE` from the
  application role, or hash-chain rows, or ship them to write-once storage.
- **It is deleted with its tenant.** `audit_event.tenant_id` still has V1's
  `ON DELETE CASCADE`. Tenants are never deleted today (deactivation is the only
  lifecycle), but the day deletion exists, the one record of what happened goes
  with it. Retention also stays unbounded.
- **Failed logins and authorization denials are not recorded**, deliberately:
  they belong to metrics. Failed logins are now `auth.login.failures{principal}`
  with a `LoginFailureSpike` alert. Denials have no dedicated meter; they show only
  as 401/403 in `http_server_requests`, which Boot's observation filter records
  ahead of the security chain.
- ~~Two concurrent deactivations can both record~~ — closed by a conditional
  UPDATE that reports whether it changed the row (see Current state's Customer
  updates for why not `@Version`). Every caller still gets 200, and exactly one
  event is recorded.
- **Ordering is by `created_at` only.** Two events in one transaction can share a
  timestamp at the clock's resolution, and then their relative order in a
  response is unspecified. No read depends on it today.
- ~~A bad `entityType` value is a 500~~ — closed, and it was never audit-specific.
  Every unconvertible query parameter or path variable (`?customerId=not-a-uuid`,
  `/api/subscriptions/not-a-uuid`) and every unknown `?sort=` property was a 500.
  `ProblemDetailsAdvice` now maps `MethodArgumentTypeMismatchException` and
  `PropertyReferenceException` to 400 `VALIDATION_ERROR`, and
  `InvalidRequestParameterIntegrationTest` covers them. The response names the
  parameter and the expected shape (an enum's allowed values, "must be a UUID")
  but never echoes the rejected value, and the sort error never names the entity
  class.
- ~~The rest of that category was still a 500~~ — closed. The
  `@ExceptionHandler(Exception.class)` catch-all runs before Spring's
  `DefaultHandlerExceptionResolver`, so Spring MVC's own client errors all became
  500 `INTERNAL_ERROR`. `ProblemDetailsAdvice` now maps each one explicitly:

  | Exception | Status | `code` |
  |---|---|---|
  | `HttpMessageNotReadableException` (not JSON, missing body, wrong field type) | 400 | `MALFORMED_REQUEST_BODY` |
  | `HttpRequestMethodNotSupportedException` | 405, with `Allow` | `METHOD_NOT_ALLOWED` |
  | `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` |
  | `NoResourceFoundException` (unknown URL) | 404 | `ENDPOINT_NOT_FOUND` |
  | `MissingServletRequestParameterException` (latent) | 400 | `VALIDATION_ERROR` |

  `MalformedRequestIntegrationTest` covers the first four through the real request
  path; `ProblemDetailsAdviceTest` covers the latent one, since no endpoint has a
  required query parameter.
  - **Not `extends ResponseEntityExceptionHandler`.** It would cover more, but it
    already handles the validation, header and type-mismatch exceptions mapped
    here, so the two would clash at startup unless those handlers were rewritten
    as overrides.
  - **A body error is not `VALIDATION_ERROR`:** validation never ran, because
    there was no object to validate. A wrong-typed field is named by its JSON path
    (`amountCents has an invalid value`). Jackson's own message, which quotes the
    value and names the DTO class, is never returned.
  - **`ENDPOINT_NOT_FOUND` is not `<TYPE>_NOT_FOUND`:** "no such endpoint" and "no
    such record" call for different reactions from a client. Only an
    authenticated caller can get it; an anonymous one still gets 401, so 404
    cannot be used to map which endpoints exist.
  - **Still unmapped:** anything else Spring MVC raises before dispatch, e.g. 406
    for an `Accept` header nothing can produce. Nothing here exercises it.

**Observability**

- **No tracing.** The `requestId` correlates log lines within one process, but
  nothing follows a notification across the SQS hop, or a Stripe payment from the
  API call to its webhook. Micrometer Tracing with OTel is the next step if that
  matters, and the queue hop is where it would earn its keep.
- **No Alertmanager.** Rules evaluate and show as firing at
  `localhost:9090/alerts`, but nothing notifies anyone.
- **A job that has never run has no staleness series.** `jobs.last.success` is
  registered on the first successful run, so for up to an hour after a restart
  (the hourly jobs) the staleness alerts cannot fire, and a job that never runs
  after startup is invisible. An `absent()` rule would cover it, at the cost of a
  false alarm on every restart.
- **Counters reset on restart and are per instance.** Fine for rates and
  `increase()`, which handle resets, but not a ledger. The database is the record
  of how many invoices exist; the metrics say how fast things are happening.
- **The outbox gauge runs two aggregate queries per scrape, and neither has an
  index built for it.** `idx_notification_tenant_status_created` leads with
  `tenant_id`, and these queries filter only on status, so Postgres cannot seek on
  it (not checked with `EXPLAIN`). That is fine at this size and grows with the
  table, since sent rows are never deleted. A partial index on `created_at WHERE
  status IN ('PENDING','PUBLISHED')` stays small, because it only ever holds rows
  still waiting. A scrape during a database outage reports NaN rather than a stale
  value.
- **Nothing tests the dashboard JSON or the alert expressions.** The metric names
  they query are asserted by `BusinessMetricsIntegrationTest`, and both were
  checked against a live Prometheus once, but a PromQL typo in a panel would only
  show as an empty graph. `promtool check rules` in CI is the cheap fix.
- **Local credentials are defaults.** Grafana is `admin`/`admin` and the scrape
  password is `.env.example`'s, which is fine on a laptop and must change anywhere
  reachable.
- **No per-tenant view of anything in Prometheus**, by design (cardinality).
  Questions like "which tenant's payments are failing" go to the logs or the
  database.

**Tenant provisioning**

- **The initial password is never forced to change.** There is no password-change
  endpoint, so the generated password stays the admin's password. Real systems
  mark it as one-time and require a change on first login.
- **Losing the initial password has no route back.** It is shown once and there is
  no reset; an operator would have to reset the hash by hand.
- **No platform-user management.** Platform admins exist through the seed or the
  bootstrap variables only. There is no endpoint to add a second one, disable one,
  or rotate a password, and the bootstrap deliberately never touches an existing
  row.
- **No tenant update or delete.** A tenant's name cannot be changed after
  creation, and deletion is intentionally absent (cascades would destroy
  financial records); deactivation is the only lifecycle operation.
- **Deactivation catches up in a burst.** A long suspension leaves several periods
  due. Renewal advances one period per `BillingCycleJob` run (hourly), so the
  tenant's customers receive one invoice, one charge and one email per hour until
  their subscriptions are current, rather than one consolidated catch-up invoice.
- **Held emails can be stale.** Bodies are rendered at enqueue time, so a
  payment-failed email held through a suspension still says "we'll try again on"
  a date that has passed by the time it is sent. Re-rendering at delivery, or
  expiring time-sensitive notification types, is the refinement.
- **Missed periods cannot be waived.** The policy is to bill them, and deciding
  otherwise is the tenant's call per customer, but that needs a `VOID` invoice
  transition, which is still unreachable.
- ~~Provisioning is not audited~~ — closed by Audit events.
- **Swagger UI and `/v3/api-docs` are public.** Convenient locally, and it exposes
  the full API shape to anyone who can reach the service. Fine for a portfolio, not
  for a real deployment.
