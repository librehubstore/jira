-- V1.4 workflow editor (spec §4.9). A workflow without transitions stays "simplified": any status to any status.
create table transitions (
    id              uuid primary key,
    workflow_id     uuid         not null references workflows (id) on delete cascade,
    name            varchar(128) not null,
    from_status_ids jsonb        not null default '[]', -- empty = global transition (from any status)
    to_status_id    uuid         not null references statuses (id) on delete cascade,
    position        int          not null default 0,
    rules           jsonb        not null default '{}'
);
create index transitions_workflow_idx on transitions (workflow_id);

-- Workflow per ticket type; types not listed use the space's workflow.
create table space_type_workflows (
    space_id    uuid not null references spaces (id) on delete cascade,
    type_id     uuid not null references ticket_types (id) on delete cascade,
    workflow_id uuid not null references workflows (id),
    primary key (space_id, type_id)
);
