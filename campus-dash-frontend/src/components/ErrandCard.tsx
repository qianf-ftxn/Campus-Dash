import { ErrandCard as Card, STATUS_TEXT, yuan } from '../api';
import { TYPE_TEXT } from '../workspace';
import ActionButtons from './ActionButtons';
import Icon from './Icon';

export default function ErrandCardView({ card, onChanged }: { card: Card; onChanged: () => void }) {
  return <article className="errand-card">
    <div className="card-topline"><span className="card-category"><Icon name={card.type === 'BUY' ? 'bag' : 'box'} size={16}/>{TYPE_TEXT[card.type] ?? card.type}</span><span className={`status status-${card.status}`}>{STATUS_TEXT[card.status] ?? card.status}</span></div>
    <a href={`#/detail/${card.id}`} className="card-title">{card.title}</a>
    <div className="card-meta"><span>订单 #{String(card.id).slice(-8)}</span></div>
    <div className="card-bottom"><span className="reward"><small>悬赏</small>{yuan(card.rewardCents)}</span><a className="card-detail" href={`#/detail/${card.id}`}>查看详情 <Icon name="arrow" size={15}/></a></div>
    <ActionButtons card={card} onChanged={onChanged} compact />
  </article>;
}
