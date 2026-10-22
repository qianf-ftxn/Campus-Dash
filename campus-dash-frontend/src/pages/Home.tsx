import { useOrders } from '../hooks/usePagedResource';
import { useCallback } from 'react';
import { api, ErrandCard } from '../api';
import { CLOSED_STATUSES, TYPE_TEXT } from '../workspace';
import Icon from '../components/Icon';
import ErrandCardView from '../components/ErrandCard';

export default function Home() {
  const loader = useCallback((page: number) => api.mine('PUBLISHED_BY_ME', page), []);
  const { items: orders, error, loading, refresh: load } = useOrders(loader);
  const active = orders.filter(o => !CLOSED_STATUSES.has(o.status));
  return <div>
    <div className="page-head"><div><span className="eyebrow">YOUR CAMPUS, MADE EASIER</span><h1>今天，有什么需要帮忙？</h1><p>从一份午餐到一件快递，让顺路的同学搭把手。</p></div><span className="page-note">校园生活服务</span></div>
    <section className="home-hero"><div className="hero-copy"><span className="hero-kicker">校园里的小事，我们一起完成</span><h2>你安心忙，<br />琐事交给顺路的人。</h2><p>发布需求 · 悬赏托管 · 完成后确认</p><a className="btn btn-primary" href="#/user/publish">{orders.length ? '发布一份新的托付' : '发布我的第一份托付'} <Icon name="arrow" size={17} /></a></div><div className="hero-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="art-dash dash-one"/><div className="art-dash dash-two"/><div className="art-tile tile-main"><Icon name="bag" size={72}/></div><div className="art-tile tile-box"><Icon name="box" size={37}/></div><div className="art-tile tile-check"><Icon name="shield" size={30}/></div><span className="art-label">一份托付，安心送达</span></div></section>
    <div className="service-grid">{Object.entries(TYPE_TEXT).map(([type, title], i) => <a key={type} className="service-card" href={`#/user/publish?type=${type}`}><span className={`service-icon service-${i}`}><Icon name={['box', 'bag', 'clock', 'plus'][i]} size={25}/></span><div><strong>{title}</strong><p>{['快递、文件，顺路送到', '餐饮、日用，帮你带回', '节省等待，腾出时间', '写下你的个性化需求'][i]}</p></div><Icon name="arrow" size={17}/></a>)}</div>
    <div className="section-head"><div><h2>最近的托付</h2><p>最近 {orders.length} 笔订单中，{active.length} 笔尚未结束，{orders.filter(o => o.status === 'DELIVERED').length} 笔待你验收</p></div><a href="#/user/orders">全部订单 <span aria-hidden="true">↗</span></a></div>
    {error && <div className="banner warn" role="alert">订单加载失败：{error}<button className="link-btn" onClick={load}>重试</button></div>}
    {loading ? <div className="empty">正在获取订单…</div> : !error && orders.length === 0 ? <div className="empty"><Icon name="orders" size={32}/><h3>还没有发布过订单</h3><p>从取快递、带份饭开始，让校园生活轻松一点。</p><a href="#/user/publish" className="btn btn-primary">去发布订单</a></div> : <div className="card-grid">{orders.slice(0, 3).map(o => <ErrandCardView key={o.id} card={o} onChanged={load}/>)}</div>}
    <section className="steps-strip"><div><span>01</span><strong>描述需求</strong><small>选择服务，写清具体事项</small></div><div><span>02</span><strong>等待接单</strong><small>悬赏托管，跟进订单进度</small></div><div><span>03</span><strong>确认完成</strong><small>验收后，悬赏结算给跑腿员</small></div></section>
  </div>;
}
