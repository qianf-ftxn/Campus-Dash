import { useOrders } from '../hooks/usePagedResource';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { TYPE_TEXT } from '../workspace';
import OrderItem from '../components/OrderItem';
import Icon from '../components/Icon';
export default function Square() {
  const [type, setType] = useState('ALL');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState('latest');
  const [query, setQuery] = useState('');
  useEffect(() => { const timer = setTimeout(() => setQuery(search.trim()), 250); return () => clearTimeout(timer); }, [search]);
  const loader = useCallback((page: number) => api.hall(page, type, query, sort), [type, query, sort]);
  const { items: cards, error, loading, more, refresh, loadMore } = useOrders(loader);
  const visible = cards.filter(c => c.availableActions.includes('GRAB'));
  return <div className="hall-page"><div className="page-head"><div><span className="eyebrow">CAMPUS ERRAND BOARD</span><h1>接单大厅</h1><p>看看校园里有哪些顺路的事，选择适合你的托付。</p></div><div className="page-head-actions"><button className="btn" disabled={loading} onClick={refresh}>刷新需求</button><a className="btn btn-primary" href="#/runner/orders">我的接单 <Icon name="arrow" size={16}/></a></div></div>
    <div className="hall-notice"><Icon name="shield" size={19}/><span>接单后请及时确认，完成送达后等待用户验收。</span><span className="hall-campus"><Icon name="location" size={14}/>默认校区</span></div>
    <section className="hall-controls"><div className="hall-filter-top"><div className="tabs" aria-label="订单类型">{[['ALL','全部需求'], ...Object.entries(TYPE_TEXT)].map(([key,text]) => <button key={key} aria-pressed={type === key} className={`tab ${type === key ? 'active' : ''}`} onClick={() => setType(key)}>{text}</button>)}</div><div className="search-field"><Icon name="search" size={17}/><input aria-label="搜索需求" maxLength={64} value={search} onChange={e => setSearch(e.target.value)} placeholder="搜索任务或取送地点"/>{search && <button className="clear-search" aria-label="清空搜索" onClick={() => setSearch('')}>×</button>}</div></div><div className="hall-filter-bottom"><span>{loading && !cards.length ? '正在查找校园需求…' : `已展示 ${visible.length} 个待接需求`}</span><div className="sort-switch" aria-label="订单排序"><button aria-pressed={sort === 'latest'} className={sort === 'latest' ? 'active' : ''} onClick={() => setSort('latest')}><Icon name="clock" size={14}/>最新发布</button><button aria-pressed={sort === 'reward'} className={sort === 'reward' ? 'active' : ''} onClick={() => setSort('reward')}><Icon name="wallet" size={14}/>悬赏优先</button></div></div></section>
    {error && <div className="banner warn" role="alert">加载失败：{error}<button className="link-btn" onClick={refresh}>重试</button></div>}
    {loading && cards.length === 0 ? <div className="hall-grid" aria-label="正在加载需求">{[0,1,2,3].map(i => <div className="hall-skeleton" key={i}><i/><i/><i/></div>)}</div> : !error && visible.length === 0 ? <div className="empty"><Icon name="box" size={32}/><h3>暂时没有匹配的需求</h3><p>换个关键词或服务类型，看看其他校园托付。</p><button className="btn" onClick={() => { setType('ALL'); setSearch(''); }}>查看全部需求</button></div> : <div className="hall-grid">{visible.map(c => <OrderItem key={c.id} card={c} onChanged={refresh} layout="hall"/>)}</div>}
    <div className="hall-pagination">{more ? <button className="btn" disabled={loading} onClick={loadMore}>{loading ? '加载中…' : '再看看更多需求'}</button> : !loading && visible.length > 0 && <span>已经看到全部匹配需求了</span>}</div>
  </div>;
}
