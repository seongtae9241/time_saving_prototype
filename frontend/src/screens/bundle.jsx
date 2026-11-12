import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Cats, Empty, RuleNote, AiLoading, postCats, postWho } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { post } from '../api.js';
import { hh, fmt, dlabel, addDays, todayISO } from '../lib/format.js';
import { useLocationAsk } from '../ui/location.jsx';

/* ---------- 빈 시간 묶음 추천 (기능 U-12) ----------
   봉사하는 사용자가 날짜와 가능한 시간 범위를 넣으면, 시간이 겹치지 않고 이동이 짧은 도움 요청 공고 2~3건을 묶어 추천한다.
   묶음 고르기(시간·거리 계산)는 서버 코드가 하고 AI는 "왜 이 묶음인지" 설명만 쓴다(키가 없으면 규칙 문장).
   "한 번에 제안"은 공고마다 봉사 제안을 차례로 보낸다. 일부가 안 되면 어느 것이 됐고 안 됐는지 보여 준다. */
const HOURS = Array.from({ length: 25 }, (_, i) => 8 + i * 0.5); // 8:00 ~ 20:00

export function BundleLink({ style }) {
  const nav = useNavigate();
  return (
    <div className="tip" style={{ marginTop: 12, padding: '12px 16px', ...style }}>
      <div className="grow small"><Spk /> 빈 시간에 가까운 봉사 2~3건을 이어서 할 수 있게 묶어 드려요</div>
      <button className="btn soft sm" style={{ width: 'auto', padding: '0 12px', minHeight: 36 }} onClick={() => nav('/bundle')}>묶음 추천</button>
    </div>
  );
}

function BundleCard({ b, n, onPropose, busy, result }) {
  const nav = useNavigate();
  const sent = !!result;
  return (
    <div className="ai" style={{ marginTop: 12 }}>
      <div className="ah"><Spk />묶음 {n} · {b.count}건 이어서<Mod n={4} /></div>
      <div className="small" style={{ marginTop: 2 }}>{b.reason}</div>
      <div className="stat2" style={{ marginTop: 10 }}>
        <div><b>{hh(b.startHour)}~{hh(b.endHour)}</b><span>활동 {fmt(b.totalHours)}시간 · 사이 빈 시간 {b.idleMin}분</span></div>
        <div><b>+{fmt(b.totalEarn)}시간</b><span>예상 적립 · 건 사이 이동 {b.totalKm}km</span></div>
      </div>
      {b.items.map((it, i) => {
        const p = it.post;
        const w = postWho(p);
        const res = result && result.results.find((r) => r.postId === it.postId);
        return (
          <div key={it.postId}>
            {i > 0 && <div className="mute2" style={{ margin: '8px 0 0 6px' }}><Ic n="pin" s={13} /> {it.travelKm > 0 ? `${it.travelKm}km 이동 (걸어서 약 ${it.travelMin}분)` : '같은 동네'} · {it.waitMin}분 여유</div>}
            <div className="card item click" style={{ marginTop: 8 }} role="button" tabIndex={0} onClick={() => nav(`/post/${p.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/post/${p.id}`); }}>
              <div className="grow">
                <div className="b">{hh(it.startHour)}~{hh(it.endHour)} · {p.title}</div>
                <div className="mute2">{w.name} · {p.area} · 예상 +{fmt(it.estEarn)}시간</div>
                <Cats list={postCats(p)} style={{ marginTop: 6 }} />
                {res && (res.ok
                  ? <div className="small" style={{ marginTop: 6, color: '#1F9D63', fontWeight: 700 }}>✓ 제안을 보냈어요 · 24시간 안에 수락·거절</div>
                  : <div className="notice" role="alert" style={{ marginTop: 6 }}>보내지 못했어요: {res.error}</div>)}
              </div>
              {res && res.ok ? <button className="btn soft sm" style={{ width: 'auto', padding: '0 10px' }} onClick={(e) => { e.stopPropagation(); nav(`/req/${res.requestId}`); }}>보기</button> : <span className="mute2">›</span>}
            </div>
          </div>
        );
      })}
      <details style={{ marginTop: 10 }}><summary className="mute2" style={{ cursor: 'pointer' }}>코드가 계산한 근거 보기</summary><ul className="small muted" style={{ margin: '6px 0 0', paddingLeft: 18 }}>{(b.facts || []).map((f) => <li key={f}>{f}</li>)}</ul></details>
      <RuleNote source={b.source} />
      {sent
        ? <div className="notice" style={{ marginTop: 10 }}>{result.message}</div>
        : <button className="btn" style={{ marginTop: 12 }} disabled={busy} onClick={() => onPropose(b)}>{busy ? '보내는 중…' : `이 ${b.count}건에 한 번에 제안하기`}</button>}
    </div>
  );
}

export function Bundle() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast, confirm } = useUi();
  const [sp] = useSearchParams();
  const [date, setDate] = useState(sp.get('date') || addDays(1));
  const [from, setFrom] = useState(Number(sp.get('from')) || 10);
  const [to, setTo] = useState(Number(sp.get('to')) || 16);
  const [pos, setPos] = useState(null);
  const [res, setRes] = useState(null);
  const [loading, setLoading] = useState(false);
  const [busyKey, setBusyKey] = useState(null);
  const [sent, setSent] = useState({}); // 묶음 key → 한 번에 제안 결과
  const askLocation = useLocationAsk();
  const days = Array.from({ length: 10 }, (_, i) => addDays(i));
  if (date && !days.includes(date)) days.unshift(date);
  const verified = me && me.idStatus === 'APPROVED';
  const find = async (p = pos) => {
    if (to - from < 1.5) return toast('가능한 시간을 1시간 30분 이상으로 잡아 주세요');
    setLoading(true); setSent({});
    try { setRes(await post('/bundles/recommend', { date, fromHour: from, toHour: to, ...(p ? { lat: p.lat, lng: p.lng } : {}) })); } catch { setRes(null); }
    setLoading(false);
  };
  const useHere = async () => {
    const got = await askLocation('묶음 추천');
    if (!got) return;
    setPos(got);
    if (res) find(got);
  };
  const propose = async (b) => {
    if (!verified) { toast('봉사는 신분증 인증이 끝난 뒤 할 수 있어요'); return nav('/my/verify'); }
    const lines = b.items.map((it) => `${hh(it.startHour)}~${hh(it.endHour)} ${it.post.title}`).join(' / ');
    if (!(await confirm(`${b.count}건에 한 번에 제안할까요?`, `${dlabel(res.date)} · ${lines}. 공고마다 봉사 제안이 따로 가고, 상대가 24시간 안에 수락·거절해요.`, '한 번에 제안'))) return;
    setBusyKey(b.key);
    try {
      const r = await post('/bundles/propose', { items: b.items.map((it) => ({ postId: it.postId, startHour: it.startHour, hours: it.hours })) });
      setSent((s) => ({ ...s, [b.key]: r }));
      toast(r.message);
    } catch { /* 토스트 */ }
    setBusyKey(null);
  };
  return (
    <Screen title="빈 시간 묶음 추천" noTabs>
      <div className="mute2" style={{ margin: '-2px 0 6px' }}>봉사할 수 있는 날짜와 시간을 고르면, 그 안에서 <b>시간이 겹치지 않고 가까운</b> 도움 요청 2~3건을 묶어 드려요.</div>
      {!verified && <div className="notice">봉사 제안은 신분증 인증을 마친 뒤 보낼 수 있어요. <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700 }} onClick={() => nav('/my/verify')}>인증하러 가기 ›</a></div>}
      <label className="f">날짜</label>
      <div className="tags">{days.map((d) => <button key={d} className={'chip ' + (date === d ? 'on' : '')} onClick={() => setDate(d)}>{d === todayISO() ? '오늘' : dlabel(d)}</button>)}</div>
      <input className="in" type="date" style={{ marginTop: 10 }} min={todayISO()} value={date} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="다른 날짜 고르기" />
      <label className="f">가능한 시간</label>
      <div className="row">
        <select className="in grow" aria-label="시작 시각" value={from} onChange={(e) => { const s = +e.target.value; setFrom(s); if (to < s + 1.5) setTo(Math.min(20, s + 1.5)); }}>{HOURS.filter((h) => h <= 18.5).map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select>
        <span>~</span>
        <select className="in grow" aria-label="끝 시각" value={to} onChange={(e) => setTo(+e.target.value)}>{HOURS.filter((h) => h >= from + 1.5).map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select>
      </div>
      <div className="mute2" style={{ marginTop: 8 }}>
        출발은 {pos ? '지금 계신 곳' : `내 동네(${(me && me.area) || '프로필'})`} 기준이에요.{' '}
        {pos ? <button type="button" className="linkbtn" onClick={() => { setPos(null); if (res) find(null); }}>내 동네 기준으로</button> : <button type="button" className="linkbtn" onClick={useHere}>지금 위치로 바꾸기</button>}
        {pos && ' 위치는 이번 추천에만 쓰고 저장하지 않아요.'}
      </div>
      <button className="btn big-cta" style={{ marginTop: 14 }} disabled={loading} onClick={() => find()}>{loading ? '묶는 중…' : '묶음 추천 받기'}</button>

      {loading && <div style={{ marginTop: 12 }}><AiLoading label="겹치지 않는 묶음을 찾는 중…" /></div>}
      {!loading && res && (
        <>
          <div className="sec-t"><span>{dlabel(res.date)} {hh(res.fromHour)}~{hh(res.toHour)} · 묶음 <span style={{ color: 'var(--brand)' }}>{res.bundles.length}</span>개</span></div>
          <div className="mute2" style={{ margin: '-4px 0 4px' }}>이 시간에 할 수 있는 공고 {res.candidateCount}건에서 골랐어요. {res.rule}</div>
          {res.bundles.length ? res.bundles.map((b, i) => <BundleCard key={b.key} b={b} n={i + 1} busy={busyKey === b.key} result={sent[b.key]} onPropose={propose} />)
            : <><Empty>{res.message}</Empty><button className="btn soft" onClick={() => nav('/board?type=NEED')}>봉사 요청 공고 전체 보기</button></>}
          {res.bundles.length > 0 && <div className="notice" style={{ marginTop: 12 }}>추천은 참고용이에요. 제안을 보낼지는 직접 정하고, 수락은 도움 받는 분(또는 담당자)이 해요. 공고 1개에는 요청 1건만 수락돼요.</div>}
        </>
      )}
    </Screen>
  );
}
