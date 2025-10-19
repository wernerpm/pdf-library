plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
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
    implementation("io.ktor:ktor-server-core:2.3.5")
    implementation("io.ktor:ktor-server-netty:2.3.5")
    implementation("io.ktor:ktor-server-html-builder:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")
    implementation("io.ktor:ktor-server-partial-content:2.3.5")

    // Kotlinx HTML for frontend DSL
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.9.1")

    // PDF processing
    implementation("org.apache.pdfbox:pdfbox:3.0.0")
    implementation("org.apache.pdfbox:pdfbox-tools:3.0.0")

    // Image processing for thumbnails
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // For hashing
    implementation("commons-codec:commons-codec:1.16.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // JSON handling
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // DateTime handling
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.ktor:ktor-server-tests-jvm:2.3.5")
    testImplementation("io.mockk:mockk:1.13.8")
}

application {
    mainClass.set("com.example.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }
}