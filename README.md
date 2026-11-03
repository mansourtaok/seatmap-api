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

## Accounts and access

- **Staff** log in with email and password (`POST /api/auth/login`). The session lives in
  Postgres (Spring Session) and the browser gets an HttpOnly `SESSION` cookie.
  Passwords are bcrypt hashes. Ten failed logins in a row lock the account for 15 minutes.
- **CSRF**: every response sets a readable `XSRF-TOKEN` cookie. Changes must send it back
  in an `X-XSRF-TOKEN` header, which Angular's `HttpClient` does by itself. Serve the web app
  and `/api` from the same origin; in development, use the Angular dev-server proxy.
- **Organizations** own layouts and events. Members have a role: `VIEWER` (read),
  `EDITOR` (edit, publish, run events, confirm at the box office) or `ADMIN` (also members
  and deleting layouts). Another organization's items answer `404`. An organization always
  keeps at least one admin.
- **Buyers** need no account. They see events that are on sale (`/api/public/...`), hold
  and release seats, and use the reservation id as their key. A guest (by IP address) can
  make 10 holds a minute and hold 20 places per event at once (`seatmap.holds-per-minute`,
  `seatmap.max-held-per-guest`). Staff aren't limited. Only staff can confirm, until
  payments do it themselves.
- Behind a reverse proxy, set `server.forward-headers-strategy` so the guest limits see
  the client's address. Set `SEATMAP_SECURE_COOKIES=true` when serving over HTTPS.

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
| `POST` | `/api/auth/register` | Sign up: `email`, `password` (10+ characters), `displayName`, `organizationName`; logs in |
| `POST` | `/api/auth/login` | `email`, `password`; starts a session |
| `POST` | `/api/auth/logout` | Ends the session |
| `GET` | `/api/auth/me` | The logged-in user and their organizations |
| `GET` / `POST` | `/api/organizations` | Your organizations / create another |
| `GET` / `POST` | `/api/organizations/{id}/members` | List / add an existing user by `email` and `role` (admin) |
| `PUT` / `DELETE` | `/api/organizations/{id}/members/{userId}` | Change role / remove (admin; anyone can leave) |
| `GET` | `/api/public/events` | Events on sale (no login) |
| `GET` | `/api/public/events/{id}` | An on-sale event with its prices and layout document |
| `GET` | `/api/public/events/{id}/assets/{assetId}` | Its floor-plan image |
| `POST` | `/api/layouts` | Create from a designer document (`?organizationId=` if you're in several) |
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
