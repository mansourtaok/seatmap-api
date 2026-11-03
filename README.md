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

- **event**: sells one published version, with its own currency and status
  (`DRAFT`, `ON_SALE`, `CLOSED`, `CANCELLED`).
- **event_price**: the event's price per category. It starts at the version's defaults.
- **reservation** / **reservation_item**: a basket, held for 10 minutes
  (`seatmap.hold-duration`), then confirmed (sold) or released. Items keep the price
  they were held at.
- **event_seat**: one row per taken seat, so a seat can't be held or sold twice. An
  expired hold is free, and the next hold takes it over. A job releases expired holds
  every 30 seconds.

Seats the designer marked `held` or `blocked`, and anything without a ticket category,
are never sold. A hold is all or nothing.

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

| `POST` | `/api/events` | Create (`layoutId`, `version`, `name`, `startsAt`, `endsAt`, `currency`) |
| `GET` | `/api/events` | List events |
| `GET` | `/api/events/{id}` | Event and prices (ETag = revision) |
| `PUT` | `/api/events/{id}` | Name, dates, currency, status; `If-Match` required |
| `PUT` | `/api/events/{id}/prices` | `{"prices": {"<categoryId>": 25}}` |
| `GET` | `/api/events/{id}/availability` | Capacity, sold, held, available per category and area |
| `GET` | `/api/events/{id}/seat-statuses` | `{"<seatId>": "sold" \| "held" \| "blocked"}`, the designer's live statuses |
| `POST` | `/api/events/{id}/reservations` | Hold `{"items": [{"unitId": "...", "quantity": 1}]}`; `409` lists what's taken |
| `GET` | `/api/reservations/{id}` | A reservation |
| `POST` | `/api/reservations/{id}/confirm` | Sell what it holds (stands in for payment for now) |
| `DELETE` | `/api/reservations/{id}` | Release a hold |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details; an invalid
document's `400` lists what's wrong in `problems`.
