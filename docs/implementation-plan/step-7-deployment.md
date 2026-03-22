# Step 7 — Cloud Deployment

> **Status**: NOT STARTED
> **Depends on**: Step 6 (Authentication) must be complete before exposing to the internet

## Goals

- Single-command deploy on any Linux VM (Hetzner, DigitalOcean, AWS Lightsail, etc.)
- Automatic HTTPS via Let's Encrypt — zero manual certificate management
- All secrets in environment variables, never in source control
- Hardened HTTP response headers
- Firewall exposes only port 443 externally
- NAS/SMB volumes can be mounted into the container

---

## Deployment Stack

```
Internet → 443 → Caddy (TLS termination, headers, rate limiting)
                     ↓ http://app:8080
               Ktor app (PDF library JAR)
                     ↓ bind mounts
               /data/metadata   (metadata + thumbnails + manifest)
               /mnt/nas         (NAS volume, optional)
```

---

## Files to Create

### `Dockerfile`

Multi-stage build — keeps the runtime image small (JRE only, no Gradle):

```dockerfile
# Stage 1: build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY . .
RUN ./gradlew installDist --no-daemon

# Stage 2: runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/build/install/pdf-library/ .

# Non-root user for security
RUN useradd -r -u 1001 -g root pdflibrary
USER pdflibrary

EXPOSE 8080
ENTRYPOINT ["bin/pdf-library"]
```

### `docker-compose.yml`

```yaml
services:
  app:
    build: .
    restart: unless-stopped
    environment:
      - JWT_SECRET=${JWT_SECRET}
      - APP_URL=${APP_URL}           # e.g. https://library.example.com
    volumes:
      - ./config/config.json:/app/config/config.json:ro
      - metadata_data:/data/metadata
      # Uncomment for NAS mount:
      # - /mnt/nas:/mnt/nas:ro
    expose:
      - "8080"

  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config

volumes:
  metadata_data:
  caddy_data:
  caddy_config:
```

### `Caddyfile`

```
{$APP_URL} {
    reverse_proxy app:8080

    # Rate-limit auth endpoints: 10 requests/minute per IP
    rate_limit /api/auth/* 10r/m

    # Security headers (supplements Ktor's headers)
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
        X-Frame-Options "DENY"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "strict-origin-when-cross-origin"
        Permissions-Policy "camera=(), microphone=(), geolocation=()"
        -Server
    }

    encode gzip
    log
}
```

> **Note**: Caddy automatically obtains and renews a Let's Encrypt TLS certificate for `{$APP_URL}`. The domain must resolve to the server's IP before first run.

### `.env.example`

```bash
# Copy to .env and fill in values. Never commit .env to source control.

# Secret key for signing JWTs. Generate with: openssl rand -hex 32
JWT_SECRET=

# Public URL of the app (used for WebAuthn RP ID and Caddy HTTPS)
APP_URL=https://library.example.com
```

---

## Ktor Security Headers

Add to `Main.kt` alongside `configureContentNegotiation()`:

```kotlin
fun Application.configureSecurityHeaders() {
    install(DefaultHeaders) {
        header(HttpHeaders.XFrameOptions, "DENY")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        // HSTS — only send over HTTPS (Caddy handles the redirect from HTTP)
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        // CSP: scripts and styles only from self, no inline scripts
        header("Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'")
    }
}
```

> **Note**: `'unsafe-inline'` is included for styles only because the current `index.html` uses some inline styles. It should be removed once those are extracted to `app.css`.

Add to `Main.kt`:

```kotlin
fun Application.configureCors(appUrl: String) {
    install(CORS) {
        allowHost(appUrl.removePrefix("https://").removePrefix("http://"))
        allowCredentials = true
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Post)
    }
}
```

---

## Configuration Changes

Move secrets out of `config.json` and into environment variables. `AppConfiguration` should read `JWT_SECRET` and `APP_URL` from env, with a clear startup error if missing:

```kotlin
val jwtSecret: String = System.getenv("JWT_SECRET")
    ?: error("JWT_SECRET env var is required")
val appUrl: String = System.getenv("APP_URL")
    ?: error("APP_URL env var is required")
```

---

## Firewall / VM Setup

On the VM (example for Ubuntu/ufw):

```bash
ufw default deny incoming
ufw allow ssh
ufw allow 80/tcp    # Caddy HTTP → redirect to HTTPS
ufw allow 443/tcp   # Caddy HTTPS
ufw enable
```

Ports 8080 (Ktor) is not exposed externally — only accessible from Caddy inside the Docker network.

---

## NAS / SMB Volume Mounting

If the PDFs live on a NAS, mount the share on the host and bind-mount it read-only into the container. Example `/etc/fstab` entry (credentials file method):

```
//nas.local/books  /mnt/nas  cifs  credentials=/etc/samba/nas.creds,ro,iocharset=utf8,uid=1001  0  0
```

Then in `docker-compose.yml` uncomment the `/mnt/nas` volume mount and update `config.json` scan paths accordingly.

---

## Deployment Steps (first time)

1. Provision a Linux VM, point your domain's DNS A record at its IP
2. Install Docker + Docker Compose plugin (`apt install docker.io docker-compose-plugin`)
3. Clone the repo, `cp .env.example .env`, fill in `JWT_SECRET` and `APP_URL`
4. `docker compose up -d`
5. Watch Caddy obtain the certificate: `docker compose logs -f caddy`
6. Open `https://your-domain/login` and register your first passkey

## Deployment Steps (updates)

```bash
git pull
docker compose build app
docker compose up -d --no-deps app
```

---

## Tests to Add

| File | Covers |
|------|--------|
| `MainSecurityHeadersTest.kt` | Assert HSTS, X-Frame-Options, X-Content-Type-Options present on responses |
| `MainCorsTest.kt` | Assert cross-origin requests blocked, same-origin allowed |
