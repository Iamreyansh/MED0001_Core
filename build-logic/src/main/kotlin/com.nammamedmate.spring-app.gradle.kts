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

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += output + compileClasspath
    }
}

configurations {
    named("integrationTestImplementation") { extendsFrom(configurations["testImplementation"]) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations["testRuntimeOnly"]) }
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests (Testcontainers)."
        group = "verification"
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(tasks.named("test"))
    }

tasks.named("check") {
    dependsOn(integrationTest)
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(integrationTest)
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(integrationTest)
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )
}
