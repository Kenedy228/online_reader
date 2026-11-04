const assert = require("node:assert/strict");
const AxeBuilder = require("@axe-core/playwright").default;
const { base, root, launch } = require("./fixture.cjs");
let debugPage, activeBrowser;
(async () => {
  const browser = await launch();
  activeBrowser = browser;
  const context = await browser.newContext({
      viewport: { width: 1280, height: 900 },
    }),
    page = await context.newPage();
  debugPage = page;
  const external = [],
    csp = [];
  page.on("dialog", (d) => d.accept());
  page.on("request", (r) => {
    if (!r.url().startsWith(base) && !r.url().startsWith("data:"))
      external.push(r.url());
  });
  page.on("console", (m) => {
    if (/Content Security Policy|Refused to/.test(m.text())) csp.push(m.text());
  });
  async function login(p, name) {
    await p.goto(base + "/login");
    await p.locator("[name=username]").fill(name);
    await p.locator("[name=password]").fill("Synthetic-preview-password");
    await Promise.all([
      p.waitForURL(base + "/"),
      p.getByRole("button", { name: "Войти", exact: true }).click(),
    ]);
  }
  async function audit(p, name) {
    const viewport = p.viewportSize();
    await p.setViewportSize({ width: 320, height: 900 });
    await p.evaluate(() => (document.documentElement.style.fontSize = "200%"));
    assert(
      await p.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      name + " overflow320/200",
    );
    await p.screenshot({ path: root + "/notes-" + name + "-mobile200.png" });
    await p.evaluate(() => (document.documentElement.style.fontSize = ""));
    await p.setViewportSize(viewport);
    const r = await new AxeBuilder({ page: p })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
      .analyze();
    assert.deepEqual(
      r.violations.map((v) => ({
        id: v.id,
        nodes: v.nodes.map((n) => n.target),
      })),
      [],
      name,
    );
    console.log("PASS axe+320/200 " + name);
  }
  async function saved(p = page) {
    await p.waitForFunction(() =>
      /Позиция сохранена\.|Сохранённая позиция восстановлена\./.test(
        document.querySelector("#progress-message")?.textContent || "",
      ),
    );
  }
  async function upload(p, name) {
    await p.goto(base + "/library/upload");
    await p.locator("#file").setInputFiles(root + "/" + name);
    const response = p.waitForResponse(
      (r) =>
        r.request().method() === "POST" && r.url().endsWith("/library/upload"),
    );
    await p
      .getByRole("button", { name: "Добавить книгу", exact: true })
      .click();
    assert.equal((await response).status(), 303);
    await p.waitForURL((u) => /^\/library\/books\/\d+$/.test(u.pathname));
    return new URL(p.url()).pathname;
  }
  async function created(p = page) {
    await p.waitForURL((u) =>
      /^\/library\/books\/\d+\/notes\/\d+$/.test(u.pathname),
    );
    await p.locator("#saved-note").waitFor();
    return new URL(p.url()).pathname;
  }
  const username = "ui26_" + Date.now();
  await page.goto(base + "/registration");
  for (const [key, value] of Object.entries({
    username,
    email: username + "@example.invalid",
    password: "Synthetic-preview-password",
    confirmPassword: "Synthetic-preview-password",
  }))
    await page.locator("[name=" + key + "]").fill(value);
  await Promise.all([
    page.waitForURL((u) => u.pathname === "/login"),
    page.locator("form input[type=submit]").click(),
  ]);
  await login(page, username);
  const card = await upload(page, "valid.txt"),
    list = card + "/notes";
  await page.getByRole("link", { name: "Заметки книги", exact: true }).click();
  assert.match(await page.locator("main").innerText(), /Заметок пока нет/);
  await audit(page, "empty");
  await page.getByRole("link", { name: "Читать книгу", exact: true }).click();
  await saved();
  await page
    .locator("#book-text")
    .evaluate((el) => scrollTo(0, el.offsetTop + 1000));
  await page.waitForTimeout(200);
  await page.getByRole("link", { name: "Заметка", exact: true }).click();
  await page.waitForURL((u) => u.pathname.endsWith("/notes/new"));
  const checkpoint = await (
    await context.request.get(
      base + "/api/v1/books/" + card.split("/").pop() + "/reading-progress",
      { headers: { Accept: "application/json" } },
    )
  ).json();
  assert(checkpoint.position.offset > 0);
  assert.equal(
    Number(await page.locator("input[name=offset]").inputValue()),
    checkpoint.position.offset,
  );
  await audit(page, "new");
  await page.screenshot({ path: root + "/new-desktop.png" });
  const payload =
    "</textarea><script>window.NOTE_XSS=1</script><img src=x onerror=window.NOTE_XSS=1>🙂\nЛичная мысль";
  await page.locator("#note-text").fill(payload);
  await page
    .getByRole("button", { name: "Сохранить заметку", exact: true })
    .click();
  const note = await created();
  assert.equal(await page.locator("#saved-note").innerText(), payload);
  assert.equal(await page.evaluate(() => window.NOTE_XSS), undefined);
  assert.equal(
    await page.locator("#saved-note script,#saved-note img").count(),
    0,
  );
  await audit(page, "detail");
  await page
    .getByRole("link", { name: "Открыть место в книге", exact: true })
    .click();
  assert.equal(
    Number(await page.locator("#book-text").getAttribute("data-offset")),
    checkpoint.position.offset,
  );
  await saved();
  await page.goto(base + note + "/edit");
  await page.locator("#note-text").waitFor();
  await audit(page, "edit");
  const tab = await context.newPage();
  tab.on("dialog", (d) => d.accept());
  await tab.goto(base + note + "/edit");
  await page.locator("#note-text").fill("Первое сохранение");
  await page
    .getByRole("button", { name: "Сохранить изменения", exact: true })
    .click();
  await created();
  await tab.locator("#note-text").fill("Черновик другой вкладки");
  await tab
    .getByRole("button", { name: "Сохранить изменения", exact: true })
    .click();
  await tab.locator(".errors").waitFor();
  assert.match(await tab.locator(".errors").innerText(), /другой вкладке/);
  assert.equal(
    await tab.locator("#note-text").inputValue(),
    "Черновик другой вкладки",
  );
  assert.match(
    await tab.locator(".current-values").innerText(),
    /Первое сохранение/,
  );
  assert.equal(
    await tab.evaluate(() => document.activeElement.className),
    "errors",
  );
  await audit(tab, "conflict");
  await tab
    .getByRole("button", { name: "Сохранить изменения", exact: true })
    .click();
  await created(tab);
  assert.equal(
    await tab.locator("#saved-note").innerText(),
    "Черновик другой вкладки",
  );
  await tab.close();
  await page.goto(base + note + "/delete");
  await audit(page, "delete");
  const editor = await context.newPage();
  await editor.goto(base + note + "/edit");
  await editor.locator("#note-text").fill("Изменено перед удалением");
  await editor
    .getByRole("button", { name: "Сохранить изменения", exact: true })
    .click();
  await created(editor);
  await editor.close();
  await page.getByRole("checkbox").check();
  await page
    .getByRole("button", { name: "Удалить заметку", exact: true })
    .click();
  await page.locator(".errors").waitFor();
  assert.equal(await page.getByRole("checkbox").isChecked(), false);
  assert.match(
    await page.locator("main").innerText(),
    /Изменено перед удалением/,
  );
  await audit(page, "delete-conflict");
  await page.getByRole("checkbox").check();
  await page
    .getByRole("button", { name: "Удалить заметку", exact: true })
    .click();
  await page.waitForURL(base + list);
  assert.equal((await context.request.get(base + note)).status(), 404);
  // Native forms with JavaScript disabled must keep CRLF/code-point semantics and invalid drafts.
  const nativeContext = await browser.newContext({
      javaScriptEnabled: false,
      viewport: { width: 1280, height: 900 },
    }),
    native = await nativeContext.newPage();
  await login(native, username);
  await native.goto(base + list + "/new");
  await native.locator("#note-text").fill("x\n".repeat(1000));
  await native
    .getByRole("button", { name: "Сохранить заметку", exact: true })
    .click();
  const nativeNote = await created(native);
  assert.equal(
    await native.locator("#saved-note").innerText(),
    "x\n".repeat(1000).trim(),
  );
  await native.goto(base + nativeNote + "/edit");
  await native.locator("#note-text").fill("z".repeat(2001));
  await native
    .getByRole("button", { name: "Сохранить изменения", exact: true })
    .click();
  assert.equal(
    await native.locator("#note-text").inputValue(),
    "z".repeat(2001),
  );
  assert.match(await native.locator("main").innerText(), /2000/);
  await native.locator("#note-text").fill("🙂".repeat(2000));
  await native
    .getByRole("button", { name: "Сохранить изменения", exact: true })
    .click();
  await created(native);
  assert.equal(
    Array.from(await native.locator("#saved-note").innerText()).length,
    2000,
  );
  console.log(
    "PASS no-JS native CRUD/CRLF2000CP/emoji2000/retained invalid draft",
  );
  await page.goto(base + list + "/new");
  const fields = {};
  for (const name of [
    "_csrf",
    "chapter",
    "offset",
    "fingerprint",
    "version",
    "proof",
  ])
    fields[name] = await page
      .locator("main form input[name=" + name + "]")
      .inputValue();
  for (let i = 0; i < 21; i++) {
    const r = await context.request.post(base + list, {
      form: { ...fields, text: "Запись " + i },
      maxRedirects: 0,
    });
    assert.equal(r.status(), 303);
  }
  await page.goto(base + list);
  assert.equal(await page.locator(".book-list > li").count(), 20);
  await audit(page, "list");
  await page.getByRole("link", { name: "Следующая", exact: true }).click();
  assert.equal(await page.locator(".book-list > li").count(), 2);
  const adminContext = await browser.newContext(),
    admin = await adminContext.newPage();
  await login(admin, "preview-admin");
  assert.equal((await admin.goto(base + nativeNote)).status(), 404);
  const adminCard = await upload(admin, "valid.txt");
  assert.equal(
    (
      await admin.goto(
        base + adminCard + "/notes/" + nativeNote.split("/").pop(),
      )
    ).status(),
    404,
  );
  const epub = await upload(page, "valid.epub");
  await page.getByRole("link", { name: "Читать книгу", exact: true }).click();
  await saved();
  await page
    .getByRole("link", { name: "Следующая глава", exact: true })
    .click();
  await saved();
  await page.getByRole("link", { name: "Заметка", exact: true }).click();
  await page.waitForURL((u) => u.pathname.endsWith("/notes/new"));
  assert.equal(await page.locator("input[name=chapter]").inputValue(), "s1");
  await page.locator("#note-text").fill("Мысль о второй главе EPUB");
  await page
    .getByRole("button", { name: "Сохранить заметку", exact: true })
    .click();
  const epubNote = await created();
  await page
    .getByRole("link", { name: "Открыть место в книге", exact: true })
    .click();
  assert.equal(
    await page.locator("#book-text").getAttribute("data-chapter"),
    "s1",
  );
  await saved();
  await page.goto(base + epub + "/delete");
  await page.getByRole("checkbox").check();
  await page
    .getByRole("button", { name: "Удалить книгу", exact: true })
    .click();
  await page.waitForURL(base + "/library");
  assert.equal((await context.request.get(base + epubNote)).status(), 404);
  assert.equal(
    (
      await context.request.get(
        base + "/api/v1/books/" + epub.split("/").pop() + "/reading-progress",
      )
    ).status(),
    404,
  );
  await page.goto(base + nativeNote + "/edit");
  await page.keyboard.press("Tab");
  assert.equal(
    await page.evaluate(() => document.activeElement.className),
    "skip-link",
  );
  assert.notEqual(
    await page.evaluate(
      () => getComputedStyle(document.activeElement).outlineStyle,
    ),
    "none",
  );
  await page.keyboard.press("Enter");
  assert.equal(await page.evaluate(() => document.activeElement.id), "main");
  assert.deepEqual(external, []);
  assert.deepEqual(csp, []);
  console.log(
    "PASS TXT+EPUB cursor/flush→notes CRUD/XSS/tabs409/confirm/pagination/noJS/owner/delete; keyboard/focus,8axe states,320/200,zero external/CSP.",
  );
  await browser.close();
})().catch(async (e) => {
  console.error(e);
  if (debugPage) {
    console.error("DEBUG", debugPage.url());
    await debugPage
      .screenshot({ path: root + "/notes-failure.png" })
      .catch(() => {});
  }
  await activeBrowser?.close().catch(() => {});
  process.exitCode = 1;
});
