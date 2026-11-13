import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, ReqRow, PostCard, Loading, Empty, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { fmt, ACTIVE_ST, PAST_ST } from '../lib/format.js';
import { needsMe } from './home.jsx';
import { MonthNote } from '../ui/ActivityAi.jsx'; // 7.13 B-6
import { DIc, CalIcon, HmBig, hmTxt, useMode } from '../ui/proto.jsx';
import { MyGroups } from './group.jsx'; // 기능 U-10: 내 활동에 단체 봉사

/* ---------- 내 활동 (B-46): 마이 안 화면. 도움 받은 활동 / 봉사한 활동 토글 + 월별 횟수 요약 (프로토타입 2026-11-12) ---------- */
export function Act() {
  const [sp, setSp] = useSearchParams();
  const seg = sp.get('seg') || 'need';
  const { setMode, give } = useMode();
  const [reqs] = useLoad(() => api('/requests?box=all'), []);
  const [posts] = useLoad(() => api('/posts/mine'), []);
  const [sum] = useLoad(() => api('/my/activity-summary?months=6', { quiet: true }).catch(() => null), []);
  const all = reqs || [];
  // 헤더 모드(도움 받기 / 봉사하기)와 같이 움직인다. 여기서 바꿔도 헤더가 같이 바뀐다.
  const side = (r) => (r.myRole === 'VOLUNTEER') === give;
  const list = all.filter(side);
  const need = list.filter(needsMe);
  const going = list.filter((r) => ACTIVE_ST.includes(r.status) && !need.includes(r));
  const past = list.filter((r) => PAST_ST.includes(r.status));
  const mine = (posts || []).filter((p) => (p.type === 'OFFER') === give);
  const segs = [['need', '응답 필요', need.length], ['going', '진행 중', going.length], ['posts', '내 공고', mine.length], ['past', '지난 활동', past.length]];
  const done = past.filter((r) => r.status === 'DONE');
  const body = seg === 'need' ? need : seg === 'going' ? going : seg === 'past' ? past : null;
  const months = (sum && sum.months) || [];
  const who = (g) => { setMode(g ? 'give' : 'recv'); setSp({ seg: 'need' }, { replace: true }); };
  return (
    <Screen title="내 활동" tab="/my">
      <div className="chips" role="tablist" aria-label="도움 받은 활동과 봉사한 활동" style={{ marginBottom: 10 }}>
        <button role="tab" aria-selected={!give} className={'chip ' + (!give ? 'on' : '')} onClick={() => who(false)}>도움 받은 활동</button>
        <button role="tab" aria-selected={give} className={'chip ' + (give ? 'on' : '')} onClick={() => who(true)}>봉사한 활동</button>
      </div>
      <div className="chips" style={{ marginBottom: 6 }}>{segs.map(([k, t, n]) => <button key={k} className={'chip ' + (seg === k ? 'on' : '')} onClick={() => setSp({ seg: k }, { replace: true })}>{t} {n}</button>)}</div>
      {reqs === undefined ? <Loading /> : body ? (body.length ? body.map((r) => <ReqRow key={r.id} r={r} />) : <Empty>없어요</Empty>) : (mine.length ? mine.map((p) => <PostCard key={p.id} p={{ ...p, mine: true }} />) : <Empty>올린 {give ? '도움 가능' : '봉사 요청'} 공고가 없어요</Empty>)}
      {give && <MyGroups />}
      {months.length > 0 && (
        <div className="card" style={{ marginTop: 14 }}>
          <div className="between"><span className="b">월별 횟수 요약</span><span className="legend"><i style={{ background: '#6DB3A0' }} />봉사<i style={{ background: '#6F9CF0', marginLeft: 8 }} />도움 받음</span></div>
          <table className="small" style={{ width: '100%', marginTop: 10, borderCollapse: 'collapse', textAlign: 'center' }}>
            <thead><tr className="mute2"><th scope="col" style={{ textAlign: 'left', fontWeight: 500, padding: '4px 0' }}>달</th>{months.map((m) => <th key={m.month} scope="col" style={{ fontWeight: 500 }}>{monthLabel(m.month)}</th>)}</tr></thead>
            <tbody>
              <tr><th scope="row" style={{ textAlign: 'left', fontWeight: give ? 800 : 500, padding: '6px 0', color: '#3F8A75' }}>봉사</th>{months.map((m) => <td key={m.month} style={{ fontWeight: give ? 800 : 500 }}>{m.helpCount}회</td>)}</tr>
              <tr><th scope="row" style={{ textAlign: 'left', fontWeight: give ? 500 : 800, padding: '6px 0', color: '#3A6FD8' }}>도움 받음</th>{months.map((m) => <td key={m.month} style={{ fontWeight: give ? 500 : 800 }}>{m.receivedCount}회</td>)}</tr>
            </tbody>
          </table>
          <div className="mute2" style={{ marginTop: 8 }}>완료된 활동만 세요 · 지금까지 봉사 {sum.totalHelpCount}회 · 도움 받음 {sum.totalReceivedCount}회</div>
        </div>
      )}
      {seg === 'past' && done.length > 0 && (
        <div className="card" style={{ marginTop: 10 }}>
          <div className="b">활동 결산</div>
          <div className="stat2" style={{ marginTop: 10 }}><div><b>{done.length}회</b><span>{give ? '봉사' : '도움 받음'}</span></div><div><b>{fmt(done.reduce((x, r) => x + (Number(r.hours) || 0), 0))}시간</b><span>{give ? '봉사한 시간' : '받은 시간'} (예정 시간 기준)</span></div></div>
        </div>
      )}
    </Screen>
  );
}

const LT = { EARN: '적립', HOLD: '보류', RELEASE: '보류 해제', USE: '사용', GIFT_OUT: '선물', GIFT_IN: '선물 받음', ADJUST: '조정', REFUND: '환불' };
function monthBars(rows) {
  const W2 = 320, H = 140, pt = 18, pb = 24, bw = rows.length > 4 ? 13 : 18;
  const max = Math.max(4, ...rows.flatMap((r) => [r[1], r[2]])) * 1.1;
  const cw = (W2 - 16) / Math.max(1, rows.length);
  return (
    <svg viewBox={`0 0 ${W2} ${H}`} className="chart" role="img" aria-label="월별 적립·사용">
      {rows.map(([m, a, b], i) => {
        const cx = 8 + cw * i + cw / 2, by = H - pb, ha = ((H - pt - pb) * a) / max, hb = ((H - pt - pb) * b) / max;
        const last = i === rows.length - 1;
        return (
          <g key={m}>
            <rect x={cx - bw - 2} y={by - ha} width={bw} height={ha} rx="5" fill="#5B8DEF" />
            <rect x={cx + 2} y={by - hb} width={bw} height={hb} rx="5" fill="#F59E4B" />
            <text x={cx - bw / 2 - 2} y={by - ha - 5} textAnchor="middle" fontSize="10" fill="#5B8DEF" fontWeight="700">{fmt(a)}</text>
            <text x={cx + bw / 2 + 2} y={by - hb - 5} textAnchor="middle" fontSize="10" fill="#C46A17" fontWeight="700">{fmt(b)}</text>
            <text x={cx} y={H - 6} textAnchor="middle" fontSize="11.5" fill={last ? '#1B1D22' : '#687080'} fontWeight={last ? 700 : 400}>{m}</text>
          </g>
        );
      })}
    </svg>
  );
}
function monthLabel(m) { const s = String(m); const p = s.split('-'); return p.length > 1 ? `${+p[1]}월` : s; }

/* ---------- 시간 통장 (A-06, B-30~34) ---------- */
export function Bank() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { sheet, closeSheet } = useUi();
  const [b] = useLoad(() => api('/bank'), []);
  const rule = () => sheet(() => (
    <>
      <h2>시간 통장 규칙</h2>
      <div className="ml">{[['통장 번호', '가입할 때 정해지는 내 고유 번호예요. 바뀌지 않아요'], ['적립', '예정 시간(요청·공고에 정한 시간) × 난이도 (기본 1.0 / 수고 1.2), 0.5 단위. 실제와 다르면 시간 조정을 요청해요'], ['사용', '10시간 이상 적립한 뒤부터 (선물받은 시간 제외)'], ['보류·차감', '요청이 확정되면 예정 시간 기준으로 보류하고, 활동이 완료되면 예정 시간 기준으로 차감해요 (난이도 가중치 포함)'], ['선물', '연결된 가족에게만. 양도·매매·상속 불가, 돈으로 바꿀 수 없어요'], ['기관 대상', '받는 쪽 차감 없이 봉사자만 적립']].map(([a, c]) => <div key={a}><div className="grow"><b>{a}</b><span>{c}</span></div></div>)}</div>
      <button className="btn ghost" style={{ marginTop: 12 }} onClick={closeSheet}>닫기</button>
    </>
  ));
  // 2026-11-13 대표 지시: 거래 내역은 월별로 골라 보고(최신순), 건마다 번·쓴 시간, 그 뒤 잔액, 도움 준(받은) 사람을 보여 준다.
  // 잔액(balanceAfter)·상대 이름(counterpart)·월 합계(months)는 모두 서버가 계산한 값이다.
  const [ledM, setLedM] = useState(null);
  const [open, setOpen] = useState(false);
  if (b === undefined) return <Screen title="시간 통장" back={false}><Loading /></Screen>;
  const bk = b || { balance: 0, hold: 0, entries: [], monthly: [] };
  const entries = bk.entries || [];
  const monthSum = Object.fromEntries((bk.months || []).map((m) => [m.month, m]));
  const lvOf = (w) => (Number(w) >= 1.5 ? '책임' : Number(w) >= 1.2 ? '수고' : '기본'); // '책임'은 2026-11-13 전 기록 표시용
  const mkOf = (l) => String(l.createdAt).slice(0, 7);
  const ms = [...new Set(entries.map(mkOf))]; // 최신 달부터
  const curM = ms.includes(ledM) ? ledM : ms[0];
  const mi = ms.indexOf(curM);
  const rowsM = entries.filter((l) => mkOf(l) === curM);
  const sumM = monthSum[curM];
  const N = 8;
  const isHold = (l) => l.type === 'HOLD' || l.type === 'RELEASE';
  const whoTxt = (l) => (l.counterpart ? `${l.counterpart}${/님$/.test(l.counterpart) ? '' : ' 님'}` : '');
  // 원장 글(label)이 "도움 종류 · 상대 이름" 꼴이면 이름은 상대 칸에서 따로 보여 주므로 앞쪽(도움 종류)만 제목으로 쓴다
  const titleOf = (l) => { const t = l.label || LT[l.type]; return l.counterpart && String(t).includes(' · ') ? String(t).split(' · ')[0] : t; };
  const receipt = (l) => sheet(() => {
    const v = Number(l.amount) || 0, inn = v > 0, hold = isHold(l);
    const dt = String(l.createdAt).slice(0, 10).split('-');
    return (
      <>
        <h2 style={{ textAlign: 'center', marginBottom: 12 }}>거래 상세</h2>
        <div className="rcpt">
          <div className="rc-h"><span>{hold ? (l.type === 'HOLD' ? '확정된 일정에 묶인 시간' : '묶였다가 풀린 시간') : l.type === 'GIFT_OUT' || l.type === 'GIFT_IN' ? '가족 선물' : inn ? '봉사해서 모은 시간' : '도움 받아 쓴 시간'}</span><b style={{ color: hold ? '#5F6672' : inn ? '#2F8069' : '#C0603F' }}>{hold ? '' : inn ? '+' : '−'}{hmTxt(v)}</b><span>{+dt[0]}년 {+dt[1]}월 {+dt[2]}일</span></div>
          <div className="rc-r"><span>구분</span><b>{LT[l.type] || l.type}</b></div>
          <div className="rc-r"><span>내용</span><b>{l.label || LT[l.type]}</b></div>
          {l.counterpart && <div className="rc-r"><span>{l.counterpartRole === 'HELPED' ? '도운 분' : '도와준 분'}</span><b>{whoTxt(l)}</b></div>}
          {l.baseHours ? <div className="rc-r"><span>계산</span><b>{hmTxt(l.baseHours)} × {Number(l.weight || 1).toFixed(1)} ({lvOf(l.weight || 1)})</b></div> : null}
          <div className="rc-sum">
            <div className="rc-r"><span>거래 후 잔액</span><b>{hmTxt(l.balanceAfter)}</b></div>
            {hold && <div className="rc-r"><span>알림</span><b>묶인 시간은 잔액을 바꾸지 않아요</b></div>}
            <div className="rc-r"><span>거래 번호</span><b>TX-{dt.join('')}-{String(l.id).padStart(4, '0')}</b></div>
          </div>
        </div>
        {l.requestId && <button className="btn soft" style={{ marginTop: 22 }} onClick={() => { closeSheet(); nav(`/req/${l.requestId}`); }}>활동 상세 · 봉사 일지 보기</button>}
        <div className="btns" style={{ marginTop: l.requestId ? 8 : 22 }}><button className="btn ghost" onClick={closeSheet}>닫기</button><button className="btn ghost" onClick={() => { closeSheet(); nav('/qna/new'); }}>문제 신고</button></div>
      </>
    );
  });
  const arrow = (up) => <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d={up ? 'M10 15V5M5.5 9.5 10 5l4.5 4.5' : 'M10 5v10M5.5 10.5 10 15l4.5-4.5'} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" /></svg>;
  return (
    <Screen title="시간 통장" back={false}>
      {/* 통장 번호 · 예금주 · 쓸 수 있는 시간 · 총 모은 시간 · 총 사용 시간 · [가족에게 선물하기] · [플래너] */}
      <div className="pb">
        <div className="pb-head"><span className="pb-mark"><CalIcon s={18} /></span><b>시간 적금 통장</b><span className="pb-no">{bk.accountNo || (me && me.accountNo) || ''}</span></div>
        <div className="pb-body" style={{ paddingBottom: 12 }}>
          <div className="pb-who"><span>예금주</span><b>{me ? me.name : ''}</b></div>
          <div className="pb-l">쓸 수 있는 시간</div><div className="pb-n"><HmBig v={bk.available ?? bk.balance} /></div>
          <div className="pb-note">적립 한도 없음 · 가족에게 선물할 수 있어요{Number(bk.hold) > 0 ? ` · 묶인 시간 ${hmTxt(bk.hold)}` : ''}</div>
        </div>
        <div className="pbt2">
          <div className="pt-i in"><span className="pt-ic">{arrow(false)}</span><div><span>총 모은 시간</span><b>{hmTxt(bk.totalEarned)}</b></div></div>
          <div className="pt-i out"><span className="pt-ic">{arrow(true)}</span><div><span>총 사용 시간</span><b>{hmTxt(bk.totalUsed)}</b></div></div>
        </div>
        <div className="pb-acts"><button onClick={() => nav('/gift')}>가족에게 선물하기</button><button onClick={() => nav('/planner', { replace: true })}>플래너</button></div>
      </div>
      {bk.canUseSelf === false && <div className="notice" style={{ marginTop: 12 }}>직접 적립한 시간이 10시간이 되면 도움을 받을 때 쓸 수 있어요. 선물받은 시간은 바로 쓸 수 있어요.</div>}
      {/* 거래 내역: 월별 필터 · 최신순 · 건마다 번/쓴 시간 · 그 뒤 잔액 · 도움 준(받은) 사람 */}
      <div className="sec-t"><span>거래 내역</span><span className="led-sort">최신순 · 누르면 상세</span></div>
      {entries.length ? (
        <div className="ledger2">
          <div className="lm-h">
            <button className="bs-nav" aria-label="이전 달" disabled={mi >= ms.length - 1} onClick={() => { setLedM(ms[mi + 1]); setOpen(false); }}>‹</button>
            <div className="lm-t"><b>{curM.slice(0, 4)}년 {+curM.slice(5, 7)}월</b>{sumM && <span>적립 <em className="bt-a">+{hmTxt(sumM.in)}</em> · 사용 <em className="bt-b">−{hmTxt(sumM.out)}</em></span>}</div>
            <button className="bs-nav" aria-label="다음 달" disabled={mi <= 0} onClick={() => { setLedM(ms[mi - 1]); setOpen(false); }}>›</button>
          </div>
          {(open ? rowsM : rowsM.slice(0, N)).map((l) => {
            const v = Number(l.amount) || 0, hold = isHold(l);
            const who = whoTxt(l);
            return (
              <button type="button" key={l.id} className="lg2" onClick={() => receipt(l)}>
                <div className="l2-d"><b>{String(l.createdAt).slice(8, 10)}</b><span>{+String(l.createdAt).slice(5, 7)}월</span></div>
                <div className="l2-t"><b>{titleOf(l)}</b><em>{who ? `${l.counterpartRole === 'HELPED' ? '도운 분' : '도와준 분'} ${who} · ` : ''}{LT[l.type] || l.type}{l.baseHours ? ` · ${hmTxt(l.baseHours)} × ${Number(l.weight || 1).toFixed(1)}` : ''}</em></div>
                <div className="l2-r"><b className={hold ? '' : v > 0 ? 'lg-in' : 'lg-out'}>{hold ? '' : v > 0 ? '+' : '−'}{hmTxt(v)}</b><span>잔액 {hmTxt(l.balanceAfter)}</span></div>
              </button>
            );
          })}
          {rowsM.length > N && <button className="l2-more" onClick={() => setOpen(!open)}>{open ? '접기' : <>더보기 <span>{rowsM.length - N}건</span></>}</button>}
        </div>
      ) : <Empty>아직 내역이 없어요</Empty>}
      <div className="mute2" style={{ margin: '8px 2px 0' }}>묶인 시간은 확정된 도움 일정에 잡혀 있는 시간이에요(끝나면 차감, 취소되면 돌아와요). 잔액 {hmTxt(bk.balance)} · 직접 적립 {hmTxt(bk.earnedSelf)} · 선물받음 {hmTxt(bk.giftReceived)} · <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700, cursor: 'pointer' }} onClick={rule} onKeyDown={(e) => { if (e.key === 'Enter') rule(); }}>이용 규칙</a></div>
      {/* 기능 U-24 시간 플랜 · U-25 월별 리포트 */}
      <div className="card item click" style={{ marginTop: 12 }} role="button" tabIndex={0} onClick={() => nav('/bank/time-plan')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/bank/time-plan'); }}><DIc k="ai" size={40} bare /><div className="grow"><div className="b">시간 플랜</div><div className="mute2">앞으로 몇 년, 어떤 도움을 받을 수 있는지와 적립 목표</div></div>›</div>
      <div className="card item click" style={{ marginTop: 8 }} role="button" tabIndex={0} onClick={() => nav('/bank/report')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/bank/report'); }}><span className="pb-mark"><CalIcon s={18} /></span><div className="grow"><div className="b">월별 리포트</div><div className="mute2">이번 달 봉사·도움 횟수와 지난달 대비 변화</div></div>›</div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="between"><span className="b">월별 결산 <span className="mute2" style={{ fontWeight: 500 }}>최근 {(bk.monthly || []).length}개월</span></span><span className="legend"><i style={{ background: '#5B8DEF' }} />적립<i style={{ background: '#F59E4B', marginLeft: 8 }} />사용</span></div>
        {(bk.monthly || []).length ? monthBars(bk.monthly.map((m) => [monthLabel(m.month), Number(m.in) || 0, Number(m.out) || 0])) : <Empty>아직 결산할 내역이 없어요</Empty>}
        <div className="between mute2" style={{ marginTop: 4 }}><span>이번 달 적립 +{fmt(bk.monthIn)}</span><span>이번 달 사용 −{fmt(bk.monthOut)}</span></div>
        <MonthNote />
      </div>
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
  if (fam !== undefined && !linked.length) {
    return (
      <Screen title="가족에게 선물" noTabs>
        <Empty>연결된 가족이 없어요</Empty>
        <p className="su-p center">시간은 연결된 가족에게만 선물할 수 있어요. 먼저 가족 연결을 신청해 주세요.</p>
        <button className="btn big-cta" onClick={() => nav('/my/family', { replace: true })}>가족 연결 신청하러 가기</button>
      </Screen>
    );
  }
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
