import { useOrders } from '../hooks/usePagedResource';
import { useCallback, useState } from 'react';
import { api } from '../api';
import { CLOSED_STATUSES, WorkspaceRole } from '../workspace';
import OrderItem from '../components/OrderItem';
import Icon from '../components/Icon';

export default function Mine({ role }: { role: WorkspaceRole }) {
  const runner = role === 'RUNNER';
  const [tab, setTab] = useState('ALL');
  const [sort, setSort] = useState('priority');
  const queryRole = runner ? 'GRABBED_BY_ME' : 'PUBLISHED_BY_ME';
  const loader = useCallback((page: number) => api.mine(queryRole, page, sort), [queryRole, sort]);
  const { items: cards, error, loading, more, refresh, loadMore } = useOrders(loader);
  const filters = [ ['ALL', '全部订单'], ['ACTIVE', runner ? '待履约' : '进行中'], ['DELIVERED', runner ? '待用户验收' : '待我验收'], ['SETTLED', '已完成'], ['DISPUTED', '争议中'], ['CLOSED', '已关闭 / 退款'] ];
  const matches = (status: string, key: string) => key === 'ALL' || (key === 'ACTIVE' ? ['PUBLISHED','LOCKED','ACCEPTED','PICKED_UP'].includes(status) : key === 'CLOSED' ? CLOSED_STATUSES.has(status) && status !== 'SETTLED' : status === key);
  const visible = cards.filter(c => matches(c.status, tab));
  const pending = cards.filter(c => c.availableActions.some(a => ['CONFIRM','PICKUP','DELIVER','SETTLE'].includes(a))).length;
  return <div className="orders-page"><div className="page-head"><div><span className="eyebrow">{runner ? 'MY DELIVERIES' : 'MY ORDERS'}</span><h1>{runner ? '我的接单' : '我的订单'}</h1><p>{runner ? '先处理需要履约的订单，再查看已完成的记录。' : '需要你确认的托付，放在前面。'}</p></div><a className="btn btn-primary" href={runner ? '#/runner/hall' : '#/user/publish'}><Icon name="plus" size={17}/>{runner ? '去接新单' : '发布新订单'}</a></div>
    <div className="order-overview"><div><span className="overview-icon"><Icon name="orders"/></span><span><small>已加载订单</small><strong>{loading && !cards.length ? '—' : cards.length}</strong></span></div><div className="overview-pending"><span className="overview-icon"><Icon name="clock"/></span><span><small>待我处理</small><strong>{loading && !cards.length ? '—' : pending}</strong></span></div><div><span className="overview-icon"><Icon name="shield"/></span><span><small>已完成</small><strong>{loading && !cards.length ? '—' : cards.filter(c => c.status === 'SETTLED').length}</strong></span></div><p>数量统计当前已加载记录</p></div>
    <section className="orders-panel"><div className="orders-toolbar"><div className="tabs" aria-label="订单状态">{filters.map(([key, text]) => <button key={key} className={`tab ${key === tab ? 'active' : ''}`} aria-pressed={key === tab} onClick={() => setTab(key)}>{text}<span>{cards.filter(c => matches(c.status, key)).length}</span></button>)}</div><select aria-label="我的订单排序" value={sort} onChange={e => setSort(e.target.value)}><option value="priority">待处理优先</option><option value="latest">最新发布</option><option value="reward">悬赏从高到低</option></select></div>
      <div className="order-table-heading" aria-hidden="true"><span>任务信息</span><span>状态与进度</span><span>悬赏</span><span>操作</span></div>
      {error && <div className="banner warn" role="alert">加载失败：{error}<button className="link-btn" disabled={loading} onClick={refresh}>重试</button></div>}
      {loading && cards.length === 0 ? <div className="empty">正在整理你的订单…</div> : !error && visible.length === 0 ? <div className="empty"><Icon name="orders" size={32}/><h3>当前分类暂无已加载订单</h3><p>{more ? '还有历史记录尚未加载，可继续加载查看。' : runner ? '前往接单大厅，发现适合你的需求。' : '发布需求后，可以在这里跟进进度。'}</p></div> : <div className="order-list">{visible.map(c => <OrderItem key={c.id} card={c} onChanged={refresh} layout="row" role={role}/>)}</div>}
      <div className="order-list-footer"><span>已加载 {cards.length} 笔 · 当前分类 {visible.length} 笔</span>{more ? <button className="btn" onClick={loadMore} disabled={loading}>{loading ? '加载中…' : '加载更多订单'}</button> : cards.length > 0 && <span>已展示全部记录</span>}</div>
    </section>
  </div>;
}
