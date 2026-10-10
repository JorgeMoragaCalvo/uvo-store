# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

UvoStore is a **multi-tenant e-commerce SaaS platform**: one Spring Boot 4.1.0 (Java 21, Maven) backend + one Vite/React 19/TypeScript frontend in `frontend/` (storefront **and** admin panel,
same build) serve any number of stores. Each request is resolved to a tenant (`Store`) by its own
custom domain or, as a fallback, a `<slug>.<platform-domain>` subdomain — see "Multi-tenancy"
below. It originated as the migration target for a prior Laravel 12 / Vue 3 implementation that
lives in a separate repository (`C:\Users\jorgemc\Desktop\uvostore_1.0`) —
`docs/based-on-your-knowledge-golden-gizmo.md` documents that original entity/schema migration
plan and is background context for why the schema looks the way it does, not a live source of
truth (the schema is now finalized in the Flyway migrations, and multi-tenancy/the admin panel/
payments/etc. are new work that has no Laravel equivalent).

**The backend API, the storefront, and the admin panel are all fully built out**, not scaffolding.
The storefront reproduces the real customer-facing flow (home, shop, product detail, cart, checkout with Stripe/Webpay/MercadoPago/manual payment, order tracking, legal pages). 
The admin panel (`frontend/src/admin/`) covers products, categories, orders, coupons, customers, users/roles, shipping (zones/methods/rates, with Chilexpress/Correos de Chile carrier integration), payment gateway config, banners, store/general settings, and sales/product/payment reports with charts and CSV export. Customer account/address management exists on the **backend** (JWT, `/api/customer/**`) but deliberately has **no storefront UI yet** — there's nowhere for a customer to log in from, only the backend contract is ready.

Two robustness audits have been run against this code and **both are closed** — see "How to read the
`C5`/`A1`/`F19` references" under Architecture, which is also the key to finding IDs cited
throughout this file and in the code comments. The hardening they produced is the bulk of what the
Architecture section documents, so prefer reading *why* a constraint exists there over inferring it
from the code.

**The active roadmap is `PLAN_PRODUCCION.md`** — what still has to be true before this runs for real
customers, as `PROD-01`…`PROD-30` with acceptance criteria. Read §5.1 (launch blockers) and §13
(phases) before starting anything; the dependency chain is `01 → 02/03/08 → 04/05/09/10 →
11/12/14 → 16`. Most of the remaining P0s are infrastructure and evidence rather than code: no
deployment infrastructure exists yet (see "Known gotchas"), and the payment gateways have never been
exercised against real sandbox credentials.

## Commands

```
./mvnw spring-boot:run          # run the app (reads .env automatically, see below)
./mvnw clean verify             # build + full test suite + JaCoCo report
./mvnw test                     # run tests only
./mvnw test -Dtest=ClassName    # run a single test class
```
On Windows use `mvnw.cmd`. Add `-o` (offline) once the local repository is warm — the suite boots a
Spring context per test class, and dependency resolution is the slowest part of a cold run.

```
cd frontend && npm run dev        # Vite dev server (http://localhost:5173), proxies /api/* to VITE_DEV_PROXY_TARGET
cd frontend && npm run build      # tsc -b && vite build
cd frontend && npm run lint       # eslint .
cd frontend && npm run test       # vitest run (npm run test:watch for watch mode)
cd frontend && npm run preview    # preview a production build
cd frontend && npx tsc -b         # type check on its own — NOT `tsc --noEmit`, see "Known gotchas"
```

## Multi-tenancy

- `TenantContext` (ThreadLocal) holds the current request's `Store`. `TenantResolutionFilter` populates it from the `Host` header: exact match on `Store.domain` first (a client's own custom domain), then a `<slug>.<anything>` subdomain match as the fallback every store keeps working under regardless (e.g. `demo.localhost:8080` in dev). `JwtAuthenticationFilter` cross-checks the token's `sid` claim against the resolved tenant — a mismatch clears the security context (and so answers **401**, F19), not a silent cross-tenant leak.
- New stores are created via `/api/platform/**` (`PlatformApiKeyAuthFilter`, shared secret in the `X-Platform-Key` header) — an **operator-only** tool (`/plataforma/nueva-tienda` in the frontend), not public self-service signup. The intended flow: a client hands the operator team a nick/domain/admin credentials, the operator creates the store, the client then self-manages everything from their own admin panel.
- **Anything the backend builds that points at a store goes through `StorePublicUrlResolver`**
  (`service/url/`, PROD-04) — the inverse of `StoreHostResolver`: that one turns a hostname into a
  `Store`, this one turns a `Store` into its public origin. It **takes the store as an argument and
  never reads `TenantContext` or the request**, which is what lets it work in `AFTER_COMMIT`
  listeners and scheduled jobs (they take the store from the order or the user). Three properties
  configure it: `app.public-url.platform-domain` plus the `app.public-url.storefront` /
  `app.public-url.api` templates, where `{host}` is substituted. Two origins because in dev the
  storefront is Vite on 5173 and the API is the backend on 8080; in production both are the same
  https origin behind the proxy. Invalid templates **fail startup** (no `{host}`, not absolute, or
  carrying a path), same policy as `app.reports.timezone`.
  It replaced `app.frontend-url`, a **single global URL injected in seven classes** — the three
  emails with links (admin password reset, admin invitation, customer activation), Webpay's
  post-return redirect, and the Stripe/MercadoPago/Webpay fallbacks. Every store's buyer landed on
  one configured host, and Webpay's case wasn't even overridable. **The property is deleted, not
  deprecated**, so a forgotten `@Value("${app.frontend-url}")` fails the context instead of quietly
  resolving to the wrong tenant. Two related changes went with it:
  - `return_url` (Webpay) and `notification_url` (MercadoPago) used to be built from the live
    request's `getScheme()`/`getServerName()`/`getServerPort()`, so behind a TLS-terminating proxy
    the gateway got a URL describing the internal hop (`http`, upstream port). **Don't "fix" that
    class of problem with `server.forward-headers-strategy`** — see the `ClientIpResolver` note
    under rate limiting. `LocalFileStorageService.publicUrl` still builds image URLs this way and
    is the one known remaining case (deferred to PROD-05/09).
  - **The SPA no longer sends return URLs at all.** `successUrl`/`cancelUrl`/`failureUrl`/
    `pendingUrl`/`returnUrl` are gone from the three `*Request` records: they were passed to Stripe
    and MercadoPago unvalidated, which is an open redirect inside a real payment flow. An old SPA
    build that still sends them keeps working — Jackson drops unknown properties
    (`url/LegacyReturnUrlFieldsTest` pins that, because frontend and backend deploy separately).
- **A custom domain is only used in outgoing links once it's verified** (`stores.domain_verified_at`,
  V23). The asymmetry is deliberate: **inbound** resolution accepts the domain from the moment it's
  written (otherwise you could never test it before marking it), **outbound** link building requires
  the verification, because an email linking to a domain whose DNS isn't pointed here yet is a dead
  email and there's no resend screen. `PUT /api/platform/stores/{id}/domain/verified` records the
  operator's check — **it does not probe DNS**; automating that waits on PROD-03. Changing the
  domain clears the flag, so a new domain never inherits it.
- The frontend computes every API client's `baseURL` **at runtime** from `window.location.origin` (`frontend/src/services/api.ts`, `admin/services/adminApi.ts`, `platform/services/platformApi.ts`) — not from a build-time env var — so one deployed frontend build serves any tenant. In dev, Vite's own proxy (`vite.config.ts`) forwards `/api/*` to `VITE_DEV_PROXY_TARGET` (`frontend/.env`, default `http://demo.localhost:8080`) so the browser still sees same-origin requests, matching production. In production this assumes frontend static assets and the API are served from the same origin (a reverse proxy) — that infrastructure doesn't exist yet, see "Known gotchas".

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
- **The three secrets have no defaults, and the app refuses to start without them** (C4). Generate
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
- **CORS is an allowlist derived from the stores themselves** (`TenantCorsConfigurationSource`, A2):
  an origin is accepted only when its hostname resolves to a real store — custom domain first, then
  subdomain slug, the same two-step lookup `TenantResolutionFilter` uses, shared via
  `StoreHostResolver`. Anything else gets no `Access-Control-Allow-*` header and the browser blocks
  it. `app.cors.additional-origins` is the escape hatch for an origin that isn't a store hostname
  (a separately hosted SPA build); empty by default, because in dev Vite proxies `/api/*` and
  requests are same-origin. Note when testing by hand: an `Origin` identical to the request's own
  host is same-origin and CORS never engages — use a different port.
- **Rate limiting** (`RateLimitFilter`, A4) on the six unauthenticated endpoints: admin/customer
  login, customer registration, admin forgot-password (window 5x longer — each hit sends a real
  email), order tracking and the MercadoPago webhook. Counters in a bounded Caffeine cache; limits
  are properties (`app.rate-limit.*`). **Who the caller is, is `ClientIpResolver`'s decision, not this
  filter's** (F18): the filter used to take `X-Forwarded-For`'s first entry without checking where it
  came from, so anyone who sent the header picked their own counter and this control did not exist.
  Two rules now, and both are needed — the header is read **only** when the socket peer is in
  `app.rate-limit.trusted-proxies` (**empty by default**, so by default the header is ignored), and the
  chain is walked **right to left**, because nginx's standard `proxy_add_x_forwarded_for` *appends* to
  whatever the client sent, which makes the leftmost entry attacker-controlled even with a real proxy in
  front. **Do not "fix" this with `server.forward-headers-strategy=framework`**: Spring's
  `ForwardedHeaderFilter` does overwrite `getRemoteAddr()` from that header but has no trusted-proxy
  notion at all, so it would spread the hole instead of closing it. Startup logs which mode is active —
  behind an unconfigured proxy every client shares one bucket, and login cuts off at 5 attempts total.
- **Per-account throttling** (`AccountAttemptThrottle`, F18) on admin login, customer login and admin
  forgot-password, because per-IP is blind to an attacker spread across many addresses. Same Caffeine
  pattern, but called from `AuthController` where the email is already parsed — no body reading in a
  filter. The key includes the **store id** (emails are per store). Logins count *failures* and a
  success clears the counter; forgot-password counts every call. **Counting happens whether or not the
  account exists**, deliberately: that endpoint answers 200 either way so as not to reveal which emails
  are registered, and a 429 that only appeared for real ones would reveal exactly that. 429s from here
  go through `TooManyRequestsException` and come out with the same body and `Retry-After` the filter
  writes by hand.
- Testing note for both: **surefire sets the limits absurdly high** because
  `IntegrationTestSupport.loginAdmin` hits the real login endpoint and almost every test uses it —
  production's 5/min would break the suite intermittently. `RateLimitTest` sets its own tiny limits and
  separates counters with `req.setRemoteAddr(...)`; it used to use `X-Forwarded-For`, which no longer
  does anything by default. `TrustedProxyRateLimitTest` and `AccountThrottleTest` need their own
  contexts because they need different properties.
- **JWTs are revocable** (`TokenVersionService`, A5). `users.token_version`/`customers.token_version`
  (V14) is carried in the token's `tv` claim and compared on every authenticated request through a
  60s Caffeine cache. Bumping the version — deactivating, deleting or re-roling a user, and any
  password change or reset on either side — evicts the cache entry too, so revocation is immediate in a
  single process. **With more than one instance it would lag by up to the TTL**; there is no
  multi-instance deployment yet. A token with no `tv` claim counts as version 0 (the column default), so
  the change didn't log anyone out. An invalid/revoked token yields **401** (see below); it used to yield
  403.
  *This bullet used to claim "any password change/reset" and that was aspirational*: until F22 **no
  customer flow bumped anything**, so a stolen customer token survived its owner changing the password —
  the one action a victim takes to stop it. `TokenVersionService.revokeCustomerTokens` had existed since
  A5 with **zero callers** (so had `revokeUserTokens`; the three admin sites all bump inline instead, and
  `revokeUserTokens` is where that should live if anyone unifies them). `CustomerServiceImpl.updatePassword`
  now calls it, **after** validating the current password — before, and anyone holding the token could
  evict the owner by guessing. `AdminCustomerServiceImpl.deleteCustomer` now evicts too, matching what
  `UserServiceImpl.deleteUser` already did: with the version cached, a deleted customer's token keeps
  *authenticating* for up to the TTL (what then fails is the row lookup, not the auth — the symptom is a
  404, not a 401). The endpoint still answers 204 with no new token, so changing your password ends your
  own session as well; when the SPA grows a customer surface, issue a fresh token there (after the bump).
  `security/CustomerTokenRevocationTest` is the first coverage of both `/api/customer/account` and the
  customer half of A5 — `SecurityHardeningTest`'s revocation cases are all admin-side.
- **Customer addresses: creating one never worked, and `isDefault` was ignored** (F23). Two bugs in one
  service, and the first was found by the test written for the second:
  - `createAddress` never set `store`, and `shipping_addresses.store_id` has been `NOT NULL` since V8
    (multi-tenancy). This service is the **only** writer of that table, so `POST /api/customer/addresses`
    always answered 500 on a constraint violation — the endpoint had never worked. The store is taken from
    `customer.getStore()`, not `TenantContext`, so an address can't land in a different store than its
    owner.
  - `applyCommonFields` copied the eleven text fields and skipped `isDefault`, while the
    `if (command.isDefault())` that unsets **the others** was there in both create and update. So the
    destructive half of the operation ran, and the constructive half didn't: asking for "make this the
    default" **deleted the existing default** and marked nothing, leaving the customer with none. And a
    `false` on the current default was ignored, so the flag could only ever be turned on, via the separate
    `POST .../default` endpoint. The root cause is a faithful-but-partial port: `unsetOtherDefaults` ports
    `ShippingAddress::booted()`'s `saving` hook, which does only the "unset the others" half and worked in
    Laravel because mass assignment had already set `is_default` from the request. The effect survived the
    port; its cause didn't.
  "At most one default per customer" is enforced in the service, not the database — the three paths that
  write the flag (create, update, `setDefaultAddress`) share one implementation and the tests assert
  "exactly one". A partial unique index on `(customer_id) WHERE is_default` would make it unbreakable but
  requires an explicit `flush()` after unsetting, or Hibernate may flush the new default's INSERT first and
  trip the index even though the end state is valid. Deleting the default promotes nobody, and the first
  address isn't auto-defaulted — neither did the original. `customer/CustomerAddressTest` is the first
  coverage of this surface at all.
- **Admins are invited, not handed a password.** Until now the only way to create an administrator was for
  the creator to **invent that person's password** and pass it along out of a band — so the creator knew it
  forever, and so did whatever channel it traveled through; nothing forced a change, and nothing recorded
  whether one happened. Not a vulnerability: credential hygiene, on the surface where refunds are issued and
  gateway credentials are configured.
  The apparatus was half-built: `users.invitation_token` UNIQUE since V4, `invitation_sent_at`, an
  `invitation_accepted_at` that **was never written**, `UserRepository.findByInvitationToken` with zero
  callers, and `createUser`'s `sendInvitation` branch — but no email, no acceptance endpoint, and
  `UserForm.tsx` sent `sendInvitation` **hardcoded to `'false'`**, so the flag was only reachable by calling
  the API by hand. Same shape as F11's `isOnSale='false'`, which is why the form change is part of the fix
  rather than a nicety.
  Now: with the switch on (**default when creating**) the account is born with `password = null` and a
  one-time token, the email goes out from `UserServiceImpl` (failures logged, never rolled back — the admin
  is created and the token is valid, so a dead SMTP must not undo the work), and
  `POST /api/admin/auth/accept-invitation` sets the password, clears the token, **writes
  `invitation_accepted_at`** and returns a session carrying the role's permissions, like login does. TTL 7
  days (`app.admin-invitation.ttl-days`), shorter than the customer's 30 because this hands over the panel.
  **V22 drops `users.password`'s NOT NULL** so a credential-less account can exist — NULL means "no
  credential yet", and such an account cannot log in because the encoder rejects by length before comparing.
  `customers.password` has been nullable since V2 for the equivalent reason, so this aligns the two halves.
  Note for anyone touching `createUser`: `passwordEncoder.encode(null)` **does not throw** in Spring
  Security 7 (`AbstractValidatingPasswordEncoder.encode` returns null), so what used to flag a missing
  password was only the column's NOT NULL, as a 500. With V22 that net is gone — the explicit check in
  `createUser` is what now stops a silent 200 creating an admin nobody can ever log into.
  **The landing page does not exist yet** (that's PROD-06). The email links to `/admin/aceptar-invitacion?token=…` and
  `router.tsx` has no such route — the invited admin gets a 404, and the only way to finish the flow today
  is to POST the token and password by hand. The backend contract, the panel switch and the email are
  done; the one screen that consumes them is not. Same for the customer half (`/cuenta/activar`, F24),
  which has the additional problem that there's no storefront account UI to land in at all.
  Also still missing: **resending an expired invitation from the panel** — if the TTL runs out, the only
  way back is to delete the user and create them again — and customer password recovery (see the F24
  bullet below).
- **A guest buyer can turn that email into an account** (F24). The checkout already generated an
  `invitationToken`, a timestamp and `INVITED` (`markInvitedIfGuest`) — and that was all: no email went
  out and no endpoint accepted it, so the email was **burned** in that store (register rejects it because
  the row exists, login demands `ACTIVE` with a password). Five pieces existed, two were missing:
  `CustomerRepository.findByInvitationToken` had zero callers and `AccountStatus.INVITED` was written in
  one place and read in none. Now `CustomerInvitationEmailListener` (its own listener on `OrderPlacedEvent`,
  same async/AFTER_COMMIT scaffolding as the other mail listeners) sends the link, and
  `POST /api/customer/auth/accept-invitation` takes token + password, activates, and **consumes the token**.
  Expiry comes from `invitation_sent_at` + `app.customer-invitation.ttl-days` (30) — no migration, the
  column was already there. Rate-limited by IP; **not** by account, because what arrives is a token, not
  an email to key on. As with the admin half, **the `/cuenta/activar` the email points at doesn't exist**
  — and here it can't until the storefront grows an account surface, so this endpoint stays contract-only
  for now.
  Note on the finding's other option ("don't create an INVITED state without a mechanism"): it would not
  have fixed anything. The lockout comes from `customerRegister`'s `existsByStoreIdAndEmail`, which ignores
  status and password — dropping INVITED leaves the guest as GUEST, and the email is just as unusable.
  And on why registration doesn't simply **claim** a passwordless row: nobody verifies email ownership at
  guest checkout, so anyone who knew the address could get there first and take the buyer's name and phone.
  The token in the mailbox is what proves ownership.
  The **admin** invitation was the other half of the same dead apparatus and was out of scope here; it is
  now implemented — see the "Admins are invited" bullet above. **Customer password recovery is still
  dead**: `Customer` has `passwordResetToken`/`passwordResetExpiresAt` and there is no customer
  forgot-password endpoint, only the admin one. Also still unused in `CustomerRepository`:
  `findByEmail`, `existsByEmail`,
  `countWithOrders`, `countByCreatedAtAfter` — the first two and last two queries **without `storeId`**,
  which is a cross-tenant leak waiting for a careless caller.
- **401 vs 403 is now defined** (`ApiSecurityErrorHandlers`, F19), and the distinction matters to the SPA,
  not just to purists. There are three cases:
  - **No token, expired, revoked, or issued for another store → 401**, with `WWW-Authenticate: Bearer`.
    The chain had no `exceptionHandling(...)` at all, so Spring Security's default answered **403** to
    unauthenticated requests — and `adminApi`'s interceptor only logs out on 401, so an expired session
    left the SPA holding a dead token: `RequireAdminAuth` never fired (there *was* a token) and every
    screen showed errors with no way back to the login but clearing `localStorage`.
  - **Authenticated with the wrong role** (a customer token on `/api/admin/**`) **→ 403**, from the new
    `AccessDeniedHandler`. Same status as before, but now with an `ApiError` body — the default one wrote
    Boot's error JSON, which has no `message`, so the panel displayed axios's raw
    "Request failed with status code 403".
  - **Authenticated admin missing a permission → 403**, unchanged, from `@PreAuthorize` through
    `GlobalExceptionHandler`. **This is why the SPA must not log out on 403**: a restricted admin opening
    a section that isn't theirs gets a legitimate 403, and bouncing them to the login would loop.
  `ExceptionTranslationFilter` already tells the first two apart (`AuthenticationTrustResolver`); the only
  thing missing was declaring what to answer. `AuthStatusTest` pins all three side by side.
  Note for anyone writing a handler like this: the `ObjectMapper` bean in this context is **Jackson 3**
  (`tools.jackson.databind`, Boot 4's default) — the loose `new ObjectMapper()` instances in
  `MercadoPagoServiceImpl` and friends are Jackson 2 (`com.fasterxml`), which is not injectable here.
- **Uploads are validated by magic bytes** (`UploadedImageValidator`, A8), not by the client's
  filename or declared content type: JPEG/PNG/GIF/WEBP only, and the stored extension is derived
  from the detected type. It lives in the storage layer, so all six upload endpoints funnel through
  it. `/uploads/**` is public and same-origin, so responses also carry `X-Content-Type-Options:
  nosniff` (set in `SecurityConfig`, whose chain covers static resources). **Test fixtures must use
  real image bytes** — `IntegrationTestSupport.pngBytes()` exists for that; a string named `.png`
  is now correctly rejected.

## Architecture

### How to read the `C5` / `A1` / `M7` / `B6` / `G4` / `F19` references

Most of what follows explains *why* the code is the way it is by citing the finding that forced it.
There have been **three** review rounds, and their IDs are not interchangeable:

| labels you'll see in code                                                | round                                                                                                                       | document                                                                                  | status      |
|--------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|-------------|
| `C1`–`C5`, `A1`–`A8`, `M1`–`M10`, `B5`–`B8`, `R1`, `G1`–`G6` (no hyphen) | first robustness audit plus its annex — `R1` async/mail executor, `G1`/`G2` scheduled jobs, `G4` refunds, `G5` store status | **not in this repo**; `docs/analisis-critico-2026-09.md:7` lists them as already-existing | closed      |
| `F01`–`F24`                                                              | independent audit, numbered straight through (severity only in its table)                                                   | `docs/auditoria-independiente-2026-09.md`                                                 | **closed**  |
| `PROD-01`–`PROD-30`                                                      | **not an audit** — the production readiness backlog, P0/P1/P2                                                               | `PLAN_PRODUCCION.md` §5                                                                   | in progress |
| `C-1`, `A-1`, `M-1`… (**hyphenated**)                                    | `docs/analisis-critico-2026-09.md`, a separate review with its own numbering                                                | that document                                                                             | —           |

**The hyphenated IDs do not map onto the code labels.** `A-1` in `analisis-critico` is the rate
limiter; `A1` in the code is admin permissions. No comment or line in this file uses the hyphenated
form — if you see `A1`, it's always the first audit's label.

**A reference in a comment or in this file is not an open task — it's the record of why an
invariant exists.** Before removing or simplifying anything marked that way, find the test that
pins it: nearly every finding left one that fails when the fix is undone.

Three things worth knowing about the `F01`–`F24` round:
- Several findings were **misjudged by the audit itself**. `F18` was filed Medium and is High (the
  rate limiter it describes could be bypassed by anyone who sent a header). `F17` was understated.
  `F21`'s described damage had largely been closed already by `F13`. And **`F24`'s first prescribed
  remedy would not have fixed the problem it describes** — the note on that bullet explains why.
  Where a bullet in this file states a judgement, that's the one that was actually applied.
- **`docs/audit.md` is a truncated duplicate** of the independent audit — it stops after `F01`.
  The complete list is in `docs/auditoria-independiente-2026-09.md`.
- The **admin-panel invitation** ("Admins are invited", under Local environment) is **not** an audit
  finding. It's later work, born from a gap noted while closing `F24`.

Note on layout, because it's not obvious: the **per-finding bullets live under "Local environment"**
above (they grew out of that list) while the **longer prose blocks are in "Backend" below**. Both
cite the same IDs; there's no significance to which section a finding landed in.

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
Each domain has matching `repository/`, `service/<domain>/` (interface + `*Impl` + DTOs/records), and `controller/<domain>/` packages, plus `controller/admin/**` (full admin panel backend: products, categories, attributes, customers, orders, coupons, shipping zones/methods/rates +
carrier credentials, payment gateway config, users/roles, home banners, store/general settings,
and sales/products/payment-methods reports with date-range filters and CSV export),
`controller/customer/**` (account + addresses, JWT), `controller/platform/**` (store onboarding,
`X-Platform-Key`), and `controller/auth` (admin/customer JWT login+register, password reset).

**A payment is only accepted if the amount matches** (M4). `OrderStatusService.markPaid` takes the
amount the gateway says arrived and compares it against `order.getTotal()` before anything is marked
paid — none of Stripe, Webpay or MercadoPago did that; they each read the payment's *status* while
the amount sat unused in the SDK response. A mismatch (including an unknown amount) leaves the order
**PENDING** with a note in `order_status_history` and a Sentry message, the same pair
`OrderInventoryService` uses. The check lives inside `markPaid`, not in the five callers, so no
gateway can skip it. Comparison is exact, no tolerance: CLP has no cents.

Closed by F17: `PaymentServiceImpl` still sends `setUnitAmount(order.getTotal())` in whole units, which
is only right for a zero-decimal currency, and the amount check uses the same convention — so a wrong
currency would have been a wrong charge that *passed* the check, since the comparison is blind to the
unit. The currency is no longer free text: `SettingValues.SUPPORTED_CURRENCIES` is the one place that
says which currencies exist (today: CLP only), the `currency` setting is validated against it on writing,
and `Money`'s scale-0 constant plus MercadoPago/Webpay's hardcoded `"CLP"` are documented as moving with
that catalogue. Supporting a second currency means changing all four together, not just the setting.

**Money settings can't be saved broken** (F17). `tax_rate`, `currency`, `default_shipping_cost` and
`free_shipping_threshold` are text in a key/value table read by five consumers, and nothing used to
check them: `tax_rate=abc` left quoting and checkout answering 400 with BigDecimal's internal message —
and a 400 doesn't reach Sentry, so the store stopped selling with nobody alerted. Worse were the valid
numbers: a negative rate charged *below* the product price silently, and `-100` with tax-inclusive
prices divided by zero. `SettingValues` is now the single place that decides what a valid money setting
is, **both on writing and on read** — validating the PUT doesn't heal stores that already stored garbage.
Two parsers had also drifted apart (`Double.parseDouble` when pricing vs `new BigDecimal` in
`/cart/calculate` and `/checkout/config`, which disagree on `" 19 "`), so the same setting was valid or
invalid depending on the entry point; there is one reader now. A stored value that won't parse fails
loudly with a Sentry message rather than falling back to 19%, which would invent a tax for a store that
may be exempt.

**Reports report net money, in Chilean time** (F20). Two classes own the decisions the three report
services used to make each on their own:
- `ReportRevenue` — how much an order actually left. Every report summed `Order.total` for `PAID` orders,
  and **a partial refund keeps the order PAID** on purpose (`RefundService.finish`), so refunded money
  kept counting as revenue with no symptom at all: the figure was simply higher than reality. It also owns
  the **per-line split**, because `OrderItem` has no discount column — the coupon lives only on
  `Order.discountAmount`, so product/category reports showed more than the customer paid. The remainder
  goes to the last line, so the parts sum to the order's net exactly the same rule F06 set for tax.
  **Refunds are attributed to the order's date, not the refund's** — a November refund of an October sale
  lowers October.
- `ReportZone` (`app.reports.timezone`, default `America/Santiago`, invalid value fails startup) — owns
  **both** the range bounds (`ReportDateRange`, used by all three controllers) and the day label
  (`dateKey`). Both were UTC, so a Chilean store's "1–31 Oct" report actually ran from Sep 30 21:00 to
  Oct 31 20:59: end-of-month evening sales fell out of that month and into the next. The upper bound is
  now **exclusive** (it was `23:59:59` against an inclusive query, which lost sub-second orders). It's a
  platform property rather than a per-store setting because every store is Chilean today — F17's currency
  catalogue only admits CLP. When that changes, this bean is where it changes.
- The three report services had **no tests at all** before this (`report/*Test` is the first net), and
  they still **aggregate in memory**: all orders in range with their items, and
  `ProductsReportServiceImpl.getProductsData` paginates with `subList` after loading everything. Slow,
  not wrong; moving it to SQL aggregates is deliberately left out, so a performance rewrite never gets
  mixed with a change that corrects figures.

**MercadoPago's webhook is signature-verified** (M3). `x-signature` (`ts=…,v1=…`) is checked against
the HMAC-SHA256 of `id:<data.id>;request-id:<x-request-id>;ts:<ts>;` with the store's `webhookSecret`
credential, constant-time, **before** the outbound `PaymentClient.get()` — rejecting afterward would
still let anyone burn the merchant's API quota, which is the finding. Enabling MercadoPago without
that secret is refused at configuration time (`AdminPaymentGatewayServiceImpl`), so verification is
never silently off; a store with no secret answers 401 like any bad signature rather than revealing
it isn't configured. The endpoint is also rate-limited (`app.rate-limit.webhook`).

**Every /api/admin endpoint is permission-checked** (A1). `@EnableMethodSecurity` was already on but
unused; now all **109 endpoints across 19 controllers** carry `@PreAuthorize("hasAuthority('...')")` —
`GET` needs `dominio.view`, everything else `dominio.manage`. The catalogue is 23 permissions seeded
globally by `V15` (`Permission` has no `store_id`; `Role` does, `UNIQUE(store_id, name, guard_name)`).
`AuthController.adminAuthorities()` already put permission names in the JWT, so the annotations
needed no plumbing. **No role means no permissions**, so V15 also creates an "Administrador" role per
store and assigns it to every existing admin — without that backfill, enforcing the annotations locks
everyone out of their own panel. For the same reason `IntegrationTestSupport.createAdmin` now grants
a full-access role, and `createAdminWithPermissions(store, prefix, "products.view")` builds a
restricted one. Those 19 are the guarded admin subset, not every controller in the project (there
are 37 `@RestController` classes in total — the rest are public storefront, customer, POS, platform
and auth surfaces, each with its own filter). Three of the 19 live in `controller/settings/**`, not
`controller/admin/**` — easy to miss when adding endpoints. On the frontend, `NAV_ITEMS` entries and
admin routes declare a `permission` (`handle.permission` in `router.tsx`), checked once in
`AdminLayout` via `useMatches()`; that's cosmetic only, the server is the enforcement.

**Stock and coupon uses are never written with read-modify-write** (C5). `Product.stock`,
`ProductVariation.stock` and `Coupon.timesUsed` move only through the conditional `@Modifying`
queries in their repositories — `decrementStock` carries `AND stock >= :quantity`, `claimUsage`
carries `AND (usageLimit IS NULL OR timesUsed < usageLimit)`, and **the affected-row count is the
only trustworthy result**: 0 means someone else got there first. Reading the entity, comparing in
Java, and saving it back lets two concurrent payments both sell the last unit. The database backs
this up with `CHECK (stock >= 0)` on both tables (V13). All of the order-lifecycle stock movement
lives in `OrderInventoryService`, guarded by `orders.stock_applied`/`stock_restored`, because four
separate paths can cancel an order and a second cancellation would otherwise invent inventory.
`StockConcurrencyTest` is the only test in the project that runs real concurrent threads — it
deliberately does not extend `IntegrationTestSupport`, whose per-test transaction would hide the
race.

Both gaps that used to be listed here are now closed. `PosWebhookServiceImpl.handleStockUpdated` no longer overwrites stock blindly: it uses `ProductRepository.setStockIfUnchanged`, conditioned on the `oldStock` the POS says it saw, so a concurrent web sale can't be erased — a mismatch is reported as a divergence (same wording and Sentry alert as `PosOrderNotifier.checkStockDivergence`, which already treats our own decrement as the authority for web stock) instead of written (F13). And marking an order paid from the admin panel now goes through `OrderStatusService.markPaid`, so it publishes `PaymentConfirmedEvent` and does decrement stock (F07).

**POS webhooks can't be replayed** (F21). HMAC plus a ±300s freshness window proves a request is authentic
but not that it's *new*: inside that window a captured request replays fine, since body and timestamp are
unchanged. `PosWebhookAuthFilter` now remembers what it already served, keyed by `(companyId, signature)` —
**the signature is itself the event id** the finding asked for (HMAC of body+timestamp with the merchant's
secret), so no change to the UvoPOS contract was needed. Two things to know:
- The check runs **after** signature verification, deliberately. Recording first would let anyone burn the
  genuine signature or fill the store with garbage, without knowing the secret.
- The store is a Caffeine cache expiring just past the freshness window — nothing older can be replayed
  anyway, so there's no table to prune. **A restart inside those 300s loses the guard, and with more than
  one instance each would remember its own**; the same issue as `TokenVersionService`'s cache, and the move is
  to a table with `UNIQUE (company_id, signature_hash)` when that day comes.

What a replay actually did before this is worth recording because it is not what you'd guess: for
`stock.updated` F13's conditional UPDATE already refused it (the stock is already the new value) — so no
double-apply, but a **false divergence alert to Sentry** on every replay. `stock.alert`/`product.created`
only log. The one that genuinely hurt was `product.updated`, which rewrites price/name/description
unconditionally.

Still open in the same area, two things:
- `PosSyncServiceImpl` writes an absolute stock too, but it's the create-or-update of a whole product rather
  than a reaction to an inventory movement, and it carries no `oldStock` to compare against — coordinating
  that one is the next step.
- **`product.updated` overwrites later edits.** A legitimate late or out-of-order webhook silently reverts an
  admin's price correction — the same shape as the bug F13 fixed for stock, in the branch F13 didn't touch.
  The replay guard bounds it to replays, not to late deliveries. The obvious fix (compare
  `product.updatedAt` against `mapping.lastSyncedAt`) is too fragile to bolt on: `markSynced` and the
  product `save` land milliseconds apart, so that comparison is already true right after a legitimate sync.
  It needs per-field sync marks, or an `oldValue` in the payload like stock has.

`OpenApiConfig` wires springdoc — API docs at `/swagger-ui.html`, grouped into five surfaces
(public/admin/customer/pos/platform) with a `bearerAuth` JWT scheme wired to the "Authorize"
button. Error tracking is `io.sentry:sentry` (the **core SDK**, not
`sentry-spring-boot-starter-jakarta` — that starter's autoconfiguration references a class Spring
Boot 4 moved/removed and fails to load; see the comment in `pom.xml`). Initialized manually in
`UvoStoreApplication.main()` from `SENTRY_DSN`; a blank/unset DSN leaves it inactive.
`GlobalExceptionHandler`'s catch-all reports unhandled exceptions to Sentry (and logs them
locally) without leaking internals to the client.

**Which exception to throw** (M1): a rule the caller broke, in words meant for them, is
`BusinessException` → 400 with the message. A request that can't be served but wasn't wrong is
`OutOfStockException`/`ShippingUnavailableException` → 409. Anything else — a wrapped SDK error, a
failed decryption — stays `IllegalStateException` and falls to the catch-all: 500, nothing leaked,
Sentry notified. Don't put `IllegalStateException` back on the 400 handler; that mapping is what
sent Stripe's raw error text to customers and kept those failures out of Sentry.
`IllegalArgumentException` does map to 400, and every use of it here is genuine input validation.

**Sorting from the client** (M8): the admin listings take a `sortField` query parameter. It must go
through the controller's `ALLOWED_SORTS` set with a silent fallback to `createdAt` — passing it
straight to `Sort.by(...)` makes every column of the entity orderable, the password hash included.
`ProductController:25,52` is the reference shape.

**Lazy collections** (M7): `Product.productImages`, `Product.variations` and
`ProductVariation.attributeAssignments` carry `@BatchSize`, which is what keeps a listing page at a
constant number of queries. Not `JOIN FETCH`: the listings are `findAll(spec, pageable)`, and a
fetch join over a collection with pagination makes Hibernate page in memory. `@OneToOne(mappedBy)`
is the other trap — it can't be proxied, so it costs one SELECT per row no matter what `fetch` says.
`ProductListingQueryCountTest` fails if any of this is undone.

**Database**: PostgreSQL via Flyway, schema in `src/main/resources/db/migration/` — **23 migrations,
currently up to `V23__store_domain_verified.sql`**, well past the original catalog/settings ones.
Everything from V8 on is hardening or new capability rather than the original port: V8 stores, V11
store domains, V12 password reset, V13 stock/coupon integrity, V14 token versions, V15 the
permission catalogue, V16 the listing indexes, V17 TIMESTAMPTZ + the enum CHECK constraints, V18
store status, V19 order refunds, V20 `order_items.stock_applied`, V21 refund intent, V22 nullable
`users.password` (admin invitations), V23 `stores.domain_verified_at` (PROD-04).
`spring.jpa.hibernate.ddl-auto=validate`, so any entity change must be paired with a new Flyway
migration — **never edit an already-applied one**, add the next number (`V24__...sql`).

**Enum columns** (B6): every column that stores an enum name carries a `CHECK` listing its values
(V17). Adding a constant to an enum therefore needs a migration too — otherwise the new value is
rejected by the database at runtime, not at compile time. `stores.status` is the exception and is
deliberately unconstrained: it looks like an enum but is a free-form `String` (`Store.java:53`).

**Time zones** (B5/B7): timestamps are `TIMESTAMPTZ` and Hibernate writes them with
`hibernate.jdbc.time_zone=UTC`, so a value doesn't change meaning when the server's zone does. Any
new date column follows suit — `TIMESTAMP` without a zone is the bug V17 removed.

**Uploads** (M10): `uploads/` is gitignored — the images are store data, not code. That also means
**git is no longer a copy of them and nothing else is either**: the directory is untracked *and*
ignored, which is exactly the state a `git clean -fdx` or an IDE cleanup wipes without asking. It
already happened once (2026-09-07), and the files were only recoverable because they still sat in
the commit before `7294c0d`. That safety net expires as history ages. In production the path needs a
persistent volume or `app.storage.driver=s3`; in development, back it up or accept losing it.

Tests must never write there: `app.upload-dir` is pointed at `target/test-uploads` in the surefire
config, so `mvnw verify` can't touch real store images and its leftovers die with `mvnw clean`. Any
test that resolves upload paths reads that property rather than hardcoding `uploads` — see
`AdminProductImageTest`, which used to hardcode it and cleaned up inside the real directory.

**Filters** (B8): the six security filters are `@Component`, which would auto-register them in the
servlet chain on top of where `SecurityConfig` puts them. `FilterRegistrationConfig` disables that
auto-registration, so `SecurityConfig` is the single place the order is decided. A new filter needs
its `setEnabled(false)` registration there too.

**Coverage**: `./mvnw verify` writes a JaCoCo report to `target/site/jacoco/index.html`. There is no
enforced threshold yet — the baseline is 63.7% of lines (2026-09-07), with `service.report` the
thinnest area at 9.2%. **Both figures predate the F01–F24 round**, which roughly quintupled the
suite and gave `service.report` its first tests at all (F20); re-measure before quoting them.

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
  `/api/admin/auth/forgot-password` + `/reset-password` for admin password recovery (email sending
  is graceful-degrade — see `EmailService` below). The customer side has the columns and **no
  endpoint**: there is no customer forgot-password.
- `POST /api/admin/auth/accept-invitation` and `POST /api/customer/auth/accept-invitation` — token +
  chosen password, both one-time, both returning a logged-in session. Same shape, different TTLs
  (`app.admin-invitation.ttl-days` 7, `app.customer-invitation.ttl-days` 30) and different origins:
  the admin's is created by `UserServiceImpl.createUser`, the customer's by a guest checkout. Both
  are IP-rate-limited; neither is account-throttled, because what arrives is a token, not an email
  to key on. See the invitation bullets above.
- `/api/admin/**` (`ROLE_ADMIN`) and `/api/customer/**` (`ROLE_CUSTOMER`) are guarded; `/api/v1/**` is fully public.
- `/api/platform/**` (store onboarding) uses `X-Platform-Key`, not JWT — see "Multi-tenancy".
- POS integration (`/api/sync/**`, `/api/webhooks/pos/**`) uses its own HMAC/API-key filters, separate from JWT.

### External integrations — all "off until configured"
Every external integration in this codebase follows the same pattern: a blank/missing env var leaves it inactive (log-and-skip, never throw), so the app runs fully in dev/CI without any of these configured.
Applies to: `EmailService` (SMTP, `spring.mail.*` — password reset, order confirmation, and the customer and admin invitations), Stripe/Webpay/MercadoPago (`PaymentGatewayConfig` per store, plus shared Webpay parent commerce code in `application.properties`), Chilexpress/Correos de Chile
shipping quotes, S3 file storage (`app.storage.driver=s3`), and Sentry (`SENTRY_DSN`/ `VITE_SENTRY_DSN`). **Webpay/MercadoPago/Stripe are fully wired (backend + checkout UI) but have never been tested against real sandbox credentials** — don't assume they work end-to-end without that verification.

**Shipping is priced from region + commune, and the checkout refuses an address it can't reach**
(A7). `ShippingRateServiceImpl.findZone` matches a zone by **exact string** against the free-text
`regions`/`communes` arrays an admin typed into `ZoneForm`, so the storefront must offer those exact
values — that's what `GET /api/v1/shipping/coverage` is for. Until this existed, the SPA sent no
region at all, no zone ever matched, and `.orElse(ZERO)` made **every order ship for free** without a
word. `CartTotals`/`CartCalculationResult` now carry `shippingAvailable` (false = the store ships
but not there, which is not the same as free) and `couponApplied`; `CheckoutServiceImpl` throws
`ShippingUnavailableException` → 409 rather than creating an order nobody can dispatch. Note for
tests: a store with no zones can't ship, so any test that checks out without caring about delivery
calls `IntegrationTestSupport.disableShipping(store)`. `default_shipping_cost` is still read only by
`/checkout/config` and has no effect on pricing.

### Frontend (`frontend/`)
Vite + React 19 + TypeScript + Tailwind v4 (`@tailwindcss/vite`, CSS custom properties for the runtime-configurable theme colors from `store-settings`) + React Router + Zustand + axios + shadcn/ui (admin panel only).

**Routes are code-split by audience** (A6). The whole app used to build into one 1.14 MB chunk, so
landing on the storefront downloaded the entire admin panel and `recharts` first. `router.tsx` now
loads everything under `/admin` and `/plataforma` — plus `/checkout`, the only place zod and
react-hook-form are used — through React Router 7's own `lazy` property (not `React.lazy` +
`Suspense`; with `createBrowserRouter` the router handles the pending state). The rest of the
storefront stays eagerly imported: it's the hot path and its pages are small. `vite.config.ts` pulls
React and the router into a `react-vendor` chunk so a returning visitor reuses it across deployments —
note this project builds with **Vite 8 / rolldown**, so it's `build.rolldownOptions.output
.advancedChunks`, not rollup's `manualChunks`. Adding a heavy dependency to a storefront page
undoes this, so check the chunk sizes after (`npm run build` prints them).

**Checkout validation is zod + react-hook-form** (`pages/checkoutSchemas.ts`), the only place in the app using them — every other form validates with native `required` attributes. It replaced two truthiness booleans that accepted a single character in every field and any string as an email.

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
- **Testing**: **441 backend `@Test` methods across 80 classes** (JUnit + `IntegrationTestSupport`,
  one Spring-managed transaction per test, auto-rolled-back — `AFTER_COMMIT` listeners never fire
  under this setup, which is intentional) and **125 frontend tests across 27 files** (Vitest +
  React Testing Library, `npm run test`), both wired into CI (`.github/workflows/ci.yml`) and the
  release workflow (`.github/workflows/release.yml`, triggered by pushing a `vX.Y.Z` tag — builds
  artifacts and publishes a GitHub Release with auto-generated notes). Most of that growth is the
  audit rounds: nearly every `C`/`A`/`M`/`B`/`F` finding left a test that fails when its fix is
  undone, so a red suite after a refactor is usually an invariant being removed, not a flaky test.
  Three recurring devices worth knowing before writing a new one:
  - **`disableShipping(store)` before any checkout.** A fresh store covers no shipping zone, so
    checkout legitimately answers 409 (A7). Tests that don't care about delivery call this.
  - **Package-visible builder methods to assert outbound payloads** without SMTP or network:
    `MercadoPagoServiceImpl.buildPreferenceRequest`, `PaymentServiceImpl.buildSessionParams`,
    `CustomerInvitationEmailListener.body`, `UserServiceImpl.invitationBody`. That's why those
    tests live in the service's own package.
  - **Tokens are read from the database, not a mailbox.** There is no SMTP in tests and
    `EmailServiceImpl` logs and skips, so `PasswordResetTest`, `CustomerInvitationTest` and
    `AdminInvitationTest` all query the row. When a test needs to age a timestamp the service
    itself just wrote, it does so with a native `UPDATE` — and **`flush()` before `clear()`**, or
    `clear()` discards the endpoint's own pending writes and the row reads as if nothing happened.
- **`./mvnw test-compile` without `clean` can report BUILD SUCCESS over stale classes.** After a
  signature change in `src/main`, the incremental compiler happily left the old `target/test-classes`
  in place and said nothing; `./mvnw -o clean test-compile` then reported 13 real errors. When you've
  changed a method signature or a constructor, **don't trust an incremental green** — use `clean`.
- **`frontend/src/pages/Checkout.test.tsx` can time out (~15 s) in a loaded full-suite run** and
  passes on its own. It's the heaviest test file in the suite; a single failure there with a
  duration near the timeout is that, not a regression — re-run before chasing it.
- **`npx tsc --noEmit` in `frontend/` passes unconditionally and proves nothing.** `tsconfig.json`
  has `"files": []` and only `references`, so `--noEmit` compiles zero files and always exits 0 —
  it will happily stay silent on a type error that breaks the build. **The real check is
  `npx tsc -b`** (what `npm run build` runs). Run the full trio before calling frontend work done:
  `npx tsc -b`, `npm run lint`, `npm run test`. Skipping lint is how a
  `react-refresh/only-export-components` error shipped once — `tsc` and Vitest were both green.
- **No deployment infrastructure exists yet** (no Docker, no reverse proxy, no SSL) — paused
  pending a hosting decision. Client custom domains and same-origin frontend+API in production
  both depend on that piece landing eventually.
- **The Laravel/Vue predecessor lives outside this repo** at `C:\Users\jorgemc\Desktop\uvostore_1.0`.
  Its Vue SPA (`resources/js/`) was dead code (never mounted) — the real reference for "what
  should this page do" was always the Blade views + `app.js` + the Livewire home component, not
  the Vue files.
- **Sample/demo data**: `docs/guia-datos-demo-presentacion.md` has a full set of fictional store/product/banner/coupon data (with the exact admin API payloads) for spinning up a presentable demo store from scratch.
