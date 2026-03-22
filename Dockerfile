# Stage 1: build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY . .
RUN ./gradlew installDist --no-daemon

# Stage 2: runtime — JRE only, no build tools
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/build/install/pdf-library/ .

# Run as non-root
RUN useradd -r -u 1001 -g root pdflibrary
USER pdflibrary

EXPOSE 8080
ENTRYPOINT ["bin/pdf-library"]
