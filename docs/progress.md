# Прогресс чтения

Читалка сохраняет текущую Unicode-позицию в аккаунте и открывает её при следующем
входе или перезапуске приложения. Bare `/library/books/{id}/read` восстанавливает
сохранённый locator; явные chapter/offset/anchor из оглавления, шрифта или ссылки
имеют приоритет. Смена названия/автора locator не меняет. Исходник на месте не
заменяется; другой файл — другая книга. Контракт позиции —[reader.md](reader.md).

## Persistence и конкуренция

V7 добавляет `reading_progress`: одна запись на book, явный owner_id и composite FK
(book_id,owner_id) к books, fingerprint, representation_version, chapter, Unicode
position_offset, server revision и updated_at. DB ограничивает диапазоны и
исключает чужой owner. FK каскадно удаляет progress при физическом удалении book.
В первой транзакции подтверждённого логического удаления BookService удаляет
progress и переводит книгу в DELETING; storage cleanup/quota reservation не меняются.

ProgressService проверяет CurrentAccount и owner/READY под блокировкой книги до
эффекта. FileBookTransactions.lockReady получает owner-scoped FOR UPDATE до
загрузки Book в persistence context: предварительный download может оставить
старый managed snapshot и вызвать optimistic-lock ошибку после ожидания удаления.
Save и delete используют одну блокировку. Если delete завершился первым —404;
если save первым —его строка удаляется транзакцией delete. Чужой User/Admin и
отсутствующий book дают одинаковый404, не требуют доступа к storage.

Native параметризованный INSERT ON CONFLICT DO NOTHING создаёт revision1 при
expected revision0; UPDATE меняет только owner/book с совпадающей revision и
увеличивает её на1. Другой результат —409 progress_conflict, без silent overwrite.
Revision — int32; последняя допустимая mutation revision2147483646. Client timestamp
не используется. Потеря ответа может означать, что запись уже применена:
повтор со старой revision даст409, предлагая явный выбор.

## Подтверждение диапазона

Сервер выдаёт странице HmacSHA256 proof диапазона, уже проверенного BookReader:
accountId, bookId, source fingerprint, representation version, chapter, from/next.
Offset должен находиться в этом диапазоне; для непустого фрагмента next исключён,
для пустой главы разрешён0. Ключ —32 случайных байта в памяти экземпляра; proof
не даёт прав и не заменяет live owner/READY проверку. Пользователь не задаёт
owner_id, размер главы или валидный диапазон из собственных неподписанных данных.

Это позволяет сохранять позицию без повторного hashing/parsing всей книги каждые
2с. Нет постоянного per-book cache, отдельной библиотеки токенов или env-секрета.
При перезапуске ключ меняется: старые страницы получают409 stale_reader и требуют
переоткрытия книги, сохранённый progress в PostgreSQL остаётся. Другой fingerprint
отклоняется409 stale_position, неподдерживаемая версия —422; позиция не переносится молча. Proof привязан
к открытому фрагменту; переход создаёт новый proof. Источник остаётся immutable.

## HTTP и ограничения

Канонический контракт —[openapi.yaml](openapi.yaml):

- GET `/api/v1/books/{id}/reading-progress`: `{revision,position}`, при отсутствии
  `{revision:0,position:null}`. Position содержит chapter/offset/fingerprint/version.
- PUT того же ресурса: `{revision,position,proof}`;201 при создании,200 при замене,
 409 при конфликте/устаревшем proof, без записи. Поля неизвестного owner, aliases,
  duplicate keys/trailing JSON, дробные и строковые integer не принимаются.
- Сессия и native CSRF обязательны, PUT требует X-CSRF-TOKEN; auth/API errors
  имеют фиксированные code/message без ввода/SQL/путей/исключений. Headers, CSP,
  denyAll и UI-ошибки других маршрутов сохранены. Все ответы no-store.
- JSON UTF-8 ≤2048 bytes, depth≤8, string≤256 и number≤12 символов. Приём тела
  неблокирующий, суммарно≤5с:408 timeout,413 размер. Проверки auth/CSRF идут раньше
  body admission. Для формы PUT возвращается415; Spring FormContentFilter выключен
  и guarded, чтобы не читать form-body до security/ограниченного receiver.
- PUT budget —100 попыток/минуту на accountId, не IP, память≤4096 активных окон;
  ошибки тоже считаются, CSRF отказы не считаются. Исчерпание —429 с Retry-After,
  активные counters не вытесняются. Системные DB/lease ошибки —503 без ложного успеха.

`spring.mvc.formcontent.filter.enabled=false` обязателен во всех профилях.
Существующие HTML формы используют POST и работают штатно; публичного form PUT
или нового Spring multipart parser нет. Runtime guard отклоняет override до I/O.

## Клиент и восстановление после ошибок

TypeScript определяет первую видимую Unicode-позицию в ограниченном тексте статьи;
скрытые canonical LF остаются в DOM и учитываются Range, surrogate pair не режется.
Шрифт и viewport не входят в locator. Debounce2с отправляет только последнюю позицию;
один in-flight request и одно текущее значение, без offline/localStorage очереди.
Статус «сохранено» появляется только после совпадающего server ACK/revision.

При ошибке отменяются pending timers, предыдущий ACK больше не считается
доказательством текущего состояния сервера. Автоматические retry останавливаются,
доступна явная повторная попытка.409 предлагает загрузить сохранённую позицию или
явно заменить её текущей: GET revision, затем CAS PUT; новая гонка снова409.
401/403/404, timeout, offline и неизвестный результат не становятся успехом.
Статус объявляется через role=status; управление сохранением доступно клавиатурой.

Переходы по обычным ссылкам, главам, шрифт и logout ожидают flush; при сбое переход
останавливается с возможностью явно уйти без сохранения. Шрифт использует последнюю
подтверждённую позицию непосредственно перед submit. Sticky-ссылка настройки
сначала сохраняет locator и открывает новый фрагмент с ним: прокрутка к форме
не считается перемоткой книги. Повторный native submit запускается в следующей
задаче event loop, после снятия reentrancy-флага исходного submit-события браузером. Visibility/pagehide отправляют
best-effort keepalive PUT; закрытие браузера/авария не гарантируют доставку, пока нет
ACK. При несохранённом состоянии beforeunload запрашивает предупреждение браузера;
его показ зависит от браузера и пользовательской активации. Нет бесконечных retries.
Без JavaScript чтение и SSR восстановление работают, автосохранение выключено и
это явно показано; адрес фрагмента можно сохранить самостоятельно.

## Проверки и восстановление схемы

Обычная suite проверяет HMAC/диапазоны, настоящий HTTP/CSRF/owner/Admin, JSON
coercion/лимиты/deadline/rate, новую сессию, revision и логическое удаление.
`-Ppostgres-it` проверяет V6→V7 без потери книги, реальные JPA/CAS/constraints/FK,
blocked writer против delete, перезапуск приложения и смену proof key.
Browser checks должны включать scroll/Unicode/debounce/flush, вкладки409/replace,
network failure/ambiguous ACK, шрифт при in-flight save, native logout/шрифт без
dirty-состояния, sticky font-ссылку с точным checkpoint, keyboard/320px/200%/axe.
Ручной screen reader и p95 относятся к отдельной приёмке, не доказываются сборкой.

До применения V7 нужна согласованная резервная копия PostgreSQL+storage при
остановленных writes ([database.md](database.md)). Миграция additive, старые книги
не меняет и не синтезирует прогресс. Для отката остановите writes и восстановите
pre-V7 snapshot вместе со storage; не редактируйте Flyway history/старые миграции,
не запускайте clean и не удаляйте таблицу с позициями как скрытый rollback.

Личные заметки используют тот же locator/range proof, но сохраняются явно
нативной формой. Reader note-link ждёт progress flush и передаёт актуальный cursor;
book DELETING удаляет обе коллекции в первом commit. Контракт —[notes.md](notes.md).
