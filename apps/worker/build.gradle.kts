plugins {
    id("com.nammamedmate.spring-app")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:messaging"))
    implementation(project(":platform:observability"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("software.amazon.awssdk:sqs:2.31.20")
    implementation("org.crac:crac:1.5.0")
    // AWS Lambda runtime + SQS event types for the worker handler (shaded uber jar).
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    implementation("com.amazonaws:aws-lambda-java-events:3.11.5")
}

// Worker ships as a shaded uber jar: AWS Lambda's classloader cannot read Spring
// Boot's nested BOOT-INF/lib/* layout, so we flatten classes to the jar root.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("med0001-worker.jar")
    archiveClassifier.set("")
    // Merge Java service files (e.g. META-INF/services/*).
    mergeServiceFiles()
    // Spring Boot 3.x auto-configuration imports — must be concatenated, not overwritten.
    append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    // Drop jar signatures that become invalid after merging.
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
