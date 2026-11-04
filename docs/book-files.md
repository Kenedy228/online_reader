# Загрузка, хранение и скачивание книг

После входа `/library/upload` принимает один EPUB или TXT. Нужен JavaScript:
форма отправляет multipart с нативным CSRF-токеном в заголовке `X-CSRF-TOKEN`.
Body/query token для этого маршрута не заменяет заголовок. Остальные формы
по-прежнему используют штатный CSRF. Успех — 303 к своей карточке; ошибки
показываются формой с подходящим статусом, без имени файла или исключения парсера.
При потере ответа нужно проверить библиотеку перед повтором.

Исходник неизменяем, формат определяется проверкой содержимого, а не MIME клиента.
Имя принимается только как basename без путей/управляющих символов, до 254 code points.
Расширение задаёт проверяемый формат EPUB/TXT; начальное название берётся из basename
и ограничено 200 code points. Автор пока пуст; метаданные меняются в карточке.
Форматы/профиль и продуктовые лимиты — [mvp.md](mvp.md).

## Ограничения и отказы

| Граница | Проверка |
| --- | --- |
| Приём | ≤2 активных запроса; до 11 МиБ multipart, до 10 МиБ исходник |
| Время | До 30с на приём тела, до 10с после получения на обработку |
| Временные данные | До 128 МиБ на экземпляр, включая остатки очистки; резерв 32 МиБ на задачу |
| Библиотека | До 100 записей и 1 ГиБ исходников владельца, включая PROCESSING/DELETING |
| ZIP | До 2000 entries;32 МиБ фактически прочитанных распакованных байтов,8 МиБ на entry; ratio≤100 |
| XML | Глубина 128, суммарно до 64 attributes/namespace declarations на элемент |
| Изображения | PNG/JPEG, до 8192 на сторону и 16 млн pixels; проверка размера перед decode |

`UPLOAD_MAX_FILE_SIZE=10MB` и `UPLOAD_MAX_REQUEST_SIZE=11MB` закреплены MVP;
иные значения отклоняются guard до I/O, пока спецификация и реализация не изменены.
Spring/Servlet multipart отключён намеренно: тело получает неблокирующий receiver,
потом Apache streaming parser. Не включайте native multipart через runtime override.
Адмиссия выполняется до разбора тела, после проверки сессии; CSRF проверяет штатный
filter до чтения. Актуальный accountId/username/роль/флаги проверяются в processing
перед резервированием книги. Это не даёт live SQL до запуска deadline задержать приём.

Receive deadline абсолютный, поэтому trickle не продлевает 30с. Обычный socket idle
`HTTP_CONNECTION_TIMEOUT` может оборвать молча полностью замолчавшее соединение раньше;
клиент обрабатывает это как потерю результата, без ложного сообщения об успехе.
Нет бесконечной очереди: processing использует два workers и нулевую очередь.
Перегрузка — 503 с Retry-After, превышение размера — 413, неподдерживаемое расширение — 415,
невалидная книга — 422, дубликат/квота/отмена — 409. Ожидание owner lock на PostgreSQL
ограничено 1с, statement/network — 2с, транзакция — 5с. HTTP processing deadline отменяет
задачу; остаточный worker продолжает занимать временной резерв до фактического выхода.
Slot запроса освобождается отдельно. Отмена не может прервать следующую задачу того же worker.

## Проверка содержимого и границы доверия

TXT требует строгий UTF-8, допускает начальный BOM; пустой/бинарный текст,
NUL и управляющие символы кроме TAB/CR/LF отклоняются. Исходные байты сохраняются.

EPUB требует mimetype первым stored entry, container/один OPF package, manifest,
XHTML spine и навигацию EPUB2 NCX либо EPUB3 nav. Проверяются root/namespace/parents,
единственность ID и canonical href, относительные ресурсы и пригодный текст.
Fixed layout, неподдерживаемый spine, traversal/absolute paths/backslashes,
symlinks, явные .zip/.epub entries, повреждённые данные/CRC и bombs отклоняются.
Рекурсивная распаковка не выполняется; прочие бинарные ресурсы остаются непрозрачными.
XML не использует DTD/entities/XInclude/внешний resolver; parser не делает сеть.
Layout metadata собирается целиком, включая split CDATA. Media typing нельзя
обойти вторым font alias на тот же XHTML href.

Encryption неизвестного алгоритма или содержимого отклоняется. Допускается только
IDPF font obfuscation на manifest font resource, который не является spine/nav;
шрифт не декодируется и не выдаётся. Это не DRM support. Профиль ограничен и не
означает полной EPUB conformance. Книги могут содержать авторский script/CSS/SVG:
здесь они остаются непрозрачными байтами исходника, не исполняются/не загружаются.
Преобразование и безопасная выдача для чтения —[reader.md](reader.md); raw XML/XHTML не становится HTML origin.

Выбраны pinned Apache Commons Compress1.28.0 (ZIP metadata/symlinks/statistics)
и Commons FileUpload1.6.0 (streaming multipart без Servlet adapters). FileUpload
ограничивает part headers2048 байт, parts≤2 (файл и необязательный CSRF field≤512).
Самописный multipart/ZIP parser не используется. Версии/known fixes проверены по
[Apache FileUpload security](https://commons.apache.org/proper/commons-fileupload/security)
и [Compress security](https://commons.apache.org/proper/commons-compress/security.html).

## Приватное хранилище и единственный экземпляр

`ONLINE_READER_STORAGE_ROOT`: новый приватный каталог вне public resources.
POSIX-права root/sources/staging — 0700; существующие широкие права отклоняются.
На файловой системе с ACL требуется доступ только владельца; без проверяемых
POSIX/ACL permissions запуск запрещён. ACL-путь Windows пока не проверен в этом окружении.
Symlinks в пути/служебных каталогах не допускаются. Имена sources — UUID.book,
из HTTP путь/UUID не принимается. Запись CREATE_NEW не перезаписывает существующий файл.

Root `.lease` исключает двух владельцев каталога. `.identity` —постоянный UUID root;
V6 `book_storage` связывает его с БД. Отдельное PG соединение держит advisory lock
на экземпляр приложения: host JAR и Compose не должны одновременно работать с одной БД.
Неверный root или второй экземпляр отказывают **до recovery**. Потеря DB lease
закрывает файловые операции; требуется устранить причину и перезапустить приложение.
Это local single-instance storage, без HA/разделения работы между узлами.
Не копируйте активное хранилище ради параллельного запуска с той же БД.

Compose монтирует `books-data:/app/storage`; image создаёт каталог UID/GID10001,
0700. Остальная root FS остаётся read-only, временные файлы загрузки находятся
в приватном staging. `make down`/Compose down сохраняют обе volumes. Не используйте
`down -v` и не меняйте identity/binding ради обхода отказа запуска.

## Транзакции, квоты и восстановление

1. Приём multipart резервирует временное место; после bounded streaming parse
   вычисляется SHA-256. В транзакции блокируется текущий owner, проверяются
   dedup/count/bytes, создаётся PROCESSING со своим UUID. Гонки не обходят квоту.
2. После проверки формата исходник записывается CREATE_NEW, flush/fsync,
   затем отдельный commit переводит PROCESSING в READY. Отмена/DELETING не воскресает.
3. Ошибка после резервирования переводит книгу в DELETING. Если БД недоступна,
   marker PROCESSING остаётся занят; recovery при старте либо retry после 60с
   без активного writer переводит его в DELETING. Файлы не теряют учёт квоты.
4. Delete из библиотеки немедленно закрывает доступ. Cleaner с bounded batch/cursor
   повторяет очистку каждые 10с, пропускает активных writers/readers. Только после
   подтверждённого удаления файла удаляется marker/резерв. Отсутствующий файл —успех retry.
   Ошибка удаления оставляет файл/marker/квоту. Незавершённое скачивание удерживает
   резерв до закрытия stream; уже выданные байты отозвать нельзя.

READY duplicate — 409 со ссылкой только на свою карточку. PROCESSING/DELETING
duplicate — 409 со состоянием без ссылки и новой записи/квоты. Между владельцами
байты не переиспользуются и наличие чужой книги не раскрывается.

При рестарте orphan staging очищается; не удалённые остатки продолжают учитываться
в 128 МиБ. PROCESSING считается незавершённой загрузкой и очищается с retry.
READY никогда не удаляется только из-за отсутствия файла; download возвращает 404,
сначала нужно выяснить причину и восстановить согласованные данные.

Перед V6 сохраните DB backup; миграция добавляет binding table, V1–V5 не меняются.
Перед переносом/backup остановите writes/приложение и сохраните согласованные
PostgreSQL + sources + `.identity` с приватными правами. При восстановлении нужны
обе части; новый пустой root нельзя подставлять старой БД. Staging не является
резервной копией. Полная backup/restore приёмка —#28, автоматического down SQL нет.

Прогресса/заметок пока нет. Их удаление при первом DELETING commit обязательно
интегрировать в #25/#26; #19 остаётся открытой до этой проверки.

## Download и проверки

`GET/HEAD /library/books/{id}/download` сначала проверяет live account и owner/READY
в SQL, потом открывает storage. Чужой/отсутствующий ID — одинаковый 404; Admin без override.
MIME — text/plain UTF-8 или application/epub+zip, disposition всегда attachment
с server-generated `book-{id}.txt/epub`, native nosniff/no-store/CSP сохраняются.
Ни исходное имя, ни storage key/path, ни HTML/SVG inline не выдаются.

Focused/unit tests проверяют форматы, budgets, XXE/ZIP/DRM/alias regressions,
права/lease/неперезапись/recovery. Настоящий HTTP container проверяет CSRF,
multipart, headers/owner, duplicate, delete/retry,30s trickle и 10s processing с
fault injection. PostgreSQL IT дополнительно проверяют advisory lease, lock timeout
и атомарную конкурентную квоту. H2 у каждого Spring context отдельная, чтобы старый
scheduled cleaner не затрагивал данные нового. Полная reader/a11y/performance/restore
приёмка остаётся в последующих задачах; unit/HTTP tests её не заменяют.
