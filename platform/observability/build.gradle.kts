plugins {
    id("com.nammamedmate.spring-library")
}

dependencies {
    api(project(":platform:kernel"))
    api("org.springframework:spring-web")
    api("org.springframework:spring-context")
    api("jakarta.servlet:jakarta.servlet-api")
    api("io.micrometer:micrometer-core")
    api("org.slf4j:slf4j-api")
}
