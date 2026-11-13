/* 담당자(ORG) PC 틀 — 프로토타입 5판(2026-11-13). 계약서 7.22.
   폭 901px 이상에서 역할이 ORG 일 때만 쓴다. 그보다 좁으면 Frame.jsx 의 모바일 틀이 그대로 나온다. */
import { useEffect, useLayoutEffect, useState, useSyncExternalStore } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './ctx.jsx';
import { api } from '../api.js';
import '../styles/pc.css';

/* ---------- 선 아이콘 (5판 ICO: 24×24, 굵기 2, 둥근 끝) ---------- */
const LI = (d) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;
export const ICO = {
  logo: LI('<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2M9 2h6"/>'),
  home: LI('<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"/>'),
  snow: LI('<path d="M12 2v20M4.9 7l14.2 10M4.9 17L19.1 7M9 4l3 2 3-2M9 20l3-2 3 2"/>'),
  users: LI('<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.8-3.6 3.3-5.5 6.5-5.5s5.7 1.9 6.5 5.5"/><circle cx="17" cy="9" r="2.7"/><path d="M17 14.5c2.4.2 4 1.8 4.5 4.5"/>'),
  cal: LI('<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M3 10h18M8 3v4M16 3v4"/>'),
  list: LI('<rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 8h8M8 12h8M8 16h5"/>'),
  building: LI('<path d="M3 21h18M5 21V10l7-5 7 5v11M9 21v-6h6v6"/>'),
  mega: LI('<path d="M4 10v4a1 1 0 0 0 1 1h3l6 4V5L8 9H5a1 1 0 0 0-1 1zM18 9a4 4 0 0 1 0 6"/>'),
  ticket: LI('<path d="M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z"/><path d="M14 5v12" stroke-dasharray="2 2"/>'),
  trend: LI('<path d="M3 17l6-6 4 4 8-8M15 7h6v6"/>'),
  out: LI('<path d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11"/>'),
  bell: LI('<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15zM10 20a2 2 0 0 0 4 0"/>'),
  user: LI('<circle cx="12" cy="8" r="4"/><path d="M4 21c1-4.2 4-6.5 8-6.5s7 2.3 8 6.5"/>'),
  memo: LI('<path d="M4 20h4L19 9l-4-4L4 16zM13 7l4 4"/>'),
  hand: LI('<path d="M8 13V5.5a1.5 1.5 0 0 1 3 0V11M11 10V4.5a1.5 1.5 0 0 1 3 0V11M14 10.5V6a1.5 1.5 0 0 1 3 0v7c0 4-2.5 7-6 7s-5-2-6.5-5L3 11.5a1.5 1.5 0 0 1 2.5-1.5L8 13"/>'),
  search: LI('<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>'),
  heart: LI('<path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"/>'),
  clock: LI('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'),
  warn: LI('<path d="M12 3l10 18H2z"/><path d="M12 10v4M12 17.5v.5"/>'),
  back: LI('<path d="M15 5l-7 7 7 7"/>'),
  plus: LI('<path d="M12 5v14M5 12h14"/>'),
  mic: LI('<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>'),
  swap: LI('<path d="M4 8h14l-3-3M20 16H6l3 3"/>'),
};
export const PIc = ({ k }) => <span style={{ display: 'contents' }} dangerouslySetInnerHTML={{ __html: ICO[k] || ICO.list }} />;

/* ---------- 카테고리 색(5판 DC: 진한 / 중간 / 연한) · 상태 배지(stTag) ---------- */
export const DC5 = {
  '동행': ['#6F9CF0', '#CFE0FC', '#EDF3FE'], '장보기·심부름': ['#F2996B', '#FFD9C4', '#FFF1E9'], '말동무·정서': ['#E98AA5', '#FBD3DE', '#FFF0F4'],
  '디지털 도움': ['#5AA9C9', '#C6E7F2', '#EAF6FA'], '생활 정리': ['#E0A93B', '#FCE6B5', '#FFF7E3'], '가벼운 집안일': ['#5AA9C9', '#C6E7F2', '#EAF6FA'],
};
const DC_ETC = ['#2F9C7F', '#CFEBDF', '#F2FAF6'];
export const dc5 = (cat) => DC5[cat] || DC_ETC;
/** 5판 상태 배지 색: 요청 · 수락 · 확정/진행 중 · 일지 확인 · 완료 */
export const STC = { req: ['#E8F0FE', '#3A6FD8'], acc: ['#FFF4DC', '#A6760C'], go: ['#E2F3EC', '#2F8069'], log: ['#FDECE6', '#C25E48'], end: ['#F1F4F8', '#6B7585'] };
/** 실제 요청 상태값 → [5판 배지 색, 글자] */
export const ST5 = {
  SENT: ['req', '요청'], VOL_ACCEPTED: ['acc', '수락'], CONFIRMED: ['go', '확정'], IN_PROGRESS: ['go', '진행 중'], CHECKED_OUT: ['go', '일지 대기'],
  LOG_SUBMITTED: ['log', '일지 확인'], DONE: ['end', '완료'], REJECTED: ['end', '거절됨'], DECLINED: ['end', '거부됨'], EXPIRED: ['end', '만료'], CANCELLED: ['end', '취소'], NOSHOW: ['log', '노쇼'],
};
export function StTag5({ st, kind }) {
  const [k, t] = ST5[st] || ['end', st];
  const [bg, fg] = STC[k];
  return <span className="stag" style={{ background: bg, color: fg }}>{st === 'SENT' && kind === 'PROPOSAL' ? '새 제안' : t}</span>;
}
export function Avc({ n, cat, size }) {
  const [a, b, bg] = cat === 'acc' ? ['#2F8069', '#CFEBDF', '#E2F3EC'] : dc5(cat);
  return <span className="avc" style={{ background: bg, color: a, boxShadow: `inset 0 0 0 1.5px ${b}`, ...(size ? { width: size, height: size, fontSize: Math.round(size * 0.42) } : null) }} aria-hidden="true">{(n || '?')[0]}</span>;
}
export function CPill({ cat, children }) {
  const [a, b, bg] = dc5(cat);
  return <span className="cpill" style={{ background: bg, color: a, borderColor: b }}>{children || cat}</span>;
}

/* ---------- PC 틀을 쓸지: 역할 ORG + 폭 901px 이상 ---------- */
const MQ = '(min-width:901px)';
const subMq = (cb) => { const m = window.matchMedia(MQ); m.addEventListener('change', cb); return () => m.removeEventListener('change', cb); };
export function useWide() { return useSyncExternalStore(subMq, () => window.matchMedia(MQ).matches, () => false); }
export function usePc() {
  const { me } = useAuth();
  const wide = useWide();
  return !!(me && me.role === 'ORG' && wide);
}

/* ---------- 메뉴 (5판 ORGNAV 순서. 권한 위임은 기능이 없어 넣지 않음) ---------- */
export const ORG_MENU = [
  ['돌봄', [
    ['/o/home', '홈', 'home'],
    ['/o/weather', '특보 알림', 'snow'],
    ['/o/elders', '관리 대상', 'users'],
    ['/o/schedule', '돌봄 편성표', 'cal'],
    ['/o/reqs', '요청 관리', 'list'],
    ['/o/public', '공공 서비스 연계', 'building'],
  ]],
  ['기관 공고', [
    ['/board?tab=group&kind=mine', '단체 봉사 공고', 'mega'],
    ['/o/events', '기관 행사', 'ticket'],
  ]],
  ['관리', [
    ['/o/report', '리포트', 'trend'],
    ['/recurring', '정기 요청', 'clock'],
    ['/o/my', '내 정보·설정', 'user'],
  ]],
];
/** 지금 주소가 어느 메뉴에 속하는지(메뉴에 없는 하위 화면은 가까운 메뉴에 불을 켠다) */
function activeMenu(loc) {
  const p = loc.pathname, q = loc.search;
  if (p.startsWith('/o/home')) return '/o/home';
  if (p.startsWith('/o/weather')) return '/o/weather';
  if (p.startsWith('/o/elders')) return '/o/elders';
  if (p.startsWith('/o/schedule')) return '/o/schedule';
  if (p.startsWith('/o/public')) return '/o/public';
  if (p.startsWith('/o/events') || p.startsWith('/news')) return '/o/events';
  if (p.startsWith('/o/report')) return '/o/report';
  if (p.startsWith('/recurring')) return '/recurring';
  if (p.startsWith('/group') || (p === '/board' && /tab=group/.test(q))) return '/board?tab=group&kind=mine';
  if (p.startsWith('/o/reqs') || p.startsWith('/req') || p.startsWith('/o/memo-posts') || p.startsWith('/write') || p.startsWith('/regular') || p.startsWith('/voice') || p === '/board' || p.startsWith('/post')) return '/o/reqs';
  if (p.startsWith('/o/my') || p.startsWith('/my') || p.startsWith('/qna') || p.startsWith('/notice') || p === '/public') return '/o/my';
  return '';
}

/* 프로필 숫자 3줄(관리 대상 · 처리할 일 · 이번 주 돌봄): 화면을 옮길 때마다 틀이 다시 그려지므로 한 번 받은 값을 1분 동안 나눠 쓴다 */
let statCache = null, statAt = 0, statUser = null;
function loadStats(userId) {
  if (!statCache || statUser !== userId || Date.now() - statAt > 60000) {
    statAt = Date.now(); statUser = userId;
    statCache = Promise.all([api('/org/home', { quiet: true }).catch(() => null), api('/org/schedule', { quiet: true }).catch(() => null)])
      .then(([h, s]) => ({ elders: h && h.counts ? h.counts.elders : null, todo: h && h.todo ? h.todo.length : null, week: s && s.counts ? s.counts.fixed : null }));
  }
  return statCache;
}
let lastStats = null;
function useStats(userId) {
  const [v, setV] = useState(lastStats && lastStats.u === userId ? lastStats.v : null);
  useEffect(() => { let on = true; loadStats(userId).then((x) => { lastStats = { u: userId, v: x }; if (on) setV(x); }); return () => { on = false; }; }, [userId]);
  return v;
}

const DOW = ['일', '월', '화', '수', '목', '금', '토'];
const todayText = () => { const d = new Date(); const z = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}.${z(d.getMonth() + 1)}.${z(d.getDate())} (${DOW[d.getDay()]})`; };

/**
 * 담당자 PC 틀: 왼쪽 메뉴(로고·프로필 카드·숫자 3줄·그룹 메뉴·로그아웃) + 위 막대(제목·날짜·알림 종·프로필) + 본문.
 * wide=true 인 화면(홈·요청 보드·편성표)은 본문을 가득 쓰고, 나머지는 모바일 본문을 가운데 카드에 담는다.
 */
export function PcShell({ title, wide, cls = '', canBack, bodyRef, onDemo, children }) {
  const { me, unread, logout } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const on = activeMenu(loc);
  const stats = useStats(me && me.id);
  useLayoutEffect(() => { document.body.classList.add('pcw-on'); return () => document.body.classList.remove('pcw-on'); }, []);
  const org = (me && (me.orgName || (me.org && me.org.name))) || '기관';
  const n = (x, u) => (x == null ? '-' : `${x}${u}`);
  const isRoot = ORG_MENU.some(([, items]) => items.some(([k]) => k.split('?')[0] === loc.pathname));
  return (
    <div className="pcw">
      <aside className="pcw-nav">
        <div className="pfl">
          <div className="pf-top"><span className="pf-lg"><PIc k="logo" /></span><b>시간 적금</b></div>
          <button type="button" className="pf-card" onClick={() => nav('/o/my')} aria-label="내 정보·설정">
            <div className="pf-av" aria-hidden="true">{me ? me.name[0] : ''}</div>
            <div style={{ minWidth: 0 }}><div className="pf-o">{org}</div><div className="pf-n">{me && me.name} <small>담당자</small></div></div>
          </button>
          <div className="pf-rows">
            <button type="button" onClick={() => nav('/o/elders')}><span>관리 대상</span><b>{n(stats && stats.elders, '명')}</b></button>
            <button type="button" onClick={() => nav('/o/reqs')}><span>처리할 일</span><b>{n(stats && stats.todo, '건')}</b></button>
            <button type="button" onClick={() => nav('/o/schedule')}><span>이번 주 돌봄</span><b>{n(stats && stats.week, '건')}</b></button>
          </div>
        </div>
        <nav className="nv-card" aria-label="담당자 메뉴">
          {ORG_MENU.map(([g, items]) => (
            <div key={g}>
              <div className="gp">{g}</div>
              {items.map(([k, l, i]) => <button key={k} type="button" className={'nv ' + (on === k ? 'on' : '')} aria-current={on === k ? 'page' : undefined} onClick={() => nav(k)}><span className="ni"><PIc k={i} /></span>{l}</button>)}
            </div>
          ))}
          <div className="nv-foot">
            {onDemo && <button type="button" className="nv" onClick={onDemo}><span className="ni"><PIc k="swap" /></span>계정 바꾸기</button>}
            <button type="button" className="nv" onClick={async () => { await logout(); nav('/login', { replace: true }); }}><span className="ni"><PIc k="out" /></span>로그아웃</button>
          </div>
        </nav>
      </aside>
      <div className="pcw-main">
        <header className="mtop">
          {canBack && !isRoot && <button type="button" className="ic" aria-label="뒤로" onClick={() => nav(-1)}><PIc k="back" /></button>}
          <h1>{title}</h1>
          <span className="mu">{todayText()}</span>
          <button type="button" className="ic" aria-label={unread ? `알림 ${unread}건` : '알림'} onClick={() => nav('/notif')}><PIc k="bell" />{unread ? <span className="dot" /> : null}</button>
          <button type="button" className="prof" onClick={() => nav('/o/my')} aria-label="내 정보·설정"><i aria-hidden="true">{me ? me.name[0] : ''}</i>{me && me.name}</button>
        </header>
        <main key={loc.key} className="mbd" ref={bodyRef}>
          {wide ? children : <div className={`pcw-col bd ${cls}`}>{children}</div>}
        </main>
      </div>
    </div>
  );
}
