import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useNavigationType } from 'react-router-dom';
import { I } from './icons.js';
import { Ic } from './bits.jsx';
import { useAuth, useUi } from './ctx.jsx';
import { DIc, MODE, useMode } from './proto.jsx';
import { api } from '../api.js';

export const ROLES = {
  USER: { label: '사용자', color: '#5B8DEF', tabs: [['/u/home', '홈', 'home'], ['/board', '공고', 'search'], ['/bank', '시간 통장', 'wallet'], ['/planner', '플래너', 'cal'], ['/my', '마이', 'user']] },
  ORG: { label: '담당자', color: '#3A6FD8', tabs: [['/o/home', '홈', 'home'], ['/o/elders', '도움 대상', 'users'], ['/o/schedule', '편성표', 'cal'], ['/o/reqs', '요청 관리', 'list'], ['/o/report', '리포트', 'chart'], ['/o/my', '마이', 'user']] },
  ADMIN: { label: '운영자', color: '#1B1D22', tabs: [['/a/dash', '대시보드', 'chart'], ['/a/approve', '승인', 'shield'], ['/a/safety', '신고·점검', 'flag'], ['/a/manage', '관리', 'gear']] },
};

export const DEMO = [
  ['yujin', 'USER', '손유진 · 봉사도 하고 도움도 받아요'],
  ['org1', 'ORG', '박지원 · 행복동 주민센터'],
  ['admin', 'ADMIN', '시간 적금 운영팀'],
];

/** 다른 데모 계정으로 로그인 시트 (프로토타입 roleSheet) */
export function useDemoSheet() {
  const { sheet, closeSheet, toast } = useUi();
  const { login, logout, me } = useAuth();
  const nav = useNavigate();
  const { mode, setMode } = useMode();
  const pick = (m) => { closeSheet(); setMode(m); toast(m === 'give' ? '봉사하기 화면이에요' : '도움 받기 화면이에요'); };
  return () => sheet(() => (
    <>
      {me && me.role === 'USER' && (
        <div className="msel">
          <h2>지금 할 일</h2>
          <div className="msel-r">
            <button className={'msel-b r ' + (mode !== 'give' ? 'on' : '')} aria-pressed={mode !== 'give'} onClick={() => pick('recv')}><DIc k="recv" size={40} /><div><b>도움 받기</b><span>도움 가능 공고 보기</span></div></button>
            <button className={'msel-b g ' + (mode === 'give' ? 'on' : '')} aria-pressed={mode === 'give'} onClick={() => pick('give')}><DIc k="offer" size={40} /><div><b>봉사하기</b><span>봉사 요청 보기</span></div></button>
          </div>
        </div>
      )}
      <h2>다른 데모 계정으로 로그인</h2>
      <p className="mute2" style={{ margin: '0 0 10px' }}>비밀번호는 모두 1234예요.</p>
      {DEMO.map(([id, role, desc]) => (
        <div key={id} className={'opt ' + (me && me.loginId === id ? 'on' : '')} role="button" tabIndex={0}
          onClick={async () => {
            closeSheet();
            try { const u = await login(id, '1234', true); toast(`${u.name} 님으로 로그인했어요`); nav({ USER: '/u/home', ORG: '/o/home', ADMIN: '/a/dash' }[u.role] || '/', { replace: true }); } catch { /* 토스트 */ }
          }}>
          <span className="rd" />
          <div className="grow"><div className="b">{ROLES[role].label} · {id}</div><div className="mute2">{desc}</div></div>
        </div>
      ))}
      {me && <button className="btn ghost" style={{ marginTop: 12 }} onClick={async () => { closeSheet(); await logout(); nav('/login', { replace: true }); }}>로그아웃</button>}
    </>
  ));
}

/* 공지 확성기 (2026-11-13 대표 지시, 프로토타입 .ntc): 고정 공지가 있으면 사용자 탭 화면 위쪽에 한 줄로 흐르고, 누르면 그 공지로 간다.
   공지 목록은 한 번만 불러 탭 사이에서 나눠 쓴다(5분 지나면 다시). */
let noticeCache = null, noticeAt = 0;
function pinnedNotices() {
  if (!noticeCache || Date.now() - noticeAt > 300000) {
    noticeAt = Date.now();
    noticeCache = api('/notices', { quiet: true }).then((l) => (l || []).filter((n) => n.pinned)).catch(() => { noticeCache = null; return []; });
  }
  return noticeCache;
}
function NoticeTicker() {
  const nav = useNavigate();
  const [list, setList] = useState([]);
  useEffect(() => { let on = true; pinnedNotices().then((l) => { if (on) setList(l.slice(0, 2)); }); return () => { on = false; }; }, []);
  if (!list.length) return null;
  return (
    <button type="button" className="ntc" aria-label={`공지: ${list.map((n) => n.title).join(', ')}`} onClick={() => nav(`/notice/${list[0].id}`)}>
      <span className="nt-ic" aria-hidden="true">📢</span>
      <div className="nt-w" aria-hidden="true"><div className="nt-t">{[0, 1].map((k) => list.map((n) => <span key={`${k}-${n.id}`}>{n.title}</span>))}</div></div>
      <span style={{ flex: '0 0 auto', opacity: 0.7 }} aria-hidden="true">›</span>
    </button>
  );
}

/**
 * 프로토타입 render()의 화면 틀: 헤더(뒤로·제목·알림 벨·역할 칩) + 본문 + 역할별 아래 탭
 */
export default function Screen({ title = '', noHeader, noTabs, noBell, cls = '', tab, back, children, bodyRef }) {
  const { me, unread } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const navType = useNavigationType();
  const demo = useDemoSheet();
  const { mode } = useMode();
  const role = me && me.role;
  const R = role && ROLES[role];
  const canBack = back !== false && (back === true || (window.history.state && window.history.state.idx > 0));
  const ref = useRef(null);
  const bd = bodyRef || ref;
  const anim = navType === 'POP' ? 'nav-bwd' : navType === 'REPLACE' ? 'nav-swap' : 'nav-fwd';
  useEffect(() => { if (bd.current) bd.current.scrollTop = 0; }, [loc.pathname]); // eslint-disable-line
  const active = tab || loc.pathname;
  const showTabs = R && !noTabs;
  return (
    <>
      {!noHeader && (
        <header className="hd">
          {canBack ? <button className="bk" aria-label="뒤로" onClick={() => nav(-1)}><Ic h={I.back} /></button> : <span style={{ width: 4 }} />}
          <h1>{title}</h1>
          <div className="hd-r">
            {role && role !== 'ADMIN' && !noBell && (
              <button className="bell" aria-label="알림" onClick={() => nav('/notif')}><Ic h={I.bell2} />{unread ? <i>{unread}</i> : null}</button>
            )}
            {R && <button className="chip-role" onClick={demo} aria-label={role === 'USER' ? `지금 ${MODE[mode].label} 화면 · 바꾸기` : `${R.label} · 계정 바꾸기`}><span className="dot" style={{ background: role === 'USER' ? MODE[mode].color : R.color }} />{role === 'USER' ? MODE[mode].label : R.label}<Ic h={I.down} /></button>}
          </div>
        </header>
      )}
      {role === 'USER' && showTabs && !noHeader && R.tabs.some(([k]) => k === loc.pathname) && <NoticeTicker />}
      <main key={loc.key} className={`bd ${cls} ${anim}`} ref={bd}>{children}</main>
      {showTabs && (
        <nav className="tabs">
          {R.tabs.map(([k, l, i]) => (
            <button key={k} className={active === k || active.startsWith(k + '/') ? 'on' : ''} onClick={() => nav(k, { replace: true })}><Ic h={I[i]} /><span>{l}</span></button>
          ))}
        </nav>
      )}
    </>
  );
}
