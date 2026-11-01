# Схема PostgreSQL и миграции

Схемой управляет Flyway (версия из Spring Boot dependency management).
SQL находится в `main/resources/db/migration`; V1 соответствует существующим
User/Admin/Action и связующей таблице admin_actions. V2 расширяет password до 255, добавляет префикс распознанным BCrypt cost12 и
проверяет encoded-only формат обоих типов аккаунтов; роли не меняются.
Совместимость и восстановление: [authentication.md](authentication.md). V4 добавляет users.role с NOT NULL/default User/CHECK User/Admin; users не повышаются
автоматически, legacy admin/actions/links сохраняются. Назначение роли —
[authorization.md](authorization.md). V3 добавляет users.email: UNIQUE и канонический lowercase/trim, NULL сохраняется
для legacy, новые регистрации требуют email через service/DTO. Правила переноса —
[registration.md](registration.md). Seed-аккаунтов и универсальных паролей нет.

## Запуск и изменение схемы

В `local` и `production` Flyway применяет новые версии до создания JPA
EntityManagerFactory, затем Hibernate проверяет соответствие модели:
`ddl-auto=validate`. SQL init и автоматический Hibernate update выключены.
Миграции используют тот же datasource, что JPA; отдельные Flyway credentials/URL
и произвольные locations не поддерживаются. Guard запрещает отключать миграции,
validation, включать auto-baseline или clean. В тестовой H2 Flyway выключен:
PostgreSQL-контракт проверяется отдельно, без подмены другой БД.

- Уже применённый `V*.sql` не редактируйте. Добавляйте следующую версию;
  Flyway хранит checksum и историю в `flyway_schema_history`.
- Сначала проверяйте миграцию на пустой disposable PostgreSQL, затем на копии
  предыдущей версии с синтетическими данными. Не запускайте проверки на dev-БД.
- Перед изменением типа, NOT NULL, UNIQUE, FK или удалением данных проверяйте
  совместимость существующих записей и составляйте план восстановления.
- V1 только создаёт новую схему. Повторный запуск с её историей — no-op;
  данные и ограничения сохраняются. Новых доменных сущностей пока нет.

## Непустая БД без Flyway history

Автоматического принятия старой Hibernate-схемы нет. Приложение откажется
мигрировать непустую БД без истории. Не обходите отказ через `baseline-on-migrate`,
`repair`, удаление history или `ddl-auto=update`.

Перед переносом такой БД сделайте backup и восстановите отдельную локальную копию.
Сравните таблицы, типы, NOT NULL, уникальные ограничения, sequence increment
(`actions_seq`: 50) и FK с V1. Расхождения требуют отдельной согласованной
миграции/процедуры переноса. Проверка только Hibernate validate недостаточна:
она не доказывает все constraints и индексы. Существующая внешняя БД в этой работе
не использовалась и не изменялась; её совместимость не подтверждена.

## Восстановление

До опасной миграции сохраните PostgreSQL custom-format dump через `pg_dump -Fc`.
Адрес и credentials задавайте через локальные libpq-переменные `PGHOST`, `PGPORT`,
`PGDATABASE`, `PGUSER`, `PGPASSWORD`, не в аргументах команды. Dump храните вне Git
и Docker build context; не публикуйте его содержимое.

При отказе сначала остановите запись в БД и сохраните логи без секретов.
Не откатывайте миграцию удалением записи из Flyway history. Восстановите dump
через `pg_restore` в отдельную пустую БД, проверьте ограничения, данные и запуск
совместимой версии приложения; переключение на восстановленную БД согласуйте
отдельно. Автоматических down-миграций или очистки БД нет. Полная проверка backup/
restore вместе с файлами книг остаётся [#28](https://github.com/Kenedy228/online_reader/issues/28).

## Проверки

```sh
./mvnw clean verify
./mvnw -Ppostgres-it clean verify
```

Первая команда запускает изолированную H2 suite. Вторая дополнительно запускает
`PostgresMigrationsIT` на disposable PostgreSQL17: пустая БД → V1→V2→V3→V4 → Hibernate
validate и JPA-запись; UNIQUE/NOT NULL/длина/FK; повторный migrate с существующей
записью без потери данных; преобразование legacy BCrypt и транзакционный rollback
при неизвестном формате пароля; V2→V3 с сохранением legacy NULL/flags/hash,
email UNIQUE/CHECK. RegistrationPostgresIT проверяет гонки регистрации по
username/email на настоящем сервисе/JPA и неизменность победившего аккаунта;
V3→V4 сохраняет legacy данные и проверяет role CHECK/default, provider читает
сохранённую роль, а live policy отказывает после её снятия. Используется закреплённый image digest; Docker обязателен.
При ошибке Docker или PostgreSQL fallback к dev/H2 отсутствует.
