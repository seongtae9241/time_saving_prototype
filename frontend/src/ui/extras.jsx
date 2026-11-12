// 2026-11-09 회의 반영 공용 조각: 가로 슬라이드(U4), 단골 등록 버튼(U7), 자주 받는 도움 순위(U3), 홈 모드 기억(U6)
import { Children, useEffect, useRef, useState } from 'react';
import { api, del, post } from '../api.js';
import { useLoad } from './bits.jsx';
import { useUi } from './ctx.jsx';
import { useMode } from './proto.jsx';
import '../styles/meeting.css';

/** 가로 슬라이드: 스크롤 스냅 + 손가락으로 넘기기 + 점 표시. 점을 누르면 그 카드로 간다. */
export function Slider({ children, label }) {
  const ref = useRef(null);
  const [ix, setIx] = useState(0);
  const items = Children.toArray(children).filter(Boolean);
  const n = items.length;
  useEffect(() => { if (ix >= n) setIx(Math.max(0, n - 1)); }, [n]); // eslint-disable-line
  const onScroll = () => {
    const el = ref.current;
    if (!el || !el.firstElementChild) return;
    const w = el.firstElementChild.getBoundingClientRect().width + 10;
    setIx(Math.max(0, Math.min(n - 1, Math.round(el.scrollLeft / w))));
  };
  const go = (i) => {
    const el = ref.current;
    if (!el || !el.children[i]) return;
    el.scrollTo({ left: el.children[i].offsetLeft - el.firstElementChild.offsetLeft, behavior: 'smooth' });
  };
  if (!n) return null;
  return (
    <div role="region" aria-roledescription="슬라이드" aria-label={label}>
      <div className={'slide' + (n === 1 ? ' one' : '')} ref={ref} onScroll={onScroll}>{items}</div>
      {n > 1 && (
        <div className="slide-dots" role="tablist">
          {items.map((_, i) => <button key={i} type="button" role="tab" aria-selected={i === ix} aria-label={`${i + 1}번째 카드`} className={i === ix ? 'on' : ''} onClick={() => go(i)} />)}
        </div>
      )}
    </div>
  );
}

/** U6 → 2026-11-11: 홈 세그먼트 대신 헤더의 전역 모드(ui/proto.jsx useMode). 예전 호출부 호환용('get' | 'give'). */
export function useHomeMode() {
  const { mode, setMode } = useMode();
  return [mode === 'give' ? 'give' : 'get', (m) => setMode(m === 'give' ? 'give' : 'recv')];
}

/** U3: 내가 자주 받는 도움 {top:[{category,count}], order:[...]} — 실패하면 빈 값 */
export function useTopCats() {
  const [d] = useLoad(() => api('/me/top-categories', { quiet: true }).catch(() => null), []);
  return d || null;
}
/** 공고를 자주 받는 도움 순으로(같은 순위 안에서는 원래 순서 유지) */
export function sortByTop(list, top) {
  if (!top || !top.top || !top.top.length) return list;
  const rank = {}; top.top.forEach((t, i) => { rank[t.category] = i; });
  const r = (p) => Math.min(99, ...((p.categories && p.categories.length ? p.categories : [p.category]).map((c) => (rank[c] ?? 99))));
  return list.map((p, i) => [p, i]).sort((a, b) => (r(a[0]) - r(b[0])) || (a[1] - b[1])).map((x) => x[0]);
}

/** B-65: 단골로 등록 / 해지 버튼. 프로필·평가 화면에서 즐겨찾기처럼 직접 등록한다(활동이 없어도 됨). 자동(3회) 단골도 해지할 수 있다. */
export function RegularButton({ volunteerId, style, compact, onChange }) {
  const { toast } = useUi();
  const [st, reload] = useLoad(() => (volunteerId ? api(`/regulars/${volunteerId}/status`, { quiet: true }).catch(() => null) : Promise.resolve(null)), [volunteerId]);
  const [busy, setBusy] = useState(false);
  if (!st || (!st.canMark && !st.regular)) return null;
  const run = async () => {
    if (busy) return;
    setBusy(true);
    try {
      if (st.regular) { await del(`/regulars/${volunteerId}`); toast('단골을 해지했어요'); }
      else { await post(`/regulars/${volunteerId}`); toast('단골로 등록했어요. 공고 없이 바로 요청할 수 있어요'); }
      await reload();
      if (onChange) onChange();
    } catch { /* 토스트 */ }
    setBusy(false);
  };
  return (
    <button type="button" className={'btn ' + (st.regular ? 'ghost' : 'soft') + (compact ? ' sm' : '')} style={{ ...(compact ? { width: 'auto', padding: '0 12px' } : {}), ...style }} disabled={busy} onClick={run}>
      {st.regular ? `★ 단골 해지${st.auto ? ` (자동 · ${st.doneCount}회)` : ''}` : '☆ 단골로 등록'}
    </button>
  );
}
