import { api, ErrandCard, STATUS_TEXT, yuan } from '../api';
import { TYPE_TEXT, WorkspaceRole } from '../workspace';
import ActionButtons from './ActionButtons';
import Icon from './Icon';

const dateFormat = new Intl.DateTimeFormat('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hour12:false, timeZone:'Asia/Shanghai' });
export function OrderTime({ value }: { value?: string | null }) {
  if (!value || Number.isNaN(Date.parse(value))) return null;
  return <time dateTime={value}><Icon name="clock" size={13}/>{dateFormat.format(new Date(value))} 发布</time>;
}
function taskParts(title: string) {
  const split = title.indexOf('｜');
  return split < 0 ? { title, note: '' } : { title: title.slice(0, split), note: title.slice(split + 1) };
}
function progress(status: string, runner: boolean) {
  const text: Record<string, string> = {
    PUBLISHED: '等待跑腿员接单', LOCKED: runner ? '请及时确认接单' : '等待跑腿员确认',
    ACCEPTED: runner ? '已接单，待取件' : '跑腿员准备取件', PICKED_UP: '正在配送途中',
    DELIVERED: runner ? '等待用户验收' : '请验收并确认完成', DISPUTED: '平台正在处理争议',
    SETTLED: '任务完成，悬赏已结算', CANCELLED: '订单已结束', REFUNDED: '悬赏已退回', CLOSED: '订单已关闭',
  };
  return text[status] ?? '查看订单最新进度';
}
export default function OrderItem({ card, onChanged, layout, role }: {
  card: ErrandCard; onChanged: () => void; layout: 'row' | 'hall'; role?: WorkspaceRole;
}) {
  const task = taskParts(card.title);
  const icon = card.type === 'BUY' || /餐|饭|咖啡|水果|蛋糕/.test(task.title) ? 'bag' : card.type === 'QUEUE' ? 'clock' : 'box';
  const category = <span className={`task-category category-${card.type.toLowerCase()}`}>{TYPE_TEXT[card.type] ?? '校园帮忙'}</span>;
  if (layout === 'hall') return <article className="hall-card">
    <div className="hall-card-top"><span className={`task-icon task-icon-${icon}`}><Icon name={icon} size={23}/></span>{category}<OrderTime value={card.createdAt}/></div>
    <a className="task-title" href={`#/detail/${card.id}`} title={card.title}>{task.title}</a>
    <p className={`task-description ${task.note ? '' : 'description-empty'}`}>{task.note || '查看需求详情，与发布同学确认交接事项。'}</p>
    <div className="hall-card-footer"><div className="task-reward"><small>跑腿悬赏</small><strong>{yuan(card.rewardCents)}</strong></div><div className="hall-card-actions"><a className="order-detail-link" href={`#/detail/${card.id}`}>详情 <Icon name="arrow" size={14}/></a><ActionButtons card={card} onChanged={onChanged} compact/></div></div>
  </article>;
  return <article className={`order-row ${card.availableActions.some(a => ['CONFIRM','PICKUP','DELIVER','SETTLE'].includes(a)) ? 'needs-action' : ''}`}>
    <div className="order-task"><span className={`task-icon task-icon-${icon}`}><Icon name={icon} size={24}/></span><div className="order-task-copy"><div className="order-title-line"><a className="task-title" href={`#/detail/${card.id}`} title={card.title}>{task.title}</a>{category}</div>{task.note && <p className="task-description">{task.note}</p>}<OrderTime value={card.createdAt}/></div></div>
    <div className="order-progress"><span className={`status status-${card.status}`}>{STATUS_TEXT[card.status] ?? card.status}</span><small>{progress(card.status, role === 'RUNNER')}</small></div>
    <div className="task-reward"><small>悬赏金额</small><strong>{yuan(card.rewardCents)}</strong></div>
    <div className="order-actions"><ActionButtons card={card} onChanged={onChanged} compact/><a className="order-detail-link" href={`#/detail/${card.id}`}>订单详情 <Icon name="arrow" size={14}/></a></div>
  </article>;
}
