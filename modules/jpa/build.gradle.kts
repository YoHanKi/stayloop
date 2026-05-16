plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
    `java-test-fixtures`
}

dependencies {
    // jpa
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    // querydsl
    api("com.querydsl:querydsl-jpa::jakarta")
    kapt("com.querydsl:querydsl-apt::jakarta")
    // jdbc-mysql
    runtimeOnly("com.mysql:mysql-connector-j")
    // flyway — schema SSOT 를 JPA `ddl-auto` 에서 마이그레이션 파일로 이관 (week5 PR0 0-1)
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-mysql")

    testImplementation("org.testcontainers:mysql")

    testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testFixturesImplementation("org.testcontainers:mysql")
}
