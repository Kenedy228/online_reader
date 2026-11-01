# Локальная PostgreSQL и приложение в Docker

Это окружение для разработки и проверки. Деплой и публикация образов не выполняются.
Нужны Docker Engine, Compose v2.20+ и BuildKit; команды выполняются из корня проекта.
Maven, Node.js и npm на хосте для Docker build не требуются.

## Переменные и данные

Создайте приватный файл: `umask 077`, затем `cp .env.example .env`.
Задайте собственные локальные `SPRING_DATASOURCE_USERNAME` и
`SPRING_DATASOURCE_PASSWORD`. Пустые credentials блокируют Compose до старта.
Значения с `$`, `#`, пробелами и shell-символами заключайте в одинарные кавычки.
Не используйте старые опубликованные credentials или пароли внешних сервисов.

| Параметр | По умолчанию | Назначение |
| --- | --- | --- |
| `POSTGRES_DB` | `online_reader` | Имя локальной БД |
| `POSTGRES_PORT` | `5432` | PostgreSQL на `127.0.0.1` хоста |
| `APP_PORT` | `8080` | Приложение на `127.0.0.1` хоста |
| `SPRING_DATASOURCE_USERNAME/PASSWORD` | Нет | Начальная локальная учётная запись и доступ приложения |

PostgreSQL использует официальный образ 17.11 с immutable digest и named volume
`postgres-data`, изолированный Compose project name. Не меняйте major-версию образа
поверх существующего volume без процедуры PostgreSQL upgrade.
Начальные имя БД/user/password применяются entrypoint только к новому volume:
редактирование `.env` не переименует существующую БД и не сменит пароль в ней.
Локальная bootstrap-учётная запись официального PostgreSQL-образа имеет superuser
права; это не схема распределения DB-прав для внешнего production-сервера.

## Приложение на хосте

```sh
docker compose up -d --wait postgres mailpit
./mvnw clean verify
```

Compose автоматически читает `.env` для подстановки. Spring Boot на хосте его
сам не читает. Экспортируйте собственный доверенный файл по инструкции
[security.md](security.md), затем запустите JAR:

```sh
set -a
. ./.env
set +a
java -jar target/online-reader-0.0.1-SNAPSHOT.jar
```

`SPRING_PROFILES_ACTIVE=local`. JDBC URL должен использовать адрес хоста,
например `jdbc:postgresql://127.0.0.1:5432/online_reader`; при изменении
`POSTGRES_DB`/`POSTGRES_PORT` обновите `SPRING_DATASOURCE_URL` в `.env`.
`SERVER_PORT` управляет портом host JAR; `APP_PORT` относится только к Compose.
Mailpit для host JAR — `127.0.0.1:1025`, Web UI — `127.0.0.1:8025`.
`SPRING_MAIL_PORT` меняет только host SMTP port, `MAILPIT_UI_PORT` — UI/API port.
Проверка SMTP при старте приложения отключена, Compose проверяет readyz Mailpit.
Spring Mail smoke и очистка писем описаны в [mail.md](mail.md).

## Приложение в Docker

```sh
docker compose --profile app up -d --build --wait
```

Профиль Compose `app` включает приложение; без него запускаются PostgreSQL и Mailpit.
Внутри Docker приложение использует `postgres:5432` и имя БД из `POSTGRES_DB`,
независимо от host JDBC URL. Адрес bind фиксирован `0.0.0.0:8080` внутри контейнера;
наружу опубликован только `127.0.0.1:${APP_PORT}`. Общая конфигурация Spring Boot
по-прежнему не активирует профиль сама: Compose явно выбирает `local`.

Dockerfile собирает код через Maven Wrapper и запускает обычные H2-тесты.
PostgreSQL IT запускаются отдельно на хосте через `./mvnw -Ppostgres-it clean verify`;
Docker socket в build stage не передаётся. Builder использует JDK21 и `unzip`,
нужный Wrapper для проверки ZIP-дистрибутива по закреплённой SHA-256.
Runtime — отдельный JRE21 image без Maven/Node/исходников, UID/GID10001;
Java работает как PID1 и получает SIGTERM напрямую. Root filesystem read-only,
`/tmp` — ограниченный tmpfs. Файловое хранилище книг ещё не реализовано (#22),
volume для книг пока не создаётся.

Compose ждёт TCP healthcheck PostgreSQL и readyz Mailpit перед стартом приложения. Flyway поднимает
пустую схему, Hibernate выполняет validate. HTTP healthcheck приложения проверяет
главную страницу; это smoke-доступность, не полная проверка читалки или почты.
Mailpit hostname в app container — `mailpit:1025`; Web UI на loopback-порту8025.
Auth/TLS для перехватчика выключены. Spring Mail отправка, API-содержимое, очистка
и отказ зависшего SMTP с конечным timeout проверяются `-Pmailpit-it`.
Inbox Mailpit временный: restart/down очищают письма, PostgreSQL volume сохраняется.

## Остановка и сохранность

```sh
docker compose --profile app down
```

Команда удаляет контейнеры и сеть, сохраняя PostgreSQL volume. Повторный `up`
подключает ту же БД, Flyway проверяет историю и сохраняет существующие записи.
`docker compose --profile app restart` перезапускает контейнеры с тем же volume;
после изменения `.env`/Compose используйте `up`, поскольку restart не обновляет env.
Не добавляйте `-v` к down и не выполняйте volume prune: это уничтожает данные.
Удаление или сброс пользовательской БД требует отдельного решения и backup.
Смена project name создаёт другое окружение и другой volume.

Проверка состояния: `docker compose --profile app ps`.
Логи смотрите локально; не публикуйте полные `config`, `inspect`, env или dumps:
runtime Docker metadata содержит переданные credentials. Правила миграций и
восстановления — [database.md](database.md).

## Build context и ограничения

`.dockerignore` по умолчанию исключает всё и разрешает только Maven/Java/frontend
build inputs. `.git`, `.env*`, IDE, node_modules, target, локальные dumps и ключи
не передаются builder. Dockerfile не принимает секретов через ARG/ENV и копирует
в runtime только executable JAR. При добавлении нового build input обновляйте
allowlist, сохраняя исключение секретов.

По умолчанию порты служебных компонентов ограничены loopback. Не меняйте это
окружение в конфигурацию внешнего production-сервера. Makefile — #15,
интеграция полной читалки — #27.
