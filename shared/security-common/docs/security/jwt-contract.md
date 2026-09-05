# TontiFlow — Contrat JWT

**Version :** 1.0  
**Phase :** 2B-01  
**Statut :** Contractuel  
**Dernière mise à jour :** 2026-08-19

---

## 1. Objectif

Ce document définit le contrat technique commun utilisé par TontiFlow
pour l'émission, la validation et la propagation des informations
d'identité contenues dans les JSON Web Tokens (JWT).

Le contrat est partagé entre :

- `authentication-service`
- `security-common`
- `api-gateway`
- les microservices protégés

L'objectif est de garantir que tous les services interprètent un JWT
de manière identique.

---

## 2. Architecture de responsabilité

### Authentication Service

`authentication-service` est responsable de :

- authentifier l'utilisateur ;
- générer l'Access Token ;
- signer le JWT avec la clé privée RSA ;
- générer les claims conformes au présent contrat ;
- générer et gérer les Refresh Tokens.

### Security Common

`security-common` fournit le vocabulaire partagé, pas la vérification
elle-même :

- `JwtClaimNames` : noms des claims du contrat ;
- `BearerTokenExtractor` : extraction du Bearer Token depuis le header
  `Authorization` ;
- `UserContext` : contrat d'identité immuable, reconstruit à partir des
  claims validés.

**État réel (vérifié par audit, 2026-08-28)** : `security-common` ne
contient ni vérification de signature, ni filtre HTTP. Chaque consommateur
du JWT réimplémente localement, à l'identique, la vérification RS256
(clé publique uniquement) :

- `JwtVerifier` — dupliqué à l'identique dans `user-service`,
  `tontine-service`, `financial-service`, `credit-service`,
  `notification-service` (seule la javadoc diffère d'un service à l'autre) ;
- `GatewayJwtVerifier` — même logique de vérification, côté `api-gateway`
  (classe distincte car l'intégration Spring Security y est réactive/WebFlux,
  incompatible avec le filtre Servlet des microservices) ;
- `AccessTokenService` — côté `authentication-service`, qui signe *et*
  valide (rôle de producteur, distinct des consommateurs ci-dessus).

Cette duplication est un choix assumé pour l'instant, pas une erreur :
chaque service reste vérifiable indépendamment, sans dépendance partagée
sur une classe de vérification. Une centralisation dans `security-common`
reste possible (la logique de vérification pure n'a aucune dépendance
Servlet/WebFlux) mais n'a pas été effectuée, faute de justification
architecturale suffisante à ce jour.

`security-common` ne doit pas être responsable de l'authentification
métier de l'utilisateur.

### API Gateway

Le Gateway est responsable de :

- recevoir le JWT ;
- authentifier la requête localement (`GatewayJwtAuthenticationWebFilter` /
  `GatewayJwtVerifier`) avant routage ;
- empêcher qu'un client externe puisse imposer des headers d'identité
  non fiables.

**État réel (vérifié par audit, 2026-08-28)** : le Gateway ne réécrit ni
ne propage aucun header d'identité (`X-User-*` ou autre) vers les
microservices — les routes ne définissent aucun filtre
`AddRequestHeader`/`RemoveRequestHeader`. Chaque microservice revalide donc
indépendamment le JWT reçu tel quel ; la mention précédente de « transmettre
les informations d'identité validées » décrivait une propagation par
header qui n'a jamais été implémentée. La génération/propagation d'un
`X-Correlation-ID` n'existe pas non plus actuellement.

### Microservices

Les microservices utilisent uniquement l'identité reconstruite depuis leur
propre vérification du JWT (voir « Security Common » ci-dessus).

Ils ne doivent jamais considérer les headers :

```text
X-User-Id
X-User-Roles
X-User-Permissions
```