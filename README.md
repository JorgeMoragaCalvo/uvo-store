# UvoStore

A multi-tenant e-commerce SaaS platform, built as a module of UvoPOS. One Spring Boot backend and one
React frontend serve any number of independent stores: each request is resolved to a tenant by its own
custom domain, or by a `<slug>.<platform-domain>` subdomain as the fallback every store keeps working under.

Each store gets a customer-facing storefront (catalog, cart, checkout, order tracking) and an admin
panel (products, orders, refunds, customers, coupons, shipping, payment gateways, reports) — both served
from the same frontend build, told apart by route.

| | |
|---|---|
| Backend | Spring Boot 4.1, Java 21, Maven |
| Frontend | React 19, TypeScript, Vite 8, Tailwind, Zustand |
| Database | PostgreSQL 18, schema managed by Flyway |
| Payments | Stripe, Webpay Plus (Transbank), MercadoPago, manual payment |
| Shipping | Zone/method/rate engine. Carrier quoting (Chilexpress) exists but is off by default |
| POS | Two-way sync with UvoPOS (catalog, stock, sales), off by default (`POS_ENABLED`) |

## Requirements

- JDK 21
- Node 24
- PostgreSQL 18 (any recent 15+ should work; 18 is what CI runs)

## Getting started

**1. Create the database.**

```bash
createdb uvostore
createuser uvostore --pwprompt
```

**2. Configure secrets.** Copy `.env.example` to `.env` and fill it in. Three values are required and
have no defaults — the app refuses to start without them, deliberately, because they used to ship
with working values committed to this repository:

```bash
cp .env.example .env
openssl rand -base64 32      # once per secret: JWT_SECRET, APP_ENCRYPTION_KEY, PLATFORM_API_KEY
```

`.env` is read at startup and is gitignored. Tests don't read it — they get their own throwaway
values from the surefire configuration in `pom.xml`, so the test suite runs on a fresh clone with
nothing exported.

**3. Run the backend.** Flyway applies the migrations on first start.

```bash
./mvnw spring-boot:run          # http://localhost:8080
```

**4. Run the frontend.**

```bash
cd frontend
cp .env.example .env
npm install
npm run dev                     # http://localhost:5173, proxies /api/* to the backend
```

**5. Create a store.** The migrations seed the permission catalogue and one store, `demo`, with no
admin — so it can't be logged into, and its slug is taken. Stores are created through the
operator-only onboarding endpoint, authenticated with `PLATFORM_API_KEY` and reachable only from a
private network (localhost counts):

```bash
curl -X POST http://localhost:8080/api/platform/stores \
  -H "X-Platform-Key: $PLATFORM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"slug":"mitienda","storeName":"Mi Tienda","adminName":"Admin",
       "adminEmail":"admin@mitienda.local"}'
```

`domain` is optional (a custom domain, used once it is marked verified). The response carries the
store's `storefrontUrl` and `adminUrl`. The same form exists as a page at
`http://localhost:5173/plataforma/nueva-tienda`.

Nobody picks the owner's password: the owner gets a one-time invitation email and chooses it there.
Without `MAIL_HOST` no email is sent, so locally take the token from the database and open the link
yourself:

```sql
SELECT invitation_token FROM users WHERE email = 'admin@mitienda.local';
```

```
http://mitienda.localhost:5173/admin/aceptar-invitacion?token=<invitation_token>
```

After that the panel is at `http://mitienda.localhost:5173/admin`. The dev proxy keeps the browser's
subdomain and sends it to the backend as the `Host` header, which is what tells the backend which store
you're working on — so each store is reached at `<slug>.localhost:5173`. `VITE_DEV_PROXY_TARGET` in
`frontend/.env` only sets the backend port, and the store a bare `localhost:5173` goes to.

## Configuration switches

`.env.example` documents the variables and their defaults. These change what the module does:

| Variable | Default | What it does |
|---|---|---|
| `APP_ENVIRONMENT` | `development` | With `production` or `staging`, startup fails on any development default left in place (`ProductionConfigurationRules`) |
| `SWAGGER_ENABLED` | `true` | Publishes Swagger UI and `/v3/api-docs`. Production and staging require `false` |
| `CUSTOMER_ACCOUNTS_ENABLED` | `false` | Shopper accounts. Off, `/api/customer/**` answers 404 and checkout is guest-only |
| `SHIPPING_CARRIERS_ENABLED` | empty | Carriers allowed to quote. Empty means stores ship with their own rates only |
| `POS_ENABLED` | `false` | The UvoPOS integration: `/api/sync/**`, `/api/webhooks/pos/**` and sending sales to the POS |

Effects that must survive a crash (the sale sent to the POS, order emails) go through a transactional
outbox (`app.outbox.*`); deliveries that fail for good show up in the admin panel under «Envíos fallidos».

## Testing

```bash
./mvnw clean verify                   # full backend suite; coverage lands in target/site/jacoco/
                                      # and the build fails below the line-coverage floor (pom.xml)
./mvnw test -Dtest=ClassName          # a single test class (with `verify` instead, add -Djacoco.skip=true)

cd frontend
npm run lint
npm test
npm run build                         # tsc -b && vite build
```

The backend tests are integration tests: real Spring context, real MockMvc dispatch through the
actual filter chain, real PostgreSQL. They need a reachable database (the same one as development is
fine). Most run inside a transaction that is rolled back (`IntegrationTestSupport`). Some can't,
because what they test only happens on a real commit — concurrency, crash recovery, the outbox:
`StockConcurrencyTest`, the `*InterruptionTest` classes and others built on `support/CommittedFixtures`
or `support/InterruptionTestSupport`. Those clean up after themselves, but one killed halfway leaves
rows in the development database.

**End-to-end.** `frontend/e2e/` is a Playwright suite that drives the packaged jar, Vite, and doubles
for the POS, the payment gateways and SMTP:

```bash
./mvnw package -DskipTests            # the suite runs target/uvo-store-*.jar: rebuild after backend changes
cd frontend
npx playwright install chromium       # once
npm run e2e                           # npm run e2e:report opens the last HTML report
```

It drops and recreates its own database, `uvostore_e2e`, on every run, with the credentials in
`E2E_DB_USERNAME`/`E2E_DB_PASSWORD` (falling back to `DB_USERNAME`/`DB_PASSWORD` from `.env`).

**API contract.** The OpenAPI spec is versioned in `docs/api/` and `OpenApiSpecTest` fails when the API
changes without it. After changing an endpoint or an exposed DTO, regenerate it and review the diff:

```bash
./mvnw test -Dtest=OpenApiSpecTest -Dopenapi.regenerate=true
```

## Layout

```
src/main/java/org/uvo/uvostore/
  controller/     REST endpoints, grouped by audience: public v1, admin, customer, pos, platform
  service/        business logic; DTOs live next to the service that returns them
  entity/         JPA entities
  repository/     Spring Data repositories
  security/       JWT, tenant resolution, rate limiting, POS HMAC/API-key filters
  config/         Spring configuration (security, CORS, OpenAPI, filter registration)
src/main/resources/db/migration/     Flyway migrations — never edit an applied one, add the next
frontend/src/
  pages/          storefront
  admin/          admin panel (pages, stores, types, services)
  platform/       operator pages (store onboarding)
  components/     shared UI
  stores/         Zustand state
frontend/e2e/     Playwright suite: tests, doubles, launcher
docs/             project documentation, in Spanish
docs/api/         the versioned OpenAPI spec
```

## API documentation

Swagger UI is served at `/swagger-ui.html`, the OpenAPI spec at `/v3/api-docs`, only while
`SWAGGER_ENABLED=true` — the development default. Production and staging require it off, and then both
answer 404; the contract to work from is the exported spec in `docs/api/`, one file per group.

Endpoints are grouped by audience (public, admin, customer, pos, platform). The admin and customer
groups are wired to a bearer-JWT scheme and the platform group to `X-Platform-Key`, so the "Authorize"
button works. The POS endpoints are signed with HMAC and can't be tried from the UI.

## Project documentation

- [Plan de profesionalización y puesta en producción](PLAN_PRODUCCION.md): production readiness, the
  `PROD-*` and `POS-*` backlog and its current status, and operational acceptance criteria.
- [Guía de uso](docs/guia-de-uso.md): the merchant's manual for the admin panel.
- [docs/api/](docs/api/README.md): the API contract.

## Contributing

`CLAUDE.md` is the working reference for anyone — human or agent — changing this code: architecture,
conventions, and the non-obvious traps that have already cost someone an afternoon (which exception
type to throw, why sort parameters go through an allowlist, why `@BatchSize` and not `JOIN FETCH`,
what breaks if you edit an applied migration). Read it before a first change.

CI (`.github/workflows/`):
- `ci.yml`, on every pull request and on pushes to `main` and `feature/**`: the full backend suite
  (`clean verify`), frontend lint, tests and build, and the end-to-end suite (`e2e.yml`).
- `dependency-review.yml` on pull requests, and `qodana_code_quality.yml` on pull requests and `main`.
- `release.yml`, on a `vX.Y.Z` tag: builds the backend jar and the frontend bundle, runs the end-to-end
  suite on that same commit, and publishes the GitHub Release only if it passes. Publishing a release
  does not deploy anything.
