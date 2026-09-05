# TontiFlow — Modern Tontine & Financial Platform

TontiFlow est une plateforme microservices distribuée de gestion de tontines, d'épargne communautaire, de paiements Mobile Money, de crédit et de solidarité.

## Architecture & Topology

L'architecture repose sur un modèle hybride pragmatique composé de 6 microservices répartis sur des bases de données isolées (Database-per-Service) et communicant via REST (Synchrone) et RabbitMQ (Asynchrone).

## Exécution en conteneurs Docker (décision R12)

Cette stack (`docker-compose.app.yml`) est distincte du `docker-compose.yml` existant
(PostgreSQL de développement local, jamais modifié) : elle contient son propre
PostgreSQL applicatif isolé et les 9 services buildés depuis leurs `Dockerfile`
respectifs. `config-service` et `discovery-service` sont démarrés pour
complétude mais ne sont réellement requis par aucun autre service (cf. audit
R12) — aucune dépendance de démarrage artificielle n'est créée vers eux.

### Prérequis

- Docker et Docker Compose (plugin v2).
- Une paire de clés RSA pour la signature/vérification JWT (voir
  [docker/keys/README.md](docker/keys/README.md)) :

```bash
mkdir -p docker/keys
openssl genrsa -out docker/keys/private_key.pem 2048
openssl rsa -in docker/keys/private_key.pem -pubout -out docker/keys/public_key.pem
```

### Variables d'environnement

Copier le template et renseigner des valeurs réelles (`.env.app` n'est jamais
commité — voir `.gitignore`) :

```bash
cp .env.app.example .env.app
```

Variables obligatoires (le démarrage échoue explicitement si absentes) :
`POSTGRES_USER`, `POSTGRES_PASSWORD`, `EUREKA_USERNAME`, `EUREKA_PASSWORD`,
`CONFIG_SERVER_USERNAME`, `CONFIG_SERVER_PASSWORD`. `GATEWAY_HOST_PORT` est
optionnelle (défaut `8080` ; ajuster si ce port est déjà occupé sur votre
machine, comme observé pendant la validation R12).

### Construire et démarrer

```bash
docker compose --env-file .env.app -f docker-compose.app.yml up -d --build
```

Le démarrage complet des 9 services (JVM Spring Boot) prend usuellement
60 à 90 secondes après le démarrage de PostgreSQL — c'est la valeur réelle
observée pendant la validation R12 et la raison des délais de `healthcheck`
définis dans `docker-compose.app.yml`.

### Vérifier l'état

```bash
docker compose --env-file .env.app -f docker-compose.app.yml ps
```

Tous les services applicatifs doivent afficher `(healthy)` (`config-service`
et `discovery-service` n'ont pas de healthcheck configuré — absence
d'endpoint métier dépendant, cf. audit R12).

### Accéder à l'application

Seul `api-gateway` publie un port vers l'hôte (aucun autre service, y compris
PostgreSQL, n'est accessible depuis l'hôte) :

```
http://localhost:${GATEWAY_HOST_PORT:-8080}/api/v1/...
```

### Consulter les logs

```bash
docker compose --env-file .env.app -f docker-compose.app.yml logs -f <nom-du-service>
```

### Arrêter la stack

```bash
docker compose --env-file .env.app -f docker-compose.app.yml down
```

Conserve les données (le volume `tontiflow_app_postgres_data` persiste).

### ⚠️ Réinitialiser les données (destructif, irréversible)

```bash
docker compose --env-file .env.app -f docker-compose.app.yml down -v
```

Supprime définitivement le volume PostgreSQL applicatif de cette stack. Sans
effet sur le PostgreSQL de développement local (`docker-compose.yml`), qui
utilise un volume distinct.