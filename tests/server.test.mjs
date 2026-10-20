import { test } from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import {
  createServer,
  byteRange,
  bridgeExposureError,
} from "../server/http.mjs";
import {
  createProvider,
  parseContentTotal,
  restoreSampleEntry,
  sweepCaches,
} from "../server/provider.mjs";
async function fixture(t, provider, token = "", options = {}) {
  const server = createServer(provider, token, options);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => {
    server.closeAllConnections();
    server.close();
  });
  return `http://127.0.0.1:${server.address().port}`;
}
// Mirrors the pinned upstream: the app-search cursor chain is walked from page 1 on
// every call, and each uncached page costs one signed upstream request.
function fakeApi() {
  const calls = { signed: 0, website: 0 };
  const appSearchCache = new Map();
  const api = {
    appSearchCache,
    pageCache: new Map(),
    streamCache: new Map(),
    videoInfoCache: new Map(),
    async appSearchPage(keyword, tabType, page, limit) {
      const key = `${tabType}|${keyword}|${page}`;
      const cached = appSearchCache.get(key);
      if (cached && Date.now() - cached.time < 300000) return cached.value;
      let cursor = {};
      if (page > 1) {
        const previous = await api.appSearchPage(keyword, tabType, page - 1, limit);
        cursor = previous.cursor || {};
      }
      calls.signed += 1;
      const value = {
        list: [
          {
            url: String(7000 + page),
            title: `上游第 ${page} 页`,
            img: "",
            desc: "",
            tname: "",
            content: "",
          },
        ],
        hasMore: true,
        cursor: { search_id: `s${page}`, passback: `p${page}` },
      };
      appSearchCache.set(key, { time: Date.now(), value });
      return value;
    },
    async routerData(url) {
      calls.website += 1;
      return {
        loaderData: {
          "search_(keyword)/page": {
            searchList: [
              {
                video_data: {
                  series_id: "999",
                  series_name: "官网结果",
                  series_cover: "",
                },
              },
            ],
          },
        },
      };
    },
    itemToCard(item) {
      const source =
        item?.video_data && typeof item.video_data === "object"
          ? item.video_data
          : item;
      return {
        url: String(source?.series_id ?? source?.url ?? ""),
        title: String(source?.series_name ?? source?.title ?? ""),
        img: source?.series_cover ?? source?.img ?? "",
        desc: "",
        tname: "",
        content: "",
      };
    },
  };
  return { api, calls };
}
test("range handling supports seeking and suffixes; rejects malformed and multipart ranges", () => {
  assert.deepEqual(byteRange("bytes=50-", 100), [50, 99]);
  assert.deepEqual(byteRange("bytes=-10", 100), [90, 99]);
  for (const range of [
    "bytes=100-",
    "bytes=50-40",
    "bytes=-0",
    "bytes=0-1,5-6",
    "garbage",
    "bytes=-",
  ])
    assert.equal(byteRange(range, 100), null);
});
test("media stream delivers entire open range across 1 MB chunks; HEAD and suffix are correct", async (t) => {
  const data = Buffer.alloc(2 * 1024 * 1024 + 123, 7);
  let calls = 0;
  const url = await fixture(t, {
    stream: async () => ({
      total: data.length,
      chunk: async (a, b) => {
        calls++;
        return data.subarray(a, b + 1);
      },
    }),
  });
  const head = await fetch(url + "/api/episodes/123/stream", {
    method: "HEAD",
  });
  assert.equal(head.status, 200);
  assert.equal(calls, 0);
  const res = await fetch(url + "/api/episodes/123/stream", {
    headers: { Range: "bytes=17-" },
  });
  assert.equal(res.status, 206);
  assert.equal(
    res.headers.get("content-range"),
    `bytes 17-${data.length - 1}/${data.length}`
  );
  assert.deepEqual(Buffer.from(await res.arrayBuffer()), data.subarray(17));
  assert.equal(calls, 3);
  const tail = await fetch(url + "/api/episodes/123/stream", {
    headers: { Range: "bytes=-20" },
  });
  assert.equal((await tail.arrayBuffer()).byteLength, 20);
  const bad = await fetch(url + "/api/episodes/123/stream", {
    headers: { Range: "bytes=999999999-" },
  });
  assert.equal(bad.status, 416);
});
test("authentication protects metadata and media; health does not claim upstream availability", async (t) => {
  const url = await fixture(t, {}, "secret");
  assert.equal((await fetch(url + "/health")).status, 401);
  const res = await fetch(url + "/health", {
    headers: { Authorization: "Bearer secret" },
  });
  assert.equal((await res.json()).upstream, "unverified");
  assert.equal((await fetch(url + "/api/episodes/1/stream")).status, 401);
});
test("upstream failures stay failures, do not expose signed URLs, and release stream slots", async (t) => {
  const fail = async () => {
    throw new Error("secret-signed-url");
  };
  const url = await fixture(t, { home: fail, stream: fail });
  for (let i = 0; i < 6; i++) {
    const res = await fetch(url + "/api/episodes/1/stream");
    assert.equal(res.status, 502);
    assert.ok(!(await res.text()).includes("secret"));
  }
  assert.equal((await fetch(url + "/api/home")).status, 502);
});
test("bad identifiers and invalid queries never reach provider", async (t) => {
  const url = await fixture(t, {});
  assert.equal((await fetch(url + "/api/home?page=NaN")).status, 400);
  assert.equal((await fetch(url + "/api/search?q=")).status, 400);
  assert.equal(
    (await fetch(url + "/api/episodes/https:example/stream")).status,
    404
  );
});
test("search pagination does not scale upstream calls with the page number", async () => {
  const { api, calls } = fakeApi();
  const provider = createProvider(api);
  const result = await provider.search("短剧", 100);
  assert.ok(
    calls.signed <= 3,
    `page=100 must not fan out upstream (got ${calls.signed} signed requests)`
  );
  assert.equal(result.items.length, 1);
  const before = calls.signed;
  const shallow = await provider.search("短剧", 2);
  assert.equal(calls.signed, before);
  assert.equal(shallow.items[0].id, "7002");
});
test("search judges the filtered items, not the raw upstream list", async () => {
  const { api } = fakeApi();
  api.appSearchPage = async () => ({
    list: [{ url: "not-a-number", title: "" }],
    hasMore: false,
    cursor: {},
  });
  const provider = createProvider(api);
  const result = await provider.search("短剧", 1);
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, "999");
});
test("a client that stops reading cannot hold a stream slot forever", async (t) => {
  const payload = Buffer.alloc(1024 * 1024, 7);
  const url = await fixture(
    t,
    {
      stream: async () => ({
        total: 32 * 1024 * 1024,
        chunk: async (a, b) => {
          const out = Buffer.alloc(b - a + 1, 7);
          payload.copy(out, 0, a % payload.length);
          return out;
        },
      }),
    },
    "",
    { backpressureTimeout: 200 }
  );
  const port = Number(new URL(url).port);
  const failures = [];
  const originalError = console.error;
  console.error = (...args) => failures.push(args.join(" "));
  const sockets = [];
  t.after(() => {
    console.error = originalError;
    for (const socket of sockets) socket.destroy();
  });
  for (let i = 0; i < 4; i++) {
    const socket = net.connect(port, "127.0.0.1");
    socket.on("error", () => {});
    sockets.push(socket);
    socket.write(
      "GET /api/episodes/1/stream HTTP/1.1\r\nHost: localhost\r\n\r\n"
    );
  }
  await new Promise((resolve) => setTimeout(resolve, 1500));
  assert.ok(
    failures.some((line) => line.includes("CLIENT_BACKPRESSURE_TIMEOUT")),
    "a stalled reader must be logged and its slot released"
  );
  const res = await fetch(url + "/api/episodes/1/stream", {
    headers: { Range: "bytes=0-9" },
  });
  assert.equal(res.status, 206);
});
test("the stream route honours the fresh parameter", async (t) => {
  const seen = [];
  const url = await fixture(t, {
    stream: async (id, fresh) => {
      seen.push(fresh);
      return { total: 4, chunk: async () => Buffer.from("abcd") };
    },
  });
  await fetch(url + "/api/episodes/7/stream?fresh=1");
  await fetch(url + "/api/episodes/7/stream");
  assert.deepEqual(seen, [true, false]);
});
test("405 responses carry the required Allow header", async (t) => {
  const url = await fixture(t, {});
  const res = await fetch(url + "/api/home", { method: "POST" });
  assert.equal(res.status, 405);
  assert.equal(res.headers.get("allow"), "GET, HEAD");
});
test("the play route shares the stream quota", async (t) => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const url = await fixture(t, {
    stream: async () => {
      await gate;
      return { total: 1, chunk: async () => Buffer.alloc(1) };
    },
  });
  const pending = [];
  for (let i = 0; i < 4; i++)
    pending.push(fetch(url + "/api/episodes/1/play"));
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal((await fetch(url + "/api/episodes/1/play")).status, 503);
  release();
  for (const request of pending) assert.equal((await request).status, 200);
});
test("Content-Range parsing is tolerant and never invents a file size", () => {
  assert.equal(
    parseContentTotal({ "content-range": "bytes 0-262143/52428800" }),
    52428800
  );
  assert.equal(
    parseContentTotal({ "content-range": "bytes 0-262143 / 52428800" }),
    52428800
  );
  assert.equal(
    parseContentTotal({ "Content-Range": "BYTES 0-262143/52428800" }),
    52428800
  );
  assert.equal(parseContentTotal({ "content-range": "bytes 0-262143/*" }), 0);
  assert.equal(parseContentTotal({}), 0);
});
test("the encrypted sample entry follows the original fourcc from the frma box", () => {
  const box = (type, body) => {
    const head = Buffer.alloc(8);
    head.writeUInt32BE(8 + body.length, 0);
    head.write(type, 4, "ascii");
    return Buffer.concat([head, body]);
  };
  const entry = (tag, original) => {
    const sinf = box("sinf", box("frma", Buffer.from(original, "ascii")));
    const head = Buffer.alloc(8);
    head.writeUInt32BE(16 + sinf.length, 0);
    head.write(tag, 4, "ascii");
    return Buffer.concat([head, Buffer.alloc(8), sinf]);
  };
  const avc = entry("encv", "avc1");
  restoreSampleEntry(avc);
  assert.equal(avc.toString("ascii", 4, 8), "avc1");
  const hev = entry("encv", "hvc1");
  restoreSampleEntry(hev);
  assert.equal(hev.toString("ascii", 4, 8), "hvc1");
  const audio = entry("enca", "mp4a");
  restoreSampleEntry(audio);
  assert.equal(audio.toString("ascii", 4, 8), "mp4a");
});
test("the prepared CENC sample table does not stay on the cached stream object", async () => {
  const shared = { url: "https://cdn.example/x.mp4", key: null };
  let preparedCalls = 0;
  const api = {
    streamCache: new Map(),
    videoInfoCache: new Map(),
    pageCache: new Map(),
    appSearchCache: new Map(),
    async resolveStream(id, quality) {
      api.streamCache.set(`${id}|${quality}`, { time: Date.now(), value: shared });
      return shared;
    },
    async prepareHeader(source) {
      preparedCalls += 1;
      source.prepared = {
        total: 100,
        header: Buffer.alloc(8),
        samples: [{ start: 0, end: 10, iv: Buffer.alloc(16) }],
      };
      return source.prepared;
    },
    async remoteRange() {
      return { data: Buffer.alloc(10), total: 100 };
    },
    decryptPartial(data) {
      return data;
    },
  };
  const provider = createProvider(api);
  const stream = await provider.stream("123");
  assert.equal(stream.total, 100);
  assert.equal(shared.prepared, undefined);
  assert.equal(api.streamCache.get("123|auto").value.prepared, undefined);
  await provider.stream("123");
  assert.equal(preparedCalls, 1);
});
test("cache eviction is least-recently-used, not insertion ordered", async () => {
  const api = {
    streamCache: new Map(),
    videoInfoCache: new Map(),
    pageCache: new Map(),
    appSearchCache: new Map(),
  };
  for (const key of ["a", "b", "c"])
    api.pageCache.set(key, { time: Date.now(), value: {} });
  // "a" is the hottest entry: the adapter refreshes its insertion order on use.
  const value = api.pageCache.get("a");
  api.pageCache.delete("a");
  api.pageCache.set("a", value);
  sweepCaches(api, { stream: 8, videoInfo: 8, page: 2, appSearch: 8 });
  assert.equal(api.pageCache.has("a"), true);
  assert.equal(api.pageCache.has("b"), false);
  assert.equal(api.pageCache.has("c"), true);
});
test("the home page falls back to the nested category list", async () => {
  const api = {
    streamCache: new Map(),
    videoInfoCache: new Map(),
    pageCache: new Map(),
    appSearchCache: new Map(),
    async categoryPage() {
      return {
        loaderData: {
          category_page: {
            categoryData: {
              recommendList: [
                { series_id: "123", series_name: "剧", series_cover: "" },
              ],
            },
          },
        },
      };
    },
    itemToCard(item) {
      return {
        url: String(item.series_id),
        title: item.series_name,
        img: "",
        desc: "",
        tname: "",
        content: "",
      };
    },
  };
  const provider = createProvider(api);
  const home = await provider.home(1);
  assert.equal(home.items.length, 1);
  assert.equal(home.items[0].id, "123");
});
test("exposing the bridge beyond loopback without a token is refused", () => {
  assert.equal(bridgeExposureError("127.0.0.1", ""), null);
  assert.equal(bridgeExposureError("localhost", ""), null);
  assert.equal(bridgeExposureError("::1", ""), null);
  assert.equal(bridgeExposureError("0.0.0.0", "secret"), null);
  assert.ok(bridgeExposureError("0.0.0.0", ""));
  assert.ok(bridgeExposureError("192.168.1.10", ""));
});
