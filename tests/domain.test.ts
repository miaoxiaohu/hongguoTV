import { test } from "node:test";
import assert from "node:assert/strict";
import {
  Detail,
  Progress,
  Series,
  episodeGroup,
  hasMorePages,
  mergePage,
  normalizeBaseUrl,
  resumeTarget,
} from "../src/domain/model";
import { BridgeProvider } from "../src/data/provider";
const detail: Detail = {
  id: "1",
  title: "剧",
  cover: "",
  description: "",
  badge: "",
  tags: "",
  episodes: [
    { id: "b", number: 1, title: "一" },
    { id: "a", number: 2, title: "二" },
  ],
};
const progress: Progress = {
  series: detail,
  episodeId: "a",
  episodeNumber: 1,
  position: 32,
  duration: 80,
  completed: false,
  updatedAt: 1,
};
test("resume follows stable episode ID after order changes", () =>
  assert.deepEqual(resumeTarget(detail, progress), { index: 1, position: 32 }));
test("completed episode advances, last episode restarts, absent episode resets", () => {
  assert.deepEqual(
    resumeTarget(detail, { ...progress, episodeId: "b", completed: true }),
    { index: 1, position: 0 }
  );
  assert.deepEqual(resumeTarget(detail, { ...progress, completed: true }), {
    index: 1,
    position: 0,
  });
  assert.deepEqual(
    resumeTarget(detail, { ...progress, episodeId: "missing" }),
    { index: 0, position: 0 }
  );
});
test("overlapping pagination replaces metadata without duplicating cards", () => {
  const rows = mergePage(
    [detail],
    [
      { ...detail, title: "新标题" },
      { ...detail, id: "2" },
    ]
  );
  assert.equal(rows.length, 2);
  assert.equal(rows[0].title, "新标题");
});
test("server config accepts host origins and rejects credentials, paths and other protocols", () => {
  assert.equal(
    normalizeBaseUrl(" http://192.168.1.10:8787/ "),
    "http://192.168.1.10:8787"
  );
  for (const url of [
    "file:///tmp/file",
    "http://user:secret@host",
    "http://host/path",
    "http://host?q=a",
    "",
  ])
    assert.throws(() => normalizeBaseUrl(url));
});
test("a malformed port is refused before it can reach fetch as a broken URL", () => {
  for (const url of [
    "http://host:",
    "http://host:8787:9",
    "http://host:99999",
    "http://host:0",
    "http://host:65536",
  ])
    assert.throws(() => normalizeBaseUrl(url), /端口|地址/);
  assert.equal(normalizeBaseUrl("http://host:1"), "http://host:1");
  assert.equal(normalizeBaseUrl("http://host:65535"), "http://host:65535");
  assert.equal(normalizeBaseUrl("http://[::1]:8787"), "http://[::1]:8787");
});
test("a page that repeats the rows the list already has means the end, not more", () => {
  const rows: Series[] = [
    { id: "1", title: "一", cover: "", description: "", badge: "", tags: "" },
    { id: "2", title: "二", cover: "", description: "", badge: "", tags: "" },
  ];
  // The site answers every page with the same rows, so a second page that
  // brings nothing new must end the list instead of inviting another click.
  assert.equal(hasMorePages([], rows), true);
  assert.equal(hasMorePages(rows, rows), false);
  assert.equal(hasMorePages(rows, []), false);
  assert.equal(hasMorePages(rows, [...rows, { ...rows[0], id: "3" }]), true);
  // A replaced list starts over at page one, where the answer is still unknown.
  assert.equal(hasMorePages(rows, rows, true), true);
  assert.equal(hasMorePages(rows, [], true), false);
});
test("the episode group follows the episode the page is on", () => {
  assert.equal(episodeGroup(0, 20), 0);
  assert.equal(episodeGroup(19, 20), 0);
  assert.equal(episodeGroup(20, 20), 1);
  assert.equal(episodeGroup(24, 20), 1);
  assert.equal(episodeGroup(-1, 20), 0);
});
function withResponse(
  response: { ok: boolean; status: number; body: string },
  run: () => Promise<void>
) {
  const original = globalThis.fetch;
  globalThis.fetch = (async () => ({
    ok: response.ok,
    status: response.status,
    async text() {
      return response.body;
    },
  })) as unknown as typeof globalThis.fetch;
  return run().finally(() => {
    globalThis.fetch = original;
  });
}
const provider = new BridgeProvider({
  baseUrl: "http://192.168.1.10:8787",
  token: "",
});
test("an HTML error page becomes a readable message, not a raw JS error", async () => {
  await withResponse(
    {
      ok: false,
      status: 502,
      body: "<html><body>502 Bad Gateway</body></html>",
    },
    async () => {
      await assert.rejects(
        () => provider.home(1),
        (error: unknown) => {
          const message = String((error as Error).message);
          assert.match(message, /服务返回 502/);
          assert.doesNotMatch(message, /Unexpected token|Unexpected end/);
          return true;
        }
      );
    }
  );
});
test("a page that is not the service's own data says so, and JSON errors are kept", async () => {
  await withResponse(
    { ok: true, status: 200, body: "<html><body>router</body></html>" },
    async () => {
      await assert.rejects(() => provider.home(1), /无法解析/);
    }
  );
  await withResponse(
    { ok: false, status: 400, body: JSON.stringify({ error: "分页参数无效" }) },
    async () => {
      await assert.rejects(() => provider.home(1), /分页参数无效/);
    }
  );
  await withResponse(
    {
      ok: true,
      status: 200,
      body: JSON.stringify({
        items: [
          { id: "1", title: "一", cover: "", description: "", badge: "", tags: "" },
        ],
      }),
    },
    async () => {
      assert.equal((await provider.home(1)).length, 1);
    }
  );
});
