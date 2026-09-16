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
    `SubscriptionRenewalService.applyRenewal`'s split from load/save) rounds
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
    `SubscriptionRenewalService.applyRenewal` advances a subscription, the
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
    path. `InvoicePdfService.generatePdf` does hold a transaction across the
    upload, which is the same shape — the difference is that there the upload
    was an unrelated side effect, whereas here storing the bytes *is* the
    operation, and the render/upload happen before the entity is mutated so a
    failure rolls back a transaction that wrote nothing.
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
    with `open-in-view`, the request's Hibernate session opens before the tenant is
    knowable (it is inside the signed body), pinning every query to the
    `__no_tenant__` sentinel. `PROPAGATION_REQUIRES_NEW` does not fix it — with no
    active transaction there is nothing to suspend, so the new transaction adopts
    the bound `EntityManager`. `PaymentWebhookService` unbinds it, sets the tenant,
    settles in a fresh session, and rebinds. Caught by a test, not by reading.
  - **No Stripe account exists**, and none is needed: Stripe supports neither
    Kosovo nor Albania, and the signup country is permanent and must match a real
    entity there. `StripePaymentGatewayTest` runs the real SDK against
    `stripe-mock` in a container (a plain JUnit test, no Spring context, so it
    neither forks the context cache nor needs the Stripe profile), and
    `StripeWebhookIntegrationTest` signs its own payloads with a test
    `whsec_` secret — verification is HMAC, so the production path runs unchanged.
    This is why the Stripe adapter has CI coverage that a real-account integration
    could never have.
- **Architecture tests** — `architecture/ArchitectureTest` (ArchUnit,
  `com.tngtech.archunit:archunit-junit5`) turns CLAUDE.md §3/§5's layering and naming
  rules into executable checks: no `..domain..`/`..api..` dependency on
  `..infra..` beyond the `*Entity` carve-out below, `common` never depends on
  a feature module, Spring Data types stay behind `..infra.jpa..`, the domain
  stays free of `jakarta.persistence`, Thymeleaf/openhtmltopdf/the AWS SDK
  each stay inside their one adapter package (the Stripe SDK too, confined to
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
GET  /api/health                       (public)
POST GET      /api/products
GET            /api/products/{code}
POST GET      /api/plans
GET            /api/plans/{code}
POST GET      /api/plans/{planCode}/entitlements
POST GET      /api/customers
GET            /api/customers/{id}
POST GET      /api/subscriptions[?customerId=]
GET            /api/subscriptions/{id}
POST GET      /api/subscriptions/{subscriptionId}/usage
POST GET      /api/subscriptions/{subscriptionId}/invoices
GET            /api/invoices[?subscriptionId=]
GET            /api/invoices/{id}
POST GET      /api/invoices/{id}/pdf
POST GET      /api/invoices/{id}/payments   (POST needs an Idempotency-Key header)
GET            /api/payments/{id}
POST          /api/webhooks/stripe          (public; authenticated by signature)
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
- Still no update or delete anywhere.
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
- `audit_event` table exists but nothing writes to it. No longer *blocked*,
  though: it needed an authenticated principal to record in its `actor` column,
  and there now is one — which is why CLAUDE.md §7 moved it after authentication.
- ~~Only `InvoiceStatus.OPEN` is ever set~~ — partly closed: payments produce
  `PAID`. `DRAFT`, `VOID` and `UNCOLLECTIBLE` remain declared and unreachable,
  still deliberately: `VOID` needs a cancellation path and `UNCOLLECTIBLE`
  belongs to dunning.
- No update or delete on invoices either, same as everywhere else — an
  invoice is additionally meant to be immutable once issued (see Current state), so
  "no update" here is a stronger property than the same gap on Product/
  Plan/Customer/Subscription, not just an unaddressed one.

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

- **A stuck `PENDING` payment blocks its invoice, and nothing cleans it up.** If
  the provider is unreachable (or the app dies between the reserve and the
  provider call), the payment stays `PENDING` and the partial unique index refuses
  every other attempt on that invoice. Retrying with the same `Idempotency-Key`
  resumes it — that is why the header is mandatory — but a caller who loses the key
  has no route back, and there is no reconciliation job that asks the provider what
  happened to old pending payments. That job is the honest next step for this
  module, and the first thing a real system would add.
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

**Authentication and authorization**

- **No refresh tokens, no logout, no revocation.** A token is valid until it
  expires (1h); a stolen one cannot be recalled. Real systems pair a short access
  token with a refresh token and a revocation list, which needs server-side state
  this deliberately doesn't have.
- **No password reset, no password change, no user management at all.** Users only
  exist because `db/seed` creates them. There is no endpoint to create one, so the
  `uk_app_user_tenant_email` constraint is unreachable through the API and is
  deliberately *not* registered in `ProblemDetailsAdvice`'s constraint map — adding
  it would be dead code until a user-management endpoint exists.
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
- **No platform-level (cross-tenant) principal.** Every token is bound to a
  tenant, so "who may read `/actuator/metrics`?" has no clean answer — any tenant's
  ADMIN would technically qualify. Non-health actuator endpoints stay unexposed to
  avoid the question; Observability will force it, since Prometheus has to scrape
  `/actuator/prometheus`.
- **No way to create a tenant.** `TenantRepository` has only `findActiveById` and
  `findAllActive` — no `save`, no controller, no CLI. A tenant exists only because
  `db/seed` or manual SQL made it. Four options were weighed: self-service signup,
  a platform-admin API, out-of-band tooling, and a payment-provider webhook. A B2B
  billing backend points at the platform-admin API, since customers here are
  onboarded through a sales process rather than a signup form (CLAUDE.md §7).
- **The seeded `acme`/`demo` tenants still ship in `V1`, in every environment.**
  The seeded *logins* were moved to a profile-gated `db/seed` because their
  password is public; the tenants stayed, for two reasons. They carry no
  credentials, and deleting them would be worse than leaving them: all twelve
  tables referencing `tenant` cascade, so dropping `acme` would take every
  customer, product, subscription and invoice with it — and with no provisioning
  API, a deployment stripped of them would have no tenants and no way to get one.
- **Swagger UI and `/v3/api-docs` are public.** Convenient locally, and it exposes
  the full API shape to anyone who can reach the service. Fine for a portfolio, not
  for a real deployment.
