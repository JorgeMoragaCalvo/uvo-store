# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

UvoStore is a Spring Boot 4.1.0 (Java 21, Maven) backend + a standalone Vite/React 19/TypeScript storefront in `frontend/`, targeting PostgreSQL. It is the migration target for a prior Laravel 12 / Vue 3 implementation that lives in a separate repository (`C:\Users\jorgemc\Desktop\uvostore_1.0`) — `docs/based-on-your-knowledge-golden-gizmo.md` documents the entity/schema migration plan and should be treated as background context for why the schema looks the way it does, not as a live source of truth (the schema is now finalized in the Flyway migrations below).

**Both the backend API surface and the `frontend/` storefront are fully built out**, not scaffolding. The storefront reproduces the real customer-facing flow (home, shop, product detail, cart, checkout, order tracking, legal pages) that used to run on Laravel Blade + Livewire + a legacy vanilla-JS cart. Customer account/auth (login, register, order history) exists on the backend (JWT) but is deliberately **not** implemented in the React frontend yet — out of scope by design, not an oversight.

## Commands

```
./mvnw spring-boot:run          # run the app (reads .env automatically, see below)
./mvnw clean install            # build + run tests
./mvnw test                     # run tests only
./mvnw test -Dtest=ClassName    # run a single test class
```

```
cd frontend && npm run dev        # Vite dev server (http://localhost:5173)
cd frontend && npm run build      # tsc -b && vite build
cd frontend && npm run lint       # eslint .
cd frontend && npm run preview    # preview a production build
```

## Local environment

- **PostgreSQL** runs as a Windows service (`postgresql-x64-18`, start type Automatic, data dir `C:\pgdata`) — it starts on its own with Windows, no manual step needed. Database `uvostore`, role `uvostore` / password `uvostore` (superuser locally; not the same as the nonexistent `postgres` role).
- **Backend config**: `.env` at the repo root (gitignored) holds `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`. It's loaded automatically at startup via the `me.paulschwarz:spring-dotenv` dependency in `pom.xml` — no need to export env vars manually, from a terminal or from IntelliJ's Run Configuration.
- **Frontend config**: `frontend/.env` holds `VITE_API_URL=http://localhost:8080/api/v1`.
- CORS (`SecurityConfig`) is wide open for dev (`allowedOriginPatterns: *`, `allowCredentials: true`) — tighten before any production deploy.

## Architecture

### Backend (Spring Boot)
Base package `org.uvo.uvostore`, entry point `UvoStoreApplication`.

```
entity/catalog      Product, ProductVariation, ProductImage, Category, Attribute, AttributeValue, ProductVariationAttribute
entity/order        Order, OrderItem, OrderStatusHistory, Coupon, CouponUsage
entity/customer     Customer, ShippingAddress
entity/shipping     ShippingZone, ShippingMethod, ShippingRate
entity/pos          PosConnection, ProductSyncMapping, SyncWebhookLog
entity/settings     Setting, StoreSettings, HomeBanner
entity/security     User, Role, Permission
entity/common       Shared @Embeddable types (Address, Dimensions)
```
Each domain has matching `repository/`, `service/<domain>/` (interface + `*Impl` + DTOs/records), and `controller/<domain>/` packages, plus `controller/admin/**` (full admin panel: products, categories, attributes, customers, orders, coupons, shipping config, roles/users, sales/product/payment reports) and `controller/auth` (admin/customer JWT login+register).

**Database**: PostgreSQL via Flyway, schema fully defined in `src/main/resources/db/migration/` — `V1__catalog.sql` through `V7__settings.sql`, 28 tables total, derived field-by-field from the JPA entities. `spring.jpa.hibernate.ddl-auto=validate`, so any entity change must be paired with a new Flyway migration (never edit an already-applied one — add `V8__...sql` etc.).

### Public storefront API (`/api/v1/**`, no auth)
Mirrors what the React `frontend/` consumes: `products` (search/filter incl. `featured`, `in_stock`, `is_new`, `on_sale`), `products/{slug}`, `products/{slug}/related`, `categories`, `attributes`, `cart/validate`, `cart/calculate`, `checkout`, `checkout/config`, `create-checkout-session` + `verify-payment` + `stripe/webhook` (Stripe Checkout Sessions, not raw Payment Intents), `store-settings`, `home-banners`, `orders/track`. The last three (`store-settings`, `home-banners`, `orders/track`) plus the `is_new`/`on_sale` product filters were added specifically to support the React migration — they didn't exist in the original backend scaffold.

Response DTOs are camelCase (Jackson serializes Java records as-is) — notably `Product.productType` serializes **lowercase** (`"simple"`/`"variable"`, from `.name().toLowerCase()`), which differs from the enum's own casing; don't assume uppercase when consuming this API.

### Auth
- `POST /api/admin/auth/login`, `POST /api/customer/auth/{login,register}` — JWT.
- `/api/admin/**` (`ROLE_ADMIN`) and `/api/customer/**` (`ROLE_CUSTOMER`) are guarded; `/api/v1/**` is fully public.
- POS integration (`/api/sync/**`, `/api/webhooks/pos/**`) uses its own HMAC/API-key filters, separate from JWT.

### Frontend (`frontend/`)
Vite + React 19 + TypeScript + Tailwind v4 (`@tailwindcss/vite`, CSS custom properties for the runtime-configurable theme colors from `store-settings`) + React Router + Zustand + axios.

```
src/services/api.ts          axios client grouped by domain, baseURL from VITE_API_URL
src/types/api.ts             TS types mirroring the backend DTOs (mind the productType casing above)
src/stores/                  useStoreSettingsStore, useCartStore, useProductsStore, useCheckoutStore, useNotificationStore
src/pages/                   Home, Shop (also mounted at /category/:slug), ProductDetail, Cart, Checkout, OrderSuccess, TrackOrder, legal/*
src/components/layout/       Header, Footer (theme/branding driven by store-settings, unified — no Blade-era header/footer split)
src/components/cart/         CartSidebar, CartLineItem
src/components/home/         HeroSlider (home-banners), ProductSection (new/featured/deals, each gated by its store-settings toggle)
```
Cart state persists to `localStorage` under the key `uvostore_cart` (kept identical to the legacy Laravel `app.js` cart, in case of shared deploys/migration overlap). Cart totals always come from `POST /cart/calculate` — never computed client-side. Account/login is deliberately absent; the header's account icon is a disabled placeholder.

## Known gotchas

- **`README.md` and `HELP.md` are unmodified Spring Initializr boilerplate.**
- **No real test coverage** — only the generated `UvoStoreApplicationTests` context-load test exists.
- **Two pre-existing Spring/JPA bugs were found and fixed** while getting the app to boot against a real schema — worth knowing about if similar patterns show up elsewhere:
  - `AttributeValueRepository` had a typo'd derived query name (`finByAttributeId...` instead of `findByAttributeId...`).
  - `PosNotificationListener` and `StockDecrementListener` combined `@TransactionalEventListener(phase = AFTER_COMMIT)` with a plain `@Transactional`, which Spring 7 now rejects at startup — both need `@Transactional(propagation = Propagation.REQUIRES_NEW)`. If you add another `AFTER_COMMIT` listener that also needs a transaction, use the same pattern.
- **The Laravel/Vue predecessor lives outside this repo** at `C:\Users\jorgemc\Desktop\uvostore_1.0`. Its Vue SPA (`resources/js/`) was dead code (never mounted) — the real reference for "what should this page do" was always the Blade views + `app.js` + the Livewire home component, not the Vue files.
- **Only test/seed data exists today** (one category, one product, inserted manually to verify the checkout flow end-to-end). There's no seed script — load real catalog data via the admin API or directly in Postgres.
