# seatmap-api

Backend for the seatmap platform: stores seat-map layouts designed in the
[venue-designer](../venue-designer) Angular app, and will later proxy the
Claude API for the AI designer.

## Stack

- Java 27, Spring Boot 4.1, Gradle (Kotlin DSL, via the wrapper)
- PostgreSQL 18, schema managed by Flyway (`src/main/resources/db/migration`)

## Local setup

1. Install PostgreSQL and create the app database:

   ```sql
   CREATE ROLE seatmap LOGIN PASSWORD '<password>';
   CREATE DATABASE seatmap OWNER seatmap;
   ```

2. Create `config/application.properties` (gitignored):

   ```properties
   spring.datasource.password=<password>
   ```

   or set the `SEATMAP_DB_PASSWORD` environment variable instead.

3. Run:

   ```sh
   ./gradlew bootRun
   ```

   Health check: <http://localhost:8080/actuator/health>
