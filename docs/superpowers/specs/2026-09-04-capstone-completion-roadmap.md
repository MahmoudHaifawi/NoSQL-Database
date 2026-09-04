# Capstone Completion Roadmap — Decentralized NoSQL DB

**Date:** 2026-09-04
**Repo:** `NoSQL-Database` (Atypon/Wiley Java & DevOps Bootcamp capstone)
**Status:** Design approved; ready for per-milestone implementation planning.

---

## 1. Purpose

The repository implements a solid *distributed backbone* for the capstone but leaves
four **explicitly required** features unimplemented or stubbed. This document:

1. Records the requirement-coverage audit (what exists vs. what the brief requires).
2. Defines the dependency structure between the missing features.
3. Specifies the work as four sequenced milestones (M1–M4), each of which will get
   its own implementation plan.

The primary goal is a **to-spec, portfolio-grade** completion of the capstone — not a
commercial database. Design choices favor correctness, testability, and clear
"I understand DB internals" narratives over production hardening. The existing code was
written early in the author's studies and is refactored to a senior (4-years-experience)
Java standard as part of the work — see M0 (§3) and the boy-scout standard (§4).

---

## 2. Requirement-coverage audit

Verified against the source code (not just the README).

### Implemented (matches the brief)
- Bootstrapping node supplies config and maps users to nodes, load-balanced
  (`UsersLoadBalancer`); new user contacts bootstrap once, then uses its assigned node.
- Node verifies login (`AuthenticationController` `/login`).
- Document store with JSON documents, DB schema + per-document schema
  (`/write/schema/new`, `/write/document/new`).
- Data + schemas replicated to every node's local filesystem
  (`broadcastSchema` / `broadcastDocument`).
- Reads served by any node; writes have node affinity; forward-to-affinity + broadcast
  (`AffinityLoadBalancer`).
- Fixed N nodes, Docker + docker network, threads per user, no shared filesystem.
- At least one predetermined admin (`info.json`).

### Partial / weak
- **Affinity granularity:** assigned **per schema**, not per **document** as the brief
  words it. (Acceptable deviation; noted.)
- **Security:** login works but passwords are compared in **plaintext** and the "token"
  is just the user's role string.
- **Demo/testing evidence:** only a thin Thymeleaf login/dashboard; no demo application
  and effectively no automated tests (default Spring context test only).

### Required but MISSING / stubbed → the scope of this roadmap
1. **Indexing on a single JSON property**, implemented by hand — `IndexingService` is an
   **empty class**. → **M1**
2. **Optimistic locking** (versioned writes; retry on version mismatch) — **absent**
   (`version`/`optimistic`/`CAS` = 0 grep hits; writes use plain `synchronized`). → **M2**
3. **Read/write specific JSON properties** within a document — **absent**
   (only whole-document read/create). → **M2**
4. **Delete a DB / delete a document** — **absent** (only create; `FileDeleter` unused). → **M3**
5. Caching — optional per the brief; stubbed `Cache`. Repurposed as the M1 buffer pool.

---

## 3. Dependency structure

The four gaps are **not** independent:

- The **index (M1) is foundational.** Property update and delete must keep every index
  consistent.
- **Optimistic locking is the concurrency rule for updates**, not a standalone feature —
  it must be built together with the property/document update path (M2).
- **Delete (M3)** must remove index entries and reuses M2's affinity/version handling.

Therefore the maintenance interface of the M1 index is designed up front with three
hooks — `onInsert`, `onUpdate`, `onDelete` — even though only `onInsert` has a caller in
M1. M2 supplies `onUpdate`; M3 supplies `onDelete`.

### Milestone order

The four required features (M1–M4) are the core. M5 is the user's UI-consolidation goal.
M6–M13 are **standout features** chosen to differentiate the project for backend /
distributed-systems / DevOps roles (Gulf and global) — see §9.

| # | Milestone | Fills / Adds | Depends on | Status |
|---|-----------|--------------|-----------|--------|
| M0 | Foundations & refactor baseline: test harness + CI, SLF4J logging, unified error/exception model, constants, DI + thread-safety hygiene, fix outright bugs | Code quality (Clean Code / Effective Java / SOLID); DevOps (CI) | — | committed, first |
| M1 | B+-tree indexing (disk-paged) + query UI slice | Indexing; efficient unique ID | — | required |
| M2 | Document/property update + optimistic locking + UI slice | Property read/write; optimistic locking | M1 | required |
| M3 | Delete DB + delete document + UI slice | Delete DB / document | M1 (M2 for version reuse) | required |
| M4 | Test suite + web demo assembly + security (BCrypt/JWT/RBAC) | Testing evidence; demo; security | M1–M3 | required |
| M5 | Single app (unify the demo into one polished client) | UI consolidation (user goal) | M4 | committed |
| M6 | Consistent hashing + virtual nodes | Node hashing & load balancing | M1 | standout (T1) |
| M7 | Quorum consistency (N/R/W) + read-repair | Data consistency | M6 | standout (T1) |
| M8 | Vector clocks (distributed conflict detection) | Data consistency (distributed) | M2, M7 | standout (T2) |
| M9 | Gossip / SWIM failure detection + dynamic membership | Availability; communication protocols | M6 (M7) | standout (T2) |
| M10 | WAL + crash recovery | Durability | M1–M3 | standout (T2) |
| M11 | Query language + index-aware planner | Query-engine depth | M1 | standout (T2, drop-first) |
| M12 | Observability (Prometheus/Grafana) + YCSB benchmark | Testing; quantified results | M6–M9 | standout (T1) |
| M13 | Kubernetes + Helm deployment | DevOps; cloud | M4 (stable app) | standout (T1) |

**Sequencing rationale:** CI is stood up first so every milestone is test-protected. The
core (M1–M4) comes before the distributed layer because update/delete must exist before
they can be made quorum- and failure-aware. In the distributed phase, **consistent hashing
(M6) precedes quorum (M7)** because the ring defines each key's replica set; **vector
clocks (M8)** give read-repair its conflict-resolution rule; **gossip (M9)** makes the ring
and quorum failure-aware. Observability + benchmark (M12) come after the distributed layer
so the numbers are meaningful; K8s (M13) deploys the finished system.

**Descoping guidance (drop in this order under scope pressure):** M11 (query planner) →
M13 (drop K8s; CI lives in M0) → M10 (WAL). **Never drop** the consistency/availability core
(M6–M9) or observability+benchmark (M12) — they carry the standout story. Merkle-tree
anti-entropy remains an *optional* stretch on top of M7/M8, not a committed milestone.

**Client strategy (decided):** the demo client is the **existing Thymeleaf web UI**,
extended. Today it is a setup-only admin panel (create DB / add user / create schema) with
no way to use the data. Rather than deferring all of it to M4, **each milestone ships a
thin UI slice** for the feature it adds — M1 a query page, M2 insert/update forms, M3
delete controls — so every milestone is visible in the browser as it lands. M4 completes
the demo: schema-driven dynamic forms, result tables, a **"served by Node N" badge** on
each response as visible load-balance evidence, and the automated test suite. (A small CLI
script may still back the reproducible load-balance/correctness evidence; the primary
end-user client is the web UI.)

### M0 — Foundations & refactor baseline (design intent)

The codebase was written early in the author's studies and is being brought up to a senior
(4-years-experience) Java standard. Several issues are **correctness bugs**, not style:
`broadcastSchema/broadcastDocument` return before their executor tasks finish and leak an
un-shutdown thread pool; `ReadService` keeps request state (`threads`) in a singleton field
and returns `null` on error; `Cache` mixes a Spring `@Component` with a hand-rolled
`getInstance()` singleton. M0 establishes the baseline everything else builds on:

- **Test harness + CI** — JUnit 5, a test layout, and a GitHub Actions workflow
  (build + test). Add **characterization tests** for current behavior *before* refactoring it.
- **Logging** — replace `System.out.println` with SLF4J/Logback.
- **Error/exception model** — one consistent strategy (typed exceptions + a global handler
  / consistent `Response`); stop swallowing exceptions and returning `null`.
- **Constants & config** — extract magic strings (`"info"`, `"nextId"`, `"Node"`, paths)
  into constants/enums.
- **DI & thread-safety hygiene** — constructor injection throughout; remove mutable
  per-request state from singleton services/controllers; fix the broadcast (await
  completion, managed executor) and the `Cache` singleton/DI conflict.
- **Housekeeping** — remove dead/commented code and typos; gitignore `.idea/`.

Scope discipline: M0 fixes cross-cutting foundations and outright bugs only. Code a later
milestone rewrites (`Cache` internals → M1 buffer pool; auth → M4 security; broadcast → M7
quorum) gets a minimal correct fix now, not a full polish.

---

## 4. Cross-cutting principles

- **Per-node ownership.** Each node maintains its **own** indexes. Because replicated
  writes flow through the same `WriteOperation` path, index maintenance on every node is
  automatic. `CREATE INDEX` is broadcast so every node builds its own index file.
- **Index is a derived structure.** Record files are the source of truth; any index can
  be rebuilt from them. This underpins the durability model (no WAL needed in v1).
- **Consistency model.** As the brief allows, replication is eventually consistent during
  broadcast (a concurrent read on a not-yet-updated node sees the old copy; once updated,
  reads return the new copy).

- **Code-quality standard (boy-scout rule).** Every milestone leaves the code it touches at
  a senior Java bar — Clean Code (Uncle Bob), Effective Java (Bloch), SOLID, appropriate
  design patterns — with tests. Continuous, not a one-time pass; directly serves the brief's
  required "defend your code against…" report sections.

### Out of scope (documented as future work)
Latch-crabbing concurrency within the B+-tree; variable-length slotted pages (we cap
string keys instead); server-side retry loops (client retries on conflict); keyset/cursor
pagination (offset/limit in v1); cluster autoscaling; Merkle-tree anti-entropy (optional
stretch, not committed). *(WAL is now M10; dynamic membership is now M9 — both promoted
into scope as standout milestones.)*

---

## 5. M1 — B+-tree indexing (disk-paged) — full design

### 5.1 Components (all in the `Node` module)

| Unit | Purpose | Depends on |
|------|---------|-----------|
| `BPlusTree` | search / insert / delete / range-scan over keys; disk-agnostic | `Pager`, `Page` |
| `Page` + page codec | fixed 4 KB page: (de)serialize an internal/leaf/meta node | — |
| `Pager` (buffer pool) | reads/writes pages through the LRU cache; allocates page ids; flushes dirty pages | `LruCache`, index file |
| `IndexService` (fills the empty class) | orchestration: create (bulk-load), maintain (`onInsert/onUpdate/onDelete`), query | `BPlusTree`, `IndexCatalog` |
| `IndexCatalog` | registry of indexed `(schema, field)` → index file; persisted as JSON | filesystem |
| `LruCache<K,V>` | generic LRU (finishes the stubbed `Cache`/`DoubleLinkedList`), with dirty write-back used by `Pager` | — |

### 5.2 Data model

- **Composite key** = `fieldValue ‖ docId`, making every key unique so non-unique indexes
  and duplicate values work naturally. Equality `field = v` is a range scan
  `[(v, 0) … (v, MAX_ID)]`.
- **Key widths** (fixed): Integer 4 B, Double 8 B, Boolean 1 B, String **capped at 64 B**
  (truncate-with-marker), plus `docId` 4 B. Page ids and doc ids are 4-byte ints.
- **Comparison** uses a **typed comparator** (decode then compare by field type,
  tie-break by docId) — not raw `memcmp` — avoiding order-preserving encodings.
- **No null keys:** existing document validation guarantees every schema field is present
  and non-null, so indexed fields are always present.

### 5.3 Page format (4 KB pages; page 0 = meta)

- **Meta:** magic, keyType enum, keySize, rootPageId, pageCount, dirtyFlag.
- **Internal:** `type=INTERNAL(1B)`, `numKeys(2B)`, `child0(4B)`, then `(key, childN)` pairs.
- **Leaf:** `type=LEAF(1B)`, `numKeys(2B)`, `rightSibling(4B)`, then entries — each entry
  is the composite key itself (the docId payload is already in the key). `rightSibling`
  links leaves for ordered range/ORDER BY scans.
- Fan-out at 4 KB: ~500 entries/leaf (Integer index) to ~56 (64 B String index).

### 5.4 Buffer pool (`Pager`) — the repurposed LRU cache

- Finish the stub as generic `LruCache<K,V>` (`add/get/remove/clear`). `Pager` holds an
  `LruCache<Integer, Page>` of fixed capacity (default **128 pages = 512 KB**).
- Read: hit → move-to-front; miss → read 4 KB at `pageId × 4096`, insert.
- Eviction: drop LRU page; **if dirty, write it back to the index file first.**
- Mutations mark pages dirty in the pool; disk write happens on eviction or `flushAll()`.

### 5.5 Durability (no WAL in v1)

- A mutating op sets meta `dirtyFlag=1`, applies changes, calls `Pager.flushAll()`, then
  clears the flag.
- On startup, per index: validate meta (magic + `dirtyFlag`). If the file is missing,
  invalid, or was left dirty (torn write), **rebuild the index from the record files.**
  Worst case is a rebuild — never corruption.

### 5.6 Concurrency

Per-index `ReentrantReadWriteLock`: queries take the read lock; maintenance (inside the
already-`synchronized` write path) takes the write lock. Latch-crabbing is future work.

### 5.7 `CREATE INDEX` — sorted bulk-load

1. Validate db/schema/field exist and field is in the schema.
2. Scan `<schema>-records/*.json`, extract `(fieldValue, docId)` pairs.
3. Sort by composite key.
4. Pack leaves left-to-right at a fill factor, linking `rightSibling`; build internal
   levels bottom-up to a single root; write meta.
5. Register in `IndexCatalog`; broadcast to peers (each peer bulk-loads its own).

### 5.8 Write-path hook (maintenance)

Inject `IndexService` into `WriteOperation`. Inside the existing `synchronized` block,
after the doc file is written and `nextId` bumped:
`indexService.onInsert(db, schema, docId, documentJSON)` → for each index on the schema,
insert `(value, docId)`, `flushAll()`, clear dirty flag. On failure: log and leave the
index dirty (self-heals via startup rebuild); the document is never lost.

### 5.9 API

Index management (admin token; broadcast to peers):

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/admin/index/create?database=&schema=&field=` | sorted bulk-load, then broadcast |
| GET  | `/admin/index/list?database=&schema=` | list active indexes |
| POST | `/admin/index/drop?database=&schema=&field=` | delete index file + catalog entry |

Query (user token) — single endpoint:

```
POST /user/read/query
{ "database","schema","field",
  "op": "EQ|GT|GTE|LT|LTE|BETWEEN",
  "value": <v>,            // single-bound ops
  "low": <a>, "high": <b>, // BETWEEN (inclusive)
  "order": "ASC|DESC",
  "limit": <n>, "offset": <k> }
→ { SUCCESS, "documents": [ ... sorted, paginated ... ] }
```

Service walks the B+-tree to matching ids in leaf order, applies order/limit/offset, loads
docs via existing `fetchById`. Query on an unindexed field → explicit "no index" error
(never a silent full scan). Pagination v1 = offset/limit; keyset/cursor is future work.

### 5.10 Files

- `./data/<db>/indexes/<schema>.<field>.idx` (new `PathBuilder` method + `indexes/` dir).
- `IndexCatalog` JSON listing active indexes.

### 5.11 Testing (TDD; brute-force oracle)

Every query result is checked against a full-scan over the same data.
1. **Page codec** round-trip (meta/internal/leaf; empty & full boundaries).
2. **LruCache** eviction order + **dirty write-back** + get/remove/clear.
3. **BPlusTree**: insert/search; duplicates; forced multi-level **splits** with invariant
   checks (equal leaf depth, sorted keys, fan-out bounds); EQ/GT/GTE/LT/LTE/BETWEEN vs
   oracle; ORDER BY ASC/DESC + limit/offset vs oracle; **bulk-load tree ≡ repeated-insert
   tree** (identical query results).
4. **IndexService + write path**: create-then-write (incremental) and write-then-create
   (bulk-load) both match the oracle.
5. **Durability**: leave dirty flag set (simulated crash) → startup rebuild → correct.
6. **Concurrency smoke**: queries during writes — no exceptions, no corruption.

### 5.12 UI slice (thin)

Add a **Query page** to the DBMS web UI: pick database / schema / field, choose an operator
(EQ / GT / GTE / LT / LTE / BETWEEN), order and page size, submit, and render the returned
documents in a table with the **"served by Node N" badge**. This is the browser-visible
proof that the index works, and the first data-usage page in the UI. (Also a
`/admin/index/create` form so an index can be created without curl.)

---

## 6. M2 — Document/property update + optimistic locking (design intent)

Fills: *read/write specific JSON properties*; *optimistic locking*.

- **Version field:** one `_version` integer **per document**, incremented on each
  successful write (per-document, not per-property).
- **Update endpoints:**
  - `POST /write/document/update` — replace a document (schema-validated), version-checked.
  - `POST /write/property/update` — set a single property, version-checked.
  - Both carry the client's expected `_version`.
- **Property read:** `POST /user/read/property?...&property=` returns a single field
  (whole-doc read then project).
- **Optimistic locking rule:** the **affinity owner** is the single serialization point
  for a document's writes. It compares expected vs. stored `_version`; on match it applies
  the change, bumps `_version`, and broadcasts; on mismatch it returns a **`CONFLICT`**
  response. The **client retries** (re-read → re-apply → resend) — satisfying the brief's
  "fail and restart after updating its version." (Server-side retry loop is the noted
  alternative.)
- **Index maintenance:** updating an indexed field calls `IndexService.onUpdate` →
  remove `(oldValue, id)`, insert `(newValue, id)`.
- **Validation:** updated fields must match the schema's declared types.

Detailed decisions (endpoint bodies, conflict payload shape, retry contract, index
`onUpdate` atomicity) are deferred to the M2 spec.

---

## 7. M3 — Delete DB + delete document (design intent)

Fills: *delete a DB / delete a document*.

- `POST /write/document/delete?database=&schema=&id=` — remove the document file; call
  `IndexService.onDelete` for every index on the schema; broadcast. Optional version check.
- `POST /admin/database/delete?databaseName=` — remove the database directory (records,
  schemas, affinities, indexes); update `info.json`; broadcast (admin token).
- Affinity is respected for document delete (forward to owner, then broadcast), mirroring
  the write path.

Detailed decisions (tombstones vs. hard delete, delete-then-rebuild vs. incremental index
delete, cascade rules) are deferred to the M3 spec.

---

## 8. M4 — Testing, demo, hardening (design intent)

Fills: *testing evidence*; *demo application*; (security).

- **Demo application = the extended Thymeleaf web UI** (decision recorded in §3). Building
  on the existing setup panel, add data-usage pages that call the node APIs through the
  DBMS gateway: insert a document (form generated from the schema), read by id, read all,
  **run an index query** (equality / range / BETWEEN / ORDER BY + pagination), update a
  property, delete a document. Render results in tables.
- **Load-balance evidence in the UI:** each response shows a **"served by Node N" badge**;
  a small stats view (or CLI script) fires many requests and shows the distribution across
  nodes and the affinity mapping — the brief's required *evidence of correctness and load
  balance*.
- **Incremental delivery:** the thin per-milestone UI slices (M1–M3) are assembled and
  polished here; M4 is the finishing pass, not the first UI work.
- **Automated tests** across M1–M3 using the brute-force-oracle technique, plus
  integration tests exercising affinity forwarding, broadcast replication, and conflict
  retries.
- **Optional hardening:** replace plaintext password compare with **BCrypt**; optionally
  a signed token. (Not strictly required, but strengthens the "security issues" report
  section.)

Detailed decisions deferred to the M4 spec.

---

## 9. Standout milestones (M5–M13) — design intent

Chosen to differentiate the project for backend / distributed-systems / DevOps roles,
Gulf and global. Full designs are produced per-milestone when reached; below is intent and
the key decisions each will resolve.

### 9.1 M5 — Single app (unify the demo client)
Consolidate the per-milestone UI slices into one polished demo application over the node
APIs. **Open question for M5:** whether "single app" means merging the three Spring modules
into one deployable, or (more likely, to preserve the decentralized architecture) a single
unified front-end / SPA over the gateway. **The node processes must stay separate** — that
decentralization is the point of the project.

### 9.2 M6 — Consistent hashing + virtual nodes
Replace round-robin placement with a hash ring plus virtual nodes; documents and replicas
map to ring positions, with smooth rebalancing when N changes. Prerequisite for meaningful
quorum (defines each key's replica set). Directly strengthens the brief's "node hashing &
load balancing" report section.

### 9.3 M7 — Quorum consistency (N/R/W) + read-repair
Tunable replication factor N with read/write quorums R/W: writes ack from W replicas; reads
gather from R and **read-repair** stale replicas. Turns full-broadcast into real tunable
consistency. Depends on M6 for the replica set.

### 9.4 M8 — Vector clocks (distributed conflict detection)
Upgrade M2's per-document `_version` integer to a **version vector** (a counter per node);
concurrent writes on different nodes are detected as conflicts vs. causally ordered.
Read-repair (M7) uses the vectors to pick winners / surface siblings.

### 9.5 M9 — Gossip / SWIM failure detection + dynamic membership
Nodes gossip heartbeats; SWIM-style suspicion detects down/slow peers and disseminates
membership; the ring (M6) and quorum routing (M7) react to liveness. Removes the static
mesh / silent-failure weakness.

### 9.6 M10 — WAL + crash recovery
Append-only write-ahead log for all mutations (insert/update/delete); replay on restart to
recover. Upgrades the "rebuild index from records" fallback into real durability with a
recovery routine.

### 9.7 M11 — Query language + index-aware planner (drop-first stretch)
A small `WHERE` DSL parsed into a plan; a rule-based planner picks an index vs. a full scan.
**Scoped to single-field predicates** to avoid ballooning into multi-index intersection.
First milestone to drop under scope pressure.

### 9.8 M12 — Observability + YCSB benchmark
Prometheus metrics (query latency, buffer-pool hit ratio, per-node request counts,
replication lag) + Grafana dashboards, plus a YCSB-style benchmark producing published
throughput/latency graphs. Best after the distributed layer (M6–M9) so the numbers mean
something.

### 9.9 M13 — Kubernetes + Helm deployment
Replace the docker shell scripts with a Helm chart / K8s manifests. (CI is stood up early —
see the roadmap table — and protects every milestone; K8s deployment lands once the app is
stable.)

---

## 10. Next step

Generate the **M0 implementation plan** (foundations & refactor baseline) via the
writing-plans process, then the **M1 plan** (B+-tree indexing). Each subsequent milestone
gets its own spec + plan when reached and applies the boy-scout code-quality standard (§4)
to the code it touches, behind tests.
