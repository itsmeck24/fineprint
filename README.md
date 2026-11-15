# FinePrint

FinePrint tracks the eligibility rules in new-grad and intern job postings: graduation window, degree
level, return to school, and work authorization or sponsorship. It reads the posting title, the
description and the application form, and keeps every version so changes can be detected.

Work in progress. Right now it runs a collector that polls public Greenhouse and Lever job boards once
an hour and stores a new version of a posting whenever its content changes.

## Run

Needs Docker.

```
docker compose up -d --build
docker compose logs -f collector
```

Postgres is on `localhost:5434` (user, password and database are all `fineprint`).

## Test

Needs Java 21 and Docker (the database tests start Postgres with Testcontainers).

```
cd backend
./mvnw verify
```

## Data sources

- Greenhouse Job Board API: https://developers.greenhouse.io/job-board.html
- Lever Postings API: https://github.com/lever/postings-api

Requests go one at a time per host with at least 2 seconds between them, and every request is logged in
the `fetch_log` table.
