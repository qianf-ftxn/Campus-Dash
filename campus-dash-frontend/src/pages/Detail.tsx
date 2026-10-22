import { useCallback, useEffect, useRef, useState } from 'react';
import { api, yuan, STATUS_TEXT, BizError } from '../api';
import type { ErrandCard, StatusChange } from '../api';
import ActionButtons from '../components/ActionButtons';
import { subscribeWs } from '../ws';
import { WorkspaceRole, TYPE_TEXT } from '../workspace';

interface Props {
  errandId: string;
  role: WorkspaceRole;
}

const TERMINAL = new Set(['SETTLED', 'CLOSED', 'CANCELLED', 'REFUNDED']);

export default function Detail({ errandId, role }: Props) {
  const [card, setCard] = useState<ErrandCard | null>(null);
  const [timeline, setTimeline] = useState<StatusChange[]>([]);
  const [error, setError] = useState('');
  const [timelineError, setTimelineError] = useState('');
  const [notice, setNotice] = useState('');
  const [arbitrating, setArbitrating] = useState(false);
  const [favor, setFavor] = useState<'RUNNER' | 'PUBLISHER' | null>(null);
  const [decisionBusy, setDecisionBusy] = useState(false);
  const [updating, setUpdating] = useState(false);
  const active = useRef(true);
  const inFlight = useRef(false);
  const deciding = useRef(false);
  const current = useRef<ErrandCard | null>(null);
  const forbidden = useRef(false);
  const load = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true; setUpdating(true);
    try {
      const [detail, history] = await Promise.allSettled([api.detail(errandId), api.timeline(errandId)]);
      if (!active.current) return;
      if (detail.status === 'fulfilled') {
        current.current = detail.value; setCard(detail.value); setError(''); forbidden.current = false;
        if (!detail.value.availableActions.includes('ARBITRATE')) { setArbitrating(false); setFavor(null); }
      } else {
        const e = detail.reason;
        setError(e.message || '订单加载失败');
        if (e instanceof BizError && ['FORBIDDEN', 'ERRAND_NOT_FOUND', 'UNAUTHORIZED'].includes(e.code)) {
          current.current = null; setCard(null); setTimeline([]); forbidden.current = true;
          return;
        }
      }
      if (history.status === 'fulfilled') { setTimeline(history.value); setTimelineError(''); }
      else setTimelineError(history.reason.message || '进度加载失败');
    } finally { inFlight.current = false; if (active.current) setUpdating(false); }
  }, [errandId]);
  useEffect(() => {
    active.current = true; void load();
    const unsubscribe = subscribeWs(event => {
      if (event.type === 'errand.status' && String(event.payload.errandId) === errandId) void load();
    });
    const timer = setInterval(() => {
      if (!document.hidden && !forbidden.current && !TERMINAL.has(current.current?.status ?? '')) void load();
    }, 10000);
    return () => { active.current = false; unsubscribe(); clearInterval(timer); };
  }, [load, errandId]);
  const arbitrate = async (direction: 'RUNNER' | 'PUBLISHER') => {
    if (deciding.current || !current.current?.availableActions.includes('ARBITRATE')) return;
    deciding.current = true; setDecisionBusy(true); setNotice('');
    try {
      const response = await api.arbitrate(errandId, direction);
      if (!active.current) return;
      const text: Record<string, string> = {
        SETTLED_TO_RUNNER: '仲裁完成：支持跑腿，资金已结算。',
        REFUNDED_TO_PUBLISHER: '仲裁完成：支持发布用户，资金已退回。',
        ALREADY_DONE: '订单已经处理，请以最新状态与记录为准。',
      };
      if (!text[response.result]) throw new BizError('SETTLE_CONFLICT', '本次裁决未完成，请刷新核实订单状态');
      setNotice(text[response.result]); setArbitrating(false); setFavor(null);
      await load();
    } catch (e) { if (active.current) { setError((e as Error).message); void load(); } }
    finally { deciding.current = false; if (active.current) setDecisionBusy(false); }
  };

  if (!card) return <div className="empty" role={error ? 'alert' : 'status'}>{error || '加载中…'}{error && <button className="btn" disabled={updating} onClick={load}>重新加载</button>}{error && <a className="btn" href={`#${role === 'ARBITRATOR' ? '/admin/orders' : role === 'RUNNER' ? '/runner/orders' : '/user/orders'}`}>返回订单列表</a>}</div>;

  return (
    <div className="detail-page">
      <a className="back-link" href={`#${role === 'ARBITRATOR' ? '/admin/disputes' : role === 'RUNNER' ? '/runner/orders' : '/user/orders'}`}>← 返回{role === 'ARBITRATOR' ? '平台工作台' : '我的订单'}</a>
      <div className="page-head">
        <div><span className="eyebrow">ORDER DETAILS</span><h1>{card.title}</h1><p>订单 #{card.id}</p></div>
        <span className={`status status-${card.status}`}>{STATUS_TEXT[card.status]}</span>
      </div>

      {notice && <div className="banner success" role="status">{notice}</div>}
      {error && <div className="banner warn" role="alert">更新失败：{error}。当前信息可能尚未更新。<button className="link-btn" onClick={load}>重试</button></div>}

      <div className="detail-grid">
        <section className="panel">
          <h3>订单信息</h3>
          <table className="kv">
            <tbody>
              <tr><td>悬赏金额</td><td className="reward">{yuan(card.rewardCents)}（发布时已托管）</td></tr>
              <tr><td>服务类型</td><td>{TYPE_TEXT[card.type] ?? card.type}</td></tr>
              <tr><td>发布用户</td><td>#{card.publisherId}</td></tr>
              <tr><td>当前跑腿</td><td>{card.grabberId && card.grabberId !== '-1' ? `#${String(card.grabberId).slice(-6)}` : '—'}</td></tr>
              {role === 'ARBITRATOR' && <tr><td>匹配轮次</td><td>第 {card.round + 1} 轮</td></tr>}
              <tr><td>你的角色</td><td>{{ PUBLISHER: '发布用户', CURRENT_RUNNER: '履约跑腿员', ARBITRATOR: '平台管理员', VIEWER: '浏览者' }[card.role] ?? card.role}</td></tr>
            </tbody>
          </table>

          <h3>订单操作</h3>
          <ActionButtons card={{ ...card, availableActions: updating || error ? [] : card.availableActions.filter(a => a !== 'ARBITRATE') }} onChanged={load} />
          {card.availableActions.length === 0 && <p className="muted">当前无需操作，请关注订单进度。</p>}

          {role === 'ARBITRATOR' && card.availableActions.includes('ARBITRATE') && (
            <div className="arbitrate-zone">
              {!arbitrating ? (
                <button className="btn btn-dispute" disabled={updating || !!error} onClick={() => setArbitrating(true)}>选择仲裁方向</button>
              ) : (
                <div className="arbitrate-choose">
                  <p>选择裁决方向，核实后再确认提交：</p>
                  <button className="btn btn-deliver" disabled={decisionBusy} onClick={() => setFavor('RUNNER')}>支持跑腿 → 结算给跑腿</button>
                  <button className="btn btn-cancel" disabled={decisionBusy} onClick={() => setFavor('PUBLISHER')}>支持发布用户 → 全额退款</button>
                  {favor && <div className="action-confirm"><p>确定支持{favor === 'RUNNER' ? '跑腿员并按结算规则分账' : '发布用户并退回全部悬赏'}？确认后将执行资金处理。</p><button className="btn btn-primary" disabled={decisionBusy || updating || !!error} onClick={() => arbitrate(favor)}>{decisionBusy ? '正在提交…' : '确认裁决'}</button><button className="btn" disabled={decisionBusy} onClick={() => setFavor(null)}>返回核实</button></div>}
                </div>
              )}
            </div>
          )}
        </section>

        <section className="panel">
          <h3>订单进度</h3>
          {timelineError && <div className="banner warn" role="alert">订单进度加载失败：{timelineError}<button className="link-btn" onClick={load}>重试</button></div>}
          <div className="timeline">
            {!updating && !timelineError && timeline.length === 0 && <p className="muted">暂无记录</p>}
            {timeline.map((s, i) => (
              <div key={i} className="timeline-item">
                <span className="timeline-time">{new Date(s.time).toLocaleString('zh-CN')}</span>
                <span className="timeline-transit">
                  {STATUS_TEXT[s.from] ?? s.from} → <strong>{STATUS_TEXT[s.to] ?? s.to}</strong>
                </span>
                {s.round > 0 && <span className="round">第 {s.round + 1} 轮</span>}
                <span className="muted">操作者 {s.operatorId === '-1' ? '系统' : `#${String(s.operatorId).slice(-6)}`}</span>
              </div>
            ))}
          </div>
          <p className="timeline-hint">
            跑腿员接单后需及时确认；超时未确认时，系统会重新匹配。
          </p>
        </section>
      </div>
    </div>
  );
}
