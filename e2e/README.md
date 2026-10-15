# Tests de bout en bout

Parcours du §5 de la spec, sur l'image Docker avec une base vierge :

```bash
docker build -t fluxo:dev ..          # depuis la racine du dépôt
npm install && npx playwright install chromium
npx playwright test                   # démarre docker-compose.e2e.yml (port 18080), base neuve à chaque run
```

`FLUXO_URL=http://localhost:5180 npx playwright test` vise une instance déjà lancée (attention : le
parcours « premier lancement » exige une base vide). Rapport : `npx playwright show-report`.
Arrêt de la pile : `docker compose -f docker-compose.e2e.yml down -v`.
