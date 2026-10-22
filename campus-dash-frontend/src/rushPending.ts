export interface PendingRush {
  requestId: string;
  campaignId?: string;
  title?: string;
  rewardCents?: number;
}
const requestIdPattern = /^[A-Za-z0-9_:-]{8,64}$/;
export function readPendingRush(raw: string | null): PendingRush | null {
  if (!raw) return null;
  try {
    const item = JSON.parse(raw) as PendingRush;
    if (!item || typeof item.requestId !== 'string' || !requestIdPattern.test(item.requestId)) return null;
    if (typeof item.campaignId === 'string' && /^\d+$/.test(item.campaignId) && typeof item.title === 'string'
        && item.title.trim().length > 0 && item.title.length <= 64 && Number.isSafeInteger(item.rewardCents)
        && item.rewardCents! > 0 && item.rewardCents! <= 10_000_000) return item;
    return { requestId: item.requestId };
  } catch { return requestIdPattern.test(raw) ? { requestId: raw } : null; }
}
export function canResubmit(pending: PendingRush): pending is Required<PendingRush> {
  return Boolean(pending.campaignId && pending.title && pending.rewardCents);
}
