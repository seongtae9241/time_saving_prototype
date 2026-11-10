import { useEffect, useRef } from 'react';
import { useLocation, useNavigate, useNavigationType } from 'react-router-dom';
import { I } from './icons.js';
import { Ic } from './bits.jsx';
import { useAuth, useUi } from './ctx.jsx';

export const ROLES = {
  USER: { label: '사용자', color: '#3D6DF2', tabs: [['/u/home', '홈', 'home'], ['/board', '공고', 'search'], ['/news', '동네 소식', 'cal'], ['/act', '내 활동', 'list'], ['/bank', '시간 통장', 'wallet'], ['/my', '마이', 'user']] },
  ORG: { label: '담당자', color: '#6A4FD8', tabs: [['/o/home', '홈', 'home'], ['/o/elders', '도움 대상', 'users'], ['/o/reqs', '요청 관리', 'list'], ['/o/report', '리포트', 'chart'], ['/o/my', '마이', 'user']] },
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
  return () => sheet(() => (
    <>
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

/**
 * 프로토타입 render()의 화면 틀: 헤더(뒤로·제목·알림 벨·역할 칩) + 본문 + 역할별 아래 탭
 */
export default function Screen({ title = '', noHeader, noTabs, noBell, cls = '', tab, back, children, bodyRef }) {
  const { me, unread } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const navType = useNavigationType();
  const demo = useDemoSheet();
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
            {R && <button className="chip-role" onClick={demo}><span className="dot" style={{ background: R.color }} />{R.label}<Ic h={I.down} /></button>}
          </div>
        </header>
      )}
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
