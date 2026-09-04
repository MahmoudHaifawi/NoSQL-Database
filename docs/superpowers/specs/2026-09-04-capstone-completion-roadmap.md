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
"I understand DB internals" narratives over production hardening.

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

| # | Milestone | Fills | Depends on |
|---|-----------|-------|-----------|
| M1 | B+-tree indexing (disk-paged) | Indexing; efficiently-indexed unique ID | — |
| M2 | Document/property update + optimistic locking | Property read/write; optimistic locking | M1 |
| M3 | Delete DB + delete document | Delete DB / document | M1 (M2 for version reuse) |
| M4 | Testing + demo + hardening | Testing evidence; demo app; (security) | M1–M3 |

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

### Out of scope (documented as future work)
Write-ahead log; dynamic membership / autoscaling; latch-crabbing concurrency;
variable-length slotted pages (we cap string keys instead); server-side retry loops
(client retries on conflict); keyset/cursor pagination (offset/limit in v1).

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

- **Automated tests** across M1–M3 using the brute-force-oracle technique, plus
  integration tests exercising affinity forwarding, broadcast replication, and conflict
  retries.
- **Demo application** (CLI or small web app — e.g. contacts/banking) on top of the DB,
  presenting **evidence of correctness and load balance** (which node served which
  request; affinity distribution).
- **Optional hardening:** replace plaintext password compare with **BCrypt**; optionally
  a signed token. (Not strictly required, but strengthens the "security issues" report
  section.)

Detailed decisions deferred to the M4 spec.

---

## 9. Next step

Generate the **M1 implementation plan** (B+-tree indexing) via the writing-plans process.
M2–M4 each get their own spec + plan when reached.
