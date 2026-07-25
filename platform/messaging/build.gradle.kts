plugins {
    id("com.nammamedmate.spring-library")
}

dependencies {
    api(project(":platform:kernel"))
    api("org.springframework:spring-tx")
    api("org.springframework:spring-context")
    api("org.springframework:spring-jdbc")
    api("software.amazon.awssdk:sqs:2.31.20")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("org.mockito:mockito-core")
}
