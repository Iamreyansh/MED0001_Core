plugins {
    id("com.diffplug.spotless") version "7.0.2" apply false
    id("com.github.spotbugs") version "6.1.7" apply false
    id("org.owasp.dependencycheck") version "12.1.1"
}

dependencyCheck {
    failBuildOnCVSS = 9.0f
    formats = listOf("HTML", "JSON")
    suppressionFile = "config/owasp-suppressions.xml"
}

tasks.register("aggregateJacocoReport") {
    group = "verification"
    description = "Placeholder for aggregated coverage; each module enforces 100% locally."
    dependsOn(subprojects.map { "${it.path}:jacocoTestCoverageVerification" })
}
