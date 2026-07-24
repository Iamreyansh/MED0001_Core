plugins {
    id("com.nammamedmate.spring-app")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:security"))
    implementation(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation(project(":platform:observability"))
    implementation(project(":domains:auth"))
    implementation(project(":domains:customer"))
    implementation(project(":domains:pharmacy"))
    implementation(project(":domains:catalogue"))
    implementation(project(":domains:inventory"))
    implementation(project(":domains:pos"))
    implementation(project(":domains:prescription"))
    implementation(project(":domains:teleconsult"))
    implementation(project(":domains:order"))
    implementation(project(":domains:rider"))
    implementation(project(":domains:payment"))
    implementation(project(":domains:marketing"))
    implementation(project(":domains:crm"))
    implementation(project(":domains:support"))
    implementation(project(":domains:analytics"))
    implementation(project(":domains:notification"))
    implementation(project(":domains:medicine-schedule"))
    implementation(project(":domains:automation"))
    implementation(project(":domains:observability-ops"))
    implementation(project(":domains:settings"))
    implementation(project(":domains:integration"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("org.crac:crac:1.5.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    implementation("software.amazon.awssdk:s3:2.31.20")
    implementation("software.amazon.awssdk:sqs:2.31.20")
    implementation("software.amazon.awssdk:secretsmanager:2.31.20")

    testImplementation(project(":testing"))
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    testRuntimeOnly("com.h2database:h2")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("med0001-api.jar")
}

tasks.processResources {
    from(rootProject.file("db/migration")) {
        into("db/migration")
    }
}
