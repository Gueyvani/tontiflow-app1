# Clés JWT pour l'exécution Docker (décision R12)

Ce répertoire est le point de montage attendu par `docker-compose.app.yml`
pour les clés RSA JWT (RS256) :

```
docker/keys/private_key.pem   # authentication-service uniquement (signature)
docker/keys/public_key.pem    # tous les services (vérification)
```

**Ces fichiers ne sont jamais committés** (`*.pem`/`*.key` exclus par
`.gitignore` à la racine du dépôt) — ce répertoire reste vide dans le dépôt
Git lui-même.

## Générer une paire de clés locale (développement/validation uniquement)

```bash
mkdir -p docker/keys
openssl genrsa -out docker/keys/private_key.pem 2048
openssl rsa -in docker/keys/private_key.pem -pubout -out docker/keys/public_key.pem
```

## Mécanisme

`docker-compose.app.yml` monte ce répertoire en lecture seule dans chaque
conteneur (`./docker/keys:/keys:ro`) et positionne :

- `JWT_PUBLIC_KEY_PATH=file:/keys/public_key.pem` (tous les services)
- `JWT_PRIVATE_KEY_PATH=file:/keys/private_key.pem` (authentication-service
  uniquement — c'est le seul service qui signe des tokens)

Ce mécanisme est strictement local à votre machine — pour un déploiement
réel, remplacez ce montage de volume par le mécanisme de secrets de votre
plateforme cible (non traité par cette phase, hors périmètre R12).
