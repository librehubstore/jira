# Jira — notes de recherche (périmètre : Kanban + gestion de tickets, usage interne)

Contexte : Atlassian a renommé "issues" → "work items" et "projects" → "spaces" (2025). Board settings = space admin ou board admin.

## Piste A — officiel

### Colonnes du board
- Colonnes = statuts mappés ; ajout/suppression/renommage/déplacement ; drag des statuts entre colonnes et panneau "Unmapped statuses" [Official] — https://support.atlassian.com/jira-software-cloud/docs/configure-columns/
- Statut non mappé = ticket caché du board ET du backlog [Official] — idem
- Supprimer une colonne renvoie ses statuts en "Unmapped" [Official] — idem
- Seule la colonne la plus à droite = "terminé" ; couleurs : gris (à faire), bleu (en cours), vert (fait) [Official] — idem
- Contraintes colonne : min/max sur "nombre de tickets" ou "hors sous-tâches" ; en-tête rouge si max dépassé, jaune si min non atteint ; n'empêche pas l'affichage ; compte les tickets masqués par quick filters [Official] — idem
- Workflow simplifié : ajouter une colonne crée le statut ; supprimer un statut uniquement s'il n'a aucun ticket [Official] — idem
- Backlog Kanban activable par admin board [Official] — idem

### Swimlanes
- Méthodes : Stories (parent + sous-tâches), Queries (JQL par couloir), Assignees (non assignés en haut ou en bas), Epics (parent), Spaces, Aucune [Official] — https://support.atlassian.com/jira-software-cloud/docs/configure-swimlanes/
- Couloirs par requête : par défaut "Expedite" (priority = Blocker) + "Everything Else" (toujours en bas, non supprimable) ; ORDER BY ignoré, ordre par rang ; couloirs réordonnables par drag [Official] — idem

### Quick filters
- Nom + JQL + description ; réordonnables ; affichés en dropdown ou en boutons [Official] — https://support.atlassian.com/jira-software-cloud/docs/configure-quick-filters/
- Par défaut : "Only my issues" (assignee = currentUser()), "Recently updated" (updated >= -1d) [Official] — idem
- Combinaison de plusieurs quick filters non documentée par la page (connu : AND entre filtres) [Inferred] — idem
- Renommés "Custom filters" dans la nouvelle vue board [Official] — idem

## Piste B — utilisateurs
- Lenteur (10 s+ chargement), recherche qui rate des tickets [Fix] — https://pol1.lr.ggtyler.dev/r/programming/comments/vgvt3m/i_fucking_hate_jira/id66z1v/?context=3
- Carte qui disparaît quand un collègue la déplace ; interactions board/ticket confuses [Fix] — https://feddit.it/post/11055575
- Configuration par défaut trop lourde (epics, stories, subtasks, sprints) pour un usage simple [Requested] — https://deviniti.com/blog/enterprise-software/why-hate-jira/

## Sources non lues
- https://www.atlassian.com/software/jira/pricing — contenu tronqué par la lecture de page
- https://developer.atlassian.com/cloud/jira/platform/rest/v3/intro/ — tronqué

## Piste A — suite (officiel)

### Backlog Kanban
- Activable par board ; sections "Backlog" + "Selected for development" = statuts mappés colonnes 1 et 2 ; drag = transition ; rang par drag, clic droit haut/bas ; création inline ; "Create another" ; édition inline (résumé, assigné, priorité, parent) ; split d'un ticket [Official] — https://support.atlassian.com/jira-software-cloud/docs/use-your-kanban-backlog/

### Cartes
- Couleur de carte : par type, priorité, assigné ou requête JQL (1re requête qui matche gagne, gris sinon) ; bande à gauche [Official] — https://support.atlassian.com/jira-software-cloud/docs/customize-cards/
- Jusqu'à 3 champs supplémentaires sur la carte ; indicateur "jours dans la colonne" (points), cumulatif si retour arrière [Official] — idem

### Masquer les terminés
- Kanban : terminé = catégorie de statut Done ; masquage après 1/2/4 semaines ou jamais ; toute édition remet le timer à zéro ; sous-filtre par défaut masque les tickets d'une version "Released" [Official] — https://support.atlassian.com/jira/kb/kanban-board-shows-or-hides-completed-work-unexpectedly-in-jira-cloud/
- Demande d'option 24 h [Requested] — https://jira.atlassian.com/browse/JRACLOUD-98961

### Workflows
- Transitions : globales (depuis tout statut), initiale "Create" ; conditions (Only Assignee, Only Reporter, Permission, User in group/role, Sub-task blocking, Previous status, Value field…) groupables All/Any ; validators ; post-functions (assign to current user/reporter, clear field, copy field, update field incl. Resolution set/clear, webhook) ; post-functions essentielles fixes (status, comment, history, reindex, event) [Official] — https://support.atlassian.com/jira-cloud-administration/docs/configure-advanced-issue-workflows/

### Tickets (work items)
- Types par défaut : Epic (niveau 1), Story/Task/Bug (0), Subtask (-1) ; parent = niveau directement au-dessus ; max 500 enfants affichés [Official] — https://support.atlassian.com/jira-cloud-administration/docs/what-are-issue-types/
- Liens : blocks/is blocked by, clones/is cloned by, duplicates/is duplicated by, implements, causes, merged into, reviews, relates to ; types configurables par admin ; inter-espaces ; création d'un ticket lié [Official] — https://support.atlassian.com/jira-software-cloud/docs/link-issues
- Watch (W), auto-watch si commentaire/édition (préférence), commentaires markdown (titres, gras, code, listes, action items), @mention notifie, réactions emoji, lien vers un commentaire, tri d'activité inversable [Official] — https://support.atlassian.com/jira-software-cloud/docs/watch-share-and-comment-on-a-work-item/
- Activité filtrable : Commentaires / Historique (champs, transitions, ancien→nouveau) / Worklog / Tout [Official] — https://support.atlassian.com/jira-software-cloud/docs/what-are-the-different-types-of-activity-on-an-issue/
- Bulk change : transition (par groupe de même workflow, champs requis ex. résolution), edit, delete, move, watch ; max 1000 ; option "ne pas notifier" [Official] — https://support.atlassian.com/jira-software-cloud/docs/edit-multiple-issues-at-the-same-time/
- Raccourcis : ? aide, C créer, O ouvrir, A assigner, I m'assigner, M commenter, J/K haut/bas, N/P colonne suivante/précédente, Ctrl+K palette, E panneau epics [Official] — https://support.atlassian.com/jira-software-cloud/docs/use-keyboard-shortcuts/

### Recherche JQL
- Champs : affectedVersion, assignee, attachments, comment, component, created, creator, description, due, environment, fixVersion, labels, lastViewed, originalEstimate, parent, priority, project, remainingEstimate, reporter, resolution, resolved, status, summary, text, timespent, type, updated, voter/votes, watcher/watchers, key, issueLink, issueLinkType [Official] — https://support.atlassian.com/jira-software-cloud/docs/advanced-search-reference-jql-fields/
- Opérateurs : = != > >= < <= IN, NOT IN, ~, !~, IS, IS NOT, WAS, WAS IN, WAS NOT IN, WAS NOT, CHANGED ; mots-clés AND OR NOT EMPTY NULL ORDER BY [Official] — https://support.atlassian.com/jira-software-cloud/docs/jql-operators/ , https://support.atlassian.com/jira-software-cloud/docs/jql-keywords/
- Fonctions : currentUser, membersOf, now, startOf/endOf Day/Week/Month/Year, linkedWorkItems, updatedBy, watchedWorkItems, votedWorkItems, releasedVersions, unreleasedVersions, earliestUnreleasedVersion, latestReleasedVersion, componentsLeadByUser, standardWorkTypes, subtaskWorkTypes, lastLogin [Official] — https://support.atlassian.com/jira-software-cloud/docs/jql-functions/
- Filtres sauvegardés : partage, favoris, abonnement email planifié, export [Official] — https://support.atlassian.com/jira-software-cloud/docs/save-your-search-as-a-filter/

### Permissions
- Espace : Administer, Browse, Edit workflows, Manage versions, Manage work item layouts… [Official] — https://support.atlassian.com/jira-cloud-administration/docs/permissions-for-company-managed-projects/
- Commentaires/PJ : add comments, edit own/all, delete own/all ; create attachment, delete own/all ; manage watchers ; view voters & watchers [Official] — https://support.atlassian.com/jira-cloud-administration/docs/voters-watchers-comment-and-attachment-permissions/
- Rôles d'espace pour attribuer les permissions sans admin global [Official] — https://support.atlassian.com/jira-cloud-administration/docs/manage-project-permissions/

### Notifications
- Événements : created, updated, assigned, resolved, closed, commented, comment edited, reopened, deleted, moved, worklog (+/edit/delete), generic ; destinataires : assigné, rapporteur, utilisateur courant, lead d'espace, lead de composant, user, groupe, rôle, watchers, champ user [Official] — https://support.atlassian.com/jira-cloud-administration/docs/configure-notification-schemes/

### Rapports Kanban
- CFD : aire par colonne, basé sur filtre du board et mapping des colonnes ; affinable [Official] — https://support.atlassian.com/jira-software-cloud/docs/view-and-understand-the-cumulative-flow-diagram/
- Control chart : cycle/lead time sur statuts choisis, moyenne glissante (20 % des tickets, impair, min 5), écart-type, échelle racine cubique ≥ 30 j [Official] — https://support.atlassian.com/jira-software-cloud/docs/view-and-understand-the-control-chart/

## Piste B — tracker public Atlassian (votes au 2026-10-14, via API publique https://jira.atlassian.com/rest/api/2/search)
- Valeurs par défaut des champs système — 2798 votes [Requested] — https://jira.atlassian.com/browse/JRACLOUD-4812
- Champs affichés sur les cartes configurables — 2462 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85694
- Tickets d'autres espaces sur un board — 1897 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85680
- Éditeur markdown texte brut — 1772 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-72631
- Clonage en masse — 1755 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-37520
- Plusieurs boards par espace — 1739 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85686
- Gestion/suppression des labels — 1111 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-33672
- Sous-tâches visibles sur le board sans regroupement — 1059 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85702
- Visibilité par défaut des commentaires — 990 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-9091
- Plus d'options de swimlanes — 943 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85691
- Rang manuel des enfants dans un epic — 903 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-76877
- Dernier commentaire en colonne de liste — 796 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-2734
- Commentaires en fils — 696 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-93678
- Mention de groupes — 695 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-28225
- Lier en masse — 664 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-2428
- Recherche tickets avec/sans enfants — 655 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-67108
- Quick filter en dropdown de valeurs — 643 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85789 ; https://jira.atlassian.com/browse/JSWCLOUD-8095
- WIP partagé sur sous-colonnes — 576 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-86585
- Champs dans la section liens — 540 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-36490
- Options de clonage (PJ, liens, sous-tâches) — 540 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-5052 ; https://jira.atlassian.com/browse/JRACLOUD-1558
- Empêcher fermeture si "is blocked by" ouvert — 519 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-43369
- Labels colorés — 470 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-71983 ; https://jira.atlassian.com/browse/JRACLOUD-40855
- Jours/colonne hors jours non ouvrés — 473 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85653 ; https://jira.atlassian.com/browse/JSWCLOUD-5118
- Estimation en badge sur cartes Kanban — 448 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-86517
- Config board via API — 430 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-94779
- Renommer une PJ — 394 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-25060 ; labels wildcard — https://jira.atlassian.com/browse/JRACLOUD-23038
- Épingler un commentaire — 394 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-74995
- Colonnes repliables — 380 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85706 ; https://jira.atlassian.com/browse/JSWCLOUD-3690
- Board par défaut d'un espace — 356 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-91321
- Templates de tickets — 346 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-7731
- Groupes en watchers — 300 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-12913
- Tri des commentaires mémorisé — 291 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-5443
- Limite de texte avec avertissement — 281 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-59124
- Min/max par cellule swimlane×colonne — 260 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-86557
- Drag entre statuts d'une même colonne — 227 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-85718
- WIP par personne — 219 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-36973
- Swimlane par Parent / autres champs — 218 [Requested] — https://jira.atlassian.com/browse/JRACLOUD-88030 ; https://jira.atlassian.com/browse/JRACLOUD-91263
- Contrainte colonne sur champ numérique — https://jira.atlassian.com/browse/JSWCLOUD-4917 ; quick filters colorés — https://jira.atlassian.com/browse/JSWCLOUD-20567
- Assigner par drag entre swimlanes d'assignés — https://jira.atlassian.com/browse/JSWCLOUD-6431 ; drag multiple — https://jira.atlassian.com/browse/JSWCLOUD-9581 ; tactile — https://jira.atlassian.com/browse/JSWCLOUD-22130
- Simplified workflow : désactiver création de statuts — https://jira.atlassian.com/browse/JSWCLOUD-15276 ; réglage global "hide completed" — https://jira.atlassian.com/browse/JSWCLOUD-20759
- Historique : Plan mode Kanban (1079, livré), cartes configurables (824, livré), retrait auto du Done (446, livré) — https://jira.atlassian.com/browse/JSWCLOUD-6175 , https://jira.atlassian.com/browse/JSWCLOUD-3922 , https://jira.atlassian.com/browse/JSWCLOUD-17582

### Bugs connus → comportement attendu [Fix]
- Menu "Changer de statut" du board ignore conditions/validators → n'afficher que transitions autorisées — https://jira.atlassian.com/browse/JRACLOUD-95902
- Sélecteur d'assigné non contextuel → membres de l'espace, récents d'abord — https://jira.atlassian.com/browse/JRACLOUD-98324
- Bulk edit 504 à ~300 tickets → tâche de fond avec progression — https://jira.atlassian.com/browse/JRACLOUD-96474
- Recherche incohérente après création en masse → cohérence immédiate — https://jira.atlassian.com/browse/JRACLOUD-97427
- Clonage altère le contenu riche → clone fidèle — https://jira.atlassian.com/browse/JRACLOUD-96785
- PJ impossibles sur écran de transition — https://jira.atlassian.com/browse/JRACLOUD-73889 ; https://jira.atlassian.com/browse/JRACLOUD-82488
- "Change parent" contourne les validations — https://jira.atlassian.com/browse/JRACLOUD-97034
- Date picker initialisé au 18/02/1993 — https://jira.atlassian.com/browse/JRACLOUD-77159
- Import CSV : estimation minutes/heures — https://jira.atlassian.com/browse/JRACLOUD-81938
- Navigation board ↔ backlog bloquée — https://jira.atlassian.com/browse/JRACLOUD-95434 ; mémoire > 2 Go/onglet — https://jira.atlassian.com/browse/JRACLOUD-90098
- Masquage terminés réinitialisé par toute édition → baser sur date de résolution — KB ci-dessus

### Avis / forums
- Lenteur (secondes pour ouvrir un ticket / changer un statut), config complexe nécessitant un admin, UI surchargée [Fix] — https://www.capterra.com/p/19319/JIRA/reviews/
- Trop de champs requis, workflows lourds [Fix] — https://dev.to/rajesh_patel/jira-too-complex-discover-the-best-alternatives-for-seamless-project-management-52da
- Linear apprécié : vitesse, clavier (Cmd+K), peu de config [Requested] — https://www.eesel.ai/blog/linear-vs-jira ; https://toolradar.com/compare/jira-vs-linear
- Lenteur souvent due à la mauvaise configuration — https://club.ministryoftesting.com/t/i-hate-jira-do-you/83031

## Familles de sources couvertes
- Board de feedback officiel (jira.atlassian.com) ✔ ; forums/Reddit (miroirs, Lemmy, Ministry of Testing) ✔ ; avis (Capterra, extraits de recherche) ✔ ; comparatifs (Linear) ✔.
- Non lus : G2 (non trouvé) ; Reddit direct (peu de threads) ; pricing Atlassian (tronqué). Blogs ONES écartés (éditeur concurrent, contenu générique).
