export type WorkspaceRole = 'PUBLISHER' | 'RUNNER' | 'ARBITRATOR';
export const IDENTITIES: { userId: number; name: string; role: WorkspaceRole; desc: string }[] = [
  { userId: 1001, name: '同学用户', role: 'PUBLISHER', desc: '发布需求，安心等待送达' },
  { userId: 2001, name: '跑腿员 01', role: 'RUNNER', desc: '发现订单，完成配送赚取收入' },
  { userId: 2002, name: '跑腿员 02', role: 'RUNNER', desc: '独立的跑腿账户与接单记录' },
  { userId: 9001, name: '平台管理员', role: 'ARBITRATOR', desc: '处理争议，查看订单流转' },
];
export const WORKSPACES = {
  PUBLISHER: { name: '用户中心', short: '用户端', home: '/user/home', intro: '校园琐事，交给顺路的人。', nav: [
    ['/user/home', '服务首页', 'home'], ['/user/publish', '发布订单', 'plus'], ['/user/orders', '我的订单', 'orders'],
    ['/user/activities', '限时活动', 'activity'], ['/user/wallet', '我的钱包', 'wallet'], ['/user/messages', '消息中心', 'message'],
  ] },
  RUNNER: { name: '跑腿工作台', short: '跑腿端', home: '/runner/hall', intro: '每一份托付，都值得认真送达。', nav: [
    ['/runner/hall', '接单大厅', 'home'], ['/runner/orders', '我的接单', 'orders'], ['/runner/wallet', '收入钱包', 'wallet'],
    ['/runner/credit', '服务信用', 'shield'], ['/runner/messages', '消息中心', 'message'],
  ] },
  ARBITRATOR: { name: '平台管理台', short: '平台端', home: '/admin/disputes', intro: '让每一笔订单，都有清楚的交代。', nav: [
    ['/admin/disputes', '争议处理', 'shield'], ['/admin/orders', '订单查询', 'orders'],
  ] },
};
export const TYPE_TEXT: Record<string, string> = { DELIVERY: '帮我送', BUY: '帮我买', QUEUE: '帮我排队', OTHER: '其他帮忙' };
export const CLOSED_STATUSES = new Set(['SETTLED', 'CLOSED', 'CANCELLED', 'REFUNDED']);
export function defaultRole(userId: number): WorkspaceRole {
  return IDENTITIES.find(i => i.userId === userId)?.role ?? 'RUNNER';
}
export function resolveRoute(route: string, role: WorkspaceRole): string {
  const workspace = WORKSPACES[role];
  const [pathname, query = ''] = route.split('?');
  if (/^\/detail\/[0-9]+$/.test(pathname)) return pathname;
  const legacy: Record<string, string> = {
    '/square': workspace.home, '/mine': role === 'ARBITRATOR' ? '/admin/orders' : role === 'RUNNER' ? '/runner/orders' : '/user/orders',
    '/publish': '/user/publish', '/rush': '/user/activities',
    '/wallet': role === 'RUNNER' ? '/runner/wallet' : '/user/wallet',
    '/credit': '/runner/credit', '/notifications': role === 'RUNNER' ? '/runner/messages' : '/user/messages',
  };
  const target = legacy[pathname] ?? pathname;
  if (!workspace.nav.some(([path]) => path === target)) return workspace.home;
  const type = new URLSearchParams(query).get('type');
  return target === '/user/publish' && type && Object.keys(TYPE_TEXT).includes(type) ? `${target}?type=${type}` : target;
}
