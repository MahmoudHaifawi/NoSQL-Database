# M4a — Security: BCrypt + JWT with Spring Security

Status: design approved (2026-10-09), pending spec review before implementation planning.
Milestone: M4a (security slice of M4) of the capstone roadmap.
Workflow: work directly on `master`.

## 1. Goal

Replace the system's placeholder auth — plaintext password comparison and role-string "tokens"
(`"admin"`/`"user"`/`"internal"`) — with real security:

- **BCrypt** password hashing (store + verify).
- **Signed JWTs** (HS256) carrying `username` / `role` / expiry, issued on login and validated on
  every protected request.
- **Spring Security** (5.7, on Spring Boot 2.7.5) in all three modules: the Node and
  BootstrappingNode as stateless JWT resource servers, the DBMS as a form-login web client backed
  by a custom authentication provider.

Inter-node service calls (today's literal `"internal"` token) use a minted **internal service
JWT**. Behaviour of the cluster (routing, replication, indexing) is unchanged; only the auth layer
changes.

### Non-goals (YAGNI)

- No refresh tokens, token revocation/blacklist, or logout-everywhere.
- No OAuth2 / OIDC / external IdP (Keycloak was explicitly deferred).
- No TLS/HTTPS (transport security is a separate concern).
- No per-user/per-document permissions beyond the three roles; no password reset flow.
- No Spring Boot 3 / Spring Security 6 upgrade (stay on 2.7.5 / 5.7).

## 2. Shared foundation (all modules)

**Dependencies** (added to each module's `pom.xml`): `spring-boot-starter-security` (provides
`BCryptPasswordEncoder` and the filter-chain infrastructure) and jjwt
(`io.jsonwebtoken:jjwt-api:0.11.5` + runtime `jjwt-impl` + `jjwt-jackson`).

**Shared secret.** A single HMAC secret resolved from the `JWT_SECRET` environment variable, with a
development default in `application.properties`
(`app.jwt.secret=${JWT_SECRET:dev-secret-change-me-at-least-256-bits-long-xxxxx}` — must be ≥ 32
bytes for HS256). The same value in every module so any module can validate a token any node issued.

**Token shape.** HS256 JWT with claims: `sub` = username, `role` = `ADMIN|USER|INTERNAL`, `iat`,
`exp`. User tokens expire in 1 hour (matching the current cookie `maxAge`). Internal/service tokens
are minted on demand for peer calls with a short expiry (5 minutes — longer than any single
broadcast).

**Authorities.** A parsed token grants authorities that encode the existing privilege hierarchy:

| role claim | granted authorities |
|------------|---------------------|
| USER | `ROLE_USER` |
| ADMIN | `ROLE_ADMIN`, `ROLE_USER` |
| INTERNAL | `ROLE_INTERNAL`, `ROLE_ADMIN`, `ROLE_USER` |

So `hasRole("ADMIN")` admits ADMIN and INTERNAL; `hasRole("USER")` admits all three — exactly
today's `isAdminToken` / `isUserToken` semantics. `hasRole("INTERNAL")` admits only service calls.

**Scheme.** `Authorization: Bearer <jwt>`. The JWT filter strips an optional `Bearer ` prefix and
tolerates a bare token for robustness; all issuers/forwarders send `Bearer <jwt>`.

**`JwtService`** (a small class, duplicated per module since there is no shared Maven module):
- `String generateToken(String username, String role, Duration ttl)`
- `String generateServiceToken()` — `role=INTERNAL`, 5-minute ttl
- `Jws<Claims> parse(String token)` — validates signature + expiry; throws on invalid/expired
- helpers to read `username` / `role` and build Spring `GrantedAuthority` list from a role.

**`JwtAuthFilter`** (`OncePerRequestFilter`, duplicated per resource-server module): if an
`Authorization` header is present, parse+validate it and set an authenticated
`UsernamePasswordAuthenticationToken` (principal = username, authorities from role) in the
`SecurityContext`; on parse failure, leave the context empty (the filter chain then returns 401/403).
No header = anonymous (so `permitAll` endpoints still work).

## 3. Node (resource server + token issuer)

**Passwords.** `AuthenticationService.authenticateUser` verifies with
`BCryptPasswordEncoder.matches(raw, storedHash)`. `AdminOperations.addUser` hashes the incoming
password with `encode(...)` before writing to `info.json`. The seed `Node/data/info.json` admin
(`mahmoud`) gets a BCrypt hash of `admin` instead of the plaintext.

**Login.** `/login` (permitAll) authenticates, then returns a **user JWT**
(`generateToken(username, role, 1h)`) as the `Response` content — replacing the returned role
string. `generateToken` is called by the authentication service / controller.

**Filter chain** (`SecurityFilterChain` bean; stateless session, CSRF disabled, `JwtAuthFilter`
before `UsernamePasswordAuthenticationFilter`):
- `POST /login` → permitAll
- `/admin/**` → `hasRole("ADMIN")`
- `/user/**`, `/write/**` → `hasRole("USER")`
- `/network/**` → `hasRole("INTERNAL")`
- anything else → `authenticated()`

**Controller role branches.** Controllers that currently branch on the token string keep their
branching but read the caller's role from the `SecurityContext` (a `SecurityUtils.currentRole()`
helper reading the authentication authorities), not from a `@RequestHeader` token:
- `WriteController` create/update/delete: `role == INTERNAL` → apply verbatim (replication);
  otherwise origin path (+ broadcast).
- `AdminController` create database / add user, `IndexController` create/drop: broadcast to peers
  only when `role == ADMIN` (an INTERNAL caller is a peer and must not re-broadcast).
  (Today this is `token.equals(Token.ADMIN)`; INTERNAL callers skip the broadcast.)

The old `AuthenticationService.isAdminToken/isUserToken/isInternalToken` and the `Token`
string-compare constants are removed (role names kept as constants where needed).

**Peer calls.** `Node` model methods (`createDocument`, `updateDocument`, `deleteDocument`,
`createIndex`, `dropIndex`, `createSchema`, `addUser`, `addDatabase`, network wiring) attach
`Authorization: Bearer <serviceJwt>` from `JwtService.generateServiceToken()` instead of the literal
`"internal"`.

## 4. BootstrappingNode (resource server)

Same `JwtService` + `JwtAuthFilter` + shared secret. Filter chain:
- `GET /test` → permitAll (health)
- `POST /getUserNode` → **permitAll** — it executes during the login handshake, before the user
  holds a token; it only resolves which node a user routes to (low sensitivity).
- `POST /createNewUser` → `hasRole("ADMIN")` (creating users is an admin action).
- `/network/**` → `hasRole("INTERNAL")`.

The bootstrapper mints an internal service JWT (it holds the shared secret) for its start-up
`/network/add/nodes` and `/network/assign/self` calls to each node.

**Add-user chain.** Creating a user traverses DBMS → bootstrap `/createNewUser` → node
`/admin/user/add`. Each hop must now carry an admin-capable token: the DBMS forwards the logged-in
admin's JWT to `/createNewUser`, and the bootstrapper, having authorized the caller as ADMIN, mints
an internal service JWT for the downstream `/admin/user/add` call (the node requires `hasRole`
ADMIN, which INTERNAL satisfies). The `ConnectionRequest.createNewUser` call and the bootstrap
`UserController.createNewUser` handler gain the token parameter/propagation accordingly.

## 5. DBMS (Spring Security form-login web client)

`spring-boot-starter-security`. A `SecurityFilterChain` bean replaces the custom `AuthInterceptor`
and `WebConfig`:
- `formLogin` using the existing Thymeleaf `/login` page (`loginPage("/login")`,
  `loginProcessingUrl("/login")`, default success → `/dashboard`).
- permitAll: `/login`, static assets.
- `authenticated()`: `/dashboard`, `/read/**`, `/update/**`, `/index/**`, `/createSchema`,
  `/createDocument`, `/admin/**`.
- CSRF: disabled for parity with the existing form posts (noted as a deliberate simplification;
  real deployments would keep CSRF on for a cookie-session form app).

**Custom `AuthenticationProvider`.** On form submit it authenticates by calling the node login the
same way the gateway does today: resolve the node via bootstrap `getUserNode`, then node `/login`
with the username/password. On success it has the node-issued **JWT** (and the node URL). It returns
an authenticated `UsernamePasswordAuthenticationToken` whose authorities come from the JWT's role,
carrying the JWT + nodeURL in its details. A success handler (or the provider via the session) stores
`token` (= the JWT) and `nodeURL` in the HTTP session, so the existing controllers keep reading
`session["token"]` / `session["nodeURL"]` and forwarding the JWT as Bearer — no controller rewrites.

`LoginController.loginPost` is removed (Spring Security handles the POST); the GET `/login` view
stays (controller or view mapping). The failed-login message surfaces via Spring Security's
`?error` redirect, shown on the existing login page.

## 6. Error handling

| Case | Result |
|------|--------|
| Wrong password at login | Node `/login` → ERROR; DBMS provider → `BadCredentialsException` → login page with error |
| Missing/invalid/expired JWT on a protected node endpoint | 401/403 (filter chain), no action taken |
| Tampered JWT (bad signature) | parse throws → unauthenticated → 401/403 |
| User token on an `/admin/**` endpoint | 403 (insufficient role) |
| Internal service token | full access (ROLE_INTERNAL ⊇ ADMIN ⊇ USER) |
| DBMS request with no authenticated session | redirect to `/login` (form-login entry point) |

## 7. Testing (TDD)

Node:
- `JwtService`: generate→parse round-trip; expired token rejected; tampered token rejected; role→authorities mapping.
- BCrypt: `authenticateUser` accepts the right password, rejects the wrong one, against a hashed seed.
- `/login` issues a parseable JWT with the correct `sub`/`role`.
- Filter chain authz via MockMvc: `/admin/**` with a USER token → 403, with ADMIN → ok; `/network/**` requires INTERNAL; `/login` open.
- Controller branch logic: INTERNAL role → apply/no-broadcast; ADMIN role → broadcast (using an authenticated context).

BootstrappingNode:
- authz via MockMvc: `/getUserNode` + `/test` open; `/createNewUser` needs ADMIN; `/network/**` needs INTERNAL.

DBMS:
- custom `AuthenticationProvider`: mocked node login returns a JWT → provider authenticates with role authorities; wrong password → `BadCredentialsException`.
- form-login gate: unauthenticated `/dashboard` → redirect to `/login`.

Live (all modules running):
- login (mahmoud/admin) → session holds a JWT → create DB/schema, insert, read, update, delete, create index, query — all succeed with the Bearer token forwarded.
- wrong password → login fails on the page.
- a hand-tampered/expired token on a direct node call → 401/403.

## 8. Rollout / sequencing notes

- Node first (issuer + resource server), then BootstrappingNode (validator), then DBMS (form-login
  client) — so each layer can be validated against the one below.
- Re-seeding `info.json` with a BCrypt hash is a one-time data change bundled with the Node work.
- Internal service-token minting must land with (or before) the peer-call changes, or replication
  breaks; they are in the same Node task.

## 9. Out of scope / follow-ups

- Shared `common` Maven module to de-duplicate `JwtService`/`JwtAuthFilter` — future cleanup.
- Refresh tokens, revocation, TLS, OAuth2/OIDC (Keycloak) — later milestones if desired.
- M4b (cross-module test coverage) and M4c (web-demo polish) remain separate milestones.
