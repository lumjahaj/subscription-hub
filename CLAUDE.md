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
Security · Hibernate 6 · PostgreSQL 17 · Flyway · Docker Compose · springdoc-openapi 2.8.x

Planned: JWT, Redis, MinIO (invoice PDFs), MailHog/Mailpit, Testcontainers,
WireMock, Micrometer, Prometheus, Grafana.

Everything must stay **free and locally runnable**.

Dev environment: Windows, IntelliJ IDEA, Docker Desktop. API testing via
IntelliJ REST Client `.http` files in `requests/`.

---

## 3. Architecture

Base package: `dev.lumjahaj.subscription.hub`

Feature-based modules, each with the same internal layering:

```
common/          api, logging, web — cross-cutting, depends on nothing
tenancy/         api, domain, infra
catalog/         api, app, domain, infra/jpa
customer/        api, app, domain, infra/jpa
subscription/    api, app, domain, infra/jpa
usage/           (not built)
billing/         (not built)
audit/           (not built)
```

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

- Tenant resolved from the `X-Tenant-Id` header (a human-readable slug like
  `acme`, **not** a UUID — that's why `tenantId` is `String` everywhere).
- `TenantResolverFilter` (`OncePerRequestFilter`, `HIGHEST_PRECEDENCE`) validates
  the header, loads the tenant, and populates `TenantContext` (static ThreadLocal)
  plus MDC. Cleans up in `finally`.
- Filter skips `/actuator`, `/swagger-ui`, `/v3/api-docs`.
- Exceptions: `MissingTenantException` → 400 `TENANT_MISSING`,
  `UnknownTenantException` → 401 `TENANT_UNKNOWN`.
- App services read the tenant via `TenantContext.getTenantId()` directly.

**Every repository query must still be tenant-scoped explicitly.**
`findByTenantIdAndCode(...)`, never `findByCode(...)`. This is no longer the
*only* thing enforcing isolation — see §9 — but it stays mandatory as defense
in depth: the structural backstop only covers Hibernate-mediated queries, not
a future native/`nativeQuery = true` one.

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
The same applies to `invoice_status` when Billing is built.

**Migrations** — Flyway, `src/main/resources/db/migration/`. Never edit an
applied migration; add a new versioned one.

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
 ├── customer            (unique tenant_id + email)
 ├── product             (unique tenant_id + code)
 │    └── plan           (unique tenant_id + code; interval, amount_cents, currency, trial_days)
 │         └── plan_entitlement   (unique tenant_id + plan_id + key; value_json jsonb)
 ├── subscription        (customer_id, plan_id, status, period/renewal/cancel timestamps)
 │    ├── subscription_entitlement_override
 │    └── usage_counter
 ├── invoice → invoice_line
 └── audit_event
```

Postgres enums: `subscription_status` (`TRIALING`, `ACTIVE`, `PAST_DUE`,
`CANCELED`, plus `PAUSED` added in V2), `invoice_status` (`DRAFT`, `OPEN`,
`PAID`, `VOID`, `UNCOLLECTIBLE`).

Seeded tenants for local dev: `acme`, `demo`.

---

## 7. Current state

**Done:**
- Docker Compose + PostgreSQL, Spring Boot baseline
- Tenant resolution, request context, MDC
- Problem+JSON error handling (`common/api`), shared MDC keys (`common/logging/MdcKeys`)
- Flyway V1 baseline schema; V2 adds `PAUSED`
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
  else `ACTIVE`, period ends `now + 1 interval`. `nextRenewal = currentPeriodEnd`.
  `cancelAt`/`canceledAt` stay null. Verified end-to-end via `subscription.http`
  (trial/no-trial creation, not-found, tenant isolation).
- Mapper unit tests committed for Product, Plan, PlanEntitlement, Customer,
  Subscription, with shared validator setup extracted.
- CI — GitHub Actions workflow running `mvn verify` against a real Postgres
  service container on every push/PR to `main`.
- README covering architecture, multi-tenancy model, and local setup.

**Endpoints:**
```
GET  /api/health
POST GET /api/products
POST GET /api/plans
POST GET /api/plans/{planCode}/entitlements
POST GET /api/customers
POST GET /api/subscriptions[?customerId=]
```

---

## 8. Roadmap

Immediate:
1. Subscription state transitions — cancel, pause/resume, with a real transition
   guard (can't cancel a canceled subscription, can't resume one that isn't
   paused). This is where distinct business-rule exceptions belong.
2. Renewal processing — scheduled job advancing `TRIALING → ACTIVE` and rolling
   billing periods. ⚠️ `TenantContext` is a ThreadLocal populated by a servlet
   filter; background jobs have no request, so tenant scoping must be handled
   explicitly there.

Then: Usage metering → Billing/invoice calculation → Invoice PDFs (MinIO) →
Mock payments → Dunning (`PAST_DUE`) → Notifications (MailHog) → Audit events →
JWT + RBAC (`ADMIN`, `BILLING`, `SUPPORT`, `USER`) → Observability
(Actuator, Micrometer, Prometheus, Grafana).

---

## 9. Known gaps and improvements

Honest assessment. Several of these matter more for the portfolio than the next
feature module does.

**Architectural**

- **Tenant isolation has a structural backstop now, but isn't fully closed.**
  Hibernate `@TenantId` (not `@Filter` — that's opt-in per `Session` and easy
  to forget to enable, so it's not worth using once `@TenantId` is in place)
  adds an automatic `tenant_id =` predicate to every Hibernate-mediated query,
  resolved from the same `TenantContext` via `TenantIdentifierResolver`. It
  does **not** protect native/`nativeQuery = true` queries (none exist yet),
  background jobs that never populate `TenantContext`, or anything outside
  Hibernate entirely (a raw JDBC script, a future reporting tool). Postgres
  row-level security remains the stronger, DB-level option for those cases —
  deliberately not done yet, because verifying it properly needs a real
  Testcontainers integration test (see Testing gap below) and correct
  `SET LOCAL`/HikariCP wiring; the plan is to pair the two rather than ship
  RLS unverified.
- **Check-then-save uniqueness race.** `findByTenantIdAndCode(...).ifPresent(throw)`
  followed by `save(...)` isn't atomic. The DB constraint catches it, but the
  resulting `DataIntegrityViolationException` currently falls through to a
  generic 500. Catch it and map to the same 409.
- **`TenantContext` and async.** ThreadLocal doesn't propagate to `@Async`
  threads or scheduled jobs. Must be addressed before renewal processing.
- **`Page<T>` serialization.** Spring warns about serializing `PageImpl`
  directly; the JSON shape isn't a stable API contract. A small `PagedResponse<T>`
  wrapper would be more honest as a public API.

**Testing — still the biggest portfolio gap**

- Mapper unit tests and CI (`mvn verify` on every push) are in place. What's
  missing is integration tests: Testcontainers + PostgreSQL, asserting the
  rules that actually matter — **tenant isolation** (tenant A cannot
  read/modify tenant B's data through any endpoint, and specifically: does
  `@TenantId` actually block a crafted cross-tenant lookup), subscription
  state transitions, period calculations, and later billing totals.

**API completeness**

- No single-resource reads (`GET /api/products/{code}`), no update or delete
  anywhere. Add `GET /{code}` at minimum — it also unlocks a proper `Location`
  header on 201 responses, which is currently missing.
- No idempotency on create endpoints. Worth at least being able to discuss.

**Smaller**

- `plan.interval` is a `String` with a `@Pattern` guard; an enum (`MONTH`,
  `YEAR`) mapped the same way as `SubscriptionStatus` would be type-safe and
  remove the `switch` default that throws `IllegalStateException`.
- No currency consistency rule — nothing prevents a tenant from mixing
  currencies across plans a single invoice would later combine.
- `audit_event` table exists but nothing writes to it.

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