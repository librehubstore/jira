# Fluxo

Gestion de tickets et board Kanban pour usage interne : une instance = une organisation, auto-hébergée.
Spécification : [documentation/jira-research/jira-prompt.md](documentation/jira-research/jira-prompt.md) ·
Suivi : [documentation/IMPLEMENTATION.md](documentation/IMPLEMENTATION.md) ·
Décisions : [documentation/DECISIONS.md](documentation/DECISIONS.md)

## Lancer en production (Docker)

Prérequis : Docker avec Compose.

```bash
export FLUXO_SECRET="$(openssl rand -hex 32)"     # signe les cookies « se souvenir de moi » ; à conserver
export FLUXO_ADMIN_USERNAME=admin                  # facultatif : sinon écran « premier lancement »
export FLUXO_ADMIN_PASSWORD='un-mot-de-passe-long'
docker compose --profile app up -d --build
```

Ouvrir http://localhost:8080. Le premier administrateur est créé au démarrage si aucun n'existe
(variables ci-dessus), sinon l'application affiche l'écran de premier lancement.

| Variable | Défaut | Rôle |
|---|---|---|
| `FLUXO_DB_URL` / `FLUXO_DB_USER` / `FLUXO_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/fluxo`, `fluxo`, `fluxo` | Base PostgreSQL 16 |
| `FLUXO_SECRET` | valeur de dev | Clé des cookies « se souvenir de moi » (obligatoire avec le profil `app`) |
| `FLUXO_ADMIN_USERNAME` / `FLUXO_ADMIN_PASSWORD` | vide | Administrateur initial |
| `FLUXO_STORAGE_DIR` | `/data/attachments` (image) | Pièces jointes |
| `FLUXO_MAX_UPLOAD` | `25MB` | Taille maximale d'une pièce jointe |
| `FLUXO_BLOCKED_EXTENSIONS` | `exe,bat,cmd,com,msi,scr,ps1,vbs` | Extensions refusées |

Les migrations de base (Flyway) s'appliquent au démarrage. Santé : `GET /actuator/health`.

## Sauvegarde et restauration

Deux éléments à sauvegarder ensemble : la base et le volume des pièces jointes.

```bash
# Sauvegarde
docker compose exec -T db pg_dump -U fluxo -Fc fluxo > fluxo-$(date +%F).dump
docker run --rm -v jira_attachments:/data -v "$PWD":/backup alpine tar czf /backup/attachments-$(date +%F).tgz -C /data .

# Restauration (application arrêtée)
docker compose --profile app stop app
docker compose exec -T db pg_restore -U fluxo -d fluxo --clean --if-exists < fluxo-AAAA-MM-JJ.dump
docker run --rm -v jira_attachments:/data -v "$PWD":/backup alpine sh -c "rm -rf /data/* && tar xzf /backup/attachments-AAAA-MM-JJ.tgz -C /data"
docker compose --profile app start app
```

Le nom du volume (`jira_attachments`) dépend du nom du dossier du projet : `docker volume ls` pour le vérifier.

## Développement

```bash
docker compose up -d db
cd backend && FLUXO_ADMIN_USERNAME=admin FLUXO_ADMIN_PASSWORD=admin-password ./mvnw spring-boot:run
cd frontend && npm install && npm run dev          # http://localhost:5180 (proxy /api → 8080)
```

Tests : `cd backend && ./mvnw verify` (JUnit + Testcontainers, Docker requis) · `cd frontend && npm test` (Vitest) ·
`cd e2e && npx playwright test` (parcours de bout en bout, voir `e2e/README.md`).

Stack : Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Flyway · React 19 + TypeScript · Vite · Tailwind 4 ·
TanStack Query · dnd-kit · TipTap.
