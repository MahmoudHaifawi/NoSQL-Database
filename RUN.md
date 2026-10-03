# Running the cluster

The system is three Spring Boot modules:

| Module              | Port  | Role                                             |
|---------------------|-------|--------------------------------------------------|
| `Node`              | 8080  | data node — storage engine + B+-tree index       |
| `BootstrappingNode` | 8079  | coordinator — user routing + cluster mesh wiring  |
| `DBMS`              | 8078  | gateway — Thymeleaf web UI, forwards to nodes      |

Seeded admin account: **`mahmoud` / `admin`**.

---

## Option A — Docker (full cluster, recommended)

Requires Docker Desktop running. Builds every module from source (multi-stage
Dockerfiles), so no local Maven is needed.

```bash
docker compose up --build
```

Then open http://localhost:8078/login and sign in. This runs three data nodes
(`Node0`, `Node1`, `Node2`) with full replication behind the bootstrapping node
and the gateway.

Addressing is consistent because every component resolves peers by compose
service name: `CLUSTER_NODES` tells the bootstrapping node the data-node list,
and `BOOTSTRAP_URL` tells the gateway where the coordinator is.

> If the bootstrapping node starts before the data nodes are accepting
> connections, its one-shot mesh wiring is skipped. Re-run it once the nodes are
> up: `docker compose restart bootstrappingnode`.

---

## Option B — Local, single node (no Docker)

Requires JDK 17 (`JAVA_HOME` pointing at a JDK 17 — Spring Boot 2.7.x does not run
on newer JDKs here).

```bash
# build all three (JDK 17)
for m in Node BootstrappingNode DBMS; do (cd $m && mvn -DskipTests clean package); done

# run in order (each in its own terminal), from the module directory:
cd Node              && java -jar target/Node-0.0.1-SNAPSHOT.jar                 # 8080
cd BootstrappingNode && java -jar target/BootstrappingNode-0.0.1-SNAPSHOT.jar    # 8079
cd DBMS              && java -jar target/DBMS-0.0.1-SNAPSHOT.jar                  # 8078
```

Defaults target a single local node (`CLUSTER_NODES=localhost:8080`,
`BOOTSTRAP_URL=http://localhost:8079`), so no environment variables are needed.

> Single-node caveat: schema affinity assigns schemas to a node named `Node0`,
> which does not match the local node's address (`localhost`). Database/schema
> creation and all index operations work, but creating a **document** through the
> gateway UI would try to forward to `Node0`. Seed documents via the node's own
> API (`POST /write/document/new` with header `Authorization: internal`) for the
> local demo, or use the Docker cluster (where node names and affinity names
> align) to exercise document writes through the UI.

---

## Demo: the B+-tree secondary index

1. Sign in at `/login` as `mahmoud` / `admin`.
2. Dashboard → **Secondary Index**.
3. **Create Index** on a field (e.g. database `shop`, schema `products`, field
   `price`) — builds a disk-paged B+-tree via a sorted bulk-load of the records.
4. **Query Index** — equality / range (`GT`, `GTE`, `LT`, `LTE`, `BETWEEN`) with
   `ASC`/`DESC` ordering and `limit`/`offset` pagination. Matching documents are
   resolved and shown in result order.
