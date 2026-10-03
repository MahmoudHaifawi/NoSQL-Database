# M2 — Document Update + Optimistic Locking

Status: design approved (2026-10-03), pending spec review before implementation planning.
Milestone: M2 of the capstone roadmap (`2026-09-04-capstone-completion-roadmap.md`).
Branch: `feature/m2-update-optimistic-locking` (off the gateway-CRUD fixes).

## 1. Goal

Add the ability to **update an existing document**, with **optimistic concurrency
control** so that two clients editing the same document cannot silently overwrite each
other. A stale update is rejected with a conflict the caller can recover from (re-read,
re-edit, retry). The feature is exercised end-to-end through the web UI.

Today the system can only create and read documents; there is no update path, and the
index write-hook is insert-only. The B+-tree has no delete yet (that is M3), which shapes
the index-maintenance approach below.

### Non-goals (YAGNI)

- No partial/patch updates — updates send the full document.
- No ETags, content hashing, or multi-version history.
- No vector clocks or causal tracking (that is M8).
- No in-place B+-tree key deletion (that is M3).
- No optimistic locking for create or delete — update only.

## 2. Versioning model

Every document carries a server-controlled integer field **`_version`**.

- **Create** stamps `_version = 1` into the stored JSON (a small change to the existing
  create path).
- **Update** increments it: a document at version *n* becomes *n+1* on each successful
  update.
- `_version` is **reserved**: any client-supplied value in a create or update body is
  ignored and overwritten by the server.
- **Legacy documents** written before M2 (e.g. pre-existing seed data) have no `_version`;
  they are read as version `1` (`doc.optInt("_version", 1)`), so they remain updatable.
- The schema validator is unaffected: it checks that each *schema* field is present with
  the right type and ignores extra fields, so `_version` rides along harmlessly. `_version`
  is not declared in the schema and is not user-editable.

The version travels in the document body, so existing read paths already return it to
clients with no change.

## 3. Update operation (Node)

New endpoint: `POST /write/document/update?database=&schema=&id=`
- Body: the full new document as JSON, including the `_version` the client last read.
- Header: `Authorization` (same token model as create).

New method `WriteOperation.updateDocument(database, schema, id, newDoc, expectedVersion)`,
executed inside the **same `synchronized` block** as create so that read-check-write is
atomic on a node. The controller reads the client's expected version from the incoming
body's `_version` field and passes it as `expectedVersion`; the server then controls the
stored `_version` itself (steps 3–5), so the client never sets it directly.

1. Load `<id>.json`. If it does not exist → `ERROR "document not found"`.
2. `storedVersion = existing.optInt("_version", 1)`.
3. If `expectedVersion != storedVersion` → **conflict**: return an `ERROR` response whose
   content carries the current version, so the UI can show it and reload. No write occurs.
4. Validate `newDoc` against the schema (existing `Validators.validateDocument`).
5. Stamp `_version = storedVersion + 1` into `newDoc`; overwrite `<id>.json`.
6. Fire the index-maintenance hook (section 5).
7. Return `SUCCESS` with the new version.

The controller mirrors `createDocument`'s structure: user-token requests go through the
affinity/replication path (section 4); the `internal` token path applies the update
verbatim (section 4).

## 4. Replication

Update replicates like create — full replication to all nodes — but with one rule that
keeps replicas identical:

- The **origin** node performs the version check and the increment **once**, producing a
  new document already stamped with version *n+1*.
- It then **broadcasts that exact document** to peers using the `internal` token.
- Peers **apply it verbatim**: overwrite the file, take `_version` straight from the
  received document, **no re-check and no re-increment**, then run their own index hook.

This mirrors how `createDocument`'s `internal`-token path already bypasses affinity and
writes directly. It guarantees every replica converges to the same version and content,
and it means the conflict check happens exactly once (on the origin), not N times.

Single-node behaviour is unchanged by the M2 design and already correct after the
gateway-CRUD affinity fix (a lone node owns every schema).

## 5. Index maintenance on update

An update may change the value of an indexed field, which must stop the old key from
pointing at the document. Because in-place key deletion does not exist until M3, M2 takes
the **rebuild** approach:

- New error-isolated hook `IndexManager.onUpdate(database, schema)` (sibling of
  `onInsert`), run under the index write lock on **every node that applies the update**.
- It **rebuilds every index on that schema** using the existing drop + `createIndex`
  bulk-load (sorted scan of the current records). After an update, a changed indexed value
  is immediately queryable under its new key, and the old key is gone.
- Like `onInsert`, a failure in maintenance is logged and does **not** fail the write; the
  indexes are derived data and can be rebuilt.

Rebuild is O(records-in-schema) per update. That is acceptable here: writes are rare
relative to reads, this is a portfolio demonstration, and correctness is guaranteed without
pulling M3's delete forward. Incremental key patching becomes possible once M3 lands.

## 6. DBMS gateway + UI

**Forwarding** (mirrors the existing write/read forwarders):
- `UpdateRequest` → `POST` node `/write/document/update`, returns the node's response
  (success + new version, or the conflict with the current version).
- Reuse `readDocumentById` to fetch a document for editing.

**Controller** (`@Controller`, `/update`, guarded by the existing `AuthInterceptor`):
- `GET /update` — form for database / schema / id.
- `POST /update/fetch` — read the document, pre-fill an edit form with one input per field
  and a **hidden `_version`**; show the current version.
- `POST /update/save` — forward the update with the hidden version. On success, show the
  new version and the saved document. On **conflict**, show a clear message and re-fetch the
  current document (so the user can re-apply their change against the latest version).

**Template** `update.html` + a dashboard link, styled like the existing pages.

## 7. Concurrency & correctness

- The version compare-and-set happens inside `WriteOperation`'s `synchronized` block, so on
  a given node two concurrent updates to the same id are serialized; the second sees the
  first's incremented version and conflicts if it carried the old one.
- Optimistic: no lock is held across client think-time; the conflict is detected at save.
- Replication consistency: origin stamps the version, peers apply verbatim (section 4).
- Index lock: `onUpdate` takes the same `IndexManager` write lock as `onInsert`/create, so
  rebuilds do not interleave with concurrent index reads/writes.

## 8. Error handling

| Case | Response |
|------|----------|
| Document id not found | `ERROR "document not found"`, no write |
| Version mismatch (stale) | `ERROR` conflict carrying the current version, no write |
| Body fails schema validation | `ERROR` with the validator message, no write |
| Index rebuild fails | write still succeeds; failure logged (derived data) |
| Gateway with no session | redirect to `/login` (existing `AuthInterceptor`) |

## 9. Testing (TDD)

Node:
- update with matching version → file overwritten, `_version` incremented, SUCCESS.
- update with stale version → conflict, file unchanged, current version reported.
- update of a missing id → error.
- body missing/mistyped schema field → validation error, no write.
- `internal`/apply path → sets version from the document, no check, no increment.
- index: after updating an indexed field, a query returns the document under the **new**
  value and not under the old one (rebuild correctness).
- create now stamps `_version = 1`; legacy doc without `_version` is treated as 1 and is
  updatable.

Gateway:
- `UpdateRequest`/service pass the version through and surface success vs conflict.
- controller: fetch pre-fills version; save on conflict re-fetches.

## 10. Boy-scout (in scope because this path is touched)

- Create path stamps `_version`; done minimally and covered by a test.
- Any small clarifications in the write path touched by update (naming, duplicated
  response-parsing) are tidied in passing — no unrelated refactoring.

## 11. Out of scope / follow-ups

- In-place B+-tree key deletion and incremental index patch — **M3**.
- Delete operation — **M3**.
- Causal/vector-clock concurrency — **M8**.
