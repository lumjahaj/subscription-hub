# Subscription Hub

A multi-tenant billing and subscription management backend for SaaS
applications, built with Spring Boot. This is a portfolio project — the goal
is to demonstrate real-world backend engineering practices (multi-tenancy,
layered architecture, migrations, error handling, API design) well enough to
walk through in a Senior Java/Spring Boot interview.

[![CI](https://github.com/lumjahaj/subscription-hub/actions/workflows/ci.yml/badge.svg)](https://github.com/lumjahaj/subscription-hub/actions/workflows/ci.yml)

---

## What it does

Subscription Hub models the backend of a billing system that serves multiple
tenants (SaaS customers) from one deployment:

- **Tenants** are resolved per-request from a verified JWT claim, and every
  tenant-owned row is isolated by a `tenant_id` column.
- **Authentication** issues signed tokens carrying the caller's tenant and roles,
  with role-based rules on every write endpoint.
- **Catalog** — tenants define `Product`s, each with one or more `Plan`s
  (interval, price in cents, currency, trial length), and optional
  `PlanEntitlement`s (feature flags/limits attached to a plan).
- **Customers** belong to a tenant and subscribe to plans.
- **Subscriptions** track lifecycle state (`TRIALING`, `ACTIVE`, `PAST_DUE`,
  `PAUSED`, `CANCELED`) and billing period timestamps, and roll forward
  automatically once a billing period closes.
- **Usage metering** records per-period metered usage against a subscription
  (`emails.sent`, `api.calls`, …) as an atomic running total, so concurrent
  requests for the same meter can't lose increments.
- **Billing** turns a closed period into an invoice: a `BASE` line for the
  plan's recurring charge plus `USAGE` lines priced from the metered
  counters, with per-tenant human-readable invoice numbers.
- **Invoice PDFs** are rendered from an HTML template and stored in MinIO
  (S3-compatible object storage), then streamed back through the API.
- **Payments** collect an invoice through a provider port. The default provider
  is an in-process fake — no account, no network — and Stripe is opt-in by
  config. Neither reports the outcome from the API call itself: a payment is
  created `PENDING` and settled only by a provider event (a webhook, for
  Stripe), so both providers share one settlement path and one set of rules.

Dunning, notifications, tenant provisioning and audit logging are on the roadmap
but not yet built — see [`CLAUDE.md`](CLAUDE.md) for the architecture and
conventions, and the state skill linked at the bottom for the detailed current
state and an honest list of what's deliberately missing.

---

## Architecture

Feature-based modules under `dev.lumjahaj.subscription.hub`, each layered the
same way:

```
common/          cross-cutting: error handling, logging, OpenAPI config
auth/            users, roles, JWT issuing
tenancy/         tenant resolution, TenantContext, tenant domain model
catalog/         Product, Plan, PlanEntitlement
customer/        Customer
subscription/    Subscription, renewal
usage/           metered usage counters
billing/         Invoice, invoice lines, PDF rendering and storage
payment/         provider port, fake + Stripe adapters, webhook settlement
```

Each feature module follows `api → app → domain ← infra`:

- **`api`** — REST controllers, request/response DTOs, mappers
- **`app`** — application services holding business rules
- **`domain`** — domain models and repository interfaces (ports)
- **`infra/jpa`** — JPA entities and Spring Data repositories (adapters)

`billing` additionally has `infra/pdf` and `infra/storage`, and `payment` has
`infra/gateway`: each outside system (database, rendering library, object store,
payment provider) sits behind its own port rather than in one catch-all adapter
package.

Repositories follow a fixed trio pattern: a domain-layer `XxxRepository`
interface (the port the app layer depends on), an infra-layer
`XxxRepositoryImpl` adapter, and a Spring Data `XxxJpaRepository`. This keeps
Spring Data's query-naming machinery out of the domain contract.

### Multi-tenancy

Single database, single schema, row-level isolation via a `tenant_id` column
on every tenant-owned table:

1. Every request carries `Authorization: Bearer <jwt>`, obtained from
   `POST /api/auth/token`.
2. `TenantResolverFilter` reads the token's signed `tenant_id` claim, re-checks
   the tenant is still active, and populates a request-scoped `TenantContext`.
3. Application services read the current tenant from `TenantContext` and
   every repository query is scoped by it — never by a tenant ID taken from
   the request body.

There is deliberately **no `X-Tenant-Id` header**. It existed until
authentication landed, and it meant the tenant was whatever the caller typed:
row-level isolation protected each tenant from the others' *bugs*, but nothing
stopped someone from simply claiming to be another tenant. Taking it from a
signed claim is what closes that.

No token returns `401 UNAUTHENTICATED`; an authenticated caller lacking the
required role returns `403 ACCESS_DENIED`.

### Payments

Charging money is the one place where "retry it" and "roll it back" stop being
free, so three things are deliberate:

- **The provider call happens outside any transaction.** A `PENDING` payment is
  reserved in one short transaction, the provider is called with none open, and
  the reference is recorded in a second. A rollback cannot un-charge a card, and
  holding a database connection open across a network call is how a pool dies.
- **One charge per invoice is enforced by the database.** A partial unique index
  allows at most one `PENDING`-or-`SUCCEEDED` payment per invoice. Code checks
  the invoice is open first, but two concurrent requests both pass that check;
  the index is what makes the loser fail *before* reaching the provider.
- **Settlement is idempotent by state, not by remembering event ids.** Only a
  `PENDING` payment can settle, and it settles once, so a redelivered,
  duplicated or out-of-order event changes nothing — and providers guarantee
  none of those three won't happen.

A caller must send an `Idempotency-Key`; it replays the first result rather than
charging twice, and it is the only way to resume a payment the provider never
acknowledged.

### Error handling

Errors are returned as RFC 7807 `application/problem+json`, with a `code`
and a `requestId` (for log correlation) on every error response.

---

## Tech stack

- Java 21, Spring Boot 3.5.6 (Web, Data JPA, Validation, Security)
- PostgreSQL 17, Flyway migrations, Hibernate 6
- MinIO for object storage, reached with the **AWS SDK v2 for S3** — MinIO is
  S3-compatible, so the same code runs against real S3 or R2 by changing an
  endpoint
- openhtmltopdf + Thymeleaf (as a library, not the Spring MVC starter) for
  rendering invoice PDFs
- `stripe-java` for the opt-in Stripe adapter. **No Stripe account is needed to
  build, run or test this project**: the default provider is the in-process
  fake, the adapter is exercised against `stripe-mock` (Stripe's own local API
  server, as a container), and webhook tests sign their payloads themselves,
  since verification is an HMAC against a shared secret. That is why the Stripe
  path has CI coverage a real-account integration could never have
- Maven
- springdoc-openapi (Swagger UI)
- Docker Compose (Postgres + pgAdmin + MinIO)
- Testcontainers — integration tests run against a real Postgres, a real MinIO
  and Stripe's own `stripe-mock`, not hand-written mocks
- GitHub Actions CI (`mvn verify`; the tests provision their own containers)

---

## Running locally

### Prerequisites

- Docker Desktop
- JDK 21
- (Optional) IntelliJ IDEA — the `requests/*.http` files are IntelliJ REST
  Client scripts

### 1. Start the infrastructure

```bash
git clone https://github.com/lumjahaj/subscription-hub.git
cd subscription-hub
cp .env.example .env
docker compose up -d
```

This brings up:

| Service        | URL / Port              | Notes                                   |
|----------------|--------------------------|------------------------------------------|
| Postgres       | `localhost:5432`         | DB/user/password from `.env`             |
| pgAdmin        | http://localhost:8081    | Login with `PGADMIN_DEFAULT_EMAIL`/`PASSWORD` from `.env`; add a server with host `postgres` |
| MinIO (S3 API) | `localhost:9000`         | Used by the app to store invoice PDFs    |
| MinIO console  | http://localhost:9001    | Login with `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` from `.env`; browse stored PDFs under the `invoices` bucket |

Flyway runs the schema migrations automatically on application startup, and the
`invoices` bucket is created on the first PDF upload — no manual `psql` or
bucket-creation step needed.

### 2. Run the application

The Spring Boot app itself is **not** part of `docker-compose.yml`; run it
directly against the containerized Postgres:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Or run `SubscriptionHubApplication` from your IDE with the `dev` profile
active. It starts on **http://localhost:8080**.

The `dev` profile is what puts `db/seed` on the Flyway path, and therefore what
creates the login accounts below. It is deliberately not the default: seed data
carries a publicly known password, so an environment that forgets to ask for it
gets none rather than silently inheriting an admin account.

### 3. Explore the API

Swagger UI: **http://localhost:8080/swagger-ui/index.html**
OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Every request against a tenant-owned endpoint needs a bearer token. Get one
first:

```bash
curl -s -X POST http://localhost:8080/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"acme","email":"admin@acme.test","password":"subscriptionhub"}'
```

then send it:

```
GET /api/products
Authorization: Bearer <token>
```

In Swagger UI, use the **Authorize** button and paste the token.

Two tenants are seeded for local testing (`acme` and `demo`), each with an
admin, plus one read-only user for trying the role rules:

| Tenant | Email               | Role    | Password         |
|--------|---------------------|---------|------------------|
| acme   | `admin@acme.test`   | ADMIN   | `subscriptionhub` |
| demo   | `admin@demo.test`   | ADMIN   | `subscriptionhub` |
| acme   | `support@acme.test` | SUPPORT | `subscriptionhub` |

These exist only under the `dev` profile. Reads are open to any authenticated
role; writes need `ADMIN` (catalog) or `ADMIN`/`BILLING` (customers,
subscriptions, usage, invoices, payments) — logging in as `support@acme.test` is
the quickest way to see a `403`.

Paying an invoice additionally requires an `Idempotency-Key` header:

```
POST /api/invoices/{id}/payments
Authorization: Bearer <token>
Idempotency-Key: <any unique string>

{ "paymentMethod": "pm_card_visa" }
```

The fake provider recognises Stripe's own test payment-method ids, so the same
requests work against either provider: `pm_card_visa` succeeds,
`pm_card_visa_chargeDeclined` is declined, and the fake-only
`pm_fake_provider_unavailable` simulates an unreachable provider.

Example request files covering happy paths, validation errors,
conflict/not-found, role denials, and tenant-isolation checks live in
[`requests/`](requests/) (IntelliJ HTTP Client format).

---

## Running tests

```bash
./mvnw verify
```

This is also what runs in CI on every push/PR to `main` (see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml)). Integration tests
provision their own Postgres, MinIO and stripe-mock via Testcontainers, so
nothing needs to be running first — invoice totals, tenant isolation, PDF round
trips, concurrent charge attempts and signed webhook settlement are all verified
against the real thing rather than mocks.

---

## Project docs

[`CLAUDE.md`](CLAUDE.md) is the living architecture and conventions doc — data
model, module conventions, and the rules each module follows.

[`.claude/skills/subscription-hub-state/SKILL.md`](.claude/skills/subscription-hub-state/SKILL.md)
holds the detailed current state: what is built, why each decision was made, and
an honest list of known gaps and what is deliberately not done.

---

## License

[MIT](LICENSE)