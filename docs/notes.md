# Личные заметки

Заметка —обычный текст от1 до2000 Unicode code points у неизменяемой позиции
EPUB/TXT. На книгу допускается≤200 заметок. Нет Markdown/HTML, общего доступа,
названий заметок или переноса между книгами. Контракт MVP —[mvp.md](mvp.md),
позиции —[reader.md](reader.md), сохранение чтения —[progress.md](progress.md).

## Сценарий и HTTP

В читалке «Заметка» сначала ожидает flush прогресса и открывает форму у текущего
видимого Unicode offset, включая корректный счёт supplementary characters и
canonical LF. Без JavaScript ссылка создаёт заметку у начала открытого фрагмента.
«Заметки книги» доступны в карточке; «Добавить заметку» в списке использует
сохранённый progress либо начало книги. Native формы создания/изменения/удаления
работают без JS. JS добавляет Unicode counter, validation и предупреждение о
несохранённом черновике; не отправляет автоматических note mutations.

Это server-rendered HTML, отдельного JSON API заметок нет:

| Метод/путь после `/library/books/{bookId}/notes` | Поведение |
| --- | --- |
| GET/HEAD пустой suffix | Свой список; page=0,size=20, size≤50, page≤10000 |
| GET/HEAD `/new` | Пустая форма с проверенным locator и proof |
| POST пустой suffix | Создание; native CSRF;303 на созданную заметку |
| GET/HEAD `/{noteId}` | Текст и ссылка к месту в книге |
| GET/HEAD + POST `/{noteId}/edit` | Изменить только текст; успешный POST303 |
| GET/HEAD + POST `/{noteId}/delete` | Явное подтверждение с revision; POST303 в список |

Порядок списка —created_at DESC,id DESC, preview≤200 code points; полный текст
открывается отдельно. Metadata книги не меняет привязку. Edit form принимает
только text/version, delete —version/confirmed, create —text и locator/proof.
Unknown fields отвергаются400. Header binding отключён: User-Agent/Content-Type
не являются полями формы или альтернативным источником текста. Spring предлагает binder также URI variables:
bookId/noteId исключаются из проверки suppressed fields, но DTO не имеет их
setter, authoritative IDs берутся только из @PathVariable. Owner/role не назначаются
через форму. CSRF и закрытый URL allowlist сохранены, JSON/OpenAPI прогресса не меняется.

Неверный тип/диапазон locator —400; чужой/отсутствующий/non-READY book/note —404;
потеря права аккаунта —403. HTML validation возвращает200 с ошибками и черновиком,
устаревшая revision/proof и квота —409. Ошибки повторного разбора locator сохраняют
статус reader (400/404/409/422/503) и не отражают ввод/пути/исключения.303 не включает
текст формы в query string. Внешние SMTP/БД для сценария не нужны.

## Текст, ввод и безопасный вывод

CRLF/CR превращаются в LF в form setter **до** @CodePointLength: native textarea
отправляет LF как CRLF, это не должно превращать валидные2000 символов в3000.
Service повторно проверяет текст, убирает крайние пробелы/переносы, отвергает
пустоту, unpaired UTF-16 surrogates и Cc кроме TAB/LF/CR (последний нормализуется).
Нормализация Unicode NFC не выполняется; считаются code points, не grapheme clusters.

Textarea maxlength4000 UTF-16 units допускает2000 supplementary символов;
JS считает Array.from(value).length и показывает лимит2000, сервер остаётся
источником проверки. При ошибке обычный native draft до4000 units сохраняется
полностью. Для злонамеренного oversized POST вывод ограничен8192 units без разрыва
surrogate pair с явным сообщением об ограничении; ничего не сохраняется автоматически.
Форма выводит bounded DTO через th:text, не BindingResult.rejectedValue. Error ID
контейнеры остаются в DOM даже без ошибок для корректных aria-describedby.

Все view используют th:text/th:field escaping и CSS white-space:pre-wrap; sanitizer
не нужен, поскольку заметка не интерпретируется как разметка. `<script>`, `</textarea>`
и HTML attributes сохраняются как текст, не исполняются. Fingerprint/proof/DB keys
не печатаются в пользовательском описании; hidden locator нужен только transport.

## Владелец, позиция и версии

Чтение проверяет CurrentAccount и own READY book до поиска заметки. Note query
ограничена одновременно noteId/bookId/ownerId: подмена вложенной книги не открывает
чужую заметку. Admin имеет те же ограничения. Create/edit/delete используют
FileBookTransactions.lockReady до первой загрузки entity и держат owner book lock
до commit. Quota count и insert сериализованы этой блокировкой. JPA @Version и
явное сравнение version отвергают устаревшие edit/delete409; client timestamp нет.

После409 edit показывает отдельно текущий сохранённый текст и оставляет черновик,
обновляя hidden version; повторное сохранение —явная замена после сравнения.
Delete409 обновляет version и снимает confirmation; пользователь подтверждает
удаление заново. Для уже удалённой заметки повторный запрос может вернуть404.
Потеря HTTP ответа не означает подтверждения записи: перед повтором create POST
проверьте список —создание не идемпотентно и может создать вторую заметку.

Create использует тот же ReadingPosition/RangeProof, что progress. Валидный proof
проверяется без filesystem I/O, owner всё равно проверяется отдельно. GET с
отсутствующим/устаревшим proof проверяет позицию через bounded BookReader и выдаёт
новый proof. При старом proof и действующей сессии/CSRF POST409 оставляет draft,
повторно проверяет locator **вне** write transaction и обновляет proof; сохранить
можно лишь явным повторным POST. Потеря сессии/CSRF проверяется раньше и даёт
login redirect/403 без записи; сохранение не подтверждено, черновик не является
серверным autosave. Другой fingerprint не принимается и молча не
переносится. Позиция заметки при редактировании текста immutable.

Если версия представления/исходника перестала совпадать, существующий текст остаётся
доступен для чтения, изменения и удаления, но ссылка к позиции скрыта с явным
сообщением. Для другой книги/исходника создаётся новая заметка в нужном месте.

## Схема и удаление

V8 добавляет book_notes с composite owner FK к books и ON DELETE CASCADE, @Version,
created_at, plain text с DB length1…2000 и locator constraints. PostgreSQL length
считает2000 emoji как2000, не4000 UTF-16 units. Row count≤200 —business invariant
под book lock, а не ненадёжная exists-then-insert проверка без сериализации.

Первая транзакция логического удаления книги удаляет notes и progress и отмечает
DELETING. Новый writer после этого получает404; writer до delete завершается,
а его row удаляется тем же delete commit. Source/quota marker остаются до успешного
physical cleanup, FK cascade также защищает финальное удаление. Восстановление
schema при откате: остановить writes и восстановить согласованный pre-V8 DB+storage
backup ([database.md](database.md)); V1–V7/Flyway history не менять, clean не применять.

## Проверка

Обычная suite: Unicode/CRLF/controls, MVC CRUD/re-read, XSS plaintext, retained draft,
proof retry, stale revision/confirmation, nested owner/Admin/flags/CSRF, pagination,
stale locator и атомарное удаление notes+progress. `make test-integration` включает
NotesPostgresIT: version race,199→200 quota race, stable pages, supplementary TEXT,
app restart, composite FK/length/cascade и реальное ожидание writer против delete;
V7→V8 сохраняет книги и progress. H2 не заменяет эти PostgreSQL проверки.

Browser acceptance: actual reader cursor→note после flush, native CRLF2000CP и emoji,
CRUD/XSS/409 retry/delete, reload/login, JS-off формы, keyboard/focus,320px/200% и
scoped axe. Ручной screen reader, nominal p95/backup recovery относятся к #27/#28,
не доказываются unit suite или автоматическим scanner.
