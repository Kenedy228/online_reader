# Роли и владение ресурсами

Владелец согласовал единые аккаунты `users` и две роли: обычный пользователь и
администратор. Администратор не получает доступ к чужой личной библиотеке,
файлам, прогрессу или заметкам. Это основа #17; полное закрытие задачи требует
реальных owner-проверок в #19/#22/#25/#26, которых пока нет.

## Матрица доступа

| Ресурс/действие | Anonymous | User | Admin |
| --- | --- | --- | --- |
| Главная, GET форм, конкретные public assets | Да | Да; GET login/registration перенаправляет на главную | Так же |
| POST регистрации с CSRF | Создаёт только User | Redirect на главную без создания аккаунта | Так же |
| POST logout с CSRF | Без выдачи прав | Завершает сессию | Завершает сессию |
| `/admin/**` | UI302 / JSON401 | 403 | Только актуальная роль/флаги + admin authority в сессии |
| Книга: список/чтение/изменение/удаление | Нет | Только собственная | Только собственная |
| Скачивание/выдача файла | Нет | Только файл собственной книги | Так же, без override |
| Прогресс и заметки | Нет | Только свои, для собственной книги | Так же, без override |
| Любой неразрешённый маршрут/файл | UI302 / JSON401 | 403 | 403 |

Книжные строки — обязательный контракт будущих реализаций, а не утверждение об
уже готовых endpoints. Сейчас библиотека, файлы, прогресс и заметки отсутствуют
и их URL закрыты fallback. Production admin handlers/панели тоже пока нет;
разрешённый админом запрос неизвестного `/admin/*` завершится 404. Test-only
policy probes позволяют проверить filter chain, но не заменяют resource tests.

## Хранение и назначение роли

V4 добавляет `users.role varchar(16) NOT NULL DEFAULT 'User'` с CHECK `'User'`/
`'Admin'`: это существующие имена Java enum, authorities — `ROLE_USER`/`ROLE_ADMIN`.
Все существующие users становятся User. Регистрация явно вставляет `'User'`,
не полагаясь ни на HTTP role, ни на default БД. Admin имеет обе authorities:
обычные функции своей библиотеки плюс отдельно разрешённые административные.

Таблицы `admin`, `actions`, `admin_actions` остаются legacy: сохраняются их данные,
связи и ограничения, но они не участвуют во входе/авторизации. Совпадение login
со значением users.username не доказывает общую личность и не переносит права.
Seed-администратора, общего пароля, public role-update API и автоматического
bootstrap из env нет. Без явного назначения все аккаунты остаются User.

Роль меняет только доверенный оператор с доступом к БД после проверки личности
целевого аккаунта. Email не подтверждён и не подходит для такой проверки. Не
переносите этот SQL в controller и не принимайте роль из регистрации.

Подключение через libpq env (`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`,
`PGPASSWORD`); credentials не передавать в CLI/URL/чат и не shell-source .env.
В интерактивном psql укажите известные ID и username целевого пользователя через
`\set account_id` и `\set account_username`. SQL-quoted `:'variable'` обязателен;
не интерполируйте непроверенный текст в SQL через shell.

```sql
BEGIN;
SELECT id, username, role, enabled, account_non_locked,
       account_non_expired, credentials_non_expired
FROM users
WHERE id = :'account_id'::integer AND username = :'account_username'
FOR UPDATE;
-- Проверьте ровно одну строку и ожидаемый аккаунт. При сомнении: ROLLBACK.
UPDATE users SET role = 'Admin'
WHERE id = :'account_id'::integer AND username = :'account_username'
  AND role = 'User' AND enabled AND account_non_locked
  AND account_non_expired AND credentials_non_expired
RETURNING id, role;
-- Только при ожидаемой одной изменённой строке: COMMIT; иначе ROLLBACK.
```

Для снятия роли используйте такую же проверенную транзакцию с
`UPDATE users SET role='User' WHERE id=:'account_id'::integer AND
username=:'account_username' RETURNING id,role;`. Это процедура оператора,
не команда, которую приложение запускает автоматически. Не печатайте password
или hash при проверке. Backup/восстановление — [database.md](database.md).

## Сессия и актуальные права

AccountPrincipal расширяет штатный Spring Security User: сохраняет неизменяемый
ID аккаунта и snapshot authorities, поддерживает штатное eraseCredentials.
JPA entity/email/hash после входа в сессию не сохраняются; password и credentials
очищаются провайдером. Для новой роли требуется повторный вход; promotion не
должен повышать ранее открытую обычную сессию.

AdminAccessPolicy перед каждым запросом `/admin/**` проверяет session authority
и актуальный users по accountId: username должен совпасть, роль Admin и все
флаги допуска должны оставаться true. Demotion, блокировка, expiry, удаление или
переименование закрывают следующий admin request, даже если в session остался
ROLE_ADMIN. Удаление и создание того же username не дают новой записи старую
сессию. Не заменяйте live-проверку одним hasRole() по snapshot сессии.

Уже прошедший авторизацию запрос отзыв не прерывает; здесь нет распределённого
session store или обещания атомарной отмены выполняющихся операций. Будущие
чувствительные mutations должны проверять authority и owner в своём сервисном
сценарии/транзакции, включая вызовы вне HTTP. После изменения principal обычные
сессии перезапуска приложения не переживают: session persistence выключен.

## Закрытые маршруты и файлы

SecurityConfig разрешает только GET/HEAD главной/форм и конкретных public
ресурсов приложения:4 изображения,7 CSS,2 production JS bundles. POST форм
разрешён явно, CSRF остаётся включён; logout обслуживает штатный security filter.
Остальные запросы — denyAll, отдельно `/admin/**` с live policy. ERROR dispatch
допускается для внутренней обработки ошибок, прямой `/error` не публичный URL.
JSON/UI errors, CSP и внешний header filter — [http-security.md](http-security.md).
При добавлении public assets обновляйте allowlist осознанно, а не разрешайте
`/assets/**`, `/styles/**` или `/js/**` целиком. Source maps не публичны.

Книги хранятся вне static/webroot; allowlist не делает public asset секретным.
Нельзя записывать приватные данные поверх разрешённого CSS/JS/изображения.
Обязательный storage/owner контур и тест реальной выдачи файла — #22. Текущие
static traps только доказывают, что непредусмотренный файл из classpath не
выдаётся через разрешение целого каталога; они не доказывают будущий storage.

## Правила owner для следующих задач

- Получать accountId из доверенного principal и проверять актуальный допуск
  аккаунта на сервере. Username/email/owner_id из запроса не определяют владельца.
- Для коллекций сразу ограничивать SQL текущим владельцем; для item/mutation
  искать по resource ID **и** owner ID, для вложенных данных также по своей книге.
- Владельца новой книги/прогресса/заметки назначает сервер. Не bind entity и
  не выполнять сначала изменение/чтение файла, а потом проверку доступа.
- Админ не получает обход owner-фильтра. Для чужого и отсутствующего private ID
  использовать одинаковый 404 в будущих resource handlers; текущий fallback403
  не считается реализованной проверкой существования/владения.
- #19: два User и Admin, чужие ID в списке/чтении/update/delete и попытка owner_id.
  #22: реальное чтение файла после owner-проверки, отказ до чтения storage, static
  bypass. #25/#26: чужой прогресс/заметка/книга и nested-ID подмена.

#17 остаётся открытой до этих интеграций и тестов; зависимость #19 — слитая
основа ролей/матрица, а не закрытый umbrella issue #17. Иначе очередь образует
цикл. Стек не меняется, дополнительные книжные функции в этот PR не входят.

## Проверки основы

`make check`: настоящие login/session/authorities, anonymous/два User/Admin,
неизвестный handler и static traps, promotion/re-login, live demotion/flags,
удалённый ID/reused username, неактивный legacy admin. Реальный HTTP тест
использует login/header/logout и ServletContext timeout, без AuthRoutes fixture.
`make test-integration`: PostgreSQL V3→V4/legacy links/default/CHECK и реальные
provider/RegistrationService/JPA вызовы с назначением/снятием роли. Регистрация
создаёт User даже при изменённом default БД. Все данные синтетические; внешняя
БД и аккаунты владельца не менялись.
