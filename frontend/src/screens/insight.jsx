// 회의반영(2026-11-09) 4장 최근 활동 리포트 · 6장 AI 개인 분석 · 내 봉사 플랜
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Loading, Empty, RuleNote, CatChip, useLoad, hbars } from '../ui/bits.jsx';
import { api, post } from '../api.js';
import { CATS, SHORT, fmt, dlabel, hh } from '../lib/format.js';
import { ReportView } from './my.jsx';
import { GoalPlan } from './goal.jsx'; // 7.13 B-5 나중에 받고 싶은 것
import { SignalCard, CareNoteBox } from './opsai.jsx';

const FREQS = [[0.25, '월 1회'], [0.5, '월 2회'], [1, '주 1회'], [2, '주 2회'], [3, '주 3회']];
const freqTxt = (v) => (FREQS.find((f) => f[0] === Number(v)) || [0, `주 ${v}회`])[1];

/* ---------- 전달 메모 한 칸 (요청 상세·리포트 공용) ---------- */
export function CareMemo({ text, style }) {
  if (!text) return null;
  return (
    <div className="notice" style={{ whiteSpace: 'pre-wrap', ...style }}>
      <b style={{ display: 'block', marginBottom: 4 }}>전달 메모</b>{text}
    </div>
  );
}

/* ---------- 최근 활동 리포트 (4장): AI 인수인계 요약 + 최근 활동(최신순) + 지난 월간 리포트 ---------- */
export function RecentReport({ subject, showMonthly = true, empty = '아직 기록된 활동이 없어요' }) {
  const nav = useNavigate();
  const [d, , err] = useLoad(() => api(`/reports/recent?subject=${encodeURIComponent(subject)}`, { quiet: true }), [subject]);
  const [open, setOpen] = useState(5);
  if (d === undefined) return <Loading />;
  if (err) return <Empty>{err.status === 403 ? err.message || '볼 수 있는 권한이 없어요' : err.message}</Empty>;
  const items = d.items || [];
  const h = d.handover || {};
  const monthly = d.monthly || [];
  return (
    <>
      <SignalCard s={d.signal} who={d.subjectLabel} />
      <div className="ai" style={d.signal && d.signal.flagged ? { marginTop: 10 } : undefined}>
        <div className="ah"><Spk />인수인계 요약<Mod n={5} /></div>
        <div className="small">{h.text}</div>
        {(h.points || []).length > 0 && <><div className="b" style={{ fontSize: 13.5, marginTop: 10 }}>다음에 챙길 것</div><ul className="small">{h.points.map((p, i) => <li key={i}>{p}</li>)}</ul></>}
        <div className="mute2" style={{ marginTop: 8 }}>최근 {h.count ?? 0}건을 묶어 정리했어요. 진단·건강 판단은 하지 않고, 병원 이야기는 해당 날짜 메모에서 직접 확인해 주세요.</div>
        <RuleNote source={h.source} />
      </div>
      <div className="sec-t"><span>최근 활동</span><span className="mute2" style={{ fontWeight: 500 }}>최신순 · {d.total ?? items.length}건</span></div>
      {items.length ? items.slice(0, open).map((it) => (
        <div key={it.requestId} className="card" style={{ marginTop: 8 }}>
          <div className="between"><CatChip k={it.category} /><span className="mute2">{dlabel(it.date)} {hh(it.startHour)} · {fmt(it.hours)}시간</span></div>
          <div className="mute2" style={{ marginTop: 6 }}>봉사자 {it.volunteer}{it.status === 'LOG_SUBMITTED' ? ' · 확인 대기' : ''}</div>
          {it.logText && <div className="small" style={{ marginTop: 6, whiteSpace: 'pre-wrap' }}>{it.logText}</div>}
          <CareNoteBox n={it.careNote} style={{ marginTop: 8 }} />
          <CareMemo text={it.careMemo} style={{ marginTop: 8 }} />
          <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => nav(subject.startsWith('elder:') ? `/o/reqs/${it.requestId}` : `/req/${it.requestId}`)} hidden={subject.startsWith('family:')}>요청 상세 보기</button>
        </div>
      )) : <Empty>{empty}</Empty>}
      {items.length > open && <button className="btn ghost sm" style={{ marginTop: 10 }} onClick={() => setOpen(open + 10)}>더 보기 ({items.length - open}건)</button>}
      {showMonthly && (
        <>
          <div className="sec-t"><span>지난 월간 리포트</span><span className="mute2" style={{ fontWeight: 500 }}>{monthly.length}개</span></div>
          {monthly.length ? monthly.map((r, i) => <div key={r.id || i} style={{ marginTop: i ? 12 : 0 }}><ReportView rep={r} who={d.subjectLabel} /></div>) : <Empty>지난 월간 리포트가 없어요</Empty>}
        </>
      )}
    </>
  );
}

/* ---------- 마이 → 내 리포트 ---------- */
export function MyReport() {
  return (
    <Screen title="내가 받은 도움 리포트" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>봉사자가 남긴 일지와 전달 메모를 최근 순서로 모았어요. 나와 공유에 동의한 가족만 볼 수 있어요.</p>
      <RecentReport subject="me" />
    </Screen>
  );
}

/* ---------- 가족 리포트 (공유 동의한 가족만) ---------- */
export function FamRecentReport() {
  const { userId } = useParams();
  return (
    <Screen title="가족 리포트" noTabs>
      <RecentReport subject={`family:${userId}`} />
    </Screen>
  );
}

/* ---------- 마이 → AI 분석 (6장) ---------- */
function Kpi3({ list }) {
  return <div className="kpis">{list.map(([v, l]) => <div key={l}><b>{v}</b><span>{l}</span></div>)}</div>;
}
export function MyInsight() {
  const nav = useNavigate();
  const [d] = useLoad(() => api('/me/insights'), []);
  if (d === undefined) return <Screen title="AI 분석" noTabs><Loading /></Screen>;
  if (!d) return <Screen title="AI 분석" noTabs><Empty>분석을 불러오지 못했어요</Empty></Screen>;
  const g = d.given || {}, r = d.received || {}, rt = d.rating || {};
  const catRows = (s) => (s.byCategory || []).map((c) => [SHORT[c.category] || c.category, c.count]);
  const slotRows = (s) => Object.entries(s.bySlot || {}).map(([k, v]) => [k, v]);
  const dist = (rt.distribution || [0, 0, 0, 0, 0]).map((v, i) => [`★${i + 1}`, v]).reverse();
  return (
    <Screen title="AI 분석" noTabs>
      <div className="sec-t" style={{ marginTop: 0 }}>① 내 활동 분석</div>
      <Kpi3 list={[[`${g.count || 0}회`, '도와줌'], [`${r.count || 0}회`, '도움 받음'], [g.ratingCount ? `★${Number(g.ratingAvg).toFixed(1)}` : '-', '받은 평가']]} />
      <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />나는 이런 이웃이에요<Mod n={5} /></div><div className="small">{d.activityText}</div><RuleNote source={d.source} /></div>
      {(g.count > 0) && <div className="card" style={{ marginTop: 10 }}><div className="b">내가 준 도움</div>{hbars(catRows(g), '#3D6DF2')}<div className="mute2" style={{ marginTop: 10 }}>시간대</div>{hbars(slotRows(g), '#6A4FD8')}{(g.topTags || []).length > 0 && <div className="tags" style={{ marginTop: 10 }}>{g.topTags.map((t) => <span key={t} className="tag2">{t}</span>)}</div>}</div>}
      {(r.count > 0) && <div className="card" style={{ marginTop: 10 }}><div className="b">내가 받은 도움</div>{hbars(catRows(r), '#F59E4B')}<div className="mute2" style={{ marginTop: 10 }}>시간대</div>{hbars(slotRows(r), '#F59E4B')}</div>}

      <div className="sec-t">② 내 평가 습관</div>
      <Kpi3 list={[[`${rt.count || 0}번`, '남긴 평가'], [rt.count ? `★${Number(rt.avg).toFixed(1)}` : '-', '평균 별점'], [rt.style || '-', '성향']]} />
      <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />평가 성향<Mod n={5} /></div><div className="small">{d.ratingText}</div><RuleNote source={d.source} /></div>
      {rt.count > 0 && <div className="card" style={{ marginTop: 10 }}><div className="b">별점 분포</div>{hbars(dist, '#E5A50A')}{(rt.topTags || []).length > 0 && <><div className="mute2" style={{ marginTop: 10 }}>자주 쓰는 칭찬</div><div className="tags" style={{ marginTop: 6 }}>{rt.topTags.map((t) => <span key={t} className="tag2">{t}</span>)}</div></>}</div>}

      <div className="sec-t">③ 내 봉사 플랜</div>
      <div className="card item click" role="button" tabIndex={0} onClick={() => nav('/bank/plan')}><div className="ic"><Ic n="wallet" s={20} /></div><div className="grow"><div className="b">남은 시간으로 얼마나 받을 수 있을까?</div><div className="mute2">카테고리·횟수를 고르면 기간을 계산해요</div></div>›</div>
      <div className="mute2" style={{ margin: '12px 2px' }}>횟수·비율·평균은 시스템이 세고, AI는 설명만 써요.</div>
    </Screen>
  );
}

/* ---------- 시간 통장 → 내 봉사 플랜 (6장) ---------- */
function PlanRow({ p, onChange, onRemove }) {
  const w = (CATS.find((c) => c.k === p.category) || { w: 1 }).w;
  return (
    <div className="card" style={{ marginTop: 8 }}>
      <div className="between"><span className="b">받고 싶은 도움</span>{onRemove && <button className="btn ghost sm" style={{ width: 'auto', minHeight: 32, padding: '0 10px' }} onClick={onRemove}>빼기</button>}</div>
      <div className="chips" style={{ marginTop: 8, flexWrap: 'wrap' }}>{CATS.map((c) => <button key={c.k} className={'chip ' + (p.category === c.k ? 'on' : '')} onClick={() => onChange({ ...p, category: c.k })}>{SHORT[c.k]}</button>)}</div>
      <div className="chips" style={{ marginTop: 8, flexWrap: 'wrap' }}>{FREQS.map(([v, t]) => <button key={v} className={'chip ' + (Number(p.perWeek) === v ? 'on' : '')} onClick={() => onChange({ ...p, perWeek: v })}>{t}</button>)}</div>
      <div className="stepper" style={{ marginTop: 8 }}><button aria-label="줄이기" onClick={() => onChange({ ...p, hours: Math.max(0.5, p.hours - 0.5) })}>−</button><div className="v">1회 {fmt(p.hours)}시간</div><button aria-label="늘리기" onClick={() => onChange({ ...p, hours: Math.min(8, p.hours + 0.5) })}>+</button></div>
      <div className="mute2" style={{ marginTop: 6 }}>가중치 ×{w.toFixed(1)}{w > 1 ? ` · 1회에 ${fmt(Math.round(p.hours * w * 2) / 2)}시간이 빠져요` : ''}</div>
    </div>
  );
}

export function CarePlan() {
  const [sp, setSp] = useSearchParams();
  const tab = sp.get('tab') === 'goal' ? 'goal' : 'now';
  const [plans, setPlans] = useState([{ category: '말동무·정서', perWeek: 1, hours: 1 }]);
  const [res, setRes] = useState(undefined);
  const seq = useRef(0);
  useEffect(() => {
    const my = ++seq.current;
    const t = setTimeout(async () => {
      try { const r = await post('/me/care-plan', { plans }, { quiet: true }); if (my === seq.current) setRes(r); } catch (e) { if (my === seq.current) setRes({ error: e.message }); }
    }, 250);
    return () => clearTimeout(t);
  }, [plans]);
  const set = (i, p) => setPlans(plans.map((x, j) => (j === i ? p : x)));
  const s = (res && res.summary) || {};
  const r = (res && res.result) || {};
  return (
    <Screen title="내 봉사 플랜" noTabs>
      <div className="chips" style={{ marginBottom: 10 }}>{[['now', '남은 시간으로 받기'], ['goal', '나중에 받고 싶은 것']].map(([k, t]) => <button key={k} className={'chip ' + (tab === k ? 'on' : '')} onClick={() => setSp(k === 'goal' ? { tab: 'goal' } : {}, { replace: true })}>{t}</button>)}</div>
      {tab === 'goal' ? <GoalPlan /> : <>
      <div className="hero">
        <div className="lbl">지금 쓸 수 있는 시간</div><div className="num">{fmt(s.usable)}<small>시간</small></div>
        <div className="sub">잔액 {fmt(s.balance)}시간 · 보류 {fmt(s.hold)}시간 · 직접 적립 {fmt(s.earnedSelf)}시간</div>
      </div>
      {res && s.canUseSelf === false && <div className="notice" style={{ marginTop: 10 }}>직접 적립한 시간이 {fmt(s.selfUseMin)}시간이 되어야 내 시간을 쓸 수 있어요. 앞으로 {fmt(s.needToUnlock)}시간 더 봉사하면 돼요. (선물받은 시간은 바로 쓸 수 있어요)</div>}
      {plans.map((p, i) => <PlanRow key={i} p={p} onChange={(x) => set(i, x)} onRemove={plans.length > 1 ? () => setPlans(plans.filter((_, j) => j !== i)) : null} />)}
      {plans.length < 4 && <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => setPlans([...plans, { category: '동행', perWeek: 0.5, hours: 2 }])}>+ 도움 더하기</button>}
      {res === undefined ? <Loading /> : res.error ? <Empty>{res.error}</Empty> : (
        <>
          <div className="card" style={{ marginTop: 12 }}>
            <div className="mute2">이대로 받으면</div>
            <div className="b" style={{ fontSize: 26, margin: '2px 0' }}>{r.weeks == null ? '-' : r.span}</div>
            <div className="mute2">한 주에 {fmt(r.weeklyCost)}시간 · 한 달에 약 {fmt(r.monthlyCost)}시간씩 줄어요</div>
            <div className="divider" />
            {(r.plans || []).map((x, i) => <div key={i} className="between small" style={{ padding: '4px 0' }}><span>{SHORT[x.category] || x.category} {x.freq} · {fmt(x.hours)}시간 × {Number(x.weight).toFixed(1)}</span><b>1회 {fmt(x.costPerSession)}시간</b></div>)}
            {res.ifUnlocked && res.ifUnlocked.weeks != null && <div className="mute2" style={{ marginTop: 8 }}>10시간 조건을 채우면 잔액 {fmt(s.available)}시간으로 {res.ifUnlocked.span} 받을 수 있어요.</div>}
          </div>
          <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />AI 추천<Mod n={4} /></div><div className="small">{res.text}</div><RuleNote source={res.source} /></div>
          <div className="sec-t">추천 조합</div>
          {(res.candidates || []).map((c, i) => (
            <div key={i} className={'opt ' + ((res.pick || []).includes(i) ? 'on' : '')} role="button" tabIndex={0} onClick={() => setPlans(c.plans.map((p) => ({ ...p })))}>
              <span className="rd" /><div className="grow"><div className="b">{c.plans.map((p) => `${SHORT[p.category] || p.category} ${freqTxt(p.perWeek)} ${fmt(p.hours)}시간`).join(' + ')}</div><div className="mute2">한 주 {fmt(c.weeklyCost)}시간 · {c.weeks == null ? '-' : c.span}</div></div>
            </div>
          ))}
          <div className="mute2" style={{ margin: '12px 2px' }}>기간은 시스템이 가중치(기본 1.0 · 수고 1.2 · 책임 1.5, 0.5 단위)를 넣어 계산해요. 실제 차감은 활동 시간에 따라 달라질 수 있어요.</div>
        </>
      )}
      </>}
    </Screen>
  );
}
