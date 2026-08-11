plugins {
    id("com.nammamedmate.spring-app")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:messaging"))
    implementation(project(":platform:observability"))
    implementation(project(":domains:pharmacy"))
    implementation(project(":domains:notification"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation("software.amazon.awssdk:sqs:2.31.20")
    implementation("software.amazon.awssdk:s3:2.31.20")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-junit-jupiter")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("med0001-worker.jar")
}
