# Prompt — Reconstruction complète de Fluxo (clone interne de Jira, Kanban + tickets)

> À donner tel quel à l'agent de code (Mistral Vibe 3.5), lancé à la racine du dépôt.
> Le dossier `documentation/` doit contenir au départ : `jira-research/jira-prompt.md` (spécification fonctionnelle complète),
> `jira-research/notes.md` (notes de recherche), `ADD_FUNCTIONALITIES.md`, `POST-IMPLEMENTATION.md`.

---

## 0. Ta mission et ta façon de travailler

Tu es l'unique développeur de **Fluxo**, un outil interne de gestion de tickets et de boards Kanban inspiré de Jira, pour un usage
**interne** (pas d'inscription publique, pas de facturation, pas de SSO). Tu dois produire, dans ce dépôt, une application complète,
testée et livrable en image Docker, en réalisant **dans l'ordre** :

1. le **MVP** (phases P0 à P8) ;
2. la **V1** (lots V1.1 à V1.8) ;
3. les **ajouts fonctionnels** de `documentation/ADD_FUNCTIONALITIES.md` (A.1 à A.5) ;
4. le **travail post-implémentation** de `documentation/POST-IMPLEMENTATION.md` (Q.1 SonarQube, S.1 à S.3 sécurité).

La spécification fonctionnelle de référence est `documentation/jira-research/jira-prompt.md` : lis-la entièrement avant de commencer
et relis la section concernée avant chaque lot (les numéros `§x.y` de ce prompt y renvoient). Ce prompt précise **comment** la
réaliser : architecture, choix techniques, décisions déjà arbitrées, pièges connus, tests attendus. En cas de conflit, ce prompt
l'emporte sur la spec (les arbitrages sont listés en §11).

### Règles de travail

- **Langue** : toutes les réponses, la documentation et les textes de l'interface en **français correct, accents compris**.
  Le code, les identifiants, les commentaires de code et les messages de commit en anglais.
- **Autonomie** : avance lot par lot sans demander de validation, sauf action destructive ou externe (suppression de données hors
  de ce que tu as créé, publication). Si une information manque, choisis la solution la plus simple, note-la dans `DECISIONS.md`.
- **Suivi obligatoire** dans `documentation/IMPLEMENTATION.md` : une case par tâche, `[ ]` à faire, `[~]` en cours, `[x]` fait
  **uniquement quand les tests correspondants sont verts**, `[-]` abandonné avec motif ; une ligne « Phase courante » ; un journal
  daté en fin de fichier (une ligne par lot : ce qui a été livré, nombre de tests back/front/e2e).
- **Décisions** dans `documentation/DECISIONS.md` : tableau `| Date | Sujet | Décision | Pourquoi |`, une ligne par arbitrage.
- **Minimalisme** : solution la plus courte qui fonctionne. Échelle de choix : inutile → déjà dans le code → bibliothèque standard
  → fonctionnalité native de la plateforme → dépendance déjà installée → nouvelle dépendance seulement en dernier recours.
  Pas d'abstraction à une seule implémentation, pas d'échafaudage « pour plus tard ». Quand tu prends un raccourci assumé
  (verrou global, parcours O(n²), cache naïf), écris un commentaire `ponytail:` qui nomme la limite et la piste d'évolution.
- **Un test par logique non triviale** (parseur, calcul, règle de droits, chemin d'argent/sécurité). Pas de test pour un one-liner.
- **Correction de bug = cause racine** : avant de corriger, cherche tous les appelants de la fonction touchée ; corrige à l'endroit
  par où ils passent tous.
- **Pas de git** : le dossier n'est pas un dépôt ; ne fais ni `git init` ni commit.
- **Ports déjà pris sur la machine** : 5173 (une autre application) et 8081 (mongo-express) — n'y touche pas. Vite tourne sur
  **5180**, l'API sur **8080**, la pile e2e sur **18080**.

### Boucle de vérification à chaque lot

1. `cd backend && ./mvnw -q -B verify` (tests d'intégration sur PostgreSQL réel via Testcontainers) ;
2. `cd frontend && npx tsc -b && npx vitest run` ;
3. à la fin de chaque lot : `docker build -q -t fluxo:dev .` puis `cd e2e && npx playwright test --reporter=line` (pile
   `fluxo-e2e`, arrêtée ensuite par `docker compose -f e2e/docker-compose.e2e.yml down -v`) ;
4. mise à jour de `IMPLEMENTATION.md` (cases, journal) et de `DECISIONS.md`.

Ne coche jamais une tâche dont les tests échouent. Si un test e2e échoue, vérifie d'abord s'il s'agit d'une vraie régression
(c'est le cas le plus fréquent) avant de toucher au test.

---

## 1. Le produit en une page

Fluxo gère des **espaces** (≈ projets Jira) contenant des **tickets** typés (Epic > Standard (Tâche, Bug, Story) > Sous-tâche),
qui suivent un **workflow** de statuts (catégories À faire / En cours / Terminé) et s'affichent sur des **boards Kanban**
configurables. Autour : commentaires riches (markdown), mentions, réactions, pièces jointes, liens entre tickets, observateurs,
historique, notifications in-app et email, recherche avec un langage de requête (**FQL**), filtres sauvegardés, opérations en masse,
champs personnalisés, composants, versions, rapports (diagramme de flux cumulé), API REST documentée avec jetons et webhooks.

Utilisateurs : un administrateur global ; des membres d'espaces avec des rôles (Admin / Membre / Lecteur par défaut, rôles
personnalisables par permissions). Contenus et données de référence en français ; interface traduite en anglais.

---

## 2. Stack technique (inchangée, versions exactes)

| Couche | Choix |
|---|---|
| Backend | **Java 21**, **Spring Boot 4.1.1** (Spring 7, **Jackson 3** `tools.jackson.*`, **Hibernate 7**), Maven Wrapper (Maven 3.10.0), packaging jar |
| Starters | `webmvc`, `data-jpa`, `flyway` (+ `flyway-database-postgresql`), `security`, `validation`, `actuator`, `mail` ; `springdoc-openapi-starter-webmvc-ui` **3.0.0** |
| Base | **PostgreSQL 16**, migrations **Flyway** (`V1__…` à `V11__…`), `hibernate.ddl-auto: validate` |
| Tests back | `spring-boot-starter-*-test`, **Testcontainers** (`postgres:16`, `@ServiceConnection`), `MockMvcTester` (AssertJ), JaCoCo 0.8.15 |
| Frontend | **React 19**, **TypeScript 6**, **Vite 8** (`@vitejs/plugin-react`), **Tailwind CSS 4** (`@tailwindcss/vite`), **react-router 8**, **@tanstack/react-query 5** |
| Front, libs | `@dnd-kit/core` 6, `@dnd-kit/sortable` 10, `@dnd-kit/utilities` 3 ; **TipTap 3** (`@tiptap/react`, `starter-kit`, `extension-image`, `extension-mention`, `extension-placeholder`, `extension-task-list`, `extension-task-item`, `suggestion`) + `tiptap-markdown` 0.9 ; `marked` 18 + `dompurify` 3 |
| Tests front | **Vitest 5** (jsdom, `@testing-library/react`, `@testing-library/jest-dom`), `@vitest/coverage-v8`, **oxlint** |
| E2E | **Playwright** + `@axe-core/playwright` dans `e2e/` (projet npm séparé) |
| Livraison | `Dockerfile` multi-étapes (node:24-alpine → eclipse-temurin:21-jdk → eclipse-temurin:21-jre, utilisateur non-root uid 1001), `docker-compose.yml` (db + profil `app`), `e2e/docker-compose.e2e.yml` |

**N'ajoute aucune autre dépendance** sans l'inscrire dans `DECISIONS.md` avec sa justification. Pas de bibliothèque de graphiques
(le CFD est en SVG fait main), pas d'ANTLR (parseur FQL écrit à la main), pas de broker (ni Kafka, ni RabbitMQ, ni STOMP),
pas de Spring Session JDBC, pas de bibliothèque i18n.

Correctifs de sécurité appliqués en avance du BOM Spring Boot (propriétés du `pom.xml`) : `tomcat.version` **11.0.27**,
`jackson-bom.version` **3.1.7**, `jackson-2-bom.version` **2.21.7** (voir S.1).

---

## 3. Arborescence cible

```
backend/
  .mvn/jvm.config                 -Djavax.net.ssl.trustStoreType=Windows-ROOT (machine de dev Windows derrière un antivirus qui
                                  inspecte TLS ; supprimé par le Dockerfile)
  pom.xml, mvnw, mvnw.cmd
  src/main/java/com/fluxo/
    FluxoApplication.java         @SpringBootApplication @EnableScheduling
    auth/      SecurityConfig, CurrentUserFilter, ApiTokenFilter, ApiTokenService, TokenController, AuthController,
               LoginService, AuthUser (record, Principal), AdminBootstrap
    user/      User, Group, UserService, AdminUserController, DirectoryController, AvatarController, repositories
    space/     Space, SpaceRole, SpaceMembership, SpaceKeyAlias, Perm (enum + ensembles ALL/MEMBER/VIEWER/ARCHIVED),
               PermissionService, SpaceService, SpaceController, repositories
    ticket/    Ticket, TicketType, RefData (Priority, Resolution), Label, Component, FixVersion, HistoryEntry, FieldDefault,
               Rank, TicketService, TicketController, TicketViews (records DTO), CatalogController, ReferenceController,
               TemplateController (+ entité Template imbriquée), ImportService, ImportController, repositories
    collab/    Comment, Attachment, Links (LinkType, TicketLink, WebLink), CommentService, AttachmentService, LinkService,
               WatcherService, CloneService, CloneController, CollabController, repositories
    workflow/  Workflow, Status, StatusCategory, Transition, WorkflowService, WorkflowController, WorkflowWebhook (record)
    board/     Board, BoardColumn, QuickFilter, BoardService, BoardController, CfdService, repositories
    search/    SearchService, SearchController, SavedFilter, SavedFilterController, repository ; fql/Fql.java, fql/FqlCompiler.java
    notification/ TicketEvent (record), Notification, NotificationService, NotificationController, EmailService
    realtime/  RealtimeService (SSE), RealtimeController
    bulk/      BulkJob, BulkService, BulkController, BulkJobRepository
    field/     FieldService, FieldController
    webhook/   WebhookService, WebhookController
    common/    Errors, AuditLog, AuditService, SpaConfig
  src/main/resources/application.yml, db/migration/V1__init.sql … V11__…sql
  src/test/java/com/fluxo/        IntegrationTest (base), TestcontainersConfiguration, TestFluxoApplication + un fichier de
                                  tests par fonctionnalité (voir §12)
frontend/
  index.html (aucun script en ligne), public/favicon.svg, public/theme.js
  src/main.tsx, App.tsx, api.ts, auth.ts, spaces.ts, tickets.ts, i18n.ts, i18n.en.ts, keyboard.ts, realtime.ts, storage.ts,
      ui.tsx, index.css, test-setup.ts
  src/board/     BoardPage, BoardCard, BoardSettingsPage, BacklogPage, CardMenu, CfdPage, model.ts (+ tests)
  src/components/ Badges, Inline, Editor, Markdown, TicketView, TicketCollab, Transitions, FqlInput, Bulk, CustomFields,
                  CreateTicketDialog, CommandPalette, GlobalSearch, NotificationBell, ShortcutHelp, ScreenCapture (+ tests)
  src/pages/     AuthPages, SpacesPage, SpaceLayout, SpaceSettingsPage, WorkflowPage, CatalogPage, SearchPage,
                 PreferencesPage, AdminPages, AdminUsersPage, ReferenceAdmin, IntegrationPages
  scripts/       i18n-missing.mjs, i18n-add.py (outils de traduction)
e2e/             package.json, playwright.config.ts, global-setup.ts, docker-compose.e2e.yml, tests/journeys.spec.ts,
                 measure-board.mjs
scripts/perf.py  amorçage de 50 000 tickets + mesures de temps de réponse
Dockerfile, docker-compose.yml, .dockerignore, README.md
documentation/   IMPLEMENTATION.md, DECISIONS.md, SECURITY-DEPENDENCIES.md, SECURITY-ARCHITECTURE.md,
                 SECURITY-MALWARE-SCAN.md, QUALITY-SONAR.md (+ fichiers d'entrée)
```

---

## 4. Conventions transverses — backend

- **Paquets par fonctionnalité** (pas de couches `controller/service/repository` globales). Contrôleurs et services
  package-private quand possible ; les services partagés sont `public`.
- **Erreurs** : `spring.mvc.problemdetails.enabled: true` (RFC 9457). Fabrique `common.Errors` : `badRequest(code, message)`,
  `conflict`, `notFound`, `forbidden`, `of(status, code, message)` → `ErrorResponseException` dont le `ProblemDetail` porte une
  propriété `code` stable (`TICKET_CONFLICT`, `FQL_ERROR`, `WIP_LIMIT`…) et éventuellement d'autres propriétés (`position`,
  `currentVersion`). Messages en français ; le front les traduit en anglais par leur `code`.
- **Droits** : tout passe par `PermissionService` : `permissions(user, space)`, `has(user, space, perm)`, `check(user, space,
  perm)` (404 si l'espace est invisible, 403 sinon — ne jamais révéler l'existence d'un espace), `visibleSpaces(user)`,
  `assignableUsers(space)`, `isAssignable`, `roleIds(user, space)`. L'admin global a toutes les permissions mais **n'est pas
  assignable partout** (seuls les membres explicites avec `BE_ASSIGNABLE`). Un espace archivé ne garde que `BROWSE` et
  `ADMINISTER_SPACE`.
- **Permissions** (`Perm`) : `BROWSE, CREATE, EDIT, TRANSITION, ASSIGN, BE_ASSIGNABLE, DELETE, LINK, COMMENT_ADD,
  COMMENT_EDIT_OWN, COMMENT_EDIT_ALL, COMMENT_DELETE_OWN, COMMENT_DELETE_ALL, ATTACH_ADD, ATTACH_DELETE_OWN, ATTACH_DELETE_ALL,
  MANAGE_WATCHERS, BULK_CHANGE, MANAGE_BOARD, MANAGE_VERSIONS, ADMINISTER_SPACE`. Rôles par défaut : Admin = tout ; Membre =
  `BROWSE, CREATE, EDIT, TRANSITION, ASSIGN, BE_ASSIGNABLE, LINK, COMMENT_ADD, COMMENT_EDIT_OWN, COMMENT_DELETE_OWN, ATTACH_ADD,
  ATTACH_DELETE_OWN, MANAGE_WATCHERS, BULK_CHANGE` ; Lecteur = `BROWSE`.
- **Tickets** : toute modification passe par `TicketService.apply(me, ticket, field, jsonValue, List<HistoryEntry> sink)` (sink
  `null` = pas d'historique, utilisé à la création). Champs d'API : `typeId, summary, description, priorityId, assigneeId,
  reporterId, parent (clé), dueDate, startDate, estimate, labels, flagged, componentIds, fixVersionIds, customFields,
  collaboratorIds, archived` ; tout autre champ → 400 `UNKNOWN_FIELD`. `version` (verrou optimiste `@Version`) est optionnel ;
  s'il est fourni et périmé → 409 `TICKET_CONFLICT` avec `currentVersion`.
- **Événements** : `TicketService` publie des `TicketEvent(type, ticket, actorId, changes, excerpt, mentioned, previousAssignee)`
  **dans la transaction** (`ApplicationEventPublisher`). Types : `CREATED, UPDATED, ASSIGNED, TRANSITIONED, RESOLVED, REOPENED,
  COMMENTED, COMMENT_EDITED, MENTIONED, DELETED, MOVED, BLOCKER_RESOLVED, COMMENT_DELETED`. Consommateurs : notifications
  (synchrone), temps réel (après commit), webhooks (charge utile construite dans la transaction, envoi après commit).
- **Audit** : `AuditService` journalise les actions d'administration (comptes, espaces, rôles…).
- **JDBC brut** quand c'est plus simple que JPA : `JdbcClient`, **toujours avec paramètres liés**. Les comptages s'écrivent
  `.query(Long.class).optional().orElse(0L)`.
- **DTO** : records dans les contrôleurs ou `TicketViews` (`UserRef(id, username, displayName, active, avatar)`, `TypeRef`,
  `StatusRef`, `PriorityRef`, `TicketRef`, `TicketDetail`, `HistoryView`, `VersionRef`, `Progress`…).
- **Base de test** `IntegrationTest` (abstraite) : `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers partagé, admin
  `admin` / `admin-password` créé par propriétés. Aides : `post/patch/put/delete/get(session, url, body)` (avec CSRF),
  `login(user, pwd)`, `admin()`, `createUser(prefix)` (mot de passe `PASSWORD = "password-123"`), `createSpace(key, name)`,
  `addMember(key, username, roleName)`, `roleId(key, name)`, `userIdByName`, `read(result)` → `JsonNode`, `randomKey()`,
  `idOf(result)`, `unique(prefix)`. Chaque test crée son propre espace (clé aléatoire) : les tests partagent une base.

## 5. Conventions transverses — frontend

- **API** : `api<T>(path, { method, body })` dans `api.ts` préfixe `/api`, envoie le jeton CSRF (cookie `XSRF-TOKEN` → en-tête
  `X-XSRF-TOKEN`) sur toute écriture, sérialise en JSON (ou envoie un `FormData` tel quel), lève `ApiError(status, message, code,
  details)` où `details` contient tout le ProblemDetail (ex. `position` d'une erreur FQL).
- **Cache** : TanStack Query. Clés : `['me']`, `['meta']`, `['spaces', archived]`, `['space', key, …]`, `['ticket', key, …]`,
  `['board', id]`, `['board', id, 'cards', …]`, `['search', …]`, `['notifications', …]`, `['filters']`, `['admin', …]`.
  Les promesses volontairement non attendues sont préfixées par `void` (exigence Sonar).
- **i18n maison** : `t('texte français', { var })` depuis `i18n.ts` ; le texte français **est la clé** ; les traductions anglaises
  sont dans `i18n.en.ts` (objet `EN` + `API_ERRORS_EN` indexé par code d'erreur). Le test `i18n.test.ts` échoue si une chaîne
  enveloppée n'a pas de traduction ou si une traduction n'est plus utilisée. Outils : `node scripts/i18n-missing.mjs` liste les
  manques ; `python scripts/i18n-add.py fichier.tsv` (lignes `français<TAB>anglais`) les ajoute. Dans `TicketView` et
  `TicketCollab`, l'import est `t as tr` (la variable `t` désigne le ticket). Changement de langue = rechargement de la page.
- **UI** : composants maison dans `ui.tsx` (`Button` variantes primary/secondary/danger/ghost, `Input` — `w-full` sauf si une
  classe `w-…` est fournie, `Field`, `ErrorText`) ; Tailwind avec mode sombre par classe `dark` ; texte jamais plus clair que
  `text-slate-500` (contraste WCAG AA vérifié par axe). Dialogues = élément natif `<dialog>` + `showModal()`.
- **Raccourcis** : `useShortcuts(map)` dans `keyboard.ts` ignore les touches pendant la saisie et quand un `<dialog open>` existe.
- **Stockage navigateur** : `storage.ts` (`localStorageGet/Set` protégés par try/catch) uniquement pour des préférences
  d'affichage (largeur du panneau, colonnes repliées, délai de capture).
- **Pièges React** : lire la valeur d'un `<select>` contrôlé **avant** un `mutate` différé ; les setters de `useSearchParams`
  renvoient un **nouveau** `URLSearchParams` ; ne jamais écrire un paramètre d'URL qui n'a pas changé (une écriture différée au
  montage annule une navigation en cours).

---

## 6. Environnement de développement et pièges connus

- Dev : `docker compose up -d db` ; backend `cd backend && FLUXO_ADMIN_USERNAME=admin FLUXO_ADMIN_PASSWORD=admin-password
  FLUXO_STORAGE_DIR=../data/attachments ./mvnw spring-boot:run` ; frontend `cd frontend && npm run dev` (port 5180, proxy
  `/api` → 8080). Identifiants de test : `admin` / `admin-password` (dev), `admin-password-e2e` (e2e).
- `application.yml` : datasource `${FLUXO_DB_URL:jdbc:postgresql://localhost:5432/fluxo}` (user/mdp `fluxo`), `open-in-view:
  false`, `hibernate.default_batch_fetch_size: 100`, multipart `${FLUXO_MAX_UPLOAD:25MB}`, actuator n'expose que `health`,
  `fluxo.admin.username/password`, `fluxo.storage-dir`, `fluxo.blocked-extensions` (exe,bat,cmd,com,msi,scr,ps1,vbs),
  `fluxo.secret` (signature du cookie « se souvenir de moi » ; l'application **refuse de démarrer** si elle est vide), springdoc
  (`api-docs.path: /api/openapi.json`, `swagger-ui.path: /api/docs`).
- **Windows / Git Bash** : préfère écrire un petit script Python (avec `PYTHONIOENCODING=utf-8`) pour les éditions complexes plutôt
  que des heredocs qui mangent les antislashs.
- **Jackson 3** : paquets `tools.jackson.*`, `JsonMapper` injectable, `JsonNode.asString()`, `properties()`, `valueStream()` ;
  les champs booléens optionnels des DTO de requête doivent être `Boolean` (pas `boolean`), sinon une absence fait échouer.
- **Hibernate / JPA** : entités imbriquées → `@Entity(name = "…")` explicite ; littéraux d'enum en JPQL → passer en paramètre ;
  les dépôts Spring Data **ne doivent pas être des interfaces imbriquées** (non détectés) ; collections JSON (`@JdbcTypeCode(
  SqlTypes.JSON)`) : remplacer la map/liste plutôt que la muter pour garantir la détection de changement ; ne jamais lire un
  champ paresseux dans un listener **après** commit (session fermée) : construire les données dans la transaction.
- **Collision de noms** : `jakarta.persistence.criteria.Order` vs `Fql.Order` (qualifier) ; `jakarta.persistence.Version` vs
  l'entité des versions → l'appeler `FixVersion` (table `versions`).
- **SQL** : l'opérateur JSONB `?` entre en conflit avec les paramètres JDBC → utiliser `jsonb_exists(col, :id)`.
- **Spring** : `@EnableScheduling` crée un second `TaskExecutor` → ne pas injecter `TaskExecutor` ; utiliser
  `Executors.newVirtualThreadPerTaskExecutor()` (fermé en `@PreDestroy`). Une dépendance circulaire service ↔ service se règle
  par `ObjectProvider<…>`.
- **Tests MockMvc** : `MockMvcTester.uri(String)` encode lui-même la requête — ne pas pré-encoder les paramètres ; dans un bloc de
  texte Java, `"""` ferme le bloc (échapper un guillemet).
- **Playwright** : une case à cocher contrôlée qui attend le serveur se clique avec `click()` puis `expect(...).toBeChecked()`
  (pas `check()`) ; attendre que le board soit monté avant d'envoyer un raccourci clavier ; ne pas rediriger la sortie
  Playwright dans `head` (blocage).

---

## 7. Modèle de données (migrations Flyway)

Toutes les clés sont des `uuid` générés par l'application (`GenerationType.UUID`) ou `gen_random_uuid()` dans les seeds.

**V1__init.sql** — `users` (username unique insensible à la casse, display_name, email, password_hash, is_admin, active,
must_change_password, preferences jsonb, failed_logins, locked_until, created_at) ; `groups`, `group_members` ; référentiels
`ticket_types` (name, icon `task|bug|story|epic|subtask`, color, hierarchy_level ∈ {1,0,−1}), `priorities` (name, rank unique,
1 = la plus urgente, color, is_default), `resolutions` (name, is_default), `link_types` (name, outward, inward) ; `workflows`
(name, shared), `statuses` (workflow_id, name, category `TODO|IN_PROGRESS|DONE`, position, unique par workflow) ; `spaces` (key
unique ≤ 10 car. majuscules/chiffres, name, description, lead_id, workflow_id, archived, next_number, settings jsonb, dates) ;
`space_key_aliases` (ancienne clé → espace, pour les renommages) ; `space_roles` (space_id, name, permissions `text[]`) ;
`space_memberships` (space_id, user_id **ou** group_id, role_id) ; `space_ticket_types` ; `field_defaults` (space_id,
ticket_type_id nullable, field, value jsonb ; index unique avec `coalesce`) ; `tickets` (space_id, number, type_id, status_id,
priority_id, resolution_id, summary ≤ 255, description text ≤ 100 000, assignee_id, reporter_id, creator_id, parent_id,
due_date, start_date, estimate numeric, flagged, rank varchar, version int, resolved_at, created_at, updated_at, colonne générée
`search tsvector` (config `simple`) + index GIN ; unique (space_id, number)) ; `labels` (space_id, name, color ; unique
`lower(name)`), `ticket_labels` ; `comments` (ticket_id, author_id, body markdown, edited_at, created_at, `search tsvector`) ;
`reactions` (comment_id, user_id, emoji) ; `attachments` (ticket_id, uploader_id, filename, mime, size, storage_key,
thumbnail_key) ; `watchers` ; `ticket_links` (source_id, target_id, link_type_id, unique, check source ≠ target) ; `web_links` ;
`history_entries` (ticket_id, actor_id, at, field, old_value, new_value, old_display, new_display — append-only ; pour un statut,
`old_value`/`new_value` = ids de statut) ; `boards` (space_id, name, is_default, hide_done_days, swimlane_mode, wip_mode,
card_fields jsonb, show_subtasks) ; `board_columns` (board_id, name, position, wip_min, wip_max) ; `board_column_statuses` ;
`quick_filters` (board_id, name, criteria jsonb, position) ; `notifications` (user_id, actor_id, ticket_id nullable, event,
payload jsonb, read_at, created_at, updated_at) ; `audit_log`.
Seeds : types Epic (`epic`, #8b5cf6, 1), Story (`story`, #22c55e, 0), Tâche (`task`, #3b82f6, 0), Bug (`bug`, #ef4444, 0),
Sous-tâche (`subtask`, #60a5fa, −1) ; priorités Bloquante 1 #b91c1c, Haute 2 #f97316, **Moyenne 3 #eab308 (défaut)**, Basse 4
#3b82f6, Mineure 5 #94a3b8 ; résolutions **Fait (défaut)**, Ne sera pas fait, Doublon, Impossible à reproduire ; liens Blocks
(bloque / est bloqué par), Duplicate, **Cloners (clone / est cloné par)**, Relates (est lié à / est lié à), Causes, Implements.

**V2** `ticket_key_aliases` (ancienne clé d'un ticket déplacé → ticket ; les anciennes URL redirigent).
**V3** `saved_filters` (owner_id, name, fql, share_scope `PRIVATE|GROUP|SPACE|ALL`, share_group_id, share_space_id),
`saved_filter_favorites` ; `boards.base_fql`, `boards.swimlane_queries jsonb '[]'` ; contrainte `swimlane_mode ∈ {NONE, ASSIGNEE,
PARENT, PRIORITY, TYPE, QUERIES, REPORTER, LABEL}`.
**V4** `board_spaces` (board_id, space_id) ; `boards.working_days jsonb '[1,2,3,4,5]'`, `backlog_enabled`, `card_color_mode ∈
{NONE, TYPE, PRIORITY, ASSIGNEE, QUERIES}`, `card_color_queries jsonb`, `wip_blocking` ; index partiel
`history_entries (ticket_id, at) where field = 'status'`.
**V5** `transitions` (workflow_id, name, from_status_ids jsonb — vide = transition globale, to_status_id, position, rules jsonb) ;
`space_type_workflows` (space_id, type_id, workflow_id).
**V6** `bulk_jobs` (user_id, operation `EDIT|TRANSITION|MOVE|DELETE|WATCH|UNWATCH`, params jsonb, total, done, failed, report
jsonb, status `RUNNING|DONE`, dates) ; `ticket_templates` (space_id, name unique par espace, type_id, field_values jsonb,
subtasks jsonb).
**V7** `components` (space_id, name unique `lower`, description, lead_id, archived), `ticket_components` ; `versions` (space_id,
name unique `lower`, description, start_date, release_date, status `UNRELEASED|RELEASED|ARCHIVED`), `ticket_fix_versions`.
**V8** `custom_fields` (name unique `lower`, type `TEXT|TEXTAREA|NUMBER|DATE|SELECT|MULTISELECT|USER|USERS|CHECKBOX|URL`, options
jsonb) ; `space_custom_fields` (space_id, field_id, type_ids jsonb — vide = tous les types) ; `field_configs` (space_id,
ticket_type_id nullable, field — nom d'API ou `cf:<uuid>`, visible, required, position, default_value jsonb) ;
`tickets.custom_values jsonb '{}'` + index GIN.
**V9** `comments.visibility_role_id` (→ space_roles, `on delete set null`) ; `email_outbox` (user_id, ticket_id, ticket_key,
summary, line, created_at, sent_at ; index partiel sur les non envoyés).
**V10** `api_tokens` (user_id, name, token_hash char(64) unique, prefix, scope `READ|WRITE`, expires_at, last_used_at, revoked) ;
`webhooks` (url, secret, events jsonb, space_id nullable, active) ; `webhook_deliveries` (webhook_id, event, status_code, error,
attempts, duration_ms, created_at).
**V11** `ticket_collaborators` ; `tickets.archived boolean` ; `users.avatar_key`.

---

## 8. API REST (toutes sous `/api`, JSON, session + CSRF ou jeton Bearer)

Authentification et compte : `GET/POST /setup` (premier lancement si aucun admin), `POST /auth/login` (`rememberMe`),
`POST /auth/logout`, `GET/PATCH /me` (profil, `preferences` fusionnées), `POST /me/password`, `POST/DELETE /me/avatar`,
`GET/POST /me/tokens`, `DELETE /me/tokens/{id}`, `GET /users/{id}/avatar`, `GET /directory/users`, `GET /directory/groups`.
Administration : `/admin/users` (GET, POST, PATCH `{id}`, `{id}/reset-password`, `{id}/unlock`), `/admin/groups` (+ `{id}/members`),
`/admin/custom-fields`, `/admin/reference` (GET), `/admin/ticket-types`, `/admin/priorities` (+ `PUT /order`),
`/admin/resolutions`, `/admin/link-types`, `/admin/webhooks` (+ `{id}/deliveries`). Les suppressions de priorité / résolution
acceptent `?replaceWith=`.
Espaces : `GET/POST /spaces`, `GET/PATCH/DELETE /spaces/{key}` (PATCH accepte aussi `commentVisibilityRoleId`, `estimateUnit`),
`/spaces/{key}/members`, `/spaces/{key}/roles` (+ `PUT /{roleId}`), `/spaces/{key}/assignable`, `/spaces/{key}/labels`
(autocomplétion), `/spaces/{key}/label-catalog`, `/spaces/{key}/components`, `/spaces/{key}/versions`, `/spaces/{key}/templates`,
`/spaces/{key}/field-defaults`, `/spaces/{key}/fields` (+ `PUT /enabled`, `PUT /configs`), `/spaces/{key}/layout?typeId=`,
`PUT /spaces/{key}/ticket-types`, `/spaces/{key}/workflows` (+ `POST/DELETE /types/{typeId}`), `POST /spaces/{key}/import/preview`,
`POST /spaces/{key}/import` (multipart `file` + `options` JSON), `POST /spaces/{key}/tickets`.
Tickets : `GET/PATCH/DELETE /tickets/{key}` (`?withSubtasks=`), `/tickets/{key}/history`, `/transitions` (GET : transitions
autorisées), `POST /transition` (`statusId, resolutionId, version, comment`), `GET /move-preview?spaceKey=`, `POST /move`,
`POST /clone`, `GET /export.json`, `/comments`, `/attachments`, `/links`, `/web-links`, `/watchers`, `PUT/DELETE /watch`.
`PATCH/DELETE /comments/{id}`, `POST /comments/{id}/reactions`, `GET/PATCH/DELETE /attachments/{id}` (`?thumbnail=true`),
`DELETE /links/{id}`, `DELETE /web-links/{id}`, `GET /link-types`, `GET /meta` (types, priorités, résolutions).
Catalogues : `PATCH/DELETE /labels/{id}`, `POST /labels/{id}/merge`, `PATCH/DELETE /components/{id}`, `PATCH/DELETE
/versions/{id}`, `POST /versions/{id}/release|unrelease|archive|merge`, `PUT/DELETE /templates/{id}`, `POST /templates/{id}/tickets`.
Custom fields : `GET /custom-fields`.
Workflows : `GET /workflows/{id}`, `PUT /workflows/{id}/transitions`, `POST /workflows/{id}/statuses`, `PATCH/DELETE
/workflows/{id}/statuses/{statusId}` (`?migrateTo=`).
Boards : `POST /boards`, `GET/PATCH/DELETE /boards/{id}`, `GET /boards/{id}/cards?backlog=`, `GET /boards/{id}/match?fql=`,
`POST /boards/{id}/move` (`ticketKey, statusId, aboveKey, belowKey, version, lane{field,value}, resolutionId, comment`),
`POST /boards/{id}/send` (`top`), `PUT /boards/{id}/columns`, `PUT /boards/{id}/quick-filters`, `POST /boards/{id}/default`,
`POST /boards/{id}/release` (`versionId` ou `name`), `GET /boards/{id}/cfd?from=&to=&fql=&zone=`.
Recherche : `GET /search` (critères structurés + `fql`, `sort`, `page`, `size` ≤ 200), `GET /search/keys` (≤ 1000 clés),
`GET /search/export.csv`, `GET /search/quick?q=`, `GET /search/fql/meta`, `GET /search/fql/values?field=&q=`,
`/filters` (CRUD, `PUT/DELETE /{id}/favorite`).
Divers : `POST /bulk`, `GET /bulk/{id}`, `GET /notifications`, `GET /notifications/unread-count`, `POST /notifications/{id}/read`,
`POST /notifications/read-all`, `GET /events` (SSE), `GET /openapi.json`, `GET /docs` (Swagger UI).

Routes du front : `/login`, `/change-password`, `/` (espaces), `/spaces/:key` (board par défaut), `/spaces/:key/boards/:boardId`,
`…/settings`, `…/backlog`, `…/cfd`, `/spaces/:key/settings`, `/spaces/:key/workflow`, `/spaces/:key/catalog`,
`/browse/:ticketKey`, `/search`, `/preferences`, `/admin/users|fields|reference|webhooks`. Le backend sert le front (fichiers de
`static/`) et renvoie `index.html` pour tout chemin inconnu hors `api/` et `actuator/` (`SpaConfig`).

---

## 9. Plan de réalisation détaillé

Crée d'abord `documentation/IMPLEMENTATION.md` avec toutes les tâches ci-dessous en `[ ]`, puis déroule-les.

### MVP

**P0 — Socle.** Squelettes backend (Spring Initializr équivalent, wrapper) et frontend (Vite react-ts, Tailwind, router, TanStack
Query, proxy). `docker-compose.yml` (postgres:16 + volume ; service `app` en profil `app`). Flyway V1 complet. ProblemDetail.
Test : contexte Spring sur Testcontainers ; `npm run build` passe.

**P1 — Comptes et authentification (§4.1).** Admin initial depuis `FLUXO_ADMIN_USERNAME/PASSWORD` (`AdminBootstrap`), sinon écran
de premier lancement `/setup`. Connexion par session (cookie, 8 h) + CSRF mode SPA (`csrf.spa()`), BCrypt, mot de passe ≥ 10
caractères, mot de passe temporaire à changer (`CurrentUserFilter` bloque toute l'API sauf `/api/me`, `/api/me/password`,
`/api/auth/logout` avec 403 `PASSWORD_CHANGE_REQUIRED`). `CurrentUserFilter` **recharge l'utilisateur à chaque requête** (un
compte désactivé perd immédiatement l'accès). « Se souvenir de moi » : `TokenBasedRememberMeServices` 30 jours, cookie
`fluxo-remember`, demandé via un attribut de requête positionné par le login. Verrouillage : 5 échecs → compte verrouillé 15 min ;
un hachage BCrypt factice (dérivé d'une valeur aléatoire) égalise les temps de réponse pour un identifiant inconnu. Admin : CRUD
comptes (création avec mot de passe temporaire, désactivation, réinitialisation, déverrouillage), groupes. Préférences JSON
(thème, langue, ordre des commentaires, raccourcis, colonnes de recherche, notifications…). Front : login, setup, changement de mot
de passe, pages d'administration. Tests des critères §4.1.

**P2 — Espaces, rôles, permissions (§2, §4.2).** Création réservée à l'admin global : clé (alias conservés lors d'un renommage),
compteur séquentiel des tickets (verrou de ligne `PESSIMISTIC_WRITE` sur l'espace), workflow simplifié propre (À faire / En cours
/ Terminé), types associés, rôles par défaut, board par défaut (une colonne par statut, terminés masqués après 14 jours).
Archivage (lecture seule) et suppression. Appartenances utilisateur ou groupe. Front : liste des espaces, création, membres, rôles
(matrice de permissions). Tests : matrice de permissions, 404 pour un espace invisible.

**P3 — Tickets (§4.3, §4.8, §4.9 MVP).** Création (seul le résumé est obligatoire ; valeurs par défaut par espace puis par type ;
le créateur est rapporteur et observateur), clé `CLÉ-N`, édition champ par champ avec historique, hiérarchie (le parent est
exactement un niveau au-dessus ; une sous-tâche a toujours un parent standard du même espace ; un ticket standard peut avoir un epic
d'un autre espace ; progression des enfants), transitions (résolution par défaut posée à l'entrée en catégorie DONE, vidée à la
sortie, `resolved_at`), changement de type (contrôle des enfants), déplacement d'espace (aperçu des pertes, nouvelle clé + alias,
sous-tâches suivent, labels recréés par nom), suppression (avec sous-tâches au choix). **Rang** (`Rank`) : chaîne base 36, nouvelle
valeur = point milieu lexicographique entre les voisins (`Rank.between(above, below)`, `null` = extrémité) — test unitaire
`RankTest`. Front : création rapide (touche C), vue ticket en page (`/browse/KEY`) et en panneau latéral redimensionnable
(`?selected=KEY`, Échap ferme), édition en place, historique. Tests des critères §4.3, §4.8.

**P4 — Collaboration (§4.4–4.7).** Commentaires markdown (droits own/all, « modifié » horodaté, trace de suppression dans
l'historique, lien direct `#comment-<id>` surligné, tri mémorisé dans les préférences). Mentions `@username` en texte brut,
autocomplétion sur les membres assignables, la personne mentionnée devient observatrice et reçoit une notification MENTIONED.
Réactions : 8 emojis fixes. Pièces jointes : stockage disque sous un nom UUID, limite 25 Mo, extensions bloquées, vignette PNG
240 px pour les images (ImageIO, best effort), téléchargement contrôlé par les droits avec `Content-Security-Policy: sandbox`,
`X-Content-Type-Options: nosniff`, images et PDF en ligne, le reste en téléchargement ; fichiers supprimés après commit, nettoyés
si la transaction échoue ; image collée dans l'éditeur = PJ référencée `/api/attachments/{id}`. Observateurs (W). Liens entre
tickets (types seedés, dans les deux sens) et liens web. Éditeur **TipTap** (markdown via `tiptap-markdown`, cases à cocher,
mentions, images collées) avec bascule vers un éditeur markdown brut selon la préférence ; rendu `marked` + **DOMPurify**.
Activité : onglets Tout / Commentaires / Historique.

**P5 — Board Kanban (§4.10 MVP).** Colonnes ↔ statuts, zone « statuts non mappés » et bandeau d'avertissement pour l'admin
(« N ticket(s) masqué(s) »). Glisser-déposer dnd-kit : changer de colonne = transition (permission TRANSITION), réordonner = rang
(permission EDIT) ; mise à jour optimiste, retour en place avec le motif si le serveur refuse ; envoyer en haut / en bas.
WIP min/max indicatif, compté sur toutes les cartes de la colonne **hors filtres rapides**, en-tête rouge « 5/4 » si dépassé,
jaune si sous le minimum ; mode « hors sous-tâches ». Swimlanes : aucune, assigné (non assignés en dernier), parent, priorité,
type ; couloirs repliables avec compte. Filtres rapides prédéfinis (Mes tickets, Mis à jour récemment = 24 h, Non assignés) et
personnalisés (critères structurés) combinés en ET, filtres par valeur (assigné, type, priorité, label, epic ; OU dans un même
filtre), recherche texte instantanée — **évalués côté client** (fonctions pures de `board/model.ts`, testées) et reflétés dans
l'URL. Masquage des terminés selon `resolved_at` (pas la date de modification). Carte : clé, résumé, type, priorité, avatar,
estimation, pastilles (sous-tâches faites/total, commentaires, PJ, échéance en rouge si dépassée, bloqué si un lien « est bloqué
par » vers un ticket non terminé), drapeau, jusqu'à 3 champs supplémentaires. Sous-tâches affichables comme cartes. Perf :
`content-visibility: auto` sur les cartes (pas de liste virtualisée) ; 2 000 cartes en moins de 1,5 s. Clavier : J/K carte
suivante/précédente, N/P colonne, Entrée/O ouvrir, Espace saisit une carte (capteur clavier dnd-kit), **Maj+N / Maj+P** déplace
la carte d'une colonne (alternative accessible au glisser) ; annonces ARIA live. Page de configuration (MANAGE_BOARD).

**P6 — Recherche et liste (§4.11 MVP).** Recherche globale `/` (clé exacte ou plein texte `tsvector` config `simple`, préfixes
`:*`), vue liste avec filtres visuels (espaces, types, catégories, assignés dont « moi » et « non assigné », priorités, labels,
parent, résolu, dates), colonnes choisies et mémorisées, tri multi-colonnes (Maj+clic), pagination, export CSV (UTF-8 avec BOM,
séparateur `;`, cellules commençant par `= + - @` préfixées d'une apostrophe). Résultats limités aux espaces visibles et
cohérents immédiatement après une modification.

**P7 — Notifications in-app (§9).** Destinataires par événement selon le tableau §9, jamais l'auteur de l'action, la mention
l'emporte, seulement les utilisateurs actifs qui voient l'espace ; **regroupement** des changements d'une même personne sur un
même ticket et du même type sur 2 minutes (la notification non lue existante est enrichie). Préférences par événement et option
« seulement quand je suis mentionné ou assigné ». Cloche avec compteur (rafraîchi par polling 30 s), liste lu / non lu, tout
marquer comme lu. Un ticket supprimé donne une notification sans lien (clé et résumé dans la charge utile).

**P8 — UX transverse et livraison.** Palette de commandes Ctrl/Cmd+K (navigation, actions sur le ticket courant), aide des
raccourcis (?), thème clair/sombre (préférence + `public/theme.js` appliqué avant le premier rendu), i18n fr/en complète,
accessibilité (focus visible, contrastes AA, libellés ARIA). Dockerfile multi-étapes, compose `app`, README. E2E Playwright des
4 parcours §5 + analyse axe (aucune violation critique/sérieuse WCAG A/AA). `scripts/perf.py` : amorce un espace `PERF` de
50 000 tickets et mesure board, recherche, ouverture de ticket.

### V1

**V1.1 — FQL et filtres sauvegardés (§4.11).**
- `search/fql/Fql.java` : lexer + parseur à **descente récursive** écrit à la main. Grammaire : `query := [expr] [ORDER BY field
  [ASC|DESC] {, …}]` ; `expr := or` ; `or := and {OR and}` ; `and := not {AND not}` ; `not := NOT not | ( expr ) | clause` ;
  `clause := field op value(s)` avec les opérateurs `= != > >= < <= ~ !~ IN, NOT IN, IS [NOT] EMPTY, WAS, WAS IN, WAS NOT,
  CHANGED [FROM v] [TO v] [BY v] [AFTER v] [BEFORE v]` ; valeurs = mot, chaîne entre guillemets, nombre, date relative (`-5d`,
  `-2w`), fonction `nom(args)`, liste `(v, …)` ; après `IN` une fonction peut remplacer la liste (`key in linkedTickets(PAY-1,
  "bloque")`) ; un nom de champ peut être une chaîne entre guillemets (champs personnalisés). AST en records (`And`, `Or`, `Not`,
  `Clause`, `Value`, `ChangeCond`, `Order`, `Query`). `ParseException` porte la **position** (index de caractère) ; l'API répond
  400 `FQL_ERROR` avec la propriété `position`. Test unitaire `FqlTest`.
- `FqlCompiler` : compile vers `Specification<Ticket>` (requêtes paramétrées uniquement). Champs : `key, space, type, status,
  statusCategory, resolution, priority (> = plus urgent, par rang), assignee, reporter, creator, labels (* joker), component,
  fixVersion, collaborator, archived, parent, summary, description, comment, text (via l'index plein texte → ensemble d'ids),
  created, updated, resolved, due, start, watcher, issueLink, issueLinkType, hasChildren, flagged, estimate` + champs
  personnalisés par nom. WAS / CHANGED via `history_entries`. Fonctions : `currentUser(), membersOf("groupe"), now(),
  startOf/endOf Day|Week|Month|Year(±n), linkedTickets(KEY, "type"), watchedTickets(), updatedBy(user, from, to),
  releasedVersions(), unreleasedVersions()`. Tri `ORDER BY`. Contexte injecté (`me`, fuseau, résolution des utilisateurs, groupes,
  plein texte, observateurs, rang de priorité, liens, champs personnalisés). Résultats **toujours** restreints aux espaces
  visibles. 50 000 tickets : < 400 ms.
- Recherche : bascule Basique ↔ FQL (les filtres visuels sont convertis en FQL ; pas l'inverse), éditeur FQL avec autocomplétion
  champs / opérateurs / valeurs (`/search/fql/meta`, `/search/fql/values`) et erreur soulignée par un caret à la position.
- Filtres sauvegardés : privé, partagé à un groupe, aux membres d'un espace ou à tous ; favoris ; seul le propriétaire (ou
  l'admin) modifie ; FQL validé à l'enregistrement.
- Board : filtre de base FQL, filtres rapides FQL évalués côté serveur (`/boards/{id}/match` → ids, combinés côté client ;
  rien n'est masqué tant que la réponse n'est pas arrivée), couloirs par requêtes (le serveur calcule l'index du premier couloir
  qui correspond, « Le reste » en dernier), par rapporteur et par label (premier label alphabétique).

**V1.2 — Temps réel.** `GET /api/events` en **SSE** (`SseEmitter` 30 min, le navigateur se reconnecte). Après commit, chaque
`TicketEvent` (et chaque changement de rang) devient un signal `{type, spaceKey, ticketKey, actorId}` envoyé seulement aux
connexions qui voient l'espace (espaces visibles mis en cache 1 min par connexion) ; battement de cœur toutes les 25 s. Côté
client, un unique `EventSource` (`useRealtime` dans le layout) invalide les requêtes concernées (ticket, cartes des boards qui
couvrent l'espace, notifications, recherche) — testé en Vitest. Une carte en cours de glisser n'est jamais déplacée par une mise à
jour distante (les cartes sont figées dans un état React pendant le glisser) ; en cas de conflit la version serveur l'emporte et un
toast l'explique ; la version de la carte est relue juste avant l'envoi d'un déplacement.

**V1.3 — Board avancé.** Plusieurs boards par espace (création, renommage, board par défaut, suppression sauf le défaut, sélecteur
de board). Board **multi-espaces** : un espace propriétaire (droits MANAGE_BOARD) + des espaces additionnels ; on ne voit que les
tickets des espaces parcourables ; une colonne peut regrouper des statuts de plusieurs workflows ; un dépôt prend, dans la
colonne, le statut du workflow du ticket. **Backlog Kanban** : la 1re colonne devient « Backlog » (retirée du board), la 2e
« Sélectionné » ; vue liste avec glisser pour ordonner et pour passer à « Sélectionné », création inline (« créer un autre »),
édition inline (résumé, estimation, priorité, assigné), panneau des epics servant de filtre (touche E) ; nécessite ≥ 3 colonnes.
Couleur de carte (bande gauche) : type, priorité, assigné (teinte dérivée de l'identifiant) ou requêtes FQL (`#rrggbb`).
**Jours dans la colonne** : depuis le dernier changement de statut, en **jours ouvrés** configurés par board (lun–ven par défaut),
proposé comme champ de carte `daysInColumn` (fonction pure testée). Colonnes repliables par utilisateur (localStorage, la colonne
repliée reste une cible de dépôt). **Dépôt par statut** : quand la colonne visée regroupe plusieurs statuts, une zone de dépôt par
statut apparaît pendant le glisser. Glisser vers un autre couloir met à jour assigné, priorité ou parent. Capteur tactile (appui
250 ms). **WIP bloquant** : refus 409 `WIP_LIMIT` côté serveur, anticipé côté client.

**V1.4 — Workflows (§4.9).** Workflow « simplifié » = aucune transition (tout statut vers tout statut) ; l'éditeur propose
« Définir des transitions » qui part d'une transition globale par statut. Transition : nom, statuts de départ (vide = tous),
statut cible, `rules` JSON : `conditionMode` ALL|ANY, `conditions` (ASSIGNEE_ONLY, REPORTER_ONLY, PERMISSION(perm), ROLE(roleId),
GROUP(groupId), SUBTASKS_DONE, NO_OPEN_BLOCKERS), `validators` (FIELD_REQUIRED(field), COMMENT_REQUIRED), `actions`
(ASSIGN_ME, ASSIGN_REPORTER, UNASSIGN, CLEAR_FIELD(field), SET_FIELD(field, valeur JSON ou texte), WEBHOOK(url)), `screen`
(resolution, comment, attachment). `GET /tickets/{key}/transitions` ne renvoie que les transitions autorisées ; une transition non
autorisée → 403, inexistante → 400. Plusieurs transitions vers un même statut : la première dont les conditions passent. Statuts :
ajout, renommage, catégorie, suppression (si utilisé : statut de remplacement obligatoire). Workflow par type : « workflow
propre » = copie du workflow de l'espace ; les tickets du type et les colonnes de board suivent les statuts de même nom ; revenir
au workflow de l'espace supprime la copie. Seul l'admin global modifie un workflow partagé. Front : la fiche, le menu de statut,
la palette (transitions sans écran uniquement), le glisser, le clavier et le backlog n'offrent que les transitions autorisées ;
pendant un glisser les colonnes inaccessibles sont grisées ; l'écran de transition (résolution vers Terminé, commentaire,
pièces jointes) s'ouvre si nécessaire, l'annuler laisse la carte en place (hook `useTransitionScreen` qui renvoie une promesse).

**V1.5 — Productivité (§4.12, §4.3).** Traitements en masse en tâche de fond : `POST /bulk` répond 202 tout de suite ; exécution
sur un **thread virtuel**, **une transaction par ticket** (un refus n'annule pas le reste), permission BULK_CHANGE vérifiée par
ticket, rapport des échecs avec motif, ≤ 1 000 tickets, notifications coupées par défaut au-delà de 20 tickets (interrupteur par
thread lu par le listener de notifications). Opérations : éditer (assigné, priorité, parent ; labels, composants et versions en
ADD/REMOVE/REPLACE), transitionner (par nom de statut, résolu dans le workflow de chaque ticket), déplacer, supprimer, observer,
ne plus observer. Front : sélection multiple (case, Maj+clic pour une plage — fonction pure testée, « tout sélectionner » via
`/search/keys`) dans la recherche et le backlog, dialogue d'opération, barre de progression (polling 500 ms), rapport
« X réussi(s), Y refusé(s) ». Clonage : « COPIE – » + lien « clone », options PJ (fichiers dupliqués), liens (dans les deux
sens), sous-tâches, commentaires (auteur et date d'origine) ; markdown copié à l'identique. Modèles de tickets par espace (type,
valeurs, sous-tâches à créer) proposés à la création.

**V1.6 — Champs et référentiels (§4.13, §4.14).** Labels : page de gestion (nombre d'usages, renommage — conflit → proposer la
fusion, fusion, couleur, suppression) ; le texte d'un label coloré est noir ou blanc selon la luminance WCAG du fond (fonction
testée). Composants (responsable = assigné par défaut à la création, archivage). Versions (non publiée / publiée / archivée,
publier, rouvrir, archiver, fusionner, avancement) ; « Publier une version » depuis le board : les tickets de la dernière colonne
reçoivent la version, qui est publiée ; un ticket portant une version publiée ou archivée quitte le board. Champs personnalisés
(10 types) définis par l'admin global, activés par espace et par type, valeurs dans `tickets.custom_values` (clé = id du champ),
validées par type, historisées, utilisables en FQL par leur nom (évaluation SQL paramétrée renvoyant des ids). Configuration des
champs par espace puis par type : visible, obligatoire à la création, ordre, valeur par défaut des champs personnalisés ; le
résumé est toujours visible et obligatoire ; le dialogue de création affiche les champs obligatoires. Administration des
référentiels : types (niveau non modifiable après création, suppression refusée si utilisé), priorités (ordre, défaut,
remplacement obligatoire à la suppression), résolutions, types de liens (« Cloners » non supprimable) ; types proposés par espace.

**V1.7 — Collaboration.** Commentaire restreint à un rôle d'espace (défaut par espace) : lisible par l'auteur, les membres du rôle
et les admins d'espace ; on ne restreint qu'à un rôle qu'on possède ; exclu de la recherche plein texte ; notification sans
extrait. Renommage d'une PJ (permis à qui peut la supprimer, extension contrôlée). **Emails** (Spring Mail, actifs seulement si
`spring.mail.host` est défini) : file `email_outbox`, vidée toutes les 30 s, un email par utilisateur et par ticket quand la
première ligne a 2 min, sujet `[KEY] résumé`, en-têtes `Message-ID`, `In-Reply-To` et `References` = `<ticket-ID@fluxo>`, lien
direct `fluxo.base-url/browse/KEY` ; préférences in-app et email séparées par événement. Notification « bloquant résolu » : à la
résolution d'un ticket, les observateurs des tickets qu'il bloquait sont prévenus. Unité d'estimation par espace (points ou
heures).

**V1.8 — Intégrations et rapports (§4.15, §8).** Jetons d'accès personnels : `flx_` + 256 bits aléatoires, seul le SHA-256 est
stocké, secret affiché une seule fois, expiration, révocation, portée READ (méthodes GET/HEAD/OPTIONS seulement) ou WRITE ;
`ApiTokenFilter` authentifie `Authorization: Bearer` pour la seule requête (rien en session) ; un jeton ne peut ni gérer les jetons
ni changer le mot de passe ; CSRF ignoré pour les requêtes Bearer. OpenAPI 3 (springdoc) sous `/api/openapi.json`, Swagger UI sous
`/api/docs`, derrière l'authentification. Webhooks (admin) : URL, événements (`ticket.created|updated|transitioned|deleted`,
`comment.created|updated|deleted`, vide = tous), espace facultatif, actif ; charge utile JSON signée **HMAC-SHA256** dans
`X-Fluxo-Signature: sha256=<hex>`, en-tête `X-Fluxo-Event`, envoi après commit (`java.net.http.HttpClient`), 3 tentatives
(attente configurable `fluxo.webhooks.backoff-ms`, 1 s puis 5 s), journal des 50 dernières livraisons ; l'action de transition
WEBHOOK est livrée par le même mécanisme (signée avec `fluxo.secret`). Import CSV : aperçu des en-têtes et des 10 premières lignes,
délimiteur détecté (`;`, `,`, tabulation), parseur RFC 4180 (guillemets, retours à la ligne dans un champ, BOM), correspondance
colonnes → champs (devinée depuis l'en-tête), unité d'estimation **explicite** (`2h`, `90m`, `3pts`, unité par défaut pour les
nombres nus ; incompatibilité avec l'unité de l'espace = erreur), parent par clé ou par identifiant de ligne, statut atteint par
les transitions autorisées, mode simulation (chaque ligne annulée), une transaction par ligne, rapport d'erreurs par ligne,
≤ 5 000 lignes. Export JSON d'un ticket. **CFD** : `CfdService` reconstruit, pour chaque jour de la période, le nombre de
tickets ayant atteint chaque colonne ou une colonne plus à droite (cumulatif) depuis l'historique des statuts, dans le fuseau du
navigateur, affinable par une requête FQL (filtre rapide ou couloir) ; page en SVG fait main (fonction `cfdBands` testée), 7 /
30 / 90 jours. Critère : 5 tickets en « À faire » dont 2 passés en « En cours » aujourd'hui → 5 / 2 / 0.

### Ajouts fonctionnels (`documentation/ADD_FUNCTIONALITIES.md`)

- **A.1** Plusieurs collaborateurs par ticket (membres de l'espace, ajoutés aux observateurs, FQL `collaborator`). La carte montre
  l'avatar du rapporteur et celui de l'assigné, puis en dessous la liste des avatars des collaborateurs.
- **A.2** Clic droit sur une carte (et touche Menu / Maj+F10) : ouvrir, ouvrir dans un nouvel onglet, m'assigner, signaler / retirer,
  changer le statut (transitions autorisées, écran si besoin), envoyer en haut / en bas, cloner, copier le lien, **archiver**,
  supprimer (confirmation). Menu ARIA (`role="menu"`, `menuitem`, navigation aux flèches, Échap). Archivage = booléen `archived` :
  hors board et hors recherche par défaut (visible avec `archived = true` en FQL), ticket toujours ouvrable par sa clé.
- **A.3** Bouton « + » sous les cartes de chaque cellule : saisie rapide ; le ticket est créé avec le champ du couloir (assigné,
  priorité, parent) puis amené dans la colonne par une transition autorisée ; le champ reste ouvert pour enchaîner.
- **A.4** Avatar uploadé dans les préférences : image **décodée côté serveur** (seules les vraies images passent), recadrée au
  carré, réécrite en PNG 128 px, URL versionnée mise en cache un an ; utilisé partout (`UserRef.avatar`) ; **zoom au survol**
  (CSS `scale(2.5)` ancré en bas à droite pour rester visible malgré `content-visibility`, désactivé si « réduire les animations »).
- **A.5** Capture d'écran en pièce jointe partout où l'on ajoute une PJ (section Pièces jointes, écran de transition) :
  `navigator.mediaDevices.getDisplayMedia` (le navigateur fait choisir l'écran, la fenêtre ou l'onglet → postes multi-écrans
  gérés), une image du flux dessinée sur un canvas puis envoyée en PNG nommé `capture-AAAA-MM-JJ-HHhMMmSS.png`. Une page web ne
  peut pas réduire sa propre fenêtre : compte à rebours facultatif de **3 s** (option mémorisée) pour laisser le temps de passer
  devant la bonne fenêtre ou de réduire le navigateur. `Permissions-Policy` autorise `display-capture=(self)`.

### Post-implémentation (`documentation/POST-IMPLEMENTATION.md`)

- **S.1 Dépendances** : `npm audit` (front, e2e), liste des artefacts Maven (`mvn dependency:list -DincludeScope=runtime`)
  interrogée sur l'API **OSV** (`https://api.osv.dev/v1/querybatch`), détail des avis, montée des versions vulnérables, nouvelle
  analyse à 0 ; contrôle des lockfiles (hôte `registry.npmjs.org`, champ `integrity`), des scripts d'installation, du wrapper
  Maven, des images de base. Rapport `documentation/SECURITY-DEPENDENCIES.md` avec plan d'amélioration chiffré.
- **S.2 Architecture** : revue authentification, sessions, CSRF, autorisations, XSS, en-têtes, fichiers, flux sortants, données,
  journaux. Applique : `Content-Security-Policy` (`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; connect-src 'self'; font-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self';
  frame-ancestors 'none'`), `Referrer-Policy: same-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=(),
  display-capture=(self)` (test `SecurityHeadersTests`) ; aucun script en ligne dans `index.html`. Rapport
  `documentation/SECURITY-ARCHITECTURE.md` (constats + plan A1–A12 : secret par défaut, cookies Secure/HSTS, limitation par IP,
  filtrage SSRF des webhooks, antivirus, audit des connexions, RGPD…).
- **S.3 Code malveillant** : inventaire des fichiers par extension, scripts, exécution de processus, obfuscation (`eval`, `atob`,
  base64 longs), URL codées en dur, clients réseau, scripts d'installation npm, plugins Maven, Dockerfile. Rapport
  `documentation/SECURITY-MALWARE-SCAN.md`.
- **Q.1 SonarQube** : `docker run -d --name fluxo-sonar -p 9000:9000 sonarqube:community` ; changer le mot de passe admin par
  défaut, créer un jeton (garde-les hors du dépôt) ; backend : JaCoCo (`prepare-agent` + `report` en `verify`) puis
  `./mvnw verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar` ; frontend : `npx vitest run --coverage
  --coverage.reporter=lcov --coverage.include="src/**"`, chemins `SF:` du lcov en `/`, puis le conteneur
  `sonarsource/sonar-scanner-cli` (hôte `host.docker.internal:9000`, `sonar.sources=src`, tests `**/*.test.ts(x)`). Corrige le
  maximum de défauts (promesses non traitées → `void`, nullabilité, regex à retour arrière, tris sans comparateur, focus…) ;
  marque dans SonarQube, **avec justification**, les faux positifs et choix assumés (SQL construit à partir de constantes, comptages
  `orElse(0L)`, CSRF exempté pour Bearer, `autoFocus` sur un champ ouvert par l'utilisateur, rôle de carte fourni par dnd-kit).
  Objectif : 0 défaut ouvert et quality gates OK (couverture du code nouveau ≥ 80 % — ajoute des tests de cas limites si besoin).
  Rapport `documentation/QUALITY-SONAR.md`.

---

## 10. Algorithmes et règles à respecter précisément

- **Recherche plein texte** : `to_tsquery('simple', …)` construit à partir des mots saisis (préfixes `:*`), colonnes générées
  `search` sur tickets (résumé + description) et commentaires (hors commentaires restreints).
- **Masquage des terminés** : `resolved_at >= now - hideDoneDays`, jamais `updated_at`.
- **Déplacement par glisser** : la requête porte la clé déplacée, le statut cible éventuel, les clés des voisins au-dessus et
  au-dessous dans la cellule affichée ; si les rangs des voisins sont incohérents (vue périmée), placer après celui du dessus.
- **Couleur assigné** : teinte = hash des caractères de l'id modulo 360.
- **Jours ouvrés** : compter les jours entiers écoulés depuis `statusSince` (lendemain inclus jusqu'à aujourd'hui) dont le jour ISO
  (lundi = 1) est dans `workingDays`.
- **Transitions** : `outgoing(ticket)` = statuts du workflow (simplifié) ou transitions dont la cible ≠ statut courant et dont
  la source contient le statut courant (ou vide) ; `available` filtre par TRANSITION puis par conditions ; `resolve(target)` →
  400 `NO_TRANSITION` / 403 ; `validate` (commentaire, champs requis) ; puis changement de statut, résolution, actions,
  événement, commentaire de l'écran.
- **CFD** : statut d'un ticket à la fin du jour J = `to` du dernier changement de statut avant la fin de J, ou `from` du premier
  changement si aucun n'a eu lieu avant, ou statut courant si aucun historique ; ticket compté s'il est créé avant la fin de J ;
  `cumulative[i]` = nombre de tickets dont la colonne ≥ i.
- **Email** : regroupement par (utilisateur, clé de ticket) ; échec SMTP = la ligne reste en file.
- **Signature webhook** : `HMAC-SHA256(secret, corps brut)` en hexadécimal minuscule.

## 11. Décisions déjà arbitrées (à reporter dans `DECISIONS.md`)

Markdown (GFM) = format canonique du contenu riche ; rang base 36 au point milieu ; recherche MVP par critères structurés puis FQL
en V1 sur les mêmes Specifications ; Spring Boot 4.1 et React 19 (plutôt que Boot 3 / React 18 cités dans la spec) ; plein texte
`simple` (contenus fr/en) ; ProblemDetail natif ; session HTTP en mémoire + remember-me signé (pas de Spring Session JDBC,
instance unique) ; création d'espace réservée à l'admin ; espace invisible → 404 ; conflit de version → 409 + rechargement ;
déplacement d'espace = EDIT source + CREATE cible ; assignables = membres explicites ; mentions en texte brut ; 8 réactions ;
images collées = PJ ; filtres de board évalués côté client ; `content-visibility` plutôt que virtualisation ; droits du board
(TRANSITION / EDIT / MANAGE_BOARD) ; notifications synchrones dans la transaction ; secret vide = refus de démarrer ; e2e sur une
pile jetable (port 18080, base en tmpfs) ; `text-slate-500` minimum ; i18n par texte français ; référentiels en français ;
parseur FQL fait main ; SSE plutôt que STOMP ; board multi-espaces à espace propriétaire ; jours dans la colonne via l'historique ;
colonnes repliées en localStorage ; glisser entre couloirs limité à assigné / priorité / parent ; TouchSensor 250 ms ; WIP
bloquant vérifié par le serveur ; workflow sans transition = simplifié ; règles de transition en JSON ; traitements en masse sur
threads virtuels ; clonage fidèle ; versions publiées hors board ; `FixVersion` ; valeurs de champs personnalisés en JSONB ;
commentaires restreints ; emails par outbox ; jetons `flx_` ; webhooks après commit sans broker ; import une transaction par
ligne ; CFD en SVG ; archivage booléen ; avatars réécrits en PNG ; capture par `getDisplayMedia` + compte à rebours ; défauts
Sonar acceptés justifiés plutôt que contournés.

---

## 12. Tests attendus

**Backend** (≈ 120 tests, tous sur PostgreSQL réel) : `AuthTests`, `SpaceTests`, `TicketTests`, `RankTest`, `CollabTests`,
`NotificationTests`, `BoardTests` (dont 2 000 cartes < 1,5 s), `SearchTests`, `FqlTest` (parseur), `FqlSearchTests`,
`RealtimeTests` (un membre reçoit le signal SSE, un non-membre non), `AdvancedBoardTests`, `WorkflowTests`, `ProductivityTests`
(masse, clonage, modèles), `CatalogTests`, `CustomFieldTests`, `ReferenceTests`, `CollaborationV1Tests` (visibilité, renommage,
emails via un `JavaMailSender` simulé avec `@MockitoBean`), `ApiTokenTests`, `WebhookTests` (serveur `com.sun.net.httpserver`
local, signature vérifiée, échec puis succès), `ImportTests`, `CfdTests`, `AddedFeaturesTests`, `SecurityHeadersTests`,
`EdgeCaseTests`.
**Frontend** (Vitest, ≈ 22) : modèle du board (filtres, couloirs, WIP, voisins, jours ouvrés, couleurs, couloirs → champs,
contraste des labels), contexte de l'autocomplétion FQL, invalidation temps réel, choix de transition / écran, sélection avec
Maj+clic, bandes du CFD, nom des captures, **complétude i18n**.
**E2E** (Playwright, exécutés dans l'ordre sur une instance neuve, 10 parcours) : premier lancement (admin, compte, espace,
membre) ; flux principal (créer, s'assigner avec I, avancer au clavier, mentionner, résoudre) ; ticket bloqué visible sur sa
carte ; configuration du board (colonne, WIP « 2/1 », swimlanes, filtre rapide dans l'URL) + **axe** ; backlog (glisser vers
« Sélectionné » à la souris par étapes) + recherche FQL (erreur positionnée puis requête valide) ; workflow (validateur
commentaire obligatoire : écran annulé puis validé) ; masse (2 tickets, rapport « 2 réussi(s), 0 refusé(s) ») + clonage ; champ
personnalisé et composant ; jeton d'API utilisé en `Bearer` + page CFD ; « + » de cellule, menu clic droit, archivage.

## 13. Définition de « terminé » et rapport final

Tout est coché dans `IMPLEMENTATION.md` ; `mvnw verify`, `tsc -b`, `vitest run` et les 10 parcours e2e passent ; l'image
`fluxo:dev` se construit ; les quatre rapports (`SECURITY-DEPENDENCIES.md`, `SECURITY-ARCHITECTURE.md`,
`SECURITY-MALWARE-SCAN.md`, `QUALITY-SONAR.md`) existent ; SonarQube : 0 défaut ouvert, quality gates OK ; aucune dépendance
vulnérable connue ; `DECISIONS.md` à jour. Termine par un rapport en français : ce qui a été livré, les chiffres de tests, les
écarts éventuels avec ce prompt et leur raison, les commandes pour lancer l'application (`docker compose --profile app up -d
--build` avec `FLUXO_SECRET`, `FLUXO_ADMIN_USERNAME`, `FLUXO_ADMIN_PASSWORD`).
