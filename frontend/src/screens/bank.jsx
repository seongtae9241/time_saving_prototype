import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, ReqRow, PostCard, Loading, Empty, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { fmt, dshort, ACTIVE_ST, PAST_ST } from '../lib/format.js';
import { needsMe } from './home.jsx';

/* ---------- 내 활동 (B-45) ---------- */
export function Act() {
  const [sp, setSp] = useSearchParams();
  const seg = sp.get('seg') || 'need';
  const [reqs] = useLoad(() => api('/requests?box=all'), []);
  const [posts] = useLoad(() => api('/posts/mine'), []);
  const list = reqs || [];
  const need = list.filter(needsMe);
  const going = list.filter((r) => ACTIVE_ST.includes(r.status) && !need.includes(r));
  const past = list.filter((r) => PAST_ST.includes(r.status));
  const mine = posts || [];
  const segs = [['need', '응답 필요', need.length], ['going', '진행 중', going.length], ['posts', '내 공고', mine.length], ['past', '지난 활동', past.length]];
  const done = past.filter((r) => r.status === 'DONE');
  const body = seg === 'need' ? need : seg === 'going' ? going : seg === 'past' ? past : null;
  return (
    <Screen title="내 활동" back={false}>
      <div className="chips" style={{ marginBottom: 6 }}>{segs.map(([k, t, n]) => <button key={k} className={'chip ' + (seg === k ? 'on' : '')} onClick={() => setSp({ seg: k }, { replace: true })}>{t} {n}</button>)}</div>
      {reqs === undefined ? <Loading /> : body ? (body.length ? body.map((r) => <ReqRow key={r.id} r={r} />) : <Empty>없어요</Empty>) : (mine.length ? mine.map((p) => <PostCard key={p.id} p={{ ...p, mine: true }} />) : <Empty>올린 공고가 없어요</Empty>)}
      {seg === 'past' && done.length > 0 && (
        <div className="card" style={{ marginTop: 10 }}>
          <div className="b">활동 결산</div>
          <div className="stat2" style={{ marginTop: 10 }}><div><b>{done.filter((r) => r.myRole === 'VOLUNTEER').length}회</b><span>봉사</span></div><div><b>{done.filter((r) => r.myRole !== 'VOLUNTEER').length}회</b><span>도움 받음</span></div></div>
        </div>
      )}
    </Screen>
  );
}

const LT = { EARN: '적립', HOLD: '보류', RELEASE: '보류 해제', USE: '사용', GIFT_OUT: '선물', GIFT_IN: '선물 받음', ADJUST: '조정', REFUND: '환불' };
function monthBars(rows) {
  const W2 = 320, H = 140, pt = 18, pb = 24, bw = 18;
  const max = Math.max(4, ...rows.flatMap((r) => [r[1], r[2]])) * 1.1;
  const cw = (W2 - 16) / Math.max(1, rows.length);
  return (
    <svg viewBox={`0 0 ${W2} ${H}`} className="chart" role="img" aria-label="월별 적립·사용">
      {rows.map(([m, a, b], i) => {
        const cx = 8 + cw * i + cw / 2, by = H - pb, ha = ((H - pt - pb) * a) / max, hb = ((H - pt - pb) * b) / max;
        const last = i === rows.length - 1;
        return (
          <g key={m}>
            <rect x={cx - bw - 2} y={by - ha} width={bw} height={ha} rx="5" fill="#3D6DF2" />
            <rect x={cx + 2} y={by - hb} width={bw} height={hb} rx="5" fill="#F59E4B" />
            <text x={cx - bw / 2 - 2} y={by - ha - 5} textAnchor="middle" fontSize="10" fill="#3D6DF2" fontWeight="700">{fmt(a)}</text>
            <text x={cx + bw / 2 + 2} y={by - hb - 5} textAnchor="middle" fontSize="10" fill="#C46A17" fontWeight="700">{fmt(b)}</text>
            <text x={cx} y={H - 6} textAnchor="middle" fontSize="11.5" fill={last ? '#1B1D22' : '#687080'} fontWeight={last ? 700 : 400}>{m}</text>
          </g>
        );
      })}
    </svg>
  );
}
const monthLabel = (m) => { const s = String(m); const p = s.split('-'); return p.length > 1 ? `${+p[1]}월` : s; };

/* ---------- 시간 통장 (A-06, B-30~34) ---------- */
export function Bank() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { sheet, closeSheet } = useUi();
  const [b] = useLoad(() => api('/bank'), []);
  const rule = () => sheet(() => (
    <>
      <h2>시간 통장 규칙</h2>
      <div className="ml">{[['적립', '실제 활동 시간 × 난이도 (기본 1.0 / 수고 1.2 / 책임 1.5), 0.5 단위'], ['사용', '10시간 이상 적립한 뒤부터 (선물받은 시간 제외)'], ['보류', '요청이 확정되면 예상 시간을 보류, 완료 후 실제 시간만큼 차감'], ['선물', '연결된 가족에게만. 양도·매매·상속 불가'], ['기관 대상', '받는 쪽 차감 없이 봉사자만 적립']].map(([a, c]) => <div key={a}><div className="grow"><b>{a}</b><span>{c}</span></div></div>)}</div>
      <button className="btn ghost" style={{ marginTop: 12 }} onClick={closeSheet}>닫기</button>
    </>
  ));
  if (b === undefined) return <Screen title="시간 통장" back={false}><Loading /></Screen>;
  const bk = b || { balance: 0, hold: 0, entries: [], monthly: [] };
  const entries = bk.entries || [];
  let bal = Number(bk.balance) || 0, last = '';
  const rows = entries.map((l) => {
    const d = dshort(l.createdAt).split(' ')[0];
    const head = d !== last ? <div className="pass-d">{d}</div> : null; last = d;
    const v = Number(l.amount) || 0;
    const isHold = l.type === 'HOLD' || l.type === 'RELEASE';
    const row = (
      <div key={l.id}>{head}
        <div className="pass-r"><div className="pc"><b>{l.label || LT[l.type]}</b><span>{LT[l.type] || l.type}{l.baseHours ? ` · ${l.baseHours}시간 × ${Number(l.weight || 1).toFixed(1)}` : ''}</span></div>
          <div className="pa"><b className={v > 0 ? 'p-in' : 'p-out'}>{isHold ? '' : v > 0 ? '+' : '−'}{fmt(Math.abs(v))}</b><span>{isHold ? '보류' : `잔액 ${fmt(bal)}`}</span></div></div>
      </div>
    );
    if (!isHold) bal -= v;
    return row;
  });
  return (
    <Screen title="시간 통장" back={false}>
      <div className="kpis" style={{ marginBottom: 10 }}>
        <div><b>{fmt(bk.totalEarned)}</b><span>전체 적립</span></div>
        <div><b>{fmt(bk.totalUsed)}</b><span>전체 사용</span></div>
        <div><b style={{ color: 'var(--brand)' }}>{fmt(bk.balance)}</b><span>남은 시간</span></div>
      </div>
      <div className="hero">
        <div className="bk-top"><span className="bk-name">시간 통장</span><span className="bk-no">{me ? `TS-${String(me.id).padStart(6, '0')}` : ''}</span></div>
        <div className="lbl">쓸 수 있는 시간</div><div className="num">{fmt(bk.available ?? bk.balance)}<small>시간</small></div>
        <div className="sub">잔액 {fmt(bk.balance)}시간 중 보류 {fmt(bk.hold)}시간 (확정된 요청) · 직접 적립 {fmt(bk.earnedSelf)}시간 · 선물받음 {fmt(bk.giftReceived)}시간</div>
        <div className="acts"><button onClick={() => nav('/gift')}>가족에게 선물</button><button onClick={rule}>이용 규칙</button></div>
      </div>
      {bk.canUseSelf === false && <div className="notice" style={{ marginTop: 12 }}>직접 적립한 시간이 10시간이 되면 도움을 받을 때 쓸 수 있어요. 선물받은 시간은 바로 쓸 수 있어요.</div>}
      <div className="card item click" style={{ marginTop: 12 }} role="button" tabIndex={0} onClick={() => nav('/bank/plan')}><div className="ic"><Ic n="cal" s={20} /></div><div className="grow"><div className="b">내 봉사 플랜</div><div className="mute2">남은 시간으로 얼마나 도움을 받을 수 있는지 계산해요</div></div>›</div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="between"><span className="b">월별 결산</span><span className="legend"><i style={{ background: '#3D6DF2' }} />적립<i style={{ background: '#F59E4B', marginLeft: 8 }} />사용</span></div>
        {(bk.monthly || []).length ? monthBars(bk.monthly.slice(-4).map((m) => [monthLabel(m.month), Number(m.in) || 0, Number(m.out) || 0])) : <Empty>아직 결산할 내역이 없어요</Empty>}
        <div className="between mute2" style={{ marginTop: 4 }}><span>이번 달 적립 +{fmt(bk.monthIn)}</span><span>이번 달 사용 −{fmt(bk.monthOut)}</span></div>
      </div>
      <div className="sec-t">내역</div>
      {entries.length ? <div className="pass">{rows}</div> : <Empty>아직 내역이 없어요</Empty>}
    </Screen>
  );
}

/* ---------- 가족에게 선물 (B-32) ---------- */
export function Gift() {
  const nav = useNavigate();
  const { me, refresh } = useAuth();
  const { toast, confirm } = useUi();
  const [fam] = useLoad(() => api('/family'), []);
  const linked = (fam || []).filter((f) => f.status === 'ACCEPTED');
  const [to, setTo] = useState(null);
  const [n, setN] = useState(1);
  const [message, setMessage] = useState('');
  const [busy, run] = useBusy();
  const sel = to || (linked[0] && linked[0].user.id);
  const max = me ? Number(me.balance) || 0 : 0;
  return (
    <Screen title="가족에게 선물" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>연결된 가족에게만 보낼 수 있어요. 선물 외에는 양도·매매·상속이 안 돼요.</p>
      <label className="f">받는 가족</label>
      {fam === undefined ? <Loading /> : linked.length ? linked.map((f) => (
        <div key={f.id} className={'opt ' + (sel === f.user.id ? 'on' : '')} role="button" tabIndex={0} onClick={() => setTo(f.user.id)}><span className="rd" /><div className="grow"><div className="b">{f.relation ? `${f.relation} ` : ''}{f.user.name}</div><div className="mute2">연결됨</div></div></div>
      )) : <Empty>연결된 가족이 없어요</Empty>}
      <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => nav('/my/family')}>+ 가족 연결 신청</button>
      <label className="f">보낼 시간</label>
      <div className="stepper"><button onClick={() => setN(Math.max(0.5, n - 0.5))} aria-label="줄이기">−</button><div className="v">{fmt(n)}시간</div><button onClick={() => setN(Math.min(Math.max(0.5, max), n + 0.5))} aria-label="늘리기">+</button></div>
      <div className="mute2" style={{ margin: '8px 2px' }}>지금 보낼 수 있는 시간 {fmt(max)}시간 · 선물받은 시간은 받은 분의 '10시간 이상 적립 후 사용' 조건에 포함되지 않아요.</div>
      <label className="f">한마디 (선택)</label><input className="in" value={message} onChange={(e) => setMessage(e.target.value)} placeholder="예) 엄마, 병원 갈 때 써요" />
      <button className="btn big-cta" style={{ marginTop: 14 }} disabled={!sel || busy} onClick={async () => {
        const f = linked.find((x) => x.user.id === sel);
        if (!(await confirm(`${f.user.name} 님께 ${fmt(n)}시간을 선물할까요?`, '보낸 시간은 되돌릴 수 없어요.', '선물하기'))) return;
        run(async () => { await post('/bank/gift', { toUserId: sel, amount: n, message }); await refresh(); toast('선물했어요'); nav('/bank', { replace: true }); });
      }}>선물하기</button>
    </Screen>
  );
}
