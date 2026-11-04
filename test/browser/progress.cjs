const assert = require("node:assert/strict"),
  fs = require("node:fs");
const AxeBuilder = require("@axe-core/playwright").default;
let debugPage, activeBrowser;
const { base, root, launch } = require("./fixture.cjs");
(async () => {
  const browser = await launch();
  activeBrowser = browser;
  const context = await browser.newContext({
      viewport: { width: 1280, height: 900 },
    }),
    page = await context.newPage();
  const external = [],
    csp = [];
  debugPage = page;
  page.on("response", async (r) => {
    if (
      r.request().method() === "PUT" &&
      r.url().includes("/reading-progress")
    ) {
      const v = await r.json().catch(() => ({}));
      console.log("PUT", r.status(), v.revision ?? v.code ?? "unknown");
    }
  });
  context.on("page", (p) => p.on("dialog", (d) => d.accept()));
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
  async function saved(p = page) {
    await p.waitForFunction(() =>
      /Позиция сохранена\.|Сохранённая позиция восстановлена\./.test(
        document.querySelector("#progress-message")?.textContent || "",
      ),
    );
  }
  async function error(p = page) {
    await p.waitForFunction(() =>
      document
        .querySelector("#progress-message")
        ?.textContent?.startsWith("Не сохранено:"),
    );
  }
  async function state(card) {
    const r = await context.request.get(
      base + "/api/v1/books/" + card.split("/").pop() + "/reading-progress",
      { headers: { Accept: "application/json" } },
    );
    assert.equal(r.status(), 200);
    return r.json();
  }
  async function scroll(p, delta) {
    await p.bringToFront();
    await p
      .locator("#book-text")
      .evaluate((el, dy) => window.scrollTo(0, el.offsetTop + dy), delta);
    await p.waitForTimeout(200);
  }
  async function audit(name) {
    const viewport = page.viewportSize();
    await page.setViewportSize({ width: 320, height: 900 });
    await page.evaluate(
      () => (document.documentElement.style.fontSize = "200%"),
    );
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      name + " overflow320/200",
    );
    const sticky = await page.locator("#progress-status").boundingBox();
    assert(
      sticky.height < 700,
      name + " sticky status must leave reading space",
    );
    await page.screenshot({
      path: root + "/progress-" + name + "-mobile200.png",
    });
    await page.evaluate(() => (document.documentElement.style.fontSize = ""));
    await page.setViewportSize(viewport);
    const r = await new AxeBuilder({ page })
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
  async function upload(name) {
    await page.goto(base + "/library/upload");
    await page.locator("#file").setInputFiles(root + "/" + name);
    const response = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" && r.url().endsWith("/library/upload"),
    );
    await page
      .getByRole("button", { name: "Добавить книгу", exact: true })
      .click();
    assert.equal((await response).status(), 303);
    await page.waitForURL((u) => /^\/library\/books\/\d+$/.test(u.pathname));
    const card = new URL(page.url()).pathname;
    await page.getByRole("link", { name: "Читать книгу", exact: true }).click();
    await page.locator("#progress-actions").waitFor({ state: "visible" });
    await saved();
    return card;
  }
  const username = "ui25_" + Date.now();
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
  const txt = await upload("valid.txt"),
    api = "/api/v1/books/" + txt.split("/").pop() + "/reading-progress";
  await audit("saved");
  await saved();
  await scroll(page, 1000);
  await saved();
  const fontCheckpoint = (await state(txt)).position.offset;
  await page
    .getByRole("link", { name: "Настроить шрифт", exact: true })
    .click();
  await page.waitForURL((u) => u.hash === "#reader-settings");
  await page.locator("#reader-font").selectOption("large");
  await page.getByRole("button", { name: "Применить", exact: true }).click();
  await page.waitForURL((u) => u.searchParams.get("font") === "large");
  await saved();
  assert.equal(
    Number(await page.locator("#book-text").getAttribute("data-offset")),
    fontCheckpoint,
  );
  console.log("PASS sticky font checkpoint and native submit with clean flush");
  let puts = 0;
  const count = (r) => {
    if (r.request().method() === "PUT" && r.url() === base + api) puts++;
  };
  page.on("response", count);
  const before = puts;
  for (const delta of [200, 500, 800]) {
    await scroll(page, delta);
    await page.waitForTimeout(200);
  }
  assert.equal(puts, before, "debounce must coalesce scrolling");
  await saved();
  assert.equal(puts, before + 1);
  let checkpoint = await state(txt);
  assert(checkpoint.position.offset > 0);
  console.log("PASS Unicode scroll/debounce/ACK", checkpoint.position.offset);
  await scroll(page, 1300);
  const leaving = await state(txt);
  await page.getByRole("link", { name: "Библиотека", exact: true }).click();
  await page.waitForURL(base + "/library");
  checkpoint = await state(txt);
  assert(checkpoint.revision > leaving.revision, "link waits for flush");
  await page.goto(base + txt + "/read");
  assert.equal(
    Number(await page.locator("#book-text").getAttribute("data-offset")),
    checkpoint.position.offset,
  );
  await saved();
  const tab = await context.newPage();
  await tab.goto(base + txt + "/read");
  await saved(tab);
  await scroll(page, 500);
  await saved();
  await scroll(tab, 1100);
  await error(tab);
  assert.match(
    await tab.locator("#progress-message").innerText(),
    /другой вкладке/,
  );
  await tab
    .getByRole("button", {
      name: "Сохранить эту позицию вместо другой",
      exact: true,
    })
    .click();
  await saved(tab);
  const chosen = await state(txt);
  await page
    .getByRole("button", { name: "Сохранить позицию", exact: true })
    .click();
  await error();
  await audit("conflict");
  await page
    .getByRole("link", { name: "Загрузить сохранённую позицию", exact: true })
    .click();
  await page.waitForFunction(
    (expected) =>
      Number(
        document.querySelector("#book-text")?.getAttribute("data-offset"),
      ) === expected,
    chosen.position.offset,
  );
  await saved();
  await tab.close();
  console.log("PASS two tabs409/explicit replace/load");
  // A queued scroll debounce must be cancelled after an earlier in-flight request fails.
  let release;
  const gate = new Promise((r) => (release = r));
  let started;
  const began = new Promise((r) => (started = r));
  let requests = 0;
  await page.route(base + api, async (route) => {
    if (route.request().method() === "PUT") {
      requests++;
      if (requests === 1) {
        started();
        await gate;
        await route.abort("failed");
        return;
      }
    }
    await route.continue();
  });
  await scroll(page, 400);
  await began;
  await scroll(page, 900);
  release();
  await error();
  await page.waitForTimeout(2500);
  assert.equal(requests, 1, "error must cancel queued autosave");
  await audit("network-error");
  await page.getByRole("link", { name: "Библиотека", exact: true }).click();
  assert.match(page.url(), /\/read/);
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "progress-actions",
  );
  assert(
    await page
      .getByRole("button", { name: "Уйти без сохранения", exact: true })
      .isVisible(),
  );
  await page
    .getByRole("button", { name: "Повторить сохранение", exact: true })
    .click();
  await saved();
  await page.unroute(base + api);
  console.log(
    "PASS network failure pauses pending debounce/navigation; explicit retry",
  );
  // Commit at the server, then lose only its response. Returning to an old ACK cannot skip retry.
  await page.goto(base + txt + "/read");
  await saved();
  let lost = true;
  let retries = 0;
  await page.route(base + api, async (route) => {
    if (route.request().method() === "PUT") {
      retries++;
      if (lost) {
        lost = false;
        await route.fetch({ maxRedirects: 0 });
        await route.abort("failed");
        return;
      }
    }
    await route.continue();
  });
  await scroll(page, 800);
  await error();
  await page.evaluate(() => scrollTo(0, 0));
  await page.waitForTimeout(200);
  await page
    .getByRole("button", { name: "Повторить сохранение", exact: true })
    .click();
  await error();
  assert.equal(retries, 2);
  assert.match(
    await page.locator("#progress-message").innerText(),
    /другой вкладке/,
  );
  await page
    .getByRole("link", { name: "Загрузить сохранённую позицию", exact: true })
    .click();
  await saved();
  await page.unroute(base + api);
  console.log("PASS ambiguous ACK loss cannot become a false no-op success");
  // Font submit must use the final flushed position, even if the user scrolls during the request.
  let unblock;
  const fontGate = new Promise((r) => (unblock = r));
  let fontStarted;
  const fontBegan = new Promise((r) => (fontStarted = r));
  let first = true,
    lastOffset = -1;
  await page.route(base + api, async (route) => {
    if (route.request().method() === "PUT") {
      lastOffset = route.request().postDataJSON().position.offset;
      if (first) {
        first = false;
        fontStarted();
        await fontGate;
      }
    }
    await route.continue();
  });
  await scroll(page, 400);
  await fontBegan;
  await page.locator("#reader-font").selectOption("largest");
  await page.getByRole("button", { name: "Применить", exact: true }).click();
  await scroll(page, 1200);
  unblock();
  await page.waitForURL((u) => u.searchParams.get("font") === "largest");
  await page.locator("#book-text.font-largest").waitFor();
  assert.equal(
    Number(await page.locator("#book-text").getAttribute("data-offset")),
    lastOffset,
  );
  await saved();
  await page.unroute(base + api);
  await audit("font-largest");
  console.log(
    "PASS font change during in-flight save keeps final Unicode offset",
  );
  checkpoint = await state(txt);
  await page.getByRole("button", { name: "Выйти", exact: true }).click();
  await page.waitForURL((u) => u.pathname === "/login");
  await login(page, username);
  await page.goto(base + txt + "/read");
  await saved();
  assert.equal(
    Number(await page.locator("#book-text").getAttribute("data-offset")),
    (await state(txt)).position.offset,
  );
  await page.goto(base + txt + "/read");
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
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "book-text",
  );
  const epub = await upload("valid.epub");
  await page.getByText("Оглавление", { exact: true }).click();
  await page
    .getByRole("link", { name: "Раздел с плюсом", exact: true })
    .click();
  await saved();
  let epubState = await state(epub);
  assert.equal(epubState.position.chapter, "s0");
  assert(epubState.position.offset > 0);
  await page
    .getByRole("link", { name: "Следующая глава", exact: true })
    .click();
  await saved();
  assert.equal((await state(epub)).position.chapter, "s1");
  await audit("epub-restored");
  await page.goto(base + epub + "/read");
  assert.equal(
    await page.locator("#book-text").getAttribute("data-chapter"),
    "s1",
  );
  await saved();
  const adminContext = await browser.newContext(),
    admin = await adminContext.newPage();
  await login(admin, "preview-admin");
  assert.equal(
    (
      await adminContext.request.get(base + api, {
        headers: { Accept: "application/json" },
      })
    ).status(),
    404,
  );
  await page.goto(base + epub + "/delete");
  await page.getByRole("checkbox").check();
  await page
    .getByRole("button", { name: "Удалить книгу", exact: true })
    .click();
  await page.waitForURL(base + "/library");
  assert.equal(
    (
      await context.request.get(
        base + "/api/v1/books/" + epub.split("/").pop() + "/reading-progress",
      )
    ).status(),
    404,
  );
  assert.deepEqual(external, []);
  assert.deepEqual(csp, []);
  console.log(
    "PASS TXT/EPUB progress persist/restore/login/debounce/flush/tabs409/errors/font/delete/owner; keyboard/focus,5axe states,320px/200%,zero external/CSP.",
  );
  await browser.close();
})().catch(async (e) => {
  console.error(e);
  if (debugPage) {
    console.error(
      "DEBUG",
      debugPage.url(),
      await debugPage
        .locator("#progress-message")
        .textContent()
        .catch(() => ""),
    );
    await debugPage
      .screenshot({ path: root + "/progress-failure.png" })
      .catch(() => {});
  }
  await activeBrowser?.close().catch(() => {});
  process.exitCode = 1;
});
