const form = document.querySelector<HTMLFormElement>('#book-upload');
const button = form?.querySelector<HTMLButtonElement>('button[type=submit]');
const file = form?.querySelector<HTMLInputElement>('#file');
const csrf = form?.querySelector<HTMLInputElement>('input[name=_csrf]');
const errorBox = document.querySelector<HTMLElement>('#upload-error');
const message = document.querySelector<HTMLElement>('#upload-message');
const status = document.querySelector<HTMLElement>('#upload-status');

if (form && button && file && csrf && errorBox && message && status) {
    const contextPath = new URL(form.action).pathname.replace(/\/library\/upload$/, '');
    const bookPrefix = contextPath + '/library/books/';
    const isBookPath = (path: string): boolean => path.startsWith(bookPrefix) && /^[1-9][0-9]*$/.test(path.slice(bookPrefix.length));
    button.disabled = false;
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        if (!form.reportValidity() || button.disabled) return;
        button.disabled = true;
        errorBox.hidden = true;
        errorBox.querySelector('a')?.remove();
        status.textContent = 'Загружаем и проверяем книгу…';
        form.setAttribute('aria-busy', 'true');
        const abort = new AbortController();
        const timeout = window.setTimeout(() => abort.abort(), 45000);
        const failures: Record<number, string> = { 403: 'Сессия истекла. Обновите страницу и повторите загрузку.',
            408: 'Загрузка заняла слишком много времени. Повторите попытку.',
            413: 'Размер файла не должен превышать 10 МиБ.',
            503: 'Загрузка временно недоступна. Повторите позже.' };
        const cleanup = (): void => { window.clearTimeout(timeout); status.textContent = ''; button.disabled = false; form.removeAttribute('aria-busy'); };
        void fetch(form.action, {
            method: 'POST', body: new FormData(form), credentials: 'same-origin',
            headers: { 'X-CSRF-TOKEN': csrf.value, Accept: 'text/html' }, signal: abort.signal,
        }).then(async (response) => {
            const location = new URL(response.url);
            if (response.ok && response.redirected && location.origin === window.location.origin && isBookPath(location.pathname)) {
                window.location.assign(location.href);
                return;
            }
            if (response.redirected && location.origin === window.location.origin && location.pathname === contextPath + '/login') {
                window.location.assign(response.url);
                return;
            }
            const html = new DOMParser().parseFromString(await response.text(), 'text/html');
            const detail = html.querySelector('#upload-message')?.textContent;
            message.textContent = detail || failures[response.status] || 'Не удалось добавить книгу. Проверьте файл и повторите попытку.';
            const duplicate = html.querySelector<HTMLAnchorElement>('#upload-duplicate');
            const path = duplicate?.getAttribute('href');
            if (path && isBookPath(path)) {
                const link = document.createElement('a');
                link.href = path; link.textContent = 'Открыть имеющуюся книгу'; errorBox.append(link);
            }
            errorBox.hidden = false; errorBox.focus();
        }).catch(() => {
            message.textContent = 'Не удалось получить результат загрузки. Проверьте библиотеку перед повторной попыткой.';
            errorBox.hidden = false; errorBox.focus();
        }).then(cleanup, cleanup);
    });
}

export {};
