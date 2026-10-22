import { useEffect, useState } from 'react';
import { useHashRoute, navigate } from './router';
import { api, getUserId, getRole, login, logout } from './api';
import { IDENTITIES, WORKSPACES, resolveRoute, WorkspaceRole } from './workspace';
import TopBar from './components/TopBar';
import Icon from './components/Icon';
import Home from './pages/Home';
import Admin from './pages/Admin';
import Square from './pages/Square';
import Detail from './pages/Detail';
import Publish from './pages/Publish';
import Mine from './pages/Mine';
import Wallet from './pages/Wallet';
import Notifications from './pages/Notifications';
import Credit from './pages/Credit';
import RushCampaign from './pages/RushCampaign';
import { connectWs, disconnectWs } from './ws';

export default function App() {
  const rawRoute = useHashRoute();
  const [userId, setUserId] = useState<number | null>(getUserId());
  const [role, setRole] = useState<WorkspaceRole>(getRole());
  const [loginRole, setLoginRole] = useState<WorkspaceRole>('PUBLISHER');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [checkingSession, setCheckingSession] = useState(getUserId() !== null);
  const [sessionError, setSessionError] = useState('');
  const [sessionAttempt, setSessionAttempt] = useState(0);
  const route = resolveRoute(rawRoute, role);

  const enter = async (id: number) => {
    setBusy(true); setError('');
    try {
      await login(id);
      setRole(getRole()); setUserId(id);
      navigate(WORKSPACES[getRole()].home);
    } catch (e) { setError((e as Error).message || '登录失败，请稍后重试'); }
    finally { setBusy(false); }
  };
  const leave = () => { disconnectWs(); logout(); setUserId(null); setError(''); };
  useEffect(() => {
    const expired = () => { disconnectWs(); setUserId(null); setError('会话已过期，请重新进入'); };
    const syncSession = (event: StorageEvent) => { if (event.key === 'dash_token' || event.key === null) { disconnectWs(); location.reload(); } };
    window.addEventListener('dash:unauthorized', expired);
    window.addEventListener('storage', syncSession);
    return () => { window.removeEventListener('dash:unauthorized', expired); window.removeEventListener('storage', syncSession); };
  }, []);
  useEffect(() => {
    if (userId !== null) {
      let active = true;
      setCheckingSession(true); setSessionError('');
      api.me().then(me => { if (active) { localStorage.setItem('dash_role', me.role); setRole(me.role); connectWs(); } }).catch(e => { if (active) setSessionError(e.message); }).finally(() => { if (active) setCheckingSession(false); });
      return () => { active = false; disconnectWs(); };
    }
  }, [userId, sessionAttempt]);
  useEffect(() => {
    if (userId !== null && !checkingSession && !sessionError && route !== rawRoute) navigate(route);
  }, [route, rawRoute, userId, checkingSession, sessionError]);

  if (userId === null) return (
    <div className={`login-page theme-${loginRole.toLowerCase()}`}>
      <section className="login-story">
        <a className="brand" href="#/"><span className="brand-symbol"><Icon name="activity" /></span>校园顺路<span className="brand-en">CampusDash</span></a>
        <span className="eyebrow">A LITTLE HELP. A BETTER CAMPUS.</span>
        <h1>把时间留给<br />更喜欢的事<span>。</span></h1>
        <p>取一份快递，带一杯咖啡。<br />校园里的小事，总有人顺路帮你。</p>
        <div className="login-route"><span><Icon name="location" /> 一份托付</span><i /><span><Icon name="shield" /> 安心送达</span></div>
        <span className="login-footnote">连接同校的你我，让校园生活轻松一点。</span>
      </section>
      <section className="login-card">
        <span className="eyebrow">WELCOME TO CAMPUSDASH</span><h2>选择你的工作空间</h2>
        <p className="muted">每一种身份，都有清楚的入口与专属流程。</p>
        <div className="login-tabs">{(['PUBLISHER', 'RUNNER', 'ARBITRATOR'] as WorkspaceRole[]).map(r => <button key={r} className={loginRole === r ? 'active' : ''} onClick={() => setLoginRole(r)}>{WORKSPACES[r].short}</button>)}</div>
        {IDENTITIES.filter(i => i.role === loginRole).map(i => <button key={i.userId} disabled={busy} className="identity-btn" onClick={() => enter(i.userId)}><span className="identity-avatar"><Icon name={loginRole === 'PUBLISHER' ? 'bag' : loginRole === 'RUNNER' ? 'activity' : 'shield'} size={24} /></span><span><strong>{i.name}</strong><small>{i.desc}</small></span><Icon name="arrow" /></button>)}
        {error && <div className="banner warn" role="alert">{error}</div>}
        <p className="login-hint">当前为演示环境，选择账户即可进入。切换身份请先退出当前空间。</p>
      </section>
    </div>
  );

  if (checkingSession || sessionError) return <div className="session-screen"><section className="panel"><h2>{checkingSession ? '正在验证账户身份…' : '暂时无法验证账户'}</h2><p className="muted">{sessionError || '身份确认后将进入对应工作空间。'}</p>{sessionError && <button className="btn btn-primary" onClick={() => setSessionAttempt(n => n + 1)}>重新验证</button>}<button className="btn" onClick={leave}>退出账户</button></section></div>;

  let page;
  if (route.startsWith('/detail/')) page = <Detail errandId={route.split('/')[2]} role={role} />;
  else if (route === '/user/home') page = <Home />;
  else if (route.split('?')[0] === '/user/publish') page = <Publish />;
  else if (route.endsWith('/orders') && role !== 'ARBITRATOR') page = <Mine role={role} />;
  else if (route.endsWith('/wallet')) page = <Wallet role={role} />;
  else if (route.endsWith('/messages')) page = <Notifications />;
  else if (route === '/runner/credit') page = <Credit />;
  else if (route === '/user/activities') page = <RushCampaign />;
  else if (role === 'ARBITRATOR') page = <Admin disputesOnly={route === '/admin/disputes'} />;
  else page = <Square />;

  return <div className={`app theme-${role.toLowerCase()}`}><TopBar route={route} userId={userId} role={role} onLogout={leave} /><div className="workspace"><header className="workspace-header"><div><span className="workspace-tag">{WORKSPACES[role].short}</span><span className="header-subtitle">{WORKSPACES[role].intro}</span></div><span className="campus-location"><Icon name="location" size={16} /> 默认校区</span></header><main className="main" key={`${userId}:${route}`}>{page}</main><footer className="workspace-footer">CampusDash 校园顺路 <span>小事有人帮，生活更从容。</span></footer></div></div>;
}
