# Fluxo — Plan d'implémentation et suivi

Spec : [jira-research/jira-prompt.md](jira-research/jira-prompt.md) · Hypothèses : [DECISIONS.md](DECISIONS.md)

**Légende** : `[ ]` à faire · `[~]` en cours · `[x]` fait (tests verts) · `[-]` abandonné (motif)

**Phase courante** : V1, ajouts et post-implémentation terminés
**Dernier état build/tests** : 2026-10-14 — backend `mvnw verify` OK (66 tests) · frontend `vitest` OK (9, dont complétude i18n) · e2e Playwright OK (4 parcours + axe WCAG A/AA) · perf OK (50 000 tickets, voir journal P8) · image Docker `fluxo:dev` 394 Mo

## Arborescence
```
backend/            Spring Boot 4.1, Java 21, Maven wrapper, com.fluxo.<feature>
frontend/           React 19 + TS + Vite, Tailwind, TanStack Query, dnd-kit, TipTap
docker-compose.yml  postgres:16 (dev) ; profil "app" = image complète
Dockerfile          multi-stage : front → static du backend → jar
```
Dev : `docker compose up -d db` · `cd backend && FLUXO_ADMIN_USERNAME=admin FLUXO_ADMIN_PASSWORD=admin-password ./mvnw spring-boot:run` · `cd frontend && npm run dev` → http://localhost:5180 (proxy /api → 8080).

---

## MVP

### P0 — Socle
- [x] P0.1 Squelette backend (Spring Initializr, Maven wrapper)
- [x] P0.2 Squelette frontend (Vite react-ts, Tailwind, Router, TanStack Query, proxy /api)
- [x] P0.3 docker-compose (db postgres:16)
- [x] P0.4 Flyway V1 : schéma MVP (§6)
- [x] P0.5 Erreurs JSON uniformes (ProblemDetail), healthcheck
- [x] P0.6 Test : contexte Spring sur Testcontainers ; `npm run build` OK

### P1 — Comptes & authentification (§4.1)
- [x] P1.1 Admin initial (env `FLUXO_ADMIN_USERNAME/PASSWORD`) ou écran premier lancement
- [x] P1.2 Login session cookie + CSRF, BCrypt, mdp ≥ 10, changement forcé si temporaire
- [x] P1.3 Verrouillage 5 échecs / 10 min → 15 min, déverrouillage admin
- [x] P1.4 Admin : CRUD comptes (désactiver, réinitialiser), groupes
- [x] P1.5 Préférences utilisateur
- [x] P1.6 Front : login, premier lancement, changement mdp, admin utilisateurs/groupes
- [x] P1.7 Tests critères §4.1

### P2 — Espaces, rôles, permissions (§2, §4.2)
- [x] P2.1 Espaces : clé, compteur séquentiel, alias de clé, archivage, suppression
- [x] P2.2 Rôles par défaut (Admin/Membre/Lecteur) + drapeaux, appartenances user/groupe
- [x] P2.3 PermissionService central + filtrage des espaces visibles
- [x] P2.4 Création d'espace : workflow simplifié, types, board par défaut
- [x] P2.5 Front : liste espaces, création, membres
- [x] P2.6 Tests critères §4.2 + matrice de permissions

### P3 — Tickets (§4.3, §4.8, §4.9 MVP)
- [x] P3.1 Référentiels seedés : types, priorités, résolutions, statuts
- [x] P3.2 Ticket CRUD, clé, champs système, verrou optimiste (409)
- [x] P3.3 Hiérarchie Epic > Standard > Sous-tâche, progression des enfants
- [x] P3.4 Valeurs par défaut par espace/type (FieldConfig)
- [x] P3.5 Transitions + résolution auto + `resolved_at`
- [x] P3.6 Historique append-only
- [x] P3.7 Changement de type, déplacement d'espace, suppression
- [x] P3.8 Front : création rapide (C), vue ticket (page + panneau), édition inline, historique
- [x] P3.9 Tests critères §4.3, §4.8

### P4 — Collaboration (§4.4–4.7)
- [x] P4.1 Commentaires markdown (own/all), tri mémorisé
- [x] P4.2 @mentions (membres) → watcher + notification ; réactions
- [x] P4.3 Pièces jointes : upload, limites, stockage disque, vignettes, aperçu, collage image
- [x] P4.4 Observateurs (W, auto-watch)
- [x] P4.5 Liens tickets (types seedés) + liens web, créer ticket lié
- [x] P4.6 Front : éditeur TipTap, activité (Tout/Commentaires/Historique), PJ, liens
- [x] P4.7 Tests critères §4.4–4.7

### P5 — Board Kanban (§4.10 MVP)
- [x] P5.1 Board : colonnes ↔ statuts, non mappés + bandeau
- [x] P5.2 Rang (base-36 point milieu) + déplacement = transition + rang
- [x] P5.3 WIP min/max indicatif
- [x] P5.4 Swimlanes : aucune/assigné/parent/priorité/type
- [x] P5.5 Filtres rapides prédéfinis + personnalisés (ET, URL), recherche texte
- [x] P5.6 Masquage des terminés (resolved_at), champs de carte, sous-tâches en cartes, flag
- [x] P5.7 Front : dnd-kit, virtualisation, panneau `?selected=`, configuration du board
- [x] P5.8 Tests critères §4.10 + perf 2 000 cartes

### P6 — Recherche & liste (§4.11 MVP)
- [x] P6.1 Recherche globale (clé exacte / plein texte)
- [x] P6.2 Vue liste : filtres visuels, colonnes mémorisées, tri, pagination
- [x] P6.3 Export CSV
- [x] P6.4 Tests visibilité + cohérence immédiate

### P7 — Notifications in-app (§9)
- [x] P7.1 Événements → destinataires, jamais l'auteur, regroupement 2 min
- [x] P7.2 Préférences par événement
- [x] P7.3 Front : cloche, liste lu/non lu
- [x] P7.4 Tests

### P8 — UX transverse & livraison
- [x] P8.1 Raccourcis clavier + palette Ctrl/Cmd+K
- [x] P8.2 Thème clair/sombre, i18n fr/en
- [x] P8.3 Accessibilité : alternative clavier au DnD, ARIA live
- [x] P8.4 Dockerfile multi-stage + compose profil app + README
- [x] P8.5 E2E Playwright : 4 parcours §5
- [x] P8.6 Mesures perf (seed 50 000 tickets)

---

## V1
Ordre de réalisation (dépendances d'abord). Chaque lot = back + front + tests.

### V1.1 — Langage de requête FQL et filtres sauvegardés
- [x] V1.1.1 Parseur FQL (champs, opérateurs, fonctions, ORDER BY, erreurs positionnées) → Specification JPA
- [x] V1.1.2 WAS / CHANGED sur l'historique, `hasChildren`, `linkedTickets()`
- [x] V1.1.3 Recherche : mode FQL + autocomplétion, bascule filtres visuels ↔ FQL
- [x] V1.1.4 Filtres sauvegardés (partage, favoris), filtre de base de board, filtres rapides FQL, swimlanes par requêtes / par champ

### V1.2 — Temps réel
- [x] V1.2.1 Flux d'événements serveur (SSE) par espace après commit ; board et ticket se mettent à jour sans recharger

### V1.3 — Board avancé
- [x] V1.3.1 Plusieurs boards par espace, board par défaut, board multi-espaces
- [x] V1.3.2 Backlog Kanban
- [x] V1.3.3 Couleurs de cartes (type, priorité, assigné, requête), jours dans la colonne (jours ouvrés)
- [x] V1.3.4 Colonnes repliables, dépôt par statut, assignation par glisser entre couloirs, tactile, WIP bloquant

### V1.4 — Workflows
- [x] V1.4.1 Éditeur de workflow : statuts, transitions, conditions, validateurs, actions, écran de transition, blocage par liens

### V1.5 — Productivité
- [x] V1.5.1 Modifications en masse en tâche de fond (BulkJob, progression, rapport)
- [x] V1.5.2 Clonage avec options, templates de tickets

### V1.6 — Champs et référentiels
- [x] V1.6.1 Labels (gestion, couleurs, fusion), composants, versions + publication depuis le board
- [x] V1.6.2 Champs personnalisés + configuration des champs par espace/type
- [x] V1.6.3 Administration des référentiels (types, priorités, résolutions, types de liens)

### V1.7 — Collaboration
- [x] V1.7.1 Visibilité des commentaires par rôle, renommage des PJ
- [x] V1.7.2 Notifications email (SMTP facultatif, fils par ticket)

### V1.8 — Intégrations et rapports
- [x] V1.8.1 API publique : jetons d'accès personnels, OpenAPI/Swagger
- [x] V1.8.2 Webhooks signés HMAC (journal des livraisons) + livraison de l'action de transition « Envoyer un webhook » (`WorkflowWebhook`)
- [x] V1.8.3 Import CSV (aperçu, simulation, unité explicite) / export CSV
- [x] V1.8.4 Diagramme de flux cumulé (CFD)

- [x] Éditeur markdown brut (livré dans le MVP, P4)

## Ajouts fonctionnels (ADD_FUNCTIONALITIES.md)
- [x] A.1 Plusieurs collaborateurs par ticket ; carte : avatars assigné + rapporteur, puis collaborateurs
- [x] A.2 Clic droit sur une carte : ouvrir, cloner, assigner, signaler, archiver, supprimer…
- [x] A.3 Bouton « + » sous les cartes de chaque cellule : ajout rapide à cet endroit
- [x] A.4 Avatar uploadé dans le profil, utilisé partout, zoom au survol
- [x] A.5 Capture d'écran en pièce jointe (choix de l'écran, compte à rebours 3 s)

## Post-implémentation (POST-IMPLEMENTATION.md)
- [x] Q.1 SonarQube front + back, correction du maximum de défauts (`documentation/QUALITY-SONAR.md`)
- [x] S.1 Analyse des dépendances front/back + plan d'amélioration (`documentation/SECURITY-DEPENDENCIES.md`)
- [x] S.2 Revue d'architecture sécurité + plan d'amélioration (`documentation/SECURITY-ARCHITECTURE.md`)
- [x] S.3 Recherche de code malveillant (`documentation/SECURITY-MALWARE-SCAN.md`)

## V2
- [ ] WIP par personne / cellule / partagé
- [ ] Abonnements aux filtres
- [ ] Clonage / liaison en masse
- [ ] Commentaires épinglés / en fils, mentions de groupes, groupes observateurs
- [ ] Suivi du temps
- [ ] Control chart
- [ ] Export complet d'espace

---

## Journal
| Date | Tâche | Note |
|---|---|---|
| 2026-10-14 | P0 | Socle : Spring Boot 4.1.1 + Flyway V1 (schéma MVP + référentiels seedés), Vite/React 19 + Tailwind 4, compose postgres:16. Avast intercepte TLS → `.mvn/jvm.config` utilise le magasin Windows. |
| 2026-10-14 | P1 | Auth session + CSRF SPA, remember-me 30 j (signé, révoqué par changement de mdp), verrouillage, admin initial env/écran, CRUD comptes/groupes, audit. Filtre qui recharge l'utilisateur à chaque requête (désactivation immédiate). Front : login, setup, changement mdp, admin. |
| 2026-10-14 | P2 | Espaces (clé, alias, archivage lecture seule, suppression confirmée), rôles Admin/Membre/Lecteur + drapeaux, membres user/groupe, PermissionService (404 si invisible, 403 sinon), création = workflow 3 statuts + 5 types + board par défaut. Front : liste, création (clé suggérée), paramètres/membres/rôles. Bug corrigé : remember-me (nom d'utilisateur du principal) + `/error` public. |
| 2026-10-14 | P3 | Tickets : clé séquentielle (verrou de ligne), champs système, valeurs par défaut espace/type, hiérarchie Epic>Standard>Sous-tâche, transitions + résolution auto, historique append-only, verrou optimiste (409), changement de type, déplacement (alias d'ancienne clé, sous-tâches suivent), suppression avec choix sous-tâches. Rang : pas fixe ±1 en bout de liste (test 10 000 ajouts → 6 car.). Front : création rapide C, vue ticket, édition en place, historique. Corrigés : focus du dialogue, pas de retry sur 4xx. |
| 2026-10-14 | P4 | Commentaires markdown (own/all), @mentions → observateur (notification en P7), réactions (8 emojis), suppression tracée ; PJ sur disque (vignette ImageIO, inline seulement png/jpeg/gif/webp/pdf, sinon téléchargement, CSP sandbox, extensions bloquées) ; observateurs (créateur, auto-watch, W) ; liens bidirectionnels + liens web http(s), « ticket restreint ». Front : éditeur TipTap riche/markdown brut (préférence), collage d'image → PJ, onglets Tout/Commentaires/Historique, tri mémorisé, M → commentaire. Corrigés : noms d'entités imbriquées JPA, refs de l'éditeur. |
| 2026-10-14 | P5 | Board : données en une requête (cartes + badges sous-tâches/commentaires/PJ/bloqué, compte non mappés), déplacement = transition + rang entre voisins, envoi en haut/bas, masquage des terminés sur resolved_at, colonnes ↔ statuts (création de statut), WIP min/max, swimlanes, champs de carte, filtres rapides structurés. Front : dnd-kit (souris + clavier, annonces ARIA), mise à jour optimiste, filtres dans l'URL (prédéfinis, personnalisés, par valeur, texte), panneau latéral redimensionnable `?selected=`, page de configuration. 2 000 cartes < 1,5 s (test). |
| 2026-10-14 | P6 (en cours) | Recherche structurée (Specification + plein texte préfixe incl. commentaires), tri multi-colonnes, pagination, dernier commentaire, export CSV (BOM, anti-formules), recherche rapide `/`. Entrée sur une clé exacte vérifiée dans Chrome (« pel-5 » → /browse/PEL-5). |
| 2026-10-14 | P7 | Événements `TicketEvent` publiés par tickets/commentaires, listener synchrone (même transaction) : destinataires du §9, jamais l'auteur, contrôle BROWSE, préférences (muet par événement, « seulement direct »), regroupement 2 min (même destinataire/ticket/auteur/événement, non lu). API liste/compteur/lu/tout lu. Front : cloche (compteur 30 s + focus), page Préférences (profil, travail, notifications). |
| 2026-10-14 | P8 | Raccourcis (hook commun désactivable, aide `?`, J/K/N/P, Maj+N/P = déplacement clavier, E/A/I/L/M/W), palette Ctrl/⌘+K (navigation + actions sur le ticket ouvert), thème clair/sombre/système (sans flash), Dockerfile multi-étapes + compose profil `app` + README (sauvegarde/restauration), e2e Playwright sur image Docker et base neuve (port 18080), script perf 50 000 tickets. Bugs trouvés par l'e2e et corrigés : valeur de `<select>` lue en différé (statut, rôle, observateur), `URLSearchParams` muté (filtres du board/recherche), Entrée démarrait un glisser dnd-kit, contraste `slate-400`, sémantique liste des cartes. |
| 2026-10-14 | P8.2 | i18n fr/en : `t('texte français', vars)` + dictionnaire `i18n.en.ts` (≈370 libellés), langue = préférence utilisateur (sinon navigateur), erreurs API traduites par `code`. Test Vitest : chaque clé traduite, aucune traduction orpheline. **MVP terminé.** |
| 2026-10-15 | V1.1 | FQL : parseur fait main + compilation en Specification (WAS/CHANGED via historique, fonctions, tri), erreurs positionnées ; recherche en mode FQL avec autocomplétion et bascule ; filtres sauvegardés partagés/favoris ; filtre de base du board, filtres rapides FQL (`/match`), couloirs par requêtes / rapporteur / label. 50 000 tickets : < 400 ms. |
| 2026-10-15 | V1.2 | SSE `/api/events` après commit, filtré par espaces visibles ; le front invalide board/ticket/notifications ; cartes figées pendant un glisser. Vérifié via le proxy Vite. |
| 2026-10-15 | V1.3 | Plusieurs boards (création, défaut, suppression), boards multi-espaces, backlog Kanban (vue liste, glisser, création et édition inline, epics), couleurs de cartes, jours ouvrés dans la colonne, colonnes repliables, zones de dépôt par statut, glisser entre couloirs → champ, tactile, WIP bloquant. Back 85 tests, front 16, e2e 5/5. |
| 2026-10-15 | V1.4 | Éditeur de workflow (statuts, transitions, conditions toutes/au moins une, validateurs, actions, écran), workflow par type, suppression de statut avec migration, transitions disponibles seules proposées (fiche, palette, glisser, clavier, backlog). Correctifs : version de carte relue avant déplacement ; écriture différée de `q` qui annulait une navigation (board et recherche). |
| 2026-10-15 | V1.5 | Traitements en masse en tâche de fond (éditer, transitionner, déplacer, supprimer, observer), rapport par ticket, notifications coupées par défaut au-delà de 20 ; sélection multiple (Maj+clic, tout sélectionner ≤ 1000) dans la recherche et le backlog ; clonage avec options ; modèles de tickets avec sous-tâches. Back 96 tests, front 19, e2e 7/7. |
| 2026-10-15 | V1.6.1 | Labels (renommer, fusionner, couleur, supprimer), composants (responsable = assigné par défaut, archivage), versions (publier, rouvrir, archiver, fusionner) ; « Publier une version » depuis le board ; FQL `component`, `fixVersion`, `releasedVersions()` ; opérations en masse ; page « Référentiels ». Back 100 tests, front 20. |
| 2026-10-15 | V1.6.2–3 | Champs personnalisés (10 types, valeurs JSONB, historique, FQL par nom, autocomplétion des options), activation par espace et type, configuration visible / obligatoire à la création / ordre / défaut ; écran d'administration des types, priorités (ordre, défaut), résolutions, types de liens, et types par espace. Back 106 tests, front 20, e2e 8/8. Le backend de dev a été arrêté par Claude Code (mémoire basse) : non relancé. |
| 2026-10-15 | V1.7 | Commentaires restreints à un rôle (défaut par espace, exclus de la recherche plein texte, notifications sans extrait), renommage des PJ ; emails via Spring Mail si SMTP configuré : file `email_outbox`, un email par utilisateur et ticket après 2 min, en-têtes de fil, préférences in-app / email par événement ; notification « bloquant résolu ». Back 109 tests, front 20. |
| 2026-10-15 | V1.8 | Jetons d'accès personnels (lecture / écriture, expiration, révocation, hors CSRF), OpenAPI + Swagger sous `/api/docs` ; webhooks signés HMAC (3 tentatives, journal), livraison de l'action de transition ; import CSV (aperçu, correspondance, simulation, unité explicite, parent par identifiant de ligne) et export JSON d'un ticket ; unité d'estimation par espace ; CFD (SVG, affinable par filtre rapide / couloir FQL). Back 115 tests, front 21, e2e 9/9. **V1 complète.** |
| 2026-10-15 | A.1–A.5 | Collaborateurs (carte : rapporteur + assigné, collaborateurs dessous ; FQL `collaborator`), menu clic droit (ouvrir, m'assigner, signaler, statut selon transitions, haut/bas, cloner, lien, archiver, supprimer), bouton « + » par cellule (couloir et colonne respectés), avatars uploadés avec zoom au survol, capture d'écran en PJ (choix de l'écran, attente 3 s). Correctif : ajout de membre impossible avant chargement des rôles. Back 118 tests, front 22, e2e 10/10. |
| 2026-10-15 | S.1–S.3 | Dépendances : 10 avis (Tomcat critiques, Jackson hauts) corrigés par montée de version, 0 restant (OSV, npm audit) ; `@tanstack/react-virtual` retirée. Architecture : CSP, Referrer-Policy, Permissions-Policy ajoutées, script en ligne supprimé, plan A1–A12. Code malveillant : rien trouvé. Back 119 tests, front 22, e2e 10/10. Rapports : `SECURITY-DEPENDENCIES.md`, `SECURITY-ARCHITECTURE.md`, `SECURITY-MALWARE-SCAN.md`. |
| 2026-10-15 | Q.1 | SonarQube local (conteneur) : 99 défauts → 0 ouvert (76 corrigés dont 2 vrais bugs, 23 justifiés dans SonarQube) ; JaCoCo et couverture Vitest ajoutés ; quality gates OK (back 74,9 %, couverture du code nouveau 82,8 %). Back 123 tests, front 22, e2e 10/10. |
