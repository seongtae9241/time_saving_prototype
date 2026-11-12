import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Mi, Cats, ReqRow, Loading, Empty, ChipsPick, RuleNote, useLoad, useBusy, hbars } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post, put, del } from '../api.js';
import { RecentReport } from './insight.jsx';
import { CATS, AREAS, arr, dshort, dlabel, addDays, ap, maskN, ACTIVE_ST, PAST_ST } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { needsMe, todoTxt } from './home.jsx';
import { TextSizeRow, useLogout } from './my.jsx';
import { MicButton } from '../ui/VoiceInput.jsx';
import { ReportView } from './my.jsx';
import { OrgBriefing, useCareSignals, SignalChip, SignalCard } from './opsai.jsx';
import { ElderClasses } from './publicClass.jsx';
import { DoorCardSheet } from './request.jsx'; // C-11 현관 QR 카드
import { DIc } from '../ui/proto.jsx';
import { SuggestLink } from '../ui/suggest.jsx';

const elderName = (e) => (e ? `${e.alias} 님` : '');

function ElderCard({ e, sig }) {
  const nav = useNavigate();
  return (
    <div className="ec" role="button" tabIndex={0} onClick={() => nav(`/o/elders/${e.id}`)}>
      <div className="av">{(e.alias || '?')[0]}</div>
      <div className="grow">
        <div className="b" style={{ display: 'flex', gap: 6, alignItems: 'center' }}>{elderName(e)}{e.flag || e.bigChange ? <span className="st no">변화 큼</span> : null}<SignalChip s={sig} />{e.status === 'ENDED' && <span className="st end">종료</span>}</div>
        <div className="mute2">{e.ageGroup} · {e.dong}{e.monthDone != null ? ` · 이번 달 활동 ${e.monthDone}회` : ''}{e.regularCount != null ? ` · 단골 ${e.regularCount}명` : e.regulars ? ` · 단골 ${e.regulars.length}명` : ''}</div>
        <Cats list={arr(e.needs)} style={{ gap: 4, marginTop: 6 }} />
      </div>›
    </div>
  );
}

/** 대상 고르기 시트 → 콜백 */
function useElderPick() {
  const { sheet, closeSheet } = useUi();
  return (title, elders, onPick) => sheet(() => (
    <>
      <h2>{title}</h2>
      {(elders || []).filter((e) => e.status !== 'ENDED').map((e) => <div key={e.id} className="opt" role="button" tabIndex={0} onClick={() => { closeSheet(); onPick(e); }}><span className="rd" /><div className="grow"><div className="b">{elderName(e)}</div><div className="mute2">{e.dong}</div></div></div>)}
      {!(elders || []).length && <Empty>등록된 대상이 없어요</Empty>}
    </>
  ));
}

/* ---------- C-16 변화 리포트 카드: n순위 · 먼저 살펴보기/비슷해요 · 변화 · 특이사항 ---------- */
function ChangeCard({ x }) {
  const nav = useNavigate();
  const first = x.level !== 'LOW';
  const change = (x.changes && x.changes.length ? x.changes : x.reasons && x.reasons.length ? x.reasons : ['이전 기록과 비슷해요.']).join(' · ');
  return (
    <div className="card click" style={{ marginTop: 10 }} role="button" tabIndex={0} onClick={() => nav(`/o/elders/${x.elderId}`)} onKeyDown={(ev) => { if (ev.key === 'Enter') nav(`/o/elders/${x.elderId}`); }}>
      <div className="between"><span className="b" style={{ fontSize: 16 }}><span className="pill" style={{ marginRight: 6 }}>{x.rank}순위</span>{x.displayName} <span className="mute2" style={{ fontWeight: 500 }}>{x.dong}</span></span>{first ? <span className="st no">안부 먼저 확인</span> : <span className="st end">비슷해요</span>}</div>
      <div className="small" style={{ marginTop: 8 }}><b>변화</b> {change}</div>
      <div className="small" style={{ marginTop: 6 }}><b>특이사항</b> {x.summary}</div>
      {x.applyText && <div className="small" style={{ marginTop: 6 }}><b>신청 내역</b> {x.applyText}</div>}
      {first && x.reasons && x.reasons.length > 0 && <div className="hnote" style={{ marginTop: 6 }}>{x.reasons.join(' · ')}</div>}
    </div>
  );
}

/** C-14 지난 요청 다시 올리기(B-06): 지난 공고를 고르고 날짜만 골라 같은 내용으로 올린다. 자동으로 반복되지 않는다. */
function RepostSheet({ elder, posts, onDone }) {
  const { toast } = useUi();
  const [sel, setSel] = useState(null);
  const [date, setDate] = useState(addDays(7));
  const [busy, run] = useBusy();
  const list = (posts || []).filter((p) => p.type === 'NEED').slice().sort((a, b) => (a.date < b.date ? 1 : -1)).slice(0, 10);
  const dates = Array.from({ length: 21 }, (_, i) => addDays(i + 1));
  const p = list.find((x) => x.id === sel);
  return (
    <>
      <h2>지난 요청 다시 올리기</h2>
      <p className="mute2" style={{ margin: 0 }}>{elderName(elder)}의 지난 공고예요. 자동으로 반복되지 않아요. 필요할 때 불러와서 날짜만 바꿔요.</p>
      {list.length ? list.map((x) => <div key={x.id} className={'opt ' + (sel === x.id ? 'on' : '')} role="button" tabIndex={0} onClick={() => setSel(x.id)}><span className="rd" /><div className="grow"><div className="b">{x.title}</div><div className="mute2">{dlabel(x.date)} {ap(x.startHour)} · {arr(x.categories).join(', ')}</div></div></div>) : <Empty>지난 공고가 없어요</Empty>}
      {p && <><label className="f">새 날짜</label><div className="tags">{dates.map((d) => <button key={d} type="button" className={'chip ' + (date === d ? 'on' : '')} onClick={() => setDate(d)}>{dlabel(d)}</button>)}</div></>}
      <button className="btn" style={{ marginTop: 12 }} disabled={!p || busy} onClick={() => run(async () => {
        // 다시 올리기: 지난 공고를 같은 내용으로 불러와 날짜만 바꿔 올린다(B-06)
        const np = await post(`/posts/${p.id}/repost`, { date });
        toast(`${dlabel(date)}로 다시 올렸어요`);
        onDone(Array.isArray(np) ? np[0] : np);
      })}>{p ? `${dlabel(date)}에 다시 올리기` : '지난 공고를 골라 주세요'}</button>
    </>
  );
}

/* ---------- 담당자 홈 (C-12) ---------- */
export function OHome() {
  const nav = useNavigate();
  const { me } = useAuth();
  const pick = useElderPick();
  const [home] = useLoad(() => api('/org/home', { quiet: true }), []);
  const [elders] = useLoad(() => api('/org/elders'), []);
  const [reqs] = useLoad(() => api('/org/requests?box=all'), []);
  const need = (reqs || []).filter(needsMe);
  const sigs = useCareSignals();
  const [chg] = useLoad(() => api('/org/change-report', { quiet: true }).catch(() => null), []);
  // C-16: 먼저 살펴볼 대상(순위는 서버가 계산). 변화 리포트를 못 불러오면 예전처럼 변화 큼·확인 권장 대상을 보여 준다
  const watch = chg ? (chg.items || []).filter((x) => x.level !== 'LOW').slice(0, 3) : null;
  const flagged = (elders || []).filter((e) => e.flag || e.bigChange || (sigs[e.id] && sigs[e.id].flagged));
  const counts = (home && home.counts) || {};
  return (
    <Screen title={me ? `${me.name} 담당자` : '담당자'} back={false}>
      <div className="hero" style={{ background: '#3A6FD8' }}>
        <div className="bk-top"><span className="bk-name">{me && (me.orgName || (me.org && me.org.name)) || '기관'}</span><span className="bk-no">담당자</span></div>
        <div className="lbl">담당 도움 대상</div><div className="num">{counts.elders ?? (elders ? elders.filter((e) => e.status !== 'ENDED').length : 0)}<small>명</small></div>
        <div className="row3"><div><b>{counts.monthDone ?? 0}건</b>이번 달 활동</div><div><b>{need.length}건</b>처리할 일</div><div><b>{counts.active ?? (reqs || []).filter((r) => ACTIVE_ST.includes(r.status)).length}건</b>진행 중</div></div>
      </div>
      <div className="sec-t"><span>처리할 일 <span style={{ color: '#E5484D' }}>{need.length}</span></span><a role="button" tabIndex={0} onClick={() => nav('/o/reqs', { replace: true })}>전체 ›</a></div>
      {reqs === undefined ? <Loading /> : need.length ? need.map((r) => (
        <div key={r.id} className="todo" role="button" tabIndex={0} onClick={() => nav(`/o/reqs/${r.id}`)}>
          <div className="n" style={{ background: '#E1ECFE', color: '#3A6FD8' }}>{r.elder ? r.elder.displayName[0] : '?'}</div>
          <div className="grow"><div className="t">{todoTxt(r)}</div><div className="d">{r.elder && r.elder.displayName} · {r.volunteer && r.volunteer.name} · {r.content}</div></div>
          {r.leftText ? <span className="dl">{r.leftText}</span> : <span className="st go">확인</span>}
        </div>
      )) : <Empty>지금 처리할 일이 없어요</Empty>}
      <div className="qa" style={{ marginTop: 18 }}>
        {[['대상 등록', 'elder', () => nav('/o/elders/new')], ['공고 대신 등록', 'req', () => pick('누구 대신 공고를 올릴까요?', elders, (e) => nav(`/write/need?elder=${e.id}`))], ['봉사 공고 찾기', 'search', () => pick('누구 대신 요청할까요?', elders, (e) => nav(`/board?elder=${e.id}`))], ['단골에게 요청', 'regular', () => pick('누구의 단골에게 요청할까요?', elders, (e) => nav(`/regular?elder=${e.id}`))]].map(([t, i, f]) => <button key={t} onClick={f}><DIc k={i} size={56} />{t}</button>)}
      </div>
      <button className="btn soft" style={{ marginTop: 12 }} onClick={() => pick('누구 대신 말로 요청할까요?', elders, (e) => nav(`/voice?elder=${e.id}`))}><Ic h={I.mic} /> 들은 내용을 말로 공고 만들기</button>
      <button className="btn soft" style={{ marginTop: 8 }} onClick={() => nav('/o/memo-posts')}><Ic n="spark" s={18} /> 통화 메모로 한 번에 만들기 (여러 건)</button>
      <div className="todo" style={{ marginTop: 12 }} role="button" tabIndex={0} onClick={() => nav('/o/schedule', { replace: true })}><div className="n" style={{ background: '#E1ECFE', color: '#3A6FD8' }}><Ic n="cal" s={20} /></div><div className="grow"><div className="t">돌봄 편성표</div><div className="d">이번 주 일정과 빈 칸을 한눈에 보고, 빈 칸에 공고를 한꺼번에 올려요</div></div><span className="st go">보기</span></div>
      <div style={{ marginTop: 14 }}><OrgBriefing /></div>
      <div className="sec-t"><span>기관 소식</span><a role="button" tabIndex={0} onClick={() => nav('/o/events')}>관리 ›</a></div>
      <div className="todo" role="button" tabIndex={0} onClick={() => nav('/o/events/new')}><div className="n" style={{ background: '#FFEDF3', color: '#E2557F' }}><Ic n="cal" s={20} /></div><div className="grow"><div className="t">기관 소식 올리기</div><div className="d">교실·모임·행사를 올리면 근처 사용자 홈에 보여요</div></div><span className="st go">올리기</span></div>
      <div className="sec-t"><span>살펴볼 대상</span><a role="button" tabIndex={0} onClick={() => nav('/o/report', { replace: true })}>관리 대상 리포트 ›</a></div>
      {watch ? (watch.length ? watch.map((x) => <ChangeCard key={x.elderId} x={x} />) : <Empty>먼저 살펴볼 대상이 없어요</Empty>)
        : flagged.length ? flagged.map((e) => <ElderCard key={e.id} e={e} sig={sigs[e.id]} />) : <Empty>변화가 큰 대상이 없어요</Empty>}
      <div className="notice" style={{ marginTop: 14 }}>도움 대상은 계정이 없어요. 요청·알림·메시지·확인·평가는 모두 담당자가 대신해요. 대상에게는 앱 알림을 보내지 않아요.</div>
    </Screen>
  );
}

/* ---------- 도움 대상 목록 · 이관 요청 (C-02, C-03) ---------- */
export function OElders() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [list] = useLoad(() => api('/org/elders'), []);
  const [tr, reloadTr] = useLoad(() => api('/org/transfers', { quiet: true }), []);
  const [busy, run] = useBusy();
  const active = (list || []).filter((e) => e.status !== 'ENDED');
  const ended = (list || []).filter((e) => e.status === 'ENDED');
  const sigs = useCareSignals();
  const { me } = useAuth();
  const isIn = (t) => t.direction ? t.direction === 'in' : me && t.to && t.to.id === me.id;
  const incoming = (tr || []).filter((t) => t.status === 'PENDING' && isIn(t));
  const outgoing = (tr || []).filter((t) => t.status === 'PENDING' && !isIn(t));
  return (
    <Screen title="도움 대상" back={false}>
      <div className="lock"><Ic n="shield" s={18} /><div>이름·주소·연락처는 입력하지 않아요 (기관이 따로 보관). 별칭은 담당자 화면에만 보이고, 봉사자에게는 '이○례 님'처럼 가려서 보여요.</div></div>
      <button className="btn" style={{ marginTop: 12 }} onClick={() => nav('/o/elders/new')}><Ic h={I.plus} /> 도움 대상 등록</button>
      <div className="sec-t">관리 중 {active.length}명</div>
      {list === undefined ? <Loading /> : active.length ? active.map((e) => <ElderCard key={e.id} e={e} sig={sigs[e.id]} />) : <Empty>등록된 대상이 없어요</Empty>}
      {(incoming.length > 0 || outgoing.length > 0) && <div className="sec-t">이관 요청</div>}
      {incoming.map((t) => (
        <div key={t.id}>
          <div className="card item"><div className="ec av" style={{ width: 46, height: 46, border: 0, padding: 0, display: 'grid', placeItems: 'center', borderRadius: 16, background: '#E1ECFE', color: '#3A6FD8', fontWeight: 800 }}>{((t.elder && t.elder.alias) || t.elderAlias || '?')[0]}</div><div className="grow"><div className="b">{(t.elder && t.elder.alias) || t.elderAlias} 님{t.elder && t.elder.dong ? ` · ${t.elder.dong}` : ''}</div><div className="mute2">{t.from ? t.from.name : '운영자'} 담당자가 이관을 요청했어요</div></div></div>
          <div className="btns" style={{ marginTop: 8 }}><button className="btn ghost sm" disabled={busy} onClick={() => run(async () => { await post(`/org/transfers/${t.id}/answer`, { accept: false }); reloadTr(); toast('이관을 거절했어요'); })}>거절</button><button className="btn sm" disabled={busy} onClick={() => run(async () => { await post(`/org/transfers/${t.id}/answer`, { accept: true }); reloadTr(); toast('이관받았어요'); window.location.reload(); })}>수락</button></div>
        </div>
      ))}
      {outgoing.map((t) => <div key={t.id} className="card item"><div className="grow"><div className="b">{(t.elder && t.elder.alias) || t.elderAlias} 님 → {t.to && t.to.name}</div><div className="mute2">받는 담당자의 수락을 기다려요</div></div><span className="st wait">대기</span></div>)}
      {ended.length > 0 && <><div className="sec-t">지원 종료 {ended.length}명</div>{ended.map((e) => <ElderCard key={e.id} e={e} />)}</>}
    </Screen>
  );
}

/* ---------- 도움 대상 상세 ---------- */
export function EDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { toast, confirm, sheet, closeSheet } = useUi();
  const [e, reload] = useLoad(() => api(`/org/elders/${id}`), [id]);
  const [reqs] = useLoad(() => api('/org/requests?box=all', { quiet: true }), [id]);
  const [reps] = useLoad(() => api('/org/reports', { quiet: true }), []);
  const [memo, setMemo] = useState('');
  const [cu, setCu] = useState('');
  const [tidyOpen, setTidyOpen] = useState(false);
  const [chg] = useLoad(() => api('/org/change-report', { quiet: true }).catch(() => null), [id]);
  const [busy, run] = useBusy();
  const sigs = useCareSignals();
  if (e === undefined) return <Screen title="도움 대상" noTabs><Loading /></Screen>;
  if (!e) return <Screen title="도움 대상" noTabs><Empty>대상을 찾을 수 없어요</Empty></Screen>;
  const myReqs = e.requests || (reqs || []).filter((r) => r.elder && String(r.elder.id) === String(id));
  const rep = (e.reports || [])[0] || (reps || []).filter((r) => r.subjectType === 'ELDER' && String(r.subjectId) === String(id))[0];
  const ended = e.status === 'ENDED';
  const transfer = () => sheet(() => <TransferSheet e={e} onDone={() => { closeSheet(); reload(); }} />);
  const myChange = chg ? (chg.items || []).find((x) => String(x.elderId) === String(id)) : null;
  const memos = e.memos || [];
  const focusMemo = () => { const el = document.getElementById('elder-memo-in'); if (el) { el.scrollIntoView({ block: 'center' }); el.focus(); } };
  const repost = () => sheet(() => <RepostSheet elder={e} posts={e.posts} onDone={(np) => { closeSheet(); if (np && np.id) nav(`/post/${np.id}`); else reload(); }} />);
  return (
    <Screen title={elderName(e)} noTabs>
      <div className="row" style={{ gap: 14 }}>
        <div className="ec av" style={{ width: 60, height: 60, border: 0, padding: 0, display: 'grid', placeItems: 'center', borderRadius: 20, background: '#E1ECFE', color: '#3A6FD8', fontWeight: 800, fontSize: 22 }}>{e.alias[0]}</div>
        <div className="grow"><div className="b" style={{ fontSize: 20 }}>{elderName(e)} {(e.flag || e.bigChange) && <span className="st no">변화 큼</span>}{ended && <span className="st end">지원 종료</span>}</div><div className="mute2">{e.ageGroup} · {e.dong} · {dshort(e.createdAt).split(' ')[0]}부터</div></div>
        {!ended && <button className="btn soft sm" style={{ width: 'auto', padding: '0 12px' }} onClick={() => nav(`/o/elders/${id}/edit`)}>수정</button>}
      </div>
      <SignalCard s={sigs[e.id]} who={elderName(e)} />
      {!ended && <div className="qa" style={{ marginTop: 16 }}>{[['공고 대신 등록', 'chat', '#EE8424', () => nav(`/write/need?elder=${id}`)], ['봉사 공고에 요청', 'search', '#5B8DEF', () => nav(`/board?elder=${id}`)], ['단골에게 요청', 'heart', '#E2557F', () => nav(`/regular?elder=${id}`)], ['메모 쓰기', 'pen', '#3A6FD8', focusMemo]].map(([t, i, c, f]) => <button key={t} onClick={f}><span style={{ background: `${c}14`, color: c }}><Ic n={i} s={22} /></span>{t}</button>)}</div>}
      {!ended && <button className="btn soft sm" style={{ marginTop: 10 }} onClick={repost}>↻ 지난 요청 다시 올리기</button>}
      {!ended && <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => nav(`/voice?elder=${id}`)}><Ic h={I.mic} /> 말로 공고 만들기</button>}
      {!ended && <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => nav(`/o/memo-posts?elder=${id}`)}><Ic n="spark" s={16} /> 통화 메모로 공고 여러 건 만들기</button>}
      <div className="card" style={{ marginTop: 14 }}>
        <dl className="kv"><dt>필요한 도움</dt><dd>{arr(e.needs).join(', ') || '-'}</dd><dt>단골</dt><dd>{(e.regulars || []).map((r) => `${r.name} ★`).join(', ') || '없음'}</dd>
          <dt>이번 달 활동</dt><dd>{e.monthDone ?? 0}회 <span className="mute2">(누적 {e.doneTotal ?? 0}회)</span></dd>
          <dt>서비스 동의</dt><dd>{e.serviceConsentAt ? `받음 (${dshort(e.serviceConsentAt).split(' ')[0]})` : <>기록 없음 {!ended && <button className="btn soft sm" style={{ width: 'auto', padding: '0 10px', minHeight: 32, marginLeft: 6 }} disabled={busy} onClick={async () => { if (!(await confirm('서비스 동의를 받았나요?', `${elderName(e)}께 서비스 이용을 설명하고 동의를 받았을 때만 기록해 주세요.`, '동의 받음으로 기록'))) return; run(async () => { await put(`/org/elders/${id}`, { consent: true }); await reload(); toast('서비스 동의를 기록했어요'); }); }}>동의 받음으로 기록</button>}</>}</dd></dl>
        <div className="mute2" style={{ marginTop: 8 }}>봉사자에게는 가린 이름({maskN(e.alias)} 님)·동·도움 내용·시간까지만 보여요. 주의사항(특이사항)은 일정이 확정된 봉사자에게만 보여요.</div>
      </div>
      <div className="sec-t"><span>봉사자에게 보내는 정보</span><span className="mute2" style={{ fontWeight: 500 }}>확정된 봉사자에게만</span></div>
      <div className="card">
        <div className="small">{e.caution || '적어 둔 주의사항이 없어요'}</div>
        <div className="mute2" style={{ marginTop: 6 }}>여기에 적은 주의사항은 일정이 확정된 봉사자에게만 보여요. 아래 담당자 메모는 나만 봐요.</div>
        {!ended && <div className="row" style={{ marginTop: 10 }}><input className="in grow" placeholder="주의사항 추가 (병명은 적지 않아요)" value={cu} onChange={(x) => setCu(x.target.value)} aria-label="주의사항 추가" /><button className="btn sm" style={{ width: 'auto', padding: '0 14px', flex: '0 0 auto' }} disabled={busy} onClick={() => run(async () => { const v = cu.trim(); if (!v) return toast('주의사항을 적어 주세요'); await put(`/org/elders/${id}`, { caution: e.caution ? `${e.caution} / ${v}` : v }); setCu(''); await reload(); toast('봉사자에게 보내는 정보에 추가했어요'); })}>추가</button></div>}
      </div>
      <div className="sec-t"><span>담당자 메모 <span className="mute2" style={{ fontWeight: 500 }}>담당자만 봐요</span></span><a role="button" tabIndex={0} onClick={() => (memos.length ? setTidyOpen(!tidyOpen) : toast('정리할 메모가 없어요'))}>✨ AI 정리</a></div>
      {tidyOpen && memos.length > 0 && (
        <div className="ai">
          <div className="ah"><Spk /><span className="mod m1">기록 정리 AI</span><Mod n={5} /></div>
          <div className="small">메모 {memos.length}건을 날짜순으로 정리했어요.</div>
          <ul className="small" style={{ margin: '6px 0 0', paddingLeft: 18 }}>{memos.slice().reverse().map((m) => <li key={m.id}><span className="mute2">{dshort(m.createdAt).split(' ')[0]}</span> {(m.lines && m.lines.length ? m.lines : [m.text]).join(' · ')}</li>)}</ul>
          <div className="mute2" style={{ marginTop: 8 }}>봉사자에게 보내는 정보에는 들어가지 않아요. 필요한 내용만 위에서 따로 추가해요.</div>
          <RuleNote source={memos.some((m) => m.source === 'ai') ? 'ai' : 'rule'} />
        </div>
      )}
      <div className="card" style={{ marginTop: tidyOpen ? 10 : 0 }}>
        {memos.length ? memos.map((m) => <div key={m.id} style={{ padding: '6px 0', borderBottom: '1px dashed var(--line)' }}><span className="mute2">{dshort(m.createdAt).split(' ')[0]}</span> {m.text}</div>) : <div className="mute2">메모가 없어요</div>}
        <div className="row" style={{ marginTop: 10 }}><MicButton onText={(t) => setMemo((x) => (x.trim() ? `${x.trim()} ${t}` : t))} /><input id="elder-memo-in" className="in grow" placeholder="특이사항·방문하고 느낀 점 (병명은 적지 않아요)" value={memo} onChange={(x) => setMemo(x.target.value)} aria-label="담당자 메모" /><button className="btn sm" style={{ width: 'auto', padding: '0 14px', flex: '0 0 auto' }} disabled={busy} onClick={() => run(async () => { if (!memo.trim()) return toast('메모를 적어 주세요'); await post(`/org/elders/${id}/memos`, { text: memo.trim() }); setMemo(''); reload(); toast('메모를 저장하고 읽기 쉽게 정리했어요'); })}>추가</button></div>
      </div>
      <div className="sec-t"><span>받은 도움 기록 · 요청</span></div>
      {myReqs.length ? myReqs.map((r) => <ReqRow key={r.id} r={{ ...r, myRole: 'MANAGER' }} />) : <Empty>진행 중인 요청이 없어요</Empty>}
      <BlockList elderId={id} />
      {!ended && <ElderClasses elderId={id} />}
      <div className="sec-t"><span>변화 리포트</span><span className="mute2" style={{ fontWeight: 500 }}>이전 상태와 비교</span></div>
      {myChange && <ChangeCard x={myChange} />}
      <button className="btn soft sm" style={{ margin: '10px 0' }} onClick={() => nav(`/o/report?elder=${id}`)}>최근 활동 기록 · 지난 월간 리포트 보기 ›</button>
      {rep ? <ReportView rep={rep} who={elderName(e)} /> : <Empty>아직 만들어진 리포트가 없어요</Empty>}
      <div className="ml" style={{ marginTop: 14 }}>
        <div role="switch" aria-checked={!!e.reportConsent} tabIndex={0} onClick={() => run(async () => { await put(`/org/elders/${id}/consent`, { reportConsent: !e.reportConsent }); await reload(); toast(!e.reportConsent ? '공유 동의를 입력했어요' : '공유 동의를 철회했어요'); })}><div className="grow">리포트 공유 동의 (대상에게 확인 후 대신 입력)<span>{e.reportConsent ? '동의함' : '동의 안 함'}</span></div><span className={'toggle ' + (e.reportConsent ? 'on' : '')} /></div>
        {!ended && <Mi t="현관 QR 카드" s={e.qrIssuedAt ? `${dshort(e.qrIssuedAt).split(' ')[0]} 발급 · 보기·인쇄·다시 발급` : '출력해 현관 안쪽에 붙여 두면 봉사자가 찍어 시작·종료를 기록해요'} i="qr" onClick={() => sheet(() => <DoorCardSheet elderId={id} name={elderName(e)} manage onClose={() => { closeSheet(); reload(); }} />)} />}
        {!ended && <Mi t="다른 담당자에게 이관" s="받는 담당자가 수락해야 넘어가요" i="users" onClick={transfer} />}
        {!ended && <div role="button" tabIndex={0} onClick={async () => {
          const n = myReqs.filter((r) => ACTIVE_ST.includes(r.status)).length;
          if (!(await confirm(`${elderName(e)} 지원을 종료할까요?`, n ? `진행 중인 요청 ${n}건을 먼저 정리해야 해요.` : '기록은 남고 새 요청은 할 수 없어요.', '종료', true))) return;
          run(async () => { await post(`/org/elders/${id}/end`); await reload(); toast('지원을 종료했어요'); });
        }}><Ic n="flag" s={20} /><div className="grow danger">지원 종료</div>›</div>}
      </div>
    </Screen>
  );
}

function TransferSheet({ e, onDone }) {
  const [mgrs] = useLoad(() => api('/org/managers'), []);
  const { me } = useAuth();
  const [to, setTo] = useState(null);
  const { toast } = useUi();
  const list = (mgrs || []).filter((m) => !me || m.id !== me.id);
  return (
    <>
      <h2>{elderName(e)} 이관 신청</h2>
      <label className="f">받는 담당자</label>
      {mgrs === undefined ? <Loading /> : list.length ? list.map((m) => <div key={m.id} className={'opt ' + (to === m.id ? 'on' : '')} role="button" tabIndex={0} onClick={() => setTo(m.id)}><span className="rd" /><div className="grow"><div className="b">{m.name}</div><div className="mute2">{m.orgName || ''} {m.loginId ? `· ${m.loginId}` : ''}</div></div></div>) : <Empty>같은 기관에 다른 담당자가 없어요</Empty>}
      <div className="notice" style={{ marginTop: 12 }}>받는 담당자가 수락하면 넘어가요. 진행 중인 요청도 함께 넘어가요.</div>
      <button className="btn" style={{ marginTop: 12 }} disabled={!to} onClick={async () => { try { await post(`/org/elders/${e.id}/transfer`, { toManagerId: to }); onDone(); toast('이관을 신청했어요'); } catch { /* 토스트 */ } }}>이관 신청</button>
    </>
  );
}

/* ---------- 도움 대상 등록 · 수정 (C-02) ---------- */
export function EForm() {
  const { id } = useParams();
  const nav = useNavigate();
  const { toast } = useUi();
  const [e] = useLoad(() => (id ? api(`/org/elders/${id}`) : Promise.resolve(null)), [id]);
  const [f, setF] = useState(null);
  const [agree, setAgree] = useState(!!id);
  const [busy, run] = useBusy();
  if (id && e === undefined) return <Screen title="도움 대상 수정" noTabs><Loading /></Screen>;
  const v = f || (e ? { alias: e.alias, dong: e.dong, ageGroup: e.ageGroup, needs: arr(e.needs), caution: e.caution || '' } : { alias: '', dong: '행복동', ageGroup: '80대', needs: [], caution: '' });
  const set = (k) => (x) => setF({ ...v, [k]: x && x.target ? x.target.value : x });
  return (
    <Screen title={id ? '도움 대상 수정' : '도움 대상 등록'} noTabs>
      <div className="lock"><Ic n="shield" s={18} /><div><b>이름·주소·연락처는 적지 않아요.</b> 기관이 따로 보관하고, 수락된 활동에만 담당자가 봉사자에게 알려요. 별칭은 봉사자에게 가려서 보여요.</div></div>
      <label className="f">별칭</label><input className="in" placeholder="예) 솔바람 · 꽃할머니 (실명 대신 부를 이름)" value={v.alias} onChange={set('alias')} />
      <label className="f">나이대 <span className="mute2" style={{ fontWeight: 500 }}>담당자에게만 보여요</span></label><ChipsPick options={['60대', '70대', '80대', '90대 이상']} value={v.ageGroup} onChange={set('ageGroup')} />
      <label className="f">사는 동</label><select className="in" value={v.dong} onChange={set('dong')}>{AREAS.map((a) => <option key={a}>{a}</option>)}</select>
      <label className="f">필요한 도움</label><ChipsPick multi options={CATS.map((c) => c.k)} value={v.needs} onChange={set('needs')} />
      <label className="f">방문 시 주의사항</label><textarea className="in" placeholder="예) 귀가 어두우세요 / 반려견이 있어요 (병명은 적지 않아요)" value={v.caution} onChange={set('caution')} />
      {!id && <div className={'su-check ' + (agree ? 'on' : '')} role="button" tabIndex={0} style={{ marginTop: 10 }} onClick={() => setAgree(!agree)}><span className="bx" /><div className="small">대상에게 서비스 이용을 설명하고 동의를 받았어요</div></div>}
      <button className="btn big-cta" style={{ marginTop: 16 }} disabled={busy} onClick={() => run(async () => {
        if (!v.alias.trim()) return toast('별칭을 적어 주세요');
        if (!v.needs.length) return toast('필요한 도움을 골라 주세요');
        if (!agree) return toast('대상에게 동의를 받았는지 체크해 주세요');
        const body = { alias: v.alias.trim(), dong: v.dong, ageGroup: v.ageGroup, needs: v.needs, caution: v.caution };
        if (id) { await put(`/org/elders/${id}`, body); toast('저장했어요'); nav(-1); }
        else { const r = await post('/org/elders', { ...body, consent: true }); toast('도움 대상을 등록했어요'); nav(`/o/elders/${r.id}`, { replace: true }); }
      })}>{id ? '저장' : '등록하기'}</button>
    </Screen>
  );
}

/* ---------- 요청 관리 ---------- */
export function OReqs() {
  const [sp, setSp] = useSearchParams();
  const seg = sp.get('seg') || 'need';
  const [reqs] = useLoad(() => api('/org/requests?box=all'), []);
  const list = reqs || [];
  const need = list.filter(needsMe);
  const going = list.filter((r) => ACTIVE_ST.includes(r.status) && !need.includes(r));
  const past = list.filter((r) => PAST_ST.includes(r.status));
  const show = seg === 'need' ? need : seg === 'going' ? going : past;
  return (
    <Screen title="요청 관리" back={false}>
      <div className="chips" style={{ marginBottom: 6 }}>{[['need', '처리할 일', need.length], ['going', '진행 중', going.length], ['past', '완료', past.length]].map(([k, t, n]) => <button key={k} className={'chip ' + (seg === k ? 'on' : '')} onClick={() => setSp({ seg: k }, { replace: true })}>{t} {n}</button>)}</div>
      {reqs === undefined ? <Loading /> : show.length ? show.map((r) => <ReqRow key={r.id} r={{ ...r, myRole: 'MANAGER' }} />) : <Empty>없어요</Empty>}
    </Screen>
  );
}

/* ---------- 생활 변화 리포트 (P-05) ---------- */
/* ---------- 받지 않기로 한 봉사자 (O1) ---------- */
function BlockList({ elderId }) {
  const { toast, confirm } = useUi();
  const [list, reload] = useLoad(() => api(`/org/elders/${elderId}/blocks`, { quiet: true }), [elderId]);
  if (!list || !list.length) return null;
  return (
    <>
      <div className="sec-t">받지 않기로 한 봉사자</div>
      {list.map((b) => (
        <div key={b.id} className="card item">
          <div className="grow"><div className="b">{b.volunteer ? b.volunteer.name : '봉사자'}</div><div className="mute2">{dshort(b.createdAt).split(' ')[0]} · 사유: {b.reason}</div></div>
          <button className="btn ghost sm" style={{ width: 'auto', padding: '0 12px' }} onClick={async () => {
            if (!(await confirm('받지 않기를 풀까요?', '이 봉사자가 다시 이 대상의 공고에 신청할 수 있어요.', '풀기'))) return;
            try { await del(`/org/blocks/${b.id}`); reload(); toast('받지 않기를 풀었어요'); } catch { /* 토스트 */ }
          }}>풀기</button>
        </div>
      ))}
    </>
  );
}

/* ---------- 변화 리포트 (C-16): 살펴볼 순서 + 최근 활동 기록 + 지난 월간 리포트 ---------- */
export function OReport() {
  const { toast } = useUi();
  const [sp, setSp] = useSearchParams();
  const range = sp.get('range') === 'today' ? 'today' : 'recent';
  const [elders] = useLoad(() => api('/org/elders'), []);
  const [chg] = useLoad(() => api(`/org/change-report${range === 'today' ? '?range=today' : ''}`), [range]);
  const [list, reload] = useLoad(() => api('/org/reports'), []);
  const reps = list || [];
  const active = (elders || []).filter((e) => e.status === 'ACTIVE');
  const sel = sp.get('elder') || (active[0] && String(active[0].id));
  const keep = (o) => { const n = {}; if (sp.get('elder')) n.elder = sp.get('elder'); if (range === 'today') n.range = 'today'; return { ...n, ...o }; };
  const items = (chg && chg.items) || [];
  return (
    <Screen title="관리 대상 리포트" back={false}>
      <div className="ai"><div className="ah"><Spk /><span className="mod m1">관리 대상 리포트 AI</span><Mod n={5} /></div><div className="small"><b>봉사 신청 내역</b>(성사·거절·만료)과 <b>활동 일지</b>를 모아 대상별 동향을 정리하고, <b>안부 확인 우선순위</b>를 매겼어요. 진단이나 건강 판단은 하지 않아요.</div><RuleNote source={chg && chg.source} /></div>
      <div className="chips" style={{ marginTop: 10 }}>{[['recent', `최근 ${(chg && chg.windowDays) || 30}일`], ['today', '오늘']].map(([k, t]) => <button key={k} className={'chip ' + (range === k ? 'on' : '')} onClick={() => { const n = keep({}); if (k === 'today') n.range = 'today'; else delete n.range; setSp(n, { replace: true }); }}>{t}</button>)}</div>
      {chg === undefined ? <Loading /> : items.length ? items.map((x) => <ChangeCard key={x.elderId} x={x} />) : <Empty>{range === 'today' ? '오늘 들어온 일지가 없어요' : '담당 대상이 없어요'}</Empty>}
      <div className="mute2" style={{ marginTop: 8 }}>신청·활동 횟수와 순서는 시스템이 계산하고, AI는 봉사자 일지 내용만 정리해요.</div>
      <div className="sec-t"><span>최근 활동 기록</span><span className="mute2" style={{ fontWeight: 500 }}>인수인계용</span></div>
      {elders === undefined ? <Loading /> : !active.length ? <Empty>담당 대상이 없어요</Empty> : (
        <>
          <div className="chips" style={{ marginBottom: 10, flexWrap: 'wrap' }}>{active.map((e) => <button key={e.id} className={'chip ' + (String(e.id) === sel ? 'on' : '')} onClick={() => setSp(keep({ elder: String(e.id) }), { replace: true })}>{elderName(e)}</button>)}</div>
          {sel && <RecentReport key={sel} subject={`elder:${sel}`} showMonthly={false} />}
          <div className="mute2" style={{ margin: '8px 2px' }}>전달 메모는 담당자만 봐요. 봉사자에게는 별칭·동·도움 내용·주의사항만 넘어가요.</div>
        </>
      )}
      <div className="sec-t">지난 월간 리포트</div>
      {list === undefined ? <Loading /> : !reps.length ? <Empty>아직 만들어진 월간 리포트가 없어요</Empty> : reps.map((r) => {
        let st = r.stats || r.statsJson || {};
        if (typeof st === 'string') { try { st = JSON.parse(st); } catch { st = {}; } }
        const series = st.prevCount != null ? [['지난달', Number(st.prevCount) || 0], ['이번 달', Number(st.count) || 0]] : null;
        return (
          <div className="card" style={{ marginTop: 10 }} key={r.id}>
            <div className="between"><span className="b" style={{ fontSize: 16 }}>{r.subjectLabel || `대상 #${r.subjectId}`} <span className="mute2" style={{ fontWeight: 500 }}>{r.month}</span></span>{r.bigChange ? <span className="st no">변화 큼</span> : <span className="st end">비슷함</span>}</div>
            {series && hbars(series.map((x) => (Array.isArray(x) ? x : [x.month, x.count])), r.bigChange ? '#E5484D' : '#3A6FD8')}
            <div className="small" style={{ marginTop: 8 }}>{r.narrative}</div>
            <RuleNote source={r.source} />
            {!r.bigChange && <button className="btn soft sm" style={{ marginTop: 10 }} onClick={async () => { try { await post(`/org/reports/${r.id}/flag`); reload(); toast('변화가 큰 사례로 표시했어요'); } catch { /* 토스트 */ } }}>변화 큰 사례로 표시</button>}
          </div>
        );
      })}
    </Screen>
  );
}

/* ---------- 담당자 마이 ---------- */
export function OMy() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast } = useUi();
  const logout = useLogout();
  return (
    <Screen title="마이" back={false}>
      <div className="row" style={{ gap: 14 }}><div className="avatar lg" style={{ background: '#E1ECFE', color: '#3A6FD8' }}>{me ? me.name[0] : ''}</div><div className="grow"><div className="b" style={{ fontSize: 20 }}>{me && me.name}</div><div className="mute2">{me && (me.orgName || (me.org && me.org.name))} · 담당자 계정</div></div></div>
      <div className="ml" style={{ marginTop: 16 }}>
        <Mi t="기관 정보" s={me && (me.orgName || (me.org && me.org.name)) || '기관'} i="building" onClick={() => toast(`${(me && (me.orgName || (me.org && me.org.name))) || '기관'} · 담당자 ${me && me.name}`)} />
        <Mi t="기관 소식 운영" s="기관 행사·교실·모임 올리기" i="cal" onClick={() => nav('/o/events')} />
        <Mi t="카테고리 건의" s="6가지 종류에 없는 도움을 운영자에게 건의" i="flag" onClick={() => nav('/my/suggest')} />
        <Mi t="알림" s="대상 관련 알림을 대신 받아요" i="bell" onClick={() => nav('/notif')} />
        <Mi t="알림 설정" s="담당 대상 관련 알림을 종류별로 켜고 꺼요" i="bell" onClick={() => nav('/my/noti')} />
        <TextSizeRow />
        <Mi t="1:1 문의 · 신고 내역" i="msg" onClick={() => nav('/qna')} />
        <Mi t="공지사항" i="file" onClick={() => nav('/notice')} />
        <Mi t="공공 서비스 안내" i="shield" onClick={() => nav('/public')} />
        <Mi t="내 정보 수정 · 비밀번호 변경" i="pen" onClick={() => nav('/my/info')} />
        <Mi t="로그아웃" i="logout" onClick={logout} />
        <Mi t="회원 탈퇴" s="담당 대상을 이관·종료한 뒤 탈퇴할 수 있어요" i="flag" onClick={() => nav('/my/withdraw')} />
      </div>
      <div className="notice" style={{ marginTop: 14 }}>담당자 계정으로는 봉사할 수 없어요. 봉사하려면 사용자 계정을 따로 만들어 주세요.</div>
    </Screen>
  );
}
