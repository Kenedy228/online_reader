# Инструкции для работы над «ДостоRead»

## Проект и границы работы

«ДостоRead» — университетская курсовая работа третьего курса: онлайн-читалка
и личная библиотека электронных книг. Цель — подготовить приложение к
production-ready состоянию с проверяемыми сборкой, безопасностью и поведением.
Деплой, публикация образов и настройка внешних серверов в текущую работу не входят.

- Roadmap и критерии готовности: [epic #1](https://github.com/Kenedy228/online_reader/issues/1).
- Границы MVP и форматы книг уточняются в [#4](https://github.com/Kenedy228/online_reader/issues/4).
- MVP: аккаунты, личная библиотека, загрузка и чтение книг, прогресс и заметки.
  Не добавляйте подписки, рекомендации, публичный каталог или рассылки без задачи.
- Сейчас есть страницы главной, входа и регистрации, клиентское переключение
  видимости пароля и заготовки моделей аккаунтов. Вход через JPA/BCrypt и сессия реализованы;
  регистрация с обязательным email реализована; роли/владельцы (#17) и книжные функции пока не реализованы.

## Приоритет инструкций и источники требований

В рамках системных ограничений применяйте явные указания пользователя, затем
ближайшие `AGENTS.md`/`RULES.md`, затем применимые требования Wiki.
Существующее отклонение в коде — технический долг, а не образец для новых изменений.

Используйте требования по области задачи, не копируя Wiki в репозиторий:

- [Code Rules](https://repo.dvgups.ru/docs/Разработка/Code-Rules) — качество, границы и объём изменений.
- [Test Rules](https://repo.dvgups.ru/docs/Разработка/Test-Rules) — ценность и уровни проверок.
- [Web Quality Rules](https://repo.dvgups.ru/docs/Разработка/Web-Quality-Rules) — безопасность и доступность.
- [Git Agent Rules](https://repo.dvgups.ru/docs/Разработка/Git-Agent-Rules) — Git и review с локальными уточнениями ниже.

При составлении проверена редакция `DVGUPS/DOCS`, ветка `main`, revision
`e219791ed0873852dd6e2ee4c34f34e8224018aa`. Для новой задачи получайте применимые
документы через настроенные Wiki tools. Если доступ недоступен, сообщите, какие
требования нельзя проверить; не подменяйте их догадками и продолжайте независимую работу.

## Стек сохраняется

- Java, Spring Boot, Spring MVC, Spring Security, Spring Data JPA.
- Thymeleaf: сервер формирует HTML; TypeScript/Webpack дополняют страницы.
- PostgreSQL — основная БД; H2 уже предусмотрена тестовым профилем.

Указание пользователя сохранить технологии имеет приоритет над целевым
SvelteKit-стеком Wiki. Не мигрируйте на SvelteKit, React, отдельное SPA,
микросервисы или другую БД без отдельного поручения. FSD, Svelte-specific правила
и каталоги из Wiki не переносите на Java-проект по аналогии. Не придумывайте
версии вне сборки: `pom.xml` закрепляет Java release 21 и Spring Boot 3.5.16,
Maven Wrapper — Maven 3.9.16. Поддерживаемый JDK для сборки: 21–25.

## Текущий контракт входа

GET `/login` создаёт пустую форму; query credentials не привязываются.
POST обслуживает штатный Spring Security formLogin с CSRF, JPA UserDetailsService
и BCrypt cost12 (`{bcrypt}`); MVC POST handler/DTO не добавляйте. Причины отказа
обобщены, пароль/username/исключение не сохраняются в model или session.
Пароль ограничен 72 байтами UTF-8 до BCrypt; хранится только passwordHash.
Статусы аккаунта проверяются после сравнения пароля. После входа меняется session ID;
logout — POST с CSRF, invalidation и удалением cookie. Rate limiter после CSRF
считает все попытки по remote address, включая успешные, с ограниченной памятью.
Не регистрируйте его второй раз как servlet Filter bean и не доверяйте X-Forwarded-For.
Runtime policy, ограничения и транзакционная V2: [docs/authentication.md](docs/authentication.md).
Регистрация использует отдельный DTO/controller/service: username ASCII3–50 с учётом
регистра, обязательный email ASCII254 trim/lowercase/UNIQUE, пароль >=15 Unicode
code points и <=72 UTF-8 bytes, точное подтверждение. Не привязывайте entity/flags/role;
после ошибки очищайте DTO и rejectedValue обоих паролей. Атомарный параметризованный
INSERT ON CONFLICT DO NOTHING не обновляет existing account; не заменяйте его
exists-then-insert. У регистрации отдельный bounded budget после CSRF.
V3 сохраняет legacy email=NULL; новые регистрации требуют email на MVC/service
границе. Email не подтверждён и не даёт права на восстановление/доступ.
Контракт/ограничения: [docs/registration.md](docs/registration.md).
Роли/ownership остаются #17, seed/admin provisioning не добавлены.

## Структура и ответственность

| Каталог | Назначение |
| --- | --- |
| `main/java/com/example/online_reader` | Spring Boot entry point, конфигурация, доменные пакеты |
| `main/resources/templates` | Thymeleaf-страницы и общие фрагменты |
| `main/resources/static` | CSS, изображения и собранные клиентские JS-файлы |
| `main/resources/application*.properties` | Конфигурация окружений |
| `main/frontend/src` | Исходники TypeScript |
| `main/frontend` | `package.json`, lock-файл, TypeScript/Webpack-конфигурация |
| `test/java` | Java/JUnit-тесты с пакетами, соответствующими приложению |
| `test/resources` | Изолированная конфигурация H2 и SMTP для проверок |
| `pom.xml`, `.mvn`, `mvnw`, `mvnw.cmd` | Maven-сборка и wrapper |

Сохраняйте существующее деление по предметным пакетам. Controller принимает
HTTP-ввод, валидирует DTO и формирует ответ/model; service отвечает за сценарий
и бизнес-правила; repository — за доступ к данным; JPA model — за отображение
хранения. Не размещайте бизнес-логику в Thymeleaf и не передавайте пароль в model.
Для новых Java-тестов используйте `test/java`, а не JS-каталоги `__tests__`.

Расширяйте подходящий существующий модуль. Не создавайте слои, interfaces,
wrappers или зависимости без текущей ответственности. Меняйте минимальный
самодостаточный объём; посторонние исправления оформляйте отдельно. Не редактируйте
`node_modules` и собранные bundles вручную: изменяйте исходники и сборку.

## Безопасность и данные

- Не выводите и не коммитьте реальные credentials, токены, персональные данные,
  env-файлы или дампы. При анализе конфигурации показывайте только имена ключей
  и замаскированные значения; diff тоже может раскрывать удалённый секрет.
- Runtime credentials читаются из окружения без встроенных значений; обработчик
  входа не должен выводить их ни до, ни после валидации. Переменные и проверки —
  в [docs/security.md](docs/security.md). Перед коммитом выполняйте staged-проверку
  `sh scripts/check-secrets.sh` с Gitleaks 8.30.1 и связанные проверки правил.
- Ранее опубликованные credentials остаются в Git history:
  [#2](https://github.com/Kenedy228/online_reader/issues/2). Не используйте их и
  не проверяйте работоспособность. Отзыв выполняет владелец; удаление из текущих
  файлов и зелёный CI не заменяют подтверждённую ротацию.
- Не обращайтесь к внешним SMTP/БД ради проверки. Для тестов нужны изолированная
  БД и локальный перехватчик почты. `compose.yaml` предоставляет PostgreSQL, Mailpit и
  приложение (opt-in профиль `app`). Команды разработки доступны через Makefile;
  `make help` описывает цели.
- Проверяйте внешние данные на сервере: тип, длину, формат, диапазон и бизнес-правила.
  Для файлов ограничивайте размер, формат, ресурсы обработки и место хранения.
- Для защищённых действий проверяйте сессию, роль и владельца ресурса на сервере.
  Публичный allowlist ограничен главной, формами и static assets; новые маршруты требуют сессию. Не отключайте CSRF
  ради работающей формы и не храните новые пароли в открытом виде.
- Экранируйте вывод; пользовательский HTML требует sanitization. Не собирайте SQL,
  команды или пути конкатенацией недоверенных строк; не раскрывайте SQL и stack traces
  в ответах. Изменения схемы и удаления данных требуют плана восстановления.

## Команды и текущие ограничения проверок

Основная команда из корня — `make check`, вызывающая `./mvnw clean verify`
(`mvnw.cmd clean verify` остаётся прямым вариантом на Windows).
Она устанавливает закреплённые Node.js 22.23.4/npm 10.9.9 в `target/frontend`,
выполняет `npm ci --ignore-scripts`, собирает frontend, компилирует Java,
запускает изолированные тесты и упаковывает executable JAR.
Maven/plugins закреплены wrapper/parent POM; не меняйте версии вне scope задачи.
При первом запуске нужны полный JDK 21–25 и доступ к реестрам зависимостей.
Для `--release 21` требуется `lib/ct.sym`: наличие команды `java` или модуля
`jdk.compiler` само по себе недостаточно. Enforcer проверяет это до сборки frontend;
при отказе укажите `JAVA_HOME` на полный JDK, не снижайте Java release ради окружения.
Makefile делегирует wrapper/POM/npm/Compose, не содержит своих версий tools.
`make help` — точные цели; семантика/зависимости — README. Make-команды требуют
GNU Make/POSIX shell, Maven Wrapper с ZIP checksum требует unzip.
`make setup/up/run` создают `.env` только если он отсутствует: случайный DB password,
mode600, без вывода значений. Существующий env не меняется и не исполняется shell.
Для проверок используйте собственные PROJECT_NAME/ENV_FILE/порты и синтетические данные.
`make down` сохраняет volumes, `make clean` удаляет только Maven target. Не добавляйте
скрытое удаление данных, проглатывание ошибок или deploy/push targets.
CI вызывает `make check` и `make secret-check SECRET_MODE=snapshot`; snapshot только
в чистом checkout, локальная staged-проверка не читает игнорируемый env оператора.

Frontend scripts из `main/frontend`: `build` (production, без source maps),
`build:dev` (development с `.map`), `typecheck` (`tsc --noEmit`) и `lint`
(ESLint/typescript-eslint recommended + prefer-const, warnings считаются отказом).
Maven выполняет npm ci, typecheck, lint, затем Webpack: ошибки не отключайте и
не обходите skipLibCheck ради зелёной сборки. TypeScript strict сохранён;
moduleResolution=bundler и types=[] отражают браузерную среду, не Node.js.
Версии lint tools закреплены package.json/lock; остальной frontend stack сохранён.

Отдельный Webpack пишет bundles в `main/resources/static/js`; Maven переопределяет
output на `target/generated-resources/static/js` и включает файлы в JAR. Production
не генерирует source maps; переключение режима удаляет только принадлежащие этим
entry points JS/maps/license sidecars и сохраняет посторонние файлы каталога.
Не добавляйте deps/bundles через `git add -f`; меняйте исходники и конфигурацию.
`package-lock.json` остаётся единственным lock-файлом для npm ci.
Отдельного JS test runner нет; не выдавайте lint/typecheck за E2E UI-проверку.

**Не запускайте Java-тесты с runtime datasource.** `./mvnw test` использует `test`.
`RuntimeEnvironmentPostProcessor` загружает `test/resources/application-test.properties`
с приоритетом выше окружения и CLI до создания beans: только H2 и localhost SMTP.
Тестовая конфигурация и H2 отсутствуют в обычном JAR; `test` требует test classpath.
`UserTest` работает в транзакции с rollback. H2 не подтверждает PostgreSQL-
совместимость — соответствующие проверки остаются в #11/#27.

Без PostgreSQL используйте `./mvnw spring-boot:test-run@local`: это H2 preset
с профилем `test`, а не runtime-профиль `local`. Обычный JAR требует явного выбора
ровно одного профиля `local`/`production`, DB credentials и локальной PostgreSQL.
`dev` и неявный выбор профиля больше не поддерживаются. Flyway применяет `main/resources/db/migration/V*.sql`, затем Hibernate выполняет
`ddl-auto=validate`. Историю миграций не переписывайте; clean, auto-baseline,
отдельный datasource Flyway и изменение его locations запрещены runtime guard.
Правила схемы/восстановления — `docs/database.md`. Не включайте `update`.
`./mvnw -Ppostgres-it clean verify` запускает JPA/migration contract checks
на disposable PostgreSQL17 (Docker обязателен); тесты `*IT` не входят в обычный
быстрый запуск. H2 использует `spring.flyway.enabled=false`, create-drop и PostgreSQL mode для native registration INSERT; проверка PostgreSQL остаётся отдельной.

`local` и `production` направляют почту только в Mailpit (loopback или `mailpit`),
SMTP auth/TLS выключены; соответствующие overrides отклоняются до I/O.
`./mvnw -Pmailpit-it clean verify` проверяет Spring Mail → disposable Mailpit,
содержимое и очистку inbox, а также timeout зависшего loopback SMTP. Контекст
проверки загружает настоящие mail properties и не наследует SMTP env оператора.
Профили `postgres-it,mailpit-it` можно запускать вместе; обычная suite Docker не требует.
Адреса, credentials, путь хранения, multipart-лимиты и конечные таймауты вынесены
в окружение; контракт — `.env.example` и README. В `production` storage root
обязателен и абсолютен; создание/права каталога проверяются при реализации #22.
Валидация конфигурации выполняется до I/O; ошибки содержат только имя параметра.
JNDI, альтернативные Hikari connection settings и `spring.jpa.properties.*`
отклоняются во всех профилях; не обходите основной datasource/DDL-контракт.
Не возвращайте общий `spring.profiles.active`, packaged test properties,
H2 console, debug/trace, SQL logging или раскрытие ошибок клиенту.

## Локальные контейнеры

`docker compose up -d --wait postgres mailpit` запускает сервисы для host JAR.
`docker compose --profile app up -d --build --wait` собирает и запускает приложение
после healthy PostgreSQL и Mailpit. Private `.env` содержит локальные DB credentials;
Compose читает его для подстановки, Spring Boot на хосте требует явного экспорта.
Контракт портов/переменных/остановки — `docs/local-development.md`.
Mailpit UI/API/SMTP ограничены loopback, relay/forward не настраиваются, проверка
версии и reverse DNS выключены. Inbox временный, без persistent volume; очистка
затрагивает только письма. Команды smoke/очистки — `docs/mail.md`.

`docker compose --profile app down` сохраняет PostgreSQL volume. Не применяйте
`down -v`, `volume prune` или глобальную очистку к пользовательским данным.
Для проверок создавайте уникальный Compose project с синтетическими credentials;
удаляйте только собственные disposable-контейнеры/volumes с проверенными labels.
Не публикуйте `docker compose config` или `docker inspect` с runtime env.

Dockerfile использует pinned official JDK/JRE21 images; Maven Wrapper, Node/npm
и обычные тесты выполняются в build stage. `unzip` нужен для ZIP checksum Wrapper.
Runtime — UID/GID10001, Java PID1, read-only filesystem и tmpfs `/tmp`.
`.dockerignore` работает по allowlist; новые build inputs добавляйте явно.
Env-файлы, credentials, `.git`, локальные caches и результаты сборки не входят
в context. Build args для секретов не добавляйте. Публикация образов/деплой вне scope.

## Проверка изменений и приёмка

- Перед правкой определите конкретный риск и критерии задачи. Для бага добавляйте
  воспроизводящий regression test, где это возможно; проверьте отказ до исправления
  и успех после него. Если это невозможно, укажите причину и пределы замены.
- Проверяйте наблюдаемое поведение, бизнес-правила, права, ошибки и сохранность данных
  на самом низком достаточном уровне. Для PostgreSQL-специфичных контрактов нужна
  изолированная PostgreSQL; прохождение H2-теста не доказывает совместимость.
- Не добавляйте тесты getters, CSS-классов, собственного mock или fixture ради TDD,
  количества или процента покрытия. Для документации и косметических изменений
  достаточно соразмерной проверки без формальных автотестов.
- Для изменённого UI проверяйте клавиатуру, видимый focus, label/error-связи,
  увеличение текста и узкий экран; применяйте WCAG 2.2 A/AA по области изменения.
  Выполняйте автоматическую accessibility-проверку, если доступен соответствующий
  инструмент, и явно отмечайте непроверенное.
- Сначала выполняйте focused-проверки, затем связанные проверки сборки и интеграции.
  Не называйте отдельный тест доказательством работоспособности всего приложения.
  Перед передачей проверьте diff и получите независимое review; если оно недоступно,
  сообщите об этом, не выдавая самопроверку за независимую.
- В результате указывайте, что изменилось, фактические команды и результаты,
  оставшиеся ограничения и значимые решения. Не заявляйте успех по предположению.
  При изменении запуска, профилей или проверок обновляйте эти инструкции и README,
  когда он появится ([#31](https://github.com/Kenedy228/online_reader/issues/31)).

## Git и review

- До изменений проверяйте `git status`; сохраняйте несвязанные правки пользователя.
  Не применяйте массовые `reset`, `clean` или откат чужих изменений.
- Основная ветка этого репозитория — `main`. Для обычной разработки используйте
  тематическую рабочую ветку от актуальной `main`, PR направляйте в `main`.
  Если задача уже выполняется в подходящей ветке/worktree, продолжайте там.
  Ветку `stage` и release workflow не вводите без отдельного поручения.
- Держите одно логическое изменение в commit/PR; используйте Conventional Commits,
  если создаёте коммит. PR должен объяснять проблему, результат, фактические проверки
  и риски. Каждую самостоятельную задачу оформляйте отдельной тематической веткой
  и PR. Значимые решения обосновывайте в описании PR: выбранный подход, причины
  и ограничения; нетривиальные инварианты фиксируйте также рядом с кодом.
  Зависимости между PR и порядок их слияния указывайте явно.
  Перед merge нужны независимое review и применимые проверки.
- Push, merge, публикация, deploy и переписывание истории допустимы только в пределах
  явного поручения пользователя. Не закрывайте issue, пока не выполнены все критерии.
- В репозиторий входят исходники и итоговая документация, требуемая задачей;
  не коммитьте временные планы, отчёты инструментов и отладочные артефакты.
