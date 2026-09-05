# syntax=docker/dockerfile:1
# ------------------------------------------------------------------
# Dockerfile pour api-gateway (decision R12, containerisation).
#
# IMPORTANT - CONTEXTE DE BUILD : construire avec la RACINE du monorepo
# comme contexte (pas services/api-gateway/), le module depend du
# parent multi-module et des modules partages (shared/core-dto,
# shared/security-common) :
#
#   docker build -f services/api-gateway/Dockerfile -t tontiflow/api-gateway:local .
#
# ATTENTION - MAVEN WRAPPER (decision R11 section 8, R12 section 11,
# NON RESOLU) : ce build utilise ./mvnw, ce qui suppose mvnw/mvnw.cmd/
# .mvn/ presents dans le contexte de build. Ces fichiers existent
# actuellement sur le disque local mais ne sont PAS suivis par Git.
# Un `git clone` frais suivi de ce meme `docker build` echouera
# ("mvnw: not found" / "permission denied") tant que ces fichiers ne
# sont pas commites. Ce Dockerfile herite de ce probleme, il ne le
# resout pas.
# ------------------------------------------------------------------

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 chmod +x ./mvnw && ./mvnw -pl services/api-gateway -am package -DskipTests -B --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S tontiflow && adduser -S tontiflow -G tontiflow
WORKDIR /app
COPY --from=build /workspace/services/api-gateway/target/*.jar app.jar
USER tontiflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
