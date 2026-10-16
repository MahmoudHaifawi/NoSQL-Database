# Running the cluster

The system is **one artifact** (`app/target/nosql-db.jar`) launched in a chosen **role**. Data nodes
still run as separate processes — that is what makes it a cluster — but the build and the launcher are
unified (one binary, many roles, like Cassandra or CockroachDB).

| Role          | Port  | Launch                                             |
|---------------|-------|----------------------------------------------------|
| `node`        | 8080  | data node — storage engine + B+-tree index         |
| `coordinator` | 8079  | user routing + cluster mesh wiring + topology       |
| `gateway`     | 8078  | Thymeleaf web UI, forwards to nodes                 |

Select the role with `--role=<node|coordinator|gateway>` (or `--spring.profiles.active=<role>`, or the
`SPRING_PROFILES_ACTIVE` env var). Launching with no role prints usage and exits.

Seeded admin account: **`mahmoud` / `admin`**.

---

## Option A — Docker (full cluster, recommended)

Requires Docker Desktop running. One image is built from source (the root `Dockerfile`) and run five
times with different roles — no local Maven needed.

```bash
docker compose up --build
```

Then open http://localhost:8078/login and sign in. This runs three data nodes (`Node0`, `Node1`,
`Node2`) with full replication behind the coordinator and the gateway. Addressing is by compose
service name: `CLUSTER_NODES` tells the coordinator the data-node list, and `BOOTSTRAP_URL` tells the
gateway where the coordinator is.

> If the coordinator starts before the data nodes accept connections, its one-shot mesh wiring is
> skipped. Re-run it once the nodes are up: `docker compose restart bootstrappingnode`.

Helper scripts: `./run.sh` (= `docker compose up --build`), `./rebuild.sh` (rebuild + restart
detached), `./stop.sh` (= `docker compose down`).

---

## Option B — Local, single node (no Docker)

Requires JDK 17 (`JAVA_HOME` pointing at a JDK 17 — Spring Boot 2.7.x does not run on newer JDKs here).

```bash
# build the single jar (whole reactor) once:
mvn -q -B -DskipTests -pl app -am clean package     # -> app/target/nosql-db.jar
JAR="$(pwd)/app/target/nosql-db.jar"

# run each role in its own terminal:
( cd Node && java -jar "$JAR" --role=node )          # 8080  (reads ./data/info.json — the seed)
java -jar "$JAR" --role=coordinator                  # 8079
java -jar "$JAR" --role=gateway                      # 8078
```

The data node reads `./data/info.json` (the admin seed), so launch it from the `Node/` directory,
which holds that file. Defaults target a single local node (`CLUSTER_NODES=localhost:8080`,
`BOOTSTRAP_URL=http://localhost:8079`), so no environment variables are needed.

> Single-node caveat: schema affinity assigns schemas to a node named `Node0`, which does not match
> the local node's address (`localhost`). Database/schema creation and all index operations work, but
> creating a **document** through the gateway UI would try to forward to `Node0`. Seed documents via
> the node's own API (`POST /write/document/new` with a valid Bearer token) for the local demo, or use
> the Docker cluster (where node names and affinity names align) to exercise document writes.

---

## Demo: the B+-tree secondary index

1. Sign in at `/login` as `mahmoud` / `admin`.
2. Dashboard → **Index**.
3. **Create Index** on a field (e.g. database `shop`, schema `products`, field `price`) — builds a
   disk-paged B+-tree via a sorted bulk-load of the records.
4. **Query Index** — equality / range (`GT`, `GTE`, `LT`, `LTE`, `BETWEEN`) with `ASC`/`DESC` ordering
   and `limit`/`offset` pagination. Matching documents are resolved and shown in result order.
