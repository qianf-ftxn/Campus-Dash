import { FormEvent, useEffect, useRef, useState } from 'react';
import { api, BizError, yuan } from '../api';
import { TYPE_TEXT } from '../workspace';
import Icon from '../components/Icon';

export default function Publish() {
  const preset = new URLSearchParams(location.hash.split('?')[1] ?? '').get('type') ?? 'DELIVERY';
  const [title, setTitle] = useState('');
  const [reward, setReward] = useState('15');
  const [type, setType] = useState(Object.keys(TYPE_TEXT).includes(preset) ? preset : 'DELIVERY');
  const [error, setError] = useState('');
  const [created, setCreated] = useState('');
  const locked = useRef(false);
  const [uncertain, setUncertain] = useState(false);
  const [busy, setBusy] = useState(false);
  const [balance, setBalance] = useState<number | null>(null);
  useEffect(() => { api.wallet().then(w => setBalance(w.availableCents)).catch(() => {}); }, []);
  const cents = Math.round(Number(reward) * 100);
  const valid = Number.isSafeInteger(cents) && cents > 0 && cents <= 10_000_000;
  const submit = async (e: FormEvent) => {
    e.preventDefault(); if (locked.current || uncertain) return; setError(''); setCreated('');
    try {
      if (!title.trim()) throw new Error('请写清楚需要帮忙的事项');
      if (!valid) throw new Error('悬赏金额须在 0.01～100000 元之间');
      if (balance !== null && cents > balance) throw new Error('可用余额不足，请调整悬赏金额');
      locked.current = true; setBusy(true);
      const r = await api.publish({ title: title.trim(), rewardCents: cents, slotTotal: 1, type });
      setCreated(r.errandId); setTitle('');
      api.wallet().then(w => setBalance(w.availableCents)).catch(() => {});
    } catch (e) {
      setError((e as Error).message);
      if (e instanceof BizError && ['NETWORK', 'TIMEOUT', 'INVALID_RESPONSE', 'HTTP_ERROR'].includes(e.code)) setUncertain(true);
    } finally { locked.current = false; setBusy(false); }
  };
  return <div><div className="page-head"><div><span className="eyebrow">A LITTLE HELP STARTS HERE</span><h1>发布一份校园托付</h1><p>写清需求，设置悬赏，交给愿意帮忙的同学。</p></div><a href="#/user/orders">查看我的订单 →</a></div>
    <div className="publish-layout"><form className="panel publish-form" onSubmit={submit}>
      <div className="form-section-title"><span>01</span><h2>选择服务</h2></div><div className="type-options">{Object.entries(TYPE_TEXT).map(([key, text], i) => <button type="button" key={key} aria-pressed={type === key} className={type === key ? 'selected' : ''} onClick={() => setType(key)}><Icon name={['box', 'bag', 'clock', 'plus'][i]} size={24}/>{text}</button>)}</div>
      <div className="form-section-title"><span>02</span><h2>描述需求</h2></div><label htmlFor="order-title">需要帮你做什么？</label><textarea id="order-title" required maxLength={64} rows={4} value={title} onChange={e => setTitle(e.target.value)} placeholder="例如：帮我从三食堂带一份饭到 6 号楼，请写清地点、物品和交接要求。"/><div className="field-note"><span>当前订单通过这段描述向跑腿员说明需求</span><span>{title.length}/64</span></div>
      <div className="form-section-title"><span>03</span><h2>设置悬赏</h2></div><label htmlFor="order-reward">跑腿悬赏（元）</label><div className="money-input"><span>¥</span><input id="order-reward" required type="number" min="0.01" max="100000" step="0.01" value={reward} onChange={e => setReward(e.target.value)}/></div><p className="field-note">{balance === null ? '发布时将检查账户余额' : `当前可用余额 ${yuan(balance)}`} · 每笔订单由一位跑腿员承接</p>
      {error && <div className="banner warn" role="alert">{error}</div>}{created && <div className="banner success" role="status">订单发布成功，悬赏已托管。<a href={`#/detail/${created}`}>查看订单进度 →</a></div>}
      {uncertain && <div className="banner warn">提交结果尚未确认，请先<a href="#/user/orders">核对我的订单</a>，避免重复发布。<button type="button" className="link-btn" onClick={() => { setUncertain(false); setTitle(''); setError(''); }}>已核对，重新填写需求</button></div>}
      <div className="form-submit"><span>本次托管 <strong>{valid ? yuan(cents) : '—'}</strong></span><button className="btn btn-primary" disabled={busy || uncertain} type="submit">{busy ? '正在发布…' : '确认发布并托管'}<Icon name="arrow" size={17}/></button></div>
    </form><aside className="publish-aside"><div className="panel"><span className="tip-icon"><Icon name="shield" size={28}/></span><h2>每一份托付，都安心一点</h2><ol><li><strong>悬赏先托管</strong><p>发布时托管悬赏，完成后再结算。</p></li><li><strong>全程可跟进</strong><p>接单、取件、送达，进度在订单中查看。</p></li><li><strong>送达后再确认</strong><p>检查完成情况后确认；有问题可发起争议。</p></li></ol></div><p className="aside-note">温馨提示：请在需求中写清取送地点和交接方式，避免发布不必要的个人隐私。</p></aside></div>
  </div>;
}
