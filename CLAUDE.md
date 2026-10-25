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
./mvnw clean verify             # build + full test suite + JaCoCo report + line-coverage floor
./mvnw test                     # run tests only
./mvnw test -Dtest=ClassName    # run a single test class
```
On Windows use `mvnw.cmd`. Add `-o` (offline) once the local repository is warm — the suite boots a
Spring context per test class, and dependency resolution is the slowest part of a cold run.

```
cd frontend && npm run dev        # Vite dev server (http://localhost:5173), proxies /api/* to VITE_DEV_PROXY_TARGET
                                  #   (<slug>.localhost:5173 reaches that store — see "Browser E2E")
cd frontend && npm run e2e        # Playwright browser suite; needs the jar first (./mvnw -o package -DskipTests)
cd frontend && npm run build      # tsc -b && vite build
cd frontend && npm run lint       # eslint .
cd frontend && npm run test       # vitest run (npm run test:watch for watch mode)
cd frontend && npm run preview    # preview a production build
cd frontend && npx tsc -b         # type check on its own — NOT `tsc --noEmit`, see "Known gotchas"
```

## Multi-tenancy

- `TenantContext` (ThreadLocal) holds the current request's `Store`. `TenantResolutionFilter` populates it from the `Host` header: exact match on `Store.domain` first (a client's own custom domain), then a `<slug>.<platform-domain>` subdomain match as the fallback every store keeps working under regardless (e.g. `demo.localhost:8080` in dev). **PROD-05a:** the fallback accepts the host only when it is *exactly* one label plus `app.public-url.platform-domain` (`StorePublicUrlResolver.slugOfPlatformHost`, which sits next to the code that composes that same subdomain so the two can't drift) — `demo.somebody-else.com`, `a.demo.<platform-domain>` and the bare platform domain resolve nothing. Before, it took the first label of *any* hostname, and since CORS shares the resolver, a foreign origin borrowing a real store's nick got credentialed access. The custom-domain match is unchanged. Consequence for dev: a `VITE_DEV_PROXY_TARGET` or `Host` outside `*.<platform-domain>` (default `localhost`) now answers 400 "no se pudo determinar la tienda" instead of resolving by accident. This does **not** close PROD-05: the proxy must still reject unknown hosts (PROD-03/05c). `JwtAuthenticationFilter` cross-checks the token's `sid` claim against the resolved tenant — a mismatch clears the security context (and so answers **401**, F19), not a silent cross-tenant leak.
- **A repository query on a tenant entity must name the store (PROD-17b).** E-mails, slugs, SKUs, coupon
  codes, role names and setting keys are unique *per store*, not globally, so `findByEmail(String)` on
  `CustomerRepository` would return — or count — another store's customer the day someone called it
  without thinking. 45 such queries sat in 15 repositories, all with zero callers (ported 1:1 from the
  Laravel scopes: `Order::byStatus()` listed every store's orders); they were deleted, not kept "just in
  case". `multitenancy/RepositoryTenantScopeTest` now enforces it for every repository whose entity has a
  `store` field: each declared method must mention the store, take a `Long` id (`id`, `customerId`,
  `companyId`… — a lookup by a record, or by its parent, already inside one store) or be listed in
  `GLOBAL_ON_PURPOSE` **with the reason it is safe**. The ten that are: three random UNIQUE tokens, four
  keys issued by a payment gateway, and three scheduled jobs that deliberately walk every store. (The outbox
  dispatcher's queries walk every store too, but `OutboxMessage` has no `store` field — it inherits the
  tenant through its order — so the test doesn't scan that repository.) Adding a
  global query is possible; it just has to be written down. Three of those are flagged in the test as
  *not store-checked by their caller* (`UserRepository.findByPasswordResetToken` and Stripe's two
  `findByStripe…Id`) — a known gap, not an endorsement. For Stripe it is wider than the repository:
  `PaymentServiceImpl.handleWebhook` verifies the signature with the *tenant's* webhook secret but never
  compares the found order's store with the request's, and `orderFromIntent` resolves the order with a
  plain `findById` from the event's `order_id` metadata (not covered by this test — it is an inherited
  method). Whether an event signed by one store's Stripe account can name another store's order id was
  **not tested**; the Webpay, MercadoPago and tracking paths do compare the store. The test skips synthetic methods (JaCoCo's
  `$jacocoInit`, and the bridge `findById(Object)` javac generates when a repository redeclares
  `findById(Long)`).
- New stores are created via `/api/platform/**` (`PlatformApiKeyAuthFilter`, shared secret in the `X-Platform-Key` header, **reachable only from the private network** since POS-6) — called by the UvoPOS backend when a company activates the module, or by the operator tool (`/plataforma/nueva-tienda` in the frontend); not public self-service signup. Either way the owner gets an **invitation** and chooses their own password, then self-manages everything from their own admin panel. See "POS-6" below.
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
    underrate limiting. `LocalFileStorageService.publicUrl` was the last place that built a URL this
    way (image URLs came out as `http://…:8080` behind the proxy — mixed content from an https page);
    **PROD-05b** moved it to `StorePublicUrlResolver.apiUrl(store, "/uploads/…")`. It reads the store
    from `TenantContext` instead of taking it as a parameter, on purpose: all nine call sites are request
    code already inside a store (the banner and settings services read `TenantContext` in the same
    methods), the DTO mappers are static and would only forward the store, and
    `S3FileStorageServiceImpl.store()` already reads it there. Without a store it **throws** instead of
    returning a relative `/uploads/…` as it did with no request — that URL is the one that breaks the
    image in production — so a listener or job needing an image URL must run inside
    `TenantContext.runWithin(store, …)`. The S3 driver's URLs (bucket/CDN) don't depend on the store and
    didn't change. A verified custom domain now serves the images too, even when the page was entered by
    the platform subdomain. Pinned by `storage/StoreImageUrlTest` (end to end: a `Host` carrying port
    9999 must not reach the URL) and the local cases of `FileStorageServiceTest`.
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
- The frontend computes every API client's `baseURL` **at runtime** from `window.location.origin` (`frontend/src/services/api.ts`, `admin/services/adminApi.ts`, `platform/services/platformApi.ts`) — not from a build-time env var — so one deployed frontend build serves any tenant. In dev, Vite's own proxy (`vite.config.ts`) forwards `/api/*` to `VITE_DEV_PROXY_TARGET` (`frontend/.env`, default `http://demo.localhost:8080`) so the browser still sees same-origin requests, matching production. Since
PROD-14a the proxy keeps the browser's store: a page on `<slug>.localhost:5173` reaches the backend as
`<slug>.localhost:8080`, and only a bare `localhost` goes to the target store. In production this assumes frontend static assets and the API are served from the same origin (a reverse proxy) — that infrastructure doesn't exist yet, see "Known gotchas".

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
  - `PLATFORM_API_KEY` — guards `/api/platform/**` store onboarding (`PlatformApiKeyAuthFilter`), which
    since POS-6 also only answers from `PLATFORM_ALLOWED_NETWORKS`.
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
  `<slug>.<platform-domain>`, the same two-step lookup `TenantResolutionFilter` uses, shared via
  `StoreHostResolver`. Anything else gets no `Access-Control-Allow-*` header and the browser blocks
  it. `app.cors.additional-origins` is the escape hatch for an origin that isn't a store hostname
  (a separately hosted SPA build); empty by default, because in dev Vite proxies `/api/*` and
  requests are same-origin. Note when testing by hand: an `Origin` identical to the request's own
  host is same-origin and CORS never engages — use a different port.
- **Rate limiting** (`RateLimitFilter`, A4) on the six unauthenticated endpoints: admin/customer
  login, customer registration, admin forgot-password (window 5x longer — each hit sends a real
  email), order tracking, and the MercadoPago webhook. Counters in a bounded Caffeine cache; limits
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
  Since POS-6 the list is compared with `IpRanges` (Spring's `IpAddressMatcher` ignores the IPv4/IPv6
  family), and `resolveStrict` is the variant for permission decisions (the platform allowlist): where
  `resolve` falls back to the socket, it says "unknown".
- **Per-account throttling** (`AccountAttemptThrottle`, F18) on admin login, customer login, and admin
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
- **`app.customer-accounts.enabled`** (`CUSTOMER_ACCOUNTS_ENABLED`, **false**): buyer accounts, deferred
  by PROD-07. Off, every `/api/customer/**` path answers 404, guest checkout issues no invitation, and no
  invitation email goes out; guest purchase itself is unaffected. Read in exactly one place,
  `CustomerAccountsPolicy`. **Deliberately not overridden in surefire**, so the whole suite runs under
  the shipped value, and the six classes that need accounts opt in with `@WithCustomerAccounts`. Don't
  turn it on in production without the storefront screens — see the PROD-07 bullet in "Architecture".
- **`app.shipping.carriers.enabled`** (`SHIPPING_CARRIERS_ENABLED`, **empty**) and **`app.pos.enabled`**
  (`POS_ENABLED`, **false**): the other two deferred integrations, PROD-12. Empty/off, no carrier can be
  configured on a shipping method and the quoter skips any row that already has one; `/api/sync/**` and
  `/api/webhooks/pos/**` answer 404 and no order is notified to the POS — which means no tax document is
  emitted. Read only by `ShippingCarrierPolicy` and `PosIntegrationPolicy`. The POS also needs the
  store's own `pos_sync_enabled`. **Neither is overridden in surefire**; the classes that need them opt
  in with `@WithPosIntegration` / `@WithShippingCarriers`. Note `@WithShippingCarriers` must also move
  `chilexpress.quote-url` off the sandbox default, or the context refuses to start — on purpose.
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
  **PROD-06 closed the rest of the loop.** Three things it added, and one hole it found:
  - **The landing page** (`admin/pages/AcceptInvitation.tsx`, route `/admin/aceptar-invitacion`, public
    and outside `AdminLayout` like the other three). Until it existed, the email linked to a 404 and the
    only way to finish was POSTing the token by hand. On success, it logs straight into the panel with the
    permissions the endpoint returns, rather than bouncing to the login: the invitee just proved mailbox
    control and chose the password, so retyping it protects nothing. **The two bad endings say different
    things and neither clears the typed password** — the server deliberately can't tell an invalid token
    from an expired, used, or deactivated one, so that branch says "ask for a resend", while a failed
    request (no `status` on the rejection — the `adminApi` interceptor spreads the `ApiError` body, which
    carries one) says the connection failed and keeps the form intact.
  - **Resend from the panel**, `POST /api/admin/users/{id}/resend-invitation` (`users.manage`, same
    permission as creating — it's the same capability). It **issues a new token, so the previous link
    dies**; the frequency limit is a minimum interval measured against `invitation_sent_at`
    (`app.admin-invitation.resend-cooldown-minutes`, 5) rather than a Caffeine window, so it's per
    invitee, survives a restart, and the message can say how many minutes are left. Resending an
    **expired** invitation is the main use case, not an edge one: before this the only way back was
    deleting the user and recreating them, which changes their id and loses their notes. `UserDto` grew
    `invitationPending` and `invitationExpiresAt` (never the token) so the list can show the state.
  - **`AdminInvitationPolicy`** now owns the TTL and the cooldown. `ttl-days` was already being read in
    two places and the panel needed a third — three separate reads of one property is how the email
    promising seven days, the endpoint accepting five and the screen drawing something else come about.
  - **The hole**: `adminAcceptInvitation` didn't check `active`, and neither does
    `JwtAuthenticationFilter` — the login is the only place that ever did. So a **deactivated** admin
    with a pending invitation accepted it and walked away with a valid session; the `tokenVersion` bump
    from deactivating doesn't help, because acceptance mints a fresh token at the current version. One
    `.filter(User::isActive)` closes it, checked there rather than at the three sites that deactivate.
    The token deliberately survives, so reactivating someone revives their invitation.
  Also aligned: **a password reset now consumes a pending invitation**. An invitee who never accepted can
  set a password through "forgot password" — both paths prove the same mailbox, so it's not a shortcut —
  but it used to leave a live invitation link for days afterwards and `invitation_accepted_at` null, which
  made the one column that answers "did they ever set their own password?" lie.
  Still missing in this family: the **customer** half (`/cuenta/activar`, F24) has no screen either, plus
  customer password recovery (see the F24 bullet below). **PROD-07 settled that half by deferring it
  behind a flag rather than building it** — see the PROD-07 bullet below.
- **A guest buyer can turn that email into an account** (F24). The checkout already generated an
  `invitationToken`, a timestamp and `INVITED` (`markInvitedIfGuest`) — and that was all: no email went
  out and no endpoint accepted it, so the email was **burned** in that store (register rejects it because
  the row exists, login demands `ACTIVE` with a password). Five pieces existed, two were missing:
  `CustomerRepository.findByInvitationToken` had zero callers and `AccountStatus.INVITED` was written in
  one place and read in none. Now the checkout queues a `CUSTOMER_INVITATION_EMAIL` outbox row (PROD-13b;
  it was an async listener on `OrderPlacedEvent` until then) and `OrderEmailComposer` builds the link, and
  `POST /api/customer/auth/accept-invitation` takes token + password, activates, and **consumes the token**.
  Expiry comes from `invitation_sent_at` + `app.customer-invitation.ttl-days` (30) — no migration, the
  column was already there. Rate-limited by IP; **not** by account, because what arrives is a token, not
  an email to key on. As with the admin half, **the `/cuenta/activar` the email points at doesn't exist**
  — and here it can't until the storefront grows an account surface, so this endpoint stays contract-only
  for now. **PROD-07 turned the whole thing off** (next bullet): the email isn't sent and the endpoint
  answers 404 until `CUSTOMER_ACCOUNTS_ENABLED=true`.
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
- **Customer accounts are deferred behind one flag** (PROD-07),
  `app.customer-accounts.enabled` / `CUSTOMER_ACCOUNTS_ENABLED`, **off by default**. The backend half is
  complete (fifteen endpoints under `/api/customer/**`, F22/F23/F24) and the storefront half does not
  exist at all: no `/cuenta/*` route, no buyer session store, no customer password recovery. The
  production plan's rule for a deferred feature is that it disappears from its **entry points, API, and
  emails**, so all three are off:
  - **`CustomerAccountsGateFilter`** answers **404** to anything under `/api/customer`, by prefix rather
    than endpoint by endpoint, so an endpoint added there tomorrow is covered without remembering the
    flag. `@ConditionalOnProperty` on controllers couldn't do it: the three customer auth endpoints live
    inside `AuthController` next to the admin ones. It runs **after `RateLimitFilter` and before
    `TenantResolutionFilter`** — a deferred feature must not become a free unlimited endpoint, but a
    disabled call shouldn't pay for tenant resolution or JWT parsing either. Being ahead of the Security
    chain is what makes the 404 arrive **without** credentials instead of F19's 401, which would be
    asking someone to log in to something that doesn't exist. The body is a real `ApiError` written with
    the context's (Jackson 3) `ObjectMapper`, like `ApiSecurityErrorHandlers` and unlike
    `RateLimitFilter.reject`, whose hand-concatenated JSON is missing `timestamp`.
  - **`markInvitedIfGuest` stops issuing invitations**, and that's not tidiness — it's what keeps the
    feature switchable back on. A row marked `INVITED` with a token that was never emailed is **trapped
    forever**: that method won't re-invite it (it demands `GUEST`) and `customerRegister` rejects it (the
    email exists). That is F24's bug wearing a different hat. Guest checkout is otherwise untouched.
  - **The email listener checks the flag too**, for rows that already carry a token from before the
    switch, so "no invitation is ever sent" is true by every path. While there, a comment that claimed a
    second order wouldn't re-send was corrected: it's false — the token is **kept**, not regenerated, so
    all three guard conditions still hold and the same broken promise goes out again. `CustomerInvitationTest`
    only ever asserted the token doesn't change.
  - **Frontend**: the inert 👤 "Cuenta (próximamente)" is gone from `Header.tsx`, and `Terms.tsx` §3 no
    longer claims an account "may be necessary" — it says what actually happens (guest checkout, tracked
    by order number).
  - **Nothing was deleted**: not the API, not `accept-invitation`, not the tests covering them. The six
    classes that exercise accounts opt in with `@WithCustomerAccounts` (one composed annotation, so they
    share a single extra Spring context), which means **the shipped value — off — is what the rest of the
    suite runs under**, and `CustomerAccountsDisabledTest` deliberately carries no annotation.
    `AuthStatusTest` instead mints its customer token with the new `IntegrationTestSupport.customerToken`,
    so it keeps testing the security chain's status codes under the shipped config.
  - **To turn them on** you need six screens (`/cuenta/activar`, login, forgot, reset, profile/password,
    addresses), a buyer session store with a route guard, and **two endpoints that don't exist** (customer
    password recovery — the columns are already there since V12, no migration). The flag is
    platform-wide, not per store. The admin panel still shows `AccountStatus.INVITED`; with the flag off, 
    no new rows reach that state, and the existing ones are legitimate history. `OpenApiConfig` still
    documents `/api/customer/**` — that's operator documentation, and the filter's 404 is the operational
    truth.
- **Carriers and the POS are deferred behind two more flags** (PROD-12), both off/empty by default:
  `app.shipping.carriers.enabled` / `SHIPPING_CARRIERS_ENABLED` and `app.pos.enabled` / `POS_ENABLED`.
  Same shape and same reason as PROD-07, applied to the other two incomplete integrations. The
  authorities are `ShippingCarrierPolicy` and `PosIntegrationPolicy`; `CustomerAccountsGateFilter` was
  generalized into **`DeferredSurfaceGateFilter`**, one filter with a rule list (`RateLimitFilter`'s
  `Rule` pattern) rather than a near-identical copy per feature.
  - **Carriers need two different kinds of yes.** "Implemented" is a fact of the code, declared by each
    client through `ShippingCarrierQuoteClient.isImplemented()` — Correos de Chile is a placeholder that
    always returns empty, so **no property can enable it**; whoever writes the real `quote` deletes one
    override. "Homologated" is an operations decision and lives in the property. Before this, the panel
    offered both carriers as two hand-written `<SelectItem>`s and `AdminShippingMethodServiceImpl` took
    any enum name, so a merchant could configure and activate a shipping method the checkout would
    **never** show, with nothing on screen saying so. Rejected at configuration time now, with two
    different messages because the two cases differ for the merchant: one can't be fixed by configuring
    anything, the other can.
  - **A homologated Chilexpress over the sandbox URL fails startup.** `chilexpress.quote-url` defaults to
    `testservices.wschilexpress.com` so development works without credentials; declaring the carrier live
    while still pointing there is §7.1's "sandbox involuntario" — quoting real shipping off a toy
    environment. Same policy as `ReportZone` with a bad timezone and `RequiredSecret` with a dev secret.
    An unknown carrier name in the list fails startup too, rather than silently offering no shipping.
  - **The POS needs two yeses as well**, and the per-store one already existed: `pos_sync_enabled` has
    been in the panel from the start and **no code read it** — turning it off turned nothing off, the
    same lie as F11's `isOnSale='false'` and PROD-06's `sendInvitation='false'`. The platform flag is
    what contains the real risk: notifying an order **emits a tax document**, and the receiver's
    idempotency contract is still unconfirmed, which is exactly why `app.pos.retry.enabled` was already
    off. Having the retry contained while the emission itself ran by default was incoherent.
  - **The outbound cut is in `PosOrderNotifier`, at both ends of the outbox** (PROD-13b):
    `companiesToNotify` returns nothing when it's off, so `markPaid` queues no sale, and `prepare` skips a
    row queued before it was switched off, without touching `PosClient`.
    Inbound, `/api/sync/**` and `/api/webhooks/pos/**` answer 404 through the gate filter.
  - **Connecting the POS now requires the webhook secret**, like F17 requires credentials to enable
    Stripe. The connection used to go `active = true` unconditionally with the secret left as `""`, and
    that doesn't leave a door open, it leaves it **broken**: `PosWebhookAuthFilter` signs without
    checking the secret exists and `SecretKeySpec` rejects an empty key, so every inbound webhook dies
    building the HMAC — from a filter, where `GlobalExceptionHandler` never gets to answer.
    `PosConnectionActivationTest` pins that behavior instead of assuming it.
  - **What's still missing the day a carrier is homologated**, and unreachable while they're off: the
    commune → Chilexpress county-code table (the real blocker, and it needs credentials and docs we don't
    have); `shipping_origin_region`/`shipping_origin_commune`, which `ShippingRateServiceImpl` reads and
    **nothing can write** (no DTO field, no screen, no seed); `ShippingCoverageService` deriving public
    coverage from `ShippingZone` only, so a carrier-only store reports no coverage and the flow is
    unreachable from the SPA; and no test of a successful Chilexpress response — the six that exist cover
    only the failure paths. On the POS side, `handleStockAlert` and `handleProductCreated` only log, and
    the HTTP response admits it to the caller.
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

**PROD-24a — the store name typed at sign-up seeds how the store presents itself.** A store has the name in
three places, and only one used to get the sign-up value: `stores.name` (e-mails, the platform list),
`store_settings.store_name` (`StoreSettings`; what the storefront draws in the header, footer and legal
pages, edited in "Apariencia") and the `settings` key `store_name` (what "Ajustes generales" shows).
Both of the last two started as `"UvoStore"` — the entity default and the `SettingsServiceImpl` fallback —
so a brand-new store introduced itself with the platform's name until its owner noticed.
`StoreOnboardingServiceImpl.seedStoreName` now writes both rows in the sign-up transaction. It goes
through the repositories directly, not `StoreSettingsService`/`SettingsService`: those read the store
from `TenantContext`, which doesn't exist yet on the platform route that creates the store. Only the
**initial** value: the owner's edits win, and **stores created before this change keep `"UvoStore"`** (no
migration; the code defaults are still there as the fallback for a store with no row). The
`"store_name"` literal is duplicated between `SettingsServiceImpl` and the onboarding service — a rename
there would leave a dead row here, which `StoreOnboardingTest` catches by reading it through the real
endpoints. The "UvoStore Admin" title in `AdminLayout.tsx` is the platform's own branding for the panel,
not a store name, and is deliberately untouched.

**A payment is only accepted if the amount matches** (M4). `OrderStatusService.markPaid` takes the
amount the gateway says arrived and compares it against `order.getTotal()` before anything is marked
paid — none of Stripe, Webpay, or MercadoPago did that; they each read the payment's *status* while
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
unused; now all **110 endpoints across 19 controllers** carry `@PreAuthorize("hasAuthority('...')")` —
`GET` needs `dominio.view`, everything else `dominio.manage`. The catalogue is 23 permissions seeded
globally by `V15` (`Permission` has no `store_id`; `Role` does, `UNIQUE(store_id, name, guard_name)`).
`AuthController.adminAuthorities()` already put permission names in the JWT, so the annotations
needed no plumbing. **No role means no permissions**, so V15 also creates an "Administrador" role per
store and assigns it to every existing admin — without that backfill, enforcing the annotations locks
everyone out of their own panel. For the same reason `IntegrationTestSupport.createAdmin` now grants
a full-access role, and `createAdminWithPermissions(store, prefix, "products.view")` builds a
restricted one. Those 19 are the guarded admin subset, not every controller in the project (there
are 37 `@RestController` classes in total — the rest are public storefront, customer, POS, platform,
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
`StockConcurrencyTest` and `StockRecoveryTest` are the tests that run real concurrent threads — they
deliberately do not extend `IntegrationTestSupport`, whose per-test transaction would hide the
race.

**A paid order whose stock never moved is swept up** (PROD-13a). The decrement runs in
`StockDecrementListener`, after the payment commits and on the request thread on purpose; a process that
dies in between used to leave the order `PAID` with `stock_applied = false` forever, since reconciliation
only looks at `PENDING`. `StockRecoveryJob` (on by default, `app.stock-recovery.*`) picks those orders up
once `paid_at` (V24, written by `markPaid`) is older than `after-minutes`, and every hit leaves a note in
`order_status_history` plus a Sentry message. Running it alongside the listener is safe because the
`stock_applied` guard is now a **claim**, not a read: `OrderRepository.claimStockApplication` is a
conditional UPDATE inside the same transaction as the decrements, so of two concurrent callers only one
decrements. Keep `order.setStockApplied(true)` on the entity as well: `Order` has no `@DynamicUpdate`, so the
final `save` writes every column and would put `false` back over the claim. The pg advisory lock that
reconciliation used is now `service/AdvisoryLock`, shared by both jobs (keys `7_312_025_001` and `…002`).
**Trap fixed along the way**: `decrementStock` carries `clearAutomatically`, so after the first line the order
and its lines are detached. With an order loaded by `findById` (the listener's case), the next line's LAZY
product could not be initialized and the stock-failure note could not be added to the uninitialized
`statusHistory`: either one threw `LazyInitializationException` and rolled the whole decrement back —
**every paid order with two or more simple products decremented nothing**. `applyOrderStock` now initializes
the products up front, and `appendHistory` writes through the repository when the collection isn't loaded.
No test caught it because they all passed orders that were already in memory; `StockRecoveryTest` drives the
real listener.

**Post-commit effects that go to the network leave through a durable outbox** (PROD-13b). Registering the sale
in the POS and the three order emails (placed, confirmed, customer invitation) used to be `@Async`
`AFTER_COMMIT` listeners on in-memory executors: a crash or a redeploy lost whatever was queued, and the POS
retry only looked at `sync_attempts >= 1`, so a sale whose process died before the first attempt **never
reached the POS** (the stock master). Now each effect is an `outbox_messages` row (V25) written **in the same
transaction** as the fact — `CheckoutServiceImpl.checkout` (new orders only, not the F15 reuse) via
`OutboxService.enqueueOrderPlaced`, and `OrderStatusServiceImpl.markPaid` via `enqueuePaymentConfirmed`, never
on its early returns — as a plain INSERT against a UNIQUE `dedup_key`, so a double enqueue aborts the second
transaction. One `POS_ORDER` row **per company** (key = `PosOrderPayloadMapper.idempotencyKey`), so one
company failing no longer re-sends to the other. `OutboxDispatcher` delivers them every 10 s
(`app.outbox.*`) on two channels (POS, mail), each under its own `AdvisoryLock` (`…003`, `…004`), triggered by
`OutboxScheduler` (a separate bean so tests can switch it off). Per row: **claim** (`PENDING→IN_FLIGHT`,
`attempts+1`) committed in `OutboxStore` *before* the network call (the `RefundIntentStore` pattern), content
built in a short read-only transaction in a separate bean, the call outside any transaction, and the outcome
written conditionally on `(IN_FLIGHT, attempts)`. **What gets retried depends on whether the effect could have
arrived**: `PosClient.notifyOrder` now returns a typed `PosDelivery` — `NOT_SENT` (no connection, connect
timeout, 408/429) is retried with backoff (1→30 min) until `give-up-after-hours`; `UNCERTAIN` (read timeout,
5xx, 409, a process that died mid-call = an `IN_FLIGHT` row older than `in-flight-timeout-seconds`) becomes
`FAILED` + Sentry **unless `app.pos.retry.enabled`**, because without POS-1's idempotency a resend emits a
second tax document; `REJECTED` (other 4xx) fails at once. Mail retries what's uncertain (a duplicate email
is a nuisance, not a document). Both re-check state at send time: no sale or "thanks" for an order cancelled
or refunded since payment, no "order received" once it's paid, no invitation with accounts off (PROD-07). One
company's failure stops its remaining rows for that run, not the whole batch. Manual review is Sentry + the
SQL in V25's comment (the F12 precedent). `EmailService.deliver` is the non-swallowing variant the outbox
needs; `send()` stays for synchronous callers. `orders.pos_synced`/`sync_attempts`/`last_sync_error` were
dropped (V25 keeps their failures as `FAILED` rows), and `AsyncConfig`, `PosNotificationRetryJob`,
`OrderPlacedEvent` and the four listeners are gone; `StockDecrementListener` stays synchronous. Shutdown waits
for work in flight (`spring.task.scheduling.shutdown.*`, `timeout-per-shutdown-phase=30s`, which must cover
POS connect + read timeouts). **Two traps that came with it**: `findByProductId` / `PosConnection.findByStoreId`
return `Optional` over non-unique columns and would have thrown *inside the payment transaction* —
`companiesToNotify` uses `findByProductIdIn` and an existence query; and **F03 was only half closed**: callers
load the order before `markPaid` in the same transaction, and `findByIdForUpdate` returned that cached,
unrefreshed instance, so two simultaneous confirmations both saw `PENDING`. `findForUpdateOrThrow` now does
`flush()` → lock → `refresh()` (`ConcurrentPaymentConfirmationTest`). Deploy without overlapping instances: an
old one still writes the dropped columns.

**The admin panel doesn't edit what UvoPOS owns** (POS-2). It used to ignore `ProductSyncMapping`: an admin could
change a synced product's price or stock, the change never reached the POS and the next sync overwrote it.
`service/catalog/PosCatalogOwnership` decides it with the same rule the `product.updated` webhook applies:
only `ACTIVE` mappings count; `syncPrice` locks the price, `syncStock` the stock **and** `manageStock`,
`syncName` the name (and so the slug), `syncDescription` `description` only — `shortDescription` is the web's
"commercial description" — and **any active mapping locks the SKU** (no flag; it's the product's identity in
the POS). Two companies lock the union of their flags. It does **not** depend on `PosIntegrationPolicy`: a
mapping can only be born through the inbound surface, so the product came from the POS. `updateProduct` calls
`checkUpdate` **before touching the product** (after, the mapping query would flush the modified entity and the
images would already be on disk): a locked field that arrives **null is kept**, one that arrives equal passes
(`compareTo` for money, blank = null), one that differs is a `BusinessException` naming every such field. The
form leaves locked fields out on purpose — a stock read when the page loaded may have moved since — so
`manageStock` is now a `Boolean` (a missing primitive fails multipart binding with a vague 400). Variations of a
mapped product are rejected (add, update, delete), guarded by the variation's **real** parent: the controller
ignores the `{productId}` in the path. `posProductId` is no longer overwritten with null on every save. The
form reads `GET /api/admin/products/{id}/pos-ownership` (`products.view`; kept out of `ProductDto`, which the
public API also serves). **Creating products stays allowed even in a store with the POS as master** — the
user's call, against the original acceptance criterion; such a product has no mapping, its sales don't reach
the POS and trip `PosOrderNotifier`'s coverage alert (`PosMasterStoreProductCreationTest` pins this).
The inbound side applies the same rule since POS-3 (below).

**Inbound POS writes are ordered and idempotent** (POS-3). The sync used to overwrite name, slug, description,
price, stock, active and category on every call, ignoring the flags, and reactivated a paused mapping;
`product.updated` applied whatever arrived last, even if it was older. Now **one path writes a mapped product's
catalog**: `service/pos/PosCatalogWriter`, used by both. It writes what the flags say (same rule as POS-2),
only non-null values, only on an `ACTIVE` mapping, and only if the event's **`version`** is newer than
`product_sync_mapping.catalog_version` (V26). `version` is **required** (400 without it) in `/api/sync/product`
and in `product.updated`. It's the POS's number for the product, growing with each change, and the two inputs
share it. An equal version is discarded as already applied, a lower one as stale; `NULL` (older mappings) accepts the first
event. The version advances even if no field had its flag on. **Contract for POS-1:** with one version,
`product.updated` must carry *all* POS-owned fields, not just the changed ones, or an older partial event that
gets discarded loses its change until the next sync. User decisions: on an existing product the sync **never
touches stock** (stock only enters via `stock.updated`, conditioned on `oldStock`); `active` and category are set
**only on creation**, after which they're the web's. Discards are logged in `sync_webhook_logs` with the new
status **`DISCARDED`** (no Sentry) and answered **200** so the POS doesn't retry; every sync call is logged now
too (`product.sync`, via `PosInboundLog`), and its response carries `applied` and `log_id`. Three traps it
closes:
- **A catalog write could resurrect a sold unit.** `Product` has no `@DynamicUpdate`, so the full-row UPDATE
  rewrote the stock read at load time over a web sale that committed meanwhile. The writer takes the product row
  with `findByIdForUpdate` (`PESSIMISTIC_WRITE`) and then `refresh`es product and mapping, the PROD-13b pattern:
  the lock makes `decrementStock` wait, and the refresh sees what committed before it. Product first, mapping
  second, the same order as the stock webhook, to avoid deadlocks.
- **The stock webhook could roll the version back.** `setStockIfUnchanged` clears the context, and the old
  `save(mapping)` merged the detached mapping, every column included. It now calls
  `mappingRepository.touchSynced`, a targeted update that also no longer forces `ACTIVE`.
- **A repeated `stock.updated` was a false divergence alert**: if the conditional UPDATE misses but the stock
  already equals `stockWeb`, it's `DISCARDED` ("ya reflejado"), not a divergence.
Pinned by `pos/PosOrderedInputsTest` and `pos/PosCatalogRaceTest`. The race tests use committed transactions,
three single-threaded with a `REQUIRES_NEW` in the middle, and one with a second thread that proves the lock.

**Divergences with the POS are reconciled** (POS-4). Before, a divergence was only seen when an order was notified
or a webhook arrived; a lost event diverged forever. `service/pos/PosReconciliationService` compares each ACTIVE
mapping's stock and catalog with what the POS says and corrects towards the POS. It runs every
`app.pos.reconciliation.interval-ms` (15 min; `PosReconciliationJob`, `AdvisoryLock` `…005`, only with
`app.pos.enabled`) and on demand from **Ajustes > POS > "Resincronizar ahora"** (`POST /api/admin/pos/resync`,
`settings.manage`, synchronous, returns a `PosReconciliationSummary`; 400 if the store's sync is off or there's no
active connection). The resync covers mapped products only — the user's call; new POS products still arrive via
`/api/sync/product`.
- **The bulk read is a proposed contract** (POS-1 isn't done and `uvopos-app` has none of the integration):
  `PosClient.readCatalog` → `POST /api/v1/products/snapshot` with `{"product_ids": [...]}` (a batch,
  `app.pos.reconciliation.batch-size`, 200), same headers as `orders/external`. It expects
  `{"data": [{product_id, version, stock_web, price, name, description}]}` — all POS-owned fields, for POS-3's
  reason. Parsing is per field and lenient (`PosCatalogSnapshot`); a failed read never throws and stops only that
  connection.
- **A web sale in flight is never undone.** Our stock has already deducted it; the POS hasn't got it. A product is
  in flight if a `POS_ORDER` outbox row of that company is `PENDING`/`IN_FLIGHT`/`FAILED` for an order with it,
  **or** a `PAID` order with `stock_applied = false` contains it. The second case: the POS may already have the
  sale while our decrement is still pending, and correcting first would let `StockRecoveryJob` deduct it twice.
  Per batch:
  1. A read-only snapshot, with stock as a **value** and the in-flight set.
  2. The POS read, outside any transaction.
  3. Only products not in flight are corrected, with `setStockIfUnchanged(snapshotStock → posStock)`, one short
     transaction each.
  A sale that starts after the snapshot moves the stock, so the UPDATE misses and the product waits for the next
  pass. A sale `SENT` before the snapshot was already in the POS when it was read. That assumes the POS applies a
  sale when it acknowledges it, and it's noted for POS-1.
- **Catalog** goes through `PosCatalogWriter.reconcile`. It applies an **equal** version too: the read is the POS's
  current state, so a difference at the same version is local drift. A lower version is still `STALE`, so a read
  older than an applied event never reverts it.
- **Reporting.** One `sync_webhook_logs` row per connection and pass (`pos.reconciliation`, payload with
  `trigger`, counts and up to 50 ids each; `FAILED` if the read failed). One Sentry message, only if something was
  corrected, missing in the POS, failed or errored. Products in flight are logged only, since that's transient.
  A product the POS no longer returns is only reported, not paused.
- **Known gap:** `SettingsServiceImpl` sets a connection's `apiUrl` only when it creates it. Changing the POS URL
  in the panel later doesn't reach the connection, so reconciliation and sales keep using the old one.

Pinned by `pos/PosReconciliationTest` (POS mocked with `@MockitoBean`, batches of 2) and `pos/PosCatalogReadTest`
(real sockets).

**POS products can have variants** (POS-5). The integration used to know only simple products: the sync created
`SIMPLE`, the mapping was per product, and a sale, a stock event or a reconciliation named the product, not the
variation. The model is the usual one, **parent with variants** (the user's call: nobody knew yet whether UvoPOS
has variants), and it's a **proposed contract** for POS-1, like the sale and the bulk read:
- **Shape.** A variant is `{variantId, sku, price, stock, attributes: {"Talla": "M", …}}`. It comes in
  `/api/sync/product` (`variants`, optional; with it, the parent's `price` and `stock` are optional and ignored,
  without it they're required as before) and in `product.updated` (`data.variants`, same shape). The bulk read
  returns `variants: [{variant_id, sku, price, stock_web, attributes}]` per row. `stock.updated` takes `variantId`,
  **required** for a variable product. A variation line of the sale carries `variant_id`; a simple line doesn't
  carry the key. A divergence result may carry `variant_id` too.
- **One version per POS product covers its variants**, and every catalog input carries the **full** variant list
  (POS-3's rule): a variant that doesn't come is gone from the POS.
- **Validation is the same for the three inputs** (`PosVariants.validate`; 400 on sync before the log row, `FAILED` on
  the webhook, an error in the reconciliation). The list can't be empty. Each variant needs id, sku, price and
  non-empty attributes. All variants must have the **same attribute names**, and no two may share an id or a
  combination. Names and values compare by slug, which is how they're stored. The storefront makes you pick a value
  of every attribute and matches all of them (`ProductDetail.tsx`), so a variant that breaks those rules could
  never be bought.
- **Schema:** `product_variation_sync_mapping` (V27) hangs off `product_sync_mapping`, which keeps the company, the
  flags and `catalog_version`. Its keys are `UNIQUE (mapping_id, external_variant_id)` and
  `UNIQUE (mapping_id, product_variation_id)`. The sync creates the product `VARIABLE`, with each variation and its
  attributes (`Attribute`/`AttributeValue` found or created by slug per store, like categories). The parent's
  price is the cheapest variant and its stock the sum.
- **Catalog updates** go through `PosCatalogWriter` → `PosVariantCatalog.apply`, under the same lock and version
  rules:
  - a known variant takes its price if `syncPrice`, and comes back to active;
  - an unknown one is created, with its stock or 0;
  - a mapped one that's missing is **deactivated**, never deleted (orders reference it) and its stock untouched;
  - an existing variant's stock is **never** written here (POS-3);
  - the parent is recomputed: stock is the sum of all variations, the same rule as
    `recalculateStockFromVariations`, and price is the cheapest active one.
  Variants for a product that's `SIMPLE` here are the new outcome **`MISMATCH`** (`FAILED`, nothing written, the
  version doesn't advance): converting a type is manual. A variable product's event without `variants` skips them.
- **Lock order: the parent before any of its variations, everywhere.** The writer locks the product and then
  writes its variations. The sale did the opposite: `decrementVariation` took the variation, then
  `recalculateStockFromVariations` the parent. The two together deadlocked, and `PosVariantLockOrderTest` gets
  Postgres' `deadlock detected` without the fix. So `OrderInventoryService.decrementVariation` and
  `restoreOrderStock` now call `findByIdForUpdate` on the parent first, and so do the variant stock webhook, the
  reconciliation and the panel's `ProductVariationServiceImpl` (add, update, delete). With that, no variation of a
  product moves while its parent is locked, which is also why the writer's in-Java sum is exact.
- **Stock per variant:** `ProductVariationRepository.setStockIfUnchanged` (F13's conditional UPDATE), then the
  parent's sum and `touchSynced`. A miss is `DISCARDED` if the value is already there, otherwise a divergence. Without
  `variantId` on a variable product the event fails: writing the parent would break the sum.
- **Sale:** `PosOrderNotifier.prepare` adds the variant to each variation line. A variation without a variant
  mapping **isn't sent**, since the POS would deduct something else, and `companiesToNotify` counts it in the
  coverage alert. `checkStockDivergence` compares a variant result with that variation. A product-level result for a
  variable product is skipped: the parent is a sum, and comparing it would alert on every sale.
- **Reconciliation:** for a variable product the parent's stock is never compared; each variant is, with
  `stock_web`, locking the parent first. "In flight" stays per **product**, which is conservative: one size with a
  pending sale delays its siblings a pass. The catalog differs on a variant price (with `syncPrice`), an unknown
  variant, or a missing or reappearing one. Counts stay per product.
- **POS-2 is unchanged.** A mapped product's variations are still rejected in the panel, since `VariationCommand`
  only carries POS-owned fields. Only the message changed: they're created and changed in the POS.
- **Out of scope:**
  - changing a variant's attributes or SKU from the POS, since they're set at creation;
  - per-variant images, because the panel has no variation UI;
  - "in flight" per variation;
  - two syncs creating the same attribute at once can hit its UNIQUE, as with categories.

Pinned by `pos/PosVariantsTest`, the variant cases in `pos/PosReconciliationTest`, `pos/PosVariantLockOrderTest`
(committed transactions, two threads) and the contract tests (`PosOrderPayloadMapperTest`, `PosCatalogReadTest`).

Both gaps that used to be listed here are now closed. `PosWebhookServiceImpl.handleStockUpdated` no longer overwrites stock blindly: it uses `ProductRepository.setStockIfUnchanged`, conditioned on the `oldStock` the POS says it saw, so a concurrent web sale can't be erased — a mismatch is reported as a divergence (same wording and Sentry alert as `PosOrderNotifier.checkStockDivergence`, which already treats our own decrement as the authority for web stock) instead of written (F13). And marking an order paid from the admin panel now goes through `OrderStatusService.markPaid`, so it publishes `PaymentConfirmedEvent` and does decrement stock (F07).

**POS webhooks can't be replayed** (F21). HMAC plus a ±300s freshness window proves a request is authentic
but not that it's *new*: inside that window a captured request replays fine, since body and timestamp are
unchanged. `PosWebhookAuthFilter` now remembers what it already served, keyed by `(companyId, signature)` —
**the signature is itself the event id** the finding asked for (HMAC of body+timestamp with the merchant's
secret), so no change to the UvoPOS contract was needed. Two things to know:
- The check runs **after** signature verification, deliberately. Recording first would let anyone burn the
  genuine signature or fill the store with garbage, without knowing the secret.
- **The store is a table since POS-3**, `pos_webhook_receipts` with `UNIQUE (company_id, signature)`, so the
  guard survives a restart and holds across instances (it used to be a Caffeine cache). `PosWebhookReplayGuard`
  records with one `INSERT … ON CONFLICT DO NOTHING` (1 = first time; two simultaneous replays can't both pass),
  committed on its own because the filter runs outside any transaction. A scheduled purge
  (`app.pos.webhook-receipts.purge-interval-ms`) deletes receipts older than **two** freshness windows plus a
  minute. The window accepts a timestamp up to 300s *ahead* too, so a signature can stay fresh 600s after it
  arrives — the old cache forgot it at 310s.

What a replay actually did before this is worth recording because it is not what you'd guess: for
`stock.updated` F13's conditional UPDATE already refused it (the stock is already the new value) — so no
double-apply, but a **false divergence alert to Sentry** on every replay. `stock.alert`/`product.created`
only log. The one that genuinely hurt was `product.updated`, which rewrote price/name/description
unconditionally; POS-3 ordered it by version (above).

`OpenApiConfig` wires springdoc — API docs at `/swagger-ui.html`, grouped into five surfaces
(public/admin/customer/pos/platform) with a `bearerAuth` JWT scheme wired to the "Authorize"
button. **PROD-17c:** all of it sits behind one switch, `SWAGGER_ENABLED` → `app.swagger.enabled`
(default **true**, the dev value), from which `springdoc.api-docs.enabled` and
`springdoc.swagger-ui.enabled` both derive so they can't diverge. On, `/swagger-ui.html` and
`/v3/api-docs/**` are public and publish the whole API map, `/api/platform/**` included. Off, springdoc
registers nothing and they answer **404**. **Production must set `SWAGGER_ENABLED=false`** — the default
is not safe-by-default; since PROD-08a, `APP_ENVIRONMENT=production`/`staging` refuses to start with it on
(see the PROD-08a paragraph below). The three paths stay in `SecurityConfig.PUBLIC_PATHS` on purpose: removing them would
turn the 404 into a 401 "log in again" over something that doesn't exist. `/v3/api-docs.yaml` is not in
that list (the pattern doesn't reach it) so an anonymous caller gets 401 either way; whether an
*authenticated* caller gets the spec from it was not checked. Pinned by `config/SwaggerDisabledTest` and
its control `SwaggerEnabledByDefaultTest`. Error tracking is `io.sentry:sentry` (the **core SDK**, not
`sentry-spring-boot-starter-jakarta` — that starter's autoconfiguration references a class Spring
Boot 4 moved/removed and fails to load; see the comment in `pom.xml`). Initialized manually in
`UvoStoreApplication.main()` from `SENTRY_DSN`; a blank/unset DSN leaves it inactive.
`GlobalExceptionHandler`'s catch-all reports unhandled exceptions to Sentry (and logs them
locally) without leaking internals to the client.

**PROD-15a — probes and Sentry labels.** `/actuator/health/liveness` and `/actuator/health/readiness`
(`management.endpoint.health.probes.enabled`) are public, named one by one in
`SecurityConfig.PUBLIC_PATHS` — not `/actuator/health/**`, which would also open any group or component
added later; `show-details=never` stays, so a caller gets `{"status":"UP"}` and nothing else. The two
groups are pinned in `application.properties`: liveness is `livenessState` alone (**no** external
dependency, so a database outage never gets the process killed and restarted into the same outage — it
would also lose the token-version cache and the rate-limiter counters), readiness is `readinessState,db`
(out of the load balancer while the database is down). Boot's default readiness does *not* include the
database. Side effect, on purpose: `probes.enabled=false` makes the app refuse to start (Boot validates
that the pinned groups exist). `HealthProbesDatabaseDownTest` replaces the `db` contributor with a bean
named `dbHealthIndicator` (the name the autoconfiguration backs off from) and asserts 503 on readiness /
200 on liveness. Sentry labels: `SentryBootstrap` reads `SENTRY_ENVIRONMENT` (`production` / `staging` /
`development`) and `SENTRY_RELEASE` (the release tag) from the process environment, next to the DSN. They
are read by hand because the SDK's `enableExternalConfiguration` is `false` by default, so setting those
variables did nothing before, and turning it on would also admit every other `SENTRY_*` variable. **If
`SENTRY_ENVIRONMENT` is unset the SDK reports `production`**, so a staging that forgets it files its
errors under production — since PROD-08a, with a DSN set and `APP_ENVIRONMENT` other than `development`, the
app refuses to start unless `SENTRY_ENVIRONMENT` matches `APP_ENVIRONMENT`. The
frontend does the same in `frontend/src/lib/sentry.ts` from build variables `VITE_SENTRY_ENVIRONMENT`
(falls back to Vite's `MODE`) and `VITE_SENTRY_RELEASE` (`release.yml` sets it from the tag; the
environment is *not* set there because the artifact is promoted between environments). That file also
**strips the value of `token`/`password`/`secret`/`signature` query parameters** from `request.url`,
`query_string`, the `Referer` header and the navigation/fetch/xhr breadcrumbs: the `?token=` in the reset
and invitation e-mail links (`/admin/reset-password`, `/admin/aceptar-invitacion`) was reaching Sentry
with every error raised on those pages. The backend sends no request URLs to Sentry (core SDK, no servlet
integration), so it needs no such filter. Not covered: `main()` itself (one line, calls
`SentryBootstrap`); and the customer-invitation e-mail links to `/cuenta/activar?token=…`, a route the SPA
does not have (the customer surface is deferred).

**PROD-15b — correlatable logs and metrics.** PROD-18 already put the request id in the logs; this adds the
store, an id for scheduled jobs, the id on the way to the POS, and Prometheus metrics.
- **MDC keys** live in `config/LogContext`: `requestId` and `storeId`.
  - `TenantContext.set`/`clear` write `storeId`, so requests, jobs and outbox deliveries carry it through
    `runWithin` without touching them.
  - `PosApiKeyAuthFilter` and `PosWebhookAuthFilter` don't resolve the store by Host, so they put it themselves,
    from the connection, and remove it in `finally`.
  - The pattern is `logging.pattern.correlation=%([%X{requestId:-}%replace(%X{storeId:-}){'^(.+)$', ' store=$1'}] ){}`.
    It renders as `[<id> store=<n>] `, or `[<id>] ` without a store. The `%replace` turns a non-empty `storeId`
    into ` store=<n>` and leaves an empty one empty. The PROD-18 logback traps still apply: the group and the
    trailing `{}`.
- **Job ids:** `SchedulingConfig` registers a `ThreadPoolTaskSchedulerCustomizer` that decorates every
  scheduled run.
  - Each run gets `requestId = job-<12 hex>`, which matches `RequestIdFilter`'s accepted format. The decorator
    restores the whole MDC afterwards.
  - It's a customizer and not a `TaskDecorator` bean, because Boot would apply such a bean to the application
    task executor too.
  - An audit row or an outbox row written by a job carries that id.
- **To the POS:** V30 adds `outbox_messages.request_id`. `OutboxService.enqueue` fills it from the MDC: the
  Webpay return or webhook that confirmed the payment, the panel request, or the reconciliation's `job-…`.
  - `OutboxDispatcher` puts the row's id in the MDC while it handles the row and runs `afterDelivered`, then
    restores the run's id. Older rows have no id and keep the run's id.
  - `PosClient` has a request interceptor that sends `X-Request-Id` from the MDC, on both `notifyOrder` and
    `readCatalog`.
- **Endpoint:** `/actuator/prometheus` (`micrometer-registry-prometheus`; exposure is `health,prometheus`;
  `/actuator/metrics` is still not exposed) on the same port. `security/MetricsAuthFilter` guards it in three
  steps:
  1. outside `PlatformNetworkPolicy` it's a 404, and the token isn't checked;
  2. without `METRICS_TOKEN` (the default) it's a 404;
  3. a missing or wrong `Authorization: Bearer <METRICS_TOKEN>` is a 401 (constant-time compare).
  The path is in `PUBLIC_PATHS` because the filter authenticates it. `ProductionConfigurationRules` warns when the
  token is empty and errors when it's shorter than 32 characters.
- **Metrics:** none of the custom metrics carries the store as a label, because series would grow with the
  number of stores. Logs (`store=`) and Sentry say which store. `management.metrics.tags.application=uvo-store`.
  - Gauges, from `service/metrics/OperationalBacklogMetrics` (a `MeterBinder`):
    - **Snapshot:** one snapshot for all gauges, computed on read and kept for `app.metrics.backlog-refresh-seconds`
      (60). No job and no scheduler thread; nobody scraping means no queries.
    - **Failures:** a failed read keeps the previous snapshot. If there never was one, gauges report `NaN`, not 0.
    - **Gauges:**
      - `uvostore_orders_paid_without_stock`: `PAID`, `stock_applied = false`, `paid_at` older than
        `app.stock-recovery.after-minutes`;
      - `uvostore_orders_pending_stale`: `status = PENDING`, older than `app.reconciliation.stale-hours`;
      - `uvostore_refunds_pending`;
      - `uvostore_outbox_pending{channel}`: `PENDING` + `IN_FLIGHT`;
      - `uvostore_outbox_oldest_pending_seconds{channel}`: measured against the read time, so it keeps growing
        between snapshots. With `pos`, this is the signal for "the POS isn't getting the sales";
      - `uvostore_outbox_failed{channel}`: with `pos`, the sales the POS doesn't have, waiting for manual review.
        **It never goes down**, because nothing resolves a `FAILED` row, so alert on increases.
    - **Queries:** the counts are global on purpose. The two on `OrderRepository` are in
      `RepositoryTenantScopeTest.GLOBAL_ON_PURPOSE`; the refund and outbox counts aren't checked there, because
      those entities have no store of their own.
  - Counters, from `service/metrics/BusinessMetrics`, all registered at startup at zero so alerts have a series
    before the first event:
    - `uvostore_pos_stock_divergences_total{source}`, where `source` is one of:
      - `reconciliation_stock`, `reconciliation_catalog`, `reconciliation_missing`: the sizes of each
        reconciliation pass, but not sales in flight;
      - `webhook`: the two divergences in `PosWebhookServiceImpl`;
      - `order_response`: `PosOrderNotifier.checkStockDivergence`, only above the alert threshold;
    - `uvostore_outbox_deliveries_total{channel,outcome}`: `outcome` is the attempt's `OutboxResult.Outcome`
      (`delivered`, `skipped`, `retryable`, `uncertain`, `permanent`), counted in `OutboxDispatcher.apply`; an
      orphan counts as `uncertain`. The final state is the `failed` gauge.
  - Boot adds, for free: JVM, Hikari, Tomcat, `http.server.requests` (templated `uri`) and `executor_*` for the
    scheduler.
- **Tests:**
  - `config/LogCorrelationTest`, `SchedulerRequestIdTest`, `MetricsEndpointTest` and `MetricsWithoutTokenTest`;
    the metrics tests use `@AutoConfigureMetrics`, because otherwise the test registry has no Prometheus
    endpoint and every 404 would pass;
  - `pos/PosClientRequestIdTest` (real sockets);
  - `outbox/OutboxRequestIdTest`;
  - `service/metrics/OperationalBacklogMetricsTest`, which measures deltas because the counts are system-wide;
  - new cases in `OutboxDispatcherTest`, `PosOrderNotifierTest`, `PosReconciliationTest`, `PosVariantsTest`,
    `ProductionConfigurationRulesTest` and `RepositoryTenantScopeTest`.

**PROD-08a — production configuration check and a separate Flyway role.** Every default in
`application.properties` is the *development* value, and until now nothing stopped a deployment from booting
on them. The signal is explicit: `APP_ENVIRONMENT` → `app.environment`, one of `development` (default),
`staging`, `production` (`AppEnvironment`). **An unknown value refuses to start**, so `prod` or `produccion`
can't silently switch the checks off. It is deliberately not a Spring profile (§7.1: a `prod` profile
implements nothing by itself). `ProductionConfigurationRules` (pure: properties, the process environment for
`SENTRY_*`, and the DB role through a `Supplier`) decides; `ProductionConfigurationCheck` applies it in its
constructor, like the other startup validators. **All errors come out together** in one
`IllegalStateException`, one per line, so the operator fixes them in one go; warnings are `WARN` lines.
- **Errors, in `production` and `staging`:**
  - `PUBLIC_STOREFRONT_URL` / `PUBLIC_API_URL` not `https`;
  - `PUBLIC_PLATFORM_DOMAIN` that is `localhost`, `*.localhost`, an IPv4 address, or has no dot;
  - **`production` only**, Webpay: `WEBPAY_ENVIRONMENT` other than `production`, or the parent code / API key
    equal to the SDK's sandbox constants (`IntegrationCommerceCodes.WEBPAY_PLUS_MALL`,
    `IntegrationApiKeys.WEBPAY`). This one closes a real hole: `WebpayServiceImpl` treats *anything* but
    `production` as integration, so a typo charged in the sandbox and marked orders paid;
  - local storage with a relative `APP_UPLOAD_DIR` (it lives in the container and dies with it), or `s3` without
    a bucket;
  - blank `MAIL_HOST` (mail is skipped silently: no invitations, resets or order e-mails);
  - `SWAGGER_ENABLED` not false;
  - `APP_ENCRYPTION_KEY` equal to the old committed value (`EncryptionKeyHolder` only warns, for the local
    reason in "Local environment"; a production install has no such excuse);
  - with `SENTRY_DSN` set, `SENTRY_ENVIRONMENT` different from `APP_ENVIRONMENT`;
  - the DB role: `FLYWAY_USER` blank or equal to `DB_USERNAME`, or the application role (`DatabaseRole`, one
    query) being superuser, able to `CREATE` in the current schema, or owner of any table;
  - (POS-6) a `/0` entry in `PLATFORM_ALLOWED_NETWORKS`, which would open store creation to the Internet.
- **Warnings:** `POS_ENABLED=false` (off on purpose until POS-1 confirms the contract; make it an error then),
  blank `RATE_LIMIT_TRUSTED_PROXIES` (the app can't know whether there is a proxy), blank `SENTRY_DSN`.
- **In `development` nothing is checked and the database is not queried**, so the suite (surefire sets no
  `app.environment`) and local runs are unchanged. One exception: a `PUBLIC_PLATFORM_DOMAIN` that looks real
  logs a `WARN` saying `APP_ENVIRONMENT` is missing — the safety net for forgetting the signal.
- **Flyway role:** `FLYWAY_USER` / `FLYWAY_PASSWORD` → `app.flyway.*`, applied by `FlywayMigrationRole`, a
  `FlywayConfigurationCustomizer` that calls `configuration.dataSource(spring.datasource.url, user, password)`.
  Blank, Flyway keeps using the application pool (local dev, where `uvostore` owns everything). **Not**
  `spring.flyway.user=${FLYWAY_USER:}`: Boot 4.1's `FlywayAutoConfiguration.getMigrationDataSource` only checks
  for `null`, so the placeholder's `""` builds a second datasource with an empty user that can't connect.
  Customizers run after Boot sets the datasource, so this one wins. Nothing in `src/main` issues DDL or
  `TRUNCATE` at runtime, and Hibernate's `validate` reads `pg_catalog`, so DML grants are enough.
- **Provisioning SQL for the two roles** (PROD-08b does it on the platform; role names are illustrative):
  ```sql
  CREATE ROLE uvostore_migrator LOGIN PASSWORD '...';
  CREATE ROLE uvostore_app LOGIN PASSWORD '...';
  GRANT CONNECT ON DATABASE uvostore TO uvostore_migrator, uvostore_app;
  GRANT USAGE, CREATE ON SCHEMA public TO uvostore_migrator;
  GRANT USAGE ON SCHEMA public TO uvostore_app;
  -- Already migrated by another role? Hand its tables over first (ALTER TABLE … OWNER TO uvostore_migrator).
  GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO uvostore_app;
  GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO uvostore_app;   -- most ids are BIGSERIAL
  ALTER DEFAULT PRIVILEGES FOR ROLE uvostore_migrator IN SCHEMA public
      GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO uvostore_app;
  ALTER DEFAULT PRIVILEGES FOR ROLE uvostore_migrator IN SCHEMA public
      GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO uvostore_app;
  ```
  The default privileges are what make the next migration's tables reachable by the app without a new grant.
- **Tests:** `config/ProductionConfigurationRulesTest` (pure; the development defaults are read from the real
  `application.properties` with no environment, so a new unsafe default can't slip by),
  `ProductionConfigurationCheckTest` (constructor + logs), `DatabaseRoleQueryTest` (the real query: local and
  CI connect as a superuser that owns everything, exactly what production forbids) and
  `FlywayMigrationRoleTest` (the customizer, plus a nested `@SpringBootTest` proving Boot applies it).
- **Not covered:** whether an absolute upload directory is really a persistent volume (the app can't know);
  the per-store Stripe keys in `settings` (runtime data, not startup configuration).

**POS-6 — store onboarding from UvoPOS, with the owner invited.** When a company activates the e-commerce
module, the UvoPOS backend calls `POST /api/platform/stores` with a `pos` block, and the same transaction
creates the store, the owner, the `PosConnection` and the two settings that make it work. **The contract is
proposed** (POS-1 is still open; `uvopos-app` has none of this), like POS-4/POS-5:
```json
{ "slug": "tiendadejuan", "domain": "", "storeName": "Tienda de Juan",
  "adminName": "Juan", "adminEmail": "juan@tienda.cl",
  "pos": { "companyId": 123, "companyName": "Juan SpA", "apiUrl": "http://pos.internal/api",
           "apiKey": "uvp_123_<≥32 alphanumerics>", "webhookSecret": "<≥32 chars>" } }
```
- **The POS generates both secrets** and sends them; the response (`StoreOnboardingResponse`, now with
  `created`) never carries them. The token must be `uvp_<companyId>_…` of the declared company
  (`service/pos/PosApiKey`, shared with `SettingsServiceImpl`). They're stored encrypted like the ones typed in
  Settings. The block is refused (400, before writing) while `POS_ENABLED=false`: `/api/sync/**` would 404 and
  the catalog could never load — a half-done onboarding.
- **Idempotent by the POS company** (`pos_connections.company_id` is UNIQUE), not by a header. The transaction
  starts with `pg_advisory_xact_lock(7312, Long.hashCode(companyId))` (two-int key space, distinct from the
  jobs' one-bigint `AdvisoryLock` keys), then looks the company up. Found ⇒ a retry, and **a retry writes
  nothing**: same slug + same secrets (constant-time) + same owner e-mail (ignoring case) ⇒ the existing store
  with `created=false`; another slug ⇒ 400 "ya tiene la tienda"; other secrets or e-mail ⇒ 400 "otras
  credenciales". Updating the secrets on a retry was rejected: a late retry of an older activation would put
  back secrets the POS no longer holds. The operator path (no `pos`) keeps the old semantics (duplicate slug ⇒
  400).
- **The owner is always invited** (both paths; the operator form `/plataforma/nueva-tienda` lost its password
  field): password `null` plus a PROD-06 token. `service/security/AdminInvitationMailer` (`issue`, `send`,
  package-visible `body`) came out of `UserServiceImpl` so both use it with an explicit `Store`. The e-mail goes
  **after commit** (`StoreOwnerInvitedEvent` → `StoreOwnerInvitationListener`, `AFTER_COMMIT`, own
  `REQUIRES_NEW` read-only transaction). A failure there can't change the response: Spring runs `AFTER_COMMIT`
  listeners inside `afterCompletion`, and `TransactionSynchronizationUtils` catches and logs whatever they throw
  (a bite proved it — without the listener's `try` the onboarding still answered 200). The `try` is there to
  attribute the failure: a log line with the user and a Sentry event, instead of Spring's generic log line that
  alerts nobody. **Not durable**; if it's lost, «¿Olvidaste tu contraseña?» works for an account
  without a password and closes the pending invitation (`AuthController.adminResetPassword`). The platform can
  send a new one (PROD-23's owner access recovery, same event and listener).
- **The initial catalog is pushed by the POS**, one `POST /api/sync/product` per product, with the token it
  generated — **sequentially** (two parallel creates can still collide on a unique constraint; the retry
  resolves it). The bulk path was hardened: product slugs are unique per store (`PosCatalogWriter.uniqueProductSlug`:
  `base`, `base-2`…, `producto` if the name leaves nothing; on a rename only when the name changes, excluding
  the product itself); categories match by name, then by slug ("Bebidas" = "bebidas"); a product or variant SKU
  already used in the store is a 400 naming it, checked before writing (`PosVariantCatalog.requireFreeSkus`).
  Each of these used to be a 500 the POS would retry forever. Note `slugify` drops accented letters
  (`Café` → `caf`), as it always did.
- **Private network only:** `PlatformNetworkPolicy` + `app.platform.allowed-networks`
  (`PLATFORM_ALLOWED_NETWORKS`, default loopback + RFC 1918 + `fc00::/7`; a mistyped entry fails the startup,
  empty closes the API). `PlatformApiKeyAuthFilter` checks the network **before** the key and answers 404 with
  the `DeferredSurfaceGateFilter` body, so from outside the key is never evaluated. The address comes from
  `ClientIpResolver.resolveStrict`, which, unlike `resolve`, says "unknown" (⇒ denied) when a forwarding header
  (`X-Forwarded-For`, `Forwarded`, `X-Real-IP`) arrives from a peer that isn't a trusted proxy, when a trusted
  proxy sends no `X-Forwarded-For`, or when a hop isn't an address. **What it can't detect** is a proxy that
  adds no header at all (stock nginx `proxy_pass`): then everything looks private. So the proxy must set
  `X-Forwarded-For`, be declared in `RATE_LIMIT_TRUSTED_PROXIES`, and should not publish `/api/platform/` at
  all (PROD-05c). Operators use the form from the private network (VPN or tunnel); in dev Vite sends no XFF.
- **IP ranges are compared by `security/IpRanges`, not Spring's `IpAddressMatcher`**, which in Security 7.1.0
  ignores the address family (checked against the jar: `10.0.0.0/8` matched IPv6 `a00::1`, `::1/128` vs
  `0.0.0.0` threw `ArrayIndexOutOfBoundsException`, `"10"` parsed as `0.0.0.10`). `ClientIpResolver` uses it for
  `trusted-proxies` too. It never resolves names.
- **Tests:** `platform/PosStoreOnboardingTest` (creation, encryption, retries, validation, and the full
  path to `/api/sync/product`), `platform/StoreOnboardingCommitTest` (committed transactions: e-mail after
  commit, not on retry, not on rollback, a failing mailer still 200, and two simultaneous retries — B waits on
  the lock in `pg_locks`, then gets `created=false`), `security/IpRangesTest`, `PlatformNetworkTest`,
  `PlatformNetworkBehindProxyTest` (shares `TrustedProxyRateLimitTest`'s context on purpose),
  `pos/PosBulkCatalogPushTest`, new cases in `StoreOnboardingTest` / `ClientIpResolverTest` /
  `ProductionConfigurationRulesTest`, and `NewStore.test.tsx`. Tests that log into a freshly onboarded store
  accept the invitation first (`StoreOnboardingTest.activateOwner`).

**PROD-18 — audit log.** `audit_events` (V28) records who changed what, in which store, when, with which result
and in which request: `store_id`, `actor_type` (`ADMIN` | `PLATFORM` | `SYSTEM`), `actor_id`, `actor_label`,
`action`, `target_type`, `target_id`, `result` (`SUCCESS` | `FAILURE`), `request_id`, `details` (jsonb). **Read it
through the platform API** (`GET /api/platform/stores/{id}/audit-events`, PROD-23) **or with SQL** — the queries
are in V28's `REVISIÓN MANUAL` block; the store panel doesn't read it.
- **Same transaction as the change.** `service/audit/AuditTrail.record(AuditEntry)` is the only writer and is
  `Propagation.MANDATORY`: if the change rolls back the row goes with it, and if the row can't be written the
  change doesn't happen (fail-closed — an enum constant missing from the migration breaks the business call, which
  `AuditCatalogConsistencyTest` catches at build time). The only two `FAILURE` rows survive because *their*
  transaction commits: a failed admin login (`adminLogin` is `@Transactional(noRollbackFor =
  BadCredentialsException.class)`; the failure path writes nothing else) and a gateway-rejected refund (inside
  `RefundIntentStore.fail`, which already ran in its own transaction; `REFUND_REQUEST` is written in `open`, so the
  intent stays recorded even if the local transaction dies after the gateway paid). **No `REQUIRES_NEW` writer**:
  it would hold a second pooled connection per failed login, and ten at once starve the pool.
- **Never secrets or whole bodies.** `details` is built only through `AuditDiff`: `value` (from/to, only if it
  changed; amounts by `compareTo`, so `10000` = `10000.00`), `secret`/`secretSubmitted`/`credentials` (only the key
  name and `set`/`replaced`/`cleared`; credential map keys are client-chosen, so only names matching
  `[a-z][A-Za-z0-9]{0,39}` are kept and the rest counted), `opaque` (only the name of a free-text field that
  changed: notes, descriptions, phone), `with` (context, not a change), text cut at 120 chars. Edits record only
  when `hasChanges()`; create, delete and toggle always record. Stored-encrypted secrets (`stripe_secret_key`) are
  **not decrypted to compare** — after a key rotation that would fail exactly on the save that fixes it. No refund
  reasons, no gateway references, no passwords or tokens, and a failed login with an unknown e-mail records
  nothing (people type their password there); attempts the account throttle rejects aren't evaluated, so brute
  force can't fill the table.
- **Actor and store.** By default the actor is the Spring Security principal (admin: id + e-mail as it was; the
  platform: `PlatformApiKeyAuthFilter` now puts `AuthPrincipal(null, operator, "PLATFORM", null)` after the key
  check) and the store is `TenantContext`'s; nothing authenticated is `SYSTEM`. Two exceptions are explicit
  (`AuditEntry.by` / `forStore`): login, invitation acceptance and password reset name the account itself as actor
  (the panel sends its stored Bearer to those routes too, and it may be another admin's), and the reset uses the
  user's store (the token is looked up without the Host). Platform actions **must** name their store —
  `TenantResolutionFilter` resolves the Host on `/api/platform/**` too, and that's not the store being created.
- **Operator of the POS (#39):** optional `X-Platform-Operator` on `/api/platform/**`
  (`[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}`, one value; malformed ⇒ 400 `ApiError`, checked after network ⇒ 404 and key
  ⇒ 401) becomes `actor_label`. Absent ⇒ platform without operator (the operator form doesn't send it). It becomes
  mandatory in PROD-19.
- **What is audited:** refunds (`REFUND_REQUEST`, `REFUND_ISSUE` S/F, `REFUND_RECORD_EXTERNAL`); manual
  cancellation (both panel paths) and payment status; gateway, carrier, Stripe and POS credentials; the eight F17
  money settings (`SettingsServiceImpl.set` now returns the previous value); admin users (create, update,
  activate/deactivate, delete, resend, accept, reset) and logins; roles with permission names; store creation
  (not its idempotent replay), domain and verification, and since PROD-23 status changes, closing and owner access
  recovery; coupons, shipping rates and methods; product and variation **price** changes made from the
  panel. POS-originated catalog writes (`/api/sync/**`, webhooks) are not audited — `sync_webhook_logs` has them.
- **Immutable:** a `BEFORE UPDATE` trigger rejects any UPDATE (also from a console); DELETE stays allowed for
  retention (undecided, no purge job), store closing and test cleanup. No foreign keys, on purpose: history
  outlives stores and users.
- **Request id:** `config/RequestIdFilter`, the one filter `FilterRegistrationConfig` registers for real, at
  `HIGHEST_PRECEDENCE`, outside the Security chain (so a 401/404/429 from a security filter carries it too). It
  accepts an incoming `X-Request-Id` matching `[A-Za-z0-9][A-Za-z0-9._-]{7,63}` or generates a UUID, returns it in
  the response header, puts it in the MDC (`requestId`) and removes it in `finally`. It never rewrites the request
  header (`MercadoPagoController` reads MercadoPago's own `x-request-id` for the signature). Both log patterns
  carry it through `logging.pattern.correlation=%([%X{requestId:-}] ){}`. Two logback traps, each caught once:
  a bare trailing space is trimmed when the properties load (the log read `[id]o.u.u…`), hence the group; and
  logback takes the character right after a group's `)` as a literal unless it is `)`, `{` or `\`, so in our own
  `!default` pattern, where `%logger` follows, every line printed `%logger{40}` verbatim — hence the empty options
  `{}` (Boot's own pattern does `%clr(...){}`). `RequestIdIntegrationTest` renders that pattern with logback's
  `PatternLayout`, since the suite runs on the default profile. It's a correlation aid, not proof: anyone can
  choose it. PROD-15b adds the store, job ids and the id on the way to the POS (see the PROD-15b section).
- **Tests:** `audit/*` — `AuditTrailTransactionTest` and `AdminLoginAuditCommitTest` / `RefundAuditCommitTest`
  (committed transactions, `support/CommittedFixtures` + `purgeStore`), `AuditCatalogConsistencyTest`,
  `PlatformAuditTest`, `AuthAuditTest`, `UserRoleAuditTest`, `MoneyConfigAuditTest`, `SettingsAuditTest`,
  `CatalogShippingAuditTest`, and `AuditSecretSweepTest` (every audited operation with marked secrets; no row
  may contain one, and the expected actions must be there); plus `service/audit/AuditDiffTest`,
  `config/RequestIdFilterTest`, `config/RequestIdIntegrationTest`, and new assertions in
  `PosStoreOnboardingTest`, `StoreOnboardingCommitTest`, `AccountThrottleTest`, `PlatformNetworkTest`.
  `IntegrationTestSupport.auditRows(store[, action])` reads the rows inside the test transaction.

**PROD-23 — platform operation.** `controller/platform/PlatformStoreController` runs the stores that already
exist. It has the same `/api/platform/stores` base and the same `PlatformApiKeyAuthFilter` as onboarding. The
consumer is the UvoPOS backoffice; there is no screen.
- **Reading:**
  - `GET /` lists stores: `search` over slug, name, domain and owner e-mail; `status`; `companyId`; the M8 sort
    allowlist; 20 per page.
  - `GET /{id}` returns one store.
  - `GET /{id}/audit-events` takes `action`, `from` (inclusive) and `to` (exclusive), 50 per page, newest first.
    This is the consumer PROD-18 left open; the panel still doesn't read the audit.
  - `PlatformStoreDto` never carries POS secrets. Its POS links come from a projection
    (`PosConnectionRepository.findLinksByStoreIdIn`): loading the entity decrypts the token, so one row with a
    rotated key would take the whole list down.
- **States (V29):** `ACTIVE ⇄ SUSPENDED → CLOSED`, where `CLOSED` is terminal. `stores.status_changed_at` records
  the last change.
  - Every transition reads the store with `StoreRepository.findLockedById` (`PESSIMISTIC_WRITE`).
  - Suspending needs a `reason` (`StoreStatusReason`). It goes to the audit and isn't stored on the store.
  - Suspending a suspended store, or reactivating an active one, is a 200 no-op with no audit row. Anything on a
    closed store is a 400, including domain changes and verification. The audit action is `STORE_STATUS_UPDATE`.
  - A POS onboarding retry doesn't reactivate a store; `StoreOnboardingResponse.status` tells the POS where it
    stands.
- **Suspension:** `TenantResolutionFilter` answers 403 to any store that isn't active ("suspendida" or "cerrada")
  except on `/api/platform/**`.
  - For **suspended stores only**, the exact paths `POST /api/v1/webpay/return`, `/api/v1/mercadopago/webhook` and
    `/api/v1/stripe/webhook` also pass, so a payment made just before suspending still gets confirmed.
  - Jobs and the outbox keep running, and POS inbound keeps working. The scheduled POS reconciliation skips
    stores that aren't active (`findAllActive`).
  - Sessions are not revoked: after reactivation, tokens still valid work again.
- **Owner access recovery**, `POST /{id}/owner/access-recovery` with an optional `{email}`: active stores only,
  and the owner is `stores.owner_user_id`.
  - The account becomes active and admin, with no password, no reset token, `invitationAcceptedAt = null`,
    `tokenVersion + 1` and a new invitation.
  - It gets the owner role back (`StoreOwnerRole.ensureAssigned`, which also holds onboarding's F08 role
    creation). The role is recreated if it was deleted; an existing role keeps its permissions.
  - The new e-mail must not belong to another admin of the store. The 5-minute cooldown applies only when the
    e-mail doesn't change.
  - The invitation e-mail goes after commit through `StoreOwnerInvitedEvent`. Audit:
    `STORE_OWNER_ACCESS_RECOVERY`, never the token.
- **Closing**, `POST /{id}/close` with `{confirmSlug, reason}`:
  - Preconditions: the store is suspended, the slug is typed correctly, and nothing is in flight. "In flight"
    means gateway payments still pending (the reconciliation criterion, without its margin), paid orders without
    stock applied, outbox messages `PENDING` or `IN_FLIGHT`, and refunds `PENDING`. A failure is a 400 with the
    counts. Pending manual orders don't block.
  - One transaction does all of this:
    - admins are deactivated, lose their password and tokens, and get `tokenVersion + 1`;
    - customers get the same in a single UPDATE;
    - gateway configs are deleted, carrier credentials are nulled, the Stripe secrets are deleted, and
      `stripe_enabled` and `pos_sync_enabled` are set to false;
    - sync mappings and POS connections are deleted, which frees the company for a new onboarding. Without that,
      the `UNIQUE (external_id, company_id)` mapping would point the new store's sync at the old products;
    - the domain is released;
    - the status becomes `CLOSED`.
  - Gateways, carriers and connections are changed with statements that don't load the entity (decryption).
  - Audit: `STORE_CLOSE`, with names and counts only.
  - Data is kept and the slug stays taken. Closing again with the slug is a 200 no-op.
- **Export and purge are a documented procedure, not an API call:** deleting a merchant's data is irreversible,
  and the retention period is agreed outside the code. The procedure is below.
  `ClosedStorePurgeProcedureTest` runs these exact blocks (it extracts them between the markers). It also fails
  if a table with `store_id` isn't named in the purge, so a new table has to be handled here.
- **Tests:** `platform/StoreLifecycleTest`, `StoreClosureTest`, `OwnerAccessRecoveryTest`,
  `OwnerAccessRecoveryCommitTest` (committed: the e-mail goes after commit), `PlatformStoreQueryTest` and
  `ClosedStorePurgeProcedureTest` (committed). There are also new cases in `SuspendedStoreTest` (callbacks pass
  while suspended, nothing passes when closed), `PosReconciliationTest`, `AuditCatalogConsistencyTest`
  (`stores_status_valid`), `AuditSecretSweepTest` and `PlatformNetworkTest`.

**Closing a store: export and purge (PROD-23).** Run it with `psql` against the store's database, after
`\set store_id <id>`.

The export goes first, and can be repeated as needed. It hands the merchant their data: orders, items,
customers without credentials, addresses, products and variations. Image files are listed by the first purge
query. The files land in `psql`'s working directory.

Each line is `COPY ... TO STDOUT \g <file>`, not `\copy`: psql doesn't replace `:store_id` inside `\copy`, while
`\g` sends ordinary SQL, which it does replace. The manual run caught that.

<!-- prod23-export:start -->
```sql
COPY (SELECT * FROM orders WHERE store_id = :store_id ORDER BY id) TO STDOUT WITH CSV HEADER \g orders.csv
COPY (SELECT i.* FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.store_id = :store_id ORDER BY i.id) TO STDOUT WITH CSV HEADER \g order_items.csv
COPY (SELECT id, email, first_name, last_name, phone, account_status, created_at FROM customers WHERE store_id = :store_id ORDER BY id) TO STDOUT WITH CSV HEADER \g customers.csv
COPY (SELECT * FROM shipping_addresses WHERE store_id = :store_id ORDER BY id) TO STDOUT WITH CSV HEADER \g shipping_addresses.csv
COPY (SELECT * FROM products WHERE store_id = :store_id ORDER BY id) TO STDOUT WITH CSV HEADER \g products.csv
COPY (SELECT * FROM product_variations WHERE store_id = :store_id ORDER BY id) TO STDOUT WITH CSV HEADER \g product_variations.csv
```
<!-- prod23-export:end -->

The purge comes **only after the agreed retention**, counted from `stores.status_changed_at`, and only for a
`CLOSED` store: the `purge_store` temp table is empty otherwise, so every statement deletes nothing. It runs in
one transaction.
- First save the image paths the first query prints. The local storage driver doesn't group files by store, so
  they're deleted by hand, and only once the transaction has committed.
- The POS logs are keyed by company, not by store. The company comes from the `STORE_CLOSE` audit row, because
  the connection is already gone. They are kept if the company has a store again, since those rows can't be told
  apart.

<!-- prod23-purge:start -->
```sql
BEGIN;
CREATE TEMP TABLE purge_store ON COMMIT DROP AS
    SELECT id FROM stores WHERE id = :store_id AND status = 'CLOSED';
CREATE TEMP TABLE purge_companies ON COMMIT DROP AS
    SELECT DISTINCT jsonb_array_elements_text(details -> 'posCompanyIds')::bigint AS company_id
    FROM audit_events WHERE store_id IN (SELECT id FROM purge_store) AND action = 'STORE_CLOSE';
-- The files to delete afterwards (local driver: by hand; S3: the directory/<store>/ prefixes).
SELECT image_path FROM product_images WHERE store_id IN (SELECT id FROM purge_store)
UNION ALL SELECT featured_image FROM products WHERE store_id IN (SELECT id FROM purge_store) AND featured_image IS NOT NULL
UNION ALL SELECT images::text FROM products WHERE store_id IN (SELECT id FROM purge_store) AND images IS NOT NULL
UNION ALL SELECT image FROM product_variations WHERE store_id IN (SELECT id FROM purge_store) AND image IS NOT NULL
UNION ALL SELECT image FROM categories WHERE store_id IN (SELECT id FROM purge_store) AND image IS NOT NULL
UNION ALL SELECT image FROM home_banners WHERE store_id IN (SELECT id FROM purge_store) AND image IS NOT NULL
UNION ALL SELECT mobile_image FROM home_banners WHERE store_id IN (SELECT id FROM purge_store) AND mobile_image IS NOT NULL
UNION ALL SELECT store_logo FROM store_settings WHERE store_id IN (SELECT id FROM purge_store) AND store_logo IS NOT NULL
UNION ALL SELECT store_favicon FROM store_settings WHERE store_id IN (SELECT id FROM purge_store) AND store_favicon IS NOT NULL
UNION ALL SELECT avatar FROM users WHERE store_id IN (SELECT id FROM purge_store) AND avatar IS NOT NULL;
UPDATE stores SET owner_user_id = NULL WHERE id IN (SELECT id FROM purge_store);
-- Cascades from orders: order_items, order_status_history, coupon_usages, order_refunds, outbox_messages.
-- Before products (order_items.product_id is RESTRICT) and before users (order_status_history.user_id).
DELETE FROM orders WHERE store_id IN (SELECT id FROM purge_store);
-- Cascades from products: product_images, product_variations, product_variation_attributes, product_sync_mapping,
-- product_variation_sync_mapping.
DELETE FROM products WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM categories WHERE store_id IN (SELECT id FROM purge_store);
-- Cascades from attributes: attribute_values.
DELETE FROM attributes WHERE store_id IN (SELECT id FROM purge_store);
-- Cascades from customers: shipping_addresses.
DELETE FROM customers WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM coupons WHERE store_id IN (SELECT id FROM purge_store);
-- Cascades from methods and zones: shipping_rates.
DELETE FROM shipping_methods WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM shipping_zones WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM home_banners WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM settings WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM store_settings WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM payment_gateway_configs WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM pos_connections WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE store_id IN (SELECT id FROM purge_store));
DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE store_id IN (SELECT id FROM purge_store));
DELETE FROM users WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM roles WHERE store_id IN (SELECT id FROM purge_store);
DELETE FROM sync_webhook_logs WHERE company_id IN (SELECT company_id FROM purge_companies)
    AND company_id NOT IN (SELECT company_id FROM pos_connections);
DELETE FROM pos_webhook_receipts WHERE company_id IN (SELECT company_id FROM purge_companies)
    AND company_id NOT IN (SELECT company_id FROM pos_connections);
DELETE FROM stores WHERE id IN (SELECT id FROM purge_store);
COMMIT;
```
<!-- prod23-purge:end -->

The store's audit rows are deleted on their own retention, which can be longer. They have no foreign key and
survive the purge.

<!-- prod23-audit-purge:start -->
```sql
DELETE FROM audit_events WHERE store_id = :store_id AND NOT EXISTS (SELECT 1 FROM stores WHERE id = :store_id);
```
<!-- prod23-audit-purge:end -->

**Which exception to throw** (M1): a rule the caller broke, in words meant for them, is
`BusinessException` → 400 with the message. A request that can't be served but wasn't wrong is
`OutOfStockException`/`ShippingUnavailableException` → 409. Anything else — a wrapped SDK error, a
failed decryption — stays `IllegalStateException` and falls to the catch-all: 500, nothing leaked,
Sentry notified. Don't put `IllegalStateException` back on the 400 handlers; that mapping is what
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

**Database**: PostgreSQL via Flyway, schema in `src/main/resources/db/migration/` — **30 migrations,
currently up to `V30__outbox_request_id.sql`**, well past the original catalog/settings ones.
Everything from V8 on is a hardening or new capability rather than the original port: V8 stores, V11
store domains, V12 password reset, V13 stock/coupon integrity, V14 token versions, V15 the
permission catalogue, V16 the listing indexes, V17 TIMESTAMPTZ + the enum CHECK constraints, V18
store status, V19 order refunds, V20 `order_items.stock_applied`, V21 refund intent, V22 nullable
`users.password` (admin invitations), V23 `stores.domain_verified_at` (PROD-04), V24 `orders.paid_at`,
V25 the outbox, V26 the POS ordered inputs, V27 POS variants, V28 `audit_events` (PROD-18, the first
plpgsql: its immutability trigger), V29 the store lifecycle (PROD-23: `CLOSED`, `status_changed_at`, two audit
actions), V30 `outbox_messages.request_id` (PROD-15b).
`spring.jpa.hibernate.ddl-auto=validate`, so any entity change must be paired with a new Flyway
migration — **never edit an already-applied one**, add the next number (`V24__...sql`).

**Enum columns** (B6): every column that stores an enum name carries a `CHECK` listing its values
(V17). Adding a constant to an enum therefore needs a migration too — otherwise the new value is
rejected by the database at runtime, not at compile time. `stores.status` got its CHECK later (V18, widened by
V29 for `CLOSED`). `audit_events` has four such CHECKs (action, target type, actor type, result), and
`AuditCatalogConsistencyTest` fails when one of those five enums and its CHECK drift apart.

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

**Filters** (B8): the seven security filters are `@Component`, which would auto-register them in the
servlet chain on top of where `SecurityConfig` puts them. `FilterRegistrationConfig` disables that
auto-registration, so `SecurityConfig` is the single place the order is decided. A new security filter
needs its `setEnabled(false)` registration there too. The exception is `RequestIdFilter` (PROD-18): not a
`@Component`, registered for real there at `HIGHEST_PRECEDENCE` so it wraps the whole Security chain.

**Coverage** (PROD-25a): `./mvnw verify` writes a JaCoCo report to `target/site/jacoco/index.html` and
**fails if line coverage of the whole project drops below the floor**, the `jacoco.line-coverage.minimum`
property in `pom.xml` (`0.76`). It is a floor, not a target: the figure measured on 2026-10-16 with 523
tests was **76.51% of lines (4264 of 5573)**, a margin of about 28 lines that lose coverage, or about 37
new lines added without a test. Branches
were 56.3% and are not gated. CI and `release.yml` both run `clean verify`, so both enforce it. To raise
it, take the integer part of a full run's figure once that is a point above the floor; lowering it to make
a build pass is the wrong fix — write the test, or, for a real exception, lower it and leave the reason
in the `pom.xml` comment. The check runs *after* the report so the HTML exists when it fails. Two traps:
`verify` with a subset (`-Dtest=Foo`) measures a sliver and fails the floor — use `-Djacoco.skip=true`
(`./mvnw test -Dtest=Foo`, the usual single-class command, never reaches it); and `target/jacoco.exec`
accumulates between runs, so a `verify` without `clean` can pass on stale data. The floor is global, so it
protects no area in particular. Thinnest by lines at that measurement: `service.order.event` 33%,
`controller.admin.report` 37%, `service.catalog` 59%, `controller.pos` 60%, `service.report` 61%,
`service.payment` 64% (478 lines, the money path), `service.pos` 65%. The earlier 63.7% / 9.2% figures
predate the F01–F24 round and no longer describe anything. The frontend has no coverage measurement.

**PROD-22 — supply chain and repository rules.** `.github/` holds `dependabot.yml` (Maven `/`, npm
`/frontend`, GitHub Actions `/`; weekly on Mondays, a 7-day `cooldown` on version updates, minor and patch
batched per ecosystem, every major its own pull request so a payment SDK's API change gets looked at; the
three `io.jsonwebtoken:*` artifacts are one group with every update type so they can't be split),
`workflows/dependency-review.yml` (fails a pull request that adds or upgrades to a dependency with a
high/critical advisory; pinned `@v5.0.0` because that action publishes no floating `v4`/`v5` tag, so
`@v5` would not resolve) and `CODEOWNERS` (one owner today; the sensitive paths are the build and workflow
files, the migrations, `security/`, `config/`, `service/security/`, `controller/auth/` and the payment,
platform and POS packages). `ci.yml` and `release.yml` run with `contents: read`; only the job that
creates the GitHub Release holds `contents: write`. Dependabot reads its file from the default branch only.

**None of this blocks anything by itself: the rules live in GitHub's settings, which no YAML shows.**
Read through the public API on 2026-10-18: the repository is public, **`main` had no branch protection and
no ruleset** (a push straight to `main` ran no check before it landed), and Qodana had failed once in 138
runs — `critical: 0` in `qodana.yaml` can turn the job red, but nothing required it. Secret scanning, push
protection and Dependabot alerts can't be read without admin rights. What `main` should require, by exact
job name: `Backend (Maven)`, `Frontend (npm)`, `qodana`, `Dependency review`, via a pull request with no
approval count while there is a single maintainer (GitHub does not count an author's approval of their own
pull request). **`Dependency review` needs "Dependency graph" switched on** in Settings → Code security — it
did not come on by itself here even though the repository is public, and with it off the job fails with
"Dependency review is not supported on this repository" (it did, on PR #62). Don't add it to the ruleset
until it has run green once, or it would block every pull request. `CODEOWNERS` only asks for review until "Require review from Code Owners" is turned on, which
needs a second person. Known gaps: GitHub's dependency graph for Maven reads `pom.xml` only, so versions
the Spring Boot BOM manages are invisible to alerts and to dependency review; Dependabot's pull requests get
a read-only token and no repository secrets, which may leave the `qodana` check red on them; actions are
pinned by tag, not by commit SHA.

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
  to key on. The admin one also requires the account to be **active** (PROD-06) and is the only one
  with a screen (`/admin/aceptar-invitacion`); resending is `POST /api/admin/users/{id}/resend-invitation`
  (`users.manage`). See the invitation bullets above.
- `/api/admin/**` (`ROLE_ADMIN`) and `/api/customer/**` (`ROLE_CUSTOMER`) are guarded; `/api/v1/**` is fully public.
- **Everything under `/api/customer/**` answers 404 as shipped** — `app.customer-accounts.enabled`
  (`CUSTOMER_ACCOUNTS_ENABLED`) is off, so `CustomerAccountsGateFilter` intercepts the whole prefix
  ahead of the Security chain, the checkout issues no invitation and no invitation email is sent. The
  authority on the flag is `CustomerAccountsPolicy`, the only class that reads it. See the PROD-07
  bullet in "Architecture" for why, and for what turning it on would require.
- `/api/platform/**` (store onboarding) uses `X-Platform-Key`, not JWT, and only answers from the private
  network (POS-6) — see "Multi-tenancy". An optional `X-Platform-Operator` names the operator for the audit
  log (PROD-18).
- POS integration (`/api/sync/**`, `/api/webhooks/pos/**`) uses its own HMAC/API-key filters, separate from JWT.

### External integrations — all "off until configured"
Every external integration in this codebase follows the same pattern: a blank/missing env var leaves it inactive (log-and-skip, never throw), so the app runs fully in dev/CI without any of these configured.
Applies to: `EmailService` (SMTP, `spring.mail.*` — password reset, order confirmation, and the customer and admin invitations), Stripe/Webpay/MercadoPago (`PaymentGatewayConfig` per store, plus shared Webpay parent commerce code in `application.properties`), Chilexpress/Correos de Chile
shipping quotes, S3 file storage (`app.storage.driver=s3`), and Sentry (`SENTRY_DSN`/ `VITE_SENTRY_DSN`). **Webpay/MercadoPago/Stripe are fully wired (backend + checkout UI) but have never been tested against real sandbox credentials** — don't assume they work end-to-end without that verification.

**Tokens never go to the log (PROD-17a).** Webpay's `token_ws` is the credential of that transaction and `/api/v1/webpay/return` is public, so the controller logs only an 8-character, sanitised prefix (`token_prefix=01ab3f9c...`; enough for `payment_id LIKE '01ab3f9c%'`). The prefix is cut *before* it is sanitised because the parameter is unauthenticated input: logging it raw lets anyone forge log lines with newlines or inflate the log. Invitation and reset tokens only travel inside the email body, and `EmailServiceImpl` logs recipient and subject, never the body — keep it that way even when SMTP is unset in dev ("print the link so I can click it" is exactly how this regresses). Both are pinned by `WebpayReturnLoggingTest` and `EmailServiceImplLoggingTest`. The exception *message* is logged but not the stack. The Transbank SDK (6.2.1) puts the token in the request URL path, and its own messages don't echo it (checked in the sources: validation says `'token' can't be null…`, HTTP errors carry Transbank's `error_message` and are read from `getErrorStream()`). If an SDK upgrade or the JDK's `HttpURLConnection` ever throws a message that carries the URL — `Server returned HTTP response code … for URL: …` is the JDK's wording — the token comes back through `e.getMessage()`; re-check on upgrades. **Not covered:** Sentry on the frontend — the invitation and reset pages carry `?token=` in the URL (see `PLAN_PRODUCCION.md` §7.3).

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

### Browser E2E (PROD-14a)
The backend suite rolls every test back, so nothing that happens **after commit** ever runs there: the
emails, the stock decrement (`StockDecrementListener`) and the sale reaching the POS through the outbox.
`frontend/e2e/` is a Playwright suite that drives the real thing: the packaged jar, Vite, and two
doubles. It covers the whole circuit for two stores, `tienda-pos` and `tienda-panel`. `demo` can't be
used because V8 seeds it with no admin, and the platform API can't create it.

```
./mvnw -o package -DskipTests                 # the launcher runs target/uvo-store-*.jar, so rebuild after backend changes
cd frontend && npx playwright install chromium   # once
cd frontend && npm run e2e                    # ~1-2 min; npm run e2e:report opens the last HTML report
```

**What runs.** `playwright.config.ts` starts three `webServer`s on their own ports, so the suite
runs alongside the normal dev setup on 8080/5173:

| Piece | Port | Notes |
|---|---|---|
| Doubles (`e2e/doubles/server.ts`) | 18089 HTTP, 12525 SMTP | One dependency-free Node process; it does need Node 24 to run `.ts` directly. Tests read what it received over HTTP (`/__doubles/pos/orders`, `/__doubles/mail?to=`), because they run in another process. |
| Backend (`e2e/launch/backend.ts`) | 18080 | See below. Its log goes to `e2e/.logs/backend.log`. |
| Vite | 15173 | `VITE_DEV_PROXY_TARGET=http://demo.localhost:18080` |

**The launcher.** `e2e/launch/backend.ts`:
- **Drops and recreates `uvostore_e2e` on every run** with `pg`, then runs `java -jar` against it.
  It refuses any database name that doesn't end in `_e2e`.
- **Database credentials** come from `E2E_DB_USERNAME`/`E2E_DB_PASSWORD`. Without them it falls back
  to `DB_USERNAME`/`DB_PASSWORD` from the backend `.env`.
- **Working directory** is a temp dir, so spring-dotenv does **not** load that `.env`, which points
  at the dev database.
- **Environment:**
  - fixed test-only secrets, from `e2e/support/env.ts`;
  - `POS_ENABLED=true` and `OUTBOX_INTERVAL_MS=1000`;
  - SMTP pointed at the double, with no STARTTLS or auth;
  - `PUBLIC_STOREFRONT_URL=http://{host}:15173`, so invitation links land on this Vite;
  - rate limiting off, since every login comes from 127.0.0.1;
  - the other scheduled jobs off.

**The doubles.**
- **POS double.** It implements the contract as `PosClient` does today; **POS-1 is still open**,
  so it's the implemented contract, not a confirmed one.
  - `POST /api/v1/orders/external` records the auth headers, `Idempotency-Key`, `X-Request-Id`
    and the body.
  - `POST /api/v1/products/snapshot` answers empty.
- **SMTP double.** Just enough SMTP for JavaMail's `SimpleMailMessage`. It decodes
  quoted-printable bodies and RFC 2047 subjects ("Confirmación…"), so tests read the link exactly
  as the owner would.
- **The POS side of each test** lives in `e2e/support/pos.ts`. It pushes the catalogue with
  `/api/sync/product` and signs `stock-updated` webhooks as `HMAC-SHA256(body + timestamp)`.

**The circuit** (`e2e/tests/circuito.spec.ts`, serial, shared module state). Steps 3–7 run for both
stores; steps 8 onward use `tienda-pos`.

1. **`tienda-pos` signs up** through the platform API with its POS link, the way the POS back office
   will.
2. **`tienda-panel` signs up** through `/plataforma/nueva-tienda`.
3. **Owner access.** The owner opens the **invitation email's link**, sets a password, logs out and
   logs in again.
4. **Configuration in the panel.** A shipping zone, and "Envío habilitado" turned off. This is a
   full settings PUT, and the later POS sale proves it didn't switch POS sync off.
5. **Products.** The POS pushes `tienda-pos`'s product; `tienda-panel`'s is created in the panel form.
6. **Purchase.** Find the product in `/shop`, then cart → 3-step checkout with "Pago contra
   entrega". The "Recibimos tu pedido" email arrives.
7. **Payment in the panel.** The order is set to `PAID` in its detail page. The stock drops in
   `/admin/products` and the confirmation email arrives.
8. **The sale reaches the POS double.**
   - It carries `Bearer <apiKey>`, `X-Company-ID`, `Idempotency-Key=<order>:<company>`, an
     `X-Request-Id` and the external `product_id`.
   - `tienda-panel`'s sale doesn't go out, because that store has no POS link.
9. **POS stock update.** A signed `stock.updated` shows up on the storefront page and in the panel.
   Resending the same request gets 401 `REPLAYED_WEBHOOK`.
10. **Tracking.** The panel saves a tracking number, and `/track-order` shows it.
11. **Isolation.**
    - **API:** `tienda-pos`'s token with `Host: tienda-panel.localhost` gets 401.
    - **Browser:** that session placed in `tienda-panel`'s origin ends at `/admin/login` with the
      token cleared.
    - **Catalogue:** neither store's catalogue shows the other's product.
12. **Direct reload** of `/admin/orders/:id`, `/admin/settings/general`, `/product/:slug` and
    `/track-order?order=`.

**Conventions.**
- **Selectors** use role, label, placeholder or text; the app has no `data-testid`.
  - The two Radix Selects in `OrderDetail` have no accessible name, because their `<Label>` doesn't
    point at them. `setPaymentStatus` finds the payment one through the text's parent.
  - The same gap exists for "Categoría" in `ProductForm`.
- **Panel sessions** are captured once after the UI login, from `localStorage`
  (`uvostore_admin_auth`). Later tests restore them with `restorePanelSession`, so each test keeps
  its own fresh page and its trace.
- **Direct API calls** go through `e2e/support/http.ts` (`node:http`):
  - `fetch` refuses a custom `Host`;
  - Node's resolver on Windows can't resolve `*.localhost`;
  - so calls go to 127.0.0.1 with the store's `Host` set by hand.

**The Vite proxy forwards the subdomain.** Before PROD-14a the proxy pinned `Host` to the target's
host, so every store opened through Vite was really the target store (`demo`). Now `proxyReq`
behaves like this:
- a browser on `<slug>.localhost:<port>` reaches the backend as `<slug>.localhost:<target port>`;
- a bare `localhost` or `127.0.0.1` still goes to the target, as before.

This also makes several stores usable by hand in dev.

**Tooling.**
- `tsconfig.e2e.json` is referenced from `tsconfig.json`, so `tsc -b` (and `npm run build`)
  type-check the suite.
- Vitest excludes `e2e/**`; without that its default include would run the `*.spec.ts` files.
- ESLint has its own block for `e2e/**` (Node globals; `rules-of-hooks` off because of Playwright's
  `use`).

**CI.** The `e2e` job in `ci.yml`:
1. packages the jar with `-DskipTests` (the backend job runs the suite);
2. installs Chromium with `--with-deps`;
3. runs `npm run e2e` against its own `postgres:18` service.

On failure it uploads `playwright-report/`, `test-results/` (traces and screenshots) and the
backend log for 7 days. **#33 is pointing this same harness at staging.**

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
  - `PosNotificationListener` (gone since PROD-13b) and `StockDecrementListener` combined
    `@TransactionalEventListener(phase = AFTER_COMMIT)` with a plain `@Transactional`, which
    Spring 7 now rejects at startup — both need
    `@Transactional(propagation = Propagation.REQUIRES_NEW)`. If you add another `AFTER_COMMIT`
    listener that also needs a transaction, use the same pattern — but anything that goes to the
    network belongs in the outbox, not in a listener.
- **Testing**: **530 backend tests across 100 classes** (as of PROD-13a) (JUnit + `IntegrationTestSupport`,
  one Spring-managed transaction per test, auto-rolled-back — `AFTER_COMMIT` listeners never fire
  under this setup, which is intentional; the Playwright suite of PROD-14a covers them, see "Browser E2E") and **142 frontend tests across 32 files** (Vitest +
  React Testing Library, `npm run test`), both wired into CI (`.github/workflows/ci.yml`) and the
  release workflow (`.github/workflows/release.yml`, triggered by pushing a `vX.Y.Z` tag — builds
  artifacts and publishes a GitHub Release with auto-generated notes). Most of that growth is the
  audit rounds: nearly every `C`/`A`/`M`/`B`/`F` finding left a test that fails when its fix is
  undone, so a red suite after a refactor is usually an invariant being removed, not a flaky test.
  Five recurring devices worth knowing before writing a new one:
  - **`disableShipping(store)` before any checkout.** A fresh store covers no shipping zone, so
    checkout legitimately answers 409 (A7). Tests that don't care about delivery call this.
  - **`@WithCustomerAccounts` to touch `/api/customer/**`.** Buyer accounts ship off (PROD-07), so
    without it the whole prefix answers 404 — including `loginCustomer`, whose `status().isOk()` then
    fails. One composed annotation rather than six copies of
    `@SpringBootTest(properties = …)`, so the opting-in classes share a single extra Spring context.
    Need a customer credential *without* the login endpoint? `customerToken(store, customer)`.
  - **`@WithPosIntegration` / `@WithShippingCarriers` to touch the POS or a carrier.** Both ship off
    (PROD-12), so without them `/api/sync/**` and `/api/webhooks/pos/**` answer 404 and configuring a
    carrier on a shipping method is a 400. Unit tests of `PosOrderNotifier` just need a policy in the
    constructor — `PosPolicyFixture` has the three combinations that matter.
  - **Package-visible builder methods to assert outbound payloads** without SMTP or network:
    `MercadoPagoServiceImpl.buildPreferenceRequest`, `PaymentServiceImpl.buildSessionParams`,
    `OrderEmailComposer.invitationBody`, `AdminInvitationMailer.body`. That's why those
    tests live in the service's own package.
  - **The outbox scheduler is off in tests** (`app.outbox.scheduler.enabled=false` in surefire,
    `pom.xml`): it runs every few seconds, and every cached context would dispatch over the shared
    database and steal the rows a test is checking. Tests call `OutboxDispatcher.dispatch(channel)`
    directly; `OutboxCrashRecoveryTest` is the committed-transaction model, with `@MockitoBean` for
    `PosClient` and `EmailService`.
  - **Tokens are read from the database, not a mailbox.** There is no SMTP in tests and
    `EmailServiceImpl` logs and skips, so `PasswordResetTest`, `CustomerInvitationTest` and
    `AdminInvitationTest` all query the row. When a test needs to age a timestamp the service
    itself just wrote, it does so with a native `UPDATE` — and **`flush()` before `clear()`**, or
    `clear()` discards the endpoint's own pending writes and the row reads as if nothing happened.
  - **Audit rows written inside a `REQUIRES_NEW` commit even under `IntegrationTestSupport`** (PROD-18):
    today that's only `RefundIntentStore` (`REFUND_REQUEST`, the refund `FAILURE`), which no rolled-back test
    reaches. Tests of those paths confirm for real and purge (`support/CommittedFixtures.purgeStore`, also used by
    `AdminLoginAuditCommitTest`); `StoreOnboardingCommitTest` deletes its `audit_events` too.
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