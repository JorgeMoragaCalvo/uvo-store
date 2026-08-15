# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

UvoStore is a Spring Boot 4.1.0 (Java 21, Maven) backend, paired with a planned React SPA frontend, targeting PostgreSQL. It is a migration target for a prior Laravel 12 / Vue 3 implementation — `docs/based-on-your-knowledge-golden-gizmo.md` is the authoritative entity/schema migration plan (verified against the real Laravel source) and should be treated as the source of truth for table/column/relationship decisions rather than re-deriving the schema from scratch.

`frontend/` is scaffolded as a Vite + React 19 + TypeScript app (ESLint configured); dependencies are installed (`npm install` has been run).

## Commands

```
./mvnw spring-boot:run          # run the app
./mvnw clean install            # build + run tests
./mvnw test                     # run tests only
./mvnw test -Dtest=ClassName    # run a single test class
```

```
cd frontend && npm run dev        # Vite dev server
cd frontend && npm run build      # tsc -b && vite build
cd frontend && npm run lint       # eslint .
cd frontend && npm run preview    # preview a production build
```

## Architecture

### Backend (Spring Boot)
Base package `org.uvo.uvostore`, entry point `UvoStoreApplication` (`src/main/java/org/uvo/uvostore/UvoStoreApplication.java`).

Entity layer lives under `src/main/java/org/uvo/uvostore/entity/`, organized into domain subpackages mirroring `docs/based-on-your-knowledge-golden-gizmo.md`:
```
entity/catalog     Product, ProductVariation, ProductImage, Category, Attribute, AttributeValue, ProductVariationAttribute
entity/order        Order, OrderItem, OrderStatusHistory, Coupon, CouponUsage
entity/customer     Customer, ShippingAddress
entity/shipping     ShippingZone, ShippingMethod, ShippingRate
entity/pos          PosConnection, ProductSyncMapping, SyncWebhookLog
entity/settings     Setting, StoreSettings, HomeBanner
entity/security     User, Role, Permission
entity/common       Shared @Embeddable types (Address, Dimensions)
```
Each domain subpackage has its own `enums` subpackage for its `@Enumerated` types.

**Current progress (check before assuming something exists):** catalog, order, customer, and shipping domains are implemented. The pos domain is in progress. The settings and security domains, Flyway migrations, and any repository/service/controller layers do not exist yet.

### Database
Targets PostgreSQL (`flyway-database-postgresql` + `postgresql` driver already in `pom.xml`), but `src/main/resources/application.properties` has no datasource/JPA/Flyway configuration wired up yet, and no Flyway migration files exist. Both need to happen before `spring.jpa.hibernate.ddl-auto=validate` or any DB-backed test can run.

### Frontend (React)
`frontend/` is a Vite + React 19 + TypeScript app (`frontend/package.json`) replacing the old Vue 3 storefront, with ESLint configured (`frontend/eslint.config.js`). Dependencies are installed, but no UvoStore-specific code has been written yet — it's still the default Vite template, with no components, routing, or API client for the Spring Boot backend.

## Known gotchas

- **`README.md` and `HELP.md` are unmodified Spring Initializr boilerplate** — no project-specific info, don't rely on them.
- **No real test coverage yet** — only the generated `UvoStoreApplicationTests` context-load test exists.
- **The Laravel/Vue predecessor lives outside this repo.** `docs/based-on-your-knowledge-golden-gizmo.md` was verified line-by-line against that real source. However, a few columns are explicitly flagged in the doc as ambiguous pending a live-DB check once Flyway migrations exist (e.g., the true live type of `Order.shippingAddress`, and the intentionally-non-standard `company_id`-based join between `PosConnection` and `ProductSyncMapping`, which has no DB-level FK constraint). Don't silently "fix" these — they're deliberate flags, not oversights.
