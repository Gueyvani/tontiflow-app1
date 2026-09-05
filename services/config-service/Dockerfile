# syntax=docker/dockerfile:1
# ------------------------------------------------------------------
# Dockerfile pour config-service (decision R12, containerisation).
#
# IMPORTANT - CONTEXTE DE BUILD : construire avec la RACINE du monorepo
# comme contexte (le module fait partie du reacteur services/pom.xml,
# meme s'il herite de son propre parent spring-boot-starter-parent,
# distinct du parent com.tontiflow) :
#
#   docker build -f services/config-service/Dockerfile -t tontiflow/config-service:local .
#
# ATTENTION - MAVEN WRAPPER (decision R11 section 8, R12 section 11,
# NON RESOLU) : ce build utilise ./mvnw, ce qui suppose mvnw/mvnw.cmd/
# .mvn/ presents dans le contexte de build. Ces fichiers existent
# actuellement sur le disque local mais ne sont PAS suivis par Git.
# Un `git clone` frais suivi de ce meme `docker build` echouera
# ("mvnw: not found" / "permission denied") tant que ces fichiers ne
# sont pas commites. Ce Dockerfile herite de ce probleme, il ne le
# resout pas.
#
# IMPORTANT - decouverte de l'audit R12 : config-service n'est
# reellement consomme par AUCUN autre service (aucun spring-cloud-
# config-client, aucun spring.config.import nulle part) - il est
# containerise pour completude du parc, sans creer de dependance de
# demarrage artificielle dans docker-compose.app.yml.
# ------------------------------------------------------------------

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 chmod +x ./mvnw && ./mvnw -pl services/config-service -am package -DskipTests -B --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S tontiflow && adduser -S tontiflow -G tontiflow
WORKDIR /app
COPY --from=build /workspace/services/config-service/target/*.jar app.jar
USER tontiflow
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
