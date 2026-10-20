import fs from "node:fs";
import vm from "node:vm";
import crypto from "node:crypto";
import axios from "axios";

// Trusted, pinned GPL source. This is a compatibility wrapper, not a sandbox for user scripts.
const source = fs.readFileSync(
  new URL("./vendor/hongguo.cjs", import.meta.url),
  "utf8"
);
const context = vm.createContext({
  require: (name) => {
    if (name === "node:crypto") return crypto;
    throw new Error("Unsupported module");
  },
  Buffer,
  URL,
  URLSearchParams,
  axios: axios.create({
    maxContentLength: 16 * 1024 * 1024,
    maxBodyLength: 1024 * 1024,
  }),
});
vm.runInContext(
  source +
    "\nthis.api = {categoryPage, categoryData, itemToCard, appSearchPage, routerData, resolveStream, prepareHeader, remoteRange, decryptPartial, streamCache, videoInfoCache, pageCache, appSearchCache, parseContentTotal, restoreSampleEntry};",
  context,
  { timeout: 5000 }
);
const api = context.api;
const SITE = "https://hongguoduanju.com";
const SEARCH_TAB = 11;
const SEARCH_LIMIT = 20;
// The pinned source walks its app-search cursor chain from page 1 on every call, so one
// cold page=100 request used to cost 100 signed upstream requests. The adapter owns the
// chain instead: it warms at most APP_CHAIN_STEPS pages per request, so a client request
// always costs a constant number of upstream calls and the warmed chain is reused later.
const APP_CHAIN_STEPS = 2;
const CACHE_TTL = 300000;
// The pinned source caches are unbounded; the CENC sample table alone is ~1MB per stream.
const CACHE_LIMITS = { stream: 8, videoInfo: 16, page: 16, appSearch: 16 };
const PREPARED_LIMIT = 4;
const card = (row) => ({
  id: String(row.url),
  title: row.title,
  cover: row.img || "",
  description: row.content || "",
  badge: row.desc || "",
  tags: row.tname || "",
});
const cards = (rows) =>
  Array.from(rows)
    .filter((r) => /^\d+$/.test(String(r.url)) && r.title)
    .map(card);
const categoryUrl = (page) =>
  `${SITE}/category?tab=1&sort_type=1${page > 1 ? `&page=${page}` : ""}`;

// Drop expired entries and enforce the per-cache limits. The pinned caches are plain
// Maps, so the first key is the least recently used one as long as the adapter refreshes
// the insertion order of the entries it touches.
export function sweepCaches(api, limits = CACHE_LIMITS, preparedCache = null) {
  for (const [cache, limit] of [
    [api.streamCache, limits.stream],
    [api.videoInfoCache, limits.videoInfo],
    [api.pageCache, limits.page],
    [api.appSearchCache, limits.appSearch],
  ]) {
    if (!cache) continue;
    for (const [key, value] of cache)
      if (Date.now() - value.time > CACHE_TTL) cache.delete(key);
    while (cache.size > limit) cache.delete(cache.keys().next().value);
  }
  if (!preparedCache) return;
  for (const [key, value] of preparedCache)
    if (Date.now() - value.time > CACHE_TTL) preparedCache.delete(key);
  while (preparedCache.size > PREPARED_LIMIT)
    preparedCache.delete(preparedCache.keys().next().value);
}

export function createProvider(api, options = {}) {
  const limits = { ...CACHE_LIMITS, ...(options.limits || {}) };
  // Prepared CENC headers (header + ~1MB sample table) live here, not on the object the
  // pinned source keeps in streamCache.
  const preparedCache = new Map();

  // Map.set keeps the original insertion position, so recency has to be refreshed by
  // re-inserting: that is what makes the eviction below least-recently-used.
  const touch = (cache, key) => {
    if (!cache || !cache.has(key)) return;
    const value = cache.get(key);
    cache.delete(key);
    cache.set(key, value);
  };
  const touchPage = (url) => touch(api.pageCache, url);
  const touchApp = (keyword, page) =>
    touch(api.appSearchCache, `${SEARCH_TAB}|${keyword}|${page}`);

  // App search with an adapter-owned cursor chain. Returns null when the chain is not
  // deep enough for the requested page, so the caller can fall back to the website
  // instead of walking the whole chain upstream.
  const appSearchPage = async (keyword, page) => {
    let deepest = 0;
    for (let p = 1; p < page; p += 1) {
      const entry = api.appSearchCache?.get(`${SEARCH_TAB}|${keyword}|${p}`);
      if (entry && Date.now() - entry.time < CACHE_TTL) deepest = p;
      else break;
    }
    let steps = 0;
    while (deepest < page - 1 && steps < APP_CHAIN_STEPS) {
      deepest += 1;
      steps += 1;
      await api.appSearchPage(keyword, SEARCH_TAB, deepest, SEARCH_LIMIT);
      touchApp(keyword, deepest);
    }
    if (deepest < page - 1) return null;
    const result = await api.appSearchPage(keyword, SEARCH_TAB, page, SEARCH_LIMIT);
    touchApp(keyword, page);
    return result;
  };

  const preparedFor = async (id, source) => {    const cached = preparedCache.get(id);
    if (cached && Date.now() - cached.time < CACHE_TTL) {
      touch(preparedCache, id);
      return cached.prepared;
    }
    const prepared = await api.prepareHeader(source);
    // The pinned source hangs the prepared header (and its CENC sample table) on the very
    // object it keeps in streamCache; drop it so the cache entry stays small.
    delete source.prepared;
    preparedCache.set(id, { time: Date.now(), prepared });
    for (const [key, value] of preparedCache)
      if (Date.now() - value.time > CACHE_TTL) preparedCache.delete(key);
    while (preparedCache.size > PREPARED_LIMIT)
      preparedCache.delete(preparedCache.keys().next().value);
    return prepared;
  };

  // Limit the pinned upstream's otherwise unbounded caches during long-lived home use.
  // Eviction is least-recently-used: the touch() calls above refresh the insertion order.
  setInterval(() => sweepCaches(api, limits, preparedCache), 60000).unref();

  return {
    async home(page = 1) {
      const data = await api.categoryPage("tab=1&sort_type=1", page);
      touchPage(categoryUrl(page));
      const pageData =
        data?.loaderData?.category_page || data?.loaderData?.["category_$"] || {};
      // The pinned extractor also looks one level deeper; keep both so a payload move
      // cannot turn the home page into a 502.
      const list = pageData.recommendList || pageData.categoryData?.recommendList;
      if (!Array.isArray(list)) throw new Error("首页数据结构已变化");
      return { items: cards(list.map(api.itemToCard)), page };
    },
    async search(keyword, page = 1) {
      try {
        const result = await appSearchPage(keyword, page);
        const items = result ? cards(result.list) : [];
        // Judge on the filtered items: an app list that filters down to nothing must not
        // become a 200 with an empty list, the client would show "no results" and stop.
        if (items.length) return { items, page };
      } catch {
        /* Website fallback; failure below remains an error, never an empty success. */
      }
      const url = `${SITE}/search/${encodeURIComponent(keyword)}?page=${page}`;
      const data = await api.routerData(url);
      touchPage(url);
      const pageData =
        data?.loaderData?.["search_(keyword)/page"] ||
        data?.loaderData?.search_page;
      if (!Array.isArray(pageData?.searchList)) throw new Error("搜索数据不可用");
      return { items: cards(pageData.searchList.map(api.itemToCard)), page };
    },
    async detail(id) {
      const url = `${SITE}/detail?series_id=${id}`;
      const data = await api.routerData(url);
      touchPage(url);
      const series = data?.loaderData?.detail_page?.seriesDetail;
      if (!series || !Array.isArray(series.vid_list) || !series.vid_list.length)
        throw new Error("剧集不存在或分集暂不可用");
      const episodes = Array.from(series.vid_list, (vid, index) => ({
        id: String(vid),
        title: `第 ${index + 1} 集`,
        number: index + 1,
      }));
      if (
        episodes.some((e) => !/^\d+$/.test(e.id)) ||
        new Set(episodes.map((e) => e.id)).size !== episodes.length
      )
        throw new Error("分集数据异常");
      return { ...card(api.itemToCard(series)), id, episodes };
    },
    async stream(id, fresh = false) {
      if (fresh) {
        api.streamCache?.delete(`${id}|auto`);
        api.videoInfoCache?.delete(id);
        preparedCache.delete(id);
      }
      const source = await api.resolveStream(id, "auto");
      touch(api.streamCache, `${id}|auto`);
      touch(api.videoInfoCache, id);
      const prepared = await preparedFor(id, source);
      return {
        total: prepared.total,
        chunk: async (start, end) =>
          api.decryptPartial(
            (await api.remoteRange(source, start, end)).data,
            start,
            source.key,
            prepared
          ),
      };
    },
  };
}

export const provider = createProvider(api);
// Re-exported for the adapter tests: tolerant Content-Range parsing lives in the pinned
// source, the service must not silently fall back to a 256KB "total".
export const parseContentTotal = api.parseContentTotal;
// Re-exported for the adapter tests: the CENC sample entry must follow the original
// fourcc from the frma box instead of being hardcoded to HEVC.
export const restoreSampleEntry = api.restoreSampleEntry;
