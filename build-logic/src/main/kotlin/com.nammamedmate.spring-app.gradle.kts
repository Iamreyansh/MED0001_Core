plugins {
    id("com.nammamedmate.spring-library")
    id("org.springframework.boot")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("plain")
}
