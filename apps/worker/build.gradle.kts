plugins {
    id("com.nammamedmate.spring-app")
}

dependencies {
    implementation(project(":platform:kernel"))
    implementation(project(":platform:messaging"))
    implementation(project(":platform:observability"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("software.amazon.awssdk:sqs:2.31.20")
    implementation("org.crac:crac:1.5.0")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("med0001-worker.jar")
}
