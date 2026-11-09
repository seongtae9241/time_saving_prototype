import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Logo, Avatar, Loading, Empty, RuleNote, QrSvg, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { fmt, hh, ap, dlabel, addDays, AREAS } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { useSpeechInput } from './voice.jsx';

const KI = { '요리': '<path d="M4 11h16v3a6 6 0 0 1-6 6h-4a6 6 0 0 1-6-6z"/><path d="M2 11h20M9 7c0-1 1-1.5 1-3M14 7c0-1 1-1.5 1-3"/>', '손재주': '<path d="M5 19L19 5"/><circle cx="18" cy="6" r="1.5"/><path d="M4 20c4-1 6-5 9-5s3 3 7 2"/>', '노래·이야기': '<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>', '디지털': '<rect x="7" y="2" width="10" height="20" rx="2.5"/><path d="M11 18h2"/>', '생활 지혜': '<path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3z"/>', '일·경험': '<rect x="3" y="7" width="18" height="13" rx="2.5"/><path d="M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2M3 13h18"/>' };
const KC = { '요리': ['#FFF1E2', '#EE8424'], '손재주': ['#F0ECFF', '#7656E3'], '노래·이야기': ['#FFEDF3', '#E2557F'], '디지털': ['#EAF0FF', '#3D6DF2'], '생활 지혜': ['#E4F5F9', '#178FAD'], '일·경험': ['#ECF1F8', '#3E5C8A'] };
const kc = (k) => KC[k] || KC['생활 지혜'];
export function KIc({ k, s = 44, bg }) {
  const n = Math.round(s * 0.52);
  return <span className="cat-ic" style={{ width: s, height: s, background: bg ? kc(k)[0] : '#fff', color: kc(k)[1] }}><svg viewBox="0 0 24 24" width={n} height={n} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" dangerouslySetInnerHTML={{ __html: KI[k] || KI['생활 지혜'] }} /></span>;
}
const isOne = (c) => c && c.mode === 'ONE';
/* 1:1은 1시간 = 1시간, 같이 해 보기는 × 0.5 (황수빈 v2 clCost) */
const cost = (c) => (isOne(c) ? Number(c.hours) : Math.round(Number(c.hours) * 0.5 * 2) / 2);
export const joined = (c) => Number(c.enrolledCount ?? c.joined ?? c.enrolled ?? c.applicants ?? 0);
const myEnroll = (c) => (c.myEnrollment && typeof c.myEnrollment === 'object' ? c.myEnrollment.status : c.myEnrollment) || c.myStatus || (c.applied ? 'APPLIED' : null);
const elderT = (c) => !!(c.elderTeacher ?? c.teacherElderId);
const teacherName = (c) => c.teacherName || (c.teacher && c.teacher.name) || '선생님';
const teacherAge = (c) => c.teacherAge || c.teacherAgeGroup || (c.teacher && c.teacher.ageGroup) || '';
const steps = (c) => { let s = c.steps || c.stepsJson || []; if (typeof s === 'string') { try { s = JSON.parse(s); } catch { s = []; } } return s.map((x) => (Array.isArray(x) ? { t: x[0], min: x[1] } : x)); };
const noteOf = (c) => { let n = c.note || c.noteJson; if (typeof n === 'string') { try { n = JSON.parse(n); } catch { n = null; } } return n; };
const Seats = ({ c }) => <div className="seat">{Array.from({ length: Number(c.capacity) || 0 }, (_, i) => <i key={i} className={i < joined(c) ? 'on' : ''} />)}<span className="mute2" style={{ marginLeft: 6 }}>{joined(c)}/{c.capacity}명</span></div>;
const TchBadge = ({ c }) => (elderT(c) ? <span className="tch" style={{ color: kc(c.kind)[1] }}><Ic n="star" s={12} /> 어르신 선생님</span> : <span className="tch" style={{ color: 'var(--sub)' }}>이웃 선생님</span>);

export function ClsMini({ c }) {
  const nav = useNavigate();
  return (
    <div className="cl-card" role="button" tabIndex={0} onClick={() => nav(`/cls/${c.id}`)}>
      <div className="cv" style={{ background: kc(c.kind)[0] }}><KIc k={c.kind} s={36} />{elderT(c) && <TchBadge c={c} />}</div>
      <div className="clb" style={{ padding: '12px 14px 14px' }}><div className="ct" style={{ fontSize: 15.5, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.title}</div><div className="mute2">{teacherName(c)} {teacherAge(c)} · {isOne(c) ? '1:1' : dlabel(c.date)}</div></div>
    </div>
  );
}
function ClCard({ c }) {
  const nav = useNavigate();
  const mine = myEnroll(c) && myEnroll(c) !== 'CANCELLED';
  return (
    <div className="cl-card" role="button" tabIndex={0} onClick={() => nav(`/cls/${c.id}`)}>
      <div className="cv" style={{ background: kc(c.kind)[0] }}><KIc k={c.kind} s={44} /><TchBadge c={c} /></div>
      <div className="clb">
        <div className="mute2" style={{ color: kc(c.kind)[1], fontWeight: 700 }}>{c.kind} · {teacherName(c)} {teacherAge(c)}{c.byOrg ? ' (기관)' : ''}</div>
        <div className="ct">{c.title}</div>
        <div className="mute2" style={{ marginTop: 4 }}>{dlabel(c.date)} {hh(c.startHour)}~{hh(Number(c.startHour) + Number(c.hours))} · {c.place || '장소 배정 중'}</div>
        <div className="between" style={{ marginTop: 4 }}><Seats c={c} />{mine ? <span className="st ok">신청함</span> : <span className="pill">수강 −{fmt(cost(c))}</span>}</div>
      </div>
    </div>
  );
}

/* ---------- 1:1 클래스 (황수빈 v2) ---------- */
export const slotTxt = (d, h) => `${dlabel(d)} ${ap(h)}`;
const slotOf = (x) => slotTxt(x.date ?? x.slotDate, x.hour ?? x.slotHour);
const tmOf = (iso) => (iso ? String(iso).slice(11, 16) : '');
const TAv = ({ c, s = 48 }) => <div className="avatar" style={{ width: s, height: s, fontSize: Math.round(s * 0.4), background: kc(c.kind)[0], color: kc(c.kind)[1] }}>{teacherName(c)[0]}</div>;
const WhoBadge = ({ c }) => (elderT(c) ? <span className="one-who e">어르신 선생님</span> : <span className="one-who">이웃 선생님</span>);
const myOne = (c) => (c && c.myOne && c.myOne.status !== 'CANCELLED' ? c.myOne : null);
const pendLbl = (p) => (p.status === 'WAIT' ? ['수락 대기', 'wait'] : p.status === 'DONE' ? ['만남 끝', 'end'] : ['확정', 'ok']);
function MineRow({ c, sub, st, cl }) {
  const nav = useNavigate();
  return <div className="card item click" role="button" tabIndex={0} onClick={() => nav(`/cls/${c.id}`)}><KIc k={c.kind} s={42} bg /><div className="grow" style={{ minWidth: 0 }}><div className="b" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.title}</div><div className="mute2">{sub}</div></div><span className={'st ' + cl}>{st}</span></div>;
}
function OneCard({ c }) {
  const nav = useNavigate();
  const p = myOne(c);
  return (
    <div className="card one-card click" role="button" tabIndex={0} onClick={() => nav(`/cls/${c.id}`)}>
      <div className="row" style={{ gap: 12, alignItems: 'flex-start' }}><TAv c={c} /><div className="grow" style={{ minWidth: 0 }}>
        <div className="between" style={{ gap: 8 }}><div className="b">{teacherName(c)} <span className="mute2" style={{ fontWeight: 500 }}>{teacherAge(c)}{c.byOrg ? ' · 기관' : ''}</span></div>{p ? <span className={'st ' + pendLbl(p)[1]}>{pendLbl(p)[0]}</span> : <WhoBadge c={c} />}</div>
        <div className="mute2" style={{ marginTop: 2 }}>{c.bio}</div></div></div>
      <div className="ct" style={{ marginTop: 12 }}>{c.title}</div>
      <div className="one-tags">{(c.tags || []).map((t) => <span key={t}>{t}</span>)}</div>
      <div className="one-ft"><Ic n="pin" s={14} /><span className="grow">{c.meet}</span><b>1:1 · {fmt(c.hours)}시간</b></div>
    </div>
  );
}

let clsModeMem = 'one';
/* ---------- 동네 클래스 목록 ---------- */
export function Cls() {
  const nav = useNavigate();
  const [f, setF] = useState('전체');
  const [mode, setModeS] = useState(clsModeMem);
  const setMode = (m) => { clsModeMem = m; setModeS(m); };
  const [list] = useLoad(() => api('/classes'), []);
  const [mine] = useLoad(() => api('/classes/mine', { quiet: true }), []);
  const [done] = useLoad(() => api('/classes?status=DONE', { quiet: true }), []);
  const kinds = ['전체', ...Object.keys(KC)];
  const pool = (list || []).filter((c) => c.status === 'OPEN' && (isOne(c) ? 'one' : 'group') === mode && (f === '전체' || c.kind === f));
  const teaching = (mine && mine.teaching) || [];
  const applied = (mine && mine.applied) || [];
  const past = (done && done.length ? done : (mine && mine.past) || []);
  const tRow = (c) => {
    if (!isOne(c)) return <MineRow key={'t' + c.id} c={c} sub={`내가 선생님 · ${dlabel(c.date)}`} st={{ OPEN: '모집 중', SPACE_PENDING: '공간 배정 대기', DONE: '끝남', CANCELLED: '취소' }[c.status] || c.status} cl={c.status === 'OPEN' ? 'go' : c.status === 'DONE' ? 'ok' : 'wait'} />;
    const n = Number(c.newRequests || 0);
    return <MineRow key={'t' + c.id} c={c} sub="내가 선생님 · 1:1" st={n ? `새 신청 ${n}` : '신청 받는 중'} cl={n ? 'go' : 'ok'} />;
  };
  const aRow = (c) => {
    const p = myOne(c);
    if (!isOne(c) || !p) return <MineRow key={'a' + c.id} c={c} sub={`수강 신청 · ${dlabel(c.date)} ${ap(c.startHour)}`} st={myEnroll(c) === 'ATTENDED' ? '출석' : '신청함'} cl="ok" />;
    const [l, k] = pendLbl(p);
    return <MineRow key={'a' + c.id} c={c} sub={`1:1 · ${teacherName(c)} 선생님 · ${slotOf(p)}`} st={l} cl={k} />;
  };
  const appliedShown = applied.filter((c) => !isOne(c) || myOne(c));
  return (
    <Screen title="동네 클래스" back={false}>
      <div className="ai-ban" style={{ marginTop: 0, background: 'linear-gradient(135deg,#FFF4E6 0%,#FFF0F4 100%)' }}><div className="grow"><div className="ah2" style={{ color: '#C2385F' }}><Ic n="star" s={13} /> 서로 가르쳐 주는 클래스</div><div className="b" style={{ fontSize: 17, marginTop: 4 }}>어르신께는 살아온 노하우를,<br />청년에게는 휴대폰 쓰는 법을</div><div className="mute2" style={{ marginTop: 4 }}>1:1로 한 시간, 열린 곳에서 만나요</div></div><div className="ai-orb" style={{ background: '#E2557F', boxShadow: '0 8px 18px rgba(226,85,127,.3)' }}><Ic n="cap" s={28} /></div></div>
      <div className="seg" style={{ marginTop: 16 }} role="tablist"><button role="tab" aria-selected={mode === 'one'} className={mode === 'one' ? 'on' : ''} onClick={() => setMode('one')}>1:1 배우기</button><button role="tab" aria-selected={mode === 'group'} className={mode === 'group' ? 'on' : ''} onClick={() => setMode('group')}>같이 해 보기 · 4명까지</button></div>
      <div className="chips" style={{ marginTop: 12 }}>{kinds.map((k) => <button key={k} className={'chip ' + (f === k ? 'on' : '')} onClick={() => setF(k)}>{k}</button>)}</div>
      {(teaching.length > 0 || appliedShown.length > 0) && <div className="sec-t">내 수업</div>}
      {teaching.map(tRow)}
      {appliedShown.map(aRow)}
      <div className="sec-t"><span>{mode === 'one' ? '지금 신청할 수 있는 선생님' : '모집 중'} {pool.length}</span></div>
      <div className="mute2" style={{ margin: '-4px 0 10px' }}>{mode === 'one' ? '궁금한 걸 한 사람에게 직접 물어봐요. 누가 가르치든 한 시간은 한 시간이에요.' : '요리·바느질처럼 손으로 같이 해 보는 수업이에요. 장소는 기관이 빌려줘요.'}</div>
      {list === undefined ? <Loading /> : pool.length ? pool.map((c) => (mode === 'one' ? <OneCard key={c.id} c={c} /> : <ClCard key={c.id} c={c} />)) : <Empty>이 종류의 수업이 아직 없어요</Empty>}
      {past.length > 0 && <div className="sec-t">지난 수업</div>}
      {past.map((c) => <div key={'p' + c.id} className="card item click" role="button" tabIndex={0} onClick={() => nav(`/cls/${c.id}/note`)}><KIc k={c.kind} s={42} bg /><div className="grow"><div className="b">{c.title}</div><div className="mute2">{teacherName(c)} 선생님 · {dlabel(c.date)}{noteOf(c) ? ' · 배움 노트 있음' : ''}</div></div>›</div>)}
      <button className="fab" onClick={() => nav('/cls/new')}><Ic h={I.plus} /> 수업 열기</button>
    </Screen>
  );
}

function AttendSheet({ c, mode, onDone, onClose }) {
  const [code, setCode] = useState('');
  const [busy, run] = useBusy();
  return (
    <>
      <h2>{mode === 'in' ? '출석 QR 찍기' : '마침 QR 찍기'}</h2>
      <p className="muted" style={{ margin: 0 }}>{c.place || '교실'} · 선생님 화면의 번호 6자리를 입력해요.</p>
      <div className="qr-cam"><div className="qr-frame"><i className="c1" /><i className="c2" /><i className="c3" /><i className="c4" /><div className="qr-line" /></div><div className="qr-hint">카메라 인식은 준비 중이에요 · 번호로 확인해요</div></div>
      <form className="row" style={{ marginTop: 10 }} onSubmit={(e) => { e.preventDefault(); run(async () => { await post(`/classes/${c.id}/attend`, { code, mode }); onClose(); onDone(); }); }}>
        <input className="in grow" inputMode="numeric" maxLength={6} placeholder="번호 6자리" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} aria-label="출석 번호" />
        <button className="btn sm" style={{ width: 'auto', padding: '0 18px', flex: '0 0 auto' }} disabled={busy || code.length !== 6}>확인</button>
      </form>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}
function ClsQrSheet({ c, onClose }) {
  const [q] = useLoad(() => api(`/classes/${c.id}/qr`), [c.id]);
  const code = q && (q.code || q.checkCode);
  return (
    <>
      <h2>출석 QR</h2>
      <p className="muted" style={{ margin: 0 }}>{c.title} · 수강생이 들어올 때 이 QR(번호)을 찍어요.</p>
      <div className="qr-show">{q === undefined ? <Loading /> : <><div className="qr-box"><QrSvg text={(q && q.qrText) || `TS-CLASS:${c.id}:${code}`} size="min(220px,62vw)" /></div><div className="b" style={{ fontSize: 30, letterSpacing: 6 }}>{code}</div></>}</div>
      <div className="sum" style={{ marginTop: 12 }}><div className="between"><span className="muted">신청</span><b>{joined(c)}/{c.capacity}명</b></div>{q && q.attended != null && <div className="between"><span className="muted">출석</span><b>{q.attended}명</b></div>}</div>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}
function FinishSheet({ c, onDone }) {
  const [txt, setTxt] = useState(steps(c).map((s) => s.t).join('\n'));
  const [tip, setTip] = useState('');
  const { toast } = useUi();
  return (
    <>
      <h2>수업 마치기 · 배움 노트</h2>
      <p className="muted">배운 순서를 한 줄씩 적으면 배움 노트로 남아요. 시간은 출석한 수강생 기준으로 정산돼요.</p>
      <label className="f">배운 순서</label><textarea className="in" value={txt} onChange={(e) => setTxt(e.target.value)} />
      <label className="f">선생님 한마디</label><input className="in" value={tip} onChange={(e) => setTip(e.target.value)} placeholder="예) 된장은 한 김 식힌 다음에 풀어야 해" />
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => { try { await post(`/classes/${c.id}/finish`, { steps: txt.split('\n').map((s) => s.trim()).filter(Boolean), tip }); onDone(); toast('수업을 마쳤어요. 배움 노트가 만들어졌어요'); } catch { /* 토스트 */ } }}>마치고 정산하기</button>
    </>
  );
}

/* ---------- 클래스 상세 ---------- */
export function ClsDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { me, refresh } = useAuth();
  const { toast, confirm, sheet, closeSheet } = useUi();
  const [c, reload] = useLoad(() => api(`/classes/${id}`), [id]);
  const [busy, run] = useBusy();
  if (c === undefined) return <Screen title="클래스 상세" noTabs><Loading /></Screen>;
  if (!c) return <Screen title="클래스 상세" noTabs><Empty>수업을 찾을 수 없어요</Empty></Screen>;
  if (isOne(c)) return <OneDetail c={c} reload={reload} />;
  const st = steps(c);
  const tot = st.reduce((a, b) => a + (Number(b.min) || 0), 0);
  const teach = c.host ?? (me && (c.teacherId === me.id || c.createdBy === me.id));
  const en = myEnroll(c);
  const mine = en && en !== 'CANCELLED';
  const full = joined(c) >= Number(c.capacity);
  let action = null;
  if (teach) {
    action = (
      <>
        <div className="notice">내가 연 수업이에요. {c.status === 'SPACE_PENDING' ? '담당자가 장소를 배정하면 모집이 시작돼요.' : c.status === 'OPEN' ? '모집 중이에요.' : c.status === 'DONE' ? '끝난 수업이에요.' : ''}</div>
        {c.status === 'OPEN' && <button className="btn" style={{ marginTop: 10 }} onClick={() => sheet(() => <ClsQrSheet c={c} onClose={closeSheet} />)}><Ic n="qr" s={20} /> 출석 QR 띄우기</button>}
        {c.status === 'OPEN' && <button className="btn ghost" style={{ marginTop: 8 }} onClick={() => sheet(() => <FinishSheet c={c} onDone={() => { closeSheet(); reload(); refresh(); }} />)}>수업 마치기 · 배움 노트</button>}
        {c.status === 'DONE' && <button className="btn soft" style={{ marginTop: 8 }} onClick={() => nav(`/cls/${c.id}/note`)}>배움 노트 보기</button>}
      </>
    );
  } else if (c.status === 'DONE') {
    action = <button className="btn soft" onClick={() => nav(`/cls/${c.id}/note`)}>배움 노트 보기</button>;
  } else if (c.status === 'OPEN' && me && me.role === 'USER') {
    if (en === 'ATTENDED') action = <><div className="chk on" style={{ marginTop: 0 }}><span className="dotp" /><div className="grow"><b>출석 완료</b><div className="mute2">묶여 있던 {fmt(cost(c))}시간이 정산됐어요 · 끝나면 후기를 남겨 주세요</div></div></div><ReviewBox c={c} /></>;
    else if (mine) action = <><div className="btns"><button className="btn ghost" disabled={busy} onClick={async () => { if (await confirm('수강 신청을 취소할까요?', '하루 전까지는 무료로 취소되고 묶인 시간이 돌아와요.', '취소하기', true)) run(async () => { await post(`/classes/${c.id}/cancel`); await reload(); refresh(); toast('신청을 취소했어요'); }); }}>신청 취소</button><button className="btn" onClick={() => sheet(() => <AttendSheet c={c} mode="in" onClose={closeSheet} onDone={async () => { await reload(); refresh(); toast(`출석했어요. ${fmt(cost(c))}시간이 정산됐어요`); }} />)}><Ic n="qr" s={20} /> 출석 QR 찍기</button></div><div className="mute2 center" style={{ marginTop: 8 }}>수업 시작 때 선생님 화면의 QR(번호)을 찍어요</div></>;
    else if (full) action = <button className="btn" disabled style={{ opacity: 0.45 }}>정원이 찼어요</button>;
    else action = <button className="btn" disabled={busy} onClick={async () => {
      if (!(await confirm('수강 신청할까요?', <>{dlabel(c.date)} {ap(c.startHour)} · {c.place}<br />신청하면 {fmt(cost(c))}시간이 묶이고, 출석하면 빠져요. 하루 전까지는 무료로 취소돼요.</>, '신청하기'))) return;
      run(async () => { await post(`/classes/${c.id}/apply`); await reload(); refresh(); toast('수강 신청했어요. 수업 날 QR로 출석해요'); });
    }}>수강 신청 · −{fmt(cost(c))}시간</button>;
  }
  return (
    <Screen title="클래스 상세" noTabs>
      <div className="cl-card" style={{ cursor: 'default' }}><div className="cv" style={{ background: kc(c.kind)[0], height: 120 }}><KIc k={c.kind} s={56} /><TchBadge c={c} /></div><div className="clb"><div className="mute2" style={{ color: kc(c.kind)[1], fontWeight: 700 }}>{c.kind}</div><div className="ct" style={{ fontSize: 20 }}>{c.title}</div><div className="small muted" style={{ marginTop: 6 }}>{c.intro}</div></div></div>
      <div className="card item" style={{ marginTop: 12 }}><Avatar name={teacherName(c)} /><div className="grow"><div className="b">{teacherName(c)} 선생님 <span className="mute2" style={{ fontWeight: 500 }}>{teacherAge(c)}</span></div><div className="mute2">{c.teacherElderId ? '휴대폰이 없어 담당자가 대신 열었어요' : elderT(c) ? '동네 어르신 선생님' : '이웃 선생님'}</div></div>{c.teacherElderId && <span className="badge elder">기관</span>}</div>
      {st.length > 0 && <div className="ai" style={{ marginTop: 12 }}><div className="ah"><Spk />AI가 정리한 수업 계획<Mod n={1} /></div><ol className="curri">{st.map((s, i) => <li key={i}>{s.t}<span>{s.min}분</span></li>)}</ol><div className="mute2" style={{ marginTop: 6 }}>총 {tot}분 · 선생님 설명을 단계로 나눴어요</div></div>}
      <div className="card" style={{ marginTop: 12 }}>
        <dl className="kv"><dt>일시</dt><dd>{dlabel(c.date)} {ap(c.startHour)} ~ {hh(Number(c.startHour) + Number(c.hours))}</dd><dt>장소</dt><dd>{c.place || '담당자가 공간을 배정하고 있어요'}</dd><dt>준비물</dt><dd>{c.items || '-'}</dd><dt>주의</dt><dd>{c.caution || '-'}</dd><dt>판단</dt><dd><span className={'st ' + (c.level === 'CAUTION' || c.level === '주의' ? 'wait' : 'ok')}>{{ ALLOW: '허용', CAUTION: '주의' }[c.level] || c.level || '허용'}</span>{c.levelReason && <span className="mute2"> {c.levelReason}</span>}</dd></dl>
        <Seats c={c} />
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="b">시간은 이렇게 정산돼요</div>
        <div className="settle"><span className="muted">수강하면 (수업 {fmt(c.hours)}시간 × 0.5, 30분 단위)</span><b style={{ color: '#E5484D' }}>−{fmt(cost(c))}시간</b><span className="muted">선생님 적립 (수업 + 준비 1시간)</span><b style={{ color: 'var(--brand)' }}>+{fmt(Number(c.hours) + 1)}시간</b><span className="muted">{c.capacity}명이 모두 오면 남는 시간</span><b>{fmt(Math.max(0, c.capacity * cost(c) - (Number(c.hours) + 1)))}시간 → 공동 적립</b></div>
        <div className="mute2" style={{ marginTop: 8 }}>남는 시간은 공동 적립으로 모여 기관 대상 무차감 도움에 쓰여요. 출석은 수업 시작 QR로 확인해요.</div>
      </div>
      <div style={{ marginTop: 16 }}>{action}</div>
    </Screen>
  );
}

/* ---------- 1:1 상세 (황수빈 v2 oneDetail) ---------- */
function OneApplySheet({ c, onDone, onClose }) {
  const slots = c.slots || [];
  const [pick, setPick] = useState(0);
  const [q, setQ] = useState('');
  const [busy, run] = useBusy();
  const { toast } = useUi();
  const ta = useRef(null);
  return (
    <>
      <h2>{teacherName(c)} 선생님께 1:1 신청</h2><p className="muted" style={{ margin: '0 0 14px' }}>{c.meet} · {fmt(c.hours)}시간</p>
      <div className="b small" style={{ marginBottom: 8 }}>언제 만날까요?</div>
      {slots.map((x, i) => <div key={i} className={'opt ' + (pick === i ? 'on' : '')} role="radio" aria-checked={pick === i} tabIndex={0} onClick={() => setPick(i)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setPick(i); } }}><span className="rd" /><div className="grow b">{slotOf(x)}</div></div>)}
      <label className="b small" htmlFor="oneq" style={{ display: 'block', margin: '18px 0 8px' }}>무엇이 궁금하세요?</label>
      <textarea className="in" id="oneq" rows={3} ref={ta} placeholder="선생님께 미리 전해 드려요. 짧게 적어도 괜찮아요" value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="one-qs">{(c.qs || []).map((x) => <button key={x} type="button" onClick={() => { setQ(x); if (ta.current) ta.current.focus(); }}>{x}</button>)}</div>
      <button className="btn" style={{ marginTop: 16 }} disabled={busy} onClick={() => {
        if (!q.trim()) return toast('궁금한 점을 한 줄만 적어 주세요');
        const x = slots[pick] || slots[0];
        run(async () => { await post(`/classes/${c.id}/one/apply`, { slotDate: x.date, slotHour: x.hour, question: q.trim() }); onClose(); onDone(); });
      }}>신청하기 · −{fmt(c.hours)}시간</button>
    </>
  );
}
function OneScanSheet({ c, m, onDone, onClose }) {
  const [code, setCode] = useState('');
  const [busy, run] = useBusy();
  return (
    <>
      <h2>{m === 'in' ? '시작 QR 찍기' : '마침 QR 찍기'}</h2>
      <p className="muted" style={{ margin: 0 }}>{c.meet} · {c.byOrg ? '담당자가 띄운' : '선생님 휴대폰 화면의'} QR을 찍어 주세요.</p>
      <div className="qr-cam"><div className="qr-frame"><i className="c1" /><i className="c2" /><i className="c3" /><i className="c4" /><div className="qr-line" /></div><div className="qr-hint">카메라 인식은 준비 중이에요 · 번호로 확인해요</div></div>
      <form className="row" style={{ marginTop: 10 }} onSubmit={(e) => { e.preventDefault(); run(async () => { await post(`/classes/${c.id}/one/${m === 'in' ? 'start' : 'end'}`, { code }); onClose(); onDone(); }); }}>
        <input className="in grow" inputMode="numeric" maxLength={6} placeholder="번호 6자리" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} aria-label="QR 번호" />
        <button className="btn sm" style={{ width: 'auto', padding: '0 18px', flex: '0 0 auto' }} disabled={busy || code.length !== 6}>확인</button>
      </form>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}
function OneQrShowSheet({ c, onClose }) {
  const [q] = useLoad(() => api(`/classes/${c.id}/qr`), [c.id]);
  const code = q && q.code;
  return (
    <>
      <h2>만남 QR</h2>
      <p className="muted" style={{ margin: 0 }}>{c.title} · 만나면 배우는 분이 이 QR을 찍어요. 시작할 때 한 번, 헤어질 때 한 번이에요.</p>
      <div className="qr-show">{q === undefined ? <Loading /> : <><div className="qr-box"><QrSvg text={(q && q.qrText) || `TS-CLASS:${c.id}:${code}`} size="min(220px,62vw)" /></div><div className="b" style={{ fontSize: 30, letterSpacing: 6 }}>{code}</div><div className="mute2">QR이 안 찍히면 이 번호 6자리를 불러 주세요</div></>}</div>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}
function OneProposeSheet({ c, r, onDone, onClose }) {
  const opts = (c.slots || []).filter((x) => !(x.date === r.slotDate && Number(x.hour) === Number(r.slotHour)));
  const [pick, setPick] = useState(0);
  const [busy, run] = useBusy();
  return (
    <>
      <h2>다른 시간 제안</h2><p className="muted" style={{ margin: '0 0 14px' }}>{r.userName} 님이 고른 시간: {slotOf(r)}</p>
      {opts.length ? opts.map((x, i) => <div key={i} className={'opt ' + (pick === i ? 'on' : '')} role="radio" aria-checked={pick === i} tabIndex={0} onClick={() => setPick(i)}><span className="rd" /><div className="grow b">{slotOf(x)}</div></div>) : <Empty>제안할 다른 시간이 없어요</Empty>}
      <button className="btn" style={{ marginTop: 16 }} disabled={busy || !opts.length} onClick={() => run(async () => { const x = opts[pick]; await post(`/classes/one/enrollments/${r.id}/propose`, { date: x.date, hour: x.hour }); onClose(); onDone(); })}>이 시간으로 제안하기</button>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}

/** 선생님(또는 담당자)이 보는 1:1 신청 카드 (프로토타입 reqCards) */
export function OneReqCards({ c, onChange }) {
  const { toast, sheet, closeSheet } = useUi();
  const [busy, run] = useBusy();
  const rq = c.requests || [];
  if (!rq.length) return <div className="empty" style={{ marginTop: 10 }}>아직 신청이 없어요. 신청이 오면 알려 드려요</div>;
  return rq.map((r) => (
    <div key={r.id} className="card" style={{ marginTop: 10 }}>
      <div className="row" style={{ gap: 12 }}><Avatar name={r.userName} /><div className="grow"><div className="b">{r.userName} <span className="mute2" style={{ fontWeight: 500 }}>{r.userAgeGroup || ''}</span></div><div className="mute2">{slotOf(r)} · {c.meet}</div></div>
        {r.status === 'OK' ? <span className="st ok">확정</span> : r.status === 'MET' ? <span className="st go">만나는 중</span> : r.proposed ? <span className="st wait">시간 제안함</span> : <span className="st go">새 신청</span>}</div>
      <div className="one-quote">“{r.question}”</div>
      {r.status === 'OK' || r.status === 'MET'
        ? <button className="btn" style={{ marginTop: 10 }} onClick={() => sheet(() => <OneQrShowSheet c={c} onClose={closeSheet} />)}><Ic n="qr" s={20} /> 만남 QR 띄우기</button>
        : r.proposed ? <div className="mute2" style={{ marginTop: 10 }}>제안한 시간에 {r.userName} 님이 답하면 확정돼요</div>
          : <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost" onClick={() => sheet(() => <OneProposeSheet c={c} r={r} onClose={closeSheet} onDone={() => { onChange(); toast('다른 시간을 제안했어요'); }} />)}>다른 시간 제안</button><button className="btn" disabled={busy} onClick={() => run(async () => { await post(`/classes/one/enrollments/${r.id}/accept`); await onChange(); toast(`수락했어요. ${r.userName} 님께 알려 드렸어요`); })}>수락하기</button></div>}
    </div>
  ));
}

function OneNoteBox({ c, onDone }) {
  const [t, setT] = useState('');
  const [busy, run] = useBusy();
  const { toast } = useUi();
  return <><label className="b" htmlFor="onenote">오늘 배운 한 줄</label><div className="mute2" style={{ margin: '2px 0 8px' }}>남기면 선생님께 고마움이 전해지고, 내 배움 노트에 쌓여요</div><textarea className="in" id="onenote" rows={2} placeholder="예) 옮길지 말지보다 3년 뒤 뭘 하고 싶은지 먼저" value={t} onChange={(e) => setT(e.target.value)} /><button className="btn sm" style={{ marginTop: 8 }} disabled={busy} onClick={() => { if (!t.trim()) return toast('한 줄만 적어 주세요'); run(async () => { await post(`/classes/${c.id}/one/note`, { text: t.trim() }); await onDone(); toast('배움 노트에 남겼어요'); }); }}>남기기</button></>;
}

function OneDetail({ c, reload }) {
  const { me, refresh } = useAuth();
  const { toast, confirm, sheet, closeSheet } = useUi();
  const [busy, run] = useBusy();
  const teach = !!c.host;
  const pd = myOne(c);
  const again = async () => { await reload(); refresh(); };
  const openApply = () => {
    if (Number(c.left) <= 0) return toast('이번 주 자리가 다 찼어요. 다음 주에 다시 열려요');
    sheet(() => <OneApplySheet c={c} onClose={closeSheet} onDone={async () => { await again(); toast(c.byOrg ? '신청했어요. 담당자가 어르신께 여쭤보고 알려 드려요' : '신청했어요. 선생님이 수락하면 알려 드려요'); }} />);
  };
  const cancel = async () => { if (await confirm('1:1 신청을 취소할까요?', '하루 전까지는 무료로 취소되고 묶인 시간이 돌아와요.', '취소하기', true)) run(async () => { await post(`/classes/${c.id}/one/cancel`); await again(); toast('신청을 취소했어요. 묶인 시간을 돌려 드렸어요'); }); };
  const scan = (m) => sheet(() => <OneScanSheet c={c} m={m} onClose={closeSheet} onDone={async () => { await again(); toast(m === 'in' ? `만남을 시작했어요. ${fmt(c.hours)}시간이 정산됐어요` : '오늘 만남이 끝났어요. 배운 걸 한 줄 남겨 볼까요?'); }} />);
  let act = null;
  if (teach) {
    act = <><div className="notice">내가 연 1:1 수업이에요. 신청이 오면 수락해 주세요. 한 주에 {c.weeklyCap}명까지만 받아요.</div><OneReqCards c={c} onChange={again} /></>;
  } else if (!me || me.role !== 'USER') {
    act = null;
  } else if (!pd) {
    act = Number(c.left) > 0
      ? <><button className="btn" onClick={openApply}>1:1 신청하기 · −{fmt(c.hours)}시간</button><div className="mute2 center" style={{ marginTop: 8 }}>{c.byOrg ? '담당자가 어르신께 여쭤보고 수락해요' : '선생님이 수락하면 확정돼요'} · 하루 전까지 무료 취소</div></>
      : <button className="btn" disabled style={{ opacity: 0.45 }}>이번 주 자리가 다 찼어요</button>;
  } else if (pd.status === 'WAIT') {
    act = pd.proposed
      ? <><div className="chk" style={{ marginTop: 0 }}><span className="dotp" style={{ background: '#EE8424', animation: 'none', boxShadow: 'none' }} /><div className="grow"><b>{c.byOrg ? '담당자가 다른 시간을 제안했어요' : '선생님이 다른 시간을 제안했어요'}</b> · {slotOf(pd)}<div className="mute2">보낸 질문 “{pd.question}”</div></div></div>
        <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost" disabled={busy} onClick={cancel}>신청 취소</button><button className="btn soft" disabled={busy} onClick={() => run(async () => { await post(`/classes/${c.id}/one/accept-proposal`); await again(); toast('확정됐어요. 약속한 날 만나요'); })}>이 시간으로 할게요</button></div></>
      : <><div className="chk" style={{ marginTop: 0 }}><span className="dotp" style={{ background: '#EE8424', animation: 'none', boxShadow: 'none' }} /><div className="grow"><b>{c.byOrg ? '담당자 확인을 기다려요' : '선생님 수락을 기다려요'}</b> · {slotOf(pd)}<div className="mute2">보낸 질문 “{pd.question}”</div></div></div>
        <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost" disabled={busy} onClick={cancel}>신청 취소</button></div></>;
  } else if (pd.status === 'OK') {
    act = <><div className="chk" style={{ marginTop: 0 }}><span className="dotp" style={{ background: 'var(--brand)', animation: 'none', boxShadow: 'none' }} /><div className="grow"><b>만남이 확정됐어요</b> · {slotOf(pd)}<div className="mute2">{c.meet}에서 만나요. 만나면 선생님 화면의 QR을 찍어요</div></div></div>
      <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost" disabled={busy} onClick={cancel}>신청 취소</button><button className="btn" onClick={() => scan('in')}><Ic n="qr" s={20} /> 시작 QR 찍기</button></div><div className="mute2 center" style={{ marginTop: 8 }}>약속 날이 아니어도 미리 만나면 찍을 수 있어요</div></>;
  } else if (pd.status === 'MET') {
    act = <><div className="chk on" style={{ marginTop: 0 }}><span className="dotp" /><div className="grow"><b>만나는 중</b> · {tmOf(pd.checkInAt)} 시작 <span className="qr-by">QR 확인</span><div className="mute2">묶여 있던 {fmt(c.hours)}시간이 정산됐어요 · 헤어질 때 마침 QR을 찍어 주세요</div></div></div><button className="btn" style={{ marginTop: 10 }} onClick={() => scan('out')}><Ic n="qr" s={20} /> 마침 QR 찍기</button></>;
  } else {
    act = <><div className="chk" style={{ marginTop: 0 }}><span className="dotp" style={{ background: '#1F9D63', animation: 'none', boxShadow: 'none' }} /><div className="grow"><b>만남 끝</b> · {tmOf(pd.checkInAt)} ~ {tmOf(pd.checkOutAt)}<div className="mute2">{teacherName(c)} 선생님께 {fmt(c.hours)}시간이 적립돼요</div></div></div>
      <div className="card" style={{ marginTop: 12 }}>{pd.note ? <><div className="b">오늘 배운 한 줄</div><div className="one-quote" style={{ marginTop: 8 }}>“{pd.note}”</div><div className="mute2" style={{ marginTop: 6 }}>선생님께도 전해 드렸어요</div></> : <OneNoteBox c={c} onDone={again} />}</div>
      <button className="btn soft" style={{ marginTop: 10 }} onClick={openApply}>{teacherName(c)} 선생님 다시 만나기</button></>;
  }
  return (
    <Screen title="1:1 클래스" noTabs>
      <div className="card one-hero"><div className="row" style={{ gap: 14 }}><TAv c={c} s={60} /><div className="grow" style={{ minWidth: 0 }}><div className="b" style={{ fontSize: 18 }}>{teacherName(c)} 선생님 <span className="mute2" style={{ fontWeight: 500 }}>{teacherAge(c)}</span></div><div className="mute2" style={{ marginTop: 2 }}>{c.bio}</div><div style={{ marginTop: 8 }}><WhoBadge c={c} /></div></div></div>
        <div className="ct" style={{ fontSize: 20, marginTop: 16 }}>{c.title}</div><div className="small muted" style={{ marginTop: 6 }}>{c.intro}</div>
        <div className="one-tags">{(c.tags || []).map((t) => <span key={t}>{t}</span>)}</div>
        <div className="mute2" style={{ marginTop: 12 }}>{c.met ? `지금까지 ${c.met}명과 만났어요` : '새로 열린 수업이에요'}</div></div>
      {c.byOrg && <div className="notice" style={{ marginTop: 12 }}>휴대폰이 없으셔서 담당자{c.managerName ? ` ${c.managerName} 님` : ''}이 신청을 대신 받아요. 만나는 날엔 담당자가 QR을 띄워 드려요.</div>}
      {(c.qs || []).length > 0 && <div className="card" style={{ marginTop: 12 }}><div className="b">이런 걸 물어볼 수 있어요</div><ul className="one-q">{c.qs.map((q) => <li key={q}><Ic n="chat" s={16} /><span>{q}</span></li>)}</ul></div>}
      <div className="card" style={{ marginTop: 12 }}><dl className="kv"><dt>방식</dt><dd>1:1 · {fmt(c.hours)}시간</dd><dt>만나는 곳</dt><dd>{c.meet}</dd><dt>가능한 시간</dt><dd>{(c.slots || []).map((x, i) => <span key={i}>{i > 0 && <br />}{slotOf(x)}</span>)}</dd><dt>이번 주</dt><dd>{Number(c.left) > 0 ? `${c.left}자리 남았어요` : '자리가 다 찼어요'}</dd></dl></div>
      <div className="card" style={{ marginTop: 12 }}><div className="b">시간은 이렇게 정산돼요</div><div className="settle"><span className="muted">배우는 사람</span><b style={{ color: '#E5484D' }}>−{fmt(c.hours)}시간</b><span className="muted">{c.byOrg ? '어르신 (담당자가 대신 받음)' : '선생님'}</span><b style={{ color: 'var(--brand)' }}>+{fmt(c.hours)}시간</b></div><div className="mute2" style={{ marginTop: 8 }}>누가 가르치든 한 시간은 똑같이 한 시간이에요. 만나서 시작 QR을 찍으면 정산돼요.</div></div>
      <div className="one-safe"><Ic n="shield" s={18} /><div className="grow"><b>안심하고 만나요</b><ul><li>도서관·라운지·시장처럼 열린 곳에서만 만나요</li><li>연락처는 공개되지 않고 앱 메시지로만 이야기해요</li><li>돈·투자·보험·종교 권유는 할 수 없어요</li></ul></div></div>
      <div style={{ marginTop: 16 }}>{act}</div>
    </Screen>
  );
}

function ReviewBox({ c }) {
  const [t, setT] = useState('');
  const [done, setDone] = useState(false);
  const { toast } = useUi();
  if (done) return <div className="mute2 center" style={{ marginTop: 10 }}>후기를 남겼어요</div>;
  return <div className="row" style={{ marginTop: 10 }}><input className="in grow" placeholder="후기 한 줄" value={t} onChange={(e) => setT(e.target.value)} /><button className="btn soft sm" style={{ width: 'auto', padding: '0 14px', flex: '0 0 auto' }} onClick={async () => { if (!t.trim()) return; try { await post(`/classes/${c.id}/review`, { text: t.trim() }); setDone(true); toast('후기를 남겼어요'); } catch { /* 토스트 */ } }}>남기기</button></div>;
}

/* ---------- 배움 노트 ---------- */
export function ClsNote() {
  const { id } = useParams();
  const { toast } = useUi();
  const [c] = useLoad(() => api(`/classes/${id}`), [id]);
  if (c === undefined) return <Screen title="배움 노트" noTabs><Loading /></Screen>;
  if (!c) return <Screen title="배움 노트" noTabs><Empty>수업을 찾을 수 없어요</Empty></Screen>;
  const n = noteOf(c) || {};
  const ns = (n.steps || []).map((s) => (typeof s === 'string' ? s : s.t));
  const rev = n.reviews || n.rev || c.reviews || [];
  return (
    <Screen title="배움 노트" noTabs>
      <div className="cl-card" style={{ cursor: 'default' }}><div className="cv" style={{ background: kc(c.kind)[0], height: 96 }}><KIc k={c.kind} s={48} /><TchBadge c={c} /></div><div className="clb"><div className="ct" style={{ fontSize: 19 }}>{teacherName(c)} 선생님의 {c.title}</div><div className="mute2">{dlabel(c.date)} · {c.place} · 학생 {joined(c)}명</div></div></div>
      {ns.length ? <div className="ai" style={{ marginTop: 12 }}><div className="ah"><Spk />수업 내용을 방법 카드로 정리했어요<Mod n={3} /></div><ol className="curri">{ns.map((s, i) => <li key={i}>{s}</li>)}</ol>{n.tip && <div className="fix" style={{ borderLeftColor: '#E2557F', marginTop: 10 }}><div className="small b" style={{ color: '#E2557F' }}>선생님 한마디</div><div className="now">“{n.tip}”</div></div>}<RuleNote source={n.source} /></div> : <Empty>아직 배움 노트가 없어요</Empty>}
      {rev.length > 0 && <div className="sec-t">후기 {rev.length}</div>}
      {rev.map((r, i) => { const w = Array.isArray(r) ? r[0] : r.name || r.userName || '수강생'; const t = Array.isArray(r) ? r[1] : r.text || r.review; return <div key={i} className="card item"><Avatar name={w} s={36} /><div className="grow"><div className="b small">{w}</div><div className="small">{t}</div></div></div>; })}
      {n.settle && <div className="card" style={{ marginTop: 12 }}><div className="b">정산 기록</div><div className="settle">{Object.entries(n.settle).map(([k, v]) => <><span className="muted" key={k}>{k}</span><b key={k + 'v'}>{String(v)}</b></>)}</div></div>}
      <button className="btn ghost" style={{ marginTop: 14 }} onClick={async () => { const t = `${c.title} 배움 노트\n` + ns.map((s, i) => `${i + 1}. ${s}`).join('\n'); try { await navigator.clipboard.writeText(t); toast('배움 노트를 복사했어요. 가족에게 붙여 보내세요'); } catch { toast('복사하지 못했어요'); } }}>가족에게 공유하기 (복사)</button>
    </Screen>
  );
}

/* ---------- 수업 열기: AI 인터뷰 (황수빈 v2: 1:1 / 같이 해 보기) ---------- */
const CNQ = [null, ['1시간', '1시간 30분', '2시간'], ['1:1로, 도서관이나 카페에서', '1:1로, 주민센터 라운지에서', '4명까지 같이 해 보기 (기관 공간)']];
const CNQ3 = { one: ['평일 저녁 7시, 주말 오후 2시', '주말 오전 10시만 돼요'], group: ['다음 주 토요일 오전', '다음 주 수요일 오후'] };
const cnKind = (t) => (/폰|키오스크|카톡|컴퓨터|앱|영상통화|인터넷/.test(t) ? '디지털' : /회사|직장|취업|이직|영업|창업|교사|은퇴|경험|노하우|진로/.test(t) ? '일·경험' : /김치|깍두기|찌개|반찬|요리|떡|국|장 담|시장|재료/.test(t) ? '요리' : /뜨개|바느질|목도리|수선|재봉|자수/.test(t) ? '손재주' : /노래|이야기|옛날|고향|사진/.test(t) ? '노래·이야기' : '생활 지혜');
const CN_T = { '요리': [['재료 손질 보기', 2], ['양념·재료 알아보기', 3], ['같이 만들기', 5], ['맛보고 담기', 2], ['보관법 듣기', 1]], '손재주': [['재료와 도구 알아보기', 1], ['기본기 따라 하기', 2], ['한 단계씩 해 보기', 4], ['마무리와 다음 숙제', 1]], '노래·이야기': [['인사하고 고향 소개', 1], ['그 시절 사진·노래 같이 보기', 2], ['이야기 듣고 질문하기', 2], ['한 줄씩 기록 남기기', 1]], '디지털': [['화면 구성 알아보기', 1], ['따라 하기', 2], ['혼자 해 보기', 2], ['질문 시간', 1]], '생활 지혜': [['도구 알아보기', 1], ['시범 보기', 2], ['직접 해 보기', 3], ['정리하고 질문', 1]], '일·경험': [['지금 고민 듣기', 2], ['내 경험 들려주기', 3], ['같이 방법 찾아보기', 2], ['다음에 해 볼 것 정하기', 1]] };
/* 서버 RuleEngine.classFilter 와 같은 금지 주제(서버가 올릴 때 한 번 더 막는다) */
const CN_BLOCK = [
  [/침\s*(을|도)?\s*(놓|뜨|맞|술)|침술|쑥뜸|뜸\s*(을|도)?\s*(뜨|놓|떠)|마사지|안마|지압|민간\s*요법|약초/, '몸에 직접 하는 시술이나 민간요법은 다칠 수 있어서 수업으로 열 수 없어요.'],
  [/투자|주식|코인|가상\s*화폐|보험|대출/, '투자·보험·대출 권유는 금전 피해가 생길 수 있어 수업으로 열 수 없어요.'],
  [/전도|교회|포교|선교|판매|팔아|다단계/, '종교 활동이나 물건 판매는 수업으로 열 수 없어요.'],
];
const nextWd = (wd) => { for (let i = 7; i < 14; i++) { const d = addDays(i); if (new Date(d).getDay() === wd) return d; } return addDays(7); };
/* 1:1 가능한 시간: 다가오는 요일(1~7일 뒤) */
const soonWd = (wd) => { for (let i = 1; i <= 7; i++) { const d = addDays(i); if (new Date(d).getDay() === wd) return d; } return addDays(1); };
const WDN = { '일': 0, '월': 1, '화': 2, '수': 3, '목': 4, '금': 5, '토': 6 };
function parseSlots(t) {
  const out = [];
  for (const part of String(t).split(/[,，·]|그리고/)) {
    const m = part.match(/(\d{1,2})\s*시(\s*반)?/);
    let h = m ? +m[1] + (m[2] ? 0.5 : 0) : /저녁/.test(part) ? 19 : /오후/.test(part) ? 14 : /오전|아침/.test(part) ? 10 : null;
    if (h == null) continue;
    if (h < 12 && (/오후|저녁|밤/.test(part) || (!/오전|아침/.test(part) && h <= 6))) h += 12;
    const wm = part.match(/([월화수목금토일])요일/);
    const days = wm ? [WDN[wm[1]]] : /평일/.test(part) ? [3] : /주말/.test(part) ? (/만/.test(part) || !/,/.test(t) ? [6, 0] : [6]) : [6];
    for (const wd of days) out.push({ date: soonWd(wd), hour: h });
  }
  const uniq = out.filter((x, i) => out.findIndex((y) => y.date === x.date && y.hour === x.hour) === i).sort((a, b) => (a.date + a.hour).localeCompare(b.date + b.hour));
  return uniq.length ? uniq.slice(0, 6) : [{ date: soonWd(3), hour: 19 }, { date: soonWd(6), hour: 14 }];
}

export function ClsNew() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const org = sp.get('org') === '1';
  const { me } = useAuth();
  const { toast } = useUi();
  const [elders] = useLoad(() => (org ? api('/org/elders') : Promise.resolve(null)), [org]);
  const [teacherElderId, setTE] = useState(null);
  const firstQ = org ? '어르신께 여쭤본 내용을 적어 주세요. 어떤 걸 알려 주고 싶어 하세요?' : '어떤 걸 알려 주고 싶으세요? 요리·손재주부터 회사 경험, 휴대폰 쓰는 법까지 다 좋아요. 제가 수업으로 정리해 드릴게요.';
  const [msgs, setMsgs] = useState([]);
  const [step, setStep] = useState(0);
  const [d, setD] = useState({});
  const [blocked, setBlocked] = useState(null);
  const [text, setText] = useState('');
  const [busy, run] = useBusy();
  const bd = useRef(null);
  const spch = useSpeechInput({ onFinal: (t) => say(t), onInterim: setText });
  useEffect(() => { if (bd.current) bd.current.scrollTop = bd.current.scrollHeight; }, [msgs, step, d.ready, blocked]);
  const reset = () => { setMsgs([]); setStep(0); setD({}); setBlocked(null); setText(''); };
  const ai = (t) => setMsgs((m) => [...m, { a: 1, t }]);
  const later = (fn, ms = 500) => setTimeout(fn, ms);
  async function say(t) {
    t = String(t || '').trim(); if (!t) return;
    setText('');
    setMsgs((m) => [...m, { a: 0, t }]);
    if (step === 0) {
      const local = CN_BLOCK.find(([re]) => re.test(t));
      let fl = null;
      try { fl = await post('/classes/check-topic', { text: t }, { quiet: true }); } catch { /* 규칙만 사용 */ }
      if (local || (fl && fl.level === 'BLOCK')) { setBlocked({ reason: local ? local[1] : fl.reason, source: 'rule' }); return; }
      const kind = cnKind(t);
      setD({ topic: t, kind, caution0: fl && fl.level === 'CAUTION' ? fl.reason : '' });
      later(() => { setStep(1); ai(`좋아요! ${kind} 수업이네요. 한 번 만나면 얼마나 걸릴까요?`); });
    } else if (step === 1) {
      setD((x) => ({ ...x, h: /2시간/.test(t) ? 2 : /30분|반/.test(t) ? 1.5 : 1 }));
      later(() => { setStep(2); ai('한 사람씩 1:1로 만날까요, 여럿이 같이 해 볼까요? 이야기나 요령을 나누는 건 1:1이, 요리·바느질처럼 손으로 하는 건 같이 해 보기가 좋아요.'); });
    } else if (step === 2) {
      const grp = /같이|여럿|명까지|[2-9]\s*명/.test(t) && !/1:1/.test(t);
      let meet = '';
      if (grp) { const m = t.match(/([2-4])\s*명/); setD((x) => ({ ...x, mode: 'group', cap: m ? +m[1] : 4, items: '재료는 기관에서 준비해요' })); }
      else { meet = /라운지|주민센터/.test(t) ? '행복동 주민센터 1층 라운지' : '행복동 작은도서관 1층 북카페'; setD((x) => ({ ...x, mode: 'one', cap: 1, items: '없어요', meet })); }
      later(() => { setStep(3); ai(grp ? '언제가 좋으세요? 같이 해 보기는 담당자가 주민센터나 경로당 공간을 잡아 드려요.' : `좋아요, ${meet}에서 만나요. 언제 시간이 되세요? 몇 개 정해 두면 배우는 분이 그중에서 골라요.`); });
    } else if (step === 3) {
      setD((x) => {
        const unit = (x.h || 1) * 60, tpl = CN_T[x.kind] || CN_T['생활 지혜'], w = tpl.reduce((a, b) => a + b[1], 0);
        const base = { ...x, steps: tpl.map(([tt, k]) => ({ t: tt, min: Math.max(5, Math.round((unit * k) / w / 5) * 5) })), lv: x.kind === '요리' || x.caution0 ? '주의' : '허용' };
        if (x.mode === 'one') return { ...base, slots: parseSlots(t), title: x.topic.replace(/\s*(알려 ?주고 싶어요?|가르쳐 ?주고 싶어요?|하는 법|들려주기|나누기)$/, '').trim() };
        const wd = /월요일/.test(t) ? 1 : /화요일/.test(t) ? 2 : /수요일/.test(t) ? 3 : /목요일/.test(t) ? 4 : /금요일/.test(t) ? 5 : /일요일/.test(t) ? 0 : 6;
        return { ...base, date: nextWd(wd), s: /오후/.test(t) ? 14 : /저녁/.test(t) ? 18 : 10, title: x.topic.replace(/\s*(알려 ?주고 싶어요?|가르쳐 ?주고 싶어요?|하는 법|들려주기)$/, '').trim(), caution: x.kind === '요리' ? '칼과 불을 쓰니 조심해 주세요. 알레르기가 있으면 미리 알려 주세요.' : x.kind === '손재주' ? '바늘·가위에 다치지 않게 조심해요.' : x.caution0 || '특별한 주의사항은 없어요.' };
      });
      setStep(4); ai('잠깐만요, 수업 계획으로 정리해 볼게요.');
      later(() => setD((x) => ({ ...x, ready: true })), 1000);
    }
  }
  const publish = () => run(async () => {
    if (org && !teacherElderId) return toast('어떤 어르신이 선생님인지 골라 주세요');
    if (d.mode === 'one') {
      const c = await post('/classes', { mode: 'ONE', kind: d.kind, title: d.title, intro: org ? '담당자가 어르신 대신 열었어요.' : 'AI 인터뷰로 정리한 1:1 수업이에요.', steps: d.steps, hours: d.h, slots: d.slots, meet: d.meet, tags: [d.kind], qs: [d.topic], bio: org ? '담당자가 대신 올렸어요' : (me && me.intro) || '', weeklyCap: 2, teacherElderId: teacherElderId || undefined });
      toast('1:1 수업을 열었어요. 바로 신청을 받을 수 있어요');
      nav(org ? '/o/space' : `/cls/${c.id}`, { replace: true });
      return;
    }
    const c = await post('/classes', { mode: 'GROUP', kind: d.kind, title: d.title, intro: org ? '담당자가 어르신 대신 열었어요.' : 'AI 인터뷰로 정리한 수업이에요.', steps: d.steps, items: d.items, caution: d.caution, date: d.date, startHour: d.s, hours: d.h, capacity: d.cap, teacherElderId: teacherElderId || undefined });
    toast('수업을 올렸어요. 담당자가 장소를 배정하면 모집이 시작돼요');
    nav(org ? '/o/space' : `/cls/${c.id}`, { replace: true });
  });
  const qs = blocked ? [] : step === 0 ? ['회사 생활 30년 노하우 나누기', '카톡으로 사진 보내는 법', '우리 어머니께 배운 깍두기 담그는 법', '침 놓는 법 알려 주고 싶어'] : step === 3 ? CNQ3[d.mode === 'group' ? 'group' : 'one'] : CNQ[step] || [];
  const done = step === 4 && d.ready;
  const Bot = () => <span className="bot"><Logo /></span>;
  const titleIn = <input className="in" style={{ background: '#fff', fontWeight: 700 }} value={d.title || ''} onChange={(e) => setD({ ...d, title: e.target.value })} aria-label="수업 제목" />;
  const btns = <div className="btns" style={{ marginTop: 12 }}><button className="btn ghost sm" onClick={reset}>다시 할래요</button><button className="btn sm" disabled={busy} onClick={publish}>확인하고 올리기</button></div>;
  return (
    <Screen title={org ? '어르신 대신 수업 열기' : '수업 열기'} noTabs cls="chatv" bodyRef={bd}>
      <div className="chat-wrap">
        <div className="chat-day"><span>AI가 묻고 수업 계획을 정리해요 · 작성 도우미 AI</span></div>
        <div className="chat">
          {org && <div className="mrow"><Bot /><div className="mcol wide"><div className="ai"><div className="ah"><Spk />선생님이 될 어르신</div><div className="tags">{(elders || []).filter((e) => e.status !== 'ENDED').map((e) => <button key={e.id} className={'chip ' + (teacherElderId === e.id ? 'on' : '')} onClick={() => setTE(e.id)}>{e.alias} 님</button>)}</div></div></div></div>}
          <div className="mrow"><Bot /><div className="mcol"><div className="bot-n">수업 도우미</div><div className="msg ai-m">{firstQ}</div></div></div>
          {msgs.map((m, i) => (m.a ? <div className="mrow" key={i}><Bot /><div className="mcol"><div className="msg ai-m">{m.t}</div></div></div> : <div className="msg me" key={i}>{m.t}</div>))}
          {blocked && <div className="mrow"><Bot /><div className="mcol wide"><div className="ai warn"><div className="ah"><Spk />이 수업은 열 수 없어요<Mod n={2} /></div><div className="small">{blocked.reason}</div><div className="small muted" style={{ marginTop: 6 }}>요리, 손재주, 일 경험, 휴대폰 쓰는 법 같은 수업은 언제든 환영이에요.</div><RuleNote source={blocked.source} /></div></div></div>}
          {done && d.mode === 'one' && (
            <div className="mrow"><Bot /><div className="mcol wide"><div className="ai">
              <div className="ah"><Spk />AI가 정리한 1:1 수업<Mod n={1} /></div>
              {titleIn}
              <div className="mute2" style={{ marginTop: 6 }}>{d.kind} · 1:1 · {fmt(d.h)}시간 · {d.meet}</div>
              <ol className="curri" style={{ marginTop: 8 }}>{d.steps.map((x, i) => <li key={i}>{x.t}<span>{x.min}분</span></li>)}</ol>
              <dl className="kv"><dt>가능한 시간</dt><dd>{d.slots.map((x, i) => <span key={i}>{i > 0 && <br />}{slotOf(x)}</span>)}</dd><dt>판단</dt><dd><span className={'st ' + (d.lv === '허용' ? 'ok' : 'wait')}>{d.lv}</span></dd></dl>
              <div className="settle" style={{ borderTop: '1px solid var(--line)', paddingTop: 10 }}><span className="muted">{org ? '어르신' : '내'} 적립 (한 번 만날 때)</span><b style={{ color: 'var(--brand)' }}>+{fmt(d.h)}시간</b><span className="muted">배우는 분</span><b>−{fmt(d.h)}시간</b></div>
              <div className="mute2" style={{ marginTop: 8 }}>1:1은 공간 배정 없이 올리면 바로 신청을 받아요.</div>
              {btns}
            </div></div></div>
          )}
          {done && d.mode !== 'one' && (
            <div className="mrow"><Bot /><div className="mcol wide"><div className="ai">
              <div className="ah"><Spk />AI가 정리한 수업<Mod n={1} /></div>
              {titleIn}
              <div className="mute2" style={{ marginTop: 6 }}>{d.kind} · {fmt(d.h)}시간 · 최대 {d.cap}명 · {dlabel(d.date)} {ap(d.s)}</div>
              <ol className="curri" style={{ marginTop: 8 }}>{d.steps.map((x, i) => <li key={i}>{x.t}<span>{x.min}분</span></li>)}</ol>
              <dl className="kv"><dt>준비물</dt><dd>{d.items}</dd><dt>주의</dt><dd>{d.caution}</dd><dt>판단</dt><dd><span className={'st ' + (d.lv === '허용' ? 'ok' : 'wait')}>{d.lv}</span></dd></dl>
              <div className="settle" style={{ borderTop: '1px solid var(--line)', paddingTop: 10 }}><span className="muted">{org ? '어르신' : '내'} 적립 (수업 + 준비)</span><b style={{ color: 'var(--brand)' }}>+{fmt(d.h + 1)}시간</b><span className="muted">학생 1명 수강</span><b>−{fmt(d.h * 0.5)}시간</b></div>
              {btns}
            </div></div></div>
          )}
        </div>
      </div>
      <div className="chat-dock">
        {!(done || step === 4) && (
          <>
            <div className="qr-row"><div className="qr">{qs.map((q) => <button key={q} onClick={() => say(q)}>{q}</button>)}</div><button className="qr-reset" onClick={reset}>↺ 처음부터</button></div>
            {!blocked && <form className="composer" onSubmit={(e) => { e.preventDefault(); say(text); }}><button type="button" className={'mic' + (spch.listening ? ' on' : '')} aria-label="음성 입력" onClick={spch.toggle}><Ic h={I.mic} /></button><input className="in" placeholder="말하듯 적어 주세요" value={text} onChange={(e) => setText(e.target.value)} aria-label="답하기" /><button type="submit" aria-label="보내기"><Ic h={I.send} /></button></form>}
          </>
        )}
      </div>
    </Screen>
  );
}

/* ---------- 담당자: 공간 배정 ---------- */
export function OSpace() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [list, reload] = useLoad(() => api('/classes?status=SPACE_PENDING'), []);
  const [spRes, reloadSp] = useLoad(() => api('/org/spaces'), []);
  const spaces = Array.isArray(spRes) ? spRes : spRes ? spRes.spaces || [] : spRes;
  const [pick, setPick] = useState({});
  const [ns, setNs] = useState({ name: '', capacity: 6, equipment: '' });
  const [busy, run] = useBusy();
  const wait = (list || []).filter((c) => c.status === 'SPACE_PENDING');
  const ones = (spRes && spRes.oneClasses) || [];
  return (
    <Screen title="동네 클래스 운영" noTabs>
      <div className="sec-t" style={{ marginTop: 0 }}>어르신 1:1 수업 {ones.length}</div><div className="mute2" style={{ margin: '-4px 0 4px' }}>휴대폰이 없는 어르신 대신 신청을 받고 수락해요. 1:1은 공간 배정이 필요 없어요.</div>
      {spRes === undefined ? <Loading /> : ones.map((c) => (
        <div key={c.id} className="card org-one" style={{ marginTop: 10 }}><div className="row" style={{ gap: 12 }}><TAv c={c} s={42} /><div className="grow" style={{ minWidth: 0 }}><div className="b">{c.title}</div><div className="mute2">{teacherName(c)} {teacherAge(c)} · {c.meet}</div></div></div>{(c.requests || []).length ? <OneReqCards c={c} onChange={reloadSp} /> : <div className="mute2" style={{ marginTop: 10 }}>아직 신청이 없어요</div>}</div>
      ))}
      <div className="sec-t">같이 해 보기 · 공간 배정</div>
      <p className="su-p" style={{ marginTop: 0 }}>같이 해 보기(최대 4명)는 기관 공간에서 열려요. 장소를 배정하면 모집이 시작돼요.</p>
      {list === undefined ? <Loading /> : wait.length ? wait.map((c) => (
        <div className="card" style={{ marginTop: 10 }} key={c.id}>
          <div className="row" style={{ gap: 12 }}><KIc k={c.kind} s={42} bg /><div className="grow"><div className="b">{c.title}</div><div className="mute2">{teacherName(c)} {teacherAge(c)} · {dlabel(c.date)} {ap(c.startHour)} · 최대 {c.capacity}명</div></div><span className={'st ' + (c.level === 'CAUTION' || c.level === '주의' ? 'wait' : 'ok')}>{{ ALLOW: '허용', CAUTION: '주의' }[c.level] || c.level || '허용'}</span></div>
          <div className="tags" style={{ marginTop: 12 }}>{(spaces || []).map((s) => <button key={s.id} className={'chip ' + (pick[c.id] === s.id ? 'on' : '')} onClick={() => setPick({ ...pick, [c.id]: s.id })}>{s.name}{s.capacity ? ` (${s.capacity}명)` : ''}</button>)}</div>
          {!(spaces || []).length && <div className="mute2" style={{ marginTop: 8 }}>먼저 아래에서 공간을 등록해 주세요</div>}
          <button className="btn sm" style={{ marginTop: 12 }} disabled={busy} onClick={() => run(async () => {
            const s = (spaces || []).find((x) => x.id === pick[c.id]);
            if (!s) return toast('장소를 골라 주세요');
            await post(`/org/classes/${c.id}/assign-space`, { spaceId: s.id, place: s.name });
            reload(); toast(`${c.title} · 모집을 시작했어요`);
          })}>배정하고 모집 시작</button>
        </div>
      )) : <Empty>배정할 수업이 없어요</Empty>}
      <button className="btn soft" style={{ marginTop: 16 }} onClick={() => nav('/cls/new?org=1')}><Ic h={I.plus} /> 어르신 대신 수업 열기</button>
      <div className="sec-t">우리 기관 공간 {(spaces || []).length}</div>
      {(spaces || []).map((s) => <div key={s.id} className="card item"><div className="grow"><div className="b">{s.name}</div><div className="mute2">최대 {s.capacity}명{s.equipment ? ` · ${s.equipment}` : ''}</div></div></div>)}
      <div className="card" style={{ marginTop: 10 }}>
        <div className="b">공간 등록</div>
        <input className="in" style={{ marginTop: 8 }} placeholder="예) 행복동 주민센터 2층 요리실" value={ns.name} onChange={(e) => setNs({ ...ns, name: e.target.value })} />
        <div className="row" style={{ marginTop: 8 }}><input className="in" style={{ width: 100, flex: '0 0 auto' }} type="number" min={1} value={ns.capacity} onChange={(e) => setNs({ ...ns, capacity: +e.target.value })} aria-label="정원" /><input className="in grow" placeholder="설비 (예: 가스레인지 4구)" value={ns.equipment} onChange={(e) => setNs({ ...ns, equipment: e.target.value })} /></div>
        <button className="btn sm" style={{ marginTop: 8 }} disabled={busy} onClick={() => run(async () => { if (!ns.name.trim()) return toast('공간 이름을 적어 주세요'); await post('/org/spaces', ns); setNs({ name: '', capacity: 6, equipment: '' }); reloadSp(); toast('공간을 등록했어요'); })}>등록</button>
      </div>
      <div className="notice" style={{ marginTop: 12 }}>휴대폰이 없는 어르신도 선생님이 될 수 있어요. 담당자가 대신 열면 출석·정산 알림도 담당자에게 와요.</div>
    </Screen>
  );
}
export const _a = AREAS;
