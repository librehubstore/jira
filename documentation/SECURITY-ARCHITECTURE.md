# Sécurité de l'architecture — revue et plan d'amélioration

*Revue du 15/10/2026, demandée par `POST-IMPLEMENTATION.md` : authentification, flux, autorisations, données.*

## Vue d'ensemble des flux

```
Navigateur ──HTTPS(*)──▶ Spring Boot (Tomcat) ──JDBC──▶ PostgreSQL
   │  SPA React (même origine)      │
   │  cookie de session + CSRF      ├──▶ disque : pièces jointes, avatars (volume /data)
   │  SSE /api/events               ├──▶ SMTP (si configuré) : emails de notification
Client API ──Bearer flx_…──▶        └──▶ URL de webhooks (configurées par l'admin)
```
(*) TLS est attendu en frontal (reverse proxy) ; l'application elle-même écoute en HTTP sur 8080.

Une seule origine sert l'interface et l'API : pas de CORS ouvert. Les seuls flux sortants sont SMTP et les webhooks.

## Constats

Légende : ✅ en place · ⚠️ amélioration recommandée · ❌ manque à combler

### Authentification

| Point | État | Détail |
|---|---|---|
| Mots de passe | ✅ | BCrypt ; 10 caractères minimum ; mot de passe temporaire à changer, l'API reste bloquée tant qu'il ne l'est pas |
| Force brute | ✅ / ⚠️ | Verrouillage du compte 15 min après 5 échecs. Pas de limitation par IP : un attaquant peut verrouiller volontairement des comptes (déni de service ciblé) |
| Sessions | ✅ | Session HTTP 8 h, invalidée si le compte est désactivé (rechargement de l'utilisateur à chaque requête) ; identifiant renouvelé à la connexion (défaut Spring Security) |
| « Se souvenir de moi » | ✅ | Cookie signé 30 jours, invalidé par un changement de mot de passe ; refus de démarrer si `FLUXO_SECRET` est vide |
| Secret par défaut | ⚠️ | Hors Docker, si `FLUXO_SECRET` n'est pas défini du tout, la valeur de développement de `application.yml` s'applique silencieusement |
| Jetons d'API | ✅ | `flx_` + 256 bits aléatoires, seul le SHA-256 est stocké, affichage unique, expiration, révocation, portée lecture seule ; un jeton ne peut ni créer de jetons ni changer le mot de passe |
| Comptes | ✅ | Pas d'inscription publique ; création, désactivation, réinitialisation par l'admin ; journal d'audit |
| MFA / SSO | ❌ | Hors périmètre de la spec (usage interne) ; à considérer si l'outil est exposé hors du réseau interne |

### Autorisations

| Point | État | Détail |
|---|---|---|
| Modèle | ✅ | Permissions par rôle d'espace (`PermissionService`) vérifiées côté serveur sur chaque action ; l'UI ne fait que masquer |
| Fuite d'existence | ✅ | Espace ou ticket invisible → 404, pas 403 |
| Recherche / FQL | ✅ | Toujours restreinte aux espaces visibles ; FQL compilé en requêtes paramétrées (jamais de concaténation) ; commentaires restreints exclus du plein texte |
| Temps réel (SSE) | ✅ / ⚠️ | Signaux filtrés par espaces visibles, contenu limité à type + clé ; liste des espaces mise en cache 1 min par connexion |
| Administration | ✅ | `/api/admin/**` exige le rôle ADMIN (filtre + contrôle dans les services) |
| Traitements en masse | ✅ | Permission vérifiée ticket par ticket, refus reportés dans le rapport |
| Workflows | ✅ | Conditions de transition évaluées côté serveur (API comprise : 403) |

### Protection des requêtes et du navigateur

| Point | État | Détail |
|---|---|---|
| CSRF | ✅ | Jeton CSRF (mode SPA) sur toute écriture par cookie de session ; requêtes à jeton Bearer exemptées (pas de cookie) |
| XSS | ✅ | Markdown rendu puis assaini par DOMPurify ; React échappe le reste. **Ajouté** : `Content-Security-Policy` (`script-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`…) ; le script de thème a été sorti d'`index.html` pour se passer de scripts en ligne |
| Clickjacking | ✅ | `X-Frame-Options: DENY` + `frame-ancestors 'none'` |
| En-têtes | ✅ | `X-Content-Type-Options: nosniff`, **ajoutés** `Referrer-Policy: same-origin` et `Permissions-Policy` (caméra, micro, géolocalisation coupés ; capture d'écran limitée au site) |
| `style-src 'unsafe-inline'` | ⚠️ | Nécessaire aux attributs `style` de React (couleurs dynamiques) ; risque faible (pas de script) |
| Export CSV | ✅ | Cellules commençant par `= + - @` neutralisées (injection de formules) |

### Fichiers

| Point | État | Détail |
|---|---|---|
| Pièces jointes | ✅ | Taille maximale configurable (25 Mo), extensions exécutables bloquées, stockage sous un nom aléatoire (pas de traversée de chemin), téléchargement contrôlé par les droits, `Content-Security-Policy: sandbox` + `nosniff`, seuls images et PDF s'affichent en ligne |
| Avatars | ✅ | Image décodée côté serveur puis réécrite en PNG 128 px : un fichier non-image ou piégé ne passe pas |
| Antivirus | ❌ | Aucune analyse des pièces jointes |
| Import CSV | ✅ | Même chemin de création que l'interface (validations, droits), 5 000 lignes max., une transaction par ligne |

### Flux sortants

| Point | État | Détail |
|---|---|---|
| Webhooks | ✅ / ⚠️ | Signature HMAC-SHA256, délai de 10 s, 3 tentatives, journal. URL configurées par l'admin uniquement : pas de filtrage des adresses internes (risque SSRF si des non-admins pouvaient un jour en créer) ; le secret de signature s'affiche en clair dans l'écran d'administration |
| Action « Envoyer un webhook » | ⚠️ | Configurée par un admin d'espace dans le workflow : un admin d'espace peut donc faire émettre des requêtes POST vers une URL de son choix (y compris interne) |
| Emails | ✅ | Seulement si SMTP est configuré ; pas de contenu de commentaire restreint |

### Données, journaux, exploitation

| Point | État | Détail |
|---|---|---|
| Injection SQL | ✅ | JPA Criteria et `JdbcClient` paramétrés ; les rares noms de tables concaténés sont des constantes du code |
| Erreurs | ✅ | RFC 9457 sans trace de pile |
| Actuator | ✅ | Seul `/actuator/health` exposé |
| Documentation API | ✅ | `/api/docs` et `/api/openapi.json` derrière l'authentification |
| Audit | ✅ / ⚠️ | Journal d'audit des actions d'administration ; pas de journal des connexions réussies ni des créations de jetons |
| Chiffrement au repos | ❌ | Base et volume de pièces jointes non chiffrés par l'application (à confier au stockage) |
| Transport | ⚠️ | HTTPS délégué au reverse proxy ; cookies sans attribut `Secure` forcé, pas de HSTS si l'application ne voit pas le HTTPS |
| Sauvegardes | ⚠️ | Non décrites (base + volume `/data`) |
| RGPD | ⚠️ | Désactivation des comptes en place ; anonymisation et export des données d'un utilisateur prévus par la spec mais non implémentés |

## Corrections appliquées pendant la revue

1. En-têtes `Content-Security-Policy`, `Referrer-Policy`, `Permissions-Policy` sur toutes les réponses (`SecurityConfig`) ; test `SecurityHeadersTests`.
2. Script de thème déplacé dans `public/theme.js` (plus aucun script en ligne).
3. Dépendances vulnérables montées (voir `SECURITY-DEPENDENCIES.md`).

## Plan d'amélioration

| # | Action | Priorité | Effort |
|---|---|---|---|
| A1 | Refuser de démarrer avec la valeur de développement de `fluxo.secret` hors profil `dev` | Haute | 1 h |
| A2 | Forcer les cookies `Secure` et `SameSite=Lax`, activer HSTS derrière le proxy (`server.forward-headers-strategy=framework`) ; documenter la configuration TLS du reverse proxy | Haute | 2 h |
| A3 | Limiter le débit des connexions par IP (ex. Bucket4j ou au niveau du proxy) en plus du verrouillage par compte | Haute | ½ j |
| A4 | Filtrer les URL de webhooks et de l'action de transition : refuser les adresses privées, loopback et link-local (résolution DNS comprise), ou les réserver au seul admin global | Haute | ½ j |
| A5 | Analyse antivirus des pièces jointes (ClamAV en conteneur, analyse à l'envoi) | Moyenne | 1 j |
| A6 | Journaliser dans l'audit : connexions (réussies et échouées), créations / révocations de jetons, modifications de webhooks | Moyenne | ½ j |
| A7 | Masquer le secret des webhooks après création (affichage unique, comme les jetons) et permettre sa rotation | Moyenne | 2 h |
| A8 | Implémenter l'export et l'anonymisation RGPD d'un compte (spec §11) | Moyenne | 1 j |
| A9 | Procédure de sauvegarde / restauration (pg_dump + volume `/data`) testée | Moyenne | ½ j |
| A10 | Remplacer les attributs `style` dynamiques par des variables CSS pour retirer `'unsafe-inline'` de `style-src` | Faible | 1 j |
| A11 | MFA (TOTP) si l'outil sort du réseau interne | Faible | 2 j |
| A12 | Tests de sécurité automatisés en CI (OWASP ZAP baseline sur l'image e2e) | Faible | ½ j |
