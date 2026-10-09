# M3 — Document Delete + B+-tree Key Deletion

Status: design approved (2026-10-09), pending spec review before implementation planning.
Milestone: M3 of the capstone roadmap (`2026-09-04-capstone-completion-roadmap.md`).
Workflow: work directly on `master` (current user workflow — commit + push to master, no feature branches).

## 1. Goal

Add a **document delete** operation and the **B+-tree key deletion** the index engine has been
missing. Delete is **version-checked** (optimistic, consistent with M2 update): a stale delete is
rejected with a conflict. With real key deletion available, index maintenance becomes
**incremental** — both for delete and (refactored) for update, retiring M2's full-rebuild.

Today the B+-tree has `insert` / `contains` / `scanRange` / `bulkLoad` but no delete; M2's update
keeps indexes correct by rebuilding the whole schema's indexes. M3 replaces that with targeted
key removal/patching and adds the delete feature end-to-end through the web UI.

### Non-goals (YAGNI)

- No node merge / borrow / rebalance / root-shrink on underflow — deletion leaves nodes possibly
  under-full (a later optional "compact/rebuild index" could reclaim space).
- No soft-delete / tombstones; delete removes the record file.
- No cascade or referential integrity.
- No id reuse after delete; `nextId` only advances.

## 2. B+-tree key deletion (engine)

New `BPlusTree.delete(byte[] key)` → `boolean` (whether an entry was removed):

1. Descend from the root to the leaf that would contain `key`, using the existing search path
   (same routing as `insert`/`contains`).
2. In that leaf, binary-search for `key`. If absent, return `false` (no-op).
3. Remove the entry (shift the remaining entries/children down, decrement the count) and write the
   leaf page back through the pager (mark dirty).
4. Return `true`.

**No structural rebalancing.** A leaf may become under-full or empty; it stays in the sorted
leaf-sibling chain, and internal separators are left unchanged. This is correct because:
- Separators are routing guides copied up at split time; removing a leaf's current minimum does not
  invalidate the parent separator (it remains a valid lower-bound guide), so every surviving key
  still routes to the correct leaf and the deleted key's slot simply isn't found.
- `scanRange` walks the leaf chain; an empty/under-full leaf is traversed and contributes no
  entries, so range/ORDER BY/pagination results stay correct.

`validate()` is extended to tolerate under-full (and empty) non-root leaves (minimum-occupancy is
no longer an invariant once deletes are allowed), while still checking key order, the leaf chain,
and separator correctness.

## 3. Incremental index maintenance

- `IndexService.onDelete(db, schema, docId, doc)` — for each field returned by `listIndexes`,
  compute the composite key `KeyCodec(fieldValue, docId)` from the supplied (pre-deletion)
  `doc` and call `tree.delete(key)` on that field's index. `IndexManager.onDelete` wraps it in the
  write lock; failures are logged and never fail the delete (indexes are derived), matching the
  existing `onInsert`/`onUpdate` hooks.
- **Update refactor (separable task):** `IndexService.onUpdate` changes from full rebuild to an
  incremental patch — given the old and new documents and the docId, for each indexed field delete
  the old composite key and insert the new one. `WriteOperation.updateDocument` already reads the
  existing document (for the version check), so it passes that old document to the hook. The
  `IndexManager.onUpdate` signature changes accordingly (old + new doc + docId); callers are updated.
  If this task is dropped, update keeps M2's correct full rebuild and delete still works.

## 4. Document delete (Node)

New endpoint: `POST /write/document/delete?database=&schema=&id=&version=`
- Header `Authorization`.
- `version` = the client's expected version (optimistic check). Ignored on the internal path.

`WriteOperation.deleteDocument(database, schema, id, expectedVersion)`, inside the same
`synchronized` block as create/update:

1. Load `<id>.json`. Missing → `ERROR "document not found"`.
2. `storedVersion = doc.optInt("_version", 1)`. If `expectedVersion != storedVersion` → **conflict**
   `ERROR` with the current version in `content`, no deletion.
3. Read the document content (needed to compute index keys), delete the `<id>.json` file.
4. Fire `onDelete(database, schema, id, doc)`.
5. Return `SUCCESS`.

`WriteOperation.applyDelete(database, schema, id)` — replication/internal path: read the document
if present, delete the file, fire `onDelete`, no version check. Deleting an already-absent file is
a success no-op (replicas converge).

## 5. Replication

Mirrors M2 update. The origin (user token) performs the version-checked delete once, then
broadcasts the delete to peers with the `internal` token; peers run `applyDelete` (verbatim, no
check). `WriteController` branches on the token exactly like `updateDocument`:
- internal → `applyDelete`
- user → `deleteDocument` + `broadcastDelete` on success.

`WriteService.broadcastDelete(db, schema, id)` iterates `Network.nodes` calling
`Node.deleteDocument(db, schema, id)` (peer call with the `internal` token). Single-node = no peers
= no-op broadcast.

## 6. DBMS gateway + UI

- `WriteRequest.deleteDocument(db, schema, id, version, token, nodeURL)` → `POST` node delete path.
- `WriteService.deleteDocument(db, schema, id, version, token, nodeURL)` → parse the node's JSON
  array; throw with the message on `ERROR` (conflict/not-found), return success otherwise.
- The `/update` page's loaded-document card gains a **Delete** button in its own form
  (`POST /update/delete`, hidden `database`/`schema`/`id`/`version`). On success → "Deleted"
  message, clear the edit form. On conflict → show the message and re-fetch the current document.
- `AuthInterceptor` already guards `/update/**`, which covers `/update/delete`.

## 7. Concurrency & correctness

- Delete's load-check-delete runs inside `WriteOperation`'s `synchronized` block, so it is atomic
  against concurrent create/update/delete on the same node.
- `onDelete`/`onUpdate` take the `IndexManager` write lock, serialized against index readers.
- Replication: origin decides once; peers apply verbatim → replicas converge (same model as M2).

## 8. Error handling

| Case | Response |
|------|----------|
| Document id not found | `ERROR "document not found"`, nothing deleted |
| Version mismatch (stale) | `ERROR` conflict carrying the current version, nothing deleted |
| Index key removal fails | delete still succeeds; failure logged (derived data) |
| Delete of already-absent file (internal apply) | success no-op |
| Gateway with no session | redirect to `/login` (existing `AuthInterceptor`) |

## 9. Testing (TDD)

Engine (`BPlusTree`):
- delete an existing key → `contains` false, absent from `scanRange`; sibling keys still found.
- delete the current minimum key of a leaf → search for other keys still correct.
- delete an absent key → returns false, tree unchanged.
- delete every key in a leaf (empty leaf) → range scan across the chain still returns neighbours.
- oracle: insert N keys, delete a random subset, assert `scanRange` equals the expected set.

Index:
- `onDelete` removes exactly the deleted doc's composite key (other docs with the same value
  remain — composite key includes docId).
- incremental `onUpdate`: a doc moves from old value to new (old-value query empty, new-value query
  returns it), with other docs untouched.

Node:
- `deleteDocument`: version match → file gone + `onDelete` fired; stale → conflict, file remains;
  missing → not found. `applyDelete` → file gone, no version check, absent-file no-op.
- `WriteController` delete: internal → `applyDelete` no broadcast; user → `deleteDocument` +
  broadcast; non-user rejected.

Gateway/live:
- delete via the `/update` page removes the doc (Read page no longer lists it); stale delete →
  conflict + re-fetch; an indexed field's query no longer returns the deleted doc.

## 10. Boy-scout (in scope because this path is touched)

- `validate()` updated for the post-delete occupancy relaxation, with a test.
- Any small clarifications in the write path touched by delete are tidied in passing — no unrelated
  refactoring.

## 11. Out of scope / follow-ups

- Index compaction / node reclamation after heavy deletion — optional later milestone.
- Bulk/range delete — not needed.
