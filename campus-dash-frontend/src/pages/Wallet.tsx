import { useCallback, useEffect, useState } from 'react';
import { api, yuan } from '../api';
import { WorkspaceRole } from '../workspace';
import { usePagedResource } from '../hooks/usePagedResource';
import { subscribeWs } from '../ws';
const REF_TYPE_TEXT: Record<string, string> = { ESCROW: '订单托管', SETTLE: '结算', REFUND: '退款', RECHARGE: '充值', WITHDRAW: '提现' };
const ledgerKey = (entry: Awaited<ReturnType<typeof api.ledger>>[number]) => `${entry.bizNo}:${entry.direction}`;
export default function Wallet({ role }: { role: WorkspaceRole }) {
  const [balanceError, setBalanceError] = useState('');
  const [balance, setBalance] = useState<Awaited<ReturnType<typeof api.wallet>> | null>(null);
  const [balanceAttempt, setBalanceAttempt] = useState(0);
  const loader = useCallback((page: number) => api.ledger(page), []);
  const { items: ledger, error, loading, more, refresh, loadMore } = usePagedResource(loader, ledgerKey);
  useEffect(() => {
    let active = true;
    const load = () => api.wallet().then(value => { if (active) { setBalance(value); setBalanceError(''); } }).catch(e => { if (active) setBalanceError(e.message); });
    void load(); const unsub = subscribeWs(load);
    const timer = setInterval(() => { if (!document.hidden) void load(); }, 15000);
    return () => { active = false; unsub(); clearInterval(timer); };
  }, [balanceAttempt]);
  return <div><div className="page-head"><div><span className="eyebrow">MY WALLET</span><h1>{role === 'RUNNER' ? '收入钱包' : '我的钱包'}</h1><p>{role === 'RUNNER' ? '查看本人账户余额与接单结算记录。' : '查看可用余额、订单托管支出与退款记录。'}</p></div><button className="btn" disabled={loading} onClick={() => { setBalanceAttempt(n => n + 1); void refresh(); }}>刷新钱包</button></div>
    {balanceError && <div className="banner warn" role="alert">余额加载失败：{balanceError}</div>}
    {!balance && !balanceError && <div className="empty">正在加载余额…</div>}
    {balance && <div className="balance-panel"><div><span className="muted">可用余额</span><strong>{yuan(balance.availableCents)}</strong></div><div><span className="muted">账户冻结余额</span><strong>{yuan(balance.frozenCents)}</strong></div></div>}
    <div className="section-head"><div><h2>资金明细</h2><p>仅展示本人钱包的收支；订单托管支出与账户冻结余额分别记账。</p></div></div>
    {error && <div className="banner warn" role="alert">流水加载失败：{error}<button className="link-btn" onClick={refresh}>重试</button></div>}
    <div className="table-scroll"><table className="ledger-table"><thead><tr><th>时间</th><th>方向</th><th>金额</th><th>类型</th><th>关联订单</th></tr></thead><tbody>{ledger.map(l => <tr key={ledgerKey(l)}><td>{new Date(l.time).toLocaleString()}</td><td className={l.direction === 'CREDIT' ? 'credit' : 'debit'}>{l.direction === 'CREDIT' ? '收入' : '支出'}</td><td className={l.direction === 'CREDIT' ? 'credit' : 'debit'}>{l.direction === 'CREDIT' ? '+' : '-'}{yuan(l.amountCents)}</td><td>{REF_TYPE_TEXT[l.refType] ?? l.refType}</td><td>{['ESCROW', 'SETTLE', 'REFUND'].includes(l.refType) ? <a href={`#/detail/${l.refId}`}>#{String(l.refId).slice(-8)}</a> : '—'}</td></tr>)}{ledger.length === 0 && <tr><td colSpan={5} className="table-empty">{loading ? '正在加载流水…' : error ? '流水暂时不可用' : '暂无流水'}</td></tr>}</tbody></table></div>
    {more && <div className="load-more"><button className="btn" disabled={loading} onClick={loadMore}>加载更多流水</button></div>}
  </div>;
}
