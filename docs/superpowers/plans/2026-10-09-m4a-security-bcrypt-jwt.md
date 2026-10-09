# M4a — Security: BCrypt + JWT with Spring Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace plaintext passwords and role-string tokens with BCrypt hashing and signed HS256 JWTs, enforced by Spring Security in all three modules (Node + BootstrappingNode as JWT resource servers, DBMS as a form-login client with a custom provider).

**Architecture:** A shared HMAC secret signs JWTs carrying `sub`/`role`/`exp`. A per-module `JwtService` mints/parses tokens; a `JwtAuthFilter` populates the Spring `SecurityContext`; `SecurityFilterChain` beans enforce role-based URL rules. Controllers branch on the role read from the context. Peer calls use a minted internal service JWT. The DBMS authenticates via a custom `AuthenticationProvider` that calls the node and stores the JWT in session.

**Tech Stack:** Java 17, Spring Boot 2.7.5, Spring Security 5.7, jjwt 0.11.5, spring-security-crypto BCrypt, org.json, JUnit 5 + AssertJ + Mockito + spring-security-test.

**Spec:** `docs/superpowers/specs/2026-10-09-m4a-security-bcrypt-jwt-design.md`

## Global Constraints

- Build/test with `JAVA_HOME=C:\Program Files\Java\jdk-17`. Maven 3.9.
- Work directly on `master`; commit (and push) there.
- One shared secret via `${JWT_SECRET}` (dev default in each `application.properties`), ≥ 32 bytes, identical across modules.
- JWT: HS256, claims `sub`=username, `role`=`ADMIN|USER|INTERNAL`, `iat`, `exp` (user 1h, service 5m). Header `Authorization: Bearer <jwt>`; filters tolerate a bare token.
- Authorities: USER→`ROLE_USER`; ADMIN→`ROLE_ADMIN`+`ROLE_USER`; INTERNAL→`ROLE_INTERNAL`+`ROLE_ADMIN`+`ROLE_USER`. `hasRole("ADMIN")` admits ADMIN+INTERNAL; `hasRole("USER")` admits all; `hasRole("INTERNAL")` service-only.
- Spring Security 5.7: use `SecurityFilterChain` beans + `@EnableWebSecurity` (not `WebSecurityConfigurerAdapter`). `antMatchers` is the 5.7 API.
- jjwt deps: `io.jsonwebtoken:jjwt-api:0.11.5` (compile) + `jjwt-impl:0.11.5` + `jjwt-jackson:0.11.5` (runtime).
- Single-node local run has no peers, so replication peer-calls are a no-op there; multi-node (Docker) peer auth is covered by Task 3.

---

### Task 1: Node — BCrypt passwords + `JwtService` foundation

**Files:**
- Modify: `Node/pom.xml`
- Modify: `Node/src/main/resources/application.properties`
- Create: `Node/src/main/java/com/database/atypon/Node/security/JwtService.java`
- Create: `Node/src/main/java/com/database/atypon/Node/security/PasswordConfig.java`
- Modify: `Node/.../services/authentication/AuthenticationService.java` (BCrypt verify)
- Modify: `Node/.../operations/admin/AdminOperations.java` (hash on addUser)
- Modify: `Node/data/info.json` (re-seed admin with a BCrypt hash)
- Test: `Node/src/test/java/com/database/atypon/Node/security/JwtServiceTest.java`
- Test: `Node/.../services/authentication/AuthenticationServiceBcryptTest.java`

**Interfaces produced:**
- `JwtService` (`@Service`): `String generateToken(String username, String role, java.time.Duration ttl)`, `String generateServiceToken()`, `io.jsonwebtoken.Jws<io.jsonwebtoken.Claims> parse(String token)`, `static java.util.List<org.springframework.security.core.GrantedAuthority> authorities(String role)`, `static String roleFromAuthorities(java.util.Collection<? extends org.springframework.security.core.GrantedAuthority>)`.
- `PasswordConfig`: `@Bean BCryptPasswordEncoder passwordEncoder()`.

- [ ] **Step 1: Add dependencies.** In `Node/pom.xml` `<dependencies>`, add:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.security</groupId>
			<artifactId>spring-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-api</artifactId>
			<version>0.11.5</version>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-impl</artifactId>
			<version>0.11.5</version>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-jackson</artifactId>
			<version>0.11.5</version>
			<scope>runtime</scope>
		</dependency>
```

- [ ] **Step 2: Add the secret property.** Append to `Node/src/main/resources/application.properties`:

```
app.jwt.secret=${JWT_SECRET:dev-secret-change-me-please-at-least-32-bytes-long-000}
```

- [ ] **Step 3: Write `JwtServiceTest`** (create `security` test package):

```java
package com.database.atypon.Node.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwt = new JwtService("test-secret-test-secret-test-secret-123456");

    @Test
    void generateAndParseRoundTrip() {
        String token = jwt.generateToken("mahmoud", "ADMIN", Duration.ofHours(1));
        Jws<Claims> parsed = jwt.parse(token);
        assertThat(parsed.getBody().getSubject()).isEqualTo("mahmoud");
        assertThat(parsed.getBody().get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void expiredTokenIsRejected() {
        String token = jwt.generateToken("x", "USER", Duration.ofSeconds(-1));
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwt.generateToken("x", "USER", Duration.ofHours(1));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "b" : "a") + "c";
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void authoritiesEncodeHierarchy() {
        assertThat(JwtService.authorities("USER")).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER");
        assertThat(JwtService.authorities("ADMIN")).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(JwtService.authorities("INTERNAL")).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN", "ROLE_INTERNAL");
    }
}
```

- [ ] **Step 4: Run it, verify it fails** — `cd Node && mvn -q -Dtest=JwtServiceTest test` → FAIL (no `JwtService`). (Maven resolves the new deps on first run.)

- [ ] **Step 5: Implement `JwtService`:**

```java
package com.database.atypon.Node.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String role, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(username)
                .claim("role", role)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(ttl)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateServiceToken() {
        return generateToken("service", "INTERNAL", Duration.ofMinutes(5));
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
    }

    public static List<GrantedAuthority> authorities(String role) {
        List<GrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_USER"));
        if ("ADMIN".equals(role) || "INTERNAL".equals(role)) list.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if ("INTERNAL".equals(role)) list.add(new SimpleGrantedAuthority("ROLE_INTERNAL"));
        return list;
    }

    public static String roleFromAuthorities(Collection<? extends GrantedAuthority> auths) {
        boolean internal = auths.stream().anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"));
        boolean admin = auths.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (internal) return "INTERNAL";
        if (admin) return "ADMIN";
        return "USER";
    }
}
```

- [ ] **Step 6: Run `JwtServiceTest`, verify PASS.**

- [ ] **Step 7: Add `PasswordConfig`:**

```java
package com.database.atypon.Node.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class PasswordConfig {
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 8: Write `AuthenticationServiceBcryptTest`** (uses a real encoder + a temp info.json). Mirror the existing auth read path (`PathBuilder.getPathToMainInfo()` → `./data/info.json`). Since `authenticateUser` reads `./data/info.json`, seed it in the test working dir:

```java
package com.database.atypon.Node.services.authentication;

import com.database.atypon.Node.model.User;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationServiceBcryptTest {

    private final Path info = Paths.get("./data/info.json");
    private byte[] original;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(info.getParent());
        original = Files.exists(info) ? Files.readAllBytes(info) : null;
        String hash = new BCryptPasswordEncoder().encode("secret");
        Files.writeString(info, new JSONObject()
                .put("databases", new org.json.JSONArray())
                .put("users", new JSONObject().put("alice",
                        new JSONObject().put("username", "alice").put("role", "user").put("password", hash)))
                .toString());
    }

    @AfterEach
    void restore() throws Exception {
        if (original != null) Files.write(info, original);
    }

    private static User user(String name, String pw) {
        User u = new User();
        u.setUsername(name);
        u.setPassword(pw);
        return u;
    }

    @Test
    void acceptsCorrectPasswordAndRejectsWrong() {
        AuthenticationService svc = new AuthenticationService(new BCryptPasswordEncoder());
        assertThat(svc.authenticateUser(user("alice", "secret")).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(svc.authenticateUser(user("alice", "wrong")).getResponseType()).isEqualTo(ResponseType.ERROR);
    }
}
```

(`Node/.../model/User.java` has a no-arg constructor + setters, no `(username,password)` constructor.)

- [ ] **Step 9: Run it, verify it fails** (constructor + BCrypt compare not wired).

- [ ] **Step 10: Wire BCrypt into `AuthenticationService`.** Inject `BCryptPasswordEncoder` (constructor) and replace the plaintext compare `userObject.getString("password").equals(user.getPassword())` with `!passwordEncoder.matches(user.getPassword(), userObject.getString("password"))`. Leave `generateToken` returning the role **for now** (Task 2 switches it to a JWT) so the app keeps working this task.

- [ ] **Step 11: Hash on add-user.** In `AdminOperations.addUser`, hash before storing: replace `obj.put("password", user.getPassword());` with `obj.put("password", new BCryptPasswordEncoder().encode(user.getPassword()));`.

- [ ] **Step 12: Re-seed the admin.** Generate a BCrypt hash of `admin` and write `Node/data/info.json` so `mahmoud`'s `password` is that hash (keep `databases` as-is). Produce the hash with a throwaway run:

Run: `cd Node && JAVA_HOME="/c/Program Files/Java/jdk-17" mvn -q compile exec:java -Dexec.mainClass=... ` — simpler: in the implementing session, compute it inline via a tiny Java/JShell or reuse `new BCryptPasswordEncoder().encode("admin")` from a scratch test, then paste the hash into `info.json`. The file ends as:

```json
{ "databases": [], "users": { "mahmoud": { "password": "<bcrypt-hash-of-admin>", "role": "admin", "username": "mahmoud" } } }
```

- [ ] **Step 13: Run the full Node suite** — `cd Node && mvn test` → all green (behaviour still role-string tokens; only hashing changed).

- [ ] **Step 14: Commit**

```bash
git add Node/pom.xml Node/src/main/resources/application.properties Node/src/main/java/com/database/atypon/Node/security/ Node/src/main/java/com/database/atypon/Node/services/authentication/AuthenticationService.java Node/src/main/java/com/database/atypon/Node/operations/admin/AdminOperations.java Node/data/info.json Node/src/test/java/com/database/atypon/Node/security/JwtServiceTest.java Node/src/test/java/com/database/atypon/Node/services/authentication/AuthenticationServiceBcryptTest.java
git commit -m "feat(node): BCrypt password hashing + JwtService (HS256) foundation"
```

---

### Task 2: Node — JWT filter chain, login issues JWT, controller role branches

**Files:**
- Create: `Node/.../security/JwtAuthFilter.java`
- Create: `Node/.../security/SecurityConfig.java`
- Create: `Node/.../security/SecurityUtils.java`
- Modify: `AuthenticationService.generateToken` (return a JWT) + `AuthenticationController` (unchanged call, now yields a JWT)
- Modify: controllers `AdminController`, `read/ReadController`, `write/WriteController`, `index/IndexController` — remove token-string authz checks (the filter chain enforces access) and branch on `SecurityUtils.currentRole()`.
- Modify: `AuthenticationService` — remove `isAdminToken/isUserToken/isInternalToken` (now unused).
- Test: `Node/.../security/NodeAuthorizationTest.java` (MockMvc)

**Interfaces produced:**
- `SecurityUtils.currentRole()` → `"ADMIN"|"USER"|"INTERNAL"|null`.
- `JwtService.generateToken` now used by login to mint a user JWT (1h).

- [ ] **Step 1: `JwtAuthFilter`:**

```java
package com.database.atypon.Node.security;

import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws javax.servlet.ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && !header.isBlank()) {
            String token = header.startsWith("Bearer ") ? header.substring(7) : header;
            try {
                Claims claims = jwtService.parse(token).getBody();
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                var auth = new UsernamePasswordAuthenticationToken(username, null, JwtService.authorities(role));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                SecurityContextHolder.clearContext(); // invalid/expired -> anonymous
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 2: `SecurityConfig`:**

```java
package com.database.atypon.Node.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                .antMatchers("/login").permitAll()
                .antMatchers("/network/**").hasRole("INTERNAL")
                .antMatchers("/admin/**").hasRole("ADMIN")
                .antMatchers("/user/**", "/write/**").hasRole("USER")
                .anyRequest().authenticated()
                .and()
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 3: `SecurityUtils`:**

```java
package com.database.atypon.Node.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {}

    /** The caller's role from the authenticated context, or null if unauthenticated. */
    public static String currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return JwtService.roleFromAuthorities(auth.getAuthorities());
    }
}
```

- [ ] **Step 4: Login issues a JWT.** Inject `JwtService` into `AuthenticationService`; change `generateToken(User user)` to return `jwtService.generateToken(user.getUsername(), user.getRole(), Duration.ofHours(1))`. `AuthenticationController.login` is unchanged (it already returns the `generateToken` result as content) and must be reachable: it is `POST /login` (permitAll).

- [ ] **Step 5: Convert controllers** — remove the `@RequestHeader("authorization") String token` authz checks (the filter chain now gates access) and branch on role. Apply exactly:
  - `read/ReadController` (`fetchById`, `fetchAll`): delete the `if(!authenticationService.isUserToken(token)) return ERROR;` blocks and the `token` params; keep input validation. Remove the now-unused `AuthenticationService` field.
  - `AdminController` (`addUser`, `createDatabase`): remove the `isAdminToken` guard; change the broadcast gate `if(!token.equals(Token.ADMIN)) return responses;` to `if(!"ADMIN".equals(SecurityUtils.currentRole())) return responses;`. Drop the `token` param + `AuthenticationService` field.
  - `write/WriteController` (`createSchema`, `createDocument`, `updateDocument`, `deleteDocument`): remove `isUserToken`/`isInternalToken` guards; replace `authenticationService.isInternalToken(token)` branches with `"INTERNAL".equals(SecurityUtils.currentRole())`; replace the schema broadcast gate `token.equals(Token.INTERNAL)` logic equivalently (internal → no broadcast). Drop `token` params + `AuthenticationService` field.
  - `index/IndexController` (`createIndex`, `dropIndex`, `listIndexes`, `query`): remove `isAdminToken`/`isUserToken` guards; change broadcast gate `token.equals(Token.ADMIN)` to `"ADMIN".equals(SecurityUtils.currentRole())`. Drop `token` params + `AuthenticationService` field.
  - `authentication/AuthenticationController`: keep as-is (login).
  - Remove `isAdminToken/isUserToken/isInternalToken` from `AuthenticationService`.

  Where a peer previously relied on the `INTERNAL` token to reach a `/user`- or `/write`-gated endpoint, note the internal service JWT has `ROLE_USER` too, so it passes the URL rule; the in-controller `INTERNAL` branch still selects the apply/no-broadcast path.

- [ ] **Step 6: Write `NodeAuthorizationTest`** (MockMvc with `spring-security-test`). Use a real `JwtService` bean (test secret via `@TestPropertySource`) to mint tokens and assert URL authorization:

```java
package com.database.atypon.Node.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=test-secret-test-secret-test-secret-123456")
class NodeAuthorizationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("u", role, Duration.ofMinutes(5));
    }

    @Test
    void adminEndpointRejectsUserTokenAcceptsAdmin() throws Exception {
        mvc.perform(get("/admin/index/list?database=d&schema=s").header("Authorization", bearer("USER")))
                .andExpect(status().isForbidden());
        // admin reaches the controller (may be 200 or a handled error, but not 401/403)
        mvc.perform(get("/admin/index/list?database=d&schema=s").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void networkEndpointRequiresInternal() throws Exception {
        mvc.perform(get("/network/get/nodes").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/network/get/nodes").header("Authorization", bearer("INTERNAL")))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenIsUnauthorizedOnProtected() throws Exception {
        mvc.perform(get("/network/get/nodes")).andExpect(status().isForbidden());
    }
}
```

(Adjust expected status for `/admin/index/list` to whatever the handler returns on a missing index — the assertion that matters is USER→403 vs ADMIN→not-403. If the ADMIN call returns a non-2xx handled error, assert `status().is(not 403)` instead with a custom matcher, or target a simpler always-200 admin endpoint.)

- [ ] **Step 7: Run tests** — `cd Node && mvn -q -Dtest=NodeAuthorizationTest test`, then the full suite `mvn test`. Fix any controller tests that passed a token header (the M1/M2/M3 controller unit tests call controller methods directly, not via HTTP, so they are unaffected by the filter; but they may reference removed `AuthenticationService`/`Token` — update those unit tests to drop the token argument and the `auth` mock where the method signature changed). Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/security/ Node/src/main/java/com/database/atypon/Node/services/authentication/AuthenticationService.java Node/src/main/java/com/database/atypon/Node/controllers/ Node/src/test/java/com/database/atypon/Node/
git commit -m "feat(node): Spring Security JWT filter chain; login issues JWT; role-based authz"
```

---

### Task 3: Node — peer calls use an internal service JWT

**Files:**
- Create: `Node/.../security/ServiceTokens.java`
- Modify: `Node/.../model/Node.java` (all peer-call methods)
- Test: covered by live multi-node check (Task 6); add `ServiceTokensTest` if cheap.

**Interfaces produced:** `ServiceTokens.bearer()` → `"Bearer <internal-jwt>"` (static; backed by the Spring `JwtService`).

- [ ] **Step 1: `ServiceTokens`** — a static bridge so the non-bean `Node` model can mint service tokens:

```java
package com.database.atypon.Node.security;

import org.springframework.stereotype.Component;

@Component
public class ServiceTokens {
    private static JwtService jwtService;

    public ServiceTokens(JwtService jwtService) {
        ServiceTokens.jwtService = jwtService;
    }

    /** "Bearer <internal service JWT>" for node-to-node calls. */
    public static String bearer() {
        return "Bearer " + jwtService.generateServiceToken();
    }
}
```

- [ ] **Step 2: Update every peer call in `Node` model.** Replace each `headers.add("authorization", "internal");` with `headers.add("Authorization", com.database.atypon.Node.security.ServiceTokens.bearer());` in: `addUser`, `addDatabase`, `createSchema`, `createDocument`, `updateDocument`, `deleteDocument`, `createIndex`, `dropIndex` (and any others). (The network-wiring calls originate from the BootstrappingNode, handled in Task 4.)

- [ ] **Step 3: Build** — `cd Node && mvn -q -DskipTests package` → BUILD SUCCESS. (Peer auth is validated live in Task 6 under Docker; single-node has no peers.)

- [ ] **Step 4: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/security/ServiceTokens.java Node/src/main/java/com/database/atypon/Node/model/Node.java
git commit -m "feat(node): peer calls authenticate with a minted internal service JWT"
```

---

### Task 4: BootstrappingNode — JWT resource server

**Files:**
- Modify: `BootstrappingNode/pom.xml` (same deps as Task 1 Step 1)
- Modify: `BootstrappingNode/src/main/resources/application.properties` (same secret property)
- Create: `BootstrappingNode/.../security/JwtService.java`, `JwtAuthFilter.java`, `SecurityConfig.java`, `ServiceTokens.java` (copies of the Node versions, package `...BootstrappingNode.security`)
- Modify: `BootstrappingNode/.../controller/UserController.java` (createNewUser authorizes ADMIN caller, mints internal JWT for the node call)
- Modify: `BootstrappingNode/.../BootstrappingNodeApplication.java` (mesh wiring sends internal JWT)
- Test: `BootstrappingNode/.../security/BootstrapAuthorizationTest.java`

- [ ] **Step 1:** Add the deps (Task 1 Step 1) and the `app.jwt.secret` property.

- [ ] **Step 2:** Copy `JwtService`, `JwtAuthFilter`, `ServiceTokens` into `com.database.atypon.BootstrappingNode.security` (identical bodies, package renamed).

- [ ] **Step 3:** `SecurityConfig` for bootstrap:

```java
// package com.database.atypon.BootstrappingNode.security; (same imports as Node SecurityConfig)
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests()
                .antMatchers("/test", "/getUserNode").permitAll()
                .antMatchers("/createNewUser").hasRole("ADMIN")
                .antMatchers("/network/**").hasRole("INTERNAL")
                .anyRequest().authenticated()
                .and()
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 4:** In `UserController.createNewUser`, the inbound ADMIN JWT is enforced by the filter; for the downstream node `/admin/user/add` call, send an internal service JWT: change `headers.set("authorization", "admin");` to `headers.set("Authorization", ServiceTokens.bearer());`.

- [ ] **Step 5:** In `BootstrappingNodeApplication.run`, add `headers` with `ServiceTokens.bearer()` to the `/network/add/nodes` and `/network/assign/self` posts (they currently post with no auth). Build an `HttpEntity` with the `Authorization` header around the body.

- [ ] **Step 6: Write `BootstrapAuthorizationTest`** (MockMvc): `/test` + `/getUserNode` open; `/createNewUser` → 403 with USER, ok with ADMIN; `/network/**` → requires INTERNAL. (Model on `NodeAuthorizationTest`, test secret via `@TestPropertySource`.)

- [ ] **Step 7:** `cd BootstrappingNode && mvn test` → green.

- [ ] **Step 8: Commit**

```bash
git add BootstrappingNode/pom.xml BootstrappingNode/src/main/resources/application.properties BootstrappingNode/src/main/java/com/database/atypon/BootstrappingNode/security/ BootstrappingNode/src/main/java/com/database/atypon/BootstrappingNode/controller/UserController.java BootstrappingNode/src/main/java/com/database/atypon/BootstrappingNode/BootstrappingNodeApplication.java BootstrappingNode/src/test/java/
git commit -m "feat(bootstrap): JWT resource server; secure createNewUser + network wiring"
```

---

### Task 5: DBMS — Spring Security form-login + custom provider

**Files:**
- Modify: `DBMS/pom.xml` (deps), `DBMS/src/main/resources/application.properties` (secret)
- Create: `DBMS/.../security/JwtService.java` (parse + roleFromClaims only — reuse the Node copy)
- Create: `DBMS/.../security/DbmsUser.java` (principal: username, role, jwt, nodeURL)
- Create: `DBMS/.../security/NodeAuthenticationProvider.java`
- Create: `DBMS/.../security/SecurityConfig.java` (formLogin, success handler storing session token/nodeURL)
- Modify: `DBMS/.../controller/LoginController.java` (drop `loginPost`; keep GET `/login`)
- Modify: `DBMS/.../database_system/connection/ConnectionRequest.java` (`createNewUser` accepts + forwards the admin JWT)
- Modify: `DBMS/.../database_system/connection/DatabaseOperations.java` + `AdminOperations.java` + `service/AdminService.java` + `controller/AdminController.java` as needed to thread the token into `createNewUser`
- Delete: `DBMS/.../config/AuthInterceptor.java`, `DBMS/.../config/WebConfig.java`
- Test: `DBMS/.../security/NodeAuthenticationProviderTest.java`

- [ ] **Step 1:** Add deps (Task 1 Step 1) + `app.jwt.secret` property. Copy a minimal `JwtService` (needs `parse` + a `role(token)` helper; `generate*` unused here but copying the whole class is fine).

- [ ] **Step 2: `DbmsUser` principal:**

```java
package com.database.atypon.DBMS.security;

public class DbmsUser {
    private final String username, role, jwt, nodeURL;
    public DbmsUser(String username, String role, String jwt, String nodeURL) {
        this.username = username; this.role = role; this.jwt = jwt; this.nodeURL = nodeURL;
    }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public String getJwt() { return jwt; }
    public String getNodeURL() { return nodeURL; }
}
```

- [ ] **Step 3: `NodeAuthenticationProvider`** — authenticate by calling the node (reusing `ConnectionRequest`):

```java
package com.database.atypon.DBMS.security;

import com.database.atypon.DBMS.database_system.connection.ConnectionRequest;
import com.database.atypon.DBMS.model.User;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class NodeAuthenticationProvider implements AuthenticationProvider {

    private final JwtService jwtService;

    public NodeAuthenticationProvider(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        try {
            String nodeURL = ConnectionRequest.retrieveNodeURL(user);
            String jwt = ConnectionRequest.login(user, nodeURL); // node /login returns the JWT
            String role = jwtService.role(jwt);
            DbmsUser principal = new DbmsUser(username, role, jwt, nodeURL);
            return new UsernamePasswordAuthenticationToken(principal, null, JwtService.authorities(role));
        } catch (Exception e) {
            throw new BadCredentialsException("Login failed: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
```

- [ ] **Step 4: `SecurityConfig`** (formLogin + success handler storing session token/nodeURL):

```java
package com.database.atypon.DBMS.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final NodeAuthenticationProvider provider;

    public SecurityConfig(NodeAuthenticationProvider provider) {
        this.provider = provider;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .authenticationProvider(provider)
                .authorizeRequests()
                .antMatchers("/login", "/css/**", "/js/**", "/webjars/**").permitAll()
                .anyRequest().authenticated()
                .and()
                .formLogin()
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler((req, res, auth) -> {
                    DbmsUser u = (DbmsUser) auth.getPrincipal();
                    req.getSession().setAttribute("token", u.getJwt());
                    req.getSession().setAttribute("nodeURL", u.getNodeURL());
                    res.sendRedirect("/dashboard");
                })
                .failureUrl("/login?error")
                .and()
                .logout().logoutUrl("/logout").logoutSuccessUrl("/login");
        return http.build();
    }
}
```

- [ ] **Step 5:** `LoginController` — remove `loginPost` (Spring Security processes `POST /login`); keep the GET `/login` returning the `login` view (seed a `user` attribute as today, and show an error when `?error` is present). Delete `config/AuthInterceptor.java` and `config/WebConfig.java`.

- [ ] **Step 6:** Thread the admin JWT through add-user: `ConnectionRequest.createNewUser(User, String token)` sends `Authorization: Bearer <token>` to bootstrap `/createNewUser`; update `AdminOperations.createNewUser`, `DatabaseOperations.createNewUser`, `AdminService.addUser`, and `AdminController.addUser` to pass the session token through. (The bootstrap then mints its own internal JWT for the node call — Task 4 Step 4.)

- [ ] **Step 7: Write `NodeAuthenticationProviderTest`** — mock `ConnectionRequest` statically (or refactor the calls behind an injectable seam) so a fake node login returns a known JWT; assert the provider returns an authenticated token with the role's authorities, and that a thrown node error becomes `BadCredentialsException`. If static mocking is undesirable, extract the two `ConnectionRequest` calls into an injectable `NodeLoginClient` and mock that.

- [ ] **Step 8:** `cd DBMS && mvn -q -DskipTests package` (DBMS has no prior unit tests; run the new provider test with `mvn -Dtest=NodeAuthenticationProviderTest test`). Green + builds.

- [ ] **Step 9: Commit**

```bash
git add DBMS/pom.xml DBMS/src/main/resources/application.properties DBMS/src/main/java/com/database/atypon/DBMS/security/ DBMS/src/main/java/com/database/atypon/DBMS/controller/ DBMS/src/main/java/com/database/atypon/DBMS/database_system/connection/ DBMS/src/main/java/com/database/atypon/DBMS/service/ DBMS/src/test/java/
git rm DBMS/src/main/java/com/database/atypon/DBMS/config/AuthInterceptor.java DBMS/src/main/java/com/database/atypon/DBMS/config/WebConfig.java
git commit -m "feat(gateway): Spring Security form-login via custom node-backed provider; JWT in session"
```

---

### Task 6: Live validation across the cluster + regression sweep

- [ ] **Step 1:** Full Node suite + BootstrappingNode suite green under JDK 17.
- [ ] **Step 2:** Build all three jars; run Node (8080) → BootstrappingNode (8079) → DBMS (8078) with the **same** `JWT_SECRET` (the dev default is identical, so no env needed locally).
- [ ] **Step 3: Direct-API checks (curl):**
  - `POST /login` (mahmoud/admin) on the node → returns a JWT string (three dot-separated segments). Capture it.
  - Call `/admin/index/list?...` with `Authorization: Bearer <jwt>` → ok; with no header → 401/403; with a garbage token → 401/403.
  - Wrong password at `/login` → ERROR.
- [ ] **Step 4: Browser (gateway) check:** `http://localhost:8078/login` → sign in mahmoud/admin → redirected to `/dashboard`; create DB → schema → insert → read → update → delete → create index → query all succeed (the JWT is forwarded as Bearer). Sign in with a wrong password → login page shows the error. Hitting `/dashboard` while logged out → redirected to `/login`.
- [ ] **Step 5:** Commit any fixes found during live validation; update `RUN.md` if the run steps changed (they do not — same ports; `JWT_SECRET` optional locally).

```bash
git commit -am "test(m4a): live-validate BCrypt+JWT auth across the cluster"
```

---

## Notes for the executor

- Build everything under `JAVA_HOME=C:\Program Files\Java\jdk-17`.
- The M1/M2/M3 **controller unit tests** call controller methods directly and pass a token string argument; when Task 2 removes those parameters, update those tests (drop the token arg and the `auth`/`isXToken` stubbing). The engine/service/index tests are unaffected.
- `@SpringBootTest` MockMvc tests load the full context, which constructs `IndexManager`/`Network` etc.; they already do in the existing `NodeApplicationTests`, so context load is known-good.
- Keep the dev `app.jwt.secret` identical across modules so local (non-Docker) runs validate tokens across services with no env setup.
- The local cluster from earlier sessions is stopped; restart it for Task 6.
