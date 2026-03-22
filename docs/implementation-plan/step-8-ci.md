# Step 8 — CI Pipeline (GitHub Actions)

> **Status**: NOT STARTED
> **Depends on**: Steps 6 + 7 (auth and Dockerfile must exist before image publishing makes sense)

## Tool Decision

**GitHub Actions + GitHub Container Registry (GHCR).**

GitHub Actions free plan provides 2,000 Linux runner minutes/month for private repositories.
This pipeline costs ~5 minutes per push (Gradle ~2 min with cache, Docker build ~3 min with
layer cache). At one push per day that is ~150 minutes/month — 7.5% of the free quota.
GHCR is currently free for storage and bandwidth; GitHub commits to 30 days notice before
any pricing change. No additional accounts, credentials, or services needed beyond what the
project already uses.

---

## Pipeline Overview

Two jobs, both triggered on push to `main` and on pull requests to `main`:

```
push / PR
  └── test          always runs — compiles + all tests
        └── publish-image   runs only on push to main, after test passes
                            builds Docker image, pushes to GHCR
```

---

## File: `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: gradle          # caches ~/.gradle/caches + ~/.gradle/wrapper

      - name: Build and test
        run: ./gradlew build

      - name: Upload test report on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: build/reports/tests/
          retention-days: 7

  publish-image:
    needs: test
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write           # required to push to GHCR

    steps:
      - uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}   # built-in, no secret setup needed

      - name: Build and push image
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: |
            ghcr.io/${{ github.repository }}:latest
            ghcr.io/${{ github.repository }}:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

---

## Dockerfile cache mount (update step 7's Dockerfile)

The `publish-image` job builds Docker without the Gradle files pre-downloaded. Add a
BuildKit cache mount to the Gradle build step so dependency downloading is cached across
Docker builds:

```dockerfile
# Stage 1: build  (replace the plain RUN line)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew installDist --no-daemon
```

This mount is transparent — `docker build` outside CI works identically (BuildKit is the
default since Docker 23). The GHA cache for Docker layers (`cache-from: type=gha`) caches
the full layer stack; the Gradle mount caches the dependency jars independently.

---

## What the Two Tags Mean

| Tag | Use |
|-----|-----|
| `ghcr.io/<owner>/<repo>:latest` | Always points to the newest main build — used in `docker-compose.yml` on the server |
| `ghcr.io/<owner>/<repo>:<sha>` | Immutable reference to a specific commit — useful for rollbacks: `docker compose pull && docker compose up -d` after pinning the sha tag |

---

## Pulling the Image on the Server

On the server, `docker-compose.yml` (from step 7) references the image instead of building
locally:

```yaml
# Replace `build: .` with:
services:
  app:
    image: ghcr.io/<owner>/<repo>:latest
```

To authenticate the server to pull from GHCR (if the repository is private):

```bash
echo $GITHUB_PAT | docker login ghcr.io -u <github-username> --password-stdin
```

Use a fine-grained PAT with only `read:packages` scope. Store it as a one-time step on the
server; Docker caches the credentials.

---

## Estimated Monthly Cost

| Scenario | Minutes used | % of free 2,000 |
|----------|-------------|-----------------|
| 1 push/day | ~150 min | 7.5% |
| 3 pushes/day | ~450 min | 22.5% |
| 10 pushes/day | ~1,500 min | 75% |

A personal project will comfortably stay under the limit. If the free quota is ever
approached, splitting the `publish-image` job to only run on tagged releases (rather than
every `main` push) immediately cuts image-build usage by 80%+.
