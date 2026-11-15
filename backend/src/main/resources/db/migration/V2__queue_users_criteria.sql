-- work items for the worker. claimed with for update skip locked and a lease.
create table jobs (
    id           bigserial primary key,
    type         text not null,
    payload      jsonb not null,
    status       text not null default 'ready' check (status in ('ready', 'running', 'done', 'dead')),
    run_at       timestamptz not null default now(),
    attempts     int not null default 0,
    max_attempts int not null default 8,
    locked_by    text,
    lease_until  timestamptz,
    last_error   text,
    dedup_key    text,
    created_at   timestamptz not null default now(),
    finished_at  timestamptz
);
create index jobs_ready on jobs (run_at) where status in ('ready', 'running');
-- the same piece of work cannot be queued twice while it is still pending
create unique index jobs_pending_dedup on jobs (dedup_key) where status in ('ready', 'running');

-- one row per remote host, shared by every worker
create table host_limits (
    host            text primary key,
    next_allowed_at timestamptz not null default now()
);

create table users (
    id            bigserial primary key,
    email         text not null unique,
    password_hash text not null,
    grad_month    date not null,
    degree_level  text not null check (degree_level in ('bachelor', 'master', 'phd')),
    created_at    timestamptz not null default now()
);

create table saved_postings (
    user_id    bigint not null references users (id) on delete cascade,
    posting_id bigint not null references postings (id),
    saved_at   timestamptz not null default now(),
    primary key (user_id, posting_id)
);

-- what the rules found in one version. range_start and range_end are months for graduation windows.
create table criteria (
    id          bigserial primary key,
    version_id  bigint not null references posting_versions (id),
    kind        text not null check (kind in ('grad_window', 'degree', 'return_to_school', 'work_auth')),
    source      text not null check (source in ('title', 'text', 'form')),
    quote       text not null,
    range_start date,
    range_end   date,
    value       text,
    extractor   text not null
);
create index criteria_version on criteria (version_id);

alter table posting_versions add column extracted_at timestamptz;

-- versions collected before extraction existed still need their rules
insert into jobs (type, payload, dedup_key)
select 'extract', json_build_object('versionId', id), 'extract:' || id from posting_versions;
