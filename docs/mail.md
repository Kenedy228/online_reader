# Локальная почта в Mailpit

Приложение сохраняет Spring Mail integration. SMTP-перехватчик окружения — Mailpit,
без внешнего провайдера, auth, STARTTLS и SSL/TLS. Регистрация, рассылки и
восстановление пароля этой задачей не реализуются.

## Запуск и адреса

Подготовьте приватный `.env` с локальными DB credentials по инструкции
[local-development.md](local-development.md). Compose проверяет обязательные
DB-переменные при загрузке конфигурации даже при выборе только Mailpit.

```sh
docker compose up -d --wait postgres mailpit
```

| Клиент | SMTP | UI/API |
| --- | --- | --- |
| Приложение на хосте | `127.0.0.1:1025` | `http://127.0.0.1:8025` |
| Приложение в Compose | `mailpit:1025` | На хосте: `http://127.0.0.1:8025` |

`SPRING_MAIL_PORT` задаёт host SMTP port, `MAILPIT_UI_PORT` — host UI/API port.
В Docker внутренние порты всегда 1025/8025, `SPRING_MAIL_HOST=mailpit` задаётся
сервису app явно. Host JAR использует `SPRING_MAIL_HOST=127.0.0.1` и host port.
SMTP credentials не нужны. Прежние опубликованные SMTP/DB пароли отозваны или
заменены владельцем в #2; их не используйте.

```sh
docker compose --profile app up -d --build --wait
```

Приложение ждёт healthy PostgreSQL и Mailpit. Проверка готовности Mailpit —
встроенный `/mailpit readyz`; HTTP healthcheck приложения не проверяет почтовый
сценарий. JavaMail connection/read/write timeouts конечны и настраиваются через
`.env.example`. Runtime guard запрещает включать SMTP auth, SSL и STARTTLS.

## Автоматический Spring Mail smoke

```sh
./mvnw -Pmailpit-it clean verify
```

Команда запускает собственный disposable Mailpit с динамическими портами,
загружает настоящие `application.properties` и local mail properties через Spring
Mail auto-configuration, отправляет синтетическое письмо между адресами `.test`
и проверяет содержимое через API, используемый UI. Затем очищает тестовый inbox
и подтверждает отсутствие писем. SMTP-настройки оператора не наследуются;
существующий Compose inbox эта команда не трогает. Docker обязателен, fallback
на внешний SMTP отсутствует.

Вторая проверка поднимает loopback TCP-сервер, который принимает соединение,
но не выдаёт SMTP greeting: настоящий JavaMail read timeout завершает отправку
ошибкой за ограниченное время. Это проверка зависшего SMTP, а не только
мгновенного connection refused. Контроль теста: завершение не позднее 6 секунд
при штатном mail read timeout 3000 ms.

Проверить обе инфраструктурные интеграции:

```sh
./mvnw -Ppostgres-it,mailpit-it clean verify
```

Обычный `./mvnw clean verify` по-прежнему не требует Docker.

## Ручная проверка UI

Чтобы увидеть синтетическое письмо в **своём Compose inbox**, можно выполнить
SMTP-пробу из контейнера приложения (curl, без auth/TLS):

```sh
docker compose exec -T app curl --max-time 5 --url smtp://mailpit:1025 \
  --mail-from sender@example.test --mail-rcpt reader@example.test \
  --upload-file - <<'MAIL'
From: sender@example.test
To: reader@example.test
Subject: Local Mailpit smoke

Synthetic local SMTP smoke message
MAIL
```

Откройте UI `http://127.0.0.1:8025`: должно появиться письмо с указанной темой.
Эта ручная проба проверяет Docker DNS/SMTP/inbox; Spring Mail проверяет Maven
suite выше. Сервис app должен быть запущен, новых email endpoints в приложении нет.

## Очистка и ограничения

В UI используйте Delete all либо выполните для **своего локального inbox**:

```sh
curl --fail --silent --show-error -X DELETE http://127.0.0.1:8025/api/v1/messages
```

Команда удаляет все перехваченные письма, не затрагивая PostgreSQL.
Mailpit хранит временную SQLite в tmpfs, без persistent volume: restart/down
также очищают inbox. Сохраняется не более 100 писем с периодической автоочисткой;
буфер не предназначен для долговременного хранения.

Официальный image закреплён stable-тегом и digest. UI/SMTP публикуются только на
loopback; auth/TLS не используются в этом локальном окружении. Relay/forward
конфигурация не передаётся, auto-relay выключен. Version checks и SMTP reverse DNS
выключены. Не добавляйте внешний relay, реальные credentials или публичную привязку
портов. Письма могут содержать чувствительные данные: используйте синтетические
адреса/тексты, не публикуйте inbox, скриншоты с данными и dumps.
