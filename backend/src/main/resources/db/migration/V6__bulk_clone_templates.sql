-- V1.5: background bulk changes (spec §4.12) and ticket templates (spec §4.3).
create table bulk_jobs (
    id          uuid primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    operation   varchar(16) not null check (operation in ('EDIT', 'TRANSITION', 'MOVE', 'DELETE', 'WATCH', 'UNWATCH')),
    params      jsonb       not null default '{}',
    total       int         not null,
    done        int         not null default 0,
    failed      int         not null default 0,
    report      jsonb       not null default '[]', -- [{key, reason}] of the failures
    status      varchar(8)  not null default 'RUNNING' check (status in ('RUNNING', 'DONE')),
    created_at  timestamptz not null default now(),
    finished_at timestamptz
);
create index bulk_jobs_user_idx on bulk_jobs (user_id, created_at desc);

create table ticket_templates (
    id         uuid primary key,
    space_id   uuid         not null references spaces (id) on delete cascade,
    name       varchar(128) not null,
    type_id    uuid references ticket_types (id) on delete set null,
    field_values jsonb      not null default '{}', -- same field names as the ticket API
    subtasks   jsonb        not null default '[]', -- summaries of the subtasks to create
    created_at timestamptz  not null default now(),
    unique (space_id, name)
);
