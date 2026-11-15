create table watches (
    user_id    bigint not null references users (id) on delete cascade,
    board_id   bigint not null references boards (id),
    created_at timestamptz not null default now(),
    primary key (user_id, board_id)
);

-- a new posting on a watched board that the user is not ruled out of. notification_id is set when it goes out
-- in a digest, in the same transaction that writes the digest notification.
create table digest_items (
    user_id         bigint not null references users (id) on delete cascade,
    posting_id      bigint not null references postings (id),
    created_at      timestamptz not null default now(),
    notification_id bigint references notifications (id) on delete set null,
    primary key (user_id, posting_id)
);
create index digest_items_pending on digest_items (user_id) where notification_id is null;
