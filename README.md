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

- **Tenants** are resolved per-request from an `X-Tenant-Id` header and every
  tenant-owned row is isolated by a `tenant_id` column.
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

Mock payments, dunning, notifications, audit logging, and JWT/RBAC auth are on
the roadmap but not yet built — see [`CLAUDE.md`](CLAUDE.md) for the detailed
current state and what's next.

---

## Architecture

Feature-based modules under `dev.lumjahaj.subscription.hub`, each layered the
same way:

```
common/          cross-cutting: error handling, logging, OpenAPI config
tenancy/         tenant resolution, TenantContext, tenant domain model
catalog/         Product, Plan, PlanEntitlement
customer/        Customer
subscription/    Subscription, renewal
usage/           metered usage counters
billing/         Invoice, invoice lines, PDF rendering and storage
```

Each feature module follows `api → app → domain ← infra`:

- **`api`** — REST controllers, request/response DTOs, mappers
- **`app`** — application services holding business rules
- **`domain`** — domain models and repository interfaces (ports)
- **`infra/jpa`** — JPA entities and Spring Data repositories (adapters)

`billing` additionally has `infra/pdf` and `infra/storage`: three different
outside systems (database, rendering library, object store), each behind its
own port rather than one catch-all adapter package.

Repositories follow a fixed trio pattern: a domain-layer `XxxRepository`
interface (the port the app layer depends on), an infra-layer
`XxxRepositoryImpl` adapter, and a Spring Data `XxxJpaRepository`. This keeps
Spring Data's query-naming machinery out of the domain contract.

### Multi-tenancy

Single database, single schema, row-level isolation via a `tenant_id` column
on every tenant-owned table:

1. Every request must include `X-Tenant-Id: <tenant-slug>` (e.g. `acme`).
2. `TenantResolverFilter` validates the header, loads the tenant, and
   populates a request-scoped `TenantContext`.
3. Application services read the current tenant from `TenantContext` and
   every repository query is scoped by it — never by a tenant ID taken from
   the request body.

A missing header returns `400 TENANT_MISSING`; an unknown tenant returns
`401 TENANT_UNKNOWN`.

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
- Maven
- springdoc-openapi (Swagger UI)
- Docker Compose (Postgres + pgAdmin + MinIO)
- Testcontainers — integration tests run against a real Postgres and a real
  MinIO, not mocks
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
./mvnw spring-boot:run
```

Or run `SubscriptionHubApplication` from your IDE. It starts on
**http://localhost:8080**.

### 3. Explore the API

Swagger UI: **http://localhost:8080/swagger-ui/index.html**
OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Every request against a tenant-owned endpoint needs the tenant header, e.g.:

```
GET /api/products
X-Tenant-Id: acme
```

Two tenants are seeded by the baseline migration for local testing: `acme`
and `demo`.

Example request files covering happy paths, validation errors,
conflict/not-found, and tenant-isolation checks live in
[`requests/`](requests/) (IntelliJ HTTP Client format).

---

## Running tests

```bash
./mvnw verify
```

This is also what runs in CI on every push/PR to `main` (see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml)). Integration tests
provision their own Postgres and MinIO via Testcontainers, so nothing needs to
be running first — invoice totals, tenant isolation and PDF round trips are all
verified against the real thing rather than mocks.

---

## Project docs

[`CLAUDE.md`](CLAUDE.md) is the living architecture and conventions doc for
this project — data model, module conventions, current state, and the honest
list of known gaps and next steps.

---

## License

[MIT](LICENSE)