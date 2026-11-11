// 2026-11-11 역할별 정리판: 시간 플래너 — 캘린더 / 추천 봉사 / 생애 플랜 (프로토타입 SCREENS.planner v3)
// 일정·목표·추천 순위·생애 숫자는 모두 서버 코드가 계산한다(GET /api/me/planner, /api/me/life-plan). AI 는 설명 문장만.
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Spk, Loading, Empty, RuleNote, useLoad, useBusy } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api, post, del } from '../api.js';
import { CATS, SHORT, hh, fmt, dlabel } from '../lib/format.js';
import { DIc, Ring, hmTxt, useMode } from '../ui/proto.jsx';

const WD = ['일', '월', '화', '수', '목', '금', '토'];
const KC = { give: '#6DB3A0', recv: '#EE9A7E', event: '#8DB4F7', plan: '#6DB3A0' };
const PLAN_RING = { boxShadow: '0 0 0 2px #fff,0 0 0 3.5px #6DB3A0' };
const pad = (n) => String(n).padStart(2, '0');
const ymOf = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
const valTxt = (x) => (x.kind === 'give' || x.kind === 'plan' ? `+${hmTxt(x.amount)}` : x.kind === 'recv' ? `−${hmTxt(x.amount)}` : '행사');

/** 날짜별 일정 한 줄 (프로토타입 .tl2) */
export function PlanRow({ x, withDate }) {
  const nav = useNavigate();
  const go = () => (x.requestId ? nav(`/req/${x.requestId}`) : x.eventId ? nav(`/news/${x.eventId}`) : x.postId ? nav(`/post/${x.postId}`) : null);
  return (
    <div className="tl2 click" role="button" tabIndex={0} onClick={go} onKeyDown={(e) => { if (e.key === 'Enter') go(); }}>
      <span className="tl2-dot" style={{ borderColor: KC[x.kind] }} />
      <div className="tl2-card">
        <DIc k={x.kind === 'event' ? 'news' : x.category} size={44} />
        <div className="grow" style={{ minWidth: 0 }}>
          <div className="b">{x.title}{x.kind === 'plan' && <> <span className="mod m1">AI 계획</span></>}</div>
          <div className="mute2">{x.who}{withDate ? ` · ${dlabel(x.date)}` : ''}</div>
        </div>
        <div className="tl2-r"><b>{x.startHour != null ? hh(x.startHour) : ''}</b><span style={{ color: KC[x.kind] }}>{valTxt(x)}</span></div>
      </div>
    </div>
  );
}

/** 나이별 시간 통장 그래프 (프로토타입 plChart) — 점은 서버가 계산한 plan.points */
function LifeChart({ p }) {
  const W = 320, H = 180, pl = 6, pr = 6, pt = 34, pb = 24;
  const pts = p.points || [];
  const mx = Math.max(1, ...pts.map((q) => q.hours)) * 1.12;
  const x = (a) => pl + ((a - p.age) / Math.max(1, p.maxAge - p.age)) * (W - pl - pr);
  const y = (v) => pt + (H - pt - pb) * (1 - v / mx);
  const line = pts.map((q, i) => `${i ? 'L' : 'M'}${x(q.age).toFixed(1)} ${y(q.hours).toFixed(1)}`).join(' ');
  const area = `${line} L${x(p.maxAge)} ${H - pb} L${x(p.age)} ${H - pb}Z`;
  const sx = x(p.startAge), ex = x(Math.min(p.maxAge, p.endAge));
  const ticks = [p.age, 60, 70, 80, 90].filter((a) => a === p.age || (a - p.age >= 6 && a <= p.maxAge));
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label={`${p.age}세부터 ${p.maxAge}세까지 시간 통장 예상. ${p.startAge}세에 ${fmt(p.saved)}시간, ${p.endAge}세까지 사용`}>
      <defs><linearGradient id="plg" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="#5B8DEF" stopOpacity=".35" /><stop offset="1" stopColor="#5B8DEF" stopOpacity="0" /></linearGradient></defs>
      <rect x={x(p.age)} y={pt - 30} width={Math.max(0, sx - x(p.age))} height={H - pb - pt + 30} fill="#F3F6FF" rx="8" />
      <rect x={sx} y={pt - 30} width={Math.max(0, ex - sx)} height={H - pb - pt + 30} fill="#FFF5EA" rx="8" />
      <text x={(x(p.age) + sx) / 2} y={pt - 18} textAnchor="middle" fontSize="10.5" fontWeight="700" fill="#3A6FD8">모으는 시기</text>
      {ex - sx > 44 && <text x={(sx + ex) / 2} y={pt - 18} textAnchor="middle" fontSize="10.5" fontWeight="700" fill="#B55E1C">쓰는 시기</text>}
      <path d={area} fill="url(#plg)" /><path d={line} fill="none" stroke="#5B8DEF" strokeWidth="3" strokeLinejoin="round" />
      <circle cx={sx} cy={y(p.saved)} r="5.5" fill="#5B8DEF" stroke="#fff" strokeWidth="2.5" />
      <g transform={`translate(${Math.min(W - 76, sx + 8)} ${y(p.saved) + 6})`}><rect width="68" height="21" rx="10.5" fill="#1B1D22" /><text x="34" y="14.5" textAnchor="middle" fontSize="11" fontWeight="700" fill="#fff">{Math.round(p.saved)}시간</text></g>
      {ticks.map((a) => <text key={a} x={x(a)} y={H - 6} textAnchor="middle" fontSize="11" fill="#687080">{a}세</text>)}
    </svg>
  );
}

/** 생애 플랜 목표 바꾸기 시트 (프로토타입 plGoal + 받고 싶은 도움) */
function GoalSheet({ plan, onSave, onClose }) {
  const [f, setF] = useState({ age: plan.age, startAge: plan.startAge, monthlyHours: plan.monthlyHours, needs: Object.fromEntries((plan.needs || []).map((n) => [n.category, n.hours])) });
  const set = (k, v) => setF((x) => { const n = { ...x, [k]: v }; if (k === 'age' && n.startAge <= v) n.startAge = v + 1; return n; });
  const need = (c, d) => setF((x) => { const cur = x.needs[c] || 0; const v = Math.max(0, Math.min(20, cur + d)); const needs = { ...x.needs }; if (v) needs[c] = v; else delete needs[c]; return { ...x, needs }; });
  const rows = [['age', '지금 나이', 20, 85, '세', 1], ['startAge', '도움 받기 시작', Math.max(f.age + 1, 50), 90, '세', 1], ['monthlyHours', '한 달에 모을 시간', 1, 20, '시간', 0.5]];
  return (
    <>
      <h2>생애 플랜 목표</h2>
      {rows.map(([k, t, a, b, u, step]) => (
        <div key={k} className="pl-q" style={{ boxShadow: 'none', background: '#F4F6FA' }}>
          <div className="between"><b id={`pl_${k}`}>{t}</b><span className="pl-v">{f[k]}{u}</span></div>
          <input type="range" min={a} max={b} step={step} value={Math.min(b, Math.max(a, f[k]))} aria-labelledby={`pl_${k}`} onChange={(e) => set(k, Number(e.target.value))} />
        </div>
      ))}
      <div className="pl-q" style={{ boxShadow: 'none', background: '#F4F6FA' }}>
        <b>나중에 받고 싶은 도움 (한 달)</b>
        <div className="pl-needs">
          {CATS.map((c) => (
            <div key={c.k} className={'pl-n ' + (f.needs[c.k] ? 'on' : '')}>
              <DIc k={c.k} size={36} /><span>{SHORT[c.k]}</span><em>{f.needs[c.k] ? `월 ${f.needs[c.k]}시간` : '없음'}</em>
              <div className="row" style={{ gap: 6, marginTop: 6, justifyContent: 'center' }}>
                <button type="button" className="btn ghost sm" style={{ width: 44, minHeight: 44, padding: 0 }} aria-label={`${SHORT[c.k]} 줄이기`} onClick={() => need(c.k, -1)}>−</button>
                <button type="button" className="btn soft sm" style={{ width: 44, minHeight: 44, padding: 0 }} aria-label={`${SHORT[c.k]} 늘리기`} onClick={() => need(c.k, 1)}>+</button>
              </div>
            </div>
          ))}
        </div>
        <div className="mute2" style={{ marginTop: 8 }}>받을 때는 가중치(기본 1.0 · 수고 1.2 · 책임 1.5)만큼 시간이 빠져요.</div>
      </div>
      <div className="btns" style={{ marginTop: 12 }}><button className="btn ghost" onClick={onClose}>취소</button><button className="btn" onClick={() => onSave(f)}>저장</button></div>
    </>
  );
}

export function Planner() {
  const nav = useNavigate();
  const { toast, sheet, closeSheet } = useUi();
  const { give } = useMode();
  const [sp, setSp] = useSearchParams();
  const seg = sp.get('seg') || 'cal';
  const [month, setMonth] = useState(sp.get('month') || '');
  const [d, reload] = useLoad(() => api(`/me/planner${month ? `?month=${month}` : ''}`), [month]);
  const [life, reloadLife] = useLoad(() => api('/me/life-plan', { quiet: true }).catch(() => null), []);
  const [sel, setSel] = useState(sp.get('day') || '');
  const [picked, setPicked] = useState([]);
  const [busy, run] = useBusy();
  const segs = [['cal', '캘린더'], ['rec', '추천 봉사'], ['life', '생애 플랜']];
  const head = (
    <div className="chips" role="tablist" aria-label="플래너" style={{ marginBottom: 12 }}>
      {segs.map(([k, t]) => <button key={k} role="tab" aria-selected={seg === k} className={'chip ' + (seg === k ? 'on' : '')} onClick={() => setSp({ seg: k }, { replace: true })}>{t}</button>)}
    </div>
  );
  if (d === undefined) return <Screen title="시간 플래너" noTabs>{head}<Loading /></Screen>;
  if (!d) return <Screen title="시간 플래너" noTabs>{head}<Empty>플래너를 불러오지 못했어요</Empty></Screen>;

  const [Y, M] = d.month.split('-').map(Number);
  const first = new Date(Y, M - 1, 1), days = new Date(Y, M, 0).getDate();
  const byDay = {};
  (d.items || []).forEach((x) => { (byDay[x.date] = byDay[x.date] || []).push(x); });
  const iso = (n) => `${d.month}-${pad(n)}`;
  const curSel = sel && sel.startsWith(d.month) ? sel : (d.today.startsWith(d.month) ? d.today : iso(1));
  const items = byDay[curSel] || [];
  const move = (n) => { const t = new Date(Y, M - 1 + n, 1); setMonth(ymOf(t)); setSel(''); };
  const g = d.goal || {};
  const tot = (g.confirmed || 0) + (g.planned || 0);
  const selDate = new Date(Y, M - 1, Number(curSel.slice(8)));
  const plans = d.plans || [];
  const recs = d.recommends || [];
  const p = life && life.plan;

  const build = () => run(async () => {
    if (!picked.length) return;
    await post('/me/planner/picks', { postIds: picked });
    setPicked([]);
    await reload();
    toast('고른 봉사를 달력에 넣었어요. 제안은 직접 보내 주세요');
  });
  const drop = (id) => run(async () => { await del(`/me/planner/picks/${id}`); await reload(); toast('계획에서 뺐어요'); });
  const openGoal = () => p && sheet(() => (
    <GoalSheet plan={p} onClose={closeSheet} onSave={async (f) => {
      try { await post('/me/life-plan', { ...f, save: true }); closeSheet(); await reloadLife(); await reload(); toast('생애 플랜을 바꿨어요'); } catch { /* 토스트 */ }
    }} />
  ));

  return (
    <Screen title="시간 플래너" noTabs>
      {head}
      {seg === 'cal' && (
        <>
          <div className="calc">
            <div className="between"><button className="cal-nav" aria-label="이전 달" onClick={() => move(-1)}>‹</button><div className="cal-m">{Y}년 {M}월</div><button className="cal-nav" aria-label="다음 달" onClick={() => move(1)}>›</button></div>
            <div className="cal-g">
              {WD.map((x, i) => <span key={x} className={'cw ' + (i === 0 ? 'sun' : '')}>{x}</span>)}
              {Array.from({ length: first.getDay() }, (_, i) => <span key={'b' + i} aria-hidden="true" />)}
              {Array.from({ length: days }, (_, i) => {
                const n = i + 1, k = iso(n), it = byDay[k] || [];
                return (
                  <button key={k} className={`cd ${k === curSel ? 'sel' : ''} ${k === d.today ? 'today' : ''} ${k < d.today ? 'past' : ''} ${it.some((x) => x.kind === 'plan') ? 'new' : ''}`} aria-pressed={k === curSel} aria-label={`${M}월 ${n}일, 일정 ${it.length}개`} onClick={() => setSel(k)}>
                    <b>{n}</b><div className="cd-dots">{it.slice(0, 3).map((x, j) => <i key={j} style={{ background: KC[x.kind], ...(x.kind === 'plan' ? PLAN_RING : {}) }} />)}</div>
                  </button>
                );
              })}
            </div>
            <div className="cal-lg"><span><i style={{ background: '#6DB3A0' }} />봉사</span><span><i style={{ background: '#EE9A7E' }} />도움 받기</span><span><i style={{ background: '#5B8DEF' }} />기관 소식</span><span><i style={{ background: '#6DB3A0', ...PLAN_RING }} />AI 계획</span></div>
          </div>
          <div className="goal2">
            <div><Ring v={tot} max={g.target || 1} /></div>
            <div className="grow"><div className="mute2">이번 달 봉사 목표</div><div className="b" style={{ fontSize: 20 }}>{hmTxt(tot)} <span className="mute2" style={{ fontSize: 14 }}>/ {fmt(g.target)}시간</span></div>
              <div className="mute2">{g.planned ? `계획 포함 · 확정 ${fmt(g.confirmed)}시간` : g.fromLifePlan ? '생애 플랜의 한 달 목표예요' : '추천 봉사를 넣으면 함께 계산돼요'}</div></div>
          </div>
          <div className="sec-t"><span>{M}월 {Number(curSel.slice(8))}일 ({WD[selDate.getDay()]})</span><span className="mute2" style={{ fontWeight: 500 }}>{items.length}개 일정</span></div>
          {items.length ? <div className="tline">{items.map((x, i) => <PlanRow key={i} x={x} />)}</div>
            : <div className="empty">비어 있는 날이에요{give ? ' · 추천 봉사를 넣어 보세요' : ''}</div>}
          <button className="ai-fill" onClick={() => setSp({ seg: 'rec' }, { replace: true })}><DIc k="ai" size={40} bare /><div className="grow" style={{ textAlign: 'left' }}><b>추천 봉사로 빈 날 채우기</b><span>내 빈 시간·지역·잘하는 도움에 맞는 봉사 요청 {recs.length}개</span></div>›</button>
        </>
      )}
      {seg === 'rec' && (
        <>
          <div className="ptext">
            <div className="pt-h"><DIc k="ai" size={34} bare /><div><b>AI가 세운 {M}월 계획</b><span>목표 {fmt(g.target)}시간 · 지금 {hmTxt(g.confirmed)}</span></div></div>
            <p>{d.text}</p>
            {plans.length > 0 && (
              <div className="pt-list">
                {plans.map((r) => (
                  <div className="pt-i" key={r.postId}>
                    <span className="pt-d"><b>{Number(r.date.slice(8))}</b>{WD[new Date(r.date).getDay()]}</span>
                    <div className="grow" style={{ minWidth: 0 }}><div className="b">{hh(r.startHour)} {r.title}</div><div className="mute2">{r.who}</div>
                      <div className="row" style={{ gap: 6, marginTop: 6 }}>
                        <button className="btn sm" style={{ width: 'auto', padding: '0 12px', minHeight: 44 }} onClick={() => nav(`/post/${r.postId}`)}>공고 보고 제안하기</button>
                        <button className="btn ghost sm" style={{ width: 'auto', padding: '0 12px', minHeight: 44 }} disabled={busy} onClick={() => drop(r.postId)}>빼기</button>
                      </div>
                    </div>
                    <span className="pill">+{fmt(r.amount)}</span>
                  </div>
                ))}
              </div>
            )}
            <div className="pt-tip">💡 {plans.length ? '계획은 내 달력에만 보여요. 제안을 보내고 상대가 수락해야 확정돼요.' : '동행(1.5배)을 하나 섞으면 같은 시간에 더 많이 쌓여요.'}</div>
            <RuleNote source={d.source} />
          </div>
          <div className="sec-t"><span>추천 봉사 <span className="mute2" style={{ fontWeight: 500 }}>빈 시간에 맞는 것만</span></span><span className="mute2" style={{ fontWeight: 600 }}>{picked.length}개 선택</span></div>
          {recs.length ? (
            <div className="recs">
              {recs.map((r) => {
                const on = picked.includes(r.id);
                return (
                  <button key={r.id} className={'rec ' + (on ? 'on' : '')} aria-pressed={on} onClick={() => setPicked(on ? picked.filter((x) => x !== r.id) : [...picked, r.id])}>
                    <DIc k={(r.categories || [])[0]} size={46} />
                    <div className="grow" style={{ minWidth: 0 }}><div className="b">{r.title}</div><div className="mute2">{r.ownerName} · {r.distanceKm}km · {dlabel(r.date)} {hh(r.startHour)}~{hh(r.endHour)}</div><div className="rec-why"><Spk /> {r.why}</div></div>
                    <div className="rec-r"><span className="pill">+{fmt(r.estEarn)}</span><span className="rec-ck">{on ? '✓' : ''}</span></div>
                  </button>
                );
              })}
            </div>
          ) : <Empty>지금은 빈 시간에 맞는 봉사 요청이 없어요</Empty>}
          <button className="ai-fill" disabled={!picked.length || busy} style={picked.length ? undefined : { opacity: 0.5 }} onClick={build}><DIc k="ai" size={40} bare /><div className="grow" style={{ textAlign: 'left' }}><b>{picked.length ? `선택한 ${picked.length}개로 계획 세우기` : '추천 봉사를 골라 주세요'}</b><span>고른 봉사를 내 달력의 그 날짜에 넣어요</span></div>›</button>
          <button className="btn ghost" style={{ marginTop: 10 }} onClick={() => nav('/board?type=NEED')}>봉사 요청 공고 전체 보기</button>
        </>
      )}
      {seg === 'life' && (!p ? <Loading /> : (
        <>
          <div className="sec-t" style={{ marginTop: 4 }}><span>생애 플랜</span><a role="button" tabIndex={0} onClick={openGoal} onKeyDown={(e) => { if (e.key === 'Enter') openGoal(); }}>목표 바꾸기</a></div>
          <div className="life">
            <div className="life-bar"><span className="lb1" style={{ flex: p.startAge - p.age }} /><span className="lb2" style={{ flex: Math.max(1, p.endAge - p.startAge) }} /></div>
            <div className="life-l"><span>지금 {p.age}세</span><span>{p.startAge}세</span><span>{p.endAge}세{p.endAge >= p.maxAge ? '+' : ''}</span></div>
            <div className="life-s"><div><i style={{ background: '#6DB3A0' }} />모으기 <b>{Math.round(p.saved)}시간</b></div><div><i style={{ background: '#EE9A7E' }} />쓰기 <b>약 {Math.round(p.lastYears)}년</b></div></div>
            <div className="ptext" style={{ marginTop: 12, boxShadow: 'none', background: '#F4F6FA' }}><p style={{ margin: 0 }}><Spk /> {life.text}</p><RuleNote source={life.source} /></div>
          </div>
          <div className="card" style={{ marginTop: 12 }}><div className="between"><span className="b">나이별 시간 통장 예상</span><span className="mute2">{p.age}세 → {p.maxAge}세</span></div><LifeChart p={p} /></div>
          <div className="sum" style={{ marginTop: 12 }}>
            <div className="between"><span className="muted">지금 통장</span><b>{hmTxt(p.balance)}</b></div>
            <div className="between"><span className="muted">한 달에 모을 시간</span><b>{fmt(p.monthlyHours)}시간 × {p.saveYears}년</b></div>
            <div className="between"><span className="muted">받고 싶은 도움</span><b>{(p.needs || []).length ? p.needs.map((n) => `${SHORT[n.category]} 월 ${fmt(n.hours)}`).join(' · ') : '아직 안 골랐어요'}</b></div>
            <div className="between"><span className="muted">한 달에 빠지는 시간 (가중치 포함)</span><b>{fmt(p.useMonthly)}시간</b></div>
            {!p.okTo90 && p.useMonthly > 0 && <div className="between"><span className="muted">90세까지 쓰려면</span><b>한 달 {fmt(p.need90Monthly)}시간</b></div>}
          </div>
          {!p.okTo90 && p.useMonthly > 0 && <button className="btn soft sm" style={{ marginTop: 10 }} disabled={busy} onClick={() => run(async () => { await post('/me/life-plan', { age: p.age, startAge: p.startAge, monthlyHours: p.need90Monthly, needs: Object.fromEntries(p.needs.map((n) => [n.category, n.hours])), save: true }); await reloadLife(); await reload(); toast(`한 달 ${fmt(p.need90Monthly)}시간으로 바꿨어요`); })}>90세까지 쓰도록 한 달 목표 올리기</button>}
          <div className="notice" style={{ marginTop: 12 }}>시간은 가족에게 선물할 수 있지만 상속은 안 돼요. 제도·가중치가 바뀌면 숫자가 달라질 수 있어요.</div>
          {!life.saved && <button className="btn" style={{ marginTop: 12 }} onClick={openGoal}>내 조건으로 계획 세우기</button>}
          <button className="btn ghost" style={{ marginTop: 10 }} onClick={() => nav('/bank/plan')}>남은 시간으로 받을 수 있는 도움 계산하기</button>
        </>
      ))}
    </Screen>
  );
}
