# ДостоRead

Онлайн-читалка и личная библиотека электронных книг. Университетская курсовая
работа третьего курса. Цель развития проекта — production-ready приложение
с воспроизводимой сборкой, безопасной работой с данными и проверенными
пользовательскими сценариями. Деплой в текущий объём работ не входит.

Проект находится на ранней стадии: основные функции читалки ещё предстоит реализовать.

## Возможности и текущий статус

| Область | Сейчас | План |
| --- | --- | --- |
| Главная страница | Описание проекта и задуманных функций | Актуализировать по мере реализации |
| Аккаунты | Страницы входа и регистрации, переключатель видимости пароля, заготовки моделей и JPA-репозиториев | [Аутентификация](https://github.com/Kenedy228/online_reader/issues/14), [регистрация](https://github.com/Kenedy228/online_reader/issues/16), [права доступа](https://github.com/Kenedy228/online_reader/issues/17) |
| Личная библиотека | Не реализована | [Управление книгами](https://github.com/Kenedy228/online_reader/issues/19) |
| Файлы книг | Загрузки и хранения нет | [Безопасная загрузка](https://github.com/Kenedy228/online_reader/issues/22) |
| Чтение | Экран чтения отсутствует | [Читалка](https://github.com/Kenedy228/online_reader/issues/23) |
| Прогресс и заметки | Не реализованы | [Прогресс](https://github.com/Kenedy228/online_reader/issues/25), [заметки](https://github.com/Kenedy228/online_reader/issues/26) |

Наличие форм не означает работающий вход или создание аккаунта. Поддерживаемые
форматы книг, лимиты и точные границы MVP определяются в
[issue #4](https://github.com/Kenedy228/online_reader/issues/4).

## Технологии

- Java, Spring Boot и Spring MVC — сервер и обработка запросов.
- Thymeleaf — формирование HTML на сервере; отдельного SPA нет.
- Spring Security — основа для аутентификации и разграничения доступа.
- Spring Data JPA и PostgreSQL — хранение данных; H2 предусмотрена тестовым профилем.
- TypeScript и Webpack — клиентские обработчики внутри страниц.

Стек сохраняется при дальнейшей разработке. Переход на другой frontend-фреймворк
или замену Spring Boot текущие задачи не предусматривают.

## Структура

```text
main/
├── java/com/example/online_reader/  # Spring Boot и предметные пакеты
├── resources/
│   ├── application*.properties     # Конфигурация профилей
│   ├── templates/                 # Thymeleaf
│   └── static/                    # CSS, изображения и генерируемые JS-bundles
└── frontend/                      # TypeScript, package-lock.json, Webpack
test/java/                         # Java/JUnit-тесты
```

## Подготовка и сборка

Основная сборка требует полного JDK 21–25 (не JRE) и доступа к Maven Central, Node.js и npm registry
при первом запуске. Java-код компилируется для Java 21. В `pom.xml` закреплены
Spring Boot 3.5.16, frontend-maven-plugin 2.0.2, Node.js 22.23.4 и npm 10.9.9.
Maven Wrapper использует Maven 3.9.16 с проверкой SHA-256 дистрибутива.
Глобальная установка Maven, Node.js и npm для основной сборки не нужна.
Если wrapper выбирает неподходящую Java, задайте `JAVA_HOME` на полный JDK.
Сборка заранее проверяет версию Java и наличие `lib/ct.sym`, нужного для `--release 21`.
Закрепляйте `JAVA_HOME` и `$JAVA_HOME/bin` в `PATH` в конфигурации своего shell,
чтобы настройка сохранялась между терминальными сессиями. JDK должен находиться
в постоянном каталоге, а не в `/tmp`; личные shell-профили в репозиторий не входят.

Из корня репозитория:

```sh
./mvnw clean verify
```

На Windows используйте `mvnw.cmd clean verify`. Сборка устанавливает Node/npm
в `target/frontend`, выполняет `npm ci` без install scripts, компилирует TypeScript
и Java, запускает изолированные тесты и создаёт executable JAR:
`target/online-reader-0.0.1-SNAPSHOT.jar`.
Bundles генерируются в `target/generated-resources/static/js` и включаются в JAR
вместе с Thymeleaf-шаблонами, CSS и изображениями. Старые bundles из исходного
`main/resources/static/js` при упаковке исключаются.

Для отдельной работы с frontend можно использовать установленные Node.js/npm:

```sh
cd main/frontend
npm ci
npm run build
```

`npm ci` устанавливает зависимости по сохранённому lock-файлу. Webpack создаёт
`passwordVisibilityChangerForLogin.bundle.js` и
`passwordVisibilityChangerForRegistration.bundle.js` в `main/resources/static/js`.
Зависимости и bundles исключены из Git. Основная Maven-сборка генерирует ресурсы
автоматически; отдельная команда npm нужна только для самостоятельной работы
с frontend. Изменяйте исходники TypeScript, а не результаты сборки.

## Запуск и профили

Профиль выбирается явно через `SPRING_PROFILES_ACTIVE`. Без профиля, с прежним
`dev` или со смесью профилей приложение завершается до создания datasource.

| Профиль | База и почта | Назначение |
| --- | --- | --- |
| `local` | PostgreSQL из окружения, Mailpit на `127.0.0.1:1025` по умолчанию | Разработка с локальными сервисами |
| `test` | H2 в памяти и localhost SMTP, настройки принудительно из тестового classpath | Изолированные тесты и ознакомительный запуск |
| `production` | PostgreSQL, явно указанный Mailpit и абсолютный путь хранения | Проверка production-конфигурации без деплоя |

Быстрый ознакомительный запуск без PostgreSQL:

```sh
./mvnw spring-boot:test-run@local
```

Откройте `http://127.0.0.1:8080`; остановка — `Ctrl+C`. Maven execution `local`
использует профиль `test` и тестовый classpath. H2 удаляется при остановке;
настройки из `test/resources/application-test.properties` имеют приоритет над
окружением и аргументами datasource/SMTP. Этот preset отличается от runtime-профиля
`local`, который использует PostgreSQL.

Для обычного JAR подготовьте собственную локальную PostgreSQL и Mailpit.
Docker Compose добавляется в [#9](https://github.com/Kenedy228/online_reader/issues/9).
Flyway применяет версионированные SQL-миграции к пустой БД при запуске, затем
Hibernate проверяет схему с `ddl-auto=validate`. Повторный запуск сохраняет данные
и не выполняет уже применённые миграции. Непустая БД без истории Flyway требует
отдельной проверки и переноса; автоматический baseline и clean запрещены.
Правила изменения схемы и восстановления — в [docs/database.md](docs/database.md).

Создайте приватный `.env` из [.env.example](.env.example), задайте свои DB credentials
и экспортируйте переменные по [инструкции](docs/security.md). Затем:

```sh
java -jar target/online-reader-0.0.1-SNAPSHOT.jar
```

Шаблон выбирает `local`. Для production-like проверки задайте
`SPRING_PROFILES_ACTIVE=production`, `SPRING_MAIL_HOST=127.0.0.1` (либо `mailpit`
в локальной Docker-сети) и абсолютный `ONLINE_READER_STORAGE_ROOT`.
В JAR отсутствуют H2 и тестовая конфигурация; профиль `test` с обычным JAR отклоняется.
Деплой и подключение к внешним сервисам не выполняются.

## Конфигурация и безопасность

Общая конфигурация не активирует профиль. Адрес БД и credentials обязательны,
встроенных паролей нет. Проверка конфигурации выполняется после загрузки Spring
ConfigData, до создания beans. Ошибка содержит только имя неверного параметра.
Ожидается PostgreSQL JDBC URL вида `jdbc:postgresql://host:5432/database`;
query-параметры (например `sslmode`) допустимы, credentials передаются отдельно.

Оба runtime-профиля используют только Mailpit: разрешены `127.0.0.1`, `localhost`,
`::1` и имя сервиса `mailpit`. Внешний SMTP host отклоняется даже при переопределении
через окружение. SMTP auth отключена, SMTP credentials не нужны. Таймауты
соединения/чтения/записи конечны; проверка SMTP при старте отключена, поэтому запуск
сам по себе не подтверждает доступность Mailpit. Его контейнер и отправка тестового
письма остаются в [#13](https://github.com/Kenedy228/online_reader/issues/13).

В `.env.example` перечислены HTTP/DB/SMTP timeouts, multipart-лимиты и путь хранения.
Лимиты должны быть положительными, request limit — не меньше file limit;
Hikari validation timeout — меньше connection timeout. В production-профиле путь
хранения обязателен и должен быть абсолютным. Функция загрузки книг ещё отсутствует:
настройка пути пока не создаёт каталог и не подтверждает права записи; реализация и
проверка файлового хранилища — в [#22](https://github.com/Kenedy228/online_reader/issues/22).
Локальный каталог по умолчанию `./var/books` исключён из Git.

H2 console, debug/trace и вывод SQL выключены. Ответы Spring error не включают
stack trace, сообщение исключения и binding errors. Попытки включить эти настройки
или автоматическое обновление схемы в runtime-профилях отклоняются при старте.
JNDI, альтернативные Hikari connection settings и прямые `spring.jpa.properties.*`
не поддерживаются: они обходят основной datasource или правила схемы и отклоняются
во всех профилях. Из Hikari overrides допускаются два документированных таймаута.
Это разделение конфигурации; текущая аутентификация и права ещё требуют #14/#17.

Опубликованные ранее DB/SMTP credentials отозваны или заменены владельцем
([#2](https://github.com/Kenedy228/online_reader/issues/2)); значения остаются в Git
history. Не используйте их. Проверки секретов и правила работы с `.env` описаны
в [docs/security.md](docs/security.md).

Автоматизация локальных сервисов и команд остаётся в
[#9](https://github.com/Kenedy228/online_reader/issues/9),
[#13](https://github.com/Kenedy228/online_reader/issues/13) и
[#15](https://github.com/Kenedy228/online_reader/issues/15).

## Проверки

В frontend сейчас определён только script `build`; scripts `test`, `lint` и
`typecheck` отсутствуют. Отдельную диагностику TypeScript после установки
зависимостей можно выполнить из корня:

```sh
node main/frontend/node_modules/typescript/bin/tsc --project main/frontend/tsconfig.json --noEmit
```

При исходном аудите эта проверка завершалась с
`TS2792: Cannot find module 'undici-types'`. Webpack собирался с предупреждением
об отсутствии `mode`. Обе проблемы отслеживаются в
[#8](https://github.com/Kenedy228/online_reader/issues/8); успешная сборка Webpack
не заменяет отдельную проверку типов и тесты приложения.

Java-тесты запускаются через `./mvnw test` или вместе со сборкой `./mvnw clean verify`.
Проверяются рендер главной страницы, HTTP-выдача обоих сгенерированных bundles
и сохранение/поиск пользователя в H2. `UserTest` использует транзакционный rollback;
тестовая конфигурация явно переопределяет datasource и SMTP.
Не переключайте тесты на `dev` и не используйте рабочую БД для проверок.
H2 smoke не доказывает совместимость PostgreSQL или всей читалки.
Для схемы, миграций и JPA-mappings есть отдельный opt-in запуск с Docker:

```sh
./mvnw -Ppostgres-it clean verify
```

Он создаёт disposable PostgreSQL 17.11 из закреплённого официального образа,
проверяет миграцию с пустой БД, Hibernate validate, запись через JPA,
ограничения и повторное применение без потери данных. БД разработчика не используется;
при недоступном Docker проверка падает, без fallback на H2/dev.
Testcontainers автоматически удаляет контейнер после завершения.
Дальнейшие сценарии читалки остаются в [#27](https://github.com/Kenedy228/online_reader/issues/27).

## Участие в разработке

Правила работы с кодом, Git, секретами и проверками находятся в [AGENTS.md](AGENTS.md).
Полный backlog и порядок работ — в
[epic #1](https://github.com/Kenedy228/online_reader/issues/1).
Архитектурная документация и итоговая приёмка отслеживаются в
[#29](https://github.com/Kenedy228/online_reader/issues/29).
При реализации функций и изменении запуска обновляйте README: планы должны
отличаться от проверенного поведения.
