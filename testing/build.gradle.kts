plugins {
    id("com.nammamedmate.spring-library")
}

dependencies {
    api(platform("org.testcontainers:testcontainers-bom:1.20.6"))
    api("org.testcontainers:junit-jupiter")
    api("org.testcontainers:postgresql")
    api("org.testcontainers:localstack")
    api("org.junit.jupiter:junit-jupiter")
}
