# HTTP security и ошибки

Политика относится к существующим Spring MVC/Thymeleaf формам и защищённой
административной границе. Это реализация основы #18; реальные user-file
Content-Type/Disposition и изоляция активного контента будут проверены в #22.
Пока файловых endpoints нет, #18 не закрывается по этому критерию.

## Измеренные defaults и явная конфигурация

На Spring Security6.5.11 уже действовали CSRF, native session/XOR token handling,
Cache-Control no-store, X-Content-Type-Options nosniff, X-Frame-Options DENY,
X-XSS-Protection0 и HSTS только для HTTPS. CSP и Referrer-Policy отсутствовали;
HTTPS HSTS по умолчанию включал includeSubDomains. Это проверено regression
тестами, а не выводом по отсутствию DSL-настроек.

Штатные HeaderWriter теперь выполняются один раз в HeaderWriterFilter,
зарегистрированном через FilterRegistrationBean **перед FilterChainProxy**,
с eager writing. Только inner headers config в HttpSecurity отключён, чтобы
не писать другой набор headers второй раз. Native CSRF/filter chain остаются.

Причина: реальный StrictHttpFirewall отказ400 происходит до внутреннего
HeaderWriterFilter; при размещении только в HttpSecurity в этом ответе не было
CSP. Перенос наружу проверен настоящим Tomcat HTTP-тестом. Не заменяйте эту схему
одним inner headers DSL и не регистрируйте filter bean второй раз автоматически.
Order вычисляется относительно SecurityProperties.filter.order.

| Header | Политика |
| --- | --- |
| Content-Security-Policy | enforce, см. ниже |
| X-Frame-Options | DENY |
| X-Content-Type-Options | nosniff |
| Referrer-Policy | no-referrer |
| Cache-Control | native no-cache, no-store, max-age=0, must-revalidate |
| Pragma / Expires | native no-cache / 0 |
| X-XSS-Protection | 0: старый browser auditor выключен, его не считаем защитой от XSS |
| Strict-Transport-Security | только request.isSecure(), max-age31536000, без includeSubDomains/preload |

CSP:

```text
default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self';
font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none';
form-action 'self'; frame-ancestors 'none'
```

Inline scripts/styles, eval, сторонние CDN, объекты и frames не разрешаются.
Скрипты, CSS и изображения приложения обслуживаются с того же origin и только
по allowlist. Не ослабляйте CSP через unsafe-inline/eval ради новой страницы;
выносите код в существующую frontend/static сборку. CSSOM изменения отдельных
style properties скриптом не равны разрешению inline style tags/attributes.
Для будущей читалки новые источники/frames требуют отдельной модели доверия.

HSTS не навязывается поддоменам неизвестного deployment. Local HTTP не получает
HSTS и использует Secure=false cookie; production cookie Secure=true требует
реального HTTPS. X-Forwarded-Proto не делает запрос secure: forwarding strategy
none сохранена. Положительный HSTS-тест задаёт secure flag в MockMvc, а не
поднимает внешний TLS. TLS/proxy/deployment не входят в текущую работу.

Headers проверены на обычных страницах, assets, redirects, validation/error
responses и firewall400. Ошибки TLS/прокси или синтаксического разбора HTTP до
входа в servlet filters не обещают application headers.

## CSRF и mutations

Используются native HttpSessionCsrfTokenRepository и XorCsrfTokenRequestAttributeHandler
(BREACH protection). POST/PUT/PATCH/DELETE не исключаются из проверки;
GET/HEAD/OPTIONS/TRACE следуют native safe-method matcher, бизнес-эффекты в GET
не добавляются. Whitelist URL не отключает CSRF. Tokens передаются штатными
Thymeleaf form actions; их не выносить в URL/логи. Два бюджета попыток считаются
только после CSRF. Тесты missing/invalid/cross-session token доказывают отказ до
записи аккаунта/аутентификации/выхода. GET logout не завершает сессию.

Текущие mutations — регистрация, login и logout. Новые endpoints в #19/#22/#25/#26
требуют CSRF и server-side authority/owner независимо от client validation.
Не добавляйте CORS/CSRF exclusions или trusted forwarded headers без задачи.

## HTTP ответы

| Сценарий | Ответ |
| --- | --- |
| Anonymous protected/unknown URL, HTML или обычный браузерный Accept | 302 на login, request cache выключен |
| Anonymous protected/unknown URL, явный application/json/problem+json/*+json Accept | 401, ProblemDetail |
| Вошедший пользователь без права | 403 |
| Missing/invalid/cross-session CSRF | 403, без domain effect |
| Ошибка полей регистрации | 200 HTML с безопасными field errors; оба пароля пусты |
| Успешная регистрация | 302 /login?registered, без autologin |
| Неверные credentials formLogin | 302 /login?error; это HTML form endpoint, не JSON login API |
| Успешный logout с CSRF | 302 /login?logout, invalidation/delete cookie |
| Превышение бюджета login/registration | 429 plain text + Retry-After; credentials не отражаются |
| Разрешённый сервером, но отсутствующий handler/resource | 404 |
| Непредвиденная ошибка приложения | 500 с общим сообщением |

Неразрешённый URL у вошедшего пользователя закрывается403, а не раскрывает
наличие handler. Реальный missing resource404 проверен после admin authorization.
Будущие чужой/отсутствующий private ID возвращают одинаковый404 через owner-query
(см. authorization.md), не просто через fallback.

Security entrypoint/denied handler используют sendError для401/403; настоящий
ERROR dispatch обрабатывает HttpErrorController. HTML-клиент получает error.html,
JSON-клиент — application/problem+json с type/title/status/detail и фиксированным
instance=/error. Исходные URI/query, exception/message/cause/stack, SQL, rejected
passwords, username/email и ErrorAttributes не копируются в model/ответ.
В error HTML нет персонализированного account header. Status берётся только из
серверного ERROR_STATUS_CODE, неподходящее значение становится500.

Error endpoint закреплён /error, whitelabel выключен; unsafe overrides отклоняются
runtime guard. Прямой HTTP /error по-прежнему не публичный маршрут: разрешён
внутренний ERROR dispatch. При изменении обработки ошибок не возвращайте raw
exception.getMessage() или include-* flags. Логирование/корреляция/health из #24
здесь не реализуются; sanitization ответа не обещает очистку любых серверных логов.

## Cache и будущие файлы

Включая главную с персональным header, login/registration/error страницы и
редиректы используют no-store. Текущие assets тоже не кешируются; отдельная
оптимизация immutable static cache потребует tests, не ослабляя private policy.
Native writer не запрещает controller явно переопределить Cache-Control:
для private handlers это недопустимо, новые endpoints должны иметь regression.

В #22 файл выдаётся после owner/authority проверки, с MIME из проверенного
allowlist фактических форматов, nosniff и безопасным Content-Disposition.
Скачивание по умолчанию attachment; неизвестный/непредназначенный для inline
тип — application/octet-stream. Filename ограничивается и кодируется штатным
ContentDisposition API, без конкатенации ввода/CRLF в headers. Не отдавайте
пользовательский HTML/SVG/XML или исходный активный документ inline в origin
приложения; reader требует отдельной sanitization/sandbox/origin политики.

Сейчас это контракт #22, а не готовый download handler. Не выдавайте проверку
logo.svg или test-only файла за доказательство защиты реальных книг. #18 остаётся
открытой до real MIME/disposition/cache/active-content evidence из #22.

## Проверки

`make check` проверяет header policy, secure-request/XFP distinction, JSON401,
настоящий Tomcat error dispatch400/401/403/404/500, firewall400, validation200,
CSRF missing/invalid/cross-session, GET без записи и неудачный logout без потери
сессии. Старые native login/logout/roles/rate regressions сохранены. PostgreSQL/
Mailpit интеграции — `make test-integration`; внешние DB/SMTP не используются.

Browser smoke выполняется с CSP включённой, без bypassCSP: реальные account
flows/toggle/resources работают без app CSP violations, внедрённый inline script
заблокирован; resource requests не несут Referer. Error403 проверен клавиатурой,
на320px и200% zoom, выполнен scoped axe. Audit library загружена через временный
same-origin browser route, не через новый production asset или unsafe-inline.
Это не полный WCAG/ASVS аудит. Preview и данные синтетические, сервер остановлен
после проверки; deployment не выполнялся.
