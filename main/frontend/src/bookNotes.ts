const editor = document.querySelector<HTMLTextAreaElement>('#note-text');
const counter = document.querySelector<HTMLElement>('#note-count');
const form = editor?.closest('form');
if (editor && counter && form) {
    const initial = editor.value, unsaved = form.dataset.unsaved === 'true';
    let leaving = false;
    const update = (): void => {
        const count = Array.from(editor.value).length;
        counter.textContent = `${count} из 2000 символов`;
        editor.setCustomValidity(count > 2000 ? 'Не больше 2000 Unicode-символов.' : '');
    };
    editor.addEventListener('input', update);
    form.addEventListener('submit', () => { leaving = true; });
    window.addEventListener('beforeunload', event => {
        if (!leaving && (unsaved || editor.value !== initial)) { event.preventDefault(); event.returnValue = ''; }
    });
    update();
    const errors = form.querySelector<HTMLElement>('.errors');
    if (errors) errors.focus();
}
export {};
