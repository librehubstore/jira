-- V1.8: personal access tokens (spec §8) and signed webhooks with a delivery log.
create table api_tokens (
    id           uuid primary key,
    user_id      uuid         not null references users (id) on delete cascade,
    name         varchar(64)  not null,
    token_hash   char(64)     not null unique, -- SHA-256 hex; the token itself is shown once
    prefix       varchar(12)  not null,
    scope        varchar(8)   not null check (scope in ('READ', 'WRITE')),
    expires_at   timestamptz,
    last_used_at timestamptz,
    revoked      boolean      not null default false,
    created_at   timestamptz  not null default now()
);
create index api_tokens_user on api_tokens (user_id);

create table webhooks (
    id         uuid primary key,
    url        varchar(2000) not null,
    secret     varchar(128)  not null,
    events     jsonb         not null default '[]', -- empty = every event
    space_id   uuid references spaces (id) on delete cascade,
    active     boolean       not null default true,
    created_at timestamptz   not null default now()
);

create table webhook_deliveries (
    id          uuid primary key,
    webhook_id  uuid         not null references webhooks (id) on delete cascade,
    event       varchar(32)  not null,
    status_code int,
    error       varchar(500),
    attempts    int          not null,
    duration_ms bigint,
    created_at  timestamptz  not null default now()
);
create index webhook_deliveries_hook on webhook_deliveries (webhook_id, created_at desc);
