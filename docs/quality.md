# Проверки критического сценария

Матрица #27 связывает риски с наблюдаемым поведением. Процент покрытия и число
тестов не являются критериями приёмки; H2 не заменяет PostgreSQL, axe — ручной
аудит доступности. Источники синтетические, без чужих книг и credentials.

## Матрица рисков

| Риск / инвариант | Минимальная достаточная проверка | Сквозное подтверждение |
| --- | --- | --- |
| Пароль, уникальность email/username, CSRF, смена/отзыв сессии | `RegistrationPolicyTest`, `RegistrationFlowTest`, `AuthenticationFlowTest`, `HttpSessionLifecycleTest`; `RegistrationPostgresIT` для INSERT race | `lifecycle.cjs`: нативные регистрация/вход/выход, повтор старой session cookie получает401 |
| User/Admin не читают и не изменяют чужие ресурсы; nested ID принадлежит книге | `LibraryOwnershipTest`, `AuthorizationFlowTest`, `ReaderHttpTest`, `ProgressHttpTest`, `NotesMvcTest` | `lifecycle.cjs`: два User и Admin, GET/PUT/POST, исходник/PNG/прогресс/заметки, неправильный note ID между своими книгами |
| Ввод/ZIP/XML/активный контент не становится доступом к файлам или исполнением | `BookFormatValidatorTest`, `EpubValidatorTest`, `EpubDocumentTest`, `ReaderHttpTest`: UTF-8, ZIP/traversal/XXE/XSS/DRM, image/processing budgets | EPUB с script/внешней ссылкой; plain-text XSS заметки; внешние browser/API запросы блокируются до отправки |
| Квоты, admission, дедлайны и cleanup сохраняют бюджет и данные | `BookFilesHttpTest` (настоящий HTTP, slow body), `PrivateBookStorageTest`, `BookFileFailureTest`, `BookStorageLeaseTest`; `BookPostgresIT` (конкуренция) | Browser upload обоих форматов; реальное изменение байтов собственного исходника даёт422 |
| Позиция Unicode переносится между устройствами и переживает рестарт | `TextFragmentTest`, `ChapterTextTest`, `RangeProofTest`, `ProgressHttpTest`; `ProgressPostgresIT` (CAS, FK, row locks) | `progress.cjs`: debounce, navigation/font flush, loss of ACK, два tab409, explicit retry/replace; `lifecycle.cjs`: logout/login/restart/continue TXT+EPUB |
| Заметки сохраняют текст, locator, версию и invalid draft | `NoteTextTest`, `NotesMvcTest`; `NotesPostgresIT` (length/FK/race/restart) | `notes.cjs`: native/no-JS CRUD, CRLF/emoji2000, XSS,409/edit/delete, пагинация, переход к месту в EPUB |
| Ошибка зависимости не становится ложным «сохранено» | HTTP security/error tests; storage fault tests; `MailpitIT` (доставка и SMTP timeout) | `lifecycle.cjs`: останавливает настоящий PostgreSQL; UI unsaved; после восстановления/restart остаются последний ACK и заметки |
| Удаление закрывает чтение до освобождения квот и не оставляет заметки/прогресс | `BookPostgresIT`, `NotesPostgresIT`, `BookFileFailureTest` | Browser confirmed delete; бывшие note/progress URL404 |
| UI доступен без мыши, при увеличении и узком экране | Native MVC/form checks + browser | 13 scoped axe состояний в `notes.cjs`/`progress.cjs`,320 CSS px/200% текста, keyboard skip/focus; это не аудит всего продукта |
| Миграции/конфигурация/упаковка воспроизводимы | `RuntimeProfilesTest`, `RuntimeCredentialsTest`, `PostgresMigrationsIT`, Maven verify, Gitleaks fixtures/snapshot | Runtime app в performance-стенде запускается из обычного JAR без H2/test fixtures |

Java-классы находятся в `test/java/com/example/online_reader`, браузерные сценарии —
в `test/browser`. Новому дефекту нужен тест нарушенного поведения в подходящем
уровне этой матрицы; не дублируйте всё в E2E и не проверяйте собственный mock.

## Повторяемый запуск

Нужны полный JDK21–25, Docker Linux containers, сеть для pinned dependencies/images
и системные библиотеки Chromium. Версии Maven/Node/npm берутся из общей сборки;
Playwright1.64.1 и axe4.14.0 закреплены отдельным `test/browser/package-lock.json`.

```sh
make check
make setup-e2e
# Только если Linux не содержит библиотек Chromium; команда меняет пакеты ОС:
sudo target/frontend/node/node test/browser/node_modules/playwright/cli.js install-deps chromium
make test-e2e
make test-acceptance
```

`test-e2e` включает обычные проверки и `ReaderE2EIT`; `test-acceptance` также
включает PostgreSQL/Mailpit IT. В CI отдельный job `acceptance` на Ubuntu24.04
выполняет setup, установку OS libraries и `make test-acceptance`. Быстрый
`make check` остаётся без Docker/браузера. Сбой setup, assertion, browser exit
или timeout завершает команду ненулевым кодом, silent skip отсутствует.

Координатор создаёт собственные disposable PostgreSQL17/Mailpit, private storage
и loopback listener; не читает `.env` или datasource/SMTP окружения оператора.
Spring запускается с runtime `local`, Flyway и `ddl-auto=validate`, без H2 preset.
Только test listener получает ephemeral port после runtime guard. Несколько
независимых сценариев на одном remote address используют fixture auth/registration
budget100 и Hikari connection/validation timeout1с/0.5с для fault trial;
production defaults и runtime guards не меняются. Серверные
rate-limit проверки остаются отдельными focused тестами.

Перезапуск, порча/восстановление только своего файла и остановка/запуск только
своего контейнера управляются через stdin/stdout Node↔Java. В приложение не
добавляются test endpoint, privilege bypass или fault flags. После restart Docker
порт PostgreSQL перечитывается: повторное использование старого mapped port
проверяло бы недоступный listener вместо восстановления тех же данных.

Каждый браузерный context временный; запросы вне точного fixture origin
блокируются и проваливают проверку. API-запросы не следуют redirect автоматически.
Таймаут ограничен8мин на suite; Java завершает owned Node/Chromium descendants.
Контексты приложения и контейнеры закрываются в `finally`/try-with-resources.
Скриншоты и stderr синтетического сценария остаются в private
`target/reader-e2e/<uuid>`; не публикуйте cookies/proofs или полный DOM в отчётах.
После диагностики `make clean` удаляет эти локальные артефакты.

## Производительность

```sh
make test-performance
```

Это отдельный opt-in профиль `reader-performance`, не CI latency gate на shared
runner. Он собирает обычный JAR и запускает pinned JRE21 и PostgreSQL17 в Docker:
каждый ограничен1 CPU/2ГиБ; фактические `NanoCpus`/`Memory` проверяются через
inspect. Приложение работает UID10001 с read-only rootfs и отдельным tmpfs `/tmp`.
Исходники находятся в собственном local Docker volume, а не tmpfs. Helper готовит
только этот volume до запуска app; перед удалением проверяется его ownership label.
Образы не строятся/публикуются, внешние серверы не используются.

Набор проверяется SQL:10 users×100 книг, по10 заметок на книгу. На владельца —
98 небольших уникальных TXT и по одному near-10МиБ TXT/EPUB. Источники проходят
настоящий `BookFormatValidator`; EPUB padding — реальные STORED bytes, не ZIP bomb.
Это bulk fixture для чтения/изменения, не измерение времени ingest.

После warm-up работают10 независимых сессий, start stagger1с, pacing10с между
итерациями каждого пользователя,10 итераций. Один пользователь последовательно
выполняет list→card→reader→progress→note; пользователи работают одновременно.
Это10 активных пользователей, а не обещание10 одновременно исполняемых HTTP.
В каждом сценарии100 samples; reader чередует оба больших формата. Отдельно
20 browser navigations измеряют время до видимого ограниченного фрагмента.

Время учитывает полное тело ответа; note включает POST303→GET200. Redirect на
login, неожиданный4xx/429/5xx или потеря mutation проваливает nominal запуск,
а не исключается из p95. Conflict/overload/fault trials из E2E/IT не входят в
latency samples. Пороги [MVP](mvp.md): p95≤500мс list/card/progress/note,
≤1000мс reader, visible text≤2000мс. `rig.json`, `measurements.json` либо
`failed-measurements.json` сохраняют ресурсы/версии/pacing/raw samples и результат.

Для окончательной приёмки стенд должен иметь local SSD без фоновой нагрузки.
Код не может вывести тип физического носителя из слова Docker volume:
подтвердите носитель отдельно и приложите hardware evidence. Замер на другом
носителе или shared host характеризует только тот запуск; не объявляйте его
доказательством performance SLA. Холодный старт, build/image pull, full upload,
стресс/масштабирование и доступность внешнего production в эти числа не входят.

## Проверенное evidence

Локальный запуск2026-11-04: Linux x86_64, AMD Ryzen7 PRO3700,
Docker29.9.3, Docker root `/var/lib/docker` на ext4 `/dev/nvme0n1p2`
(`lsblk` ROTA=0); pinned Temurin21.0.13+9/PostgreSQL17.11,
Chromium156.0.8078.4, Node22.23.4. Cgroupv2 подтвердил1CPU/2ГиБ
для app, inspect — такие же caps для PostgreSQL. Другие процессы хоста
не резервировались; эти результаты относятся к указанному локальному запуску.

Итоговый clean verify:255 обычных и35 integration/E2E/performance tests,
без failures/errors/skips. После warm-up100 samples на сценарий дали p95:
list8.0мс, card6.2мс, reader290.0мс, progress6.5мс, note53.6мс;
max из20 first-visible navigations387мс.
TXT10_260_000 и EPUB9_502_111 байт на владельца. Пороги не снижались.
Первый исследовательский прогон завершился reader503 и в результаты не включён;
после явного ожидания ACK браузерного warm-up до начала API нагрузки nominal
прошёл. Причинную связь с первоначальным503 отдельно не утверждаем.

Чувствительность regression suite проверена временным удалением invalidation
старого ACK из `readingProgress.ts` (error handler и explicit retry). Настоящий
browser E2E после сетевого отказа остался в «Не сохранено» и завершился отказом
ожидания успешного retry. Исходник восстановлен до итоговой проверки; mutant
и generated bundles в Git не включаются.

## Непроверенное и дальнейшая приёмка

Ручной screen-reader smoke на реальной паре browser/assistive technology ещё
нужен; axe/keyboard не заменяют его. Backup/restore согласованной копии БД и
исходников проверяется в #28, итоговый runbook —#29. Эта матрица и зелёный CI
сами по себе не объявляют весь проект production-ready или полностью WCAG-conformant.
