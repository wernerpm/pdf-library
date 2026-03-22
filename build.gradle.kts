plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin standard library
    implementation(kotlin("stdlib"))

    // Embedded web server (Ktor)
    implementation("io.ktor:ktor-server-core:3.4.0")
    implementation("io.ktor:ktor-server-netty:3.4.0")
    implementation("io.ktor:ktor-server-html-builder:3.4.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.0")
    implementation("io.ktor:ktor-server-partial-content:3.4.0")

    // Kotlinx HTML for frontend DSL
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.12.0")

    // PDF processing
    implementation("org.apache.pdfbox:pdfbox:3.0.6")
    implementation("org.apache.pdfbox:pdfbox-tools:3.0.6")

    // Image processing for thumbnails
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // For hashing
    implementation("commons-codec:commons-codec:1.19.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.31")

    // JSON handling
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // DateTime handling
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    // WebAuthn server-side
    implementation("com.yubico:webauthn-server-core:2.5.4")

    // JWT auth
    implementation("io.ktor:ktor-server-auth:3.4.0")
    implementation("io.ktor:ktor-server-auth-jwt:3.4.0")
    implementation("com.auth0:java-jwt:4.4.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-server-test-host:3.4.0")
    testImplementation("io.mockk:mockk:1.14.9")
}

application {
    mainClass.set("com.example.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Xmx4g")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}
