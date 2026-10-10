# M5 — Single App, Role-Based Launch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the three separate Spring Boot apps (Node, BootstrappingNode, DBMS) into one Maven reactor that produces a single executable jar launched in a chosen role (`node` / `coordinator` / `gateway`).

**Architecture:** A parent reactor pom aggregates the three existing modules as plain libraries plus a new `app` module. `app` is the only Spring Boot application; three `@Profile` + `@ComponentScan` configs activate exactly one role's beans. Java packages are unchanged (`…Node`, `…BootstrappingNode`, `…DBMS`); files move only between Maven modules, not between packages. This plan covers **Phase 1** (structure + launcher, the committed scope); Phase 2 (`common` de-duplication) is a separate later plan.

**Tech Stack:** Java 17, Spring Boot 2.7.6, Spring Security 5.7, jjwt 0.11.5, Thymeleaf, Maven multi-module reactor, JUnit 5 + AssertJ + MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-10-m5-single-app-role-based-design.md`

## Global Constraints

- Build with `JAVA_HOME=C:\Program Files\Java\jdk-17` (Spring Boot 2.7.x does not run on the default newer JDK here).
- No Java package renames: keep `com.database.atypon.Node.*`, `…BootstrappingNode.*`, `…DBMS.*`.
- No behavioural change to any endpoint, routing, replication, indexing, or security rule; no change to the HTTP/JSON wire protocol.
- Standardize all modules on Spring Boot **2.7.6** (DBMS's current version; bumps Node/Bootstrap from 2.7.5).
- Author every commit as Mahmoud Haifawi; **never** add a `Co-Authored-By` or any attribution trailer.
- Work directly on `master`; commit after each task.
- Run tests with the module's own Maven: the reactor root runs all modules via `mvn -B -ntp verify`.
- The dev JWT secret used in tests and defaults is `dev-secret-change-me-please-at-least-32-bytes-long-000` (matches the current `application.properties` so no token behaviour changes).

## Review Focus

These launcher/packaging failure modes are implied by the spec; each has a test added to Task 5 (the launcher task), which owns the code:
- **No role given** (`java -jar nosql-db.jar` with no `--role`/profile) → must fail fast with a usage message and non-zero exit, not boot an empty context.
- **Unknown role** (`--role=banana`) → must fail fast with "unknown role", not boot.
- **More than one role active** (`--spring.profiles.active=node,gateway`) → must fail fast ("choose exactly one role"), since two role scans would create conflicting beans.
- **Profile form instead of the alias** (`--spring.profiles.active=gateway`, no `--role`) → must boot the gateway normally.
- **Gateway templates in the fat jar** — the gateway's `templates/`/`static/` live in the `DBMS` jar; after merging, `GET /login` under the gateway profile must still render (200), proving classpath template resolution.

---

## Task 1: Parent reactor pom + repoint the three modules

Make the three existing projects children of a new aggregator pom. Nothing moves yet; the reactor just builds all three (still as executable jars). This is a safe, reversible first step.

**Files:**
- Create: `pom.xml` (repo root)
- Modify: `Node/pom.xml:5-10`, `BootstrappingNode/pom.xml:5-10`, `DBMS/pom.xml:5-10` (the `<parent>` block)

**Interfaces:**
- Produces: a reactor root `com.database.atypon:nosql-db:0.0.1-SNAPSHOT` (packaging `pom`) whose `<modules>` are `Node`, `BootstrappingNode`, `DBMS` (the `app` module is added in Task 5).

- [ ] **Step 1: Create the parent pom** `pom.xml` at the repo root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.6</version>
        <relativePath/>
    </parent>

    <groupId>com.database.atypon</groupId>
    <artifactId>nosql-db</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>nosql-db</name>
    <description>Decentralized cluster-based NoSQL document store — single artifact, role-based launch</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <modules>
        <module>Node</module>
        <module>BootstrappingNode</module>
        <module>DBMS</module>
    </modules>
</project>
```

- [ ] **Step 2: Repoint `Node/pom.xml`** — replace the `<parent>` block (lines 5-10) with the reactor parent, and drop the now-inherited `<java.version>` nothing else changes:

```xml
    <parent>
        <groupId>com.database.atypon</groupId>
        <artifactId>nosql-db</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
```

- [ ] **Step 3: Repoint `BootstrappingNode/pom.xml`** — same `<parent>` block replacement as Step 2.

- [ ] **Step 4: Repoint `DBMS/pom.xml`** — same `<parent>` block replacement as Step 2.

- [ ] **Step 5: Build the whole reactor**

Run: `JAVA_HOME="C:\Program Files\Java\jdk-17" mvn -q -B -ntp verify`
Expected: reactor builds `Node`, `BootstrappingNode`, `DBMS` in order; all 149 tests pass (`BUILD SUCCESS`). Each module still produces an executable jar.

- [ ] **Step 6: Commit**

```bash
git add pom.xml Node/pom.xml BootstrappingNode/pom.xml DBMS/pom.xml
git commit -m "build(m5): aggregate the three modules under a reactor parent pom"
```

---

## Task 2: Convert `Node` to a library

Remove Node's `main` application class and its repackage plugin so it becomes a plain library, and move the JWT-secret property into test resources (the merged app will own runtime config).

**Files:**
- Delete: `Node/src/main/java/com/database/atypon/Node/NodeApplication.java`
- Delete: `Node/src/main/resources/application.properties`
- Create: `Node/src/test/java/com/database/atypon/Node/NodeTestApplication.java`
- Create: `Node/src/test/resources/application.properties`
- Modify: `Node/pom.xml` (remove the `spring-boot-maven-plugin` `<build>` block)

**Interfaces:**
- Produces: `com.database.atypon.Node.NodeTestApplication` — the `@SpringBootConfiguration` that every `@SpringBootTest` in the Node module discovers.

- [ ] **Step 1: Delete the main class and the main `application.properties`**

```bash
git rm Node/src/main/java/com/database/atypon/Node/NodeApplication.java
git rm Node/src/main/resources/application.properties
```

- [ ] **Step 2: Add the test application** `Node/src/test/java/com/database/atypon/Node/NodeTestApplication.java`:

```java
package com.database.atypon.Node;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot configuration for the Node module's own @SpringBootTest tests (test scope only). */
@SpringBootApplication
public class NodeTestApplication {
}
```

- [ ] **Step 3: Add test resources** `Node/src/test/resources/application.properties`:

```properties
app.jwt.secret=dev-secret-change-me-please-at-least-32-bytes-long-000
```

- [ ] **Step 4: Make the module a library** — in `Node/pom.xml`, delete the entire `<build>...</build>` block (the `spring-boot-maven-plugin`). A library needs no repackage.

- [ ] **Step 5: Run the Node module tests**

Run: `JAVA_HOME="C:\Program Files\Java\jdk-17" mvn -q -B -ntp -pl Node test`
Expected: all Node tests pass (110), discovering `NodeTestApplication`. `NodeSecuredApiIntegrationTest` still logs in and runs the full CRUD lifecycle.

- [ ] **Step 6: Commit**

```bash
git add Node/
git commit -m "build(m5): make Node a library (drop main + repackage; test boot config)"
```

---

## Task 3: Convert `BootstrappingNode` to a library + relocate mesh wiring

Same library conversion, plus move the coordinator's start-up mesh wiring out of the deleted `main` into a profile-gated bean so it runs only under the coordinator role.

**Files:**
- Delete: `BootstrappingNode/src/main/java/com/database/atypon/BootstrappingNode/BootstrappingNodeApplication.java`
- Delete: `BootstrappingNode/src/main/resources/application.properties`
- Create: `BootstrappingNode/src/main/java/com/database/atypon/BootstrappingNode/MeshWiringRunner.java`
- Create: `BootstrappingNode/src/test/java/com/database/atypon/BootstrappingNode/BootstrapTestApplication.java`
- Create: `BootstrappingNode/src/test/resources/application.properties`
- Modify: `BootstrappingNode/pom.xml` (remove the `spring-boot-maven-plugin` `<build>` block)

**Interfaces:**
- Consumes: `com.database.atypon.BootstrappingNode.utils.ClusterNodes.all()`, `com.database.atypon.BootstrappingNode.security.ServiceTokens.bearer()` (unchanged).
- Produces: `com.database.atypon.BootstrappingNode.BootstrapTestApplication` (test `@SpringBootConfiguration`); `MeshWiringRunner` (a `@Profile("coordinator")` `CommandLineRunner`).

- [ ] **Step 1: Delete the main class and main `application.properties`**

```bash
git rm BootstrappingNode/src/main/java/com/database/atypon/BootstrappingNode/BootstrappingNodeApplication.java
git rm BootstrappingNode/src/main/resources/application.properties
```

- [ ] **Step 2: Add the mesh-wiring runner** `BootstrappingNode/src/main/java/com/database/atypon/BootstrappingNode/MeshWiringRunner.java` (the exact logic from the old `main`, now profile-gated):

```java
package com.database.atypon.BootstrappingNode;

import com.database.atypon.BootstrappingNode.model.Node;
import com.database.atypon.BootstrappingNode.security.ServiceTokens;
import com.database.atypon.BootstrappingNode.utils.ClusterNodes;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Vector;

/**
 * On start-up (coordinator role only), wires every data node's peer list and self-assignment.
 * A node that is not reachable yet is skipped; it is wired when it comes up and re-registers.
 */
@Component
@Profile("coordinator")
public class MeshWiringRunner implements CommandLineRunner {

    @Override
    public void run(String... args) {
        RestTemplate restTemplate = new RestTemplate();
        List<Node> nodes = ClusterNodes.all();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", ServiceTokens.bearer());

        for (int i = 0; i < nodes.size(); i++) {
            List<Node> peers = new Vector<>();
            for (int j = 0; j < nodes.size(); j++) {
                if (i != j) {
                    peers.add(nodes.get(j));
                }
            }
            String base = nodes.get(i).getURL();
            try {
                restTemplate.postForObject(base + "/network/add/nodes", new HttpEntity<>(peers, headers), String.class);
                restTemplate.postForObject(base + "/network/assign/self", new HttpEntity<>(nodes.get(i), headers), String.class);
            } catch (Exception e) {
                // Node not reachable yet; wired when it comes up and re-registers.
            }
        }
    }
}
```

- [ ] **Step 3: Add the test application** `BootstrappingNode/src/test/java/com/database/atypon/BootstrappingNode/BootstrapTestApplication.java`:

```java
package com.database.atypon.BootstrappingNode;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot configuration for the BootstrappingNode module's @SpringBootTest tests (test scope only). */
@SpringBootApplication
public class BootstrapTestApplication {
}
```

- [ ] **Step 4: Add test resources** `BootstrappingNode/src/test/resources/application.properties`:

```properties
app.jwt.secret=dev-secret-change-me-please-at-least-32-bytes-long-000
```

- [ ] **Step 5: Make the module a library** — in `BootstrappingNode/pom.xml`, delete the entire `<build>...</build>` block.

- [ ] **Step 6: Run the BootstrappingNode module tests**

Run: `JAVA_HOME="C:\Program Files\Java\jdk-17" mvn -q -B -ntp -pl BootstrappingNode test`
Expected: all 17 tests pass. `BootstrapAuthorizationTest` now boots **without** firing the mesh runner (no `coordinator` profile in tests), so `/cluster` and `/createNewUser` behave as before.

- [ ] **Step 7: Commit**

```bash
git add BootstrappingNode/
git commit -m "build(m5): make BootstrappingNode a library; mesh wiring -> @Profile(coordinator) runner"
```

---

## Task 4: Convert `DBMS` to a library

Same library conversion. Delete the main class (including its inner `WebServerFactoryCustomizer` that hardcodes port 8078 — the port now comes from a profile property).

**Files:**
- Delete: `DBMS/src/main/java/com/database/atypon/DBMS/DbmsApplication.java`
- Delete: `DBMS/src/main/resources/application.properties`
- Create: `DBMS/src/test/java/com/database/atypon/DBMS/DbmsTestApplication.java`
- Create: `DBMS/src/test/resources/application.properties`
- Modify: `DBMS/src/test/java/com/database/atypon/DBMS/DbmsApplicationTests.java` (if it references `DbmsApplication`)
- Modify: `DBMS/pom.xml` (remove the `spring-boot-maven-plugin` `<build>` block)

**Interfaces:**
- Produces: `com.database.atypon.DBMS.DbmsTestApplication` (test `@SpringBootConfiguration`).

- [ ] **Step 1: Delete the main class and main `application.properties`**

```bash
git rm DBMS/src/main/java/com/database/atypon/DBMS/DbmsApplication.java
git rm DBMS/src/main/resources/application.properties
```

- [ ] **Step 2: Add the test application** `DBMS/src/test/java/com/database/atypon/DBMS/DbmsTestApplication.java`:

```java
package com.database.atypon.DBMS;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot configuration for the DBMS module's @SpringBootTest tests (test scope only). */
@SpringBootApplication
public class DbmsTestApplication {
}
```

- [ ] **Step 3: Add test resources** `DBMS/src/test/resources/application.properties`:

```properties
app.jwt.secret=dev-secret-change-me-please-at-least-32-bytes-long-000
```

- [ ] **Step 4: Fix the context-load test if needed** — open `DBMS/src/test/java/com/database/atypon/DBMS/DbmsApplicationTests.java`. If it imports or references `DbmsApplication`, remove that import; the class body should be just:

```java
package com.database.atypon.DBMS;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DbmsApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5: Make the module a library** — in `DBMS/pom.xml`, delete the entire `<build>...</build>` block (the `spring-boot-maven-plugin` with the lombok exclude; the exclude only matters for repackage, which moves to `app`).

- [ ] **Step 6: Run the DBMS module tests**

Run: `JAVA_HOME="C:\Program Files\Java\jdk-17" mvn -q -B -ntp -pl DBMS test`
Expected: all 22 tests pass, discovering `DbmsTestApplication`.

- [ ] **Step 7: Commit**

```bash
git add DBMS/
git commit -m "build(m5): make DBMS a library (drop main + inner port customizer)"
```

---

## Task 5: The `app` module — launcher, role configs, profile properties

Add the one Spring Boot application. It selects a role, activates only that role's beans, and produces the single fat jar.

**Files:**
- Create: `app/pom.xml`
- Create: `app/src/main/java/com/database/atypon/app/RoleResolver.java`
- Create: `app/src/main/java/com/database/atypon/app/NoSqlApplication.java`
- Create: `app/src/main/java/com/database/atypon/app/RoleConfigurations.java`
- Create: `app/src/main/resources/application.properties`
- Create: `app/src/main/resources/application-node.properties`
- Create: `app/src/main/resources/application-coordinator.properties`
- Create: `app/src/main/resources/application-gateway.properties`
- Create: `app/src/test/java/com/database/atypon/app/RoleResolverTest.java`
- Create: `app/src/test/java/com/database/atypon/app/GatewayRoleTest.java`
- Create: `app/src/test/java/com/database/atypon/app/NodeRoleIsolationTest.java`
- Create: `app/src/test/resources/application.properties`
- Modify: `pom.xml` (add `<module>app</module>`)

**Interfaces:**
- Consumes: the three role packages `com.database.atypon.Node`, `…BootstrappingNode`, `…DBMS` (component-scanned per profile); gateway beans `com.database.atypon.DBMS.controller.DashboardController`, node beans `com.database.atypon.Node.controllers.read.ReadController` (used in isolation tests).
- Produces: `RoleResolver.resolve(String[] args, String envProfiles) -> String` (returns `"node"`/`"coordinator"`/`"gateway"`, throws `IllegalArgumentException` on none/unknown/multiple); `com.database.atypon.app.NoSqlApplication` (the fat-jar entry point).

- [ ] **Step 1: Write the failing test for the role resolver** `app/src/test/java/com/database/atypon/app/RoleResolverTest.java`:

```java
package com.database.atypon.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleResolverTest {

    @Test
    void resolvesRoleFromRoleAlias() {
        assertThat(RoleResolver.resolve(new String[]{"--role=node"}, null)).isEqualTo("node");
    }

    @Test
    void resolvesRoleFromProfileArg() {
        assertThat(RoleResolver.resolve(new String[]{"--spring.profiles.active=gateway"}, null)).isEqualTo("gateway");
    }

    @Test
    void resolvesRoleFromEnvProfile() {
        assertThat(RoleResolver.resolve(new String[]{}, "coordinator")).isEqualTo("coordinator");
    }

    @Test
    void rejectsNoRole() {
        assertThatThrownBy(() -> RoleResolver.resolve(new String[]{}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no role");
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> RoleResolver.resolve(new String[]{"--role=banana"}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown role");
    }

    @Test
    void rejectsMultipleRoles() {
        assertThatThrownBy(() -> RoleResolver.resolve(new String[]{"--spring.profiles.active=node,gateway"}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }
}
```

- [ ] **Step 2: Create the `app` pom** `app/pom.xml` (depends on the three libraries; owns the repackage + lombok exclude):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.database.atypon</groupId>
        <artifactId>nosql-db</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>app</artifactId>
    <name>app</name>
    <description>Single role-based launcher for the NoSQL cluster</description>

    <dependencies>
        <dependency>
            <groupId>com.database.atypon</groupId>
            <artifactId>Node</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>com.database.atypon</groupId>
            <artifactId>BootstrappingNode</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>com.database.atypon</groupId>
            <artifactId>DBMS</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
            <exclusions>
                <exclusion>
                    <groupId>org.skyscreamer</groupId>
                    <artifactId>jsonassert</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>nosql-db</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

Then register the module: in the root `pom.xml`, add `<module>app</module>` after `<module>DBMS</module>`, so the reactor can resolve it in the next step.

- [ ] **Step 3: Implement the role resolver** `app/src/main/java/com/database/atypon/app/RoleResolver.java`:

```java
package com.database.atypon.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Resolves the single launch role from CLI args or the environment profile. */
public final class RoleResolver {

    public static final Set<String> ROLES = Set.of("node", "coordinator", "gateway");

    private RoleResolver() {
    }

    public static String resolve(String[] args, String envProfiles) {
        String source = firstNonBlank(argValue(args, "--role="),
                argValue(args, "--spring.profiles.active="), envProfiles);
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("no role selected");
        }
        List<String> known = new ArrayList<>();
        for (String part : source.split(",")) {
            String p = part.trim();
            if (ROLES.contains(p)) {
                known.add(p);
            }
        }
        if (known.isEmpty()) {
            throw new IllegalArgumentException("unknown role: " + source);
        }
        if (known.size() > 1) {
            throw new IllegalArgumentException("choose exactly one role, got: " + known);
        }
        return known.get(0);
    }

    private static String argValue(String[] args, String prefix) {
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run the resolver test**

Run: `JAVA_HOME="C:\Program Files\Java\jdk-17" mvn -q -B -ntp -pl app -am test -Dtest=RoleResolverTest`
Expected: 6 tests PASS (`-am` also builds the sibling libraries the module depends on).

- [ ] **Step 5: Implement the launcher** `app/src/main/java/com/database/atypon/app/NoSqlApplication.java`:

```java
package com.database.atypon.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single entry point for the whole system. One jar, launched in one role:
 *   java -jar nosql-db.jar --role=<node|coordinator|gateway>
 * (or --spring.profiles.active=<role>). Only the chosen role's beans are created.
 */
@SpringBootApplication(scanBasePackages = "com.database.atypon.app")
public class NoSqlApplication {

    public static void main(String[] args) {
        String role;
        try {
            role = RoleResolver.resolve(args, System.getenv("SPRING_PROFILES_ACTIVE"));
        } catch (IllegalArgumentException e) {
            System.err.println("nosql-db: " + e.getMessage());
            System.err.println("Usage: java -jar nosql-db.jar --role=<node|coordinator|gateway>");
            System.err.println("   (or --spring.profiles.active=<role>)");
            System.exit(1);
            return;
        }
        System.setProperty("spring.profiles.active", role);
        SpringApplication.run(NoSqlApplication.class, args);
    }
}
```

- [ ] **Step 6: Implement the role configurations** `app/src/main/java/com/database/atypon/app/RoleConfigurations.java` (each activates exactly one module's component scan under its profile):

```java
package com.database.atypon.app;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Role-selecting configurations: the active profile decides which module's beans are scanned. */
public class RoleConfigurations {

    @Configuration
    @Profile("node")
    @ComponentScan("com.database.atypon.Node")
    static class NodeRole {
    }

    @Configuration
    @Profile("coordinator")
    @ComponentScan("com.database.atypon.BootstrappingNode")
    static class CoordinatorRole {
    }

    @Configuration
    @Profile("gateway")
    @ComponentScan("com.database.atypon.DBMS")
    static class GatewayRole {
    }
}
```

- [ ] **Step 7: Create the profile properties.**

`app/src/main/resources/application.properties`:
```properties
# Shared across all roles; the same secret lets every role validate tokens the others issue.
app.jwt.secret=${JWT_SECRET:dev-secret-change-me-please-at-least-32-bytes-long-000}
```
`app/src/main/resources/application-node.properties`:
```properties
server.port=8080
```
`app/src/main/resources/application-coordinator.properties`:
```properties
server.port=8079
```
`app/src/main/resources/application-gateway.properties`:
```properties
server.port=8078
```
`app/src/test/resources/application.properties`:
```properties
app.jwt.secret=dev-secret-change-me-please-at-least-32-bytes-long-000
```

- [ ] **Step 8: Confirm the module is registered** — verify the root `pom.xml` already lists `<module>app</module>` (added in Step 2). If not, add it now.

- [ ] **Step 9: Write the gateway role test** (proves the gateway profile boots, templates resolve, and node beans are absent) `app/src/test/java/com/database/atypon/app/GatewayRoleTest.java`:

```java
package com.database.atypon.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("gateway")
class GatewayRoleTest {

    @Autowired
    ApplicationContext ctx;
    @Autowired
    MockMvc mvc;

    @Test
    void gatewayBeansLoadAndNodeBeansDoNot() {
        assertThat(ctx.getBeanNamesForType(com.database.atypon.DBMS.controller.DashboardController.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(com.database.atypon.Node.controllers.read.ReadController.class)).isEmpty();
    }

    @Test
    void loginPageRendersFromTemplatesOnTheClasspath() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 10: Write the node role isolation test** `app/src/test/java/com/database/atypon/app/NodeRoleIsolationTest.java`:

```java
package com.database.atypon.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("node")
class NodeRoleIsolationTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void nodeBeansLoadAndGatewayBeansDoNot() {
        assertThat(ctx.getBeanNamesForType(com.database.atypon.Node.controllers.read.ReadController.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(com.database.atypon.DBMS.controller.DashboardController.class)).isEmpty();
    }
}
```

- [ ] **Step 11: Build and test the whole reactor**

Run: `JAVA_HOME="C:\Program Files\Java\jdk-17" mvn -q -B -ntp verify`
Expected: all modules build; `app` produces `app/target/nosql-db.jar`; every test passes (149 existing + 6 resolver + 3 role tests = 158).

- [ ] **Step 12: Smoke-test the single jar live (single node)**

```bash
# from three shells, or background each and curl:
java -jar app/target/nosql-db.jar --role=node          # 8080
java -jar app/target/nosql-db.jar --role=coordinator   # 8079
java -jar app/target/nosql-db.jar --role=gateway       # 8078
```
Verify: `curl -s localhost:8080/health` → `UP`; open `http://localhost:8078/login`, sign in `mahmoud`/`admin`, dashboard shows the topology panel with `localhost:8080` reachable. Then `java -jar app/target/nosql-db.jar` (no role) → prints usage and exits non-zero.

- [ ] **Step 13: Commit**

```bash
git add app/ pom.xml
git commit -m "feat(m5): single role-based launcher (one jar: node|coordinator|gateway)"
```

---

## Task 6: One Docker image + compose + scripts + docs

Replace the three per-module Dockerfiles with a single image built from the reactor, run five times by profile. Update the run scripts and RUN.md.

**Files:**
- Create: `Dockerfile` (repo root)
- Delete: `Node/Dockerfile`, `BootstrappingNode/Dockerfile`, `DBMS/Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `run.sh`, `rebuild.sh`, `stop.sh`
- Modify: `RUN.md`

**Interfaces:**
- Produces: one image whose role is chosen by `SPRING_PROFILES_ACTIVE`.

- [ ] **Step 1: Create the root `Dockerfile`:**

```dockerfile
# ---- build stage: build the whole reactor, produce the single app jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY Node Node
COPY BootstrappingNode BootstrappingNode
COPY DBMS DBMS
COPY app app
RUN mvn -q -B -DskipTests -pl app -am package

# ---- run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/app/target/nosql-db.jar app.jar
# Seed the admin registry so a node-role container authenticates on a fresh volume.
COPY Node/data/info.json data/info.json
# Role is chosen at runtime via SPRING_PROFILES_ACTIVE (node|coordinator|gateway).
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Delete the per-module Dockerfiles**

```bash
git rm Node/Dockerfile BootstrappingNode/Dockerfile DBMS/Dockerfile
```

- [ ] **Step 3: Rewrite `docker-compose.yml`** (one image, built once, reused by profile):

```yaml
# One image, launched five times by role. Build once:  docker compose up --build
# Open http://localhost:8078/login   (seeded admin: mahmoud / admin)
services:
  Node0:
    image: nosql-db:latest
    build: .
    environment:
      SPRING_PROFILES_ACTIVE: node
    networks: [db_cluster]
  Node1:
    image: nosql-db:latest
    environment:
      SPRING_PROFILES_ACTIVE: node
    networks: [db_cluster]
  Node2:
    image: nosql-db:latest
    environment:
      SPRING_PROFILES_ACTIVE: node
    networks: [db_cluster]

  bootstrappingnode:
    image: nosql-db:latest
    environment:
      SPRING_PROFILES_ACTIVE: coordinator
      CLUSTER_NODES: "Node0:8080,Node1:8080,Node2:8080"
    depends_on: [Node0, Node1, Node2]
    networks: [db_cluster]

  dbms:
    image: nosql-db:latest
    environment:
      SPRING_PROFILES_ACTIVE: gateway
      BOOTSTRAP_URL: "http://bootstrappingnode:8079"
    ports:
      - "8078:8078"
    depends_on: [bootstrappingnode]
    networks: [db_cluster]

networks:
  db_cluster:
    driver: bridge
```

- [ ] **Step 4: Validate the compose file**

Run: `docker compose config`
Expected: prints the resolved config with no errors. (The Docker daemon may be down in this environment — if so, `docker compose config` still validates syntax; the full `up --build` is validated wherever Docker runs.)

- [ ] **Step 5: Update the run scripts** — open `run.sh`, `rebuild.sh`, `stop.sh` and replace any three-jar logic with the single-jar model: build with `mvn -q -B -DskipTests -pl app -am package`, then launch `app/target/nosql-db.jar` three times with `--role=node|coordinator|gateway` (background each; `stop.sh` kills by port 8078/8079/8080). Keep each script's existing structure and comments; only the build/launch commands change.

- [ ] **Step 6: Rewrite `RUN.md`** — update the module table note to "one artifact, launched by role", replace Option B's three-jar build/run with:

```bash
# build the single jar (JDK 17)
mvn -q -B -DskipTests -pl app -am clean package   # -> app/target/nosql-db.jar

# run each role in its own terminal:
java -jar app/target/nosql-db.jar --role=node          # 8080
java -jar app/target/nosql-db.jar --role=coordinator   # 8079
java -jar app/target/nosql-db.jar --role=gateway       # 8078
```
Keep the seeded-admin line, the single-node affinity caveat, and the B+-tree demo section unchanged. Update Option A only if any service names/env changed (they did not).

- [ ] **Step 7: Commit**

```bash
git add Dockerfile docker-compose.yml run.sh rebuild.sh stop.sh RUN.md
git commit -m "build(m5): one Docker image + compose launched by role; update scripts and RUN.md"
```

- [ ] **Step 8: Update CI** — in `.github/workflows/ci.yml`, replace the per-module matrix build with a single reactor build:

```yaml
      - name: Build and test (reactor)
        run: mvn -B -ntp verify
```
Keep the JDK-17 setup step. Remove the `strategy.matrix` over `[Node, BootstrappingNode, DBMS]` and any per-module `working-directory`. Commit:

```bash
git add .github/workflows/ci.yml
git commit -m "ci(m5): build the whole reactor in one job"
```

- [ ] **Step 9: Push**

```bash
git push origin master
```

---

## Phase 2 (deferred, separate plan)

Extract the duplicated cross-cutting code (`Response`/`ResponseType`, the JWT security layer, `Node`/`User` models) into a new `common` library that `Node`/`BootstrappingNode`/`DBMS` depend on. Higher import churn, no behavioural or wire-format change, landed incrementally behind the green suite. This gets its own plan once Phase 1 is on `master`; Phase 1 is a complete, shippable milestone on its own.
