import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Spk, Mod, CatIc, Loading, Empty, RuleNote, useLoad } from '../ui/bits.jsx';
import { api } from '../api.js';
import { SHORT, fmt } from '../lib/format.js';
import { hmTxt } from '../ui/proto.jsx';

const num = (v) => { const n = Number(v) || 0; return Number.isInteger(n) ? String(n) : n.toFixed(1); };

/* ---------- 기능 U-24 시간 플랜: 나이·잔액·적립 속도 + 비슷한 사람들의 실제 사용 기록 → 몇 년간 어떤 도움, 적립 목표 ---------- */
export function TimePlan() {
  const nav = useNavigate();
  const [q, setQ] = useState({}); // {age, startAge} — 바꿔서 다시 계산(저장하지 않음)
  const qs = [q.age != null ? `age=${q.age}` : '', q.startAge != null ? `startAge=${q.startAge}` : ''].filter(Boolean).join('&');
  const [d] = useLoad(() => api(`/me/time-plan${qs ? `?${qs}` : ''}`), [qs]);
  if (d === undefined && !qs) return <Screen title="시간 플랜" noTabs><Loading /></Screen>;
  if (!d) return <Screen title="시간 플랜" noTabs>{d === undefined ? <Loading /> : <Empty>시간 플랜을 불러오지 못했어요</Empty>}</Screen>;
  const pat = d.pattern || {};
  const rows = d.rows || [];
  const setAge = (n) => { const age = Math.max(15, Math.min(90, d.age + n)); setQ({ age, startAge: Math.max(age, d.startAge) }); };
  const setStart = (n) => setQ({ age: d.age, startAge: Math.max(d.age, Math.min(95, d.startAge + n)) });
  return (
    <Screen title="시간 플랜" noTabs>
      <div className="hero">
        <div className="lbl">{d.saveYears > 0 ? `${d.startAge}세부터 도움을 받는다면` : '지금부터 도움을 받는다면'}</div>
        <div className="num">{d.years == null ? '-' : d.years < 1 ? <>약 {d.helpMonths}<small>개월 동안</small></> : <>약 {num(d.years)}<small>년 동안</small></>}</div>
        <div className="sub">{d.endAge != null ? `${d.endAge}세까지 · ` : ''}한 달 {num(d.useMonthly)}시간씩 쓰는 것으로 계산했어요</div>
      </div>
      {d.notice && <div className="notice" style={{ marginTop: 10 }} role="note">{d.notice}. 이웃들의 기록이 쌓이면 실제 사용 기록으로 바꿔 계산해요.</div>}
      <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />앞으로 받을 수 있는 도움<Mod n={4} /></div><div className="small">{d.text}</div><RuleNote source={d.source} /></div>

      <div className="sec-t"><span>적립 목표</span><span className="mute2" style={{ fontWeight: 500 }}>{d.targetAge}세까지 받는 기준</span></div>
      <div className="card">
        <div className="between"><span className="muted">지금 적립 속도</span><b>한 달 {num(d.earnRate)}시간</b></div>
        <div className="between" style={{ marginTop: 8 }}><span className="muted">목표</span><b className={d.onTrack ? 'cr plus' : ''}>{d.onTrack ? '지금 속도면 충분해요' : `한 달 ${num(d.goalMonthly)}시간`}</b></div>
        {!d.onTrack && <div className="between" style={{ marginTop: 8 }}><span className="muted">더 모을 시간</span><b>한 달 +{num(d.goalExtra)}시간</b></div>}
        <div className="divider" />
        <div className="small"><Spk /> {d.goalText}</div>
        <button className="btn soft sm" style={{ marginTop: 12 }} onClick={() => nav('/planner?seg=rec')}>추천 봉사 보러 가기</button>
      </div>

      <div className="sec-t"><span>어떤 도움을 얼마나</span><span className="mute2" style={{ fontWeight: 500 }}>{pat.usedDefault ? '일반적인 가정' : pat.label}</span></div>
      <div className="card">
        {rows.length ? rows.map((x, i) => (
          <div key={x.category} className="row" style={{ gap: 12, padding: '8px 0', borderTop: i ? '1px solid var(--line)' : 0 }}>
            <CatIc k={x.category} size={40} />
            <div className="grow"><div className="b">{SHORT[x.category] || x.category} <span className="mute2" style={{ fontWeight: 500 }}>한 달 {num(x.hoursPerMonth)}시간</span></div>
              <div className="mute2">{num(x.hoursPerMonth)}시간 × {Number(x.weight).toFixed(1)} = 한 달 {num(x.costPerMonth)}시간 차감{x.totalHours != null ? ` · 받는 동안 모두 ${num(x.totalHours)}시간` : ''}</div></div>
          </div>
        )) : <Empty>계산할 도움이 없어요</Empty>}
        <div className="mute2" style={{ marginTop: 8 }}>{pat.usedDefault
          ? `비슷한 이웃의 기록이 ${pat.minPeers}명보다 적어(지금 ${pat.peers}명) 동행·장보기·말동무를 조금씩 받는 일반적인 가정을 썼어요.`
          : `${pat.label}이 최근 ${pat.windowMonths}개월 동안 실제로 받은 도움의 한 달 평균이에요. 누가 얼마나 받았는지는 보이지 않아요.`}</div>
      </div>

      <div className="sec-t"><span>계산에 쓴 내 정보</span><span className="mute2" style={{ fontWeight: 500 }}>바꿔서 다시 볼 수 있어요</span></div>
      <div className="sum">
        <div className="between"><span className="muted">지금 나이{d.ageFrom === 'ageGroup' ? ` (${d.ageGroup} 기준 어림)` : ''}</span>
          <span className="row" style={{ gap: 8 }}><button className="btn ghost sm" style={{ width: 44, minHeight: 44, padding: 0 }} aria-label="나이 줄이기" onClick={() => setAge(-1)}>−</button><b>{d.age}세</b><button className="btn ghost sm" style={{ width: 44, minHeight: 44, padding: 0 }} aria-label="나이 늘리기" onClick={() => setAge(1)}>+</button></span></div>
        <div className="between"><span className="muted">도움 받기 시작</span>
          <span className="row" style={{ gap: 8 }}><button className="btn ghost sm" style={{ width: 44, minHeight: 44, padding: 0 }} aria-label="시작 나이 줄이기" onClick={() => setStart(-1)}>−</button><b>{d.startAge}세</b><button className="btn ghost sm" style={{ width: 44, minHeight: 44, padding: 0 }} aria-label="시작 나이 늘리기" onClick={() => setStart(1)}>+</button></span></div>
        <div className="between"><span className="muted">쓸 수 있는 시간 (지금 통장)</span><b>{hmTxt(d.balance)}</b></div>
        <div className="between"><span className="muted">적립 속도{d.earnMonths ? ` (최근 ${d.earnMonths}개월 평균)` : ''}</span><b>한 달 {num(d.earnRate)}시간</b></div>
        {d.saveYears > 0 && <div className="between"><span className="muted">{d.startAge}세에 모이는 시간</span><b>{num(d.saved)}시간</b></div>}
        {d.nowYears != null && d.saveYears > 0 && <div className="between"><span className="muted">지금 통장만으로 받으면</span><b>약 {num(d.nowYears)}년</b></div>}
      </div>
      <div className="mute2" style={{ margin: '12px 2px' }}>숫자는 시스템이 계산하고, 설명과 목표 문구만 AI가 써요. {d.rules}</div>
      <button className="btn ghost" onClick={() => nav('/bank/plan')}>받고 싶은 도움을 직접 골라 계산하기</button>
    </Screen>
  );
}

/* ---------- 기능 U-25 월별 리포트: 이번 달 봉사·도움 횟수, 많이 한 도움, 지난달 대비 변화 ---------- */
const ymShift = (ym, n) => { const [y, m] = ym.split('-').map(Number); const t = new Date(y, m - 1 + n, 1); return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}`; };
const diff = (n, unit) => (n === 0 ? '지난달과 같아요' : `지난달보다 ${num(Math.abs(n))}${unit} ${n > 0 ? '늘었어요' : '줄었어요'}`);

function SideCard({ title, color, s, prev, dCount, dHours }) {
  return (
    <div className="card" style={{ marginTop: 10 }}>
      <div className="between"><span className="b" style={{ color }}>{title}</span><span className="mute2">지난달 {prev.count}회 · {num(prev.hours)}시간</span></div>
      <div className="stat2" style={{ marginTop: 10 }}>
        <div><b>{s.count}회</b><span>{diff(dCount, '회')}</span></div>
        <div><b>{num(s.hours)}시간</b><span>{diff(dHours, '시간')}</span></div>
      </div>
      {(s.byCategory || []).length > 0 ? (
        <div style={{ marginTop: 10 }}>
          {s.byCategory.map((c, i) => <div key={c.category} className="between small" style={{ padding: '4px 0' }}><span>{i === 0 ? '★ ' : ''}{c.category}</span><b>{c.count}회 · {num(c.hours)}시간</b></div>)}
        </div>
      ) : <div className="mute2" style={{ marginTop: 10 }}>이 달에 완료된 활동이 없어요</div>}
    </div>
  );
}

export function MonthReport() {
  const [month, setMonth] = useState('');
  const [d] = useLoad(() => api(`/me/month-report${month ? `?month=${month}` : ''}`), [month]);
  if (!d) return <Screen title="월별 리포트" noTabs>{d === undefined ? <Loading /> : <Empty>리포트를 불러오지 못했어요</Empty>}</Screen>;
  const [Y, M] = d.month.split('-').map(Number);
  const g = d.give || {}, r = d.receive || {}, pv = d.prev || { give: {}, receive: {} }, ch = d.change || {};
  return (
    <Screen title="월별 리포트" noTabs>
      <div className="calc" style={{ paddingBottom: 12 }}>
        <div className="between"><button className="cal-nav" aria-label="이전 달" onClick={() => setMonth(ymShift(d.month, -1))}>‹</button><div className="cal-m">{Y}년 {M}월</div><button className="cal-nav" aria-label="다음 달" disabled={d.isCurrent} style={d.isCurrent ? { opacity: 0.3 } : undefined} onClick={() => setMonth(ymShift(d.month, 1))}>›</button></div>
      </div>
      <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />{M}월 한눈에<Mod n={5} /></div><div className="small">{d.text}</div><RuleNote source={d.source} /></div>
      <div className="kpis"><div><b>{g.count || 0}회</b><span>봉사</span></div><div><b>{r.count || 0}회</b><span>도움 받음</span></div><div><b>{g.top ? (SHORT[g.top] || g.top) : r.top ? (SHORT[r.top] || r.top) : '-'}</b><span>{g.top || !r.top ? '많이 한 도움' : '많이 받은 도움'}</span></div></div>
      <SideCard title="봉사한 활동" color="#3F8A75" s={g} prev={pv.give} dCount={ch.giveCount || 0} dHours={ch.giveHours || 0} />
      <SideCard title="도움 받은 활동" color="#B55E1C" s={r} prev={pv.receive} dCount={ch.receiveCount || 0} dHours={ch.receiveHours || 0} />
      <div className="sum" style={{ marginTop: 10 }}>
        <div className="between"><span className="muted">이 달 봉사로 번 시간</span><b className="cr plus">+{fmt(g.earned)}시간</b></div>
        <div className="between"><span className="muted">이 달 도움 받으며 쓴 시간</span><b className="cr minus">−{fmt(r.used)}시간</b></div>
      </div>
      <div className="mute2" style={{ margin: '12px 2px' }}>완료된 활동만 세요(활동한 날짜 기준). 횟수·시간·변화는 시스템이 세고, 요약 문장만 AI가 써요.</div>
    </Screen>
  );
}
