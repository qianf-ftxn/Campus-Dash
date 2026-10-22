import { useEffect, useRef, useState } from 'react';
import { api, ACTION_TEXT, BizError, getRole } from '../api';
import type { ErrandCard } from '../api';

interface Props {
  card: ErrandCard;
  onChanged: () => void;
  compact?: boolean;
}

/**
 * 操作按钮完全由后端 availableActions 驱动。
 * 前端没有第二份状态机——这里没有任何 "status === 'xxx' && isPublisher" 的判断，
 * 按钮存在与否由 ErrandActionResolver（后端）决定，两边不可能漂移。
 */
export default function ActionButtons({ card, onChanged, compact }: Props) {
  const locked = useRef(false);
  const capabilities: Record<string, string[]> = { PUBLISHER: ['SETTLE', 'CANCEL', 'DISPUTE'], RUNNER: ['GRAB', 'CONFIRM', 'PICKUP', 'DELIVER', 'DISPUTE'], ARBITRATOR: ['ARBITRATE'] };
  const actions = card.availableActions.filter(a => capabilities[getRole()].includes(a));
  const actionKey = actions.join(',');
  const [pending, setPending] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  useEffect(() => { setPending(null); }, [actionKey]);

  const run = async (action: string) => {
    if (locked.current || !actions.includes(action)) return;
    locked.current = true;
    setPending(null);
    setBusy(action);
    setMessage(null);
    try {
      if (action === 'GRAB') {
        await api.grab(card.id);
        setMessage('接单成功，请到我的接单中及时确认。');
      } else if (action === 'ARBITRATE') {
        // 仲裁在详情页展开方向选择，卡片上不做
        setMessage('请进入详情页选择仲裁方向');
        location.hash = `#/detail/${card.id}`;
        return;
      } else {
        const result = await api.action(card.id, action.toLowerCase() as never);
        if (result?.result === 'CONFLICT') throw new BizError('STALE_VERSION', '订单已变化，请刷新后重试');
        setMessage(result?.result?.startsWith('ALREADY_') ? '这笔订单已处理，请以最新进度为准。' : `${ACTION_TEXT[action]}成功`);
      }
      await onChanged();
    } catch (e) {
      if (e instanceof BizError) {
        setMessage(e.message);
        onChanged();
      } else {
        setMessage('网络连接失败，请稍后重试');
      }
    } finally {
      locked.current = false; setBusy(null);
    }
  };

  if (actions.length === 0 && !message) return null;

  return (
    <div className={compact ? 'actions compact' : 'actions'}>
      {actions.map((a) => (
        <button key={a} disabled={busy !== null} onClick={() => ['CANCEL', 'SETTLE', 'DISPUTE'].includes(a) ? setPending(a) : run(a)}
                className={`btn btn-${a.toLowerCase()} ${busy === a ? 'busy' : ''}`}>
          {ACTION_TEXT[a] ?? a}
        </button>
      ))}
      {pending && <div className="action-confirm" role="group" aria-label="确认订单操作"><p>{pending === 'CANCEL' ? '取消后订单将关闭，托管悬赏按订单结果退回。确认取消？' : pending === 'SETTLE' ? '请确认需求已经完成；确认后悬赏将结算给跑腿员。' : '发起争议后，订单将交由平台处理。确认发起？'}</p><button className="btn btn-primary" disabled={busy !== null} onClick={() => run(pending)}>{pending === 'SETTLE' ? '确认完成并结算' : pending === 'CANCEL' ? '确认取消并退款' : '确认发起争议'}</button><button className="btn" onClick={() => setPending(null)}>返回</button></div>}
      {message && <span className="action-msg" role="status">{message}</span>}
    </div>
  );
}
