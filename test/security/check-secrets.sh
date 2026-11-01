#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/dostoread-secrets.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
mkdir -p "$test_dir/main/resources" "$test_dir/scripts"
cp "$repo_root/.gitleaks.toml" "$test_dir/.gitleaks.toml"
cp "$repo_root/scripts/check-secrets.sh" "$test_dir/scripts/check-secrets.sh"
printf '.env\n' > "$test_dir/.gitignore"
git -C "$test_dir" init --quiet
fixture=safe-controls

scan_expect() {
    expected=$1
    mode=${2:-}
    set +e
    (cd "$test_dir" && sh scripts/check-secrets.sh ${mode:+"$mode"}) > "$test_dir/check.log" 2>&1
    actual=$?
    set -e
    if [ "$actual" -ne "$expected" ]; then
        printf 'Secret check failed: fixture=%s mode=%s expected=%s actual=%s\n' "$fixture" "$mode" "$expected" "$actual" >&2
        exit 1
    fi
}

stage() {
    git -C "$test_dir" add --all
}

cat > "$test_dir/main/resources/application.properties" <<'PROPERTIES'
spring.mail.username=${SPRING_MAIL_USERNAME}
spring.mail.password=${SPRING_MAIL_PASSWORD}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
PROPERTIES
printf '%s\n' 'spring.mail.password =' 'spring.mail.username : ' \
    >> "$test_dir/main/resources/application.properties"
cat > "$test_dir/main/resources/application-test.properties" <<'PROPERTIES'
spring.datasource.username=sa
spring.datasource.password=password
PROPERTIES
printf '%s\n' 'SPRING_MAIL_PASSWORD=' > "$test_dir/.env.example"
stage
printf '%s\n' 'SPRING_MAIL_PASSWORD=not-a-real-secret' > "$test_dir/.env"
# The default local check must not inspect an ignored operator env file.
scan_expect 0
rm "$test_dir/.env"
scan_expect 0 snapshot

for unsafe_property in \
    'spring.mail.password=not-a-real-secret' \
    'spring.datasource.password: not-a-real-secret' \
    'spring.datasource.password not-a-real-secret' \
    'spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:not-a-real-secret}' \
    'spring.mail.username=example-user'
do
    fixture=property-literal
    printf '%s\n' "$unsafe_property" > "$test_dir/main/resources/application-preview.properties"
    scan_expect 3 snapshot
    stage
    scan_expect 3 staged
done
rm "$test_dir/main/resources/application-preview.properties"
stage
scan_expect 0 snapshot
scan_expect 0 staged

printf '%s\n' 'SPRING_MAIL_PASSWORD=not-a-real-secret' > "$test_dir/.env.example"
fixture=environment-literal
scan_expect 3 snapshot
stage
scan_expect 3 staged
printf '%s\n' 'SPRING_MAIL_PASSWORD=' > "$test_dir/.env.example"
stage
scan_expect 0 snapshot
scan_expect 0 staged

printf '%s\n' 'spring.datasource.password=different-fake-secret' \
    > "$test_dir/main/resources/application-test.properties"
fixture=changed-test-credential
scan_expect 3 snapshot
stage
scan_expect 3 staged
printf 'Secret checks: safe controls passed; 7 unsafe fixtures rejected in both modes\n'
