#!/usr/bin/env bash
set -euo pipefail

# Runs only while MySQL initializes an empty data directory. The application and
# migration accounts are fixed names so Flyway migration V3 can grant the exact
# runtime table privileges after the schema exists. Passwords are never baked
# into the image or the repository; they come from the environment.
for name in MYSQL_ROOT_PASSWORD MYSQL_DATABASE MYSQL_APP_PASSWORD MYSQL_MIGRATION_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    echo "missing required environment variable: $name" >&2
    exit 1
  fi
done

if [[ ! "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_DATABASE must contain only letters, digits and underscores" >&2
  exit 1
fi

case "$MYSQL_APP_PASSWORD$MYSQL_MIGRATION_PASSWORD" in
  *$'\n'*|*$'\r'*)
    echo "database passwords must not contain newlines" >&2
    exit 1
    ;;
esac

sql_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g"
}

app_password=$(sql_escape "$MYSQL_APP_PASSWORD")
migration_password=$(sql_escape "$MYSQL_MIGRATION_PASSWORD")

# The migration account needs schema-level privileges and GRANT OPTION so V3 can
# delegate only the reviewed runtime privileges to the application account. It is
# never used by the long-running backend service.
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root --batch <<SQL
DROP USER IF EXISTS 'approval_app'@'%';
DROP USER IF EXISTS 'approval_migrator'@'%';
CREATE USER 'approval_app'@'%' IDENTIFIED BY '${app_password}';
CREATE USER 'approval_migrator'@'%' IDENTIFIED BY '${migration_password}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO 'approval_migrator'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL

echo "database accounts created: approval_app and approval_migrator"
