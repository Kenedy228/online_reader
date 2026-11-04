const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

// ReaderE2EIT creates this private descriptor and owns every resource it names.
const descriptor = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const url = new URL(descriptor.base);
assert.equal(url.hostname, "127.0.0.1");
assert.equal(url.protocol, "http:");
assert(Number(url.port) > 0);
assert(path.isAbsolute(descriptor.root));
async function launch() {
  const { chromium } = require("playwright");
  const browser = await chromium.launch({
    headless: true,
    args: ["--no-sandbox"],
  });
  const attempted = [];
  const newContext = browser.newContext.bind(browser);
  browser.newContext = async (options) => {
    const context = await newContext(options);
    // A renderer regression must never send an actual request to an external service.
    await context.route("**/*", (route) => {
      if (new URL(route.request().url()).origin === url.origin)
        return route.continue();
      attempted.push(route.request().url());
      return route.abort("blockedbyclient");
    });
    // APIRequestContext bypasses browser routing, including redirect handling.
    for (const method of ["get", "head", "post", "put", "patch", "delete"]) {
      const request = context.request[method].bind(context.request);
      context.request[method] = (target, options = {}) => {
        assert.equal(
          new URL(target, url).origin,
          url.origin,
          "external API target",
        );
        assert(
          !options.maxRedirects,
          "follow redirects explicitly after checking their origin",
        );
        return request(target, { ...options, maxRedirects: 0 });
      };
    }
    return context;
  };
  const close = browser.close.bind(browser);
  browser.close = async () => {
    await close();
    assert.deepEqual(
      attempted,
      [],
      "unexpected external browser requests (blocked)",
    );
  };
  return browser;
}
module.exports = { ...descriptor, launch };
