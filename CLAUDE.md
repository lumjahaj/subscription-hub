# Subscription Hub — Project Context

Multi-tenant billing and subscription management backend for SaaS applications.
Built as a **portfolio project** to demonstrate real-world backend engineering,
and to be explained confidently in a Senior Java/Spring Boot interview.

Your role: **senior Java/Spring Boot engineer, architect, and mentor.** Build
incrementally, explain important decisions, catch mistakes, push back when
something is wrong.

---

## 1. Non-negotiables

Read these before suggesting anything.

1. **Don't redesign what exists.** Understand the current architecture and stage
   first. Don't rewrite unrelated parts of the project.
2. **Build incrementally.** One feature at a time, committed separately. Never
   dump the whole application at once.
3. **Don't overengineer.** This is a modular monolith. No microservices, no
   event-driven everything, no interface-per-class, no patterns for their own
   sake. Only add complexity that demonstrates a useful real-world concept.
4. **Tenant isolation is critical.** Every tenant-owned operation is scoped by
   the current tenant. Never trust a tenant ID from a request body — the tenant
   context is the source of truth.
5. **State assumptions explicitly.** If you're guessing at a field name, entity
   shape, or method signature, say so rather than presenting it as fact.
6. **Explain decisions.** Why we need it, what problem it solves, alternatives,
   why this choice. Practical, not academic. This project is interview prep.
7. Before proposing any new module, refactor, or schema change, load the
   subscription-hub-state skill. Many apparent gaps are deliberate and already
   argued there.

---

## 2. Stack

Java 21 · Spring Boot 3.5.6 · Maven · Spring Web / Data JPA / Validation /
Security · Hibernate 6 · PostgreSQL 17 · Flyway · Testcontainers · Docker Compose ·
springdoc-openapi 2.8.x · MinIO (invoice PDFs) · AWS SDK v2 for S3 ·
openhtmltopdf + Thymeleaf · stripe-java (payments) · Spring Cloud AWS SQS +
ElasticMQ (notifications) · spring-boot-starter-mail + Mailpit (email)

The object store is reached with the **AWS SDK, not the MinIO client** — MinIO is
S3-compatible, so the vendor stays a config value and the same adapter works
against real S3/R2 by changing an endpoint.

Thymeleaf is present as a **library only** (`org.thymeleaf:thymeleaf`), never
`spring-boot-starter-thymeleaf`: it renders the invoice HTML that openhtmltopdf
turns into a PDF, and — the same library, a second private `TemplateEngine` —
the HTML/text notification emails. This is a pure JSON API with no MVC view
layer, and the starter would wire one in.

Notification delivery is a transactional outbox relayed to SQS: a billing or
dunning change and the outbox row it produces commit together (you cannot
commit a database row and publish to a queue in one atomic step — the
dual-write problem), and a relay job moves `PENDING` rows onto the queue for a
listener to send. SQS is reached the same way as the object store: **ElasticMQ
stands in for it locally and in tests**, and only the endpoint differs in
production. SMTP is the same shape again — **Mailpit locally, Amazon SES's SMTP
interface in production.**

The payment provider is a config value the same way: `payment.provider=fake`
(the default — in-process, deterministic, no network) or `stripe`. Both sit behind
`PaymentGateway`, and both settle through one `PaymentEvent` path, so the fake is
not a stub but the same shape as the real thing. **No Stripe account exists or is
needed**: Stripe supports neither Kosovo nor Albania, so the adapter is tested
against `stripe-mock` (Stripe's own local server, as a container) and the webhook
against payloads the tests sign themselves.

Authentication is **self-issued HS256 JWTs**, validated by
`spring-boot-starter-oauth2-resource-server` (which had been on the classpath
unused since the beginning). Symmetric signing because issuer and validator are the
same process; an external IdP would use RSA + JWKS and change only `issuer-uri`,
since the verification side is already the standard machinery — the same
"code against the standard, keep the provider a config value" shape as MinIO/S3.

Planned: Redis, WireMock, Micrometer, Prometheus, Grafana.

**Free, and runnable from a clean clone.** Nothing requires a paid service. With
only Docker, the app boots and the full test suite passes offline, with no
accounts or secrets. External providers (e.g. Stripe test mode) are opt-in
adapters chosen by config; the default is a local fake, and tests never call a
real provider. This replaced "free and locally runnable": the parts worth
protecting are cost, a stranger's first run, and hermetic tests — not whether a
developer's own manual run may reach the internet.

Dev environment: Windows, IntelliJ IDEA, Docker Desktop. API testing via
IntelliJ REST Client `.http` files in `requests/`.

---

## 3. Architecture

Base package: `dev.lumjahaj.subscription.hub`

Feature-based modules, each with the same internal layering:

```
common/          api, logging, web — cross-cutting, depends on nothing
auth/            api, app, domain, infra/jpa
tenancy/         api, domain, infra/jpa
catalog/         api, app, domain, infra/jpa
customer/        api, app, domain, infra/jpa
subscription/    api, app, domain, infra/jpa
usage/           api, app, domain, infra/jpa
billing/         api, app, domain, infra/{jpa, pdf, storage}
payment/         api, app, domain, infra/{jpa, gateway}
dunning/         app, domain, infra/jpa  (no api — it is a job, not endpoints)
notification/    app, domain, infra/{jpa, sqs, mail, template}  (no api — an
                 outbox relay and a queue consumer, not endpoints)
platform/        api, app  (no domain or infra — it orchestrates tenancy and
                 auth; exists because tenancy → auth would close a cycle)
audit/           api, app, domain, infra/jpa
```

`billing` was the first module with more than one `infra` package; `notification`
now has the most. `infra/jpa` adapts the database, `infra/storage` adapts the
object store, `infra/pdf` adapts the rendering library (billing), and
`infra/sqs`, `infra/mail`, `infra/template` adapt the queue, SMTP and the
second Thymeleaf renderer (notification) — each outside system behind its own
port, rather than one catch-all `infra`.

Dependency direction: **`api → app → domain ← infra`**

- `api` — controllers, DTOs, mappers, module-specific exceptions
- `app` — application/use-case services, business rules
- `domain` — domain models and repository ports (interfaces)
- `infra/jpa` — JPA entities, Spring Data repositories, port adapters

`common` must never depend on a feature module. Feature modules may depend on
`common` and on each other's public pieces (e.g. `SubscriptionService` uses
`CustomerRepository` and `PlanRepository`).

### Repository trio (mandatory pattern)

```
XxxRepository        domain port — the contract the app layer depends on
XxxRepositoryImpl    infra adapter — @Repository, delegates to Spring Data
XxxJpaRepository     Spring Data — extends JpaRepository
```

Never collapse these (e.g. `XxxJpaRepository extends JpaRepository, XxxRepository`).
The split exists so Spring Data's query-naming rules and `JpaRepository`'s large
surface area don't leak into the domain contract, and so there's a seam for
non-pass-through logic later.

Never use the names `JpaXxxRepository` or mix conventions.

### Domain models: only when earned

`tenancy` has a real domain model (`Tenant`) because `TenantResolverFilter`
branches on `tenant.active()` — business logic reasoning about the concept
independent of persistence.

`catalog`, `customer`, and `subscription` have **no** separate domain model —
their entities are still just data moving DTO ↔ database. That's a deliberate,
revisitable decision, not an oversight. Extract a domain model when something
needs to reason about the concept without touching JPA. Don't add one for
symmetry.

---

## 4. Multi-tenancy

Single database, single schema, **row-level isolation** via a `tenant_id` column
on every tenant-owned table.

- Tenant comes from the **`tenant_id` claim of a verified JWT** (a human-readable
  slug like `acme`, **not** a UUID — that's why `tenantId` is `String` everywhere).
  It was an `X-Tenant-Id` header until authentication landed; that header is gone,
  because a tenant the caller types is a tenant the caller chooses.
- `TenantResolverFilter` reads the claim off `SecurityContextHolder`, re-checks the
  tenant is active, and populates `TenantContext` (static ThreadLocal) plus MDC.
  Cleans up in `finally`.
- **It runs inside the security chain, registered
  `addFilterAfter(..., AuthorizationFilter.class)`** — not at `HIGHEST_PRECEDENCE`
  as it once did. It has to see an authenticated principal, and it must run after
  authorization: `BearerTokenAuthenticationFilter` only authenticates *when a token
  is present*, so placing it between the two meant unauthenticated requests were
  rejected as 400 `TENANT_MISSING` before Security could answer 401.
- It is **not a `@Component`**, and that is load-bearing: Boot auto-registers every
  `Filter` bean into the servlet chain, so a bean would also run early, outside
  security, with no principal. `SecurityConfig` constructs it directly.
- Filter skips what `SecurityConfig` permits: `/api/auth`, `/api/health`,
  `/api/webhooks`, `/actuator`, `/swagger-ui`, `/v3/api-docs` — plus
  `/api/platform`, whose tokens name no tenant. Being inside the security chain is
  not the same as running only on authenticated requests — a `permitAll` path still
  passes through every filter, just with an empty context.
- **Two principals, and neither stands in for the other.** A tenant token carries
  `tenant_id`; a platform token (`POST /api/platform/auth/token`, table
  `platform_user`) carries `roles=["PLATFORM_ADMIN"]` and *no* `tenant_id`.
  `SecurityConfig` requires `PLATFORM_ADMIN` on `/api/platform/**` and a
  `tenant_id` claim on everything else (`anyRequest().access(hasTenantClaim())`),
  so each gets 403 on the other's endpoints. The tenant rule is an allow-list on
  the claim, not a deny-list of `PLATFORM_ADMIN`, so a future third kind of token
  is refused by default. Anonymous callers still get 401: a denial for an anonymous
  principal is turned into the entry point's 401, not 403.
- **A platform request has an empty `TenantContext`**, so with open-in-view its
  session is pinned to the `__no_tenant__` sentinel. Harmless today — `tenant`,
  `app_user` and `platform_user` are not `TenantScoped` — but a platform endpoint
  that reads a `TenantScoped` entity silently gets nothing, and needs the
  `PaymentWebhookService` session treatment.
- Exceptions: `MissingTenantException` → 400 `TENANT_MISSING` now means *a valid
  token carrying no `tenant_id`*, i.e. one minted by something other than
  `AuthService`; `UnknownTenantException` → 401 `TENANT_UNKNOWN` means the tenant
  was deactivated after the token was issued.
- App services read the tenant via `TenantContext.getTenantId()` directly.
- `RequestIdFilter` (`common/web`) keeps the MDC request id and stays at
  `HIGHEST_PRECEDENCE`, outside the security chain — tenant resolution now happens
  late, but a request Security rejects never reaches it, and those responses still
  need a correlation id.

**Every repository query must still be tenant-scoped explicitly.**
`findByTenantIdAndCode(...)`, never `findByCode(...)`. This is no longer the
*only* thing enforcing isolation — see Known gaps and improvements in the subscription-hub-state skill — but it stays mandatory as defense
in depth: the structural backstop only covers Hibernate-mediated queries, not
a native/`nativeQuery = true` one. There are now two of those —
`UsageCounterJpaRepository.upsertAndIncrement` and
`InvoiceJpaRepository.allocateNextNumber` (see Current state in the subscription-hub-state skill) — and `tenantId` is bound
explicitly in both for exactly this reason.

**A provider webhook has the same chicken-and-egg, from the other side.**
`POST /api/webhooks/stripe` carries no token, so `TenantContext` is empty when
`spring.jpa.open-in-view` opens the request's `EntityManager`, and Hibernate
resolves `@TenantId` *at session open* — pinning every query to the
`__no_tenant__` sentinel. The tenant is only knowable after the signature
verifies, from the event's metadata. `PaymentWebhookService` therefore unbinds
the request's `EntityManager`, sets the tenant, and runs settlement in a
transaction that opens a fresh session, rebinding afterwards.
`PROPAGATION_REQUIRES_NEW` looks like the fix and is not: suspension only happens
when a transaction is already active, and open-in-view binds an `EntityManager`
without one, so the new transaction simply adopts it — sentinel tenant included.

**`AppUserEntity` is the one tenant-owned entity that does not extend
`TenantScoped`**, so it gets no `@TenantId` predicate. This is a chicken-and-egg,
not an oversight: `@TenantId` resolves from `TenantContext` when the Hibernate
session opens, and with `spring.jpa.open-in-view` that is the *start of the
request* — before anything could know the tenant. Reading this table is what
establishes it. Extending `TenantScoped` produced a self-contradicting
`where tenant_id = '__no_tenant__' and tenant_id = 'acme'` and failed every login
with correct credentials. Scoping stays explicit via `findByTenantIdAndEmail`, and
the issued token takes its tenant from the row found rather than from the request.

**`AuditEventEntity` is the second, for a different reason.** Platform
administrators record events under the tenant they act on (provision,
deactivate), from a request with no `TenantContext`, so the session is pinned to
`__no_tenant__` and `@TenantId` would reject the insert. The event has to commit
in the same transaction as the tenant change, which rules out the
`PaymentWebhookService` fresh-session approach. The row is append-only as well,
so `updated_at` would mean nothing. Every `AuditEventRepository` method takes the
tenant, and `AuditIntegrationTest` proves one tenant cannot read another's
events.

---

## 5. Conventions

**DTOs** — records in `<module>/api/dto/`. `XxxCreateRequest` / `XxxResponse`.
Never expose JPA entities. `tenantId` never appears in a request or response
body. Parent references use the parent's `code` (`productCode`, `planCode`) —
except `Customer`, which has no code, so subscriptions reference `customerId`
(UUID).

**Validation** — Bean Validation on request DTOs, `@Valid` on controller
parameters. DB constraints are the real integrity guarantee; annotations are the
clean-error layer.

**Mappers** — manual, no MapStruct. Static utility class with a private
constructor by default (`ProductMapper`, `PlanMapper`, `CustomerMapper`,
`SubscriptionMapper`). Becomes a `@Component` only when it needs a real
dependency — `PlanEntitlementMapper` is a bean because it needs `ObjectMapper`.
Mappers never touch repositories: resolving `productCode → ProductEntity` is the
service's job. `SubscriptionMapper` has no `toEntity()` at all, because a
subscription's fields are computed, not copied.

**Errors** — RFC 7807 `application/problem+json` via `ProblemDetailsAdvice` in
`common/api`, with `code` and `requestId` (from MDC) properties on every
response. Generic reusable exceptions in `common/api`:
`ResourceNotFoundException` (404) and `ResourceAlreadyExistsException` (409),
constructed as `new ResourceNotFoundException("Plan", planCode)`. Don't create
per-entity exception classes for these shapes. **Do** create distinct exception
types for genuine business-rule violations (invalid state transitions, etc.),
where the difference carries real information.

**Controllers** — `@RestController`, constructor injection, `201 CREATED` on
create, `Pageable` parameter on top-level list endpoints, map `Page<Entity>` →
`PagedResponse<Response>` via `PagedResponse.from(page, Mapper::toResponse)` —
never return a raw `Page<T>`, whose `PageImpl` serialization isn't a documented
contract. Nested per-parent collections (`/api/plans/{planCode}/entitlements`,
`/api/subscriptions/{subscriptionId}/usage`) return a plain `List<Response>`.

**Updates** — the pattern `CustomerController`/`CustomerService.update` set:
- **Shape:** `PUT` with a full-replacement `XxxUpdateRequest` record, not PATCH.
  A record cannot tell an absent field from an explicit null.
- **Entity:** `@Version Long` backed by `bigint NOT NULL DEFAULT 0`. A null version
  makes Spring Data's `save` treat an existing row as new and persist it.
- **Every single-resource response** carries `ETag` (`ETags.of(version)`), and the
  update takes `If-Match` through `ETags.requireIfMatch`: missing or `*` is 428,
  not one of our ETags is 412.
- **The service compares versions explicitly** (412), and `@Version` catches the
  race after that check (409 `CONCURRENT_MODIFICATION`). Setting the version on a
  managed entity does not work: Hibernate checks the version it loaded.
- **Write and audit only real changes.** A no-op PUT must not bump the version, or
  it invalidates every other client's ETag for nothing. The audit event names the
  changed fields, never their values.
- **Idempotent state commands** (activate/deactivate, and anything like them) use a
  conditional `UPDATE ... WHERE <state differs>` and act on the row count, not
  `@Version`: a caller whose intent is already satisfied gets the no-op, not a
  conflict.

**Money** — always integer minor units (`amountCents`, `long`). Never floating
point.

**Hibernate 6 + Postgres native types** — this has bitten twice:
- `jsonb` column mapped to a `String` field needs `@JdbcTypeCode(SqlTypes.JSON)`
- native enum type (`subscription_status`) needs
  `@Enumerated(EnumType.STRING)` + `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` +
  `columnDefinition`

Without these, Hibernate binds as `varchar` and Postgres rejects the statement.
The same applies to `invoice_status` (`InvoiceEntity.status`). The inverse trap
bit once Billing was built: `invoice_line.kind` is `varchar(16)`, not a
Postgres enum, so `InvoiceLineEntity.kind` deliberately gets plain
`@Enumerated(EnumType.STRING)` — the NAMED_ENUM combo there would bind a
nonexistent type and fail at startup. Same category of bug, opposite fix;
check the column's actual Postgres type before reaching for the combo.

**Spring Data `save()` on an assigned id merges.** `SimpleJpaRepository.save`
persists only when the entity looks new, which for a non-generated id means "id
is null" — never true for a slug like `tenant.id`. So `save` merges: it loads
the existing row and overwrites it. A create that races past its existence
check then silently replaces the other tenant instead of failing with 409.
`TenantRepositoryImpl.create` therefore calls `EntityManager.persist` and
flushes, so a duplicate fails on the primary key (`tenant_pkey`, mapped in
`ProblemDetailsAdvice`). Generated UUID ids don't have this problem.

**Integration tests + Testcontainers** — this has bitten once, silently, for
three commits:
- **Never** put `@Testcontainers` / `@Container` on `AbstractIntegrationTest`.
  That extension's lifecycle is *per test class* — it stops the container after
  every subclass — while Spring's context cache builds the context once and
  keeps handing out the `DataSource` holding the first container's JDBC URL.
  The second DB-touching test class to run then talks to a dead port.
- The rule underneath: **the container must outlive every Spring context that
  points at it.** Use the singleton pattern — static field, `@ServiceConnection`,
  `static { POSTGRES.start(); }`, no JUnit extension. Ryuk reaps it on JVM exit.
- **Invariant: exactly one container per build.**
  `./mvnw -B verify | grep -c "Container is started (JDBC URL"` must print `1`.
  It printed `3` for three commits and nobody looked.
  That grep matches a **JDBC-specific** log line, so it counts Postgres
  containers only. `AbstractIntegrationTest` also starts a MinIO container for
  invoice PDFs, which never emits that line — two containers per build is
  expected, and the check still means "one Postgres". Don't "fix" the grep to
  match both and then panic at `2`.
- Property overrides (`@TestPropertySource`, `@DynamicPropertySource`) go on
  `AbstractIntegrationTest`, **never** a subclass — each distinct set of
  properties is a separate Spring context-cache key, so a subclass-level
  override silently builds a second context, and with it a second set of
  containers.
- Verify with a **full `./mvnw verify`**, never a single class. A per-class run
  structurally cannot catch a cross-class lifecycle bug — the JVM exits first.
  Surefire's default `runOrder` is `filesystem`, which differs between Windows
  and Linux, so such a bug fails in a *different class* locally than on CI, and
  a green single-class run proves nothing.
- **Pin every container image, and treat "works locally" as no evidence.** A
  developer machine runs from its local image cache, so an image that has been
  retagged, moved or deleted upstream keeps working locally and fails only on CI,
  which always pulls. That is exactly what happened: MinIO's `minio/minio`
  repository disappeared from Docker Hub, CI died with "repository does not
  exist", and every local build stayed green. Images now come from
  `quay.io/minio/minio` with a pinned `RELEASE.*` tag, and `stripe-mock` is
  pinned too; `:latest` anywhere is the same bug waiting. `docker-compose.yml`
  needs the same treatment as the test containers — a fresh clone pulls both.
- Setup helpers assert the response status before calling `.getBody()`.
  Otherwise a problem+json body deserializes into the response record and
  surfaces as a confusing Jackson error (ProblemDetail's numeric `status` vs.
  an enum field) hundreds of log lines from the real cause.

**Spring Cloud AWS SQS + Java records** — this has bitten once, and only showed
up outside the test suite:
- `SqsTemplate.send(to -> to.queue(name).payload(record))` does not reliably
  serialize a Java record to JSON. Against a real ElasticMQ container, the
  record's own `toString()` landed on the queue verbatim
  (`NotificationMessage[tenantId=acme, notificationId=...]`), and
  `@SqsListener` then failed every delivery with a Jackson parse error
  ("Unrecognized token 'NotificationMessage'") — which is a real, reported
  upstream issue, not a local misconfiguration.
- **The automated integration test did not catch it.** `NotificationIntegrationTest`
  passed against the same real ElasticMQ container both before and after the
  fix, which means whatever made the conversion misbehave was not
  reproduced by that test run — a genuine gap between the test suite and a
  live `spring-boot:run`, the same category of surprise Testcontainers
  lifecycle and the pinned-image rules exist to catch, just from a
  different angle. It was only found by actually running the app
  (`docker compose up`, `spring-boot:run -Dspring-boot.run.profiles=dev`)
  and generating a real invoice — the CLAUDE.md §8 instinct to verify a
  feature live, not just in tests, is what surfaced it.
- **Fix: serialize by hand on both sides**, rather than trust the
  framework's automatic type-based conversion for a record payload.
  `SqsNotificationPublisher` calls `objectMapper.writeValueAsString(message)`
  and sends that `String` (which `StringMessageConverter` then passes
  through unchanged); `SqsNotificationListener`'s `@SqsListener` method
  takes a `String` parameter and calls `objectMapper.readValue(body, ...)`
  itself. Symmetric on both ends, and it removes the ambiguity entirely
  rather than searching for the right converter configuration.

**Servlet filters + Spring Security** — this has bitten three times in one
commit:
- Spring Boot **auto-registers every `Filter` bean** into the servlet chain. A
  filter that is both a bean and added to the security chain runs *twice* — once
  early, outside security, with no principal. Construct it directly in
  `SecurityConfig` (or disable the automatic registration with a
  `FilterRegistrationBean.setEnabled(false)`).
- **`addFilterAfter(BearerTokenAuthenticationFilter.class)` is not "after
  authentication".** That filter only authenticates *when a token is present*;
  enforcing `authenticated()` happens later, in `AuthorizationFilter`. A filter
  between the two sees unauthenticated requests and can reject them with the wrong
  status before Security answers 401. Use `AuthorizationFilter.class`.
- **Being in the security chain ≠ running only on authenticated requests.** A
  `permitAll` path still passes through every filter, just with an empty
  `SecurityContext`. Keep `shouldNotFilter` in step with the `permitAll` matchers,
  or login rejects itself.

**Security errors must stay problem+json.** Spring Security rejects inside the
filter chain, before dispatch, so `ProblemDetailsAdvice` never sees those — the
defaults are an empty 401 body and an HTML 403 page. `SecurityProblemHandler`
renders both as problem+json with `code` and `requestId`. Conversely
`@PreAuthorize` throws `AccessDeniedException` *during* dispatch, where
`@ControllerAdvice` gets first refusal — so `ProblemDetailsAdvice` needs an
explicit handler for it, or the catch-all `@ExceptionHandler(Exception.class)`
reports a forbidden request as 500 and the endpoint looks broken rather than
protected.

**Billing invariants** — each looks like a harmless cleanup and silently costs
revenue or tenant isolation. The full reasoning is under Current state in the
subscription-hub-state skill.
- **Invoice before renew, per subscription, in `BillingCycleJob`.** Renewal
  overwrites `currentPeriodStart`, so a period renewed before it is invoiced can
  never be billed. Only an "already exists" invoicing failure may proceed to
  renewal; any other failure leaves the subscription due. Never split the two
  onto separate schedules.
- **`nextRenewal == currentPeriodEnd` at every write site** (`SubscriptionService`
  create, both branches; `SubscriptionRenewalService.applyRenewal`). The job reuses
  the renewal finder on the strength of it. If a change makes them diverge,
  billing needs its own finder.
- **PDF generation stays out of the billing transaction.** No remote call inside
  `InvoiceService.generateForCurrentPeriod`, and a PDF failure in the job never
  blocks renewal — a missing PDF is recoverable via `POST /api/invoices/{id}/pdf`,
  a missed invoice is not.
- **Invoice PDFs stream through the API, never a presigned URL.** Downloads must
  stay inside the tenant-scoped request path; object keys are storage layout, not
  a security boundary, and never appear in a response.

**Payment invariants** — money moves here, and each of these looks like tidying.
Full reasoning under Current state in the subscription-hub-state skill.
- **One place settles money.** `PaymentSettlementService` is the only code that
  moves a payment off `PENDING` or an invoice to `PAID`, and it only ever acts on
  a `PaymentEvent`. Both providers reach it the same way; a second settlement path
  would be a second thing to get wrong, and only one of them would be tested.
- **The provider call never runs inside a transaction** (same rule as the invoice
  PDF): reserve in one short transaction, call the provider with none open,
  record in a second.
- **Idempotency is the caller's key plus a partial unique index.** The
  `Idempotency-Key` header is required, is passed to the provider as our payment
  id, and is the only way to resume a payment the provider never acknowledged.
  `ux_payment_invoice_in_flight_or_succeeded` allows one PENDING-or-SUCCEEDED
  payment per invoice — the thing that actually prevents a double charge.
- **Settlement is idempotent by state, not by remembering event ids.** Only a
  PENDING payment settles, so redelivered, duplicated and out-of-order events are
  no-ops. Providers guarantee none of those three.
- **A decline is not a provider outage.** A declined card still produced a real
  payment at the provider, so the adapter records the reference and lets the
  event settle it FAILED; only an unreachable provider leaves a payment PENDING.

**Dunning invariants** — collection is automatic, so the failure modes are
unattended ones.
- **The job only *starts* attempts; it never reads whether one worked.** It
  cannot: settlement is asynchronous, so with a real provider nothing is known
  when the run ends. `PaymentSettlementService` notifies
  `PaymentOutcomeListener`, and `DunningService` decides what the outcome means.
  Anything that makes the job branch on a result is a bug in disguise.
- **`dunning/` is its own module because it must be.** `billing` may not depend
  on `payment` (payment already depends on billing, and ArchUnit forbids the
  cycle), and subscription-lifecycle rules do not belong in `payment`.
- **The attempt is counted and committed before the provider is called**, so a
  crash costs one retry rather than leaving the invoice due again immediately —
  which on an hourly cron means charging the customer every hour. The
  idempotency key is `dunning:{invoiceId}:{attempt}`, so a repeat resumes.
- **`PAUSED` and `CANCELED` are never dragged to `PAST_DUE`.** Pausing is a
  deliberate customer choice; non-payment must not silently overwrite it.
- **Dunning ends.** After `dunning.max-attempts` the invoice is `UNCOLLECTIBLE`
  and the subscription `CANCELED`: each attempt costs a provider fee and annoys
  the customer's bank, so retrying forever is not a kindness.
- **A six-field cron cannot be passed through `-Dspring-boot.run.arguments`** —
  Maven splits it on spaces and Boot rejects `*/20` as a one-field expression.
  Use the environment variable (`DUNNING_CYCLE_CRON="*/20 * * * * *"`) when
  shortening a schedule to watch a job run locally.

**Notification invariants** — an outbox is only as good as the discipline
around when things are written to it and when the queue is touched.
- **The outbox row commits with the change that caused it, never after.**
  `NotificationService`'s enqueue methods run inside the caller's own
  transaction (`InvoiceService.generateForCurrentPeriod`,
  `DunningService.onPaymentFailed`/`giveUp`) so a rollback there takes the
  notification down with it. Nothing publishes to SQS from inside that
  transaction — that's a remote call, the same rule as the invoice PDF and
  the payment provider.
- **The relay claims, publishes, then records — never publishes from inside
  a transaction.** `NotificationRelayService.claimPending` and
  `.markPublished` are two separate short transactions; the SQS publish
  between them runs with none open, the same "reserve, call with nothing
  open, record" split as `PaymentService` and `InvoicePdfService`.
- **Delivery is idempotent by status, not by remembering message ids** —
  the same shape `PaymentSettlementService` uses for provider events. Only
  a row that isn't already `SENT` gets delivered, so a redelivered or
  duplicated SQS message is a no-op rather than a duplicate email.
- **A delivery failure must propagate, never be swallowed.**
  `SqsNotificationListener` lets any exception from `NotificationDeliveryService`
  escape rather than catching it: an unacknowledged message is what makes
  SQS redeliver it, and eventually route it to `notifications-dlq` via the
  redrive policy in `docker/elasticmq/elasticmq.conf`. Catching it there
  would acknowledge a message that was never actually delivered.
- **Queues are infrastructure, provisioned outside the application** — the
  same way real SQS queues would exist before a deploy touches them.
  `elasticmq.conf` defines `notifications` and `notifications-dlq`; the app
  resolves them by name and never creates them.

**Audit invariants** — an audit log is only worth anything if it cannot lie.
- **Recorded in the change's transaction, explicitly.** `AuditService.record` is
  `@Transactional(propagation = MANDATORY)`: an event commits with its change or
  not at all, and a caller with no transaction fails at once. Never move it to
  `@TransactionalEventListener(AFTER_COMMIT)` (a crash loses the row), `REQUIRES_NEW`
  (a rolled-back change leaves an event behind), or an aspect (it sees the row
  update, not the intent).
- **Only real changes are recorded.** Idempotent no-ops (deactivating an inactive
  tenant, clearing a payment method that isn't set) and refused transitions
  (409s) write nothing.
- **The actor is resolved, never passed.** `AuditActors` derives it from the
  security context. A JWT that is neither a tenant nor a platform token throws
  rather than being logged as `SYSTEM`.
- **Outcomes are `SYSTEM` whatever thread they run on** (`recordSystem`): payment
  settlement and dunning decisions. With the fake provider they run inside the
  paying user's request, and with Stripe in a webhook. The audit trail must not
  depend on the provider.
- **No secrets and no personal data in `data`.** No passwords, no payment-method
  tokens, no customer email or name, and actor ids not emails. Audit rows are
  kept for good.
- **Append-only.** No update or delete path in the port, and no write endpoint.
  Renewals and usage increments are deliberately not audited: they are high
  volume and fully derivable.

**Tenant deactivation invariants** — deactivation means *suspended*: stop acting
for the tenant, record what already happened, lose nothing, resume on reactivation.
Full reasoning under Current state in the subscription-hub-state skill.
- **Every scheduled job iterates `findAllActive`**, never all tenants. A new job
  that acts on a tenant's behalf (charges, emails, renews) must do the same, or it
  will bill a tenant that was cut off.
- **Anything that can already be in flight re-checks.** A job's filter only stops
  *new* work; `NotificationDeliveryService` re-checks the tenant because a message
  can be on the queue before deactivation. It hands the row back to `PENDING` and
  returns normally. Throwing would dead-letter it. The check comes after the
  already-`SENT` check, or a duplicate would be re-sent on reactivation.
- **Settlement never checks.** A provider event records money that has already
  moved; refusing it would leave the books wrong without undoing the charge.
- **Missed periods are billed on reactivation, not forgiven.** Waiving them is the
  tenant's decision, and needs `VOID`.

**Migrations** — Flyway, `src/main/resources/db/migration/`. Never edit an
applied migration; add a new versioned one.

**Schema and seed data are separate locations.** `db/migration` is schema and runs
everywhere; `db/seed` holds development fixtures and is only on the Flyway path
under the `dev` profile, which is deliberately *not* the default. Migrations have
no notion of environment, so anything carrying a credential must never live in
`db/migration` — a comment saying "dev only" documents the risk without preventing
it. Seeds are numbered `V9000+` so they can never interleave with a schema version.
The cost of that numbering: a dev database has a *higher* applied version than any
schema migration, so every new schema migration looks out of order to Flyway and
`validate` refuses it (`Detected resolved migration not applied to database: 11`)
— the app then won't start locally while CI, which always starts from an empty
container, stays green. `spring.flyway.out-of-order` is therefore `true` **under
the `dev` profile only**, where a seeded database is the only thing that can be in
that state.

**Commits** — conventional commits, split by concern, not by chronology.
Structural refactor, new feature, bug fix, and test tooling are separate commits.
`fix` means something previously committed and working broke — incomplete work
finished later is `feat`.
- Never add a "Co-authored-by" trailer to commit messages.
- Never run `git commit` or `git push` without showing the exact commit
  message and diff first, and waiting for explicit approval.

**`.http` files** — every endpoint gets example requests in `requests/`, covering
happy path, validation failures, conflict/not-found, and **tenant isolation**
(same identifier under a different tenant must not collide or leak).

---

## 6. Data model

```
tenant (id varchar(64) PK — slug)
 ├── app_user            (unique tenant_id + email; bcrypt password_hash)
 │    └── app_user_role  (user_id + role; CHECK against the four Role values)
 ├── customer            (unique tenant_id + email; default_payment_method nullable, V12 —
 │                        a provider token, never card data; version bigint, V17 — @Version,
 │                        exposed as the ETag)
 ├── product             (unique tenant_id + code)
 │    └── plan           (unique tenant_id + code; interval_unit, interval_count, amount_cents, currency, trial_days)
 │         └── plan_entitlement   (unique tenant_id + plan_id + key; value_json jsonb)
 ├── subscription        (customer_id, plan_id, status, period/renewal/cancel timestamps)
 │    ├── subscription_entitlement_override
 │    └── usage_counter
 ├── invoice → invoice_line   (unique tenant_id + subscription_id + period_start; period_start/end added in V6;
 │        │                     pdf_object_key nullable — renamed from pdf_url in V7, holds an object key not a URL;
 │        │                     paid_at added in V11)
 │        ├── payment      (V11; unique tenant_id + idempotency_key, plus the partial unique index
 │        │                 ux_payment_invoice_in_flight_or_succeeded on (tenant_id, invoice_id)
 │        │                 WHERE status IN ('PENDING','SUCCEEDED') — one charge per invoice)
 │        ├── dunning_state (V13; unique tenant_id + invoice_id — retry schedule, deleted once
 │        │                the invoice settles; separate from invoice because an invoice is
 │        │                immutable once issued)
 │        └── notification (V14; unique tenant_id + dedup_key — invoice_id nullable, since not
 │                          every notification is about one; html_body/text_body rendered and
 │                          stored at enqueue time; relayed to SQS, delivered over SMTP)
 └── audit_event        (V16 activates it: actor_type + actor_id, entity_type + entity_id
                          NOT NULL, request_id; append-only, no @TenantId — see §4)

invoice_number_sequence (tenant_id PK — per-tenant invoice numbering, V6)

platform_user (V15; unique email — belongs to no tenant, no FK to tenant; the
               platform principal, deliberately not an app_user with a null tenant)
```

Postgres enums: `subscription_status` (`TRIALING`, `ACTIVE`, `PAST_DUE`,
`CANCELED`, plus `PAUSED` added in V2), `plan_interval_unit` (`MONTH`, `YEAR`,
added in V3, replacing the old `plan.interval` string column), `invoice_status`
(`DRAFT`, `OPEN`, `PAID`, `VOID`, `UNCOLLECTIBLE` — `OPEN` and, since payments,
`PAID` are producible; see Current state in the subscription-hub-state skill),
`payment_status` (`PENDING`, `SUCCEEDED`, `FAILED`, added in V11).

Seeded tenants for local dev: `acme`, `demo`.

---

## 7. Roadmap

Subscription state transitions (cancel/pause/resume), renewal processing,
usage metering, billing/invoice calculation, invoice PDFs (MinIO),
JWT authentication + RBAC, payments (fake + Stripe adapters, webhook
settlement), dunning (automatic collection, retries, `PAST_DUE` →
`UNCOLLECTIBLE`/`CANCELED`), notifications (transactional outbox → SQS →
email, invoice-issued/payment-failed/subscription-canceled), tenant
provisioning (platform-admin principal and API) and audit events (who changed
what, in the change's transaction) are done — see the subscription-hub-state
skill.

Next, in this order (re-sequenced 2026-09-17; reasoning for each gap is in the
subscription-hub-state skill's Known gaps):

1. ~~**Error-mapping sweep.**~~ Done: malformed JSON, 405, 415, unknown URLs and
   missing required parameters are 4xx problem+json, not 500. It came first so
   client mistakes don't pollute Observability's server-error metrics.
2. ~~**Optimistic locking, step A.**~~ Done: `PUT /api/customers/{id}` with
   `@Version` exposed as `ETag`/`If-Match` (V17). Customer rather than Plan,
   because invoices read the plan's price at generation time, so a price edit
   would reprice every subscriber's unbilled period. `tenant` got a conditional
   UPDATE instead of `@Version`: activation is an idempotent command, and the
   loser of a race should get the no-op, not a 409.
3. **Observability** (Actuator, Micrometer, Prometheus, Grafana). Decides who may
   read metrics.
4. **`spring.jpa.open-in-view` off, plus `@Version` on `subscription`.** Measured
   with the connection-pool metrics from step 3. The subscription step needs a
   retry-or-skip policy in dunning/settlement first, because those are its writers.

- **Audit events moved after authentication**, and had to. The point of an
  audit log is recording *who* did something, and building it before there was
  an authenticated principal would have meant writing `actor = null` and then
  rebuilding it.
- **Observability still has to decide who may read metrics.** The platform
  principal now exists, so `/actuator/prometheus` can be restricted to it (or to a
  dedicated scrape credential); today `/actuator/**` accepts either kind of token.

---

## 8. Working style

When asked "what's next?" — recommend the next logical task from the current
stage, not something six weeks out.

When asked to implement something, give: files to create/change with package
paths, the code, explanation of important decisions, how to test locally, and a
suggested commit message.

When shown an error: what it means, likely cause, smallest correct fix, why it
works, how to verify. Ask for the actual stack trace rather than guessing —
the sanitized problem+json response body hides the real exception.

When multiple approaches are valid, compare briefly and recommend one.