-- Old ticket keys after a move to another space, so that OLD-12 keeps resolving (spec §4.3).
create table ticket_key_aliases (
    old_key   varchar(32) primary key,
    ticket_id uuid not null references tickets (id) on delete cascade
);
