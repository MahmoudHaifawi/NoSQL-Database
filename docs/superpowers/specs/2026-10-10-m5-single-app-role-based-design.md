# M5 — Single app, role-based launch (one binary, many roles)

Status: design approved (2026-10-10), pending spec review before implementation planning.
Milestone: M5 of the capstone roadmap.
Workflow: work directly on `master`.

## 1. Goal

Today the system is three separate Spring Boot applications — `Node` (data node, 8080),
`BootstrappingNode` (coordinator, 8079), `DBMS` (gateway + web UI, 8078) — each its own Maven
project, build, and jar. Running the system means building and launching three different artifacts.

M5 turns this into **one artifact that launches in a chosen role**, the way real distributed
databases ship (one binary, many roles — Cassandra, CockroachDB, etc.):

```
java -jar nosql-db.jar --role=node          # or --spring.profiles.active=node
java -jar nosql-db.jar --role=coordinator
java -jar nosql-db.jar --role=gateway
```

Data nodes remain **separate processes** (that is the point of a distributed store); what unifies is
the build and the launcher. The structure is a **multi-module Maven reactor with a shared `common`
library**, which gives compile-time-enforced module boundaries — the production-grade shape, not a
flat catch-all module.

### Success criteria

- A single executable jar (`app`) runs as `node`, `coordinator`, or `gateway` by profile/flag; only
  that role's beans are created.
- Behaviour is unchanged: every endpoint, the routing, replication, indexing, security, and the web
  UI work exactly as before M5. The HTTP/JSON wire protocol between components is byte-compatible.
- The full existing test suite (149 tests) stays green, plus new tests proving role isolation.
- One `Dockerfile`; `docker-compose.yml` runs that one image five times with different profiles.
- Local run and Docker run both work (single node locally; Node0/1/2 in Docker).

### Non-goals (YAGNI)

- **No Java package renames.** Modules keep their current packages (`com.database.atypon.Node`,
  `…BootstrappingNode`, `…DBMS`); a Maven module is not a Java package, so files move verbatim.
- No behavioural change to any endpoint, routing, replication, indexing, or security rule.
- No change to the HTTP/JSON wire protocol between components.
- No Spring Boot 3 / Spring Security 6 upgrade (stay on 2.7.6 / 5.7).
- No new product features — M5 is purely structural.
- No merging of data nodes into other processes; nodes stay independent processes.

## 2. Target structure

A parent reactor pom aggregating five modules. Existing module directories are reused as-is to
minimise path churn (optionally renamed to lowercase later; not required).

```
nosql-db/                     parent pom (packaging=pom; parent = spring-boot-starter-parent 2.7.6)
├── common/                   shared library  → com.database.atypon.common.*      (Phase 2 target)
├── Node/                     data-node library    (com.database.atypon.Node.*)
├── BootstrappingNode/        coordinator library  (com.database.atypon.BootstrappingNode.*)
├── DBMS/                     gateway library + templates/static (com.database.atypon.DBMS.*)
└── app/                      the ONLY Spring Boot application → the fat jar
                              (com.database.atypon.app.*)  depends on Node + BootstrappingNode + DBMS
```

- The **parent pom** declares `<packaging>pom</packaging>`, lists the `<modules>`, and sets its own
  `<parent>` to `spring-boot-starter-parent:2.7.6` so Boot's dependency management flows to every
  child. Each child's `<parent>` becomes `nosql-db` (not the Boot starter directly). In Phase 1 the
  `<modules>` are `Node`, `BootstrappingNode`, `DBMS`, `app`; `common` is added in Phase 2.
- `Node`, `BootstrappingNode`, `DBMS` become **plain libraries**: packaging `jar`, and the
  `spring-boot-maven-plugin` (repackage) is **removed** from them so they produce ordinary jars, not
  executable ones. They keep their existing dependency blocks in Phase 1.
- `app` is the **only module that repackages** (declares `spring-boot-maven-plugin` with the
  `repackage` goal + the lombok exclude), producing the single runnable fat jar. It depends on the
  three role libraries; their transitive dependencies (web, thymeleaf, security, jjwt, json) come
  with them. The gateway's Thymeleaf `templates/` and `static/` ship inside the `DBMS` jar and are on
  `app`'s classpath, so Thymeleaf resolves them unchanged.

## 3. Role selection and the sole application

### The launcher

`app` contains the **only** `@SpringBootApplication`, `com.database.atypon.app.NoSqlApplication`,
with `scanBasePackages = "com.database.atypon.app"` so its component scan finds only `app`'s own
package (the role configs) — never the role libraries directly.

Three role configurations live in `com.database.atypon.app`, each gated by profile and scanning
exactly one module's package:

```java
@Configuration @Profile("node")        @ComponentScan("com.database.atypon.Node")              class NodeRole {}
@Configuration @Profile("coordinator") @ComponentScan("com.database.atypon.BootstrappingNode")  class CoordinatorRole {}
@Configuration @Profile("gateway")     @ComponentScan("com.database.atypon.DBMS")               class GatewayRole {}
```

Only the active profile's config contributes a component scan, so **only that role's beans are
created** — the other roles' controllers, security chains, and services never instantiate. This is
what makes one context safely host three roles.

### Choosing the role

The idiomatic mechanism is the Spring profile: `--spring.profiles.active=node`. As a convenience,
`NoSqlApplication.main` also accepts `--role=<node|coordinator|gateway>` and maps it to the profile
before `SpringApplication.run`. If neither is supplied (no role/profile active), the app **fails
fast** with a one-line usage message and a non-zero exit, rather than booting an empty context.

### Why the module `main` classes are removed

The three existing `*Application` classes (`NodeApplication`, `BootstrappingNodeApplication`,
`DbmsApplication`) are `@SpringBootApplication` themselves. If `app`'s role scan picked one up, it
would re-trigger auto-configuration nested inside the running app. They are therefore **deleted from
the library modules' main sources**; the sole runtime entry point is `NoSqlApplication`.

Two pieces of logic currently carried by those mains are relocated:

- **Coordinator mesh wiring** — `BootstrappingNodeApplication`'s `CommandLineRunner` (wires peers
  into each node at start-up) becomes a `@Component @Profile("coordinator")` `CommandLineRunner` in
  the `BootstrappingNode` module. It runs only under the coordinator role and is inert during tests
  (no coordinator profile) — a bonus, since tests never wanted it to fire.
- **Gateway port** — `DbmsApplication`'s inner `WebServerFactoryCustomizer` that hardcodes `8078` is
  deleted; the port moves to profile properties (below).

### Configuration (in `app`'s resources)

- `application.properties` — shared only: `app.jwt.secret=${JWT_SECRET:dev-secret-…}` (same value all
  roles use to validate each other's tokens). No `spring.profiles.active` default.
- `application-node.properties` — `server.port=8080`.
- `application-coordinator.properties` — `server.port=8079`. (`CLUSTER_NODES` is still read from the
  environment by `ClusterNodes`.)
- `application-gateway.properties` — `server.port=8078`. (`BOOTSTRAP_URL` still read from env by
  `ConnectionRequest`.)

The library modules' own `application.properties` are removed so there is no duplicate-config
collision on the unified classpath; all runtime config lives in `app`.

## 4. Phase 1 — structure + launcher (committed core)

This phase alone satisfies the goal (one jar, three roles) and is the committed scope.

1. Add the parent reactor pom; repoint each module's `<parent>`; add the `app` module.
2. Convert `Node`/`BootstrappingNode`/`DBMS` to libraries (drop repackage); `app` owns the fat jar.
3. Delete the three module `main` classes; relocate mesh wiring (→ coordinator `@Profile` runner) and
   the gateway port (→ property).
4. Add `NoSqlApplication` + the three role configs + the profile properties in `app`.
5. Restore test bootability (Section 6) and get the full suite green.
6. Rewrite `Dockerfile`, `docker-compose.yml`, run scripts, and `RUN.md` (Section 7).
7. Validate live: single-node local run (all three roles from one jar) and the Docker 3-node cluster;
   confirm login → dashboard topology → CRUD → index → logout, exactly as M4c.

Outcome: `nosql-db.jar` launches any role; 149 existing tests green + role-isolation tests; Docker
and local runs working.

## 5. Phase 2 — `common` de-duplication (production polish, deferrable)

The three modules duplicate cross-cutting code. The near-identical `Response`/`ResponseType`, the
JWT security layer (`JwtService`, `JwtAuthFilter`, `ServiceTokens`, `SecurityUtils`, the authorities
mapping), and the small `Node`/`User` models are the clear candidates. Phase 2 extracts these into
`common` and rewires imports; `Node`/`BootstrappingNode`/`DBMS` then `depends-on` `common`.

Reconciliation notes (verified during design):

- `Response` — Node and DBMS copies are byte-identical but for the package; the coordinator copy adds
  `toJson()` / `isSuccessful()`. The unified `common.Response` is the **union** (keep `content`
  accessors + `toJson()` + `isSuccessful()`). Serialised JSON is unchanged, so the wire protocol is
  unaffected.
- `JwtService` — Node mints (`generateToken`, `generateServiceToken`) and parses; coordinator and
  gateway only parse. The unified service carries the union; roles simply call what they need.
- `Node` / `User` models — small; reconcile fields to a superset (getters/setters the consumers use).

Phase 2 is **higher-churn** (many `import` edits) but changes no behaviour and no wire format. It is
**deferrable**: Phase 1 already delivers "one jar, many roles," so if Phase 2 reveals risk it stops
at the Phase-1 milestone (the one-way ratchet — never break a working state to finish a refactor).
Each extraction (security first, then `Response`, then models) lands behind the full green suite.

## 6. Testing strategy

- **Unit tests** (B+-tree, `KeyCodec`, `Pager`, HTTP-contract stubs, `ClusterStatusServiceTest`,
  `GlobalViewModelTest`, etc.) are plain JUnit and move with their modules unchanged.
- **`@SpringBootTest` tests** currently rely on each module's `*Application` as their
  `@SpringBootConfiguration`. Since those mains are deleted, each library module that has a
  `@SpringBootTest` gets a minimal **test-only** `@SpringBootApplication` under its package in
  `src/test` (e.g. `com.database.atypon.Node.NodeTestApplication`), scanning that module. These are
  test-scope only — never on `app`'s runtime classpath — so they cannot interfere with the launcher.
  Affected: Node (`NodeAuthorizationTest`, `NodeSecuredApiIntegrationTest`, controller tests,
  `AuthenticationServiceBcryptTest`), BootstrappingNode (`BootstrapAuthorizationTest`), DBMS
  (`NodeAuthenticationProviderTest`, the renamed `DbmsApplicationTests`).
- **Test properties.** Tests that need `app.jwt.secret` and don't set it via `@TestPropertySource`
  get a `src/test/resources/application.properties` in their module with the dev secret.
- **New `app` tests** prove role isolation: boot with `@ActiveProfiles("node")` and assert a node
  bean is present and a gateway bean is absent; repeat for `gateway` and `coordinator`. This guards
  the central invariant that one role's beans never leak into another.
- **CI** (`.github/workflows/ci.yml`): replace the three-way module matrix with a single reactor
  build — `mvn -B -ntp verify` at the repo root builds `common` → the three libs → `app` and runs
  every module's tests in one pass. JDK 17 unchanged.

## 7. Docker, compose, scripts, docs

- **One `Dockerfile`** at the repo root: a multi-stage build that builds the whole reactor
  (`mvn -q -B -DskipTests package`) and runs `app/target/*.jar`. It also copies the seed
  `Node/data/info.json` into the image working directory so a `node`-role container has the admin
  registry; coordinator/gateway roles ignore it. The three per-module Dockerfiles are removed.
- **`docker-compose.yml`**: one `build` (root context), and the resulting image is reused by all five
  services, each setting `SPRING_PROFILES_ACTIVE` (and role-specific env):
  - `Node0`/`Node1`/`Node2`: `SPRING_PROFILES_ACTIVE=node`.
  - `bootstrappingnode`: `SPRING_PROFILES_ACTIVE=coordinator`, `CLUSTER_NODES=Node0:8080,Node1:8080,Node2:8080`.
  - `dbms`: `SPRING_PROFILES_ACTIVE=gateway`, `BOOTSTRAP_URL=http://bootstrappingnode:8079`, `ports: 8078:8078`.
- **`run.sh` / `rebuild.sh` / `stop.sh`** updated to the one-jar model.
- **`RUN.md`** rewritten: "build once (`mvn clean package` at root → `app/target/nosql-db.jar`), then
  launch by role." The single-node affinity caveat and the demo steps carry over.

## 8. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Nested `@SpringBootApplication` / double auto-config when `app` scans a module | Delete module mains; `app` is the sole app; role configs only `@ComponentScan` package trees that contain no `@SpringBootApplication`. |
| `@SpringBootTest` loses its config after modularization | Per-module test-only `@SpringBootApplication` in `src/test`. |
| A role's beans leak into another role | Role `@ComponentScan` is `@Profile`-gated; new `app` tests assert presence/absence per profile. |
| Thymeleaf templates not found in the unified jar | Templates/static stay in the `DBMS` jar, a compile dependency of `app`, hence on its classpath; verified in the live run. |
| Duplicate `application.properties` on one classpath | Library modules drop their `application.properties`; all runtime config lives in `app` (per-profile). |
| Phase 2 import churn breaks a working build | Phase 2 is incremental, behind the full suite, and deferrable; Phase 1 is a complete, shippable milestone. |
| Docker seed (`info.json`) missing for node role | The single `Dockerfile` copies `Node/data/info.json` into the image. |

## 9. Rollout

Single track on `master`, committed in reviewable steps:

1. Parent pom + `app` skeleton + libraries converted (build compiles, no behaviour yet).
2. Launcher + role configs + profile properties + relocated startup logic (roles boot).
3. Test bootability restored + role-isolation tests (full suite green).
4. Docker/compose/scripts/docs (one image) + live validation (local + Docker).
5. *(optional)* Phase 2 `common` de-duplication, incrementally.

Each step keeps the suite green; the system is runnable after step 3 and Docker-deployable after
step 4. Author: Mahmoud Haifawi; no attribution trailers.
