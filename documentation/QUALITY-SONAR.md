# Qualité du code — analyse SonarQube

*Analyse du 15/10/2026, demandée par `POST-IMPLEMENTATION.md` (Q.1).*

## Résultat

| Projet | Lignes | Bugs | Vulnérabilités | Code smells | Hotspots | Couverture | Duplication | Quality gate |
|---|---|---|---|---|---|---|---|---|
| Backend (Java) | 9 358 | 0 | 0 | 0 | 0 | 74,9 % (lignes 84 %) | 0,2 % | ✅ OK |
| Frontend (TS/React) | 7 512 | 0 | 0 | 0 | 0 | 18 % | 0 % | ✅ OK |

Notes de fiabilité, de sécurité et de maintenabilité : **A** sur les deux projets. Quality gate « Sonar way » (couverture du code nouveau ≥ 80 %, aucune nouvelle anomalie, duplication ≤ 3 %).

**99 défauts à la première analyse → 0 ouvert** : 76 corrigés dans le code, 23 examinés puis marqués dans SonarQube avec leur justification.

## Corrigés dans le code (76)

| Règle | Nb | Correction |
|---|---|---|
| `typescript:S9383` promesses ni attendues ni traitées | 64 | `void` explicite sur les invalidations de cache, navigations et rafraîchissements volontairement non attendus |
| `java:S2259` déréférencement potentiellement nul | 3 | `{"typeId": null}` provoquait une NullPointerException (**vrai bug**, maintenant 400) ; type MIME lu une seule fois ; `max(rank)` sur une table vide |
| `java:S6437` mot de passe en dur | 1 | Le hachage factice qui égalise les temps de réponse au login est dérivé d'une valeur aléatoire |
| `java:S1845` champs `fields` / `FIELDS` | 1 | Renommé `fieldService` |
| `java:S8786`, `typescript:S8786` expressions régulières à retour arrière super-linéaire | 3 | Estimation d'import : motif sans quantificateurs imbriqués ; résumé multiligne : `lines()` ; autocomplétion FQL : parcours linéaire |
| `typescript:S2871` tri sans comparateur | 2 | Jours ouvrés triés comme des chaînes (**vrai bug latent**) → tri numérique ; labels → `localeCompare` |
| `typescript:S6852` menu non focalisable | 1 | `tabIndex={-1}` sur le menu contextuel |
| `typescript:S6544` promesse là où un booléen est attendu | 1 | Prédicat de filtre explicitement booléen |
| `typescript:S7773` `parseInt` global | 1 | `Number.parseInt` |

## Examinés et acceptés (23, justification enregistrée dans SonarQube)

| Règle | Nb | Décision | Justification |
|---|---|---|---|
| `java:S2077` SQL formaté dynamiquement | 8 | Faux positif | Les fragments concaténés sont des constantes du code (noms de tables ou colonnes, branches d'un `switch`) ; toutes les valeurs sont des paramètres liés |
| `java:S2259` `orElse(0L)` possiblement nul | 6 | Faux positif | `count(*)` renvoie toujours une ligne ; `orElse(0L)` couvre le cas vide |
| `java:S4502` CSRF désactivé | 1 | Accepté | CSRF reste actif pour toute requête à cookie ; seules les requêtes `Authorization: Bearer` (jetons d'API, jamais envoyés automatiquement par un navigateur) en sont exemptées |
| `typescript:S9379` `autoFocus` | 6 | Accepté | Focus posé sur un champ que l'utilisateur vient d'ouvrir (édition en place, saisie rapide, dialogue), pas au chargement de la page |
| `typescript:S6848` élément interactif non natif | 1 | Faux positif | La carte reçoit `role="button"`, `tabIndex` et la gestion clavier via `useSortable` (dnd-kit) |

## Couverture

- **Backend** : JaCoCo ajouté au build (`mvn verify` produit `target/site/jacoco/jacoco.xml`). 84 % des lignes, 59 % des branches. Quatre tests de cas limites ajoutés (`EdgeCaseTests`) pour couvrir les branches d'erreur touchées par les corrections.
- **Frontend** : `@vitest/coverage-v8` ajouté. Les tests unitaires couvrent la logique pure (modèle du board, FQL, CFD, sélection, transitions…) ; les écrans sont couverts par les 10 parcours Playwright, qui n'entrent pas dans ce chiffre.

## Reproduire l'analyse

```bash
docker run -d --name fluxo-sonar -p 9000:9000 sonarqube:community     # serveur local
cd backend && ./mvnw verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<jeton> -Dsonar.projectKey=fluxo-backend
cd frontend && npx vitest run --coverage --coverage.reporter=lcov --coverage.include="src/**"
docker run --rm -e SONAR_HOST_URL=http://host.docker.internal:9000 -e SONAR_TOKEN=<jeton> \
  -v "$PWD:/usr/src" sonarsource/sonar-scanner-cli -Dsonar.projectKey=fluxo-frontend \
  -Dsonar.sources=src -Dsonar.tests=src -Dsonar.test.inclusions="**/*.test.ts,**/*.test.tsx" \
  -Dsonar.exclusions="**/*.test.ts,**/*.test.tsx" -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
```
Sous Windows, les chemins du rapport lcov doivent utiliser des `/` pour être reconnus par le scanner en conteneur.

## Pistes

1. Lancer l'analyse et la quality gate en CI à chaque fusion.
2. Tests de composants React (Testing Library) sur les écrans les plus riches (board, fiche ticket) pour faire monter la couverture front.
3. Ajouter les branches d'erreur restantes des services backend (59 % des branches couvertes).
