-- V1.1: saved FQL filters (spec §4.11) and FQL on boards (base filter, swimlanes by query).
create table saved_filters (
    id             uuid primary key,
    owner_id       uuid         not null references users (id) on delete cascade,
    name           varchar(128) not null,
    fql            text         not null,
    columns        jsonb        not null default '[]',
    share_scope    varchar(16)  not null default 'PRIVATE' check (share_scope in ('PRIVATE', 'GROUP', 'SPACE', 'ALL')),
    share_group_id uuid references groups (id) on delete set null,
    share_space_id uuid references spaces (id) on delete cascade,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);
create index saved_filters_owner on saved_filters (owner_id);

create table saved_filter_favorites (
    filter_id uuid not null references saved_filters (id) on delete cascade,
    user_id   uuid not null references users (id) on delete cascade,
    primary key (filter_id, user_id)
);

alter table boards add column base_fql text;
alter table boards add column swimlane_queries jsonb not null default '[]';
alter table boards drop constraint boards_swimlane_mode_check;
alter table boards add constraint boards_swimlane_mode_check
    check (swimlane_mode in ('NONE', 'ASSIGNEE', 'PARENT', 'PRIORITY', 'TYPE', 'QUERIES', 'REPORTER', 'LABEL'));
