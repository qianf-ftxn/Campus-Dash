import { useCallback, useRef, useState } from 'react';
import { api, yuan } from '../api';
import { usePagedResource } from '../hooks/usePagedResource';
const key = (item: Awaited<ReturnType<typeof api.notifications>>[number]) => item.id;

export default function Notifications() {
  const loader = useCallback((page: number) => api.notifications(page), []);
  const { items, error, loading, more, refresh, loadMore } = usePagedResource(loader, key);
  const [readError, setReadError] = useState('');
  const [marking, setMarking] = useState(false);
  const locked = useRef(false);
  const markAll = async () => {
    if (locked.current) return;
    locked.current = true; setMarking(true); setReadError('');
    try { await api.markAllRead(); window.dispatchEvent(new Event('dash:notifications-read')); await refresh(); }
    catch (e) { setReadError((e as Error).message); }
    finally { locked.current = false; setMarking(false); }
  };
  return <div>
    <div className="page-head"><div><span className="eyebrow">NOTIFICATIONS</span><h1>消息中心</h1><p>结算、退款与争议处理结果，都在这里。</p></div><button className="btn" disabled={marking || loading || !items.some(n => !n.read)} onClick={markAll}>{marking ? '正在标记…' : '全部标为已读'}</button></div>
    {(error || readError) && <div className="banner warn" role="alert">{error || readError}<button className="link-btn" disabled={loading} onClick={refresh}>重新加载</button></div>}
    {loading && items.length === 0 ? <div className="empty">正在加载消息…</div> : !error && items.length === 0 && <div className="empty">暂无消息</div>}
    {items.map(n => <div key={n.id} className={`notif-item ${n.read ? 'is-read' : 'is-unread'}`}><span className="notif-dot" aria-label={n.read ? '已读' : '未读'}/><span className={`notif-type notif-${n.type.toLowerCase()}`}>{{ SETTLED: '结算通知', REFUNDED: '退款通知', ARBITRATED: '争议结果' }[n.type] ?? '订单通知'}</span><a href={`#/detail/${n.errandId}`}>{['SETTLED', 'REFUNDED', 'ARBITRATED'].includes(n.type) ? n.content.replace(/(\d+)\s+分(?!钟)/g, (_, cents) => yuan(Number(cents))) : n.content}</a><span className="muted">{new Date(n.time).toLocaleString()}</span></div>)}
    {more && <div className="load-more"><button className="btn" disabled={loading} onClick={loadMore}>加载更多消息</button></div>}
  </div>;
}
