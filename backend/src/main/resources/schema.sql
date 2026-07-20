-- Ensures the isolated schema exists before Hibernate's ddl-auto=update tries to create tables
-- in it. Only runs for Postgres/Neon (spring.sql.init.mode: always, set on the prod profile) —
-- harmless no-op extra schema for local H2 dev, where it isn't used by anything.
CREATE SCHEMA IF NOT EXISTS instalego;
