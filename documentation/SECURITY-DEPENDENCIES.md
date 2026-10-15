# Sécurité des dépendances — analyse et plan d'amélioration

*Analyse du 15/10/2026, demandée par `POST-IMPLEMENTATION.md`.*

## Méthode

| Périmètre | Outil | Détail |
|---|---|---|
| Front (`frontend/`, 273 paquets) | `npm audit` | Dépendances de production et de développement |
| E2E (`e2e/`, 5 paquets) | `npm audit` | Idem |
| Back (`backend/`, 117 artefacts runtime) | `mvn dependency:list` + API [OSV](https://osv.dev) (`/v1/querybatch`) | Base qui agrège les avis GitHub (GHSA) et les CVE |
| Images Docker | Lecture du `Dockerfile` et des fichiers compose | Pas de scanner d'image exécuté (voir plan) |
| Chaîne d'approvisionnement | Lecture des lockfiles, du wrapper Maven, des scripts d'installation | Provenance et intégrité |

## Résultats

### Front et e2e : aucune vulnérabilité connue

`npm audit` : 0 info, 0 faible, 0 modérée, 0 haute, 0 critique (front et e2e, toutes dépendances).

Versions en retard sans avis de sécurité : `react` / `react-dom` 19.3.0 → 19.3.1, `@tanstack/react-query` 5.104.2 → 5.104.3 (correctifs mineurs), `typescript` 6 → 7 et `@types/node` 24 → 26 (versions majeures, à planifier).

### Back : 10 avis trouvés, **tous corrigés**

| Composant | Version | Avis | Gravité | Correction |
|---|---|---|---|---|
| Tomcat embarqué | 11.0.24 | GHSA-9xv2-5v5q-p794 (authentification DIGEST rejouable) | Critique | 11.0.27 |
| | | GHSA-gcx9-497g-6cp6 (contrôle d'accès incorrect) | Critique | |
| | | GHSA-h3x4-894j-xpx5 (authentification FORM) | Critique | |
| Jackson 3 `jackson-databind` | 3.1.5 | GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54, GHSA-q4xh-88c3-wmh7 (déni de service) | Haute | 3.1.7 |
| | | GHSA-gx83-3vf8-gh7j, GHSA-wjgm-6hv5-3cvf (validateur polymorphe, `Path`) | Modérée | |
| Jackson 3 `jackson-core` | 3.1.5 | GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 (DoS, ReDoS) | Haute | 3.1.7 |
| Jackson 2 (via springdoc) | 2.21.5 | Mêmes avis que ci-dessus | Haute | 2.21.7 |

**Exposition réelle** : Fluxo n'utilise ni l'authentification DIGEST ni FORM de Tomcat (Spring Security gère la connexion) et n'active pas le typage polymorphe de Jackson ; les avis de déni de service sur l'analyse JSON restaient en revanche exploitables par tout utilisateur connecté. **Correction appliquée** dans `backend/pom.xml` (propriétés `tomcat.version`, `jackson-bom.version`, `jackson-2-bom.version`, en attendant un Spring Boot 4.1.2). Nouvelle analyse OSV : **0 vulnérabilité** sur les 117 artefacts. Tests : backend 119, front 22, e2e 10/10 verts.

### Chaîne d'approvisionnement

| Point | Constat |
|---|---|
| Lockfiles npm | Les 278 paquets viennent de `registry.npmjs.org`, tous avec empreinte `integrity` (vérifiée par `npm ci`) |
| Scripts d'installation npm | Un seul paquet en déclare : `fsevents` (macOS, optionnel, non installé sous Windows/Linux) |
| Dépendance inutilisée | `@tanstack/react-virtual` n'était plus utilisée : **retirée** |
| Wrapper Maven | Télécharge Maven 3.10.0 depuis `repo.maven.apache.org` ; pas de `distributionSha256Sum` |
| Plugins Maven | Uniquement `spring-boot-maven-plugin` |
| Images Docker | `node:24-alpine`, `eclipse-temurin:21-jdk/jre`, `postgres:16` : étiquettes flottantes, non épinglées par empreinte |
| Exécution du conteneur | Utilisateur non-root `fluxo` (uid 1001) ✔ |

### Dépendances front à surveiller

| Paquet | Remarque |
|---|---|
| `tiptap-markdown` 0.9 | Projet peu actif ; c'est lui qui convertit le markdown de l'éditeur riche |
| `marked` + `dompurify` | Le HTML produit par `marked` est toujours assaini par DOMPurify avant affichage : à garder ensemble |

## Plan d'amélioration

| # | Action | Priorité | Effort |
|---|---|---|---|
| D1 | ✅ Monter Tomcat 11.0.27, Jackson 3.1.7 / 2.21.7 | Critique | Fait |
| D2 | ✅ Retirer `@tanstack/react-virtual` | Faible | Fait |
| D3 | Supprimer les surcharges de versions du `pom.xml` dès que Spring Boot ≥ 4.1.2 les embarque (sinon elles bloqueraient de futurs correctifs) | Haute | 5 min |
| D4 | Ajouter en CI un contrôle bloquant : `npm audit --audit-level=high` (front, e2e) et OWASP Dependency-Check ou `osv-scanner` (back) | Haute | ½ j |
| D5 | Scanner les images produites (Trivy ou Grype) en CI ; reconstruire l'image chaque semaine pour récupérer les correctifs de l'OS de base | Haute | ½ j |
| D6 | Épingler les images de base par empreinte (`eclipse-temurin:21-jre@sha256:…`) et les mettre à jour via Renovate/Dependabot | Moyenne | 2 h |
| D7 | Ajouter `distributionSha256Sum` dans `maven-wrapper.properties` | Moyenne | 10 min |
| D8 | Activer Renovate ou Dependabot (npm, Maven, Docker) avec fusion automatique des correctifs | Moyenne | 2 h |
| D9 | Appliquer les correctifs mineurs (`react` 19.3.1, `@tanstack/react-query` 5.104.3) ; planifier TypeScript 7 | Faible | 1 h |
| D10 | Évaluer un remplaçant maintenu de `tiptap-markdown` (extension markdown officielle de TipTap) | Faible | 1 j |
| D11 | Produire un SBOM (CycloneDX Maven + npm) joint à chaque image | Faible | 2 h |
