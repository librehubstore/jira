-- Fluxo MVP schema (spec §6). V1/V2 tables come in later migrations.

create table users (
    id                   uuid primary key,
    username             varchar(64)  not null unique,
    display_name         varchar(128) not null,
    email                varchar(255),
    password_hash        varchar(255) not null,
    is_admin             boolean      not null default false,
    active               boolean      not null default true,
    must_change_password boolean      not null default false,
    preferences          jsonb        not null default '{}',
    failed_logins        int          not null default 0,
    first_failed_at      timestamptz,
    locked_until         timestamptz,
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now()
);

create table groups (
    id   uuid primary key,
    name varchar(128) not null unique
);

create table group_members (
    group_id uuid not null references groups (id) on delete cascade,
    user_id  uuid not null references users (id) on delete cascade,
    primary key (group_id, user_id)
);

-- Global reference data
create table ticket_types (
    id              uuid primary key,
    name            varchar(64) not null unique,
    icon            varchar(32) not null,
    color           varchar(16) not null,
    hierarchy_level smallint    not null check (hierarchy_level in (-1, 0, 1))
);

create table priorities (
    id         uuid primary key,
    name       varchar(64) not null unique,
    rank       int         not null unique, -- lower = more urgent
    color      varchar(16) not null,
    is_default boolean     not null default false
);

create table resolutions (
    id         uuid primary key,
    name       varchar(64) not null unique,
    is_default boolean     not null default false
);

create table link_types (
    id       uuid primary key,
    name     varchar(64) not null unique,
    outward  varchar(64) not null,
    inward   varchar(64) not null
);

create table workflows (
    id     uuid primary key,
    name   varchar(128) not null,
    shared boolean      not null default false
);

create table statuses (
    id          uuid primary key,
    workflow_id uuid         not null references workflows (id) on delete cascade,
    name        varchar(64)  not null,
    category    varchar(16)  not null check (category in ('TODO', 'IN_PROGRESS', 'DONE')),
    position    int          not null,
    unique (workflow_id, name)
);

-- Spaces
create table spaces (
    id          uuid primary key,
    key         varchar(10)  not null unique,
    name        varchar(128) not null,
    description text,
    lead_id     uuid references users (id),
    workflow_id uuid         not null references workflows (id),
    archived    boolean      not null default false,
    next_number int          not null default 1,
    settings    jsonb        not null default '{}',
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

create table space_key_aliases (
    old_key  varchar(10) primary key,
    space_id uuid not null references spaces (id) on delete cascade
);

create table space_roles (
    id          uuid primary key,
    space_id    uuid         not null references spaces (id) on delete cascade,
    name        varchar(64)  not null,
    permissions text[]       not null,
    unique (space_id, name)
);

create table space_memberships (
    id       uuid primary key,
    space_id uuid not null references spaces (id) on delete cascade,
    user_id  uuid references users (id) on delete cascade,
    group_id uuid references groups (id) on delete cascade,
    role_id  uuid not null references space_roles (id) on delete cascade,
    check ((user_id is null) <> (group_id is null))
);
create unique index space_memberships_user on space_memberships (space_id, user_id) where user_id is not null;
create unique index space_memberships_group on space_memberships (space_id, group_id) where group_id is not null;

create table space_ticket_types (
    space_id       uuid not null references spaces (id) on delete cascade,
    ticket_type_id uuid not null references ticket_types (id),
    primary key (space_id, ticket_type_id)
);

-- Default field values per space (and optionally per ticket type)
create table field_defaults (
    id             uuid primary key,
    space_id       uuid        not null references spaces (id) on delete cascade,
    ticket_type_id uuid references ticket_types (id) on delete cascade,
    field          varchar(64) not null,
    value          jsonb       not null
);
create unique index field_defaults_uniq on field_defaults (space_id, coalesce(ticket_type_id, '00000000-0000-0000-0000-000000000000'), field);

-- Tickets
create table tickets (
    id            uuid primary key,
    space_id      uuid         not null references spaces (id) on delete cascade,
    number        int          not null,
    type_id       uuid         not null references ticket_types (id),
    status_id     uuid         not null references statuses (id),
    resolution_id uuid references resolutions (id),
    priority_id   uuid         not null references priorities (id),
    summary       varchar(255) not null,
    description   text,
    assignee_id   uuid references users (id),
    reporter_id   uuid         not null references users (id),
    creator_id    uuid         not null references users (id),
    parent_id     uuid references tickets (id) on delete set null,
    due_date      date,
    start_date    date,
    estimate      numeric(10, 2),
    rank          varchar(128) not null,
    flagged       boolean      not null default false,
    resolved_at   timestamptz,
    version       int          not null default 0,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    search        tsvector generated always as (
        setweight(to_tsvector('simple', summary), 'A') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'B')) stored,
    unique (space_id, number)
);
create index tickets_parent on tickets (parent_id);
create index tickets_status on tickets (status_id);
create index tickets_assignee on tickets (assignee_id);
create index tickets_rank on tickets (space_id, rank);
create index tickets_search on tickets using gin (search);

create table labels (
    id       uuid primary key,
    space_id uuid        not null references spaces (id) on delete cascade,
    name     varchar(64) not null,
    color    varchar(16)
);
create unique index labels_name on labels (space_id, lower(name));

create table ticket_labels (
    ticket_id uuid not null references tickets (id) on delete cascade,
    label_id  uuid not null references labels (id) on delete cascade,
    primary key (ticket_id, label_id)
);

create table comments (
    id         uuid primary key,
    ticket_id  uuid        not null references tickets (id) on delete cascade,
    author_id  uuid        not null references users (id),
    body       text        not null,
    edited_at  timestamptz,
    created_at timestamptz not null default now(),
    search     tsvector generated always as (to_tsvector('simple', body)) stored
);
create index comments_ticket on comments (ticket_id, created_at);
create index comments_search on comments using gin (search);

create table reactions (
    comment_id uuid        not null references comments (id) on delete cascade,
    user_id    uuid        not null references users (id) on delete cascade,
    emoji      varchar(32) not null,
    primary key (comment_id, user_id, emoji)
);

create table attachments (
    id            uuid primary key,
    ticket_id     uuid         not null references tickets (id) on delete cascade,
    uploader_id   uuid         not null references users (id),
    filename      varchar(255) not null,
    mime          varchar(128) not null,
    size          bigint       not null,
    storage_key   varchar(255) not null,
    thumbnail_key varchar(255),
    created_at    timestamptz  not null default now()
);
create index attachments_ticket on attachments (ticket_id);

create table watchers (
    ticket_id uuid not null references tickets (id) on delete cascade,
    user_id   uuid not null references users (id) on delete cascade,
    primary key (ticket_id, user_id)
);

create table ticket_links (
    id           uuid primary key,
    source_id    uuid not null references tickets (id) on delete cascade,
    target_id    uuid not null references tickets (id) on delete cascade,
    link_type_id uuid not null references link_types (id),
    unique (source_id, target_id, link_type_id),
    check (source_id <> target_id)
);
create index ticket_links_target on ticket_links (target_id);

create table web_links (
    id        uuid primary key,
    ticket_id uuid          not null references tickets (id) on delete cascade,
    url       varchar(2048) not null,
    title     varchar(255)
);

create table history_entries (
    id         uuid primary key,
    ticket_id  uuid        not null references tickets (id) on delete cascade,
    actor_id   uuid        not null references users (id),
    at         timestamptz not null default now(),
    field      varchar(64) not null,
    old_value  text,
    new_value  text,
    old_display text,
    new_display text
);
create index history_ticket on history_entries (ticket_id, at);

-- Boards
create table boards (
    id             uuid primary key,
    space_id       uuid         not null references spaces (id) on delete cascade,
    name           varchar(128) not null,
    is_default     boolean      not null default false,
    hide_done_days int,                       -- null = never hide
    swimlane_mode  varchar(16)  not null default 'NONE'
        check (swimlane_mode in ('NONE', 'ASSIGNEE', 'PARENT', 'PRIORITY', 'TYPE')),
    wip_mode       varchar(24)  not null default 'COUNT'
        check (wip_mode in ('OFF', 'COUNT', 'COUNT_EXCL_SUBTASKS')),
    card_fields    jsonb        not null default '[]',
    show_subtasks  boolean      not null default true
);

create table board_columns (
    id       uuid primary key,
    board_id uuid        not null references boards (id) on delete cascade,
    name     varchar(64) not null,
    position int         not null,
    wip_min  int,
    wip_max  int
);

create table board_column_statuses (
    column_id uuid not null references board_columns (id) on delete cascade,
    status_id uuid not null references statuses (id) on delete cascade,
    primary key (column_id, status_id)
);

create table quick_filters (
    id       uuid primary key,
    board_id uuid         not null references boards (id) on delete cascade,
    name     varchar(64)  not null,
    criteria jsonb        not null,
    position int          not null
);

-- Notifications (in-app)
create table notifications (
    id         uuid primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    actor_id   uuid references users (id),
    ticket_id  uuid references tickets (id) on delete cascade,
    event      varchar(32) not null,
    payload    jsonb       not null default '{}',
    read_at    timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index notifications_user on notifications (user_id, created_at desc);

create table audit_log (
    id        uuid primary key,
    actor_id  uuid references users (id),
    action    varchar(64) not null,
    target    varchar(255),
    details   jsonb       not null default '{}',
    at        timestamptz not null default now()
);

-- Seed global reference data
insert into ticket_types (id, name, icon, color, hierarchy_level) values
    (gen_random_uuid(), 'Epic', 'epic', '#8b5cf6', 1),
    (gen_random_uuid(), 'Story', 'story', '#22c55e', 0),
    (gen_random_uuid(), 'Tâche', 'task', '#3b82f6', 0),
    (gen_random_uuid(), 'Bug', 'bug', '#ef4444', 0),
    (gen_random_uuid(), 'Sous-tâche', 'subtask', '#60a5fa', -1);

insert into priorities (id, name, rank, color, is_default) values
    (gen_random_uuid(), 'Bloquante', 1, '#b91c1c', false),
    (gen_random_uuid(), 'Haute', 2, '#f97316', false),
    (gen_random_uuid(), 'Moyenne', 3, '#eab308', true),
    (gen_random_uuid(), 'Basse', 4, '#3b82f6', false),
    (gen_random_uuid(), 'Mineure', 5, '#94a3b8', false);

insert into resolutions (id, name, is_default) values
    (gen_random_uuid(), 'Fait', true),
    (gen_random_uuid(), 'Ne sera pas fait', false),
    (gen_random_uuid(), 'Doublon', false),
    (gen_random_uuid(), 'Impossible à reproduire', false);

insert into link_types (id, name, outward, inward) values
    (gen_random_uuid(), 'Blocks', 'bloque', 'est bloqué par'),
    (gen_random_uuid(), 'Duplicate', 'duplique', 'est dupliqué par'),
    (gen_random_uuid(), 'Cloners', 'clone', 'est cloné par'),
    (gen_random_uuid(), 'Relates', 'est lié à', 'est lié à'),
    (gen_random_uuid(), 'Causes', 'cause', 'est causé par'),
    (gen_random_uuid(), 'Implements', 'implémente', 'est implémenté par');
