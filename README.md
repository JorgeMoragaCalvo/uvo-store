# UvoStore

A multi-tenant e-commerce SaaS platform. One Spring Boot backend and one React frontend serve any
number of independent stores: each request is resolved to a tenant by its own custom domain, or by a
`<slug>.<platform-domain>` subdomain as the fallback every store keeps working under.

Each store gets a customer-facing storefront (catalog, cart, checkout, order tracking) and an admin
panel (products, orders, customers, coupons, shipping, payment gateways, reports) — both served from
the same frontend build, told apart by route.

| | |
|---|---|
| Backend | Spring Boot 4.1, Java 21, Maven |
| Frontend | React 19, TypeScript, Vite 8, Tailwind, Zustand |
| Database | PostgreSQL 18, schema managed by Flyway |
| Payments | Stripe, Webpay Plus (Transbank), MercadoPago, manual transfer |
| Shipping | Zone/method/rate engine, with Chilexpress quoting |

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
cp .env.example .env            # VITE_DEV_PROXY_TARGET picks which store you develop against
npm install
npm run dev                     # http://localhost:5173, proxies /api/* to the backend
```

**5. Create a store.** There is no seed data; a fresh database has no tenants, and without one every
request is rejected because it can't be resolved to a store. Stores are created through the
operator-only onboarding endpoint, authenticated with `PLATFORM_API_KEY`:

```bash
curl -X POST http://localhost:8080/api/platform/stores \
  -H "X-Platform-Key: $PLATFORM_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"slug":"demo","storeName":"Demo","adminName":"Admin",
       "adminEmail":"admin@demo.local","adminPassword":"una-clave-larga"}'
```

The store is then reachable at `demo.localhost:8080`, and its admin panel at
`http://localhost:5173/admin`. The `slug` you pick here has to match the host in
`frontend/.env`'s `VITE_DEV_PROXY_TARGET` (`http://demo.localhost:8080` by default): the dev proxy
sends that as the `Host` header, which is what tells the backend which store you're working on.

## Testing

```bash
./mvnw clean verify                   # full backend suite; coverage lands in target/site/jacoco/
./mvnw test -Dtest=ClassName          # a single test class

cd frontend
npm run lint
npm test
npm run build                         # tsc -b && vite build
```

The backend tests are integration tests: real Spring context, real MockMvc dispatch through the
actual filter chain, real PostgreSQL. They need a reachable database (the same one as development is
fine — every test runs inside a transaction that is rolled back).

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
  components/     shared UI
  stores/         Zustand state
```

## API documentation

Swagger UI is served at `/swagger-ui.html`, the OpenAPI spec at `/v3/api-docs`. Both are public.
Endpoints are grouped by audience (public, admin, customer, pos, platform), and the admin/customer
groups are wired to a bearer-JWT scheme so the "Authorize" button works.

## Contributing

`CLAUDE.md` is the working reference for anyone — human or agent — changing this code: architecture,
conventions, and the non-obvious traps that have already cost someone an afternoon (which exception
type to throw, why sort parameters go through an allowlist, why `@BatchSize` and not `JOIN FETCH`,
what breaks if you edit an applied migration). Read it before a first change.

CI runs the full backend suite plus frontend lint, tests and build on every pull request and on
pushes to `main` and `feature/**`.
