const assert = require("node:assert/strict");
const fs = require("node:fs");
const { performance } = require("node:perf_hooks");
const { base, root, users, launch } = require("./fixture.cjs");
const password = "Synthetic-preview-password";
const samples = Object.fromEntries(
  ["list", "card", "reader", "progress", "note"].map((name) => [name, []]),
);
const visible = [];
const raw = [];
const pacingMs = 10_000;
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  const browser = await launch();
  let failure;
  try {
    const actors = [];
    for (const user of users) {
      const context = await browser.newContext();
      const page = await context.newPage();
      await page.goto(base + "/login");
      await page.locator("[name=username]").fill(user.username);
      await page.locator("[name=password]").fill(password);
      await page.getByRole("button", { name: "Войти", exact: true }).click();
      await page.waitForURL(base + "/");
      const cards = [];
      for (const card of user.cards) {
        const response = await context.request.get(
          base + "/library/books/" + card.id + "/read",
        );
        assert.equal(response.status(), 200, "warm-up reader");
        const html = await response.text();
        const attribute = (name) => {
          const found = html.match(new RegExp("data-" + name + '="([^"]*)"'));
          assert(found, "reader attribute " + name);
          return found[1];
        };
        const csrf = html.match(/<meta name="csrf-token" content="([^"]+)"/)[1];
        const position = {
          chapter: attribute("chapter"),
          offset: 0,
          fingerprint: attribute("fingerprint"),
          version: 1,
        };
        const notePath = "/library/books/" + card.id + "/notes/" + card.note;
        const note = await context.request.get(base + notePath + "/edit");
        assert.equal(note.status(), 200);
        const noteHtml = await note.text();
        const version = noteHtml.match(/name="version"[^>]*value="(\d+)"/)[1];
        cards.push({
          ...card,
          csrf,
          position,
          proof: attribute("proof"),
          revision: 0,
          noteVersion: Number(version),
          notePath,
        });
      }
      actors.push({ context, page, cards });
    }

    // Separate first-visible-text observations; browser overhead is outside the 2CPU/4GiB server budget.
    for (const actor of actors) {
      for (const card of actor.cards) {
        const started = performance.now();
        await actor.page.goto(base + "/library/books/" + card.id + "/read");
        await actor.page.locator("#book-text").waitFor({ state: "visible" });
        const size = await actor.page
          .locator("#book-text")
          .evaluate((element) => Array.from(element.textContent).length);
        assert(
          size > 0 && size <= 16_384,
          "bounded fragment, not full near-10MiB source in DOM",
        );
        visible.push({ format: card.format, ms: performance.now() - started });
        await actor.page.waitForFunction(() =>
          /Позиция сохранена\.|Сохранённая позиция восстановлена\./.test(
            document.querySelector("#progress-message")?.textContent || "",
          ),
        );
        // Wait for the browser's initial ACK before the nominal API writer takes over.
        await actor.page.goto(base + "/library");
      }
    }
    // Read actual revisions after browser warm-up, not an assumed zero.
    for (const actor of actors)
      for (const card of actor.cards) {
        const response = await actor.context.request.get(
          base + "/api/v1/books/" + card.id + "/reading-progress",
        );
        assert.equal(response.status(), 200);
        card.revision = (await response.json()).revision;
      }

    async function timed(name, action, format) {
      const started = performance.now();
      const response = await action();
      // Consume the full response: latency isn't only time-to-headers.
      const body = await response.text();
      const elapsed = performance.now() - started;
      assert(
        response.status() >= 200 && response.status() < 300,
        name + " unexpected status " + response.status(),
      );
      samples[name].push(elapsed);
      raw.push({
        scenario: name,
        format,
        status: response.status(),
        ms: elapsed,
      });
      return body;
    }
    // Ten independent sessions are active together. Stagger/pacing avoid intentionally testing overload.
    await Promise.all(
      actors.map(async (actor, index) => {
        await delay(index * 1000);
        for (let round = 0; round < 10; round++) {
          const started = performance.now();
          const card = actor.cards[round % actor.cards.length];
          const cardPath = "/library/books/" + card.id;
          await timed("list", () =>
            actor.context.request.get(base + "/library"),
          );
          await timed("card", () => actor.context.request.get(base + cardPath));
          await timed(
            "reader",
            () => actor.context.request.get(base + cardPath + "/read"),
            card.format,
          );
          const progress = await timed("progress", () =>
            actor.context.request.put(
              base + "/api/v1/books/" + card.id + "/reading-progress",
              {
                headers: {
                  "X-CSRF-TOKEN": card.csrf,
                  Accept: "application/json",
                },
                data: {
                  revision: card.revision,
                  position: { ...card.position, offset: round + 1 },
                  proof: card.proof,
                },
              },
            ),
          );
          const acknowledged = JSON.parse(progress);
          assert.equal(acknowledged.revision, card.revision + 1);
          assert.deepEqual(acknowledged.position, {
            ...card.position,
            offset: round + 1,
          });
          card.revision = acknowledged.revision;
          const persisted = await actor.context.request.get(
            base + "/api/v1/books/" + card.id + "/reading-progress",
          );
          assert.equal(persisted.status(), 200);
          assert.deepEqual(await persisted.json(), acknowledged);
          // Native note PRG: assert 303 and time through the final expected 200 response.
          const noteBody = await timed("note", async () => {
            const response = await actor.context.request.post(
              base + card.notePath + "/edit",
              {
                form: {
                  _csrf: card.csrf,
                  text: "Измеряемая мысль " + round,
                  version: String(card.noteVersion),
                },
                maxRedirects: 0,
              },
            );
            assert.equal(
              response.status(),
              303,
              "native note mutation must commit via PRG",
            );
            card.noteVersion++;
            const location = new URL(response.headers().location, base);
            assert.equal(location.origin, new URL(base).origin);
            assert.equal(location.pathname, card.notePath);
            return actor.context.request.get(location.href);
          });
          assert.match(
            noteBody,
            new RegExp('id="saved-note"[^>]*>Измеряемая мысль ' + round + "<"),
          );
          await delay(Math.max(0, pacingMs - (performance.now() - started)));
        }
      }),
    );
    const metrics = {};
    for (const [name, values] of Object.entries(samples)) {
      assert.equal(values.length, 100, name + " sample count");
      const ordered = [...values].sort((a, b) => a - b);
      const p95 = ordered[Math.ceil(ordered.length * 0.95) - 1];
      metrics[name] = { count: values.length, p95, max: ordered.at(-1) };
    }
    fs.writeFileSync(
      root + "/measurements.json",
      JSON.stringify(
        {
          browser: browser.version(),
          node: process.version,
          users: 10,
          books: 1000,
          notes: 10000,
          pacingMs,
          staggerMs: 1000,
          warmup: "both near-10MiB formats per user, then first-visible checks",
          metrics,
          visible,
          raw,
        },
        null,
        2,
      ),
    );
    console.log("METRICS " + JSON.stringify(metrics));
    for (const [name, metric] of Object.entries(metrics))
      assert(
        metric.p95 <= (name === "reader" ? 1000 : 500),
        name + " p95 threshold exceeded: " + metric.p95,
      );
    assert(
      visible.every((sample) => sample.ms <= 2000),
      "first visible text exceeds 2000ms",
    );
  } catch (error) {
    failure = error;
    fs.writeFileSync(
      root + "/failed-measurements.json",
      JSON.stringify({ error: String(error), raw, visible }, null, 2),
    );
  } finally {
    await browser.close();
  }
  if (failure) throw failure;
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
