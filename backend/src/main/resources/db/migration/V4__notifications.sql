-- the outbox. a row is written in the same transaction as the change it reports,
-- then a sender delivers it. dedup_key is unique, so a change can never produce two rows for one user.
create table notifications (
    id                  bigserial primary key,
    user_id             bigint not null references users (id) on delete cascade,
    kind                text not null check (kind in ('change', 'digest')),
    change_event_id     bigint references change_events (id),
    dedup_key           text not null unique,
    subject             text not null,
    body                text not null,
    status              text not null default 'pending'
        check (status in ('pending', 'sending', 'sent', 'delivery_unknown', 'failed')),
    attempts            int not null default 0,
    next_attempt_at     timestamptz not null default now(),
    lease_until         timestamptz,
    locked_by           text,
    first_attempt_at    timestamptz,
    last_attempt_at     timestamptz,
    sent_at             timestamptz,
    provider_message_id text,
    last_error          text,
    created_at          timestamptz not null default now()
);
create index notifications_due on notifications (next_attempt_at) where status in ('pending', 'sending');
create index notifications_user on notifications (user_id, id desc);
