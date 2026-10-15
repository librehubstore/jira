-- V1.7: comments restricted to a space role (spec §4.4) and email notifications grouped per ticket (spec §9).
alter table comments add column visibility_role_id uuid references space_roles (id) on delete set null;

create table email_outbox (
    id         uuid primary key,
    user_id    uuid         not null references users (id) on delete cascade,
    ticket_id  uuid,
    ticket_key varchar(32)  not null,
    summary    varchar(255) not null,
    line       text         not null,
    created_at timestamptz  not null default now(),
    sent_at    timestamptz
);
create index email_outbox_pending on email_outbox (created_at) where sent_at is null;
