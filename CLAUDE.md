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
springdoc-openapi 2.8.x · s3mock (invoice PDFs) · AWS SDK v2 for S3 ·
openhtmltopdf + Thymeleaf · stripe-java (payments) · Spring Cloud AWS SQS +
ElasticMQ (notifications) · spring-boot-starter-mail + Mailpit (email) ·
Micrometer + Prometheus + Grafana (observability)

The object store is reached with the **AWS SDK, not a vendor client** — the local
stand-in is S3-compatible, so the same adapter works against real S3 by changing
an endpoint.

**That claim has now been tested, and it only partly held.** A staged deployment
(§9) ran the application against real S3, SQS, SES and Neon. SES and Neon needed
nothing at all — a `.env` change and no code. SQS needed no adapter change but
its endpoint default had to be inverted. **S3 needed three code changes**: the
credential provider was pinned to static credentials so no IAM role could ever
be used, bucket creation was unconditional, and the create request omitted the
location constraint, so it could only ever have worked in `us-east-1`. The
pattern is worth remembering: the claim held wherever the integration was plain
SMTP or plain JDBC, and leaked wherever an AWS SDK client had to be constructed.

**s3mock, not MinIO**, and the reason is operational rather than technical: MinIO
withdrew its community images from Docker Hub, then from quay.io, breaking CI
twice — see the pinned-image rule in §5.

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
"code against the standard, keep the provider a config value" shape as the
object store.

Planned: Redis, WireMock.

**Metrics are read by one account, not by a token.** `/actuator/prometheus` has
its own `SecurityFilterChain` (`MetricsSecurityConfig`, ordered first) that
accepts HTTP Basic for `METRICS_SCRAPE_USERNAME`/`PASSWORD` and nothing else:
Prometheus holds a static secret and this application's JWTs expire hourly, and a
leaked scrape secret should open the metrics and nothing more. Prometheus and
Grafana run in Compose against the app on the host; the dashboard and alert rules
are files in `docker/`, not clicks.

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
common/          api, logging, metrics, web — cross-cutting, depends on nothing
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
- **A platform request has an empty `TenantContext`**, so any session one of its
  transactions opens is pinned to the `__no_tenant__` sentinel. Harmless today —
  `tenant`, `app_user` and `platform_user` are not `TenantScoped` — but a platform
  endpoint that reads a `TenantScoped` entity silently gets nothing unless it wraps
  that transaction in `TenantContext.runAs(targetTenant)`.
- Exceptions: `MissingTenantException` → 400 `TENANT_MISSING` now means *a valid
  token carrying no `tenant_id`*, i.e. one minted by something other than
  `AuthService`; `UnknownTenantException` → 401 `TENANT_UNKNOWN` means the tenant
  was deactivated after the token was issued.
- App services read the tenant via `TenantContext.getTenantId()` directly.
- `RequestIdFilter` (`common/web`) keeps the MDC request id and stays at
  `HIGHEST_PRECEDENCE`, outside the security chain — tenant resolution now happens
  late, but a request Security rejects never reaches it, and those responses still
  need a correlation id.

**Isolation is enforced in three places, and all three stay.**

1. **Every repository query is tenant-scoped explicitly** —
   `findByTenantIdAndCode(...)`, never `findByCode(...)`. Still mandatory: it
   is the intention-revealing contract, and the only layer a reader sees.
2. **Hibernate `@TenantId`** adds the predicate automatically — but only to
   queries Hibernate builds.
3. **Postgres row-level security** (V22, V23) applies to every statement the
   application's connection issues, whichever layer wrote it. This is what
   finally covers the cases `@TenantId` structurally cannot: a
   `nativeQuery = true`, raw JDBC, a reporting tool.

**How RLS is wired**, because every piece of it is load-bearing:
- The app connects as **`subscription_hub_app`** — not `POSTGRES_USER`, which
  is a superuser *and* the table owner, and Postgres exempts both from RLS. An
  application connecting as the owner would leave every policy enforcing
  nothing, silently. `TenantConnectionBindingIntegrationTest` fails if that
  regresses.
- **Flyway keeps migrating as the owner**, and the policies are deliberately
  **not** `FORCE`d. That exemption is what lets a cross-tenant backfill work —
  V3's `UPDATE plan SET interval_unit = ...` rewrites every tenant's rows and
  under FORCE would silently update none.
- The role is created by `docker/postgres/init/01-app-role.sh`
  (**infrastructure**, because it carries a password — §5), while its grants
  and policies are migrations.
- `TenantAwareDataSource` binds `app.tenant_id` on **every connection borrow**
  and clears it on return. Not `connection-init-sql` (once per physical
  connection), and not `SET LOCAL`/`set_config(..., true)` — the connection is
  borrowed *before* Spring issues BEGIN, so a transaction-local set there is
  discarded immediately and does nothing at all.
- Policies use `current_setting('app.tenant_id', true)`, so an unbound
  connection reads **nothing** rather than everything.

**`TenantContext.runAs`/`callAs` must wrap the *transaction*, not sit inside
it.** The connection is bound when the transaction opens, so changing the
ThreadLocal inside a `@Transactional` method is already too late — the work is
scoped to whatever was in context on entry. `PaymentWebhookService` and the
four jobs were already correct; login and the platform endpoints needed the
wrapping moved out to the controller, which is why `callAs` exists. Same rule
as `@TenantId` resolving at session open, one layer lower.

**Native queries still bind `tenantId` explicitly.** Two are tenant-scoped —
`UsageCounterJpaRepository.upsertAndIncrement` and
`InvoiceJpaRepository.allocateNextNumber` — and RLS now covers them too, but
the explicit binding stays as the readable contract. Two more are cross-tenant
*on purpose*, feeding gauges during a metrics scrape, which has no tenant:
`NotificationJpaRepository.oldestAgeSecondsAcrossActiveTenants` and
`PaymentJpaRepository.oldestPendingAgeSecondsAcrossActiveTenants`. Those two
reach past the policies through **SECURITY DEFINER functions** (V21) — the only
bypass in the codebase, granted to one role, with `search_path` pinned. A new
native query must be one of those two kinds, and say which.

**`spring.jpa.open-in-view` is off, and must stay off.** Hibernate resolves
`@TenantId` *when a session opens*. With open-in-view the session opened at the
start of every request and, under the `DELAYED_ACQUISITION_AND_HOLD` mode Spring
configures, held its JDBC connection until the response was written — through
`PaymentService`'s provider call and PDF streaming, with no transaction open. Off,
a session and its connection last only as long as a transaction, and
`OpenInViewDisabledIntegrationTest` fails if it comes back.

The cost is that **a response may only read associations its finder loaded.**
Mappers run in the controller, after the transaction has ended, so:
- A mapper that reads `plan.getCode()` needs a finder with
  `@EntityGraph(attributePaths = "plan")`.
- `getX().getId()` on a proxy is safe and needs nothing.
- A collection on a *paged* finder is initialized inside a read-only transaction
  in the repository adapter instead (`InvoiceRepositoryImpl`). A collection fetch
  combined with LIMIT/OFFSET makes Hibernate paginate in memory.
- `AssociationReadsIntegrationTest` covers every endpoint that depends on this,
  and a new one belongs there.

**A provider webhook sets the tenant before its transaction.**
`POST /api/webhooks/stripe` carries no token, and the tenant is only knowable after
the signature verifies, from the event's metadata. `PaymentWebhookService` runs
settlement inside `TenantContext.runAs(event.tenantId())` and opens the
transaction there, so the session is scoped correctly. With open-in-view on, it
had to unbind the request's already-open `EntityManager` (pinned to the sentinel)
and rebind it afterwards. `PROPAGATION_REQUIRES_NEW` did not help: with no
transaction active there was nothing to suspend. That workaround is gone.

**`AppUserEntity` is the one tenant-owned entity that does not extend
`TenantScoped`**, so it gets no `@TenantId` predicate. This is a chicken-and-egg,
not an oversight: `@TenantId` resolves from `TenantContext` when the Hibernate
session opens, and a login request has no tenant in context when its transaction
opens, because reading this table is what establishes it. (It was first found
with open-in-view on; turning that off did not change it.) Extending `TenantScoped` produced a self-contradicting
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

**Both are still `@TenantId` exceptions, and neither is unprotected any more.**
V23 puts both under RLS, which resolves the same chicken-and-egg differently:
the policy reads a setting bound to the *connection*, so the tenant only has to
be known before the transaction opens, not before Hibernate builds a query. That
is what `TenantContext.callAs` does at the three entry points — `AuthController`
(login), and `PlatformTenantController` for provisioning, activation and the
platform view of a tenant's audit log. These two were the only tables with no
structural backstop at all, which is the reason RLS was worth doing rather than
a hardening of things already protected twice. `RowLevelSecurityIntegrationTest`
now asserts that **no** table with a `tenant_id` column lacks a policy, so a new
tenant-owned table cannot quietly skip one.

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
- **Subscriptions change only through compare-and-set.** Every subscription change
  is a state command (cancel, pause, resume, past due, recover, renew), and it goes
  through `SubscriptionRepository`'s `updateStatusIfStatus`, `cancelIfStatus`,
  `setPendingPlanIfPending` or `renewIfCurrent`: one UPDATE that applies only if the
  row still has the status (and, for renewal, the period and the pending plan) the
  caller read. A plan change is scheduled the same way, and `renewIfCurrent` applies
  it — the swap and the new period must be one statement, because the period's length
  came from the plan being moved onto. On a miss, re-read with
  `findCurrentByTenantIdAndId` and decide again. Never set a field on a loaded
  `SubscriptionEntity` and save it: a dirty managed entity is written at commit
  unconditionally, over whatever committed in between.

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

**A fetch-graph finder cannot re-read an association that changed underneath
it.** A query never overwrites an entity the persistence context already holds,
so when `findByTenantIdAndId` join-fetches `pendingPlan` and the row's
`pending_plan_id` changed since this transaction read it, Hibernate assembles a
row whose fetched association disagrees with the managed instance and throws
`EntityFilterException` ("is filtered for association") — a 500 where a retry
belonged. `SubscriptionRepositoryImpl.findCurrentByTenantIdAndId` therefore
refreshes by id *before* it queries, not after; `find` + `refresh` uses no fetch
graph and cannot hit it. A scalar change (a status) is silently ignored instead,
which is why the old order held until a nullable association existed — and why
the every-compare-and-set retry path is exactly where this shows up.

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
  containers only. `AbstractIntegrationTest` also starts s3mock, Mailpit and
  ElasticMQ, none of which emit that line — four containers per build is
  expected, and the check still means "one Postgres". Don't "fix" the grep to
  match them all and then panic at `4`.
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
  which always pulls. `:latest` anywhere is the same bug waiting, and
  `docker-compose.yml` needs the same treatment as the test containers — a fresh
  clone pulls both.

  **This has now happened twice, to the same dependency.** MinIO's
  `minio/minio` repository disappeared from Docker Hub; CI died with "repository
  does not exist" while every local build stayed green. The fix was
  `quay.io/minio/minio` with a pinned `RELEASE.*` tag — and that was then deleted
  too, every tag including `latest`, killing CI again with 233 errors and
  439 seconds of pull timeouts, and again invisibly on developer machines.

  The second time it was worse than CI: `docker-compose.yml` used the same image,
  so a fresh clone could not start the local stack at all — breaking the
  runnable-from-a-clean-clone property §2 calls protected.

  The object store is now **`adobe/s3mock`**. No remaining MinIO source was
  usable: `bitnamilegacy/minio` is an explicitly frozen archive, and
  `chainguard/minio` publishes only `:latest` for free, so it cannot be pinned —
  which conflicts with this very rule. The lesson pinning alone does not cover:
  **prefer an image whose publisher's product it is.** A pinned tag protects
  against drift, not withdrawal.
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
  create, both branches; `SubscriptionJpaRepository.renewIfCurrent`). The job reuses
  the renewal finder on the strength of it. If a change makes them diverge,
  billing needs its own finder.
- **PDF generation stays out of the billing transaction.** No remote call inside
  `InvoiceService.generateForCurrentPeriod`, and a PDF failure in the job never
  blocks renewal — a missing PDF is recoverable via `POST /api/invoices/{id}/pdf`,
  a missed invoice is not.
- **`InvoicePdfService.generatePdf` is three steps around the upload**, not one
  transaction spanning it: load and render, upload with nothing open, record.
  It was a single `@Transactional` method, and its javadoc argued that was safe
  because a storage failure would roll back a transaction that had written
  nothing. That argument was about rollback and missed a lost update — a payment
  settling during the slow upload was overwritten when the loaded entity was
  saved afterwards.
- **Recording the PDF key writes that column and nothing else.**
  `attachPdfObjectKeyIfAbsent` is a conditional UPDATE, so `status` and `paid_at`
  can never be collateral damage from a stale read. An invoice is *not* immutable
  once issued — `status`, `paid_at` and `pdf_object_key` are all written later —
  and assuming otherwise is what left it the one financial row with neither
  `@Version` nor compare-and-set. Any new write to an invoice names its columns
  the same way.
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
  id, and is the only way a caller can resume a payment the provider never
  acknowledged. `ux_payment_invoice_in_flight_or_succeeded` allows one
  PENDING-or-SUCCEEDED payment per invoice — the thing that actually prevents a
  double charge, and the reason a stuck PENDING payment blocks its invoice.
- **Settlement is idempotent by state, not by remembering event ids.** Only a
  PENDING payment settles, so redelivered, duplicated and out-of-order events are
  no-ops. Providers guarantee none of those three.
- **A decline is not a provider outage.** A declined card still produced a real
  payment at the provider, so the adapter records the reference and lets the
  event settle it FAILED; only an unreachable provider leaves a payment PENDING.
- **Reconciliation discovers outcomes; it never applies them.**
  `PaymentReconciliationJob` asks the provider about payments PENDING past
  `payment.reconciliation.min-age` and feeds what it learns to
  `PaymentSettlementService` as an ordinary `PaymentEvent`. Polling is the
  backstop, not the mechanism: the webhook still settles virtually everything.
  Anything that makes reconciliation write a payment or an invoice directly is
  the second settlement path the first invariant exists to forbid.
- **It never abandons a payment it could not ask about.** "The provider is
  unreachable, so mark it FAILED and free the slot" is a double-charge bug: a
  create can time out *after* the card was charged, and releasing the in-flight
  slot lets dunning retry under a new idempotency key. An unresolvable payment
  stays PENDING and escalates through `payments.pending.oldest.age` and the
  `PaymentStuckPending` alert. A human is the correct answer here; a guess is not.
- **A payment with no `providerReference` is resolved by re-issuing the create
  under the same idempotency key**, never by assuming it never happened. The key
  is our payment id, so the provider returns the original payment if one exists
  and charges exactly once either way — the whole reason the key is mandatory.

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
- **A skipped invoice is skipped *until something else changes it*.**
  `startAttempt` returns empty for an invoice with a payment in flight, which is
  right while a webhook is awaited and permanent if that webhook never comes.
  Reconciliation is what ends it; before that job existed, one unreachable
  provider call retired an invoice from collection for good. A settlement that
  arrives late must not re-count the attempt — `onPaymentFailed` reads the count
  `startAttempt` already committed and never increments it.
- **Every way of failing to collect goes through one path, and so ends.**
  `recordFailure` is it: count the attempt, give up if exhausted, otherwise
  `PAST_DUE` and tell the customer. A decline and a customer with no stored
  payment method are the same business event and differ only in which email is
  sent. Returning empty for the second — the original behaviour — wrote no
  dunning row at all, so that invoice was never chased, emailed or written off,
  and the subscription stayed `ACTIVE` for `BillingCycleJob` to renew into
  another uncollectable invoice every period. A new "cannot collect" case is a
  failure code and a template, never an early return.
- **The attempt is counted and committed before the provider is called**, so a
  crash costs one retry rather than leaving the invoice due again immediately —
  which on an hourly cron means charging the customer every hour. The
  idempotency key is `dunning:{invoiceId}:{attempt}`, so a repeat resumes.
- **`PAUSED` and `CANCELED` are never dragged to `PAST_DUE`**, including by a
  pause or cancel that commits while a failure is settling. Pausing is a deliberate
  customer choice; non-payment must not silently overwrite it. Likewise a recovery
  never reactivates a subscription canceled meanwhile, and a renewal never
  reinstates one. All three hold because dunning and renewal change subscriptions
  by compare-and-set (see Updates in §5), which `SubscriptionTransitionRaceIntegrationTest`
  forces race by race.
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

**Metrics invariants** — each looks harmless and quietly makes a metric lie or
explode.
- **Never tag a meter with a tenant, email, id or anything else unbounded.**
  Every distinct label value is a new time series held in memory forever;
  per-tenant labels are the classic way to take Prometheus down. Per-tenant
  questions belong in the logs, which carry `tenant=` on every request line.
- **Never name a tag `job` or `instance`.** Prometheus attaches both to every
  scraped series and renames a clashing application label to `exported_job`, so a
  rule filtering on it silently matches nothing. It happened: `JobMetrics` used
  `job`, every test passed, and only a live scrape showed it. The tag is
  `scheduled.job`, and `BusinessMetricsIntegrationTest` now asserts no meter uses
  either name.
- **Business counters count what committed.** Increment through
  `AfterCommit.run`, never inline in a transaction, or a rollback counts something
  that never happened. The opposite of the audit rule on purpose: an audit row
  commits *with* its change; a metric only describes what did.
- **Business events come from `audit.events`**, which `AuditService` increments
  for every recorded event, tagged by type and actor. Don't add a parallel counter
  next to an audit call. Add one only for what is deliberately not audited
  (renewals, failed logins) or for a dimension the event lacks (payment provider,
  dunning attempts).
- **A job that stops produces no error, only an absence.** Every scheduled job runs
  through `JobMetrics.run`, whose `jobs.last.success` gauge is what alerts on it,
  and reports caught per-item failures with `itemFailed`. A new job does the same.
- **Metrics are read only by the scrape account.** Never add `/actuator/prometheus`
  to the main chain or accept a JWT for it.
- **`@AutoConfigureObservability` stays on `AbstractIntegrationTest`.**
  `@SpringBootTest` otherwise swaps every exporter for a `SimpleMeterRegistry`, and
  `/actuator/prometheus` does not exist in tests at all.

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

**Configuration: "unset is the deployed value."** Most settings default to what
local development wants and a deployed host overrides them. Three cannot work
that way, because what a deployed host needs is for the setting to be *absent* —
the object-store endpoint, the SQS endpoint and the AWS credentials all mean "ask
the SDK to work it out" when nothing is set: the real regional endpoint, and the
default credential chain (`~/.aws/credentials`, or an EC2 instance role).

Those default to unset, and `.env` states the **local** value. The rule for
anything added later: **if the deployed value is nothing, the default must be
nothing, and local must be explicit.**

The reason it is a rule and not a preference is that the obvious alternative —
default to localhost, blank it when deploying — **is not expressible on Windows.**
The README's PowerShell `.env` loader calls
`Environment.SetEnvironmentVariable`, which *deletes* a variable given an empty
value rather than setting it, so "set but empty" collapses back to "absent" and
the localhost default wins. The bash loader exports an empty string and behaves
differently. That divergence shipped an S3 client addressing `<bucket>.localhost`
on the first real deployment attempt, and it surfaced only as a notification stuck
at `PUBLISHED` — five redeliveries later it dead-lettered. Any config whose
meaning depends on empty-versus-absent is broken on one of the two platforms.

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
 ├── subscription        (customer_id, plan_id, status, period/renewal/cancel timestamps;
 │                       pending_plan_id nullable, V25 — a plan change scheduled for the next
 │                       renewal, applied and cleared by the renewal's own conditional UPDATE)
 │    ├── subscription_entitlement_override
 │    └── usage_counter
 ├── invoice → invoice_line   (unique tenant_id + subscription_id + period_start; period_start/end added in V6;
 │        │                     pdf_object_key nullable — renamed from pdf_url in V7, holds an object key not a URL;
 │        │                     paid_at added in V11)
 │        ├── payment      (V11; unique tenant_id + idempotency_key, plus the partial unique index
 │        │                 ux_payment_invoice_in_flight_or_succeeded on (tenant_id, invoice_id)
 │        │                 WHERE status IN ('PENDING','SUCCEEDED') — one charge per invoice;
 │        │                 V18 adds idx_payment_pending_created on (tenant_id, created_at)
 │        │                 WHERE status = 'PENDING', for the reconciliation sweep — partial, so
 │        │                 it holds only payments still in flight)
 │        ├── dunning_state (V13; unique tenant_id + invoice_id — retry schedule, deleted once
 │        │                the invoice settles; separate from invoice because an invoice is
 │        │                immutable once issued)
 │        └── notification (V14; unique tenant_id + dedup_key — invoice_id nullable, since not
 │                          every notification is about one; html_body/text_body rendered and
 │                          stored at enqueue time; relayed to SQS, delivered over SMTP;
 │                          type is varchar + CHECK, not a Postgres enum, so V19 and V24
 │                          widen the CHECK rather than doing the NAMED_ENUM dance)
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

**Two database roles.** `POSTGRES_USER` is a superuser, owns the schema, and is
what Flyway migrates as. The application connects as `subscription_hub_app`,
which owns nothing and is `NOBYPASSRLS` — see §4. Every table with a `tenant_id`
column carries a `tenant_isolation` policy (V22, V23); the only exceptions are
`tenant` and `platform_user` (they belong to no tenant) and `app_user_role` (no
`tenant_id` — it hangs off `app_user` by `user_id`).

---

## 7. Roadmap

Subscription state transitions (cancel/pause/resume), renewal processing,
usage metering, billing/invoice calculation, invoice PDFs (S3-compatible storage),
JWT authentication + RBAC, payments (fake + Stripe adapters, webhook
settlement), dunning (automatic collection, retries, `PAST_DUE` →
`UNCOLLECTIBLE`/`CANCELED`), notifications (transactional outbox → SQS →
email, invoice-issued/payment-failed/payment-recovered/payment-method-required/
subscription-canceled), tenant
provisioning (platform-admin principal and API), audit events (who changed
what, in the change's transaction) and payment reconciliation (asking the
provider about payments no event ever settled) are done — see the
subscription-hub-state skill.

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
3. ~~**Observability**~~ Done: `/actuator/prometheus` behind a dedicated scrape
   account, job/business/outbox/login metrics, liveness and readiness probes, and
   Prometheus + Grafana in Compose with alert rules and a provisioned dashboard.
4. ~~**`spring.jpa.open-in-view` off, and subscription lost updates**~~ Done.
   4a: explicit fetch plans for every response that reads an association, then the
   flag. 4b: subscription changes became compare-and-set conditional updates
   instead of the planned `@Version`. Every writer is a state command, and
   `@Version` would only have detected conflicts, needing retry logic in five
   places, including inside synchronous fake-gateway settlement.

The re-sequenced roadmap is complete. Payment reconciliation (2026-09-18) was
taken next, ahead of the parked deployment idea and of Postgres RLS: it closed
the one gap the state skill called "the first thing a real system would add",
and it was the only candidate that fixed a live defect rather than hardening
something already correct.

**Postgres row-level security followed (2026-09-22)**, and is done — the last
item the state skill named as outstanding. It was worth doing for `app_user` and
`audit_event` specifically: every other tenant-owned table already had `@TenantId`
as a backstop, while those two had nothing but a naming convention. See §4.
**Deployment followed (2026-09-23 to 2026-09-25)** and is no longer parked: five
of six staged steps shipped and were verified against real AWS and Neon. See §9.

**Scheduled plan changes followed (2026-09-23)**, the first item taken for being
the largest remaining *product* hole rather than a live defect: a billing backend
had no answer to "move this customer from Basic to Pro" but cancel and resubscribe.
A change is scheduled, never immediate — `pending_plan_id` (V25) is applied by the
renewal's own conditional UPDATE, so the closed period stays billed at the price
the customer was actually on. Proration is the deliberate follow-up; see the
subscription-hub-state skill.

- **Two roles, not `FORCE ROW LEVEL SECURITY`.** Keeping Flyway on the owner is
  what lets a cross-tenant backfill still work; FORCE would have made V3's
  `UPDATE plan SET interval_unit = ...` silently update nothing.
- **The bypass for the two cross-tenant gauges is a pair of SECURITY DEFINER
  functions**, chosen over a sentinel tenant value the policy would honour (any
  code that can set a string reaches it, and no test can see a string literal)
  and over a second owner connection (a pool, and "who may use it" becomes a
  question about bean wiring rather than a grant).
- **The gauges were moved before the policies landed**, deliberately: in the
  other order they would have read 0 in between — a metric that lies rather than
  fails, which is the `job`-label bug's exact shape.

- **Reconciliation was not given an `abandon` path**, though one looked obvious.
  Freeing a stuck payment's in-flight slot without the provider's confirmation
  risks a second charge, so the unresolvable case escalates to a human through a
  gauge and an alert instead. Choosing the alert over the automatic fix is the
  decision worth being able to defend.

- **Audit events moved after authentication**, and had to. The point of an
  audit log is recording *who* did something, and building it before there was
  an authenticated principal would have meant writing `actor = null` and then
  rebuilding it.
- **Metrics got their own account, not the platform principal.** Restricting
  `/actuator/prometheus` to `PLATFORM_ADMIN` would have meant Prometheus holding an
  hourly-expiring JWT. The rest of `/actuator/**` is now `PLATFORM_ADMIN` only.

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

---

## 9. Deployment

Deployed 2026-09-23 to 2026-09-25, one vendor at a time, each verified live
before the next. Full stage-by-stage detail is in the subscription-hub-state
skill; this section is the standing rules.

**Managed services, one AWS account plus Neon.** S3 for invoice PDFs, SQS for the
notification outbox, SES's SMTP interface for email, Neon for Postgres, all in
`eu-central-1`. The application runs as a container on EC2 behind a Cloudflare
quick tunnel, pulling its image from ECR via an instance role. `staging` is the
only deployed profile — deliberately not `prod`, which would overstate one
free-tier instance and claim a name a real environment would want.

**Credentials come from the SDK chain, never from a file on the box.**
`StorageConfig` uses static credentials only when an access key is configured and
falls through to `DefaultCredentialsProvider` otherwise; Spring Cloud AWS does the
same for SQS once its always-injected defaults are blanked. On a developer machine
that resolves `~/.aws/credentials`, on EC2 the instance role. This is what the
"unset is the deployed value" rule in §5 exists to protect — a leftover
placeholder silently beats an instance profile.

**Never point the datasource at a pooled Postgres endpoint.**
`TenantAwareDataSource` binds `app.tenant_id` as a *session*-level setting at
connection borrow, because §4 records that `SET LOCAL` is discarded there. A
transaction-mode pooler — which is what Neon's and Supabase's pooled endpoints
are — gives each transaction a different backend, so the setting lands on one and
the query runs on another, and every RLS policy reads an unbound tenant.

That failure is **silent, concurrency-dependent, and deletes rather than leaks**:
a single request against the pooler looked perfectly correct, while 40 concurrent
reads returned the owning tenant's own data **0 times in 19 of 20 requests**. For a
billing system, invoices that intermittently do not exist is worse than an error.
Use the direct endpoint.

**The application role must be created by SQL, not a provider console.** Neon
grants console-created roles membership in `neon_superuser`, and Neon's own owner
role carries `BYPASSRLS` — so a console-created `subscription_hub_app` would very
likely inherit an exemption and leave every policy enforcing nothing, with a
green test suite. Before trusting a managed database, check
`rolbypassrls` on the app role **and on every role it inherits**; expect `f` and
zero rows. This is the same premise-check that made RLS worth doing at all (§4).

**The bucket and the queues are infrastructure, not application concerns.**
`billing.pdf.create-bucket-if-missing` is `false` under `staging`, so the write
path issues neither `headBucket` nor `createBucket` and the deployed credential
needs only `s3:GetObject` and `s3:PutObject`. The SQS queues and their redrive
policy are provisioned before a deploy touches them, exactly as
`elasticmq.conf` does locally.

**Cost is a design constraint here.** The AWS account is past its twelve-month
free tier, so EC2 bills from the first hour — roughly $10–13/month running, once
the public IPv4 charge is counted. `docker compose down` does not stop it; only
stopping or terminating the instance does. The operating rule is to terminate
after each session and rebuild from ECR plus `deploy/ec2-user-data.sh`, which is
why there is a bootstrap script and not an AMI: an AMI is backed by an EBS
snapshot that bills while it exists.

**Still deliberately absent.** No WAF, because a quick tunnel has none — so
`/api/auth/token`, which does a bcrypt verification per call and has no rate
limiting of its own, sits behind nothing but an unindexed URL. That is obscurity,
not a control, and it is the reason not to leave the instance running unattended.
A domain would fix it, along with SES's DKIM alignment, and has been declined as
not worth the cost.