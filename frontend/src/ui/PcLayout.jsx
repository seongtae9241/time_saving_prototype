/* 담당자(ORG) PC 틀을 App 에서 한 번만 그린다 — 계약서 7.23.
   화면을 옮겨도 왼쪽 메뉴·위 막대는 그대로 있고 본문만 바뀐다(1차에서는 화면마다 틀을 다시 그렸다).
   폭 900px 이하이거나 담당자가 아니면 아무것도 그리지 않고(display:contents) 화면을 그대로 내보낸다. */
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './ctx.jsx';
import { Ic, Loading, useLoad } from './bits.jsx';
import { api, post } from '../api.js';
import { ago } from '../lib/format.js';
import { PcHeadCtx, PcNav, PcTop, PIc, usePc } from './PcFrame.jsx';
import { useDemoSheet } from './Frame.jsx';
import { NT_IC, goLink } from '../screens/common.jsx';

const PUBLIC = /^\/($|login|signup|pw-reset|org-apply|manager-apply)/;

/** 알림 드롭다운(5판 O-HOME-02, 420px): 종을 누르면 위 막대 아래에 뜬다. 자료·동작은 알림 화면(/notif)과 같다. */
function NotiPop({ onClose }) {
  const nav = useNavigate();
  const { role, refreshUnread } = useAuth();
  const [list, reload] = useLoad(() => api('/notifications', { quiet: true }), []);
  const unread = (n) => !n.isRead && !n.read;
  return (
    <div className="npop" role="dialog" aria-label="알림">
      <div className="prw" style={{ padding: '4px 0 8px' }}>
        <b style={{ flex: 1, fontSize: 16 }}>알림</b>
        <button type="button" className="lk" onClick={async () => { try { await post('/notifications/read-all'); } catch { /* 토스트 */ } reload(); refreshUnread(); }}>모두 읽음</button>
      </div>
      <div className="npop-l">
        {list === undefined ? <Loading /> : !list || !list.length ? <div className="pempty">새 알림이 없어요</div> : list.slice(0, 30).map((n) => {
          const [i] = NT_IC(n.type);
          return (
            <button key={n.id} type="button" className="nli" onClick={async () => {
              if (unread(n)) { try { await post(`/notifications/${n.id}/read`, {}, { quiet: true }); refreshUnread(); } catch { /* 무시 */ } }
              if (n.link) { onClose(); goLink(nav, n.link, role); } else reload();
            }}>
              <span className="nli-i"><Ic n={i} s={18} /></span>
              <span className="nli-t"><b style={{ fontWeight: unread(n) ? 700 : 500 }}>{n.title}</b>{n.body && <small>{n.body}</small>}<small>{ago(n.createdAt)}</small></span>
              {unread(n) && <span className="dotr" aria-label="안 읽음" />}
            </button>
          );
        })}
      </div>
      <button type="button" className="pmore" style={{ width: '100%', textAlign: 'center' }} onClick={() => { onClose(); nav('/notif'); }}>알림 화면에서 전체 보기 ›</button>
    </div>
  );
}

function Bell() {
  const { unread } = useAuth();
  const [open, setOpen] = useState(false);
  const loc = useLocation();
  const box = useRef(null);
  useEffect(() => { setOpen(false); }, [loc.key]);
  useEffect(() => {
    if (!open) return undefined;
    const out = (e) => { if (box.current && !box.current.contains(e.target)) setOpen(false); };
    const k = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', out);
    document.addEventListener('keydown', k);
    return () => { document.removeEventListener('mousedown', out); document.removeEventListener('keydown', k); };
  }, [open]);
  return (
    <div className="bellbox" ref={box}>
      <button type="button" className="ic" aria-haspopup="dialog" aria-expanded={open} aria-label={unread ? `알림 ${unread}건` : '알림'} onClick={() => setOpen(!open)}><PIc k="bell" />{unread ? <span className="dot" /> : null}</button>
      {open && <NotiPop onClose={() => setOpen(false)} />}
    </div>
  );
}

function NavPart() { const demo = useDemoSheet(); useLayoutEffect(() => { document.body.classList.add('pcw-on'); return () => document.body.classList.remove('pcw-on'); }, []); return <PcNav onDemo={demo} />; }

export function PcLayout({ children }) {
  const wide = usePc();
  const loc = useLocation();
  const on = wide && !PUBLIC.test(loc.pathname);
  const [head, setHead] = useState({ title: '', canBack: false });
  // 꺼져 있을 때도 같은 자리에 같은 종류의 요소를 둬서, 창 폭이 바뀌어도 화면 상태가 날아가지 않게 한다
  return (
    <div className={on ? 'pcw' : 'pcw-off'}>
      {on ? <NavPart /> : null}
      <div className={on ? 'pcw-main' : 'pcw-off'}>
        {on ? <PcTop head={head} bell={<Bell />} /> : null}
        <PcHeadCtx.Provider value={on ? setHead : null}>{children}</PcHeadCtx.Provider>
      </div>
    </div>
  );
}
