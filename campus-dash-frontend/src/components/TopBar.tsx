import { useEffect, useState } from 'react';
import { api } from '../api';
import { IDENTITIES, WORKSPACES, WorkspaceRole } from '../workspace';
import { subscribeWs } from '../ws';
import Icon from './Icon';

export default function TopBar({ route, userId, role, onLogout }: { route: string; userId: number; role: WorkspaceRole; onLogout: () => void }) {
  const [unread, setUnread] = useState(0);
  useEffect(() => {
    if (role === 'ARBITRATOR') return;
    let active = true;
    const load = () => api.unread().then(r => { if (active) setUnread(r.count); }).catch(() => {});
    load();
    const unsub = subscribeWs(load);
    window.addEventListener('dash:notifications-read', load);
    const timer = window.setInterval(load, 15000);
    return () => { active = false; unsub(); clearInterval(timer); window.removeEventListener('dash:notifications-read', load); };
  }, [userId, role]);
  const workspace = WORKSPACES[role];
  return <aside className="sidebar">
    <a className="brand" href={`#${workspace.home}`}><span className="brand-symbol"><Icon name="activity" /></span><span>校园顺路<small>CampusDash</small></span></a>
    <div className="sidebar-label">{workspace.name}</div>
    <nav aria-label={`${workspace.short}导航`}>{workspace.nav.map(([path, text, icon]) => <a key={path} href={`#${path}`} className={route.split('?')[0] === path ? 'active' : ''} aria-current={route.split('?')[0] === path ? 'page' : undefined}><Icon name={icon} /><span>{text}</span>{path.endsWith('/messages') && unread > 0 && <span className="badge">{unread > 99 ? '99+' : unread}</span>}</a>)}</nav>
    <div className="sidebar-tip"><Icon name="shield" size={24} /><strong>{role === 'RUNNER' ? '认真送达，每一份信任' : role === 'ARBITRATOR' ? '公平处理每一笔争议' : '悬赏托管，安心下单'}</strong><p>{role === 'RUNNER' ? '接单后及时确认，完成后等待用户验收。' : role === 'ARBITRATOR' ? '结合订单记录核实情况，再作出资金裁决。' : '订单完成后确认，悬赏再结算给跑腿员。'}</p></div>
    <div className="sidebar-account"><span className="account-avatar">{role === 'PUBLISHER' ? '同' : role === 'RUNNER' ? '跑' : '管'}</span><div><strong>{IDENTITIES.find(i => i.userId === userId)?.name ?? '当前账户'}</strong><small>账户 {userId}</small></div><button className="icon-button" title="退出当前账户" aria-label="退出当前账户" onClick={onLogout}><Icon name="logout" size={18} /></button></div>
  </aside>;
}
