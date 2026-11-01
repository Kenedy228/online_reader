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

## Локальный запуск для проверки

Для ознакомительного запуска без внешней БД используйте тестовый classpath:

```sh
./mvnw spring-boot:test-run@local
```

Главная страница доступна по `http://localhost:8080`. Этот запуск использует
H2 в памяти и тестовый classpath; данные не предназначены для постоянного хранения.
Execution `local` явно задаёт datasource, пустые тестовые credentials, localhost SMTP
и привязку HTTP к `127.0.0.1` аргументами приложения с приоритетом выше окружения.
Проверка SMTP-соединения при старте отключена. Почтового перехватчика пока нет.
Обычный `spring-boot:test-run` без `@local` такой защиты от dev-переменных не даёт.
Остановить приложение можно через `Ctrl+C`.

В обычном JAR нет H2 и ресурсов из `test/resources`: он предназначен для PostgreSQL.
Запуск с PostgreSQL требует отдельно подготовленной локальной БД и явных
настроек окружения. Credentials берутся из переменных без встроенных значений;
подготовка `.env.example` описана в [инструкции по секретам](docs/security.md).
Старые опубликованные credentials не используйте; их ротация и безопасные
runtime-профили остаются отдельными задачами.
Восстановление сборки отслеживается в
[issue #3](https://github.com/Kenedy228/online_reader/issues/3).

## Конфигурация и безопасность

- `application.properties` включает профиль `dev` по умолчанию и содержит SMTP-настройки.
- `application-dev.properties` задаёт PostgreSQL для разработки.
- `application-test.properties` задаёт H2 и включает H2 console; само наличие этого
  файла в `main/resources` не задаёт безопасное production-окружение.
- При тестировании файл `test/resources/application-test.properties` переопределяет
  datasource на H2 в памяти, отключает H2 console и внешний SMTP. Java-тесты явно
  выбирают профиль `test` и этот файл с приоритетом выше переменных окружения.

Runtime credentials вынесены из properties в окружение, вывод логина/пароля
из обработчика входа удалён. Ранее опубликованные значения остаются в истории:
не используйте их и не публикуйте в README, issues, логах или env-файлах в Git.
Удаление из файлов не заменяет отзыв; подтверждение ротации остаётся в
[#2](https://github.com/Kenedy228/online_reader/issues/2). Переменные, загрузка
`.env`, staged secret check и CI описаны в [docs/security.md](docs/security.md).
Разделение профилей —
в [#6](https://github.com/Kenedy228/online_reader/issues/6).

Локальное окружение пока не автоматизировано. Запланированы
[Docker Compose с PostgreSQL](https://github.com/Kenedy228/online_reader/issues/9),
[Mailpit вместо внешнего SMTP](https://github.com/Kenedy228/online_reader/issues/13)
и [Makefile](https://github.com/Kenedy228/online_reader/issues/15).
До их реализации команд запуска этих компонентов в проекте нет.

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
H2 smoke не доказывает совместимость PostgreSQL, миграций или всей читалки:
дальнейшие проверки отслеживаются в [#11](https://github.com/Kenedy228/online_reader/issues/11)
и [#27](https://github.com/Kenedy228/online_reader/issues/27).

## Участие в разработке

Правила работы с кодом, Git, секретами и проверками находятся в [AGENTS.md](AGENTS.md).
Полный backlog и порядок работ — в
[epic #1](https://github.com/Kenedy228/online_reader/issues/1).
Архитектурная документация и итоговая приёмка отслеживаются в
[#29](https://github.com/Kenedy228/online_reader/issues/29).
При реализации функций и изменении запуска обновляйте README: планы должны
отличаться от проверенного поведения.
