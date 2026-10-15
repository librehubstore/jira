-- V1.6.1: components and versions (spec §4.13).
create table components (
    id          uuid primary key,
    space_id    uuid        not null references spaces (id) on delete cascade,
    name        varchar(64) not null,
    description text,
    lead_id     uuid references users (id) on delete set null,
    archived    boolean     not null default false
);
create unique index components_name on components (space_id, lower(name));

create table ticket_components (
    ticket_id    uuid not null references tickets (id) on delete cascade,
    component_id uuid not null references components (id) on delete cascade,
    primary key (ticket_id, component_id)
);

create table versions (
    id           uuid primary key,
    space_id     uuid        not null references spaces (id) on delete cascade,
    name         varchar(64) not null,
    description  text,
    start_date   date,
    release_date date,
    status       varchar(12) not null default 'UNRELEASED' check (status in ('UNRELEASED', 'RELEASED', 'ARCHIVED')),
    created_at   timestamptz not null default now()
);
create unique index versions_name on versions (space_id, lower(name));

create table ticket_fix_versions (
    ticket_id  uuid not null references tickets (id) on delete cascade,
    version_id uuid not null references versions (id) on delete cascade,
    primary key (ticket_id, version_id)
);
create index ticket_fix_versions_version on ticket_fix_versions (version_id);
