import {
  ContentProvider,
  Detail,
  Playback,
  Series,
  Settings,
} from "../domain/model";
/** Reads a response body without letting a proxy's HTML page become a JSON error. */
function parseBody(text: string): { json: boolean; data: any } {
  if (!text.trim()) return { json: false, data: null };
  try {
    const data = JSON.parse(text);
    return { json: data !== null, data };
  } catch {
    return { json: false, data: null };
  }
}
export class BridgeProvider implements ContentProvider {
  constructor(private settings: Settings) {}
  private headers(): Record<string, string> {
    return this.settings.token
      ? { Authorization: `Bearer ${this.settings.token}` }
      : {};
  }
  async request(path: string): Promise<any> {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 65000);
    try {
      const response = await fetch(this.settings.baseUrl + path, {
        headers: this.headers(),
        signal: abort.signal,
      });
      // The body is read as text first: a proxy or gateway answers with an HTML
      // error page, and parsing that as JSON threw a raw SyntaxError on the TV
      // before the status was ever looked at.
      const body = await response.text();
      const parsed = parseBody(body);
      if (!response.ok) {
        const detail =
          parsed.json && typeof parsed.data?.error === "string"
            ? String(parsed.data.error)
            : "";
        throw new Error(
          detail ||
            (parsed.json
              ? `服务返回 ${response.status}`
              : `服务返回 ${response.status}，返回的不是服务数据，请确认地址指向红果 TV 服务`)
        );
      }
      if (!parsed.json)
        throw new Error(
          "服务返回的内容无法解析，请确认服务地址是否正确（例如 http://192.168.1.10:8787）"
        );
      return parsed.data;
    } catch (error) {
      if (
        error instanceof Error &&
        error.message !== "Network request failed" &&
        error.name !== "AbortError"
      )
        throw error;
      throw new Error(
        "无法连接内容服务，请检查电视网络、服务地址和电脑上的服务进程"
      );
    } finally {
      clearTimeout(timer);
    }
  }
  async health() {
    const data = await this.request("/health");
    if (data.service !== "hongguotv" || data.apiVersion !== 1)
      throw new Error("服务版本不兼容");
  }
  private async list(path: string): Promise<Series[]> {
    const data = await this.request(path);
    if (
      !Array.isArray(data.items) ||
      data.items.some((item: Series) => !item.id || !item.title)
    )
      throw new Error("列表数据格式异常");
    return data.items;
  }
  home(page: number) {
    return this.list(`/api/home?page=${page}`);
  }
  search(query: string, page: number) {
    return this.list(`/api/search?q=${encodeURIComponent(query)}&page=${page}`);
  }
  async detail(id: string): Promise<Detail> {
    const data = await this.request(`/api/series/${encodeURIComponent(id)}`);
    if (!Array.isArray(data.episodes) || !data.episodes.length)
      throw new Error("这部剧暂时没有可播放的分集");
    return data;
  }
  async resolve(id: string, fresh = false): Promise<Playback> {
    const data = await this.request(
      `/api/episodes/${encodeURIComponent(id)}/play?fresh=${fresh ? 1 : 0}`
    );
    if (data.path !== `/api/episodes/${id}/stream`)
      throw new Error("播放地址格式异常");
    return { uri: this.settings.baseUrl + data.path, headers: this.headers() };
  }
}
