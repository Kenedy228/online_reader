#!/bin/sh
set -eu

env_file=${1:-.env}
case "$env_file" in
    /*) ;;
    *) env_file="./$env_file" ;;
esac

if [ -e "$env_file" ] || [ -L "$env_file" ]; then
    if [ ! -f "$env_file" ]; then
        printf 'Local env path must be a regular file.\n' >&2
        exit 1
    fi
    printf 'Existing local env preserved.\n'
    exit 0
fi

umask 077
temporary=$(mktemp "${env_file}.XXXXXX")
trap 'rm -f "$temporary"' EXIT
trap 'exit 1' HUP INT TERM
password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
if [ "${#password}" -ne 48 ]; then
    printf 'Could not generate the local database password.\n' >&2
    exit 1
fi

# The random value stays in shell memory and the mode-600 file, never CLI arguments.
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
        'SPRING_DATASOURCE_USERNAME=') printf '%s=%s\n' SPRING_DATASOURCE_USERNAME reader_local ;;
        'SPRING_DATASOURCE_PASSWORD=') printf '%s=%s\n' SPRING_DATASOURCE_PASSWORD "$password" ;;
        *) printf '%s\n' "$line" ;;
    esac
done < .env.example > "$temporary"

# Hard-link creation is atomic and refuses to replace an existing file.
ln "$temporary" "$env_file"
printf 'Private local env created with a random database password.\n'
