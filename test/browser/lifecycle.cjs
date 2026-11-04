const assert = require("node:assert/strict");
const readline = require("node:readline");
const { base, root, launch } = require("./fixture.cjs");
const password = "Synthetic-preview-password";
const responses = readline.createInterface({ input: process.stdin });
async function control(op, extra = {}) {
  const reply = new Promise((resolve) => responses.once("line", resolve));
  process.stdout.write(
    "E2E_CONTROL " + JSON.stringify({ op, ...extra }) + "\n",
  );
  assert.equal(await reply, "OK");
}

async function main() {
  const browser = await launch();
  try {
    const owner = await browser.newContext();
    let page = await owner.newPage();
    const external = [];
    owner.on("request", (request) => {
      if (
        !request.url().startsWith(base + "/") &&
        !request.url().startsWith("data:")
      )
        external.push(request.url());
    });
    async function login(p, username) {
      await p.goto(base + "/login");
      await p.locator("[name=username]").fill(username);
      await p.locator("[name=password]").fill(password);
      await p.getByRole("button", { name: "Войти", exact: true }).click();
      await p.waitForURL(base + "/");
    }
    async function register(p, username) {
      await p.goto(base + "/registration");
      for (const [key, value] of Object.entries({
        username,
        email: username + "@example.invalid",
        password,
        confirmPassword: password,
      })) {
        await p.locator("[name=" + key + "]").fill(value);
      }
      await p.locator("form input[type=submit]").click();
      await p.waitForURL((url) => url.pathname === "/login");
      await login(p, username);
    }
    async function saved() {
      await page.waitForFunction(() =>
        /Позиция сохранена\.|Сохранённая позиция восстановлена\./.test(
          document.querySelector("#progress-message")?.textContent || "",
        ),
      );
    }
    async function state(card) {
      const response = await owner.request.get(base + api(card));
      assert.equal(response.status(), 200);
      return response.json();
    }
    function api(card) {
      return "/api/v1/books/" + card.split("/").pop() + "/reading-progress";
    }
    async function scroll(dy) {
      await page.bringToFront();
      await page
        .locator("#book-text")
        .evaluate(
          (element, delta) => scrollTo(0, element.offsetTop + delta),
          dy,
        );
      await page.waitForTimeout(200);
    }
    async function upload(name) {
      await page.goto(base + "/library/upload");
      await page.locator("#file").setInputFiles(root + "/" + name);
      const submitted = page.waitForResponse(
        (r) =>
          r.request().method() === "POST" &&
          r.url().endsWith("/library/upload"),
      );
      await page
        .getByRole("button", { name: "Добавить книгу", exact: true })
        .click();
      assert.equal((await submitted).status(), 303);
      await page.waitForURL((url) =>
        /^\/library\/books\/\d+$/.test(url.pathname),
      );
      return new URL(page.url()).pathname;
    }
    await register(page, "lifecycle-owner");
    const txt = await upload("valid.txt");
    const epub = await upload("valid.epub");
    const retained = [];
    for (const card of [txt, epub]) {
      await page.goto(base + card + "/read");
      await saved();
      assert.equal(await page.evaluate(() => window.UNSAFE), undefined);
      await scroll(1100);
      await page.getByRole("link", { name: "Заметка", exact: true }).click();
      await page.waitForURL((url) => url.pathname.endsWith("/notes/new"));
      const checkpoint = await state(card);
      assert(checkpoint.position.offset > 0);
      await page.locator("#note-text").fill("Сохраняемая мысль 🙂 " + card);
      await page
        .getByRole("button", { name: "Сохранить заметку", exact: true })
        .click();
      await page.waitForURL((url) => /\/notes\/\d+$/.test(url.pathname));
      const note = new URL(page.url()).pathname;
      retained.push({
        card,
        checkpoint,
        note,
        text: await page.locator("#saved-note").innerText(),
      });
    }
    const oldCookies = await owner.cookies();
    // Native logout invalidates the session; subsequent UI access cannot use its old cookies.
    await page.getByRole("button", { name: "Выйти", exact: true }).click();
    await page.waitForURL((url) => url.pathname === "/login");
    assert.equal((await owner.request.get(base + api(txt))).status(), 401);
    const expired = await browser.newContext({
      storageState: { cookies: oldCookies, origins: [] },
    });
    assert.equal(
      (await expired.request.get(base + api(txt))).status(),
      401,
      "logged-out session ID cannot be replayed",
    );
    await expired.close();
    await login(page, "lifecycle-owner");
    async function verifyRetained() {
      for (const { card, checkpoint, note, text } of retained) {
        await page.goto(base + card + "/read");
        await saved();
        assert.equal(
          Number(await page.locator("#book-text").getAttribute("data-offset")),
          checkpoint.position.offset,
        );
        assert.equal(
          await page.locator("#book-text").getAttribute("data-chapter"),
          checkpoint.position.chapter,
        );
        await page.goto(base + note);
        assert.equal(await page.locator("#saved-note").innerText(), text);
      }
    }
    await verifyRetained();
    await control("restart");
    await login(page, "lifecycle-owner");
    await verifyRetained();
    console.log(
      "PASS TXT+EPUB register/login/upload/read/progress/note/logout/login/restart/continue",
    );

    const imageContext = await browser.newContext({
      storageState: await owner.storageState(),
      javaScriptEnabled: false,
    });
    const resourcePage = await imageContext.newPage();
    await resourcePage.goto(base + epub + "/read?chapter=s0&offset=0");
    const image = new URL(
      await resourcePage.locator("#book-text img").first().getAttribute("src"),
      resourcePage.url(),
    ).href;
    assert.equal(new URL(image).origin, new URL(base).origin);
    assert.equal((await owner.request.get(image)).status(), 200);
    await imageContext.close();
    // An ordinary second user AND an operator-appointed Admin must hit the same private boundary.
    for (const username of ["lifecycle-other", "preview-admin"]) {
      const stranger = await browser.newContext();
      const foreign = await stranger.newPage();
      if (username === "preview-admin") await login(foreign, username);
      else await register(foreign, username);
      await foreign.goto(base + "/library");
      const csrf = await foreign
        .locator("input[name=_csrf]")
        .first()
        .inputValue();
      for (const { card, note } of retained) {
        for (const path of [
          card,
          card + "/read",
          card + "/download",
          card + "/notes",
          note,
          note + "/edit",
          note + "/delete",
          api(card),
        ]) {
          assert.equal(
            (await stranger.request.get(base + path)).status(),
            404,
            username + " foreign " + path,
          );
        }
        assert.equal(
          (
            await stranger.request.put(base + api(card), {
              headers: { "X-CSRF-TOKEN": csrf, Accept: "application/json" },
              data: {
                revision: 0,
                position: {
                  chapter: "text",
                  offset: 0,
                  fingerprint: "0".repeat(64),
                  version: 1,
                },
                proof: "invalid",
              },
            })
          ).status(),
          404,
        );
        assert.equal(
          (
            await stranger.request.post(base + note + "/edit", {
              form: { _csrf: csrf, text: "Чужое изменение", version: "0" },
              maxRedirects: 0,
            })
          ).status(),
          404,
        );
        assert.equal(
          (
            await stranger.request.post(base + note + "/delete", {
              form: { _csrf: csrf, confirmed: "true", version: "0" },
              maxRedirects: 0,
            })
          ).status(),
          404,
        );
      }
      assert.equal((await stranger.request.get(image)).status(), 404);
      // An existing foreign note nested under a different book cannot escape ownership either.
      assert.equal(
        (
          await stranger.request.get(
            base + txt + "/notes/" + retained[1].note.split("/").pop(),
          )
        ).status(),
        404,
      );
      await stranger.close();
    }
    await page.goto(base + txt + "/read");
    await saved();
    assert.equal(
      (
        await owner.request.get(
          base + txt + "/notes/" + retained[1].note.split("/").pop(),
        )
      ).status(),
      404,
      "own books cannot exchange nested note IDs",
    );
    const beforeCsrf = await state(txt);
    assert.equal(
      (
        await owner.request.put(base + api(txt), { data: { revision: 0 } })
      ).status(),
      403,
    );
    assert.deepEqual(await state(txt), beforeCsrf);
    await control("corrupt", { book: Number(txt.split("/").pop()) });
    assert.equal((await page.goto(base + txt + "/read")).status(), 422);
    assert.doesNotMatch(
      await page.locator("main").innerText(),
      /Exception|storage\/sources|select .*from/i,
    );
    await control("restore");
    console.log(
      "PASS owner boundaries: two Users/Admin, source/image/progress/note/nested IDs, CSRF, corrupt source",
    );

    await page.goto(base + txt + "/read");
    await saved();
    const acknowledged = await state(txt);
    await control("database-stop");
    await scroll(2000);
    await page.waitForFunction(() =>
      document
        .querySelector("#progress-message")
        ?.textContent?.startsWith("Не сохранено:"),
    );
    assert.doesNotMatch(
      await page.locator("#progress-message").innerText(),
      /Позиция сохранена\./,
    );
    await control("database-recover");
    // New login: memory sessions do not survive a real restart. Last ACK is the persisted truth.
    await login(page, "lifecycle-owner");
    assert.deepEqual(await state(txt), acknowledged);
    await verifyRetained();
    assert.deepEqual(external, []);
    console.log(
      "PASS actual PostgreSQL outage has no false success; last ACK and notes survive recovery",
    );
  } finally {
    await browser.close();
    responses.close();
  }
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
  responses.close();
});
