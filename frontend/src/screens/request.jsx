import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, TBar, CatIc, Avatar, Stars, StTag, FlowBar, Loading, Empty, AiLoading, RuleNote, QrSvg, Upload, useLoad, useBusy, postWho, postCats, diffText } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { CATS, SHORT, W, LV, fmt, hh, dlabel, dshort, KIND_LABEL, lvName, lvWeight, addDays } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { useReport } from './common.jsx';
import { FilterBox } from './ai.jsx';
import QrScanner, { qrText } from '../ui/QrScanner.jsx';
import { VoiceField, MicButton } from '../ui/VoiceInput.jsx';
import { CareMemo } from './insight.jsx';

/* ---------- 담당자 봉사자 거부 (O1) ---------- */
function MgrRejectSheet({ r, onSubmit }) {
  const opts = ['대상이 원하지 않아요', '지난 활동에서 불편함이 있었어요', '주의사항을 지키지 않았어요', '기타'];
  const [v, setV] = useState(opts[0]);
  const [etc, setEtc] = useState('');
  const [block, setBlock] = useState(false);
  const { toast } = useUi();
  const name = r.volunteer ? r.volunteer.name : '봉사자';
  return (
    <>
      <h2>{name} 님 거부</h2>
      <p className="muted" style={{ marginTop: 0 }}>사유는 봉사자에게 알림으로 전해져요.</p>
      {opts.map((t) => <div key={t} className={'opt ' + (v === t ? 'on' : '')} role="button" tabIndex={0} onClick={() => setV(t)}><span className="rd" /><div>{t}</div></div>)}
      {v === '기타' && <textarea className="in" style={{ marginTop: 10 }} placeholder="사유 (필수)" value={etc} onChange={(e) => setEtc(e.target.value)} />}
      <div className={'su-check ' + (block ? 'on' : '')} role="button" tabIndex={0} style={{ marginTop: 12 }} onClick={() => setBlock(!block)}><span className="bx" /><div className="small"><b>이 봉사자 받지 않기</b><br />앞으로 {r.elder ? r.elder.displayName : '이 대상'}의 공고에 요청·제안할 수 없어요. 대상 화면에서 풀 수 있어요.</div></div>
      <button className="btn warn" style={{ marginTop: 12 }} onClick={() => {
        const reason = v === '기타' ? etc.trim() : v;
        if (!reason) return toast('사유를 적어 주세요');
        onSubmit(reason, block);
      }}>거부하기</button>
    </>
  );
}

/* ---------- 공개 프로필 시트 (B-12, B-40, B-41) ---------- */
export function ProfileView({ p }) {
  if (!p) return <Loading />;
  return (
    <>
      <div className="row" style={{ gap: 14 }}>
        <div className="intro" style={{ padding: 0, border: 0, background: 'none' }}><div className="ph">{(p.name || '?')[0]}</div></div>
        <div className="grow">
          <h2 style={{ margin: 0 }}>{p.name} <span className="mute2" style={{ fontWeight: 500, fontSize: 14 }}>{p.ageGroup}</span></h2>
          <div><Stars r={p.ratingAvg} /> <span className="mute2">· 활동 {p.ratingCount || 0}회</span></div>
          <div className="tags" style={{ marginTop: 6 }}>
            {p.crime && <span className="badge trust">✓ 범죄경력 확인</span>}
            {p.eduDone && <span className="badge trust">✓ 교육 수료</span>}
            {(p.regularBadges || []).map((b, i) => <span key={i} className="badge">★ {typeof b === 'string' ? b : b.label || b.name} 단골</span>)}
          </div>
        </div>
      </div>
      {p.intro && <p className="small" style={{ margin: '14px 0 4px' }}>“{p.intro}”</p>}
      {p.experience && <div className="mute2">{p.experience}</div>}
      <div className="tags" style={{ marginTop: 10 }}>{(p.tags || p.topTags || []).map((t) => <span key={typeof t === 'string' ? t : t.tag} className="tag2">{typeof t === 'string' ? t : t.tag}</span>)}</div>
    </>
  );
}
function ProfileSheet({ id, onClose }) {
  const [p] = useLoad(() => api(`/users/${id}/profile`), [id]);
  return <><ProfileView p={p} /><button className="btn ghost" style={{ marginTop: 16 }} onClick={onClose}>닫기</button></>;
}
export function useProfileSheet() {
  const { sheet, closeSheet } = useUi();
  return (id) => sheet(() => <ProfileSheet id={id} onClose={closeSheet} />);
}

/* ---------- 요청·제안 작성 (B-14~16, B-22, C-05, C-06) ---------- */
export function ReqForm() {
  const [sp] = useSearchParams();
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast, confirm } = useUi();
  const postId = sp.get('post'), to = sp.get('to'), elderId = sp.get('elder');
  const [p] = useLoad(() => (postId ? api(`/posts/${postId}`) : Promise.resolve(null)), [postId]);
  const [vol] = useLoad(() => (to ? api(`/users/${to}/profile`) : Promise.resolve(null)), [to]);
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  const kind = postId ? (p && p.type === 'NEED' ? 'PROPOSAL' : 'REQUEST') : 'REGULAR';
  const isProp = kind === 'PROPOSAL';
  const lo = p ? Number(p.startHour) : 9, hi = p ? Number(p.endHour) : 20;
  const cats = p ? postCats(p) : CATS.map((c) => c.k);
  const [f, setF] = useState({ s: null, h: 1, cat: null, content: '', memo: '', date: addDays(1) });
  const [ai, setAi] = useState(null);
  const [busy, run] = useBusy();
  useEffect(() => {
    if (postId && !p) return;
    setF((x) => ({ ...x, s: x.s ?? lo, h: Math.min(1, hi - lo) || 0.5, cat: x.cat || cats[0], content: x.content || (p && p.type === 'NEED' ? p.title || '' : '') }));
  }, [p]); // eslint-disable-line
  if ((postId && p === undefined) || f.s == null) return <Screen title="요청" noTabs><Loading /></Screen>;
  if (postId && !p) return <Screen title="요청" noTabs><Empty>공고를 찾을 수 없어요</Empty></Screen>;
  const starts = []; for (let t = lo; t < hi; t += 0.5) starts.push(t);
  const hs = [0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4];
  const w = p ? postWho(p) : { name: vol ? vol.name : '' };
  const receiverElder = elderId || (p && p.elderId);
  const weight = ai && ai.diff ? lvWeight(ai.diff.level, ai.diff.weight) : W(f.cat);
  const lvl = ai && ai.diff ? lvName(ai.diff.level) : LV(f.cat);
  const earn = Math.round(f.h * weight * 2) / 2;
  const check = async () => {
    if (!f.content.trim()) return toast('도움 내용을 먼저 적어 주세요');
    setAi({ loading: true });
    try {
      const [fl, df] = await Promise.all([post('/ai/filter', { text: f.content }), post('/ai/difficulty', { text: f.content, categories: [f.cat], hours: f.h })]);
      setAi({ filter: fl, diff: df });
    } catch { setAi(null); }
  };
  const send = () => run(async () => {
    if (!f.content.trim()) return toast('도움 내용을 적어 주세요');
    if (ai && ai.filter && ai.filter.level === 'BLOCK') return toast('보낼 수 없는 요청이에요');
    const date = p ? p.date : f.date;
    if (!(await confirm(isProp ? '제안을 보낼까요?' : '요청을 보낼까요?', <>{dlabel(date)} {hh(f.s)}~{hh(f.s + f.h)} · {f.cat}<br />메모는 보낸 뒤 고칠 수 없어요.</>, '보내기'))) return;
    try {
      const r = await post('/requests', { kind, postId: postId ? Number(postId) : undefined, volunteerId: to ? Number(to) : undefined, elderId: elderId ? Number(elderId) : undefined, date, startHour: f.s, hours: f.h, category: f.cat, content: f.content.trim(), memo: f.memo.trim() });
      toast(isProp ? '제안을 보냈어요' : '요청을 보냈어요. 응답이 오면 알려드려요');
      nav(me.role === 'ORG' ? `/o/reqs/${r.id}` : `/req/${r.id}`, { replace: true });
    } catch (e) {
      if (e.code === 'FORBIDDEN_REQUEST' && e.data) setAi({ filter: e.data.filter || { level: 'BLOCK', reason: e.message, alternatives: e.data.alternatives || [] } });
    }
  });
  const dates = Array.from({ length: 7 }, (_, i) => addDays(i + 1));
  return (
    <Screen title={isProp ? '봉사 제안' : kind === 'REGULAR' ? '단골에게 요청' : '도움 요청'} noTabs>
      <div className="row" style={{ gap: 10 }}>
        <Avatar name={w.name} elder={w.elder} />
        <div className="grow"><div className="b">{w.name}{kind === 'REGULAR' && <> <span className="badge">단골</span></>}</div><div className="mute2">{p ? `${dlabel(p.date)} ${hh(p.startHour)}~${hh(p.endHour)} 공고` : '공고 없이 바로 요청'}</div></div>
      </div>
      {elderId && <div className="lock" style={{ marginTop: 12 }}><Ic n="users" s={18} /><div><b>{elder ? elder.alias + ' 님' : ''}</b> 대신 요청해요. 알림·메시지는 담당자가 받아요.</div></div>}
      {kind === 'REGULAR' && <><label className="f">날짜</label><div className="tags">{dates.map((d) => <button key={d} className={'chip ' + (f.date === d ? 'on' : '')} onClick={() => setF({ ...f, date: d })}>{dlabel(d)}</button>)}</div></>}
      <label className="f">시작 시간 {p && <span className="mute2" style={{ fontWeight: 500 }}>(공고 범위 {hh(lo)}~{hh(hi)})</span>}</label>
      <div className="tp">{starts.map((t) => <button key={t} className={f.s === t ? 'on' : ''} onClick={() => setF({ ...f, s: t, h: t + f.h > hi ? Math.max(0.5, hi - t) : f.h })}>{hh(t)}</button>)}</div>
      <label className="f">활동 시간</label>
      <div className="tp">{hs.map((h) => <button key={h} className={f.h === h ? 'on' : ''} disabled={f.s + h > hi} onClick={() => setF({ ...f, h })}>{h}시간</button>)}</div>
      {p && <div className="card" style={{ marginTop: 12, padding: '14px 16px' }}><div className="between"><span className="mute2">선택한 시간</span><b>{hh(f.s)} ~ {hh(f.s + f.h)}</b></div><TBar s={lo} e={hi} ss={f.s} se={f.s + f.h} /></div>}
      <label className="f">도움 종류</label>
      <div className="tags">{cats.map((k) => <button key={k} className={'chip ' + (f.cat === k ? 'on' : '')} onClick={() => { setF({ ...f, cat: k }); setAi(null); }}>{SHORT[k] || k}</button>)}</div>
      <label className="f" htmlFor="rfc">도움 내용</label>
      <VoiceField value={f.content} onChange={(v) => { setF((x) => ({ ...x, content: v })); setAi(null); }}><textarea id="rfc" className="in" placeholder="어떤 도움이 필요한지 적어 주세요" value={f.content} onChange={(e) => { setF({ ...f, content: e.target.value }); setAi(null); }} /></VoiceField>
      <button className="btn soft sm" style={{ marginTop: 8 }} onClick={check}><Ic h={I.spark} /> AI로 내용 확인하기 (금지 요청·난이도)</button>
      <div style={{ marginTop: 10 }}>
        {ai && ai.loading && <AiLoading label="AI가 확인하는 중…" />}
        {ai && ai.filter && <FilterBox f={ai.filter} diff={ai.diff} />}
      </div>
      <label className="f" htmlFor="rfm">메모 <span className="mute2" style={{ fontWeight: 500 }}>(보낼 때 한 번만 적을 수 있어요)</span></label>
      <VoiceField value={f.memo} onChange={(v) => setF((x) => ({ ...x, memo: v }))}><textarea id="rfm" className="in" style={{ minHeight: 80 }} placeholder="예) 엘리베이터 있어요 / 반려견이 있어요" value={f.memo} onChange={(e) => setF({ ...f, memo: e.target.value })} /></VoiceField>
      <div className="sum" style={{ marginTop: 16 }}>
        <div className="between"><span className="muted">난이도</span><b>{lvl} × {weight.toFixed(1)}</b></div>
        <div className="between"><span className="muted">{isProp ? '내 예상 적립' : '봉사자 예상 적립'}</span><b className="cr plus">+{fmt(earn)}시간</b></div>
        <div className="between"><span className="muted">{isProp ? '받는 분' : '내 통장'}</span><b>{receiverElder ? '기관 대상 · 차감 없음' : isProp ? '완료 후 차감' : `${fmt(earn)}시간 보류 → 완료 후 차감`}</b></div>
      </div>
      {!isProp && !receiverElder && me && me.role === 'USER' && me.earnedSelf != null && me.earnedSelf < 10 && <div className="notice" style={{ marginTop: 10 }}>직접 적립한 시간이 10시간 이상이어야 도움을 받을 수 있어요 (지금 {fmt(me.earnedSelf)}시간). 선물받은 시간은 이 조건 없이 쓸 수 있어요.</div>}
      <div className="mute2" style={{ margin: '10px 2px' }}>상대가 24시간 안에 응답하지 않으면 자동 만료돼요.</div>
      <button className="btn big-cta" disabled={busy} onClick={send}>{isProp ? '제안 보내기' : '요청 보내기'}</button>
    </Screen>
  );
}

/* ---------- 단골에게 요청 (B-14, C-06) ---------- */
export function Regular() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  const [list] = useLoad(() => api(elderId ? `/regulars?elderId=${elderId}` : '/regulars'), [elderId]);
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  return (
    <Screen title="단골에게 요청" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>{elderId ? `${elder ? elder.alias + ' 님' : '대상'}을` : '나를'} 3번 이상 도와준 분은 단골이 돼요. 공고 없이 바로 요청할 수 있어요.</p>
      {list === undefined ? <Loading /> : !list || !list.length ? <Empty>아직 단골이 없어요</Empty> : list.map((h) => {
        const id = h.id || h.userId || h.volunteerId;
        return (
          <div className="pc2" key={id}>
            <div className="top"><Avatar name={h.name} s={52} /><div className="grow"><div className="who">{h.name}<span className="badge">★ 단골{h.doneCount || h.count ? ` · ${h.doneCount || h.count}회` : ''}</span></div><div className="sub">{h.ageGroup ? `${h.ageGroup} · ` : ''}<Stars r={h.ratingAvg} />{h.experience ? ` · ${h.experience}` : ''}</div></div></div>
            {h.intro && <div className="muted small" style={{ marginTop: 10 }}>“{h.intro}”</div>}
            <button className="btn sm" style={{ marginTop: 12 }} onClick={() => nav(`/req/new?to=${id}${elderId ? `&elder=${elderId}` : ''}`)}>{h.name} 님에게 요청하기</button>
          </div>
        );
      })}
    </Screen>
  );
}

/* ---------- 요청 상세 · 활동 진행 (사용자/담당자 공통) ---------- */
const norm = (s) => String(s).toLowerCase().replace(/[-_\s]/g, '');
function can(r, name, fallback) {
  if (Array.isArray(r.actions) && r.actions.length) return r.actions.map(norm).includes(norm(name));
  return fallback;
}
const startTs = (r) => { const d = new Date(String(r.date).slice(0, 10) + 'T00:00:00'); d.setMinutes(Math.round(Number(r.startHour) * 60)); return d.getTime(); };
const minsSinceStart = (r) => Math.floor((Date.now() - startTs(r)) / 60000);
const tm = (dt) => (dt ? String(dt).slice(11, 16) : '');
const durMin = (a, b) => (a && b ? Math.max(0, Math.round((new Date(b) - new Date(a)) / 60000)) : null);
const durTxt = (m) => (m == null ? '' : `${Math.floor(m / 60) ? Math.floor(m / 60) + '시간 ' : ''}${m % 60 ? (m % 60) + '분' : ''}`.trim() || '0분');
const NS_LATE = 10, NS_MISS = 30;
const checkByText = (m) => (m === 'manual' ? '버튼 확인' : m === 'qr' ? 'QR 확인' : '번호 확인');
const alertBox = (kind, title, body) => <div className={`alert ${kind}`}><Ic n={kind === 'bad' ? 'flag' : 'clock'} s={20} /><div><b>{title}</b>{body}</div></div>;
const QrStepsBar = ({ k }) => <div className="qr-steps">{['도착 · 시작 QR', '마칠 때 · 종료 QR', '활동 일지'].map((t, i) => <div key={t} className={i < k ? 'done' : i === k ? 'on' : ''}>{i < k ? '✓ ' : ''}{t}</div>)}</div>;

/** 봉사자: 시작·종료 QR 찍기 시트 (카메라로 실제 QR 인식 + 번호 6자리 입력) */
function ScanSheet({ r, mode, onDone, onClose }) {
  const [busy, run] = useBusy();
  const who = r.elder ? `${r.elder.displayName} 댁 현관의 QR 카드` : `${r.recipient ? r.recipient.name : '받는 분'} 님 휴대폰의 QR`;
  const url = `/requests/${r.id}/${mode === 'in' ? 'check-in' : 'check-out'}`;
  const submit = (body) => post(url, body, { quiet: body.method === 'qr' });
  const manual = () => run(async () => { await post(url, { method: 'manual' }); onClose(); onDone(); });
  return (
    <>
      <h2>{mode === 'in' ? '시작 QR 찍기' : '종료 QR 찍기'}</h2>
      <p className="muted" style={{ margin: 0 }}>{who}를 화면 네모 안에 맞춰 주세요.</p>
      <QrScanner kind="REQ" id={r.id} submit={submit} onDone={() => { onClose(); onDone(); }}
        foot={r.elder ? '카드가 안 보이면 담당자에게 메시지로 알려 주세요' : '상대방이 휴대폰 화면 밝기를 높이면 잘 찍혀요'} />
      <button className="au-demo" disabled={busy} onClick={manual}>번호 없이 {mode === 'in' ? '시작' : '마치기'} (받는 분이 한 번 더 확인해요)</button>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}

/** 받는 쪽/담당자: 내 QR 보여주기 (체크 코드를 담은 QR) */
function ShowQrSheet({ r, title, sub, onClose, onRefresh }) {
  const [left, setLeft] = useState(30);
  useEffect(() => {
    const t = setInterval(() => setLeft((x) => { if (x <= 1) { onRefresh && onRefresh(); return 30; } return x - 1; }), 1000);
    return () => clearInterval(t);
  }, []); // eslint-disable-line
  return (
    <>
      <h2>{title}</h2>
      <p className="muted" style={{ margin: 0 }}>{sub}</p>
      <div className="qr-show">
        <div className="qr-box"><QrSvg text={r.qrText || qrText('REQ', r.id, r.checkCode)} size="min(220px,62vw)" /></div>
        <div className="b" style={{ fontSize: 30, letterSpacing: 6 }}>{r.checkCode || '------'}</div>
        <div className="qr-timer"><i style={{ width: `${(left / 30) * 100}%` }} /></div>
        <div className="mute2">QR이 안 찍히면 위 번호 6자리를 불러 주세요</div>
      </div>
      <button className="btn ghost" style={{ marginTop: 8 }} onClick={onClose}>닫기</button>
    </>
  );
}

function SosSheet({ onClose, notify }) {
  return (
    <>
      <h2 style={{ color: '#E5484D' }}>긴급 상황인가요?</h2>
      <p className="muted">받는 분이 쓰러졌거나 다쳤다면 바로 119에 전화해 주세요.</p>
      <div className="stack">
        <a className="btn warn big-cta" href="tel:119"><Ic n="phone" s={20} /> 119 전화</a>
        <button className="btn" onClick={notify}>담당자·운영자에게 긴급 알림 보내기</button>
        <button className="btn ghost" onClick={onClose}>닫기</button>
      </div>
      <div className="mute2" style={{ marginTop: 10 }}>긴급 알림은 이 활동의 메시지와 신고로 담당자와 운영자에게 가요.</div>
    </>
  );
}

function ReasonSheet({ title, options, yes, warn, onSubmit }) {
  const [v, setV] = useState(options[0]);
  const [etc, setEtc] = useState('');
  return (
    <>
      <h2>{title}</h2>
      {options.map((t) => <div key={t} className={'opt ' + (v === t ? 'on' : '')} role="button" tabIndex={0} onClick={() => setV(t)}><span className="rd" /><div>{t}</div></div>)}
      {v === '기타' && <textarea className="in" style={{ marginTop: 10 }} placeholder="사유" value={etc} onChange={(e) => setEtc(e.target.value)} />}
      <button className={'btn ' + (warn ? 'warn' : '')} style={{ marginTop: 12 }} onClick={() => onSubmit(v === '기타' ? etc || '기타' : v)}>{yes}</button>
    </>
  );
}

function AdjustSheet({ r, onDone }) {
  const [h, setH] = useState(null);
  const [reason, setReason] = useState('');
  const { toast } = useUi();
  return (
    <>
      <h2>시간 조정 요청</h2>
      <p className="muted">실제 활동 시간이 예정({r.hours}시간)과 달랐나요?</p>
      <div className="tp">{[0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4].map((x) => <button key={x} className={h === x ? 'on' : ''} onClick={() => setH(x)}>{x}시간</button>)}</div>
      <textarea className="in" style={{ marginTop: 10 }} placeholder="사유" value={reason} onChange={(e) => setReason(e.target.value)} />
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => {
        if (!h) return toast('실제 활동 시간을 골라 주세요');
        try { await post(`/requests/${r.id}/adjust`, { hours: h, reason }); onDone(); toast('시간 조정을 요청했어요. 상대가 확인하면 반영돼요'); } catch { /* 토스트 */ }
      }}>요청하기</button>
    </>
  );
}

function AppealSheet({ r, onDone }) {
  const [lv, setLv] = useState(r.difficulty === 'BASIC' ? 'EFFORT' : 'RESPONSIBLE');
  const [reason, setReason] = useState('');
  const { toast } = useUi();
  return (
    <>
      <h2>난이도 이의 신청</h2>
      <p className="muted">지금 등급: {diffText(r)}. 실제로 더 힘들었다면 운영자가 검토해 조정해요.</p>
      {[['BASIC', '기본 1.0'], ['EFFORT', '수고 1.2'], ['RESPONSIBLE', '책임 1.5']].filter(([k]) => k !== r.difficulty).map(([k, l]) => <div key={k} className={'opt ' + (lv === k ? 'on' : '')} role="button" tabIndex={0} onClick={() => setLv(k)}><span className="rd" /><div>{l}로 조정 요청</div></div>)}
      <textarea className="in" style={{ marginTop: 10 }} placeholder="예) 쌀 20kg와 생수 2묶음을 4층까지 날랐어요" value={reason} onChange={(e) => setReason(e.target.value)} />
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => {
        if (!reason.trim()) return toast('사유를 적어 주세요');
        try { await post(`/requests/${r.id}/appeal`, { reason: reason.trim(), requestedLevel: lv }); onDone(); toast('이의 신청을 보냈어요. 운영자가 확인해요'); } catch { /* 토스트 */ }
      }}>신청하기</button>
    </>
  );
}

function RateSheetOrg({ r, onDone }) {
  const [s, setS] = useState(5);
  const [tags, setTags] = useState(['설명이 친절해요']);
  const { toast } = useUi();
  const T = ['설명이 친절해요', '편안해요', '시간 약속을 잘 지켜요', '꼼꼼해요', '안전하게 도와줘요'];
  return (
    <>
      <h2>대신 확인·평가</h2>
      <p className="muted">{r.elder ? r.elder.displayName : '대상'}께 여쭤본 내용으로 평가해 주세요.</p>
      <div className="stars-in">{[1, 2, 3, 4, 5].map((i) => <span key={i} className={i <= s ? 'on' : ''} role="button" tabIndex={0} onClick={() => setS(i)}>★</span>)}</div>
      <div className="tags" style={{ marginTop: 10 }}>{T.map((t) => <button key={t} className={'chip ' + (tags.includes(t) ? 'on' : '')} onClick={() => setTags(tags.includes(t) ? tags.filter((x) => x !== t) : [...tags, t])}>{t}</button>)}</div>
      <button className="btn" style={{ marginTop: 14 }} onClick={async () => {
        try {
          if (r.status === 'LOG_SUBMITTED') await post(`/requests/${r.id}/confirm`);
          await post(`/requests/${r.id}/rate`, { stars: s, tags, comment: '' });
          onDone(); toast('확인했어요. 봉사자에게 시간이 적립됐어요');
        } catch { onDone(); }
      }}>확인하고 평가 남기기</button>
    </>
  );
}

export function RequestDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { me, refresh } = useAuth();
  const ui = useUi();
  const { toast, confirm, sheet, closeSheet } = ui;
  const report = useReport();
  const profile = useProfileSheet();
  const [r, reload] = useLoad(() => api(`/requests/${id}`), [id]);
  const [msg, setMsg] = useState('');
  const [called, setCalled] = useState(false);
  const [, setTick] = useState(0);
  const [busy, run] = useBusy();
  const bodyRef = useRef(null);
  useEffect(() => { const t = setInterval(() => setTick((x) => x + 1), 30000); return () => clearInterval(t); }, []);
  if (r === undefined) return <Screen title="요청" noTabs><Loading /></Screen>;
  if (!r) return <Screen title="요청" noTabs><Empty>요청을 찾을 수 없어요</Empty></Screen>;
  const role = r.myRole;
  const iVol = role === 'VOLUNTEER';
  const mgr = role === 'MANAGER';
  const recv = role === 'RECIPIENT' || mgr;
  const st = r.status;
  const v = r.volunteer || {};
  const toName = r.elder ? r.elder.displayName : r.recipient ? r.recipient.name : '';
  const done = async (m) => { await reload(); refresh(); if (m) toast(m); };
  const act = (path, body, m) => run(async () => { await post(`/requests/${r.id}/${path}`, body); await done(m); });
  const reject = (path, title, m) => sheet(() => <ReasonSheet title={title} options={path === 'cancel' ? ['일정이 생겼어요', '몸이 안 좋아요', '다른 방법으로 해결했어요', '기타'] : ['시간이 안 맞아요', '도움 내용이 어려워요', '거리가 멀어요', '기타']} yes={path === 'cancel' ? '취소하기' : path === 'decline' ? '거부하기' : '거절하기'} warn={path !== 'reject'} onSubmit={(reason) => { closeSheet(); act(path, { reason }, m); }} />);
  const late = minsSinceStart(r);
  const isUserInitiated = (role === 'RECIPIENT' && r.kind !== 'PROPOSAL') || (iVol && r.kind === 'PROPOSAL') || (mgr && r.kind !== 'PROPOSAL');
  const canMsg = ['VOL_ACCEPTED', 'CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT', 'LOG_SUBMITTED', 'DONE'].includes(st);
  const ro = st === 'DONE' || !can(r, 'message', true);
  const msgs = r.messages || [];
  const scan = (mode) => sheet(() => <ScanSheet r={r} mode={mode} onClose={closeSheet} onDone={() => done(mode === 'in' ? '시작을 기록했어요. 받는 분에게 알렸어요' : '기록 완료! 일지를 써 주세요')} />);
  const showQr = (k) => sheet(() => <ShowQrSheet r={r} title={k === 0 ? '시작 QR' : '종료 QR'} sub={`${v.name || '봉사자'} 님에게 이 화면을 보여 주세요.`} onClose={() => { closeSheet(); reload(); }} />);
  const qrCard = () => sheet(() => (
    <>
      <h2>현관 QR 카드</h2>
      <p className="muted" style={{ margin: 0 }}>{toName} 댁 현관 안쪽에 붙여 두는 카드예요. 봉사자가 도착·종료할 때 찍어요.</p>
      <div className="qr-card"><div className="b" style={{ fontSize: 15 }}>시간 적금 · 활동 확인 카드</div><div className="qr-box" style={{ margin: '10px auto' }}><QrSvg text={r.qrText || qrText('REQ', r.id, r.checkCode)} size={160} /></div><div className="b" style={{ fontSize: 22, letterSpacing: 4 }}>{r.checkCode}</div><div className="mute2">봉사자: 시작할 때 한 번, 끝날 때 한 번 찍어 주세요</div></div>
      <div className="notice" style={{ marginTop: 10 }}>카드에는 주소·이름이 들어 있지 않아요. 이 활동에서만 쓰는 번호예요.</div>
      <div className="btns" style={{ marginTop: 12 }}><button className="btn ghost" onClick={closeSheet}>닫기</button><button className="btn" onClick={() => window.print()}>인쇄하기</button></div>
    </>
  ));
  const sos = () => sheet(() => <SosSheet onClose={closeSheet} notify={async () => {
    closeSheet();
    try {
      await post(`/requests/${r.id}/messages`, { text: '[긴급] 활동 중 긴급 상황이 생겼어요. 바로 확인해 주세요.' }, { quiet: true });
      await post('/reports', { targetType: 'REQUEST', targetId: r.id, targetLabel: `긴급 · ${r.content}`, type: '긴급 상황', content: '활동 중 긴급 알림' }, { quiet: true });
      toast('담당자와 운영자에게 긴급 알림을 보냈어요'); reload();
    } catch { toast('긴급 알림을 보내지 못했어요. 119에 먼저 전화해 주세요'); }
  }} />);

  const k = st === 'IN_PROGRESS' ? 1 : st === 'CHECKED_OUT' ? 2 : 0;
  const checkInfo = r.checkInAt ? <>{tm(r.checkInAt)} 시작 <span className="qr-by">{checkByText(r.checkMethod)}</span></> : null;

  let action = null;
  if (st === 'SENT' && !isUserInitiated && can(r, 'accept', true)) {
    action = (
      <>
        <div className="btns" style={{ marginTop: 16 }}>
          <button className="btn ghost" disabled={busy} onClick={() => reject('reject', '거절 사유', '거절했어요. 상대에게 알림이 가요')}>거절</button>
          <button className="btn" disabled={busy} onClick={async () => {
            const prop = r.kind === 'PROPOSAL';
            if (!(await confirm(prop ? '이 제안을 수락할까요?' : '이 요청을 수락할까요?', prop ? '수락하면 일정이 확정되고, 같은 공고의 다른 제안은 자동으로 거절돼요.' : '수락하면 공고가 닫히고, 같은 공고의 다른 요청은 자동으로 거절돼요. 이후 받는 분이 최종 수락하면 일정이 확정돼요.', '수락하기'))) return;
            act('accept', {}, prop ? '제안을 수락했어요. 일정이 확정됐어요' : '수락했어요. 받는 분의 최종 수락을 기다려요');
          }}>수락</button>
        </div>
        {r.leftText && <div className="mute2 center" style={{ marginTop: 8 }}>{r.leftText} 안에 응답하지 않으면 자동 만료돼요</div>}
      </>
    );
  } else if (st === 'SENT') {
    action = <><div className="notice" style={{ marginTop: 16 }}>{r.kind === 'PROPOSAL' ? (iVol ? `${toName} 님` : '받는 분') : `${v.name} 님`}의 응답을 기다리고 있어요{r.leftText ? ` (${r.leftText} 남음)` : ''}</div>{can(r, 'cancel', true) && <button className="btn ghost" style={{ marginTop: 10 }} disabled={busy} onClick={() => reject('cancel', '취소 사유', '취소했어요')}>{r.kind === 'PROPOSAL' ? '제안 취소' : '요청 취소'}</button>}</>;
  } else if (st === 'VOL_ACCEPTED' && recv) {
    action = (
      <>
        {mgr && <div className="card" style={{ marginTop: 14 }}><div className="b">대상에게 확인했나요?</div><div className="mute2">전화나 방문으로 {toName}께 일정을 설명하고 의사를 물어봐 주세요.</div><div className={'su-check ' + (called ? 'on' : '')} role="button" tabIndex={0} style={{ marginTop: 8 }} onClick={() => setCalled(!called)}><span className="bx" /><div className="small">전화로 확인했어요</div></div></div>}
        <div className="btns" style={{ marginTop: 16 }}>
          <button className="btn ghost" disabled={busy} onClick={() => reject('decline', mgr ? '대신 거부 사유' : '거부 사유', '거부했어요')}>{mgr ? '대신 거부' : '거부'}</button>
          <button className="btn" disabled={busy} onClick={async () => {
            if (mgr && !called) return toast('대상에게 확인했는지 먼저 체크해 주세요');
            if (!(await confirm(`${v.name} 님으로 확정할까요?`, <>{dlabel(r.date)} {hh(r.startHour)}~{hh(Number(r.startHour) + Number(r.hours))}{mgr || r.elder ? ' · 기관 대상이라 시간 차감이 없어요.' : ` · 내 통장에서 ${fmt(r.estEarn)}시간이 보류돼요.`}</>, '최종 수락'))) return;
            act('accept', {}, mgr ? '대신 최종 수락했어요. 방문 장소를 안내해 주세요' : '일정이 확정됐어요');
          }}>{mgr ? '대신 최종 수락' : '최종 수락'}</button>
        </div>
        <div className="mute2 center" style={{ marginTop: 8 }}>소개 카드를 보고 정해 주세요{r.leftText ? ` · ${r.leftText} 남음` : ''}</div>
      </>
    );
  } else if (st === 'VOL_ACCEPTED') {
    action = <div className="notice" style={{ marginTop: 16 }}>{r.elder ? '담당자가 대상에게 확인 후' : `${toName} 님이`} 최종 수락하면 일정이 확정돼요.</div>;
  } else if (['CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT'].includes(st) && iVol) {
    const end = Math.round(Number(r.hours) * 60);
    const to = r.elder ? '담당자' : `${toName} 님`;
    const warn = k === 0 && late >= NS_MISS ? alertBox('bad', `시작 체크 없이 ${late}분이 지났어요`, `${to}에게 알림이 갈 수 있어요. 지금 도착했다면 시작 QR을 찍어 주세요. 지각으로 기록돼요.`)
      : k === 0 && late >= NS_LATE ? <>{alertBox('warn', `약속 시간이 ${late}분 지났어요`, `늦어지면 미리 알려 주세요. ${NS_MISS}분이 지나도 시작 체크가 없으면 ${to}에게 알려요.`)}<button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => run(async () => { await post(`/requests/${r.id}/messages`, { text: '조금 늦어요. 곧 도착할게요. 죄송해요!' }); await done('늦는다고 알렸어요'); })}><Ic n="msg" s={16} /> 늦는다고 알리기</button></>
        : k === 1 && late >= end ? alertBox('warn', `예정 종료 시각(${hh(Number(r.startHour) + Number(r.hours))})이 지났어요`, '끝났다면 종료 QR을 찍어 주세요.') : null;
    if (k === 0) action = <>{r.elder && <div className="ai" style={{ marginTop: 16 }}><div className="ah"><Spk />방문 전 안내 카드<Mod n={3} /></div><ul className="small"><li>{r.elder.caution || '담당자 안내를 확인해 주세요'}</li><li>방문 장소는 담당자가 메시지로 알려 드려요</li></ul></div>}{warn}<QrStepsBar k={0} /><button className="btn big-cta" style={{ marginTop: 12 }} onClick={() => scan('in')}><Ic n="qr" s={22} /> 시작 QR 찍기</button><div className="mute2 center" style={{ marginTop: 8 }}>도착하면 {r.elder ? `${toName} 댁 현관의 QR 카드` : `${toName} 님 휴대폰의 QR`}를 찍어 주세요</div>{can(r, 'cancel', true) && <><button className="btn ghost sm" style={{ marginTop: 10 }} disabled={busy} onClick={() => reject('cancel', '일정 취소 사유', '취소했어요')}>일정 취소</button><div className="mute2 center" style={{ marginTop: 6 }}>일정 취소는 시작 24시간 전까지 무료예요. 그 뒤엔 경고가 쌓여요</div></>}</>;
    else if (k === 1) action = <>{warn}<QrStepsBar k={1} /><div className="chk on"><span className="dotp" /><div className="grow"><b>활동 중</b> · {checkInfo}<div className="mute2">예정 종료 {hh(Number(r.startHour) + Number(r.hours))} · 끝나면 QR을 한 번 더 찍어 주세요</div></div></div><div className="btns" style={{ marginTop: 10 }}><button className="btn warn" onClick={sos}><Ic n="bell" s={18} /> 긴급</button><button className="btn" onClick={() => scan('out')}><Ic n="qr" s={18} /> 종료 QR 찍기</button></div></>;
    else action = <><QrStepsBar k={2} /><div className="chk"><span className="dotp" style={{ background: '#1F9D63', animation: 'none', boxShadow: 'none' }} /><div className="grow"><b>활동 끝</b> · {tm(r.checkInAt)} ~ {tm(r.checkOutAt)} ({durTxt(durMin(r.checkInAt, r.checkOutAt))})<div className="mute2">{r.checkMethod === 'manual' ? '번호가 아닌 방법으로 기록돼 받는 분이 시간을 한 번 더 확인해요' : '기록된 시간이 일지에 자동으로 들어가요'}</div></div></div><button className="btn big-cta" style={{ marginTop: 10 }} onClick={() => nav(`/req/${r.id}/log`)}>활동 일지 쓰기</button></>;
  } else if (['CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT'].includes(st) && recv) {
    const noShow = k === 0 && late >= NS_MISS;
    const qrBtn = mgr
      ? <button className="btn ghost" style={{ marginTop: 8 }} onClick={qrCard}><Ic n="qr" s={18} /> 현관 QR 카드 보기·인쇄</button>
      : <button className="btn big-cta" style={{ marginTop: 10 }} onClick={() => showQr(k)}><Ic n="qr" s={22} /> 내 QR 보여주기 ({k === 0 ? '시작' : '종료'})</button>;
    if (k === 0 && noShow) action = <>{alertBox('bad', `${v.name} 님이 아직 오지 않았어요`, `약속 시간이 ${late}분 지났는데 시작 체크가 없어요. 사정이 있을 수 있으니 메시지를 먼저 보내 보세요.`)}{qrBtn}<div className="btns" style={{ marginTop: 10 }}><button className="btn ghost" onClick={() => run(async () => { await post(`/requests/${r.id}/messages`, { text: '조금 더 기다릴게요. 도착하시면 알려 주세요.' }); await done(`${v.name} 님에게 알렸어요`); })}>조금 더 기다릴게요</button><button className="btn warn" onClick={async () => {
      if (!(await confirm('노쇼로 신고하고 취소할까요?', `${v.name} 님이 약속 ${late}분이 지나도록 오지 않았어요. 취소하면 보류된 시간이 돌아오고, 운영팀이 사실을 확인해요.`, '신고·취소', true))) return;
      run(async () => {
        await post(`/requests/${r.id}/noshow`, {});
        await done('취소했어요. 보류된 시간이 돌아왔어요');
      });
    }}>노쇼 신고·취소</button></div></>;
    else if (k === 0) action = <>{late >= NS_LATE && alertBox('warn', `약속 시간이 ${late}분 지났어요`, `${v.name} 님이 아직 시작 QR을 찍지 않았어요. ${NS_MISS}분이 지나면 다시 알려 드려요.`)}<div className="card" style={{ marginTop: 16 }}><div className="row" style={{ gap: 12 }}><span className="qr-ic"><Ic n="qr" s={22} /></span><div className="grow"><div className="b">{v.name} 님이 도착하면 {mgr ? '현관 QR 카드를 찍어요' : '내 QR을 보여 주세요'}</div><div className="mute2">QR(또는 번호 {r.checkCode || '6자리'})을 찍어야 활동 시간이 기록돼요. 끝날 때도 한 번 더 찍어요</div></div></div></div>{qrBtn}{mgr && <button className="btn big-cta" style={{ marginTop: 12 }} onClick={() => run(async () => { await post(`/requests/${r.id}/messages`, { text: '방문 장소는 따로 안내드린 주소예요. 도착하시면 현관 안쪽 QR 카드를 찍어 주세요.' }); await done('방문 장소 안내를 보냈어요'); })}>방문 장소 안내 보내기</button>}{can(r, 'cancel', true) && <button className="btn ghost sm" style={{ marginTop: 10 }} disabled={busy} onClick={() => reject('cancel', '일정 취소 사유', '취소했어요')}>일정 취소</button>}</>;
    else if (k === 1) action = <><div className="chk on" style={{ marginTop: 16 }}><span className="dotp" /><div className="grow"><b>{v.name} 님 도착</b> · {checkInfo}<div className="mute2">끝나면 종료 QR을 {mgr ? '찍어요' : '보여 주세요'} · 예정 종료 {hh(Number(r.startHour) + Number(r.hours))}</div></div></div><div className="btns" style={{ marginTop: 10 }}><button className="btn warn" onClick={sos}><Ic n="bell" s={18} /> 긴급</button>{mgr ? <button className="btn" onClick={qrCard}><Ic n="qr" s={18} /> 현관 QR 카드</button> : <button className="btn" onClick={() => showQr(1)}><Ic n="qr" s={18} /> 내 QR 보여주기 (종료)</button>}</div></>;
    else action = <div className="chk" style={{ marginTop: 16 }}><span className="dotp" style={{ background: '#1F9D63', animation: 'none', boxShadow: 'none' }} /><div className="grow"><b>활동 끝</b> · {tm(r.checkInAt)} ~ {tm(r.checkOutAt)} ({durTxt(durMin(r.checkInAt, r.checkOutAt))})<div className="mute2">{v.name} 님이 일지를 올리면 확인해 주세요. 확인하면 시간이 정산돼요</div></div></div>;
  } else if (st === 'LOG_SUBMITTED' && recv) {
    const adj = r.adjust && r.adjust.status === 'PENDING';
    action = (
      <>
        <div className="ai" style={{ marginTop: 16 }}><div className="ah"><Spk />활동 일지<Mod n={3} /></div><div className="small" style={{ whiteSpace: 'pre-wrap' }}>{r.logText}</div>{r.logPhotoUrl && <img src={r.logPhotoUrl} alt="활동 사진" style={{ width: '100%', borderRadius: 12, marginTop: 10 }} />}</div>
        <CareMemo text={r.careMemo} style={{ marginTop: 10 }} />
        {r.checkInAt && <div className="mute2" style={{ marginTop: 6 }}>체크 기록 {tm(r.checkInAt)} ~ {tm(r.checkOutAt)} ({durTxt(durMin(r.checkInAt, r.checkOutAt))}){r.checkMethod === 'manual' ? ' · 버튼으로 기록돼 시간을 한 번 더 확인해 주세요' : ''}</div>}
        {adj && <div className="card" style={{ marginTop: 10 }}><div className="b">시간 조정 요청</div><div className="mute2">{v.name} 님이 실제 시간을 {r.adjust.hours}시간으로 바꿔 달라고 했어요 (예정 {r.hours}시간).</div><div className="btns" style={{ marginTop: 10 }}><button className="btn ghost sm" onClick={() => act('adjust/answer', { accept: false }, '시간 조정을 거절했어요')}>거절</button><button className="btn sm" onClick={() => act('adjust/answer', { accept: true }, '시간 조정을 반영했어요')}>반영하기</button></div></div>}
        <div className="sum" style={{ marginTop: 10 }}>
          <div className="between"><span className="muted">예정 시간</span><b>{r.hours}시간</b></div>
          <div className="between"><span className="muted">{mgr || r.elder ? `${toName} 차감` : '내 통장에서 차감'}</span><b className="cr minus">{mgr || r.elder ? '없음 (기관 대상)' : `−${fmt(Number(r.hours) * lvWeight(r.difficulty, r.weight))}시간`}</b></div>
          <div className="between"><span className="muted">{v.name} 님 적립</span><b className="cr plus">+{fmt(r.estEarn)}시간</b></div>
        </div>
        <div className="btns" style={{ marginTop: 12 }}>
          {mgr ? <button className="btn" disabled={busy} onClick={() => sheet(() => <RateSheetOrg r={r} onDone={() => { closeSheet(); done(); }} />)}>대신 확인·평가</button>
            : <button className="btn" disabled={busy} onClick={() => run(async () => { await post(`/requests/${r.id}/confirm`); await refresh(); toast('확인했어요. 시간이 정산됐어요'); nav(`/req/${r.id}/rate`, { replace: true }); })}>확인했어요</button>}
        </div>
        <div className="mute2 center" style={{ marginTop: 8 }}>24시간 안에 확인하지 않으면 자동 확정돼요</div>
      </>
    );
  } else if (st === 'LOG_SUBMITTED') {
    const adj = r.adjust && r.adjust.status;
    action = <><div className="notice" style={{ marginTop: 16 }}>일지를 제출했어요. {r.elder ? '담당자' : '상대'}가 확인하면 적립돼요 (24시간 뒤 자동 확정).</div>{adj ? <div className="mute2" style={{ marginTop: 8 }}>시간 조정 요청 {r.adjust.hours}시간 · {{ PENDING: '확인 대기', ACCEPTED: '반영됨', REJECTED: '거절됨' }[adj] || adj}</div> : <button className="btn ghost" style={{ marginTop: 10 }} onClick={() => sheet(() => <AdjustSheet r={r} onDone={() => { closeSheet(); reload(); }} />)}>시간 조정 요청</button>}</>;
  } else if (st === 'DONE') {
    action = (
      <>
        <div className="sum" style={{ marginTop: 16 }}><div className="between"><span className="muted">{iVol ? '내 적립' : `${v.name} 님 적립`}</span><b className="cr plus">+{fmt(r.earned ?? r.estEarn)}시간</b></div></div>
        {!r.rated && !(iVol && r.elder) && <button className="btn big-cta" style={{ marginTop: 12 }} onClick={() => (mgr ? sheet(() => <RateSheetOrg r={r} onDone={() => { closeSheet(); done(); }} />) : nav(`/req/${r.id}/rate`))}>{mgr ? '대신 평가하기' : '평가하기'}</button>}
        {r.rated && <div className="mute2 center" style={{ marginTop: 10 }}>평가를 남겼어요</div>}
        {iVol && <button className="btn ghost" style={{ marginTop: 10 }} onClick={() => sheet(() => <AppealSheet r={r} onDone={() => { closeSheet(); reload(); }} />)}>난이도 이의 신청</button>}
      </>
    );
  } else if (['REJECTED', 'DECLINED', 'EXPIRED', 'CANCELLED', 'NOSHOW'].includes(st)) {
    action = <div className="notice" style={{ marginTop: 16 }}>{{ REJECTED: '거절된 요청이에요', DECLINED: '최종 거부된 요청이에요', EXPIRED: '24시간 안에 응답이 없어 만료됐어요', CANCELLED: '취소된 일정이에요', NOSHOW: '노쇼로 처리된 일정이에요' }[st]}{r.rejectReason ? ` · 사유: ${r.rejectReason}` : ''}</div>;
  }

  const showIntro = (st === 'VOL_ACCEPTED' && recv) || mgr;
  const title = mgr ? `${toName} · ${KIND_LABEL[r.kind] || '요청'}` : r.kind === 'PROPOSAL' ? '봉사 제안' : r.kind === 'REGULAR' ? '단골 요청' : '도움 요청';
  return (
    <Screen title={title} noTabs bodyRef={bodyRef}>
      <div className="between"><StTag st={st} /><span className="mute2">{mgr ? '담당자가 대신 진행' : iVol ? '내가 봉사' : '내가 도움 받음'}{r.leftText && ['SENT', 'VOL_ACCEPTED'].includes(st) && <> · <b style={{ color: '#E5484D' }}>{r.leftText} 남음</b></>}</span></div>
      <FlowBar r={r} />
      {showIntro && v.name && (
        <div className="intro" style={{ marginTop: 14 }}>
          <div className="mute2" style={{ marginBottom: 10 }}>{st === 'VOL_ACCEPTED' ? `${v.name} 님이 수락했어요 · 소개 카드` : '봉사자'}</div>
          <div className="row" style={{ gap: 14 }}><div className="ph">{v.name[0]}</div><div className="grow"><div className="b" style={{ fontSize: 18 }}>{v.name} <span className="mute2" style={{ fontWeight: 500 }}>{v.ageGroup}</span></div><div><Stars r={v.ratingAvg} /> <span className="mute2">· 활동 {v.ratingCount || 0}회</span></div><div className="tags" style={{ marginTop: 6 }}>{v.crime && <span className="badge trust">✓ 범죄경력 확인</span>}{v.eduDone && <span className="badge trust">✓ 교육 수료</span>}{v.isRegular && <span className="badge">★ {mgr ? `${toName} 단골` : '내 단골'}</span>}</div></div></div>
          {v.intro && <div className="small" style={{ marginTop: 12 }}>“{v.intro}”</div>}
          {v.experience && <div className="mute2" style={{ marginTop: 6 }}>{v.experience}</div>}
          {v.tags && v.tags.length > 0 && <div className="tags" style={{ marginTop: 8 }}>{v.tags.map((t) => <span key={t} className="tag2">{t}</span>)}</div>}
        </div>
      )}
      {r.elder && iVol && <div className="lock" style={{ marginTop: 14 }}><Ic n="users" s={18} /><div><b>{r.elder.displayName}</b> · {r.elder.dong} · 기관 등록 대상{r.elder.caution && <div style={{ marginTop: 4 }}>{r.elder.caution}</div>}<div style={{ marginTop: 6, opacity: 0.85 }}>{['CONFIRMED', 'IN_PROGRESS'].includes(st) ? '방문 장소는 아래 메시지로 담당자가 알려드려요.' : '수락되면 담당자가 방문 장소를 따로 알려드려요.'} 이 활동은 받는 분의 시간이 차감되지 않아요.</div></div></div>}
      {!r.elder && !showIntro && !mgr && (() => {
        const o = iVol ? r.recipient : r.volunteer;
        if (!o) return null;
        return <div className="card item click" style={{ marginTop: 14 }} role="button" tabIndex={0} onClick={() => profile(o.id)}><Avatar name={o.name} /><div className="grow"><div className="b">{o.name} {!iVol && v.isRegular && <span className="badge">★ 단골</span>}</div><div className="mute2">{iVol ? '도움 받는 분' : '봉사하는 분'}{!iVol && <> · <Stars r={v.ratingAvg} /></>}</div></div>›</div>;
      })()}
      <div className="card" style={{ marginTop: 10 }}>
        <div className="row" style={{ gap: 12 }}><CatIc k={r.category} size={44} /><div className="grow"><div className="b" style={{ fontSize: 16.5 }}>{r.content}</div><div className="mute2">{mgr ? `${toName} · ${r.elder ? r.elder.dong : ''}` : `${r.category} · ${diffText(r)}`}</div></div></div>
        <div className="divider" />
        <dl className="kv">
          <dt>일시</dt><dd>{dlabel(r.date)} {hh(r.startHour)} ~ {hh(Number(r.startHour) + Number(r.hours))} ({r.hours}시간)</dd>
          <dt>{iVol ? '내 예상 적립' : '봉사자 적립'}</dt><dd className="cr plus">+{fmt(r.estEarn)}시간</dd>
          {(mgr || (r.elder && !iVol)) && <><dt>대상 차감</dt><dd>없음</dd></>}
          {r.postTitle && <><dt>공고</dt><dd><a role="button" tabIndex={0} style={{ color: 'var(--brand)', cursor: 'pointer' }} onClick={() => nav(`/post/${r.postId}`)}>{r.postTitle}</a></dd></>}
          {r.memo && <><dt>메모</dt><dd>{r.memo}</dd></>}
        </dl>
      </div>
      {action}
      {r.logText && !(st === 'LOG_SUBMITTED' && recv) && (
        <div className="ai" style={{ marginTop: 14 }}><div className="ah"><Spk />활동 일지<Mod n={3} /></div><div className="small" style={{ whiteSpace: 'pre-wrap' }}>{r.logText}</div></div>
      )}
      {!(st === 'LOG_SUBMITTED' && recv) && <CareMemo text={r.careMemo} style={{ marginTop: 10 }} />}
      {r.careMemo && <div className="mute2" style={{ marginTop: 6 }}>전달 메모는 쓴 봉사자·{r.elder ? '담당자' : '받는 분과 공유에 동의한 가족'}만 볼 수 있어요.</div>}
      {r.canRejectVolunteer && v.name && (
        <button className="btn ghost sm" style={{ marginTop: 12 }} disabled={busy} onClick={() => sheet(() => <MgrRejectSheet r={r} onSubmit={(reason, block) => {
          closeSheet();
          run(async () => { await post(`/org/requests/${r.id}/reject-volunteer`, { reason, block }); await done(block ? '거부하고 앞으로 받지 않기로 했어요' : '봉사자를 거부했어요. 봉사자에게 알림이 가요'); });
        }} />)}>이 봉사자 거부하기</button>
      )}
      {canMsg && (
        <>
          <div className="sec-t"><span>메시지{mgr ? ` (${toName} 대신)` : ''}</span><span className="mute2" style={{ fontWeight: 500 }}>{ro ? '읽기만 가능' : '실시간 채팅 아님'}</span></div>
          <div className="thread">
            <div className="tsys">연락처 교환·금전 요구는 금지예요. 문제가 있으면 메시지를 바로 신고하세요.</div>
            {msgs.map((m) => {
              if (m.systemMsg) return <div key={m.id} className="tsys">{m.text}</div>;
              const mine = me && m.senderId === me.id;
              return (
                <div key={m.id} className={'tm ' + (mine ? 'me' : '')}>
                  {!mine && <div className="mute2" style={{ margin: '0 4px 3px', fontSize: 12 }}>{m.senderLabel}</div>}
                  <div className="bub">{m.text}</div>
                  {m.warn && <div className="meta" style={{ color: '#E5484D' }}>⚠ {typeof m.warn === 'string' ? m.warn : '연락처·계좌로 보이는 내용이 있어요. 연락처 교환·금전 요구는 금지예요'}</div>}
                  <div className="meta"><span>{dshort(m.createdAt)}</span>{!mine && <a role="button" tabIndex={0} onClick={() => report({ targetType: 'MESSAGE', targetId: m.id, targetLabel: `메시지 · ${m.senderLabel}` })}>신고</a>}</div>
                </div>
              );
            })}
          </div>
          {!ro && (
            <form className="tin" onSubmit={(e) => {
              e.preventDefault();
              const t = msg.trim(); if (!t) return;
              run(async () => { await post(`/requests/${r.id}/messages`, { text: t }); setMsg(''); await reload(); setTimeout(() => { if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight; }, 50); });
            }}>
              <MicButton onText={(t) => setMsg((x) => (x.trim() ? `${x.trim()} ${t}` : t))} />
              <input className="in" placeholder={mgr ? '봉사자에게 보낼 내용' : '시간·장소 등을 적어 보내요'} value={msg} onChange={(e) => setMsg(e.target.value)} aria-label="메시지" />
              <button type="submit" aria-label="보내기"><Ic h={I.send} /></button>
            </form>
          )}
        </>
      )}
      <button className="au-demo" onClick={() => report({ targetType: 'REQUEST', targetId: r.id, targetLabel: `${KIND_LABEL[r.kind] || '요청'} · ${r.content}` })}>신고하기</button>
    </Screen>
  );
}

/* ---------- 활동 일지 (B-24) ---------- */
export function LogWrite() {
  const { id } = useParams();
  const nav = useNavigate();
  const { toast } = useUi();
  const [r] = useLoad(() => api(`/requests/${id}`), [id]);
  const [raw, setRaw] = useState('');
  const [tidy, setTidy] = useState(null);
  const [consent, setConsent] = useState(false);
  const [photo, setPhoto] = useState(null);
  const [careMemo, setCareMemo] = useState('');
  const [busy, run] = useBusy();
  if (r === undefined) return <Screen title="활동 일지" noTabs><Loading /></Screen>;
  if (!r) return <Screen title="활동 일지" noTabs><Empty>요청을 찾을 수 없어요</Empty></Screen>;
  const mins = durMin(r.checkInAt, r.checkOutAt);
  return (
    <Screen title="활동 일지" noTabs>
      <div className="card"><div className="mute2">{r.elder ? r.elder.displayName : r.recipient && r.recipient.name} · {dlabel(r.date)} {hh(r.startHour)}~{hh(Number(r.startHour) + Number(r.hours))}</div><div className="b" style={{ fontSize: 17 }}>{r.content}</div></div>
      <label className="f" htmlFor="lg">한 줄 기록 <span style={{ color: '#E5484D' }}>(필수)</span></label>
      <VoiceField value={raw} onChange={(v) => { setRaw(v); setTidy(null); }}><textarea id="lg" className="in" style={{ minHeight: 130 }} placeholder="예) 냉장고 정리함. 유통기한 지난 반찬 3개 버림. 우유랑 계란이 떨어졌다고 하심" value={raw} onChange={(e) => { setRaw(e.target.value); setTidy(null); }} /></VoiceField>
      <label className="f" htmlFor="cm">전달 메모 <span className="mute2" style={{ fontWeight: 500 }}>(선택 · {careMemo.length}/500)</span></label>
      <textarea id="cm" className="in" style={{ minHeight: 80 }} maxLength={500} placeholder="병원에서 들은 말, 다음에 챙길 것" value={careMemo} onChange={(e) => setCareMemo(e.target.value.slice(0, 500))} />
      <div className="mute2" style={{ margin: '4px 2px 0' }}>{r.elder ? '담당자' : '받는 분(과 공유에 동의한 가족)'}에게만 보여요. 다른 봉사자에게는 보이지 않아요.</div>
      <label className="f">사진 <span className="mute2" style={{ fontWeight: 500 }}>(받는 분이 동의한 경우만, 선택)</span></label>
      <div className={'su-check ' + (consent ? 'on' : '')} role="button" tabIndex={0} onClick={() => setConsent(!consent)}><span className="bx" /><div className="small">받는 분({r.elder ? '담당자 확인' : '본인'})이 사진 촬영에 동의했어요</div></div>
      {consent && <Upload label="사진 올리기" accept="image/*" style={{ minHeight: 80 }} onUploaded={setPhoto} />}
      <button className="btn soft" style={{ marginTop: 14 }} onClick={async () => {
        if (!raw.trim()) return toast('한 줄 기록을 먼저 적어 주세요');
        setTidy({ loading: true });
        try { const t = await post('/ai/log-tidy', { raw }); setTidy(t); } catch { setTidy(null); }
      }}><Ic h={I.spark} /> AI로 일지 정리하기</button>
      <div style={{ marginTop: 12 }}>
        {tidy && tidy.loading && <AiLoading />}
        {tidy && tidy.text && (
          <div className="ai"><div className="ah"><Spk />정리된 활동 일지<Mod n={3} /></div>
            <textarea className="in" style={{ background: '#fff', minHeight: 100 }} value={tidy.text} onChange={(e) => setTidy({ ...tidy, text: e.target.value })} aria-label="정리된 일지" />
            <div className="mute2" style={{ marginTop: 6 }}>원래 기록: “{raw.slice(0, 30)}{raw.length > 30 ? '…' : ''}” · 고칠 수 있어요</div>
            <RuleNote source={tidy.source} />
          </div>
        )}
      </div>
      <div className="sum" style={{ marginTop: 14 }}>
        <div className="between"><span className="muted">활동 시간 (미리 정한 시간)</span><b>{r.hours}시간</b></div>
        {mins != null && <div className="between"><span className="muted">체크 기록 {tm(r.checkInAt)}~{tm(r.checkOutAt)}</span><b>{durTxt(mins)}</b></div>}
        {mins != null && Math.abs(mins - r.hours * 60) >= 30 && <div className="notice" style={{ marginTop: 6 }}>기록된 시간이 예정보다 {mins > r.hours * 60 ? '길어요' : '짧아요'}. 제출 후 시간 조정을 요청할 수 있어요.</div>}
        <div className="between"><span className="muted">예상 적립</span><b className="cr plus">+{fmt(r.estEarn)}시간</b></div>
      </div>
      <button className="btn big-cta" style={{ marginTop: 14 }} disabled={busy} onClick={() => run(async () => {
        if (!raw.trim()) return toast('한 줄 기록은 꼭 적어야 해요');
        await post(`/requests/${r.id}/log`, { raw: raw.trim(), text: (tidy && tidy.text) || raw.trim(), photoUrl: consent ? photo : null, photoConsent: consent, careMemo: careMemo.trim() });
        toast('일지를 제출했어요. 확인되면 적립돼요');
        nav(-1);
      })}>일지 제출하기</button>
      <div className="mute2 center" style={{ marginTop: 8 }}>실제 시간이 달랐다면 제출 후 시간 조정을 요청할 수 있어요</div>
    </Screen>
  );
}

/* ---------- 평가 (B-38, B-39) ---------- */
export function Rate() {
  const { id } = useParams();
  const nav = useNavigate();
  const { toast } = useUi();
  const [r] = useLoad(() => api(`/requests/${id}`), [id]);
  const [s, setS] = useState(5);
  const [tags, setTags] = useState([]);
  const [comment, setComment] = useState('');
  const [busy, run] = useBusy();
  if (!r) return <Screen title="평가하기" noTabs>{r === undefined ? <Loading /> : <Empty>요청을 찾을 수 없어요</Empty>}</Screen>;
  const iVol = r.myRole === 'VOLUNTEER';
  const target = iVol ? r.recipient || {} : r.volunteer || {};
  const T = iVol ? ['약속을 잘 지켜요', '친절해요', '설명이 자세해요', '편안했어요'] : ['시간 약속을 잘 지켜요', '설명이 친절해요', '편안해요', '꼼꼼해요', '대화가 즐거워요', '안전하게 도와줘요'];
  return (
    <Screen title="평가하기" noTabs>
      <div className="center" style={{ padding: '10px 0' }}><Avatar name={target.name} s={64} style={{ margin: '0 auto' }} /><div className="b" style={{ fontSize: 18, marginTop: 8 }}>{iVol ? `${target.name} 님과의 활동은 어떠셨어요?` : `${target.name} 님의 도움은 어떠셨어요?`}</div></div>
      <div className="stars-in">{[1, 2, 3, 4, 5].map((i) => <span key={i} className={i <= s ? 'on' : ''} role="button" tabIndex={0} aria-label={`${i}점`} onClick={() => setS(i)}>★</span>)}</div>
      <label className="f">좋았던 점</label>
      <div className="tags">{T.map((t) => <button key={t} className={'chip ' + (tags.includes(t) ? 'on' : '')} onClick={() => setTags(tags.includes(t) ? tags.filter((x) => x !== t) : [...tags, t])}>{t}</button>)}</div>
      <label className="f" htmlFor="rc">한마디 (선택)</label>
      <VoiceField value={comment} onChange={setComment}><textarea id="rc" className="in" style={{ minHeight: 80 }} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="고마운 마음을 적어 주세요 (금전 표현 없이)" /></VoiceField>
      <div className="notice" style={{ marginTop: 14 }}>{iVol ? '봉사받는 분 평가는 상대에게 보이지 않는 비공개 점수예요.' : `같은 분께 3번 이상 도움을 받으면 그분에게 '단골' 배지가 생겨요.${r.volunteer && r.volunteer.isRegular ? ` 지금 ${target.name} 님은 이미 내 단골이에요 ★` : ''}`}</div>
      <button className="btn big-cta" style={{ marginTop: 18 }} disabled={busy} onClick={() => run(async () => {
        await post(`/requests/${r.id}/rate`, { stars: s, tags, comment: comment.trim() });
        toast('평가를 남겼어요');
        nav(`/req/${r.id}`, { replace: true });
      })}>평가 남기기</button>
    </Screen>
  );
}
