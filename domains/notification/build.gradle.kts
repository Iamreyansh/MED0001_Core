plugins {
    id("com.nammamedmate.spring-library")
}

dependencies {
    api(project(":platform:kernel"))
    api(project(":platform:security"))
    implementation(project(":platform:messaging"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.jsonwebtoken:jjwt-impl:0.12.6")
    implementation("io.jsonwebtoken:jjwt-jackson:0.12.6")
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta:2.2.29")
    testImplementation("org.springframework.security:spring-security-test")
}
