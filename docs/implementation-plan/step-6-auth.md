# Step 6 — Authentication: Passkeys + JWT

> **Status**: NOT STARTED

## Goals

- All library routes require authentication; unauthenticated requests get a login page
- Authentication uses **WebAuthn passkeys** — no passwords, phishing-resistant
- After a successful passkey assertion, the server issues a **short-lived JWT** (8 h)
- Authorisation is flat: any authenticated user has full access
- Credentials stored in `credentials.json` in `metadataStoragePath` — no external DB needed
- Supports single-digit users; no self-registration (admin registers devices via the UI)

---

## Libraries

Add to `build.gradle.kts`:

```kotlin
// WebAuthn server-side
implementation("com.yubico:webauthn-server-core:2.5.4")

// JWT
implementation("io.ktor:ktor-server-auth:3.4.0")
implementation("io.ktor:ktor-server-auth-jwt:3.4.0")
implementation("com.auth0:java-jwt:4.4.0")
```

---

## Configuration

Add to `AppConfiguration`:

```kotlin
val rpId: String          // Relying Party ID = your domain, e.g. "library.example.com"
val rpName: String        // Human-readable, e.g. "My PDF Library"
val jwtIssuer: String     // Same as rpId is fine
```

Add to `Main.kt` startup (fail fast if missing):

```kotlin
val jwtSecret = System.getenv("JWT_SECRET")
    ?: error("JWT_SECRET environment variable is required")
```

---

## Architecture

### Credential Storage — `AuthStore`

New class `auth/AuthStore.kt`:
- Loads/saves `credentials.json` via `FileSystemStorage` (atomic writes)
- Stores a list of `StoredCredential` (username, credential ID, public key, sign count, display name)
- Thread-safe with a `Mutex`
- Used by both the registration and authentication flows

```kotlin
@Serializable
data class StoredCredential(
    val username: String,
    val credentialId: String,       // Base64url
    val publicKeyCose: String,      // Base64url COSE-encoded public key
    val signCount: Long,
    val displayName: String,
    val createdAt: Instant
)
```

### Challenge Cache — `ChallengeCache`

New class `auth/ChallengeCache.kt`:
- In-memory `ConcurrentHashMap<String, ChallengeEntry>` (sessionId → challenge + expiry)
- Entries expire after 5 minutes
- Used to correlate the `start` and `finish` halves of each ceremony
- Sweep expired entries on each write (no background thread needed at this scale)

### AuthService — `auth/AuthService.kt`

Wraps `RelyingParty` (Yubico) + `AuthStore` + `ChallengeCache`:

```
registerStart(username, displayName) → PublicKeyCredentialCreationOptions (JSON)
registerFinish(sessionId, credential JSON) → stores credential, returns success
loginStart(username?) → PublicKeyCredentialRequestOptions (JSON)  ← username optional for discoverable credentials
loginFinish(sessionId, assertion JSON) → verifies, updates signCount, returns username
```

### JWT Middleware — Ktor `Authentication` plugin

```kotlin
install(Authentication) {
    jwt("auth-jwt") {
        realm = appConfig.rpName
        verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).withIssuer(jwtSecret).build())
        validate { credential ->
            if (credential.payload.getClaim("sub").asString() != null)
                JWTPrincipal(credential.payload) else null
        }
        challenge { _, _ ->
            call.respond(HttpStatusCode.Unauthorized, ApiResponse.error("Authentication required"))
        }
    }
}
```

Wrap all existing routes in `authenticate("auth-jwt") { ... }`. Auth endpoints and the login page are outside this block.

---

## API Endpoints

| Endpoint | Auth | Description |
|----------|------|-------------|
| `GET /login` | Public | Serves login HTML page |
| `POST /api/auth/register/start` | Public* | Begin passkey registration |
| `POST /api/auth/register/finish` | Public* | Complete passkey registration |
| `POST /api/auth/login/start` | Public | Begin passkey authentication |
| `POST /api/auth/login/finish` | Public | Complete passkey authentication → returns JWT |
| `POST /api/auth/logout` | JWT | Invalidates client-side token (client deletes cookie) |
| All existing `/api/*` | **JWT** | Protected |
| `/static/*` | **JWT** | Protected (login page served from `/login`, not `/static/`) |

\* Register endpoints should be further protected in practice — see Security Notes below.

---

## Frontend Changes

### Login Page (`login.html`)

New standalone page (not under `/static/`) with:
- "Sign in with passkey" button
- Calls `POST /api/auth/login/start` → passes options to `navigator.credentials.get()`
- Sends assertion to `POST /api/auth/login/finish` → receives JWT
- Stores JWT in `localStorage` (or `sessionStorage` for tab-scoped) and redirects to `/`

### app.js changes

Add JWT to all fetch calls:

```js
const token = localStorage.getItem('jwt');
fetch('/api/pdfs', { headers: { 'Authorization': `Bearer ${token}` } })
```

Handle 401 responses by redirecting to `/login`.

### Passkey Registration Flow (admin UI)

Add a small "Register new device" panel (visible only when already logged in):
- Calls `POST /api/auth/register/start` with a chosen username
- Passes options to `navigator.credentials.create()`
- Sends result to `POST /api/auth/register/finish`

---

## Security Notes

- **Register endpoint protection**: In the initial implementation, registration is only usable while no credentials exist (bootstrapping). Once at least one credential is stored, `registerStart` requires a valid JWT. This prevents strangers from adding themselves.
- **Sign count validation**: Yubico's library enforces sign count to detect cloned credentials.
- **Challenge expiry**: 5-minute window; `loginFinish` rejects replayed or expired challenges.
- **JWT HttpOnly cookie option**: Consider storing the JWT in an HttpOnly cookie instead of `localStorage` to protect against XSS. Trade-off: CSRF token then needed for POST requests.
- **JWT secret rotation**: Changing `JWT_SECRET` invalidates all existing sessions immediately. Document this in the `.env.example`.
- **No refresh tokens**: 8 h expiry is appropriate for personal use. User re-authenticates with passkey when it expires (fast, no password needed).

---

## Files to Create / Modify

| File | Change |
|------|--------|
| `auth/AuthStore.kt` | New — credential persistence |
| `auth/ChallengeCache.kt` | New — in-memory challenge store with expiry |
| `auth/AuthService.kt` | New — WebAuthn ceremony logic |
| `Main.kt` | Install JWT auth plugin, protect routes, add auth endpoints |
| `config/AppConfiguration.kt` | Add `rpId`, `rpName`, `jwtIssuer` fields |
| `src/main/resources/login.html` | New — standalone login page |
| `src/main/resources/static/app.js` | Add `Authorization` header to all fetch calls, handle 401 |
| `build.gradle.kts` | Add `webauthn-server-core`, `ktor-server-auth-jwt`, `java-jwt` |

---

## Tests to Add

| File | Covers |
|------|--------|
| `auth/AuthStoreTest.kt` | Save/load round-trip, concurrent access |
| `auth/ChallengeCacheTest.kt` | Expiry, used challenge rejected |
| `MainAuthTest.kt` | Protected routes return 401 without JWT, 200 with valid JWT |
