# Décisions et hypothèses

| Date | Sujet | Décision | Pourquoi |
|---|---|---|---|
| 2026-10-14 | Contenu riche | Markdown (GFM) stocké comme format canonique ; TipTap édite en riche, rendu sanitisé | Un seul format, recherchable, couvre la demande « éditeur markdown » |
| 2026-10-14 | Rang des tickets | Chaîne base-36, point milieu entre voisins ; rééquilibrage si > 64 caractères (V1) | Pas de renumérotation à chaque déplacement |
| 2026-10-14 | Recherche MVP | Filtres structurés (JSON → JPA Specification) + plein texte `tsvector` ; FQL en V1 réutilisera les Specifications | Livrer le MVP sans le parseur |
| 2026-10-14 | Temps réel | Reporté en V1 ; MVP = refetch au focus et après mutation | Réduit le MVP |
| 2026-10-14 | Workflow MVP | Workflow simplifié par espace, transitions globales | Éditeur de workflow en V1 |
| 2026-10-14 | Swimlanes / filtres rapides MVP | Modes fixes + critères structurés ; mode « requêtes » et FQL en V1 | Dépendent de FQL |
| 2026-10-14 | Versions | Spring Boot 4.1 (Jackson 3, Hibernate 7) et React 19, au lieu de Boot 3 / React 18 cités dans la spec | Versions stables courantes à la date du projet |
| 2026-10-14 | Maven + TLS | `backend/.mvn/jvm.config` : `trustStoreType=Windows-ROOT` | Avast réécrit les certificats HTTPS ; le truststore Java ne les connaît pas |
| 2026-10-14 | Recherche plein texte | Config `simple` (sans racinisation) | Contenus mixtes fr/en ; évite de choisir une langue |
| 2026-10-14 | Erreurs API | `spring.mvc.problemdetails.enabled` (RFC 9457) | Natif, pas de handler maison |
| 2026-10-14 | Sessions | Session HTTP en mémoire (8 h) + cookie remember-me signé (30 j) ; pas de Spring Session JDBC | Instance unique ; un redémarrage ne déconnecte que les sessions sans « se souvenir de moi » |
| 2026-10-14 | DTO requêtes | Champs booléens optionnels en `Boolean` (pas `boolean`) | Jackson 3 rejette les primitives absentes |
| 2026-10-14 | Port dev front | Vite sur 5180 | 5173 occupé par une autre application locale |
| 2026-10-14 | Création d'espace | Réservée à l'admin global (pas de réglage « les utilisateurs peuvent créer ») | YAGNI, à ajouter si demandé |
| 2026-10-14 | Board par défaut | Masquage des terminés à 14 jours | Valeur raisonnable, modifiable par board |
| 2026-10-14 | Espace invisible | 404 (et non 403) quand l'utilisateur n'a pas BROWSE | Ne pas révéler l'existence de l'espace |
| 2026-10-14 | Conflit de version | 409 `TICKET_CONFLICT` + `currentVersion` ; le front recharge le ticket. Pas de liste des champs en conflit | Plus simple ; les champs modifiés restent visibles dans l'historique |
| 2026-10-14 | Description (P3) | Textarea markdown + rendu sanitisé ; l'éditeur TipTap arrive en P4 | Éditeur partagé avec les commentaires |
| 2026-10-14 | Déplacement de ticket | Droit EDIT sur la source + CREATE sur la cible ; les labels sont recréés par nom dans la cible ; une sous-tâche ne se déplace qu'avec son parent | Rien n'est perdu en MVP (pas encore de composants/versions) |
| 2026-10-14 | Assignables | Seuls les membres explicites (directs ou via groupe) avec BE_ASSIGNABLE ; un admin global n'est pas assignable partout | Évite d'avoir les admins dans tous les sélecteurs |
| 2026-10-14 | Mentions | Texte brut `@username` dans le markdown (pas de nœud mention) ; autocomplétion sur les membres assignables | Markdown restant lisible et recherchable ; les lecteurs restent mentionnables à la main |
| 2026-10-14 | Réactions | Liste fermée de 8 emojis | Évite de stocker du texte arbitraire |
| 2026-10-14 | Pièces jointes | Images collées = PJ du ticket, référencées `/api/attachments/{id}` dans le markdown | Une seule mécanique de stockage et de droits |
| 2026-10-14 | Bundle front | Chunk > 500 Ko (TipTap) accepté | Outil interne ; découpage si le temps de chargement devient gênant |
| 2026-10-14 | Filtres du board | Évalués côté client sur les cartes chargées (testés en Vitest) ; le serveur ne stocke que les critères | Instantané, URL partageable, WIP compté hors filtres sans calcul serveur |
| 2026-10-14 | Virtualisation du board | `content-visibility: auto` sur les cartes au lieu d'une liste virtualisée | Natif, compatible dnd-kit ; à remplacer si la mesure 2 000 cartes (P8.6) dépasse le budget |
| 2026-10-14 | Dépôt dans une colonne multi-statuts | Premier statut de la colonne (dépôt par statut en V1) ; changer de couloir ne modifie pas le champ (V1) | Périmètre MVP |
| 2026-10-14 | Droits du board | Changer de colonne = TRANSITION ; réordonner = EDIT ; configurer = MANAGE_BOARD | Correspondance directe avec la matrice §2 |
| 2026-10-14 | Notifications | Listener synchrone dans la transaction métier (pas d'asynchrone, pas de broker) ; compteur rafraîchi par polling 30 s | Cohérence garantie, instance unique ; push temps réel avec STOMP en V1 |
| 2026-10-14 | Ticket supprimé | Notification DELETED sans lien vers le ticket (clé et résumé conservés dans le payload) ; les autres notifications du ticket disparaissent avec lui | Évite des liens morts |
| 2026-10-14 | Glisser au clavier | Espace saisit une carte (dnd-kit), Entrée l'ouvre ; Maj+N/P déplace directement d'une colonne | Spec §4.16 (Entrée = ouvrir) + alternative clavier §10 |
| 2026-10-14 | Secret | `FLUXO_SECRET` vide ⇒ l'application refuse de démarrer (contrôle applicatif, pas `:?` de Compose qui bloquait toutes les commandes) | Évite un déploiement avec la clé de dev |
| 2026-10-14 | E2E | Pile jetable `fluxo-e2e` sur le port 18080 (8081 pris par un autre projet local), base en tmpfs | Base neuve à chaque run, sans toucher la base de dev |
| 2026-10-14 | Contraste | `text-slate-400` remplacé par `text-slate-500` pour tout le texte | axe : 2,63:1 < 4,5:1 (WCAG AA) |
| 2026-10-14 | i18n | Le texte français est la clé (`t('Créer un ticket')`), anglais dans `i18n.en.ts` ; changement de langue = rechargement de page | Pas de bibliothèque, pas de fichier de clés abstraites à maintenir ; complétude vérifiée par test |
| 2026-10-14 | Données de référence | Types, priorités, résolutions et statuts par défaut restent en français (données, pas libellés d'interface) ; erreurs serveur en français, traduites côté front via leur `code` | L'écran d'administration de ces référentiels arrive en V1 |
| 2026-10-15 | Parseur FQL | Descente récursive écrite à la main (`search/fql/Fql.java`), compilée en `Specification<Ticket>` ; pas d'ANTLR | ~400 lignes, aucune dépendance ni génération de code ; erreurs positionnées faciles à produire |
| 2026-10-15 | FQL et plein texte | `~` sur summary/description/comment/text passe par la recherche plein texte existante (ensemble d'ids) ; `~` sur labels accepte `*` | Une seule mécanique de plein texte |
| 2026-10-15 | `priority > X` | « Plus urgent que X » (rang plus petit), comme Jira | Sens naturel pour l'utilisateur |
| 2026-10-15 | `IN fonction()` | `key in linkedTickets(…)` accepté sans parenthèses de liste | Syntaxe Jira habituelle |
| 2026-10-15 | Filtres rapides FQL | Évalués par le serveur (`GET /boards/{id}/match`), combinés côté client aux critères visuels ; tant que la réponse n'est pas arrivée, rien n'est masqué | Le FQL ne peut pas s'évaluer sur les cartes côté client |
| 2026-10-15 | Couloirs par requêtes | Le serveur calcule l'index du premier couloir correspondant (`lane` sur la carte) ; « Le reste » en dernier | Évalué une fois par chargement du board |
| 2026-10-15 | Couloir par label | Premier label par ordre alphabétique | Une carte n'apparaît qu'une fois |
| 2026-10-15 | Bascule basique → FQL | Les filtres visuels sont convertis en FQL ; la bascule inverse n'essaie pas de parser le FQL | Le FQL est plus expressif que les filtres visuels |
| 2026-10-15 | Temps réel | SSE natif (`SseEmitter`) au lieu de STOMP/WebSocket ; le message ne contient que type + clé, le client recharge via l'API habituelle | Pas de dépendance, passe les proxys HTTP, aucune donnée ne fuit hors des contrôles de droits |
| 2026-10-15 | Temps réel, droits | Espaces visibles mis en cache 1 min par connexion | Évite une requête par événement et par client ; un membre retiré peut recevoir des clés pendant 1 min au plus |
| 2026-10-15 | Temps réel, animation | Pas d'animation sur les cartes modifiées à distance | Cosmétique ; à ajouter si demandé |
| 2026-10-15 | Board multi-espaces | Le board garde un espace propriétaire (droits MANAGE_BOARD sur celui-ci) + des espaces additionnels ; on ne voit que les tickets des espaces qu'on peut parcourir ; une colonne peut regrouper des statuts de plusieurs workflows, et un dépôt prend le statut de la colonne qui appartient au workflow du ticket | Pas de nouveau modèle de droits ; les colonnes restent la seule configuration |
| 2026-10-15 | Jours dans la colonne | Calculés depuis le dernier changement de statut (historique), en jours ouvrés configurés par board ; affichés comme champ de carte « Jours dans la colonne » | Réutilise l'historique et le mécanisme des champs de carte ; un changement de statut au sein d'une même colonne remet le compteur à zéro |
| 2026-10-15 | Backlog Kanban | 1re colonne = Backlog, 2e = « Sélectionné » ; il faut au moins 3 colonnes. Édition inline : résumé, estimation, priorité, assigné. Sélection multiple reportée au lot BulkJob (V1.5), parent non éditable inline | Périmètre ; la sélection multiple n'a de sens qu'avec les actions en masse |
| 2026-10-15 | Colonnes repliées | Mémorisées dans le navigateur (localStorage par board) | Préférence d'affichage personnelle, pas une donnée partagée |
| 2026-10-15 | Glisser entre couloirs | Modifie assigné, priorité ou parent ; les autres modes (type, rapporteur, label, requêtes) ne changent aucun champ | Ce sont les seuls champs que la spec cite |
| 2026-10-15 | Tactile | `TouchSensor` de dnd-kit avec appui de 250 ms | Le défilement au doigt reste possible |
| 2026-10-15 | WIP bloquant | Vérifié par le serveur (409 `WIP_LIMIT`) et anticipé par le client | Le client évite l'aller-retour, le serveur fait foi |
| 2026-10-15 | Couleur par assigné | Teinte dérivée de l'identifiant | Stable, sans configuration |
| 2026-10-15 | Workflow simplifié | Un workflow sans transition reste « simplifié » (tout statut vers tout statut) ; « Définir des transitions » part d'une transition globale par statut | Compatibilité MVP sans migration de données |
| 2026-10-15 | Règles de transition | Conditions, validateurs, actions et écran stockés en JSON sur la transition (`rules`) ; transitions remplacées en bloc par l'éditeur | Un seul objet à valider et à afficher ; pas de tables par type de règle |
| 2026-10-15 | Plusieurs transitions vers un même statut | La première dont les conditions passent est utilisée (ordre de l'éditeur) | Le glisser-déposer ne vise qu'un statut |
| 2026-10-15 | Actions « fixer un champ » | Valeur en JSON (`3`, `true`, `["a"]`, `"2026-12-01"`), texte brut accepté comme chaîne ; mêmes contrôles qu'une édition manuelle | Réutilise la validation des champs du ticket |
| 2026-10-15 | Action webhook | Publiée comme événement après la transition ; la livraison arrive avec les webhooks (V1.8.2) | Un seul mécanisme d'envoi signé |
| 2026-10-15 | Workflow par type | « Workflow propre » = copie du workflow de l'espace ; tickets du type et colonnes de board suivent les statuts de même nom ; revenir au workflow de l'espace supprime la copie | Pas d'écran de correspondance de statuts à maintenir |
| 2026-10-15 | Suppression de statut utilisé | Les tickets passent au statut choisi en une requête, sans entrée d'historique | Opération d'administration rare |
| 2026-10-15 | Écran de transition | Résolution (vers Terminé), commentaire, pièces jointes (envoyées avant la transition) ; ouvert par le glisser, le clavier, le backlog et la fiche ; la palette de commandes ne propose que les transitions sans écran | Annuler l'écran laisse la carte en place |
| 2026-10-15 | Traitements en masse | Threads virtuels Java 21 + table `bulk_jobs`, une transaction par ticket, progression lue par polling (500 ms) | Pas de broker ; un refus n'annule pas les autres tickets |
| 2026-10-15 | Transition en masse | Par nom de statut, résolu dans le workflow de chaque ticket ; les règles de transition s'appliquent ticket par ticket et les refus vont au rapport | Couvre les tickets de workflows différents sans écran de correspondance |
| 2026-10-15 | Opérations composants / versions en masse | Ajoutées avec le lot V1.6.1 | Ces champs n'existent pas encore |
| 2026-10-15 | « Ne pas notifier » | Interrupteur par thread lu par le listener de notifications | Aucun changement dans les services métier |
| 2026-10-15 | Clonage | Copie fidèle du markdown ; commentaires recopiés avec auteur et date d'origine ; liens copiés dans les deux sens ; fichiers des PJ dupliqués | Le contenu riche est du markdown : rien à convertir |
| 2026-10-15 | Modèles | Valeurs limitées aux champs système éditables ; les valeurs saisies à la création priment sur le modèle | Les champs personnalisés s'ajouteront en V1.6.2 |
| 2026-10-15 | Versions publiées | Un ticket portant une version publiée ou archivée quitte le board, quelle que soit sa colonne | Règle simple, identique à Jira Kanban |
| 2026-10-15 | « Publier une version » | Concerne les tickets de l'espace propriétaire du board présents dans la dernière colonne | Une version appartient à un seul espace |
| 2026-10-15 | Renommage / fusion de labels | Appliqués en SQL sans entrée d'historique sur chaque ticket | Opération d'administration ; l'historique ticket resterait bruité |
| 2026-10-15 | Couleur du texte des labels | Noir ou blanc selon la luminance relative WCAG du fond | Contraste garanti quelle que soit la couleur choisie |
| 2026-10-15 | Entité « Version » | Classe Java `FixVersion` (table `versions`) | Évite le conflit avec `@jakarta.persistence.Version` |
| 2026-10-15 | Valeurs des champs personnalisés | Colonne JSONB `tickets.custom_values` (clé = id du champ) avec index GIN, pas de table EAV | Une seule lecture par ticket, validation applicative par type |
| 2026-10-15 | FQL sur champs personnalisés | Par nom (entre guillemets si espaces), évalué en SQL paramétré qui renvoie un ensemble d'ids | Pas de fonction JSON dans les critères JPA ; à optimiser si des requêtes sur champ personnalisé deviennent lentes |
| 2026-10-15 | Configuration des champs | Visible, obligatoire à la création, ordre, et défaut (champs personnalisés) par espace puis par type ; les défauts des champs système restent dans « valeurs par défaut » (MVP) ; l'écran ne saisit pas encore les défauts des champs personnalisés (API seulement) | Réutilise l'existant ; résumé toujours obligatoire |
| 2026-10-15 | Suppression de référentiels | Priorités et résolutions : remplacement obligatoire si utilisées ; types de tickets et de liens : refusée si utilisés ; la valeur par défaut ne se supprime pas | Aucune donnée orpheline |
| 2026-10-15 | Changement de niveau d'un type | Interdit après création | La hiérarchie des tickets existants resterait incohérente |
| 2026-10-15 | Commentaire restreint | Lisible par l'auteur, les membres du rôle et les admins d'espace ; on ne restreint qu'à un rôle que l'on a ; exclu de la recherche plein texte ; la notification « a commenté » part sans extrait | Aucune fuite du contenu ; l'existence d'un commentaire reste visible dans la notification |
| 2026-10-15 | Emails | Spring Mail actif seulement si `spring.mail.host` est défini ; file `email_outbox` vidée toutes les 30 s, un email par utilisateur et ticket dès que la première ligne a 2 min ; `In-Reply-To`/`References` = `<ticket-ID@fluxo>` ; texte en français | Pas de broker ; un échec d'envoi reste en file et sera retenté |
| 2026-10-15 | « Modification en masse terminée » | Rapport affiché dans le dialogue de suivi, pas de notification séparée | L'auteur suit déjà la progression à l'écran |
| 2026-10-15 | Renommage de PJ | Permis à qui peut la supprimer ; l'extension reste contrôlée | Même règle de droits que la suppression |
| 2026-10-15 | Jetons d'API | Préfixe `flx_`, SHA-256 en base, affiché une seule fois ; un jeton ne gère ni les jetons ni le mot de passe ; requêtes à jeton exemptées de CSRF (pas de cookie) | Pratique standard ; un jeton volé ne peut pas se perpétuer |
| 2026-10-15 | OpenAPI | springdoc 3, description `/api/openapi.json` et Swagger UI `/api/docs`, derrière l'authentification | Générée depuis le code ; pas de surface publique |
| 2026-10-15 | Webhooks | Charge utile construite dans la transaction, envoi après commit sur threads virtuels, 3 tentatives (1 s, 5 s), journal des 50 dernières livraisons ; URL saisies par l'admin seul (pas de filtrage SSRF) | Pas de broker ; à durcir si des non-admins configurent des webhooks |
| 2026-10-15 | Import CSV | Une transaction par ligne (annulée en simulation), délimiteur détecté (; , tabulation), résumé ramené sur une ligne, statut atteint par les transitions du workflow ; numéro de ligne = enregistrement CSV | Un échec n'annule pas les autres lignes ; règles métier identiques à la saisie |
| 2026-10-15 | CFD | Calcul serveur depuis l'historique des statuts, par jour dans le fuseau du navigateur ; graphique SVG sans bibliothèque | Pas de dépendance graphique pour un seul graphe |
| 2026-10-15 | Archivage | Booléen `archived` : hors board et hors recherche par défaut, visible avec `archived = true` en FQL, toujours ouvrable par sa clé | Distinct de la suppression, réversible |
| 2026-10-15 | Collaborateurs | Membres de l'espace, ajoutés automatiquement aux observateurs | Ils reçoivent les notifications du ticket |
| 2026-10-15 | Avatars | Image décodée côté serveur (seules les vraies images passent), recadrée au carré, PNG 128 px, URL versionnée mise en cache un an | Pas de SVG ni de fichier arbitraire servi |
| 2026-10-15 | Zoom des avatars | CSS `scale(2.5)` ancré en bas à droite, désactivé si « réduire les animations » | Reste visible dans les cartes malgré `content-visibility` |
| 2026-10-15 | Capture d'écran | `getDisplayMedia` : le navigateur fait choisir l'écran, la fenêtre ou l'onglet (multi-écran géré) ; une page ne peut pas réduire sa propre fenêtre, d'où un compte à rebours facultatif de 3 s (mémorisé) avant la prise de la vue | Seule voie possible dans un navigateur, sans extension |
| 2026-10-15 | Bouton « + » de cellule | Crée le ticket avec le champ du couloir (assigné, priorité, parent) puis l'amène dans la colonne par une transition autorisée | Respecte le workflow comme un glisser |
| 2026-10-15 | Défauts Sonar acceptés | SQL dynamique à partir de constantes, comptages `orElse(0L)`, CSRF exempté pour les jetons Bearer, `autoFocus` sur des champs ouverts par l'utilisateur, rôle de la carte fourni par dnd-kit : marqués dans SonarQube avec justification plutôt que contournés | Changer le code pour faire taire l'outil n'aurait rien amélioré |
| 2026-10-15 | Couverture | JaCoCo (back) et `@vitest/coverage-v8` (front) ajoutés ; les écrans React restent couverts par l'e2e, hors métrique Sonar | Seuls outils nécessaires à la quality gate |
