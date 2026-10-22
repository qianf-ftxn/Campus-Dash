import { getToken } from './api';
export interface WsEvent {
  type: 'errand.status' | 'notification.new' | 'credit.changed';
  payload: Record<string, unknown>;
}
type Handler = (event: WsEvent) => void;
const handlers = new Set<Handler>();
let socket: WebSocket | null = null;
let reconnectDelay = 1000;
let heartbeatTimer: number | undefined;
let reconnectTimer: number | undefined;
let available = false;
let enabled = false;
export function isWsAvailable() { return available; }
export function subscribeWs(handler: Handler) { handlers.add(handler); return () => { handlers.delete(handler); }; }
function reconnect() {
  if (!enabled || reconnectTimer !== undefined) return;
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = undefined; reconnectDelay = Math.min(reconnectDelay * 2, 8000); open();
  }, reconnectDelay);
}
function open() {
  if (!enabled || socket) return;
  const token = getToken();
  if (!token) return;
  let current: WebSocket;
  try {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    current = new WebSocket(`${protocol}//${location.host}/ws?token=${encodeURIComponent(token)}`);
    socket = current;
  } catch { available = false; reconnect(); return; }
  current.onopen = () => {
    if (socket !== current || !enabled) return;
    available = true; reconnectDelay = 1000;
    heartbeatTimer = window.setInterval(() => {
      if (current.readyState === WebSocket.OPEN) current.send('ping');
    }, 30000);
  };
  current.onmessage = message => {
    if (socket !== current || !enabled || message.data === 'pong') return;
    let event: WsEvent;
    try { event = JSON.parse(message.data); } catch { return; }
    if (!event || !['errand.status', 'notification.new', 'credit.changed'].includes(event.type)
        || !event.payload || typeof event.payload !== 'object') return;
    for (const handler of handlers) { try { handler(event); } catch { /* Keep independent subscribers isolated. */ } }
  };
  current.onclose = () => {
    if (socket !== current) return;
    available = false; socket = null;
    if (heartbeatTimer !== undefined) clearInterval(heartbeatTimer);
    heartbeatTimer = undefined; reconnect();
  };
  current.onerror = () => { current.close(); };
}
export function connectWs() { enabled = true; if (reconnectTimer === undefined) open(); }
export function disconnectWs() {
  enabled = false; available = false; reconnectDelay = 1000;
  if (heartbeatTimer !== undefined) clearInterval(heartbeatTimer);
  if (reconnectTimer !== undefined) clearTimeout(reconnectTimer);
  heartbeatTimer = reconnectTimer = undefined;
  if (socket) { socket.onclose = null; socket.onerror = null; socket.onmessage = null; socket.onopen = null; socket.close(); }
  socket = null;
}
