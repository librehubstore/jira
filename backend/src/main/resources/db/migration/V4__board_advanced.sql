-- V1.3: multi-space boards, backlog, card colours, working days, blocking WIP (spec §4.10).
create table board_spaces (
    board_id uuid not null references boards (id) on delete cascade,
    space_id uuid not null references spaces (id) on delete cascade,
    primary key (board_id, space_id)
);
create index board_spaces_space_idx on board_spaces (space_id);

alter table boards
    add column working_days       jsonb       not null default '[1,2,3,4,5]',
    add column backlog_enabled    boolean     not null default false,
    add column card_color_mode    varchar(16) not null default 'NONE'
        check (card_color_mode in ('NONE', 'TYPE', 'PRIORITY', 'ASSIGNEE', 'QUERIES')),
    add column card_color_queries jsonb       not null default '[]',
    add column wip_blocking       boolean     not null default false;

-- "Days in column": last status change per ticket.
create index history_entries_status_idx on history_entries (ticket_id, at) where field = 'status';
