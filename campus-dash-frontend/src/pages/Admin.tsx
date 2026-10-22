import { useOrders } from '../hooks/usePagedResource';
import { useCallback, useState } from 'react';
import { api, ErrandCard, STATUS_TEXT, yuan } from '../api';
import Icon from '../components/Icon';

export default function Admin({ disputesOnly }: { disputesOnly: boolean }) {
  const [status, setStatus] = useState(disputesOnly ? 'DISPUTED' : 'PUBLISHED');
  const loader = useCallback((page: number) => api.list(1, status, page), [status]);
  const { items: cards, error, loading, more, refresh: load, loadMore } = useOrders(loader);
  return <div><div className="page-head"><div><span className="eyebrow">PLATFORM OPERATIONS</span><h1>{disputesOnly ? '争议处理' : '订单查询'}</h1><p>{disputesOnly ? '核实双方情况与订单记录，再决定托管资金的归属。' : '按订单状态查询，查看参与账户和流转记录。'}</p></div><button className="btn" disabled={loading} onClick={load}>刷新列表</button></div>
    <div className="admin-notice"><Icon name="shield" size={26}/><div><strong>平台独立处理空间</strong><p>查看订单详情与时间线；争议订单可选择结算给跑腿员或退款给用户。</p></div></div>
    {!disputesOnly && <div className="list-toolbar"><label>订单状态 <select value={status} onChange={e => setStatus(e.target.value)}>{Object.entries(STATUS_TEXT).filter(([k]) => k !== 'DRAFT').map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label></div>}
    {error && <div className="banner warn" role="alert">加载失败：{error}</div>}
    <div className="table-scroll"><table className="ledger-table"><thead><tr><th>订单 / 需求</th><th>发布用户</th><th>跑腿员</th><th>悬赏</th><th>状态</th><th>操作</th></tr></thead><tbody>{cards.map(c => <tr key={c.id}><td><strong>{c.title}</strong><small className="table-sub">#{c.id}</small></td><td>#{c.publisherId}</td><td>{c.grabberId && c.grabberId !== '-1' ? `#${c.grabberId}` : '待匹配'}</td><td>{yuan(c.rewardCents)}</td><td><span className={`status status-${c.status}`}>{STATUS_TEXT[c.status]}</span></td><td><a href={`#/detail/${c.id}`}>{disputesOnly ? '处理争议' : '查看详情'} →</a></td></tr>)}{cards.length === 0 && <tr><td colSpan={6} className="table-empty">{loading ? '正在加载…' : error ? '暂时无法获取订单' : disputesOnly ? '暂无待处理争议' : '该状态下暂无订单'}</td></tr>}</tbody></table></div>
    {more && <div className="load-more"><button className="btn" disabled={loading} onClick={loadMore}>加载更多订单</button></div>}
  </div>;
}
