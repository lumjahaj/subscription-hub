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

---

## 2. Stack

Java 21 · Spring Boot 3.5.6 · Maven · Spring Web / Data JPA / Validation /
Security · Hibernate 6 · PostgreSQL 17 · Flyway · Testcontainers · Docker Compose ·
springdoc-openapi 2.8.x · MinIO (invoice PDFs) · AWS SDK v2 for S3 ·
openhtmltopdf + Thymeleaf

The object store is reached with the **AWS SDK, not the MinIO client** — MinIO is
S3-compatible, so the vendor stays a config value and the same adapter works
against real S3/R2 by changing an endpoint.

Thymeleaf is present as a **library only** (`org.thymeleaf:thymeleaf`), never
`spring-boot-starter-thymeleaf`: it renders the invoice HTML that openhtmltopdf
turns into a PDF. This is a pure JSON API with no MVC view layer, and the starter
would wire one in.

Authentication is **self-issued HS256 JWTs**, validated by
`spring-boot-starter-oauth2-resource-server` (which had been on the classpath
unused since the beginning). Symmetric signing because issuer and validator are the
same process; an external IdP would use RSA + JWKS and change only `issuer-uri`,
since the verification side is already the standard machinery — the same
"code against the standard, keep the provider a config value" shape as MinIO/S3.

Planned: Redis, MailHog/Mailpit, WireMock, Micrometer, Prometheus, Grafana.

Everything must stay **free and locally runnable**.

Dev environment: Windows, IntelliJ IDEA, Docker Desktop. API testing via
IntelliJ REST Client `.http` files in `requests/`.

---

## 3. Architecture

Base package: `dev.lumjahaj.subscription.hub`

Feature-based modules, each with the same internal layering:

```
common/          api, logging, web — cross-cutting, depends on nothing
auth/            api, app, domain, infra/jpa
tenancy/         api, domain, infra
catalog/         api, app, domain, infra/jpa
customer/        api, app, domain, infra/jpa
subscription/    api, app, domain, infra/jpa
usage/           api, app, domain, infra/jpa
billing/         api, app, domain, infra/{jpa, pdf, storage}
audit/           (not built)
```

`billing` is the first module with more than one `infra` package. `infra/jpa`
adapts the database, `infra/storage` adapts the object store, and `infra/pdf`
adapts the rendering library — three different outside systems, each behind its
own port, rather than one catch-all `infra`.

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
  `/actuator`, `/swagger-ui`, `/v3/api-docs`. Being inside the security chain is
  not the same as running only on authenticated requests — a `permitAll` path still
  passes through every filter, just with an empty context.
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
*only* thing enforcing isolation — see §9 — but it stays mandatory as defense
in depth: the structural backstop only covers Hibernate-mediated queries, not
a native/`nativeQuery = true` one. There are now two of those —
`UsageCounterJpaRepository.upsertAndIncrement` and
`InvoiceJpaRepository.allocateNextNumber` (see §7) — and `tenantId` is bound
explicitly in both for exactly this reason.

**`AppUserEntity` is the one tenant-owned entity that does not extend
`TenantScoped`**, so it gets no `@TenantId` predicate. This is a chicken-and-egg,
not an oversight: `@TenantId` resolves from `TenantContext` when the Hibernate
session opens, and with `spring.jpa.open-in-view` that is the *start of the
request* — before anything could know the tenant. Reading this table is what
establishes it. Extending `TenantScoped` produced a self-contradicting
`where tenant_id = '__no_tenant__' and tenant_id = 'acme'` and failed every login
with correct credentials. Scoping stays explicit via `findByTenantIdAndEmail`, and
the issued token takes its tenant from the row found rather than from the request.

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
create, `Pageable` parameter on list endpoints, map `Page<Entity>` →
`Page<Response>` via `page.map(Mapper::toResponse)`.

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
- Setup helpers assert the response status before calling `.getBody()`.
  Otherwise a problem+json body deserializes into the response record and
  surfaces as a confusing Jackson error (ProblemDetail's numeric `status` vs.
  an enum field) hundreds of log lines from the real cause.

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

**Migrations** — Flyway, `src/main/resources/db/migration/`. Never edit an
applied migration; add a new versioned one.

**Schema and seed data are separate locations.** `db/migration` is schema and runs
everywhere; `db/seed` holds development fixtures and is only on the Flyway path
under the `dev` profile, which is deliberately *not* the default. Migrations have
no notion of environment, so anything carrying a credential must never live in
`db/migration` — a comment saying "dev only" documents the risk without preventing
it. Seeds are numbered `V9000+` so they can never interleave with a schema version.

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
 ├── customer            (unique tenant_id + email)
 ├── product             (unique tenant_id + code)
 │    └── plan           (unique tenant_id + code; interval_unit, interval_count, amount_cents, currency, trial_days)
 │         └── plan_entitlement   (unique tenant_id + plan_id + key; value_json jsonb)
 ├── subscription        (customer_id, plan_id, status, period/renewal/cancel timestamps)
 │    ├── subscription_entitlement_override
 │    └── usage_counter
 ├── invoice → invoice_line   (unique tenant_id + subscription_id + period_start; period_start/end added in V6;
 │                              pdf_object_key nullable — renamed from pdf_url in V7, holds an object key not a URL)
 └── audit_event

invoice_number_sequence (tenant_id PK — per-tenant invoice numbering, V6)
```

Postgres enums: `subscription_status` (`TRIALING`, `ACTIVE`, `PAST_DUE`,
`CANCELED`, plus `PAUSED` added in V2), `plan_interval_unit` (`MONTH`, `YEAR`,
added in V3, replacing the old `plan.interval` string column), `invoice_status`
(`DRAFT`, `OPEN`, `PAID`, `VOID`, `UNCOLLECTIBLE` — only `OPEN` is producible
today, see §7).

Seeded tenants for local dev: `acme`, `demo`.

---

## 7. Current state

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
  `TenantScoped.tenantId`, resolved via `tenancy/infra/TenantIdentifierResolver`
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
  see the Testcontainers rules in §5 — `TestRestTemplate` on a random port
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
- **API hardening pass** — closed the three gaps §9 flagged as mattering more
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
    the tenancy note in §4: `@TenantId`'s automatic predicate doesn't apply
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
    arithmetic at all, so the §5 money rule holds right up to the moment the
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
    rather than the generic resource shapes. §5 says not to invent per-entity
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
  - `AppUserEntity` deliberately does not extend `TenantScoped` — see §4 for the
    chicken-and-egg that forces it.
  - Actuator: only `health` and `info` are exposed, and only `/actuator/health` is
    public. `httptrace` was removed from the exposure list — a Boot 2 endpoint id
    needing a bean nothing defines, so already dead, but it records request and
    response *headers*, which now means bearer tokens.
  - Seeded dev logins (`admin@acme.test`, `admin@demo.test`, `support@acme.test`,
    password `subscriptionhub`) live in `db/seed` under the `dev` profile, not in
    `db/migration` — see §5.

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
```

---

## 8. Roadmap

Subscription state transitions (cancel/pause/resume), renewal processing,
usage metering, billing/invoice calculation, invoice PDFs (MinIO), and
JWT authentication + RBAC are done — see §7.

Next: Mock payments → Dunning (`PAST_DUE`) → Notifications (MailHog — the PDF
is the attachment, which is why it came first) →
**Tenant provisioning (platform-admin API)** → Audit events → Observability
(Actuator, Micrometer, Prometheus, Grafana).

Two notes on that order:

- **Audit events moved after authentication**, and had to. `audit_event` has an
  `actor` column and the whole point of an audit log is recording *who* did
  something; building it before there was an authenticated principal would have
  meant writing `actor = null` and then rebuilding it.
- **Tenant provisioning is `POST /api/platform/tenants` behind a principal with
  *no* `tenant_id` claim.** It earns its own roadmap line because it introduces
  the platform-level principal that two other gaps already need — actuator
  authorization beyond `/health`, and the fact that there is currently no way to
  create a tenant at all (see §9).

---

## 9. Known gaps and improvements

Honest assessment. Several of these matter more for the portfolio than the next
feature module does.

**Architectural**

- **Tenant isolation now rests on a signed claim, not the caller's word.** Until
  authentication landed, `X-Tenant-Id` was client-supplied and unverified: anyone
  who could reach the API was any tenant they liked. `@TenantId` and
  `TenantIsolationIntegrationTest` protected tenant A from tenant B's *bugs*, never
  from B claiming to be A. That is closed — but note `AppUserEntity` is now an
  entity with no `@TenantId` predicate at all (§4), so its single repository method
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
  `InvoiceJpaRepository.allocateNextNumber` (see §7), which bind `tenantId`
  by hand instead — or anything outside Hibernate entirely (a raw JDBC
  script, a future reporting tool). Postgres row-level security remains
  the stronger, DB-level option for those cases — deliberately not done yet;
  the Testcontainers harness that was the prerequisite for verifying it
  properly now exists, so RLS is next up if the `SET LOCAL`/HikariCP wiring
  is worth it.
- ~~Check-then-save uniqueness race~~ — closed. See §7's API hardening pass.
  `findByTenantIdAndCode(...).ifPresent(throw)` followed by `save(...)` is
  still not atomic, but the losing side of the race now gets the same 409
  a synchronous duplicate does, instead of a 500.
- ~~`Page<T>` serialization~~ — closed. See §7's API hardening pass.

**Testing**

- Mapper/unit tests, CI, and Testcontainers-backed integration tests are all
  in place now — tenant isolation, subscription state transitions, and
  renewal period math are verified against a real Postgres end-to-end, not
  just unit-tested in isolation (see §7). `ProblemDetailsAdviceTest` covers
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

- ~~No single-resource reads~~ — closed. See §7's API hardening pass.
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
  and there now is one — which is why §8 moved it after authentication.
- Only `InvoiceStatus.OPEN` is ever set — `DRAFT`, `PAID`, `VOID`, and
  `UNCOLLECTIBLE` are declared (they must match the Postgres enum exactly)
  but nothing in the code produces them yet. Deliberate: better an
  honestly-unused enum value than an invented lifecycle with no logic
  behind it. `PAID`/`VOID`/`UNCOLLECTIBLE` arrive with payments and
  dunning.
- No update or delete on invoices either, same as everywhere else — an
  invoice is additionally meant to be immutable once issued (see §7), so
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
  link. Declared because §8 named it, not because it does anything.
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
  onboarded through a sales process rather than a signup form (§8).
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

---

## 10. Working style

When asked "what's next?" — recommend the next logical task from the current
stage, not something six weeks out.

When asked to implement something, give: files to create/change with package
paths, the code, explanation of important decisions, how to test locally, and a
suggested commit message.

When shown an error: what it means, likely cause, smallest correct fix, why it
works, how to verify. Ask for the actual stack trace rather than guessing —
the sanitized problem+json response body hides the real exception.

When multiple approaches are valid, compare briefly and recommend one.