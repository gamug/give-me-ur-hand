plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "com.givemeurhand"
version = "0.1.0"

repositories { mavenCentral() }

val ktorVersion = "2.3.12"
val coroutinesVersion = "1.8.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.1.2")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

application {
    mainClass.set("com.givemeurhand.backend.ApplicationKt")
}

tasks.register<JavaExec>("ingestDocuments") {
    group = "application"
    description = "Extrae los PDFs de psicological-first-aid y los carga como chunks en MongoDB"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.givemeurhand.backend.ingest.IngestDocumentsKt")
    environment(System.getenv())
    if (project.hasProperty("sourceDir")) {
        args = listOf(project.property("sourceDir") as String)
    }
}

tasks.register<JavaExec>("seedProfessionals") {
    group = "application"
    description = "Crea cuentas de profesionales a partir de professionals-seed.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.givemeurhand.backend.professional.SeedProfessionalsKt")
    environment(System.getenv())
    if (project.hasProperty("seedFile")) {
        args = listOf(project.property("seedFile") as String)
    }
}

tasks.register<JavaExec>("extractAlarmCriteria") {
    group = "application"
    description = "Extrae criterios de alarma (PEIVD) desde knowledge_chunks vía DeepSeek y los guarda en alarm_criteria"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.givemeurhand.backend.alarm.ExtractAlarmCriteriaKt")
    environment(System.getenv())
}

tasks.test {
    useJUnitPlatform()
}
