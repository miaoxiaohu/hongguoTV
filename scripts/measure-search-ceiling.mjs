// Measures the search ceiling of the site: how many rows a search page can ever return.
//
// Why this is worth a script: the site's public search renders exactly ten rows and ignores every
// paging parameter we have tried, while the page's own totalCount reports 90-190. A client that
// trusts the page therefore shows ten results and calls it "all of them", and a show that exists
// but is not in those ten looks like a show that does not exist.
//
// Measured 2026-10-19 on the keyword 回到心跳开始时: eight different paging parameters, four
// header combinations, and a keyword that exists nowhere in the catalogue - every one of them
// answers with the same ten rows in the same order. The ceiling is the server's.
//
// This script re-measures that, so the claim in the code comments can be checked instead of
// trusted, and so a future change in the site's behaviour shows up as a failure here rather than
// as a mystery complaint.
//
// Usage: node measure-search-ceiling.mjs [keyword]
//   keyword defaults to 回到心跳开始时. Exit 0 when every variant agrees (the ceiling holds),
//   1 when they do not (the site's behaviour changed and the app's assumptions need revisiting).
import { writeFileSync } from "node:fs";

const SITE = "https://hongguoduanju.com";
const UA =
  "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
  "Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
const KEYWORD = process.argv[2] || "回到心跳开始时";
const NONSENSE = "zzz不存在的剧名zzz";

/** Pulls the JSON blob the page hides in its _ROUTER_DATA script tag. */
function extractRouter(html) {
  const at = html.indexOf("_ROUTER_DATA");
  if (at < 0) return null;
  const first = html.indexOf("{", at);
  let depth = 0, quoted = false, esc = false;
  for (let i = first; i < html.length; i++) {
    const ch = html[i];
    if (quoted) { if (esc) esc = false; else if (ch === "\\") esc = true; else if (ch === `"`) quoted = false; continue; }
    if (ch === `"`) quoted = true;
    else if (ch === "{" || ch === "[") depth++;
    else if (ch === "}" || ch === "]") { depth--; if (depth === 0) return JSON.parse(html.slice(first, i + 1)); }
  }
  return null;
}

const titleOf = (row) => {
  const o = row?.video_data || row || {};
  return o.series_title || o.series_name || o.name || "";
};
const idOf = (row) => {
  const o = row?.video_data || row || {};
  return String(o.series_id || o.book_id || o.vid || "");
};

async function search(url) {
  const res = await fetch(url, { headers: { "User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9" }, signal: AbortSignal.timeout(30000) });
  const text = await res.text();
  const loader = extractRouter(text)?.loaderData || {};
  const section = loader["search_(keyword)/page"] || loader.search_page;
  const rows = Array.isArray(section?.searchList) ? section.searchList : [];
  return {
    http: res.status,
    bytes: text.length,
    rows: rows.length,
    total: section?.totalCount ?? null,
    first: rows.length ? `${idOf(rows[0])} ${titleOf(rows[0])}` : "",
    fingerprint: rows.map(idOf).join(","),
  };
}

// Every paging parameter we have ever tried, plus a plain request.
const VARIANTS = [
  ["(no parameter)", ""],
  ["page=1", "?page=1"],
  ["page=2", "?page=2"],
  ["p=2", "?p=2"],
  ["pageNum=2", "?pageNum=2"],
  ["page_num=2", "?page_num=2"],
  ["pn=2", "?pn=2"],
  ["offset=24", "?offset=24"],
  ["limit=100", "?limit=100"],
  ["pageSize=50", "?pageSize=50"],
];

const results = [];
for (const [label, query] of VARIANTS) {
  const url = `${SITE}/search/${encodeURIComponent(KEYWORD)}${query}`;
  try {
    const r = await search(url);
    results.push({ label, query, ...r });
    console.log(
      `${label.padEnd(16)} http=${r.http} bytes=${String(r.bytes).padStart(7)} rows=${String(r.rows).padStart(3)} ` +
      `totalCount=${String(r.total).padStart(4)} first="${r.first}"`
    );
  } catch (e) {
    results.push({ label, query, error: String(e.message || e) });
    console.log(`${label.padEnd(16)} FAILED ${String(e.message || e)}`);
  }
}

let nonsense = null;
try {
  nonsense = await search(`${SITE}/search/${encodeURIComponent(NONSENSE)}?page=1`);
  console.log(
    `\nnonsense keyword "${NONSENSE}": rows=${nonsense.rows} totalCount=${nonsense.total} first="${nonsense.first}"`
  );
} catch (e) {
  console.log(`\nnonsense keyword FAILED ${String(e.message || e)}`);
}

const ok = results.filter((r) => !r.error);
const rowCounts = [...new Set(ok.map((r) => r.rows))];
const prints = [...new Set(ok.map((r) => r.fingerprint))];
const ceiling = rowCounts.length === 1 ? rowCounts[0] : null;
const sameRows = prints.length === 1;

console.log(`\n=== summary ===`);
console.log(`keyword        : ${KEYWORD}`);
console.log(`variants tried : ${ok.length}/${VARIANTS.length}`);
console.log(`row counts seen: ${rowCounts.join(", ")}`);
console.log(`same ten rows  : ${sameRows ? "yes - every variant answers with the identical list" : "no - the variants disagree"}`);
if (nonsense) {
  console.log(
    `nonsense rows  : ${nonsense.rows} ` +
    (nonsense.rows > 0 ? "(the site never answers with an empty list, so 'no results' cannot come from it)" : "")
  );
}
const verdict =
  ceiling !== null && sameRows && ceiling <= 10
    ? `CEILING HOLDS: every variant answers with the same ${ceiling} rows.`
    : `CEILING CHANGED: row counts ${rowCounts.join(", ")}, identical=${sameRows}. The app's assumptions need re-measuring.`;
console.log(verdict);

// A machine-readable copy, so the measurement can be pasted into an issue without retyping it.
writeFileSync(
  "search-ceiling.json",
  JSON.stringify({ at: new Date().toISOString(), keyword: KEYWORD, results, nonsense, ceiling, sameRows }, null, 2)
);
console.log("wrote search-ceiling.json");

process.exit(ceiling !== null && sameRows && ceiling <= 10 ? 0 : 1);
