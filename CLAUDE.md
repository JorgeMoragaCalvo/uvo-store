# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

UvoStore is a **multi-tenant e-commerce SaaS platform**: one Spring Boot 4.1.0 (Java 21, Maven)
backend + one Vite/React 19/TypeScript frontend in `frontend/` (storefront **and** admin panel,
same build) serve any number of stores. Each request is resolved to a tenant (`Store`) by its own
custom domain or, as a fallback, a `<slug>.<platform-domain>` subdomain — see "Multi-tenancy"
below. It originated as the migration target for a prior Laravel 12 / Vue 3 implementation that
lives in a separate repository (`C:\Users\jorgemc\Desktop\uvostore_1.0`) —
`docs/based-on-your-knowledge-golden-gizmo.md` documents that original entity/schema migration
plan and is background context for why the schema looks the way it does, not a live source of
truth (the schema is now finalized in the Flyway migrations, and multi-tenancy/the admin panel/
payments/etc. are new work that has no Laravel equivalent).

**The backend API, the storefront, and the admin panel are all fully built out**, not scaffolding.
The storefront reproduces the real customer-facing flow (home, shop, product detail, cart,
checkout with Stripe/Webpay/MercadoPago/manual payment, order tracking, legal pages). The admin
panel (`frontend/src/admin/`) covers products, categories, orders, coupons, customers,
users/roles, shipping (zones/methods/rates, with Chilexpress/Correos de Chile carrier
integration), payment gateway config, banners, store/general settings, and sales/product/payment
reports with charts and CSV export. Customer account/address management exists on the **backend**
(JWT, `/api/customer/**`) but deliberately has **no storefront UI yet** — there's nowhere for a
customer to log in from, only the backend contract is ready.

## Commands

```
./mvnw spring-boot:run          # run the app (reads .env automatically, see below)
./mvnw clean install            # build + run tests
./mvnw test                     # run tests only
./mvnw test -Dtest=ClassName    # run a single test class
```

```
cd frontend && npm run dev        # Vite dev server (http://localhost:5173), proxies /api/* to VITE_DEV_PROXY_TARGET
cd frontend && npm run build      # tsc -b && vite build
cd frontend && npm run lint       # eslint .
cd frontend && npm run test       # vitest run (npm run test:watch for watch mode)
cd frontend && npm run preview    # preview a production build
```

## Multi-tenancy

- `TenantContext` (ThreadLocal) holds the current request's `Store`. `TenantResolutionFilter`
  populates it from the `Host` header: exact match on `Store.domain` first (a client's own custom
  domain), then a `<slug>.<anything>` subdomain match as the fallback every store keeps working
  under regardless (e.g. `demo.localhost:8080` in dev). `JwtAuthenticationFilter` cross-checks the
  token's `sid` claim against the resolved tenant — a mismatch clears security context (403), not
  a silent cross-tenant leak.
- New stores are created via `/api/platform/**` (`PlatformApiKeyAuthFilter`, shared secret in the
  `X-Platform-Key` header) — an **operator-only** tool (`/plataforma/nueva-tienda` in the
  frontend), not public self-service signup. The intended flow: a client hands the operator team a
  nick/domain/admin credentials, the operator creates the store, the client then self-manages
  everything from their own admin panel.
- The frontend computes every API client's `baseURL` **at runtime** from `window.location.origin`
  (`frontend/src/services/api.ts`, `admin/services/adminApi.ts`, `platform/services/platformApi.ts`)
  — not from a build-time env var — so one deployed frontend build serves any tenant. In dev,
  Vite's own proxy (`vite.config.ts`) forwards `/api/*` to `VITE_DEV_PROXY_TARGET`
  (`frontend/.env`, default `http://demo.localhost:8080`) so the browser still sees same-origin
  requests, matching production. In production this assumes frontend static assets and the API are
  served from the same origin (a reverse proxy) — that infrastructure doesn't exist yet, see
  "Known gotchas".

## Local environment

- **PostgreSQL** runs as a Windows service (`postgresql-x64-18`, start type Automatic, data dir
  `C:\pgdata`) — it starts on its own with Windows, no manual step needed. Database `uvostore`,
  role `uvostore` / password `uvostore` (superuser locally; not the same as the nonexistent
  `postgres` role).
- **Backend config**: `.env` at the repo root (gitignored) — copy `.env.example` and fill it in. It
  holds `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` plus three **required** secrets that no longer have
  defaults (see below). Loaded at startup by `me.paulschwarz:spring-dotenv`, registered explicitly
  in `UvoStoreApplication.main()` — no need to export env vars manually, from a terminal or from
  IntelliJ's Run Configuration. `DB_URL` must include `?charSet=UTF8` — see the encoding gotcha
  below. `SENTRY_DSN` is the one exception: `main()` reads it with `System.getenv()` before Spring
  is up, so it has to be a real environment variable, not a `.env` entry.
- **The three secrets have no defaults and the app refuses to start without them** (C4). Generate
  each with `openssl rand -base64 32`:
  - `JWT_SECRET` — signs admin and customer JWTs, minimum 32 bytes (`JwtService`).
  - `PLATFORM_API_KEY` — guards `/api/platform/**` store onboarding (`PlatformApiKeyAuthFilter`).
  - `APP_ENCRYPTION_KEY` — AES-256 (base64, exactly 32 bytes) for payment gateway credentials and
    the Stripe/POS secrets in `settings`, at rest (`EncryptionKeyHolder`).

  All three shipped with working values committed in `application.properties` until C4, so those
  values are in git history and are treated as public: `JWT_SECRET` and `PLATFORM_API_KEY` refuse to
  start if set to their old value. `APP_ENCRYPTION_KEY` only **warns**, because rotating it makes
  every already-encrypted row unreadable — the local `.env` deliberately keeps the old value so the
  existing `payment_gateway_configs` row still decrypts; **production must use a fresh key**.
  Tests don't read `.env`: surefire supplies its own throwaway values in `pom.xml`, which is why CI
  needs no secrets in its workflow.
- **Frontend config**: `frontend/.env` holds `VITE_DEV_PROXY_TARGET` (dev proxy target, see
  "Multi-tenancy" above) and optionally `VITE_SENTRY_DSN` (blank = Sentry inactive). There is no
  `VITE_API_URL` anymore — the API base URL is computed at runtime, not build time.
- CORS (`SecurityConfig`) is wide open for dev (`allowedOriginPatterns: *`, `allowCredentials:
  true`) — tighten before any production deploy.

## Architecture

### Backend (Spring Boot)
Base package `org.uvo.uvostore`, entry point `UvoStoreApplication`.

```
entity/tenant       Store (the tenant root — slug + optional custom domain)
entity/catalog      Product, ProductVariation, ProductImage, Category, Attribute, AttributeValue, ProductVariationAttribute
entity/order        Order, OrderItem, OrderStatusHistory, Coupon, CouponUsage
entity/customer     Customer, ShippingAddress
entity/shipping     ShippingZone, ShippingMethod, ShippingRate (+ carrier quote clients for Chilexpress/Correos de Chile)
entity/payment      PaymentGatewayConfig (per-store Webpay/MercadoPago credentials, AES-256-GCM encrypted at rest)
entity/pos          PosConnection, ProductSyncMapping, SyncWebhookLog
entity/settings     Setting, StoreSettings, HomeBanner
entity/security     User, Role, Permission
entity/common       Shared @Embeddable types (Address, Dimensions)
```
Each domain has matching `repository/`, `service/<domain>/` (interface + `*Impl` + DTOs/records),
and `controller/<domain>/` packages, plus `controller/admin/**` (full admin panel backend:
products, categories, attributes, customers, orders, coupons, shipping zones/methods/rates +
carrier credentials, payment gateway config, users/roles, home banners, store/general settings,
and sales/products/payment-methods reports with date-range filters and CSV export),
`controller/customer/**` (account + addresses, JWT), `controller/platform/**` (store onboarding,
`X-Platform-Key`), and `controller/auth` (admin/customer JWT login+register, password reset).

**Stock and coupon uses are never written with read-modify-write** (C5). `Product.stock`,
`ProductVariation.stock` and `Coupon.timesUsed` move only through the conditional `@Modifying`
queries in their repositories — `decrementStock` carries `AND stock >= :quantity`, `claimUsage`
carries `AND (usageLimit IS NULL OR timesUsed < usageLimit)`, and **the affected-row count is the
only trustworthy result**: 0 means someone else got there first. Reading the entity, comparing in
Java and saving it back lets two concurrent payments both sell the last unit. The database backs
this up with `CHECK (stock >= 0)` on both tables (V13). All of the order-lifecycle stock movement
lives in `OrderInventoryService`, guarded by `orders.stock_applied`/`stock_restored`, because four
separate paths can cancel an order and a second cancellation would otherwise invent inventory.
`StockConcurrencyTest` is the only test in the project that runs real concurrent threads — it
deliberately does not extend `IntegrationTestSupport`, whose per-test transaction would hide the
race.

Two related gaps that are **not** covered: `PosWebhookServiceImpl.handleStockUpdated` still
overwrites stock blindly from the POS and can clobber a decrement, and marking an order paid from
the admin panel (`AdminOrderServiceImpl.updatePaymentStatus`) does **not** decrement stock — only
the gateway paths publish `PaymentConfirmedEvent`.

`OpenApiConfig` wires springdoc — API docs at `/swagger-ui.html`, grouped into five surfaces
(public/admin/customer/pos/platform) with a `bearerAuth` JWT scheme wired to the "Authorize"
button. Error tracking is `io.sentry:sentry` (the **core SDK**, not
`sentry-spring-boot-starter-jakarta` — that starter's autoconfiguration references a class Spring
Boot 4 moved/removed and fails to load; see the comment in `pom.xml`). Initialized manually in
`UvoStoreApplication.main()` from `SENTRY_DSN`; a blank/unset DSN leaves it inactive.
`GlobalExceptionHandler`'s catch-all reports unhandled exceptions to Sentry (and logs them
locally) without leaking internals to the client.

**Database**: PostgreSQL via Flyway, schema in `src/main/resources/db/migration/` (currently up to
V12, well past the original catalog/settings migrations — multi-tenancy, store domains, password
reset, etc. are all later migrations). `spring.jpa.hibernate.ddl-auto=validate`, so any entity
change must be paired with a new Flyway migration (never edit an already-applied one — add
`V13__...sql` etc.).

### Public storefront API (`/api/v1/**`, no auth)
Mirrors what the React `frontend/` consumes: `products` (search/filter incl. `featured`,
`in_stock`, `is_new`, `on_sale`), `products/{slug}`, `products/{slug}/related`, `categories`,
`attributes`, `cart/validate`, `cart/calculate`, `checkout`, `checkout/config`,
`create-checkout-session` + `verify-payment` (Stripe), `webpay/create` + `webpay/return`,
`mercadopago/create-preference`, `store-settings`, `home-banners`, `orders/track`.

Response DTOs are camelCase (Jackson serializes Java records as-is) — notably
`Product.productType` serializes **lowercase** (`"simple"`/`"variable"`, from
`.name().toLowerCase()`), which differs from the enum's own casing; don't assume uppercase when
consuming this API.

### Auth
- `POST /api/admin/auth/login`, `POST /api/customer/auth/{login,register}` — JWT.
  `/api/admin/auth/forgot-password` + `/reset-password` and the customer equivalent fields exist
  for password recovery (email sending is graceful-degrade — see `EmailService` below).
- `/api/admin/**` (`ROLE_ADMIN`) and `/api/customer/**` (`ROLE_CUSTOMER`) are guarded; `/api/v1/**`
  is fully public.
- `/api/platform/**` (store onboarding) uses `X-Platform-Key`, not JWT — see "Multi-tenancy".
- POS integration (`/api/sync/**`, `/api/webhooks/pos/**`) uses its own HMAC/API-key filters,
  separate from JWT.

### External integrations — all "off until configured"
Every external integration in this codebase follows the same pattern: a blank/missing env var
leaves it inactive (log-and-skip, never throw), so the app runs fully in dev/CI without any of
these configured. Applies to: `EmailService` (SMTP, `spring.mail.*` — used for password reset and
order confirmation emails), Stripe/Webpay/MercadoPago (`PaymentGatewayConfig` per store, plus
shared Webpay parent commerce code in `application.properties`), Chilexpress/Correos de Chile
shipping quotes, S3 file storage (`app.storage.driver=s3`), and Sentry (`SENTRY_DSN`/
`VITE_SENTRY_DSN`). **Webpay/MercadoPago/Stripe are fully wired (backend + checkout UI) but have
never been tested against real sandbox credentials** — don't assume they work end-to-end without
that verification.

### Frontend (`frontend/`)
Vite + React 19 + TypeScript + Tailwind v4 (`@tailwindcss/vite`, CSS custom properties for the
runtime-configurable theme colors from `store-settings`) + React Router + Zustand + axios +
shadcn/ui (admin panel only).

```
src/services/api.ts          axios client grouped by domain, baseURL computed at runtime (see "Multi-tenancy")
src/types/api.ts             TS types mirroring the backend DTOs (mind the productType casing above)
src/stores/                  useStoreSettingsStore, useCartStore, useProductsStore, useCheckoutStore, useNotificationStore
src/pages/                   Home, Shop (also mounted at /category/:slug), ProductDetail, Cart, Checkout, OrderSuccess, TrackOrder, legal/*
src/components/layout/       Header, Footer (theme/branding driven by store-settings, unified — no Blade-era header/footer split)
src/components/cart/         CartSidebar, CartLineItem
src/components/home/         HeroSlider (home-banners), ProductSection (new/featured/deals, each gated by its store-settings toggle)
src/admin/                   Full admin panel — pages/ + stores/ + services/adminApi.ts, one CRUD screen per backend domain (products, categories, orders, coupons, customers, users, roles, shipping zones/methods/rates, payment gateways, banners, settings, reports). Same three store patterns repeat throughout: paginated list, flat list, or paginated-list+detail — copy the nearest existing example rather than inventing a new one.
src/platform/                 /plataforma/nueva-tienda — the operator-only store onboarding form
src/test/setup.ts             Vitest + Testing Library setup: jest-dom matchers, RTL cleanup, and a ResizeObserver stub (jsdom doesn't implement it, needed by several Radix UI primitives)
```
Cart state persists to `localStorage` under the key `uvostore_cart` (kept identical to the legacy
Laravel `app.js` cart, in case of shared deploys/migration overlap). Cart totals always come from
`POST /cart/calculate` — never computed client-side. Storefront account/login is deliberately
absent (see Overview); the header's account icon is a disabled placeholder. The admin panel's own
login (`/admin/login`) is separate and fully functional.

## Known gotchas

- **`README.md` and `HELP.md` are unmodified Spring Initializr boilerplate.**
- **`.env` only works because `UvoStoreApplication.main()` registers the initializer by hand.**
  spring-dotenv 4.x announced itself through `META-INF/spring.factories`, which Spring Boot 4 no
  longer reads, so the dependency sat on the classpath doing nothing and `.env` was silently
  ignored for months — invisible because every value in it happened to match its own default in
  `application.properties`. 5.x ships no auto-registration at all. Don't "simplify"
  `new SpringApplicationBuilder(...).initializers(new DotenvApplicationInitializer())` back to
  `SpringApplication.run(...)`: it fails silently, not loudly.
- **On Windows, the JDBC URL needs `?charSet=UTF8`** (already set in `.env` and the
  `application.properties` default). Without it, pgjdbc encodes strings using the JVM's platform
  default charset (Windows-125x, not UTF-8) instead of the `charSet` connection property — any
  accented character saved through the backend gets corrupted before it reaches Postgres, which
  then rejects it outright if the corrupted byte sequence has no equivalent in the DB's own
  encoding.
- **Two pre-existing Spring/JPA bugs were found and fixed** while getting the app to boot against a
  real schema — worth knowing about if similar patterns show up elsewhere:
  - `AttributeValueRepository` had a typo'd derived query name (`finByAttributeId...` instead of
    `findByAttributeId...`).
  - `PosNotificationListener` and `StockDecrementListener` combined
    `@TransactionalEventListener(phase = AFTER_COMMIT)` with a plain `@Transactional`, which
    Spring 7 now rejects at startup — both need
    `@Transactional(propagation = Propagation.REQUIRES_NEW)`. If you add another `AFTER_COMMIT`
    listener that also needs a transaction, use the same pattern.
- **Testing**: 73 backend tests (JUnit + `IntegrationTestSupport`, one Spring-managed transaction
  per test, auto-rolled-back — `AFTER_COMMIT` listeners never fire under this setup, which is
  intentional) and 83 frontend tests (Vitest + React Testing Library, `npm run test`), both wired
  into CI (`.github/workflows/ci.yml`) and the release workflow
  (`.github/workflows/release.yml`, triggered by pushing a `vX.Y.Z` tag — builds artifacts and
  publishes a GitHub Release with auto-generated notes).
- **No deployment infrastructure exists yet** (no Docker, no reverse proxy, no SSL) — paused
  pending a hosting decision. Client custom domains and same-origin frontend+API in production
  both depend on that piece landing eventually.
- **The Laravel/Vue predecessor lives outside this repo** at `C:\Users\jorgemc\Desktop\uvostore_1.0`.
  Its Vue SPA (`resources/js/`) was dead code (never mounted) — the real reference for "what
  should this page do" was always the Blade views + `app.js` + the Livewire home component, not
  the Vue files.
- **Sample/demo data**: `docs/guia-datos-demo-presentacion.md` has a full set of fictional
  store/product/banner/coupon data (with the exact admin API payloads) for spinning up a
  presentable demo store from scratch.
