#!/bin/sh
#
# Creates the role the application connects as at runtime.
#
# This is infrastructure, not schema, for the same reason the SQS queues live
# in elasticmq.conf rather than in the application: it carries a credential,
# and CLAUDE.md §5 keeps credentials out of db/migration, which runs in every
# environment. The privileges and the row-level security policies that go with
# this role *are* schema, and live in Flyway migrations that reference it by
# name.
#
# Why a second role at all: POSTGRES_USER is created by this image as a
# SUPERUSER, and it owns every table Flyway creates. Postgres exempts both
# superusers and table owners from row-level security, so enabling RLS while
# the application connects as that role would be a silent no-op. Flyway keeps
# using the owner (so a data-backfill migration is never filtered to zero
# rows - see V3); the application uses this role, which is exempt from
# nothing.
#
# A .sh rather than a .sql because /docker-entrypoint-initdb.d/*.sql is fed to
# psql verbatim, with no way to get the password in from the environment.
# The password is passed as a psql variable and interpolated with :'...', so
# psql quotes and escapes it rather than the shell.
#
# Runs only when the data directory is empty. An existing local database
# therefore will NOT get this role, and the grant migration will fail loudly
# at startup until it does - see README, "Start the infrastructure".
set -e

: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
     -v app_password="$POSTGRES_APP_PASSWORD" \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" <<'EOSQL'
CREATE ROLE subscription_hub_app
    LOGIN
    PASSWORD :'app_password'
    NOSUPERUSER
    NOBYPASSRLS
    NOCREATEDB
    NOCREATEROLE
    NOINHERIT;
EOSQL
