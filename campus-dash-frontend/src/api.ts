import { defaultRole, WorkspaceRole } from './workspace';
// 后端请求封装：token 管理 + Result 统一处理。
//
// 后端响应结构是 Result<T> = { code, message, data }：
//   code === 'OK' 表示成功；其他 code（SLOT_FULL / NOT_CURRENT_GRABBER 等）
//   是业务结果而不是服务器错误——前端按 code 分支处理，绝不解析文案。

export interface ApiResult<T> {
  code: string;
  message: string;
  data: T;
}

function storedUserId(): number | null {
  try {
    const id: unknown = JSON.parse(localStorage.getItem('dash_user') || 'null');
    return typeof id === 'number' && Number.isSafeInteger(id) && id > 0 ? id : null;
  } catch { return null; }
}
let token: string | null = localStorage.getItem('dash_token');
let currentUserId: number | null = storedUserId();
if (!token || currentUserId === null) {
  token = null; currentUserId = null;
  for (const key of ['dash_token', 'dash_user', 'dash_role']) localStorage.removeItem(key);
}
export function getRole(): WorkspaceRole {
  const saved = localStorage.getItem('dash_role');
  return isRole(saved) ? saved : defaultRole(currentUserId ?? 1001);
}
function isRole(role: unknown): role is WorkspaceRole {
  return role === 'PUBLISHER' || role === 'RUNNER' || role === 'ARBITRATOR';
}
export function getToken() { return token; }
export function getUserId() { return currentUserId; }

export async function login(userId: number): Promise<string> {
  const data = await request<{ token: string; userId: string | number; role: WorkspaceRole }>(
    '/api/auth/login', 'POST', { userId });
  if (!data?.token || !isRole(data.role) || String(data.userId) !== String(userId))
    throw new BizError('INVALID_RESPONSE', '登录响应不完整，请重新登录');
  token = data.token; currentUserId = userId;
  localStorage.setItem('dash_user', JSON.stringify(userId));
  localStorage.setItem('dash_role', data.role);
  localStorage.setItem('dash_token', token);
  return token;
}

export function logout(revoke = true) {
  const previous = token;
  token = null; currentUserId = null;
  for (const key of ['dash_token', 'dash_user', 'dash_role']) localStorage.removeItem(key);
  if (revoke && previous) void fetch('/api/auth/logout', {
    method: 'POST', headers: { Authorization: `Bearer ${previous}` }, keepalive: true,
  }).catch(() => {});
}

export class BizError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

/** 发请求并返回 data；code 非 OK 时抛 BizError（带业务码） */
async function request<T>(path: string, method = 'GET', body?: unknown,
                          extraHeaders: Record<string, string> = {}): Promise<T> {
  const requestToken = token;
  const headers: Record<string, string> = {};
  if (requestToken) headers['Authorization'] = `Bearer ${requestToken}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  Object.assign(headers, extraHeaders);
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 15000);
  let resp: Response;
  let result: ApiResult<T>;
  try {
    resp = await fetch(path, { method, headers, signal: abort.signal,
      body: body === undefined ? undefined : JSON.stringify(body) });
    if (resp.status === 401) throw new BizError('UNAUTHORIZED', '会话已过期，请重新登录');
    try { result = await resp.json() as ApiResult<T>; }
    catch { throw new BizError('INVALID_RESPONSE', '服务暂时不可用，请稍后重试'); }
    if (!result || typeof result.code !== 'string') throw new BizError('INVALID_RESPONSE', '服务返回异常，请稍后重试');
    if (result.code !== 'OK') throw new BizError(result.code, result.message || '请求未完成，请重试');
    if (!resp.ok) throw new BizError('HTTP_ERROR', '服务暂时不可用，请稍后重试');
  } catch (e) {
    if (e instanceof BizError) {
      if (e.code === 'UNAUTHORIZED' && requestToken !== null && token === requestToken) {
        logout(false); window.dispatchEvent(new Event('dash:unauthorized'));
      }
      throw e;
    }
    throw new BizError(abort.signal.aborted ? 'TIMEOUT' : 'NETWORK',
      abort.signal.aborted ? '请求超时，请查询处理结果后再试' : '网络连接失败，请稍后重试');
  } finally { clearTimeout(timer); }
  return result.data;
}

// ── 任务 ──────────────────────────────────────────────
export interface ErrandCard {
  /** 后端以字符串返回：雪花 ID 超过 JS 安全整数（2^53），
   *  按 number 解析会截断精度，全程必须按 string 处理 */
  id: string;
  title: string;
  createdAt?: string | null;
  status: string;
  type: string;
  rewardCents: number;
  slotTotal: number;
  slotTaken: number;
  publisherId: string;
  grabberId: string;
  round: number;
  role: string;
  availableActions: string[];
}

export interface StatusChange {
  time: string; from: string; to: string; round: number; operatorId: string;
}

export interface RushCampaign {
  id: string;
  campusId: string;
  name: string;
  startAt: string;
  endAt: string;
  quotaTotal: number;
  soldCount: number;
  status: 'NOT_STARTED' | 'ACTIVE' | 'ENDED' | 'DISABLED';
  canSubmit: boolean;
}

export interface RushRequestResult {
  requestId: string;
  status: 'PROCESSING' | 'SUCCESS' | 'FAILED';
  errandId: string | null;
  errorCode: string;
}

export const api = {
  credit: () => request<{ score: number; windowDays: number; events: { type: string; description: string; delta: number; refId: string; time: string }[] }>('/api/credit/self'),
  ranking: () => request<{ userId: string; score: number }[]>('/api/credit/ranking?campusId=1&limit=20'),
  me: async () => {
    const me = await request<{ userId: string; role: WorkspaceRole }>('/api/auth/me');
    if (!me || !isRole(me.role) || String(me.userId) !== String(currentUserId)) throw new BizError('INVALID_RESPONSE', '身份信息异常，请退出后重新登录');
    return me;
  },
  health: () => request<{ status: string }>('/api/health'),
  list: (campusId = 1, status?: string, page = 0) =>
    request<ErrandCard[]>(`/api/errands?campusId=${campusId}&page=${page}${status ? `&status=${status}` : ''}`),
  hall: (page: number, type: string, keyword: string, sort: string) => {
    const params = new URLSearchParams({ campusId: '1', status: 'PUBLISHED', page: String(page), sort });
    if (type !== 'ALL') params.set('type', type);
    if (keyword.trim()) params.set('keyword', keyword.trim());
    return request<ErrandCard[]>(`/api/errands?${params}`);
  },
  mine: (role: 'PUBLISHED_BY_ME' | 'GRABBED_BY_ME', page = 0, sort = 'latest') =>
    request<ErrandCard[]>(`/api/errands/mine?role=${role}&page=${page}&sort=${sort}`),
  detail: (id: string) => request<ErrandCard>(`/api/errands/${id}`),
  timeline: (id: string) => request<StatusChange[]>(`/api/errands/${id}/timeline`),
  publish: (req: { title: string; rewardCents: number; slotTotal: number; type?: string }) =>
    request<{ errandId: string; status: string }>('/api/errands', 'POST', req),
  grab: (id: string) =>
    request<{ code: string; grabbed: boolean }>(`/api/errands/${id}/grab`, 'POST'),
  action: (id: string, name: 'confirm' | 'pickup' | 'deliver' | 'settle' | 'cancel' | 'dispute') =>
    request<{ result?: string } | null>(`/api/errands/${id}/${name}`, 'POST'),
  arbitrate: (id: string, favor: 'RUNNER' | 'PUBLISHER') =>
    request<{ result: string }>(`/api/errands/${id}/arbitrate`, 'POST', { favor }),
  wallet: () => request<{ availableCents: number; frozenCents: number }>('/api/wallet'),
  ledger: (page = 0) => request<{
    time: string; direction: string; amountCents: number;
    refType: string; refId: string; bizNo: string;
  }[]>(`/api/wallet/ledger?page=${page}`),
  notifications: (page = 0) => request<{
    id: string; errandId: string; type: string; content: string; time: string; read: boolean;
  }[]>(`/api/notifications?page=${page}`),
  markAllRead: () => request<{ updated: number }>('/api/notifications/read-all', 'POST'),
  unread: () => request<{ count: number }>('/api/notifications/unread'),
  rushCampaigns: (campusId = 1) =>
    request<RushCampaign[]>(`/api/rush-campaigns?campusId=${campusId}`),
  submitRush: (campaignId: string, requestId: string,
               req: { title: string; rewardCents: number }) =>
    request<RushRequestResult>(`/api/rush-campaigns/${campaignId}/orders`, 'POST', req,
      { 'X-Request-Id': requestId }),
  rushResult: (requestId: string) =>
    request<RushRequestResult>(`/api/rush-requests/${encodeURIComponent(requestId)}`),
};

/** 金额展示：分 -> 元 */
export function yuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

export const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '待接单', LOCKED: '待跑腿确认', ACCEPTED: '待取件',
  PICKED_UP: '配送中', DELIVERED: '待确认完成', SETTLED: '已结算',
  CLOSED: '已关闭', CANCELLED: '已取消', DISPUTED: '争议中', REFUNDED: '已退款',
};

export const ACTION_TEXT: Record<string, string> = {
  GRAB: '立即接单', CONFIRM: '确认接单', PICKUP: '取货', DELIVER: '送达',
  SETTLE: '确认完成', CANCEL: '取消并退款', DISPUTE: '发起争议',
  ARBITRATE: '仲裁',
};
