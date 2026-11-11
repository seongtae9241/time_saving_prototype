// 2026-11-11 역할별 정리판 프로토타입의 공용 조각: 듀오톤 아이콘(dIc), 파스텔 아바타(pav), 시간 표기(hmBig·hmTxt),
// 도움 받기 / 봉사하기 전역 모드(헤더에서 바꾸면 홈·공고·내 활동·통장·플래너·요청 상세가 따라 바뀐다).
import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { DI, DC } from './duoIcons.js';
import { useAuth } from './ctx.jsx';

/** 듀오톤 아이콘 (프로토타입 dIc). bare 면 배경 없이 그림만. */
export function DIc({ k, size = 52, bare, style }) {
  const key = DI[k] ? k : 'ai';
  const [a, b, bg] = DC[key] || DC.ai;
  const s = Math.round(size * (bare ? 1 : 0.62));
  const svg = <svg viewBox="0 0 40 40" width={s} height={s} aria-hidden="true" dangerouslySetInnerHTML={{ __html: DI[key](a, b) }} />;
  if (bare) return svg;
  return <span className="dic" style={{ width: size, height: size, background: bg, '--dk': b, ...style }}>{svg}</span>;
}
export const duoColor = (k) => DC[k] || DC.ai;

const PAV = [['#FFE3D6', '#D9775A'], ['#DDF2EA', '#3F8F7B'], ['#E1ECFE', '#3A6FD8'], ['#FFF1CC', '#B98516'], ['#FCE1EA', '#C95A7C'], ['#E6F4F8', '#3D8FAE']];
/** 파스텔 아바타 (프로토타입 pav) */
export function Pav({ name, s = 40 }) {
  const n = name || '?';
  const c = PAV[[...n].reduce((x, ch) => x + ch.charCodeAt(0), 0) % PAV.length];
  return <span className="pav" style={{ width: s, height: s, fontSize: Math.round(s * 0.4), background: c[0], color: c[1] }}>{n[0]}</span>;
}

/** 시간을 "23시간 30분"으로 (프로토타입 hm·hmTxt·hmBig) */
export function hm(v) { const t = Math.round(Math.abs(Number(v) || 0) * 60); return { H: Math.floor(t / 60), M: t % 60 }; }
export function hmTxt(v) { const { H, M } = hm(v); return `${H}시간${M ? ` ${M}분` : ''}`; }
export function HmBig({ v }) { const { H, M } = hm(v); return <>{H}<small>시간</small>{M ? <>{M}<small>분</small></> : null}</>; }

/** 통장 머리의 작은 달력 그림 (프로토타입 calIcon) */
export const CalIcon = ({ s = 20 }) => (
  <svg width={s} height={s} viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="4" fill="#E1ECFE" /><rect x="3" y="5" width="18" height="6" rx="3" fill="#5B8DEF" /><rect x="7" y="2.5" width="2.4" height="5" rx="1.2" fill="#3A6FD8" /><rect x="14.6" y="2.5" width="2.4" height="5" rx="1.2" fill="#3A6FD8" /><circle cx="8.5" cy="15" r="1.5" fill="#EE9A7E" /><circle cx="12" cy="15" r="1.5" fill="#6DB3A0" /><circle cx="15.5" cy="15" r="1.5" fill="#B8D0FA" /></svg>
);
/** 달성률 고리 (프로토타입 ring) */
export function Ring({ v, max, c = '#6DB3A0' }) {
  const r = 30, C = 2 * Math.PI * r, p = max > 0 ? Math.min(1, v / max) : 0;
  return (
    <svg width="78" height="78" viewBox="0 0 78 78" role="img" aria-label={`${Math.round(p * 100)}%`}>
      <circle cx="39" cy="39" r={r} fill="none" stroke="#EEF0F5" strokeWidth="8" />
      <circle cx="39" cy="39" r={r} fill="none" stroke={c} strokeWidth="8" strokeLinecap="round" strokeDasharray={`${(C * p).toFixed(1)} ${C}`} transform="rotate(-90 39 39)" />
      <text x="39" y="44" textAnchor="middle" fontSize="15" fontWeight="800" fill="#2A2B3D">{Math.round(p * 100)}%</text>
    </svg>
  );
}

/* ---------- 도움 받기 / 봉사하기 전역 모드 ---------- */
export const MODE = { recv: { label: '도움 받기', color: '#EE9A7E' }, give: { label: '봉사하기', color: '#6DB3A0' } };
const MODE_KEY = 'ts-mode';
function readMode() {
  try {
    const v = localStorage.getItem(MODE_KEY);
    if (v === 'give' || v === 'recv') return v;
    return localStorage.getItem('ts-home-mode') === 'give' ? 'give' : 'recv'; // 예전 홈 세그먼트 값 이어받기
  } catch { return 'recv'; }
}
const ModeCtx = createContext({ mode: 'recv', setMode: () => {}, give: false });
export const useMode = () => useContext(ModeCtx);

export function ModeProvider({ children }) {
  const { me } = useAuth();
  const [mode, set] = useState(readMode);
  const setMode = useCallback((m) => {
    const v = m === 'give' ? 'give' : 'recv';
    set(v);
    try { localStorage.setItem(MODE_KEY, v); } catch { /* 저장이 막혀도 화면은 바뀐다 */ }
  }, []);
  const isUser = !!(me && me.role === 'USER');
  // 프로토타입 render(): body[data-mode] 로 주황(받기)/초록(하기) 테마를 건다. 담당자·운영자는 기본 파랑.
  useEffect(() => {
    if (isUser) document.body.dataset.mode = mode; else delete document.body.dataset.mode;
    return () => { delete document.body.dataset.mode; };
  }, [mode, isUser]);
  return <ModeCtx.Provider value={{ mode, setMode, give: mode === 'give' }}>{children}</ModeCtx.Provider>;
}
