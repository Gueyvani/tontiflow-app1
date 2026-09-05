-- Script d'initialisation idempotent des bases de donnees TontiFlow
-- Ce script cree les 5 bases isolees requis par le pattern Database-per-Service.
-- Aucune table metier n'est creee ici (responsabilite de Flyway).

CREATE DATABASE user_db;
CREATE DATABASE tontine_db;
CREATE DATABASE authentication_db;
CREATE DATABASE config_db;
CREATE DATABASE credit_db;
CREATE DATABASE discovery_db;
CREATE DATABASE financial_db;
CREATE DATABASE notification_db;