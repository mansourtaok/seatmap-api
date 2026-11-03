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

   For tests, also create `seatmap_test` owned by the same role; `./gradlew test` uses it.

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

## Model

- **layout**: a seat map and its editable draft (the designer document, `jsonb`).
  The draft's revision is its ETag; saves must send it in `If-Match`, so a stale save
  gets `409` instead of overwriting newer work.
- **layout_version**: publishing copies the draft into version 1, 2, 3, and so on.
  Versions never change, so events (later) can sell against one safely.
- **inventory_unit**: what a version sells, extracted when it's published: every seat,
  standing areas and sections sold by capacity. Keyed by the designer's object ids.
- **ticket_category**: a version's categories, with the layout's default prices.
- **asset**: floor-plan images. They're write-once, because published versions show them.

The server accepts schema version 3 documents and checks their integrity (unique ids,
seats and their owners pointing at each other, existing categories and assets).
Geometry rules stay in the designer.

## API

| Method | Path | |
|---|---|---|
| `POST` | `/api/layouts` | Create from a designer document; returns it with its new id |
| `GET` | `/api/layouts` | List layouts |
| `GET` | `/api/layouts/{id}` | Draft document (ETag = revision) |
| `PUT` | `/api/layouts/{id}/draft` | Save the draft; `If-Match` required |
| `DELETE` | `/api/layouts/{id}` | Delete; refused once a version is published |
| `POST` | `/api/layouts/{id}/versions` | Publish the draft; optional `If-Match` |
| `GET` | `/api/layouts/{id}/versions` | List versions |
| `GET` | `/api/layouts/{id}/versions/{n}` | A version's document |
| `GET` | `/api/layouts/{id}/versions/{n}/inventory` | Its categories and units |
| `PUT` | `/api/layouts/{id}/assets/{assetId}` | Store an image (PNG, JPEG, WebP, GIF) |
| `GET` | `/api/layouts/{id}/assets/{assetId}` | Fetch an image |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details; an invalid
document's `400` lists what's wrong in `problems`.
