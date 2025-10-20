# NoSQL Database — Decentralized Cluster (Java)

An educational **distributed key–value store** built in **Java**, split into three modules:

* **BootstrappingNode/** — discovery/registry to help nodes join the cluster
* **Node/** — the data node (serves reads/writes)
* **DBMS/** — storage & coordination logic (domain layer)

The repository also includes helper scripts to build and run multiple nodes locally.

---

## ✨ What’s Inside

* **Cluster join via Bootstrapping Node** (central rendezvous at startup)
* **Multiple Data Nodes** (run N nodes on different ports)
* **Basic key–value operations** (put/get/delete) — depending on your Node API
* **Clear module split**: UI-free Java core, simple runnable nodes
* **Docs**:

  * `Decentralized Cluster (Haifawi).pdf`
  * `NoSql DB.pdf`

> Tip: If you add HTTP endpoints or a CLI, list them in the **API** section below.

---

## 🗂 Repository Structure

```
.
├─ BootstrappingNode/                 # Service used for node discovery/registration
├─ DBMS/                              # Core database logic (storage/coordination)
├─ Node/                              # Data node service (handles client requests)
├─ Decentralized Cluster (Haifawi).pdf
├─ NoSql DB.pdf
├─ rebuild.sh                         # Helper: clean + rebuild project(s)
├─ run.sh                             # Helper: start bootstrapping node + N data nodes
├─ stop.sh                            # Helper: stop running nodes
├─ .gitignore
└─ NoSQL-Databaseb.iml                # IntelliJ project file
```

---

## 🧱 Prerequisites

* **JDK 17+** (`java -version`)
* **IntelliJ IDEA** (recommended) or your favorite Java IDE
* macOS/Linux (for the provided `*.sh` scripts)

  > Windows users can run via IDE or WSL / Git Bash.

---

## 🚀 Quick Start

### 1) Make scripts executable (first time)

```bash
chmod +x rebuild.sh run.sh stop.sh
```

### 2) Build

```bash
./rebuild.sh
```

### 3) Run (bootstrapping node + a few data nodes)

```bash
./run.sh
```

### 4) Stop everything

```bash
./stop.sh
```

> Open the scripts to adjust ports, node count, and JVM options as needed.

---

## ▶️ Run from IDE (Alternative)

1. **Open** the project in IntelliJ (`File → Open → NoSQL-Database`).
2. Set **Project SDK** to **JDK 17+**.
3. Create run configurations for:

   * **Bootstrapping Node** main class
   * **Data Node** main class (duplicate config per node, with different `--port` / env)
4. Run the **Bootstrapping Node** first, then start **Node** instances.

---

## 🧩 Architecture (High Level)

```mermaid
flowchart LR
  subgraph Client
    C[Client App / CLI]
  end

  BS[BootstrappingNode]:::svc
  N1[Node #1]:::svc
  N2[Node #2]:::svc
  N3[Node #3]:::svc

  C -->|PUT/GET/DEL| N1
  C -->|PUT/GET/DEL| N2
  C -->|PUT/GET/DEL| N3

  N1 <-->|join/registry| BS
  N2 <-->|join/registry| BS
  N3 <-->|join/registry| BS

  classDef svc fill:#eef,stroke:#99a,stroke-width:1px,rx:8px,ry:8px;
```

**Flow (typical):**

1. **BootstrappingNode** starts and exposes a simple registry.
2. **Node** instances start, register with the bootstrapping node, and obtain cluster peers.
3. **Client** sends operations (`put/get/delete`) to any node (node routes/handles data per your logic).

> If you implement hashing/replication later, extend this section with ring diagrams and consistency rules.

---

## ⚙️ Configuration

Common items you’ll likely want to wire (via args or env):

* **Ports**

  * Bootstrapping node (e.g., `--port=7000`)
  * Data nodes (e.g., `--port=7101`, `--port=7102`, …)

* **Bootstrap Endpoint**

  * Node startup arg: `--bootstrapHost=localhost --bootstrapPort=7000`

* **Storage Path** (if you use filesystem)

  * `--dataDir=/tmp/node-7101`

* **Cluster Options** (optional/future)

  * `--replicationFactor=3`
  * `--hashRing=consistent`

> Document your actual flags as you finalize your `main` classes.

---

## 🔌 API (Document here as you implement)

**Examples (fill with your real endpoints/CLI usage):**

### HTTP

```
PUT   /kv/{key}       body=<value>
GET   /kv/{key}
DELETE /kv/{key}
GET   /cluster/nodes
POST  /cluster/join   body={ host, port }
```

### CLI

```bash
java -jar node.jar put user:1001 '{"name":"Marya"}'
java -jar node.jar get user:1001
java -jar node.jar del user:1001
```

---

## 🧪 Testing (Suggested)

* **Unit tests** for storage layer (put/get/delete, overwrite, delete non-existent)
* **Concurrency tests** (parallel puts/gets on same key range)
* **Multi-node tests** (start 3 nodes; verify distribution and basic fail behavior)
* **Serialization** (if you gossip/handshake, test payloads)

---

## 🛠 Troubleshooting

* **Port already in use** → change ports in `run.sh` or your run configs.
* **Nodes can’t join the cluster** → confirm bootstrap host/port and that BootstrappingNode is up.
* **Data not persisted** → verify you’re using the intended storage path or in-memory map.
* **Windows script issues** → run in WSL/Git Bash or use IDE run configs.

---

## 🗺 Roadmap (Nice-to-have)

* Consistent hashing ring + virtual nodes
* Replication factor (R) with quorum (`R/W`) and simple read-repair
* Gossip-based membership (failure detection)
* Snapshot/compaction for on-disk engine
* Basic metrics (`/health`, `/metrics`)

---

## 🤝 Contributing

* Keep modules cohesive (Bootstrapping vs Node vs DBMS).
* Favor **clean interfaces** (`Storage`, `Membership`, `Router`) and **unit tests** per module.
* Small, focused PRs with clear test coverage.

---
