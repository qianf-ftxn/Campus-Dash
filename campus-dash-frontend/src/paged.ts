export interface PageSnapshot<T> { items: T[]; loading: boolean; error: string; more: boolean }
export const emptyPage = <T>(): PageSnapshot<T> => ({ items: [], loading: true, error: '', more: false });

/** Serializes pagination and commits refreshes atomically. Disposed collections ignore late responses. */
export class PagedCollection<T> {
  snapshot: PageSnapshot<T> = emptyPage();
  pages = 0;
  private active = true;
  private flight: Promise<void> | null = null;
  constructor(private fetchPage: (page: number) => Promise<T[]>, private key: (item: T) => string,
              private changed: (snapshot: PageSnapshot<T>) => void, private size = 20) {}
  dispose() { this.active = false; }
  refresh() { return this.run(false); }
  loadMore() { return this.snapshot.more ? this.run(true) : Promise.resolve(); }
  private run(append: boolean): Promise<void> {
    if (!this.active) return Promise.resolve();
    if (this.flight) return this.flight;
    this.snapshot = { ...this.snapshot, loading: true, error: '' };
    this.changed(this.snapshot);
    this.flight = this.fetch(append).finally(() => { this.flight = null; });
    return this.flight;
  }
  private async fetch(append: boolean) {
    try {
      const count = append ? 1 : Math.max(this.pages, 1);
      const rows: T[][] = [];
      for (let start = 0; start < count; start += 3) {
        if (!this.active) return;
        rows.push(...await Promise.all(Array.from({ length: Math.min(3, count - start) }, (_, i) =>
          this.fetchPage(append ? this.pages : start + i))));
      }
      if (!this.active) return;
      const merged = append ? [...this.snapshot.items, ...rows.flat()] : rows.flat();
      this.pages = append ? this.pages + 1 : count;
      this.snapshot = { items: [...new Map(merged.map(item => [this.key(item), item])).values()],
        more: rows[rows.length - 1].length === this.size, loading: false, error: '' };
    } catch (e) {
      if (!this.active) return;
      const denied = ['FORBIDDEN', 'UNAUTHORIZED'].includes((e as { code?: string }).code ?? '');
      this.snapshot = { ...this.snapshot, ...(denied ? { items: [], more: false } : {}), loading: false,
        error: e instanceof Error ? e.message : '加载失败，请重试' };
    }
    this.changed(this.snapshot);
  }
}
