create table boards (
    id    bigserial primary key,
    ats   text not null check (ats in ('greenhouse', 'lever')),
    token text not null,
    unique (ats, token)
);

-- one row per job post on a board. (board, external_id) is the stable identity.
create table postings (
    id                 bigserial primary key,
    board_id           bigint not null references boards (id),
    external_id        text not null,
    internal_job_id    text,
    title              text not null,
    url                text,
    first_published_at timestamptz,
    source_updated_at  timestamptz,
    first_seen_at      timestamptz not null default now(),
    last_seen_at       timestamptz not null default now(),
    last_detail_at     timestamptz,
    unique (board_id, external_id)
);

-- a new row only when the content hash differs from the latest version of that posting
create table posting_versions (
    id           bigserial primary key,
    posting_id   bigint not null references postings (id),
    fetched_at   timestamptz not null default now(),
    content_hash text not null,
    title        text not null,
    location     text,
    body_text    text not null,
    form         jsonb,
    raw          jsonb not null
);
create index posting_versions_latest on posting_versions (posting_id, id desc);

-- every outgoing request, so request rates and failures can be checked later
create table fetch_log (
    id          bigserial primary key,
    url         text not null,
    status      int,
    error       text,
    started_at  timestamptz not null,
    duration_ms int not null
);

create table board_polls (
    id         bigserial primary key,
    board_id   bigint not null references boards (id),
    polled_at  timestamptz not null default now(),
    job_count  int not null,
    kept_count int not null
);
