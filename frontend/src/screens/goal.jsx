// 7.13 LLM 추가 B-5: 시간 통장 → 내 봉사 플랜 → "나중에 받고 싶은 것"(거꾸로 플랜)
// 숫자(필요 총 시간·월 목표)는 서버 코드가 계산하고, AI 는 내 활동 기록에 맞춘 추천 문장과 공고 고르기만 한다.
import { useState } from 'react';
import { Spk, Mod, RuleNote, AiLoading, PostCard, useBusy } from '../ui/bits.jsx';
import { useAuth } from '../ui/ctx.jsx';
import { post } from '../api.js';
import { CATS, SHORT, fmt } from '../lib/format.js';
import '../styles/activity.css';

const FREQS = [[0.25, '월 1회'], [0.5, '월 2회'], [1, '주 1회'], [2, '주 2회'], [3, '주 3회']];

function Step({ label, value, onMinus, onPlus }) {
  return <div className="stepper" style={{ marginTop: 6 }}><button type="button" aria-label={`${label} 줄이기`} onClick={onMinus}>−</button><div className="v">{value}</div><button type="button" aria-label={`${label} 늘리기`} onClick={onPlus}>+</button></div>;
}

export function GoalPlan() {
  const { me } = useAuth();
  const canAge = !!(me && me.ageGroup && /\d/.test(me.ageGroup));
  const baseAge = canAge ? parseInt(String(me.ageGroup).match(/\d+/)[0], 10) + 5 : null;
  const [f, setF] = useState({ mode: 'later', yearsLater: 10, startAge: baseAge ? baseAge + 10 : 75, category: '동행', perWeek: 1, hoursPer: 2, years: 5 });
  const [res, setRes] = useState(null);
  const [busy, run] = useBusy();
  const set = (k, v) => { setF({ ...f, [k]: v }); setRes(null); };
  const calc = () => run(async () => {
    setRes(undefined);
    const body = { category: f.category, perWeek: f.perWeek, hoursPer: f.hoursPer, years: f.years, ...(f.mode === 'age' ? { startAge: f.startAge } : { yearsLater: f.yearsLater }) };
    try { setRes(await post('/me/goal-plan', body, { quiet: true })); } catch (e) { setRes({ error: e.message }); }
  });
  const p = (res && res.plan) || {};
  return (
    <>
      <p className="su-p" style={{ marginTop: 4 }}>나중에 받고 싶은 도움을 정하면, 지금부터 한 달에 몇 시간씩 쌓으면 되는지 거꾸로 계산해요.</p>
      <div className="card" style={{ marginTop: 8 }}>
        <div className="b">언제부터</div>
        <div className="chips" style={{ marginTop: 8 }}>
          <button type="button" className={'chip ' + (f.mode === 'later' ? 'on' : '')} onClick={() => set('mode', 'later')}>몇 년 뒤부터</button>
          {canAge && <button type="button" className={'chip ' + (f.mode === 'age' ? 'on' : '')} onClick={() => set('mode', 'age')}>몇 살부터</button>}
        </div>
        {f.mode === 'later'
          ? <Step label="몇 년 뒤" value={`${f.yearsLater}년 뒤부터`} onMinus={() => set('yearsLater', Math.max(1, f.yearsLater - 1))} onPlus={() => set('yearsLater', Math.min(40, f.yearsLater + 1))} />
          : <Step label="나이" value={`${f.startAge}살부터`} onMinus={() => set('startAge', Math.max(baseAge + 1, f.startAge - 1))} onPlus={() => set('startAge', Math.min(100, f.startAge + 1))} />}
        {f.mode === 'age' && <div className="mute2" style={{ marginTop: 4 }}>지금 나이는 프로필 나이대({me.ageGroup})로 어림해요</div>}
        <div className="b" style={{ marginTop: 14 }}>받고 싶은 도움</div>
        <div className="chips" style={{ marginTop: 8, flexWrap: 'wrap' }}>{CATS.map((c) => <button type="button" key={c.k} className={'chip ' + (f.category === c.k ? 'on' : '')} onClick={() => set('category', c.k)}>{SHORT[c.k]}</button>)}</div>
        <div className="chips" style={{ marginTop: 8, flexWrap: 'wrap' }}>{FREQS.map(([v, t]) => <button type="button" key={v} className={'chip ' + (f.perWeek === v ? 'on' : '')} onClick={() => set('perWeek', v)}>{t}</button>)}</div>
        <Step label="1회 시간" value={`1회 ${fmt(f.hoursPer)}시간`} onMinus={() => set('hoursPer', Math.max(0.5, f.hoursPer - 0.5))} onPlus={() => set('hoursPer', Math.min(4, f.hoursPer + 0.5))} />
        <Step label="받는 기간" value={`${f.years}년 동안`} onMinus={() => set('years', Math.max(1, f.years - 1))} onPlus={() => set('years', Math.min(30, f.years + 1))} />
      </div>
      <button className="btn big-cta" style={{ marginTop: 12 }} disabled={busy} onClick={calc}>얼마나 쌓아야 할까요?</button>
      {res === undefined && <div style={{ marginTop: 12 }}><AiLoading label="계산하고 추천을 고르는 중…" /></div>}
      {res && res.error && <div className="notice" style={{ marginTop: 12 }}>{res.error}</div>}
      {res && res.plan && (
        <div className="goal-out">
          <div className="card" style={{ marginTop: 12 }}>
            <div className="mute2">지금부터 한 달에</div>
            <div className="big">{fmt(p.monthlyTarget)}시간씩</div>
            <div className="mute2">한 주에 약 {fmt(p.weeklyTarget)}시간 · {p.months}개월 동안</div>
            <div className="divider" />
            <div className="between small" style={{ padding: '3px 0' }}><span>받고 싶은 도움</span><b>{SHORT[p.category] || p.category} {p.freq} {fmt(p.hoursPer)}시간 · {p.years}년</b></div>
            <div className="between small" style={{ padding: '3px 0' }}><span>1회에 빠지는 시간</span><b>{fmt(p.perSession)}시간 (×{Number(p.weight).toFixed(1)})</b></div>
            <div className="between small" style={{ padding: '3px 0' }}><span>모두 필요한 시간</span><b>{fmt(p.totalNeeded)}시간</b></div>
            <div className="between small" style={{ padding: '3px 0' }}><span>지금 있는 시간</span><b>{fmt(p.have)}시간</b></div>
            <div className="between small" style={{ padding: '3px 0' }}><span>더 쌓을 시간</span><b className="cr plus">{fmt(p.remaining)}시간</b></div>
          </div>
          <div className="ai" style={{ marginTop: 10 }}>
            <div className="ah"><Spk />이번 달 이렇게 쌓아 보세요<Mod n={4} /></div>
            <div className="small">{res.text}</div>
            {(res.tips || []).length > 0 && <ul className="small">{res.tips.map((t, i) => <li key={i}>{t}</li>)}</ul>}
            <RuleNote source={res.source} />
          </div>
          {(res.posts || []).length > 0 && <><div className="sec-t">근처 봉사 공고</div>{res.posts.map((x) => <PostCard key={x.id} p={x} />)}</>}
          <div className="mute2" style={{ margin: '12px 2px' }}>필요한 시간은 가중치(기본 1.0 · 수고 1.2 · 책임 1.5, 0.5 단위)를 넣어 시스템이 계산해요. 제도·가중치가 바뀌면 달라질 수 있어요.</div>
        </div>
      )}
    </>
  );
}
