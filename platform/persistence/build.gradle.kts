plugins {
    id("com.nammamedmate.spring-library")
}

dependencies {
    api(project(":platform:kernel"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
