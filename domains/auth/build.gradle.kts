plugins {
    id("com.nammamedmate.spring-library")
}

dependencies {
    api(project(":platform:kernel"))
    api(project(":platform:security"))
    api(project(":platform:persistence"))
    implementation(project(":platform:messaging"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.security:spring-security-crypto")
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta:2.2.29")
    testImplementation("org.springframework.security:spring-security-test")
}
