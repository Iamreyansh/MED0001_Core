plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

group = "com.nammamedmate"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

val jacocoExcludes =
    listOf(
        "**/package-info.class",
        "**/*Application.class",
        "**/*Config.class",
        "**/*Config$*.class",
        "**/*Configuration.class",
        "**/*Priming.class",
    )

val ci = providers.environmentVariable("CI").map { it == "true" }.orElse(false)

tasks.jacocoTestReport {
    dependsOn(tasks.test, tasks.compileJava)
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )
    reports {
        xml.required.set(true)
        // HTML is for local browsing; skip on CI (verification uses XML only).
        html.required.set(ci.map { !it })
    }
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.map { dir ->
            fileTree(dir) { exclude(jacocoExcludes) }
        },
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport, tasks.compileJava)
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        },
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.map { dir ->
            fileTree(dir) { exclude(jacocoExcludes) }
        },
    )
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
    java {
        googleJavaFormat("1.25.2")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

spotbugs {
    ignoreFailures.set(false)
    effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
    excludeFilter.set(rootProject.file("config/spotbugs-exclude.xml"))
}

// Main sources only — spotbugsTest / spotbugsIntegrationTest roughly doubled CI SpotBugs time.
tasks.matching { it.name.startsWith("spotbugs") && it.name != "spotbugsMain" }.configureEach {
    enabled = false
}

dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
