plugins {
    id("com.nammamedmate.spring-library")
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.slf4j:slf4j-api")
    api("jakarta.servlet:jakarta.servlet-api")
    api("org.springframework:spring-web")
    api("org.springframework:spring-context")
    api("software.amazon.awssdk:s3:2.31.20")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.mockito:mockito-core")
}
