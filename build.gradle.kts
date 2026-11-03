plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.seatmap"
version = "0.0.1-SNAPSHOT"
description = "seatmap backend API"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(27)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.apache.pdfbox:pdfbox:3.0.8")
	implementation("com.google.zxing:core:3.5.4")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-session-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// Reads the QR codes back out of generated tickets.
	testImplementation("com.google.zxing:javase:3.5.4")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Tests run against their own database (created like the app's, see README) unless told otherwise.
	if (System.getenv("SEATMAP_DB_URL") == null) {
		environment("SEATMAP_DB_URL", "jdbc:postgresql://localhost:5432/seatmap_test")
	}
	// Tests run the background jobs themselves. Left on, a job in one cached test context
	// would pick up work that another context's test is waiting for.
	environment("SEATMAP_TICKETS_MAIL_SEND_INTERVAL", "1h")
	environment("SEATMAP_HOLD_EXPIRY_INTERVAL", "1h")
	// A throwaway signing key, so tests never need the real one.
	environment("SEATMAP_TICKET_KEY", "test-only-ticket-key-0123456789abcdef")
}
