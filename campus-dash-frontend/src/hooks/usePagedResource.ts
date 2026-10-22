import { useCallback, useEffect, useRef, useState } from 'react';
import { emptyPage, PagedCollection, PageSnapshot } from '../paged';
import { subscribeWs } from '../ws';

const byId = (item: { id: string }) => item.id;
export function usePagedResource<T>(loader: (page: number) => Promise<T[]>, key: (item: T) => string,
                                   pollMs = 15000) {
  const controller = useRef<PagedCollection<T> | null>(null);
  const [state, setState] = useState<{ loader: typeof loader; value: PageSnapshot<T> }>({ loader, value: emptyPage() });
  useEffect(() => {
    const collection = new PagedCollection(loader, key, value => setState({ loader, value }));
    controller.current = collection;
    void collection.refresh();
    const update = () => { if (!document.hidden && collection.pages <= 1) void collection.refresh(); };
    const unsubscribe = subscribeWs(update);
    const timer = window.setInterval(update, pollMs);
    return () => { collection.dispose(); unsubscribe(); clearInterval(timer); };
  }, [loader, key, pollMs]);
  const refresh = useCallback(() => controller.current?.refresh(), []);
  const loadMore = useCallback(() => controller.current?.loadMore(), []);
  return { ...(state.loader === loader ? state.value : emptyPage<T>()), refresh, loadMore };
}
export function useOrders(loader: (page: number) => Promise<import('../api').ErrandCard[]>) {
  return usePagedResource(loader, byId);
}
