export interface Series {
  id: string;
  title: string;
  cover: string;
  description: string;
  badge: string;
  tags: string;
}
export interface Episode {
  id: string;
  number: number;
  title: string;
}
export interface Detail extends Series {
  episodes: Episode[];
}
export interface Progress {
  series: Series;
  episodeId: string;
  episodeNumber: number;
  position: number;
  duration: number;
  completed: boolean;
  updatedAt: number;
}
export interface Library {
  favorites: Series[];
  progress: Record<string, Progress>;
}
export interface Settings {
  baseUrl: string;
  token: string;
}
export interface Playback {
  uri: string;
  headers: Record<string, string>;
}
export interface ContentProvider {
  home(page: number): Promise<Series[]>;
  search(query: string, page: number): Promise<Series[]>;
  detail(id: string): Promise<Detail>;
  resolve(id: string, fresh?: boolean): Promise<Playback>;
}
export function resumeTarget(
  detail: Detail,
  progress?: Progress
): { index: number; position: number } {
  if (!progress) return { index: 0, position: 0 };
  const index = detail.episodes.findIndex((e) => e.id === progress.episodeId);
  if (index < 0) return { index: 0, position: 0 };
  if (progress.completed)
    return {
      index: Math.min(index + 1, detail.episodes.length - 1),
      position: 0,
    };
  return { index, position: Math.max(0, progress.position) };
}
export function mergePage(existing: Series[], incoming: Series[]): Series[] {
  return Array.from(
    new Map([...existing, ...incoming].map((item) => [item.id, item])).values()
  );
}
/**
 * Whether asking for another page is worth it. The site ignores every paging
 * parameter and answers with the same rows, so "a page came back" says nothing
 * about "there is more": only rows the list did not have yet count. A replaced
 * list starts over at page one, where the answer is still unknown.
 */
export function hasMorePages(
  existing: Series[],
  incoming: Series[],
  replace = false
): boolean {
  if (!incoming.length) return false;
  if (replace) return true;
  return mergePage(existing, incoming).length > existing.length;
}
/** The button row a given episode belongs to, counted in groups of `size`. */
export function episodeGroup(index: number, size: number): number {
  return Math.floor(Math.max(0, index) / size);
}
const BASE_URL =
  /^https?:\/\/(?:[a-z\d.\-]+|\[[0-9a-f:.]+\])(?::(?<port>\d{1,5}))?$/i;
export function normalizeBaseUrl(input: string): string {
  const url = input.trim().replace(/\/+$/, "");
  const match = BASE_URL.exec(url);
  if (!match)
    throw new Error("请输入完整服务地址，例如 http://192.168.1.10:8787");
  const port = match.groups?.port;
  if (port !== undefined && (Number(port) < 1 || Number(port) > 65535))
    throw new Error("服务地址的端口号无效，请填写 1–65535 之间的端口");
  return url;
}
