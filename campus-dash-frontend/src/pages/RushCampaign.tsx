import { useEffect, useRef, useState } from 'react';
import { api, BizError, getUserId, RushCampaign as Campaign, RushRequestResult, yuan } from '../api';
import { canResubmit, PendingRush, readPendingRush } from '../rushPending';
const ERROR_TEXT: Record<string, string> = { RUSH_SOLD_OUT: '活动名额已满', RUSH_DUPLICATE_USER: '你已参与过这个活动', INSUFFICIENT_BALANCE: '钱包余额不足', RUSH_NOT_STARTED: '活动尚未开始', RUSH_ENDED: '活动已经结束', RUSH_DISABLED: '活动已停用', RUSH_PROCESS_CONFLICT: '订单处理冲突' };
const DEFINITIVE_REJECTION = new Set(['INVALID_ARGUMENT', 'INVALID_REQUEST_ID', 'RUSH_PROCESS_CONFLICT', 'RUSH_NOT_STARTED', 'RUSH_ENDED', 'RUSH_DISABLED', 'RUSH_CAMPAIGN_NOT_FOUND', 'RUSH_SOLD_OUT', 'RUSH_DUPLICATE_USER', 'INSUFFICIENT_BALANCE']);

export default function RushCampaign() {
  const storageKey = `dash_rush_pending:${getUserId()}`;
  const [pending, setPending] = useState<PendingRush | null>(() => readPendingRush(localStorage.getItem(storageKey)));
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [title, setTitle] = useState('');
  const [reward, setReward] = useState('15');
  const [result, setResult] = useState<RushRequestResult | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const active = useRef(true);
  const locked = useRef(false);
  const pollController = useRef<AbortController | null>(null);
  const load = async () => {
    try { const values = await api.rushCampaigns(); if (active.current) { setCampaigns(values); setLoadError(''); } }
    catch (e) { if (active.current) setLoadError((e as Error).message); }
    finally { if (active.current) setLoading(false); }
  };
  const finish = (value: RushRequestResult) => {
    setResult(value);
    if (value.status === 'PROCESSING') return false;
    localStorage.removeItem(storageKey); setPending(null);
    setMessage(value.status === 'SUCCESS' ? '活动订单已生成，可在我的订单中跟进。' : '本次活动订单未生成，请查看失败原因。');
    void load(); return true;
  };
  const poll = async (request: PendingRush) => {
    pollController.current?.abort();
    const controller = new AbortController(); pollController.current = controller;
    locked.current = true; setBusy(true);
    try {
      for (const delay of [0, 1000, 2000, 4000, 5000, 5000, 5000]) {
        if (delay) await new Promise<void>(resolve => {
          const done = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', done); resolve(); };
          const timer = setTimeout(done, delay); controller.signal.addEventListener('abort', done, { once: true });
        });
        if (!active.current || controller.signal.aborted) return;
        try {
          const value = await api.rushResult(request.requestId);
          if (!active.current || controller.signal.aborted) return;
          if (finish(value)) return;
        } catch (e) {
          if (!active.current || controller.signal.aborted) return;
          if (e instanceof BizError && ['UNAUTHORIZED', 'FORBIDDEN', 'RUSH_REQUEST_FORBIDDEN'].includes(e.code)) { setMessage(e.message); return; }
        }
      }
      setMessage('结果尚未确定，查询凭据已保留。请继续查询；在确认结果前不会创建另一笔请求。');
    } finally { if (active.current && !controller.signal.aborted) { locked.current = false; setBusy(false); } }
  };
  useEffect(() => {
    active.current = true; void load();
    if (pending) { setMessage('正在恢复上次活动订单的查询…'); void poll(pending); }
    const timer = setInterval(() => { if (!document.hidden) void load(); }, 15000);
    return () => { active.current = false; pollController.current?.abort(); clearInterval(timer); };
  }, [storageKey]);

  const send = async (request: PendingRush, retry = false) => {
    if (locked.current || !canResubmit(request)) return;
    locked.current = true; setBusy(true); setMessage('正在提交活动订单…'); setResult(null);
    let stored = false;
    try {
      // Persist the exact payload before sending so a lost acknowledgement can be retried with the same key.
      localStorage.setItem(storageKey, JSON.stringify(request)); stored = true; setPending(request);
      const value = await api.submitRush(request.campaignId, request.requestId,
        { title: request.title, rewardCents: request.rewardCents });
      if (!active.current) return;
      if (!finish(value)) { setMessage('请求已受理，正在确认活动名额与悬赏托管结果。'); await poll(request); }
    } catch (e) {
      if (!active.current) return;
      if (!stored) { setMessage('无法保存订单查询凭据，本次未发送，请检查浏览器存储空间后重试。'); return; }
      if (e instanceof BizError && !retry && DEFINITIVE_REJECTION.has(e.code)) {
        localStorage.removeItem(storageKey); setPending(null); setMessage(e.message);
      } else setMessage(`提交结果尚未确认：${(e as Error).message}。请继续查询，或使用原请求重试。`);
    } finally { if (active.current) { locked.current = false; setBusy(false); } }
  };
  const submit = (campaign: Campaign) => {
    if (pending || locked.current) return;
    const cents = Math.round(Number(reward) * 100);
    if (!title.trim() || title.trim().length > 64) { setMessage('请填写 1～64 字的需求描述'); return; }
    if (!Number.isSafeInteger(cents) || cents <= 0 || cents > 10_000_000) { setMessage('悬赏金额须在 0.01～100000 元之间'); return; }
    void send({ requestId: `rush:${campaign.id}:${crypto.randomUUID()}`, campaignId: campaign.id, title: title.trim(), rewardCents: cents });
  };
  return <div><div className="page-head"><div><span className="eyebrow">CAMPUS ACTIVITIES</span><h1>限时活动</h1><p>选择活动，发布专属需求，跟进订单结果。</p></div><button className="btn" onClick={load}>刷新活动</button></div>
    <div className="banner info">活动名额有限。提交后请等待结果；订单生成成功后，可在“我的订单”中跟进。</div>
    <div className="panel form-panel"><label>需求描述<input maxLength={64} disabled={busy || !!pending} placeholder="写清需要帮忙的事项" value={title} onChange={e => setTitle(e.target.value)}/></label><label>悬赏金额（元）<input type="number" min="0.01" max="100000" step="0.01" disabled={busy || !!pending} value={reward} onChange={e => setReward(e.target.value)}/></label></div>
    {loadError && <div className="banner warn" role="alert">活动加载失败：{loadError}</div>}
    {loading ? <div className="empty">正在加载活动…</div> : !loadError && campaigns.length === 0 && <div className="empty">当前暂无可参与的活动</div>}
    <div className="card-grid rush-grid">{campaigns.map(c => <article className="errand-card" key={c.id}><div className="card-topline"><span className="card-title">{c.name}</span><span className={`status status-${c.status}`}>{{ NOT_STARTED: '未开始', ACTIVE: '进行中', ENDED: '已结束', DISABLED: '已停用' }[c.status]}</span></div><div className="card-meta"><span>{new Date(c.startAt).toLocaleString()} — {new Date(c.endAt).toLocaleString()}</span><span>成功订单 {c.soldCount} / {c.quotaTotal}</span><span>悬赏 {yuan(Math.round(Number(reward) * 100) || 0)}</span></div><button className="btn btn-primary" disabled={!c.canSubmit || busy || !!pending} onClick={() => submit(c)}>{pending ? '先确认上一笔请求结果' : '提交活动订单'}</button></article>)}</div>
    {message && <div className="banner info" role="status">{message}</div>}
    {pending && <section className="panel rush-result"><h3>待确认的活动请求</h3><p>请求号：{pending.requestId}</p>{pending.title && <p>原需求：{pending.title} · {yuan(pending.rewardCents!)}</p>}<div className="actions"><button className="btn btn-primary" disabled={busy} onClick={() => poll(pending)}>{busy ? '正在处理…' : '继续查询结果'}</button>{canResubmit(pending) && <button className="btn" disabled={busy} onClick={() => send(pending, true)}>使用原请求重试</button>}<a className="btn" href="#/user/orders">核对我的订单</a></div></section>}
    {result && <section className="panel rush-result"><h3>处理结果</h3><p>状态：{{ PROCESSING: '处理中', SUCCESS: '已生成订单', FAILED: '未成功' }[result.status]}</p>{result.errandId && <a href={`#/detail/${result.errandId}`}>查看订单详情 →</a>}{result.status === 'FAILED' && <p className="debit">{ERROR_TEXT[result.errorCode] || result.errorCode || '处理失败'}</p>}</section>}
  </div>;
}
