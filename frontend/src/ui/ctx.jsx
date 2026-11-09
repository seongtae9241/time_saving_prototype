import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { api, post, put, setToken, getToken, setErrorHandler, setUnauthorizedHandler } from '../api.js';

/* ---------- 토스트 · 바텀시트 · 확인 시트 (프로토타입 toast / sheet / confirmSheet) ---------- */
const UiCtx = createContext(null);
export const useUi = () => useContext(UiCtx);

export function UiProvider({ children }) {
  const [msg, setMsg] = useState('');
  const [on, setOn] = useState(false);
  const tRef = useRef(null);
  const [sheet, setSheet] = useState(null);
  const lastFocus = useRef(null);
  const sheetRef = useRef(null);

  const toast = useCallback((m) => {
    setMsg(m); setOn(true);
    clearTimeout(tRef.current);
    tRef.current = setTimeout(() => setOn(false), 2300);
  }, []);
  const openSheet = useCallback((node) => {
    if (!lastFocus.current) lastFocus.current = document.activeElement;
    setSheet(() => node);
  }, []);
  const closeSheet = useCallback(() => {
    setSheet(null);
    const f = lastFocus.current; lastFocus.current = null;
    if (f && document.contains(f)) f.focus({ preventScroll: true });
  }, []);
  const confirm = useCallback((title, body, yes = '확인', warn = false) => new Promise((resolve) => {
    openSheet(
      <>
        <h2>{title}</h2>
        <p className="muted">{body}</p>
        <div className="btns" style={{ marginTop: 16 }}>
          <button className="btn ghost" onClick={() => { closeSheet(); resolve(false); }}>취소</button>
          <button className={'btn ' + (warn ? 'warn' : '')} onClick={() => { closeSheet(); resolve(true); }}>{yes}</button>
        </div>
      </>,
    );
  }), [openSheet, closeSheet]);

  useEffect(() => { setErrorHandler(toast); }, [toast]);
  useEffect(() => {
    if (sheet && sheetRef.current) sheetRef.current.focus({ preventScroll: true });
    const k = (e) => { if (e.key === 'Escape' && sheet) { e.preventDefault(); closeSheet(); } };
    document.addEventListener('keydown', k);
    return () => document.removeEventListener('keydown', k);
  }, [sheet, closeSheet]);

  return (
    <UiCtx.Provider value={{ toast, sheet: openSheet, closeSheet, confirm }}>
      {children}
      <div className={'ov' + (sheet ? ' on' : '')} onClick={(e) => { if (e.target === e.currentTarget) closeSheet(); }}>
        {sheet && (
          <div className="sheet" role="dialog" aria-modal="true" tabIndex={-1} ref={sheetRef}>
            <div className="grab" />
            {typeof sheet === 'function' ? sheet() : sheet}
          </div>
        )}
      </div>
      <div className={'toast' + (on ? ' on' : '')} role="status" aria-live="polite">{msg}</div>
    </UiCtx.Provider>
  );
}

/* ---------- 로그인 사용자 · 역할 ---------- */
const AuthCtx = createContext(null);
export const useAuth = () => useContext(AuthCtx);
export const ROLE_KEY = { USER: 'user', ORG: 'org', ADMIN: 'admin' };
export const HOME_OF = { USER: '/u/home', ORG: '/o/home', ADMIN: '/a/dash' };

export function AuthProvider({ children }) {
  const [me, setMe] = useState(null);
  const [ready, setReady] = useState(false);
  const [unread, setUnread] = useState(0);

  const refresh = useCallback(async () => {
    if (!getToken()) { setMe(null); setReady(true); return null; }
    try {
      const m = await api('/me', { quiet: true });
      setMe(m); return m;
    } catch (e) {
      if (e.status === 401) setMe(null);
      return null;
    } finally { setReady(true); }
  }, []);
  const refreshUnread = useCallback(async () => {
    if (!getToken()) return;
    try {
      const r = await api('/notifications/unread-count', { quiet: true });
      setUnread(typeof r === 'number' ? r : (r && (r.count ?? r.unread)) || 0);
    } catch { /* 무시 */ }
  }, []);
  const login = useCallback(async (loginId, password, keep = true) => {
    const r = await post('/auth/login', { loginId, password, keep });
    setToken(r.token);
    setMe(r.user);
    await refresh();
    refreshUnread();
    return r.user;
  }, [refresh, refreshUnread]);
  const loginWithToken = useCallback(async (token) => { setToken(token); const m = await refresh(); refreshUnread(); return m; }, [refresh, refreshUnread]);
  const logout = useCallback(async () => {
    try { await post('/auth/logout', {}, { quiet: true }); } catch { /* 무시 */ }
    setToken(null); setMe(null); setUnread(0);
  }, []);

  useEffect(() => { refresh(); }, [refresh]);
  useEffect(() => { setUnauthorizedHandler(() => setMe(null)); }, []);
  useEffect(() => {
    if (!me) return undefined;
    refreshUnread();
    const t = setInterval(refreshUnread, 30000);
    return () => clearInterval(t);
  }, [me, refreshUnread]);

  return <AuthCtx.Provider value={{ me, ready, role: me && me.role, refresh, login, loginWithToken, logout, unread, refreshUnread }}>{children}</AuthCtx.Provider>;
}

/* ---------- 큰 글씨 (프로토타입 textSizeRow) ---------- */
export function getBig() { try { return localStorage.getItem('tb-big') === '1'; } catch { return false; } }
export function applyBig(v) {
  document.documentElement.classList.toggle('big', v);
  try { localStorage.setItem('tb-big', v ? '1' : '0'); } catch { /* 무시 */ }
}
export async function saveBigToServer(v) {
  try { await put('/me', { textSize: v ? 'BIG' : 'NORMAL' }, { quiet: true }); } catch { /* 서버 저장 실패해도 화면은 바뀜 */ }
}
