plugins {
    id("com.nammamedmate.spring-app")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:messaging"))
    implementation(project(":platform:observability"))
    implementation(project(":domains:pharmacy"))
    implementation(project(":domains:notification"))
    implementation(project(":domains:customer"))
    implementation(project(":domains:marketing"))
    implementation(project(":domains:crm"))
    implementation(project(":domains:support"))
    implementation(project(":domains:automation"))
    implementation(project(":domains:observability-ops"))
    implementation(project(":domains:medicine-schedule"))
    implementation(project(":domains:inventory"))
    implementation(project(":domains:automation"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    runtimeOnly("org.postgresql:postgresql")
    implementation("software.amazon.awssdk:sqs:2.31.20")
    implementation("software.amazon.awssdk:s3:2.31.20")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-junit-jupiter")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("med0001-worker.jar")
}
