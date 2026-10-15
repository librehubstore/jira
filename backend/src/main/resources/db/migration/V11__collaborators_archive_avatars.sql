-- ADD_FUNCTIONALITIES: several collaborators per ticket, archived tickets, uploaded avatars.
create table ticket_collaborators (
    ticket_id uuid not null references tickets (id) on delete cascade,
    user_id   uuid not null references users (id) on delete cascade,
    primary key (ticket_id, user_id)
);
create index ticket_collaborators_user on ticket_collaborators (user_id);

-- Archived tickets leave the board and default searches, without being deleted.
alter table tickets add column archived boolean not null default false;

-- Avatar file name in the attachment storage (null = initials).
alter table users add column avatar_key varchar(64);
