-- V1.6.2: custom fields and per space / ticket type field configuration (spec §4.14).
create table custom_fields (
    id         uuid primary key,
    name       varchar(64) not null,
    type       varchar(16) not null check (type in ('TEXT', 'TEXTAREA', 'NUMBER', 'DATE', 'SELECT', 'MULTISELECT', 'USER', 'USERS', 'CHECKBOX', 'URL')),
    options    jsonb       not null default '[]',
    created_at timestamptz not null default now()
);
create unique index custom_fields_name on custom_fields (lower(name));

-- A custom field enabled in a space, for some ticket types (empty list = all types).
create table space_custom_fields (
    space_id uuid  not null references spaces (id) on delete cascade,
    field_id uuid  not null references custom_fields (id) on delete cascade,
    type_ids jsonb not null default '[]',
    primary key (space_id, field_id)
);

-- Visibility, requirement at creation, order and default (custom fields) per space and optionally per ticket type.
create table field_configs (
    id             uuid primary key,
    space_id       uuid        not null references spaces (id) on delete cascade,
    ticket_type_id uuid references ticket_types (id) on delete cascade,
    field          varchar(64) not null, -- API field name, or "cf:<uuid>"
    visible        boolean     not null default true,
    required       boolean     not null default false,
    position       int         not null default 0,
    default_value  jsonb
);
create unique index field_configs_uniq on field_configs (space_id, coalesce(ticket_type_id, '00000000-0000-0000-0000-000000000000'), field);

alter table tickets add column custom_values jsonb not null default '{}';
create index tickets_custom_values on tickets using gin (custom_values);
