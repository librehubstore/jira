"""Performance check against spec §10 targets (50 000 tickets).

Usage (from the repo root, stack running, dev compose db):
    python scripts/perf.py --url http://localhost:8080 --user admin --password admin-password

Seeds a space PERF with 50 000 tickets through SQL (docker compose exec db psql) unless it exists,
then measures p95 latency of the main endpoints. Exits 1 if a target is missed.
"""
import argparse
import http.cookiejar
import json
import statistics
import subprocess
import sys
import time
import urllib.request

TARGETS_MS = {"read (GET ticket)": 200, "write (PATCH ticket)": 300, "open ticket (detail + comments + history)": 500,
              "board 2 000 cards": 1500, "search": 500}

SEED_SQL = """
do $$
declare s uuid; wf uuid; admin uuid;
begin
  if exists (select 1 from spaces where key = 'PERF') then return; end if;
  select id into admin from users where username = '{user}';
  wf := gen_random_uuid();
  insert into workflows (id, name) values (wf, 'Workflow PERF');
  insert into statuses (id, workflow_id, name, category, position) values
    (gen_random_uuid(), wf, 'À faire', 'TODO', 0), (gen_random_uuid(), wf, 'En cours', 'IN_PROGRESS', 1), (gen_random_uuid(), wf, 'Terminé', 'DONE', 2);
  s := gen_random_uuid();
  insert into spaces (id, key, name, workflow_id, lead_id, next_number) values (s, 'PERF', 'Performance', wf, admin, 50001);
  insert into space_ticket_types select s, id from ticket_types;
  insert into tickets (id, space_id, number, type_id, status_id, priority_id, summary, description, reporter_id, creator_id, rank, resolved_at)
  select gen_random_uuid(), s, g, (select id from ticket_types where name = 'Tâche'),
         (select id from statuses where workflow_id = wf and position = case when g <= 1000 then 0 when g <= 2000 then 1 else 2 end),
         (select id from priorities where is_default), 'Ticket de charge ' || g || ' paiement ' || (g % 97),
         'Description ' || md5(g::text), admin, admin, lpad(to_hex(g), 8, '0'),
         case when g > 2000 then now() - interval '60 days' else null end
  from generate_series(1, 50000) g;
  -- Board: done tickets are hidden after 14 days, so it shows the 2 000 open ones.
  insert into boards (id, space_id, name, is_default, hide_done_days) values (gen_random_uuid(), s, 'Board PERF', true, 14);
  insert into board_columns (id, board_id, name, position)
    select gen_random_uuid(), b.id, st.name, st.position from boards b, statuses st where b.space_id = s and st.workflow_id = wf;
  insert into board_column_statuses (column_id, status_id)
    select c.id, st.id from board_columns c join boards b on b.id = c.board_id join statuses st on st.name = c.name and st.workflow_id = wf where b.space_id = s;
end $$;
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin-password")
    ap.add_argument("--runs", type=int, default=30)
    a = ap.parse_args()

    print("Seeding 50 000 tickets in space PERF (skipped if present)…")
    subprocess.run(["docker", "compose", "exec", "-T", "db", "psql", "-U", "fluxo", "-d", "fluxo", "-v", "ON_ERROR_STOP=1"],
                   input=SEED_SQL.format(user=a.user).encode(), check=True, stdout=subprocess.DEVNULL)

    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def csrf():
        return next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")

    def call(method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(a.url + path, data=data, method=method,
                                     headers={"Content-Type": "application/json", "X-XSRF-TOKEN": csrf()})
        with opener.open(req) as r:
            return json.loads(r.read() or b"null")

    call("GET", "/api/setup")
    call("POST", "/api/auth/login", {"username": a.user, "password": a.password})
    space = call("GET", "/api/spaces/PERF")
    board = space["boards"][0]["id"]

    def measure(name, fn):
        fn()  # warm-up
        times = []
        for i in range(a.runs):
            t0 = time.perf_counter()
            fn(i)
            times.append((time.perf_counter() - t0) * 1000)
        p95 = statistics.quantiles(times, n=20)[18]
        ok = p95 <= TARGETS_MS[name]
        print(f"{'OK ' if ok else 'KO '} {name:45s} p95 = {p95:7.1f} ms  (cible {TARGETS_MS[name]} ms)")
        return ok

    results = [
        measure("read (GET ticket)", lambda i=0: call("GET", f"/api/tickets/PERF-{1 + i * 997 % 50000}")),
        measure("write (PATCH ticket)", lambda i=0: call("PATCH", f"/api/tickets/PERF-{1 + i}", {"summary": f"Modifié {i} {time.time()}"})),
        measure("open ticket (detail + comments + history)", lambda i=0: [call("GET", f"/api/tickets/PERF-{10 + i}{p}")
                                                                          for p in ("", "/comments", "/history", "/links", "/attachments")]),
        measure("search", lambda i=0: call("GET", f"/api/search?q=paiement%20{i % 97}&spaces=PERF&sort=updated,desc")),
        measure("board 2 000 cards", lambda i=0: call("GET", f"/api/boards/{board}/cards")),
    ]
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
