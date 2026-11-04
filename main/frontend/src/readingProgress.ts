type Position = { chapter: string; offset: number; fingerprint: string; version: number };
type Snapshot = { revision: number; position: Position | null };
const article = document.querySelector<HTMLElement>('#book-text');
const message = document.querySelector<HTMLElement>('#progress-message');
const actions = document.querySelector<HTMLElement>('#progress-actions');
const manage = document.querySelector<HTMLAnchorElement>('#progress-manage');
const saveButton = document.querySelector<HTMLButtonElement>('#progress-save');
const loadLink = document.querySelector<HTMLAnchorElement>('#progress-load');
const replaceButton = document.querySelector<HTMLButtonElement>('#progress-replace');
const leaveButton = document.querySelector<HTMLButtonElement>('#progress-leave');
const csrf = document.querySelector<HTMLMetaElement>('meta[name=csrf-token]');
const same = (a: Position | null, b: Position | null): boolean => !!a && !!b && a.chapter === b.chapter && a.offset === b.offset && a.fingerprint === b.fingerprint && a.version === b.version;

if (article && message && actions && manage && saveButton && loadLink && replaceButton && leaveButton && csrf) {
    const base = Number(article.dataset.offset), end = Number(article.dataset.end);
    const api = article.dataset.api || '', proof = article.dataset.proof || '';
    let revision = Number(article.dataset.revision);
    let current: Position = { chapter: article.dataset.chapter || '', offset: base, fingerprint: article.dataset.fingerprint || '', version: Number(article.dataset.version) };
    let acknowledged: Position | null = article.dataset.savedChapter ? { chapter: article.dataset.savedChapter,
        offset: Number(article.dataset.savedOffset), fingerprint: article.dataset.savedFingerprint || '', version: Number(article.dataset.savedVersion) } : null;
    let timer = 0, sampleTimer = 0, inFlight: Promise<boolean> | null = null, blocked = false, leaving = false, navigating = false, refreshing = false;
    let destination: (() => void) | null = null;
    actions.hidden = false;
    const dirty = (): boolean => !same(current, acknowledged);
    const status = (text: string, error = false): void => { message.textContent = text; manage.hidden = !error; };
    const schedule = (): void => { window.clearTimeout(timer); if (dirty() && !blocked) timer = window.setTimeout(() => { if (!blocked) void save(); }, 2000); };
    const failed = (code: string): void => {
        window.clearTimeout(timer); acknowledged = null; blocked = true; loadLink.hidden = false; replaceButton.hidden = code !== 'progress_conflict';
        saveButton.textContent = 'Повторить сохранение';
        status(code === 'progress_conflict' ? 'Не сохранено: позиция изменена в другой вкладке.' :
            code === 'stale_reader' ? 'Не сохранено: страница устарела. Откройте книгу заново.' :
            code === 'authentication_required' || code === 'access_denied' ? 'Не сохранено: проверьте вход в аккаунт.' :
            code === 'not_found' ? 'Не сохранено: книга недоступна.' : 'Не сохранено: ошибка связи или сервера.', true);
    };
    const snapshot = (value: unknown): Snapshot | null => {
        if (!value || typeof value !== 'object' || !('revision' in value) || !Number.isInteger(value.revision) || Number(value.revision) < 0 || !('position' in value)) return null;
        const p = value.position;
        if (p !== null && (!p || typeof p !== 'object' || !('chapter' in p) || typeof p.chapter !== 'string' || !('offset' in p) || !Number.isInteger(p.offset) ||
            !('fingerprint' in p) || typeof p.fingerprint !== 'string' || !('version' in p) || !Number.isInteger(p.version))) return null;
        return value as Snapshot;
    };
    const save = (): Promise<boolean> => {
        window.clearTimeout(timer);
        if (inFlight) return inFlight;
        if (!dirty()) return Promise.resolve(true);
        const position = { ...current }, expected = revision;
        status('Сохраняем позицию…');
        inFlight = (async (): Promise<boolean> => {
            const abort = new AbortController(), timeout = window.setTimeout(() => abort.abort(), 5000);
            try {
                const response = await fetch(api, { method: 'PUT', credentials: 'same-origin', redirect: 'error', keepalive: true,
                    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-CSRF-TOKEN': csrf.content },
                    body: JSON.stringify({ revision: expected, position, proof }), signal: abort.signal });
                const value: unknown = await response.json();
                if (!response.ok) {
                    const code = value && typeof value === 'object' && 'code' in value && typeof value.code === 'string' ? value.code : 'request_failed';
                    failed(code); return false;
                }
                const result = snapshot(value);
                if (!result || result.revision !== expected + 1 || !same(position, result.position)) { failed('request_failed'); return false; }
                revision = result.revision; acknowledged = position; blocked = false;
                loadLink.hidden = true; replaceButton.hidden = true; leaveButton.hidden = true; destination = null;
                saveButton.textContent = 'Сохранить позицию'; status(dirty() ? 'Есть несохранённая позиция.' : 'Позиция сохранена.');
                if (dirty()) schedule();
                return true;
            } catch { failed('request_failed'); return false; }
            finally { window.clearTimeout(timeout); inFlight = null; }
        })();
        return inFlight;
    };
    // Only bounded canonical article text participates; hidden LF separators still count in DOM ranges.
    const capture = (): void => {
        if (navigating || end === base) return;
        const bounds = article.getBoundingClientRect();
        if (bounds.top >= window.innerHeight) return;
        const bar = document.querySelector<HTMLElement>('#progress-status');
        const line = Math.max(0, Math.min(window.innerHeight - 1, bar?.getBoundingClientRect().bottom || 0));
        let offset = bounds.bottom <= line ? Math.max(base, end - 1) : current.offset;
        const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT);
        let node: Node | null;
        while ((node = walker.nextNode())) {
            const text = node.textContent || ''; if (!text) continue;
            const range = document.createRange(); range.selectNodeContents(node);
            if (!Array.from(range.getClientRects()).some(rect => rect.bottom > line && rect.top < window.innerHeight)) continue;
            let low = 0, high = text.length;
            while (low < high) {
                const middle = Math.floor((low + high) / 2);
                let index = middle; if (index > 0 && /[\uDC00-\uDFFF]/.test(text[index])) index--;
                const width = (text.codePointAt(index) || 0) > 0xffff ? 2 : 1;
                range.setStart(node, index); range.setEnd(node, Math.min(text.length, index + width));
                if (range.getBoundingClientRect().bottom <= line) low = middle + 1; else high = middle;
            }
            if (low > 0 && /[\uDC00-\uDFFF]/.test(text[low] || '')) low--;
            range.selectNodeContents(article); range.setEnd(node, low);
            offset = Math.min(end - 1, base + Array.from(range.toString()).length); break;
        }
        if (offset !== current.offset) {
            current = { ...current, offset };
            if (!blocked) { status('Есть несохранённая позиция.'); schedule(); }
        }
    };
    const sample = (): void => { if (!sampleTimer) sampleTimer = window.setTimeout(() => { sampleTimer = 0; capture(); }, 100); };
    const flush = async (): Promise<boolean> => {
        capture(); window.clearTimeout(timer);
        if (blocked) return false;
        if (inFlight && !await inFlight) return false;
        for (let attempt = 0; attempt < 2 && dirty(); attempt++) if (!await save()) return false;
        return !dirty();
    };
    const navigate = async (go: () => void): Promise<void> => {
        if (navigating) return;
        const success = await flush();
        if (success) { leaving = true; navigating = true; go(); }
        else { destination = go; leaveButton.hidden = false; actions.focus(); }
    };
    window.addEventListener('scroll', sample, { passive: true });
    window.addEventListener('resize', sample);
    saveButton.addEventListener('click', () => { if (refreshing) return; capture(); acknowledged = null; blocked = false; void save(); });
    loadLink.addEventListener('click', event => { event.preventDefault(); if (refreshing) return; leaving = true; window.location.assign(loadLink.href); });
    replaceButton.addEventListener('click', () => {
        if (inFlight || navigating) return;
        capture(); refreshing = true; replaceButton.disabled = true; saveButton.disabled = true; loadLink.setAttribute('aria-disabled', 'true');
        void (async () => {
            const abort = new AbortController(), timeout = window.setTimeout(() => abort.abort(), 5000);
            try {
                const response = await fetch(api, { credentials: 'same-origin', redirect: 'error', headers: { Accept: 'application/json' }, signal: abort.signal });
                const latest = snapshot(await response.json());
                if (!response.ok || !latest) { failed('request_failed'); return; }
                revision = latest.revision; acknowledged = null; blocked = false; await save();
            } catch { failed('request_failed'); }
            finally { window.clearTimeout(timeout); refreshing = false; replaceButton.disabled = false; saveButton.disabled = false; loadLink.removeAttribute('aria-disabled'); }
        })();
    });
    leaveButton.addEventListener('click', () => { if (destination) { leaving = true; destination(); } });
    document.querySelector<HTMLAnchorElement>('#progress-font')?.addEventListener('click', event => {
        event.preventDefault();
        // Jumping to controls in the same document would make their newly visible text look like a rewind.
        void navigate(() => {
            const url = new URL(loadLink.href);
            url.searchParams.set('chapter', current.chapter); url.searchParams.set('offset', String(current.offset));
            url.searchParams.set('fingerprint', current.fingerprint); url.searchParams.set('version', String(current.version));
            url.searchParams.set('font', document.querySelector<HTMLSelectElement>('#reader-font')?.value || 'normal');
            url.hash = 'reader-settings'; window.location.assign(url.href);
        });
    });
    document.addEventListener('click', event => {
        const link = event.target instanceof Element ? event.target.closest<HTMLAnchorElement>('a[href]') : null;
        if (!link || link === loadLink || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || link.target || link.hasAttribute('download')) return;
        const url = new URL(link.href);
        if (url.origin !== window.location.origin || (url.pathname === window.location.pathname && url.search === window.location.search && url.hash)) return;
        event.preventDefault(); void navigate(() => window.location.assign(url.href));
    });
    document.addEventListener('submit', event => {
        if (leaving || !(event.target instanceof HTMLFormElement)) return;
        const form = event.target, submitter = event instanceof SubmitEvent && event.submitter instanceof HTMLButtonElement ? event.submitter : undefined;
        event.preventDefault(); capture();
        void navigate(() => {
            if (form.classList.contains('reader-settings')) {
                const offset = form.querySelector<HTMLInputElement>('input[name=offset]'); if (offset) offset.value = String(current.offset);
            }
            // A resolved flush can resume before the original submit algorithm clears its reentrancy flag.
            window.setTimeout(() => form.requestSubmit(submitter), 0);
        });
    });
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && !blocked) { capture(); void save(); } });
    window.addEventListener('pagehide', () => { if (!blocked) { capture(); void save(); } });
    window.addEventListener('beforeunload', event => { if (!leaving && dirty()) { event.preventDefault(); event.returnValue = ''; } });
    status(dirty() ? 'Есть несохранённая позиция.' : 'Сохранённая позиция восстановлена.');
    schedule();
}
export {};
