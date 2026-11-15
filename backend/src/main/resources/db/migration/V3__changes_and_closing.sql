alter table postings
    add column status        text not null default 'open' check (status in ('open', 'unreachable', 'closed')),
    add column miss_count    int not null default 0,
    add column first_miss_at timestamptz,
    add column closed_at     timestamptz;

-- something a user should hear about. dedup_key makes a retried job unable to record the same change twice.
create table change_events (
    id              bigserial primary key,
    posting_id      bigint not null references postings (id),
    kind            text not null check (kind in ('rules_changed', 'closed', 'reopened')),
    from_version_id bigint references posting_versions (id),
    to_version_id   bigint references posting_versions (id),
    summary         jsonb not null,
    dedup_key       text not null unique,
    created_at      timestamptz not null default now()
);
create index change_events_posting on change_events (posting_id, id);
