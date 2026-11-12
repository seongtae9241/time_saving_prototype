import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Loading, Empty, AiLoading, RuleNote, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post, put } from '../api.js';
import { CATS, SHORT, arr } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { speak, useSpeechInput } from './voice.jsx';

// 첫 활동 교육·연습 (기능 U-34, 프로토타입 2026-11-12): AI 역할극 상황을 모두 안전 규칙에 맞게 마치면 첫 활동 교육(안전 교육) 이수로 기록된다.
// 이수가 봉사의 필수 조건인지는 운영자의 봉사 인증 항목 설정(edu)에 따른다(서버가 edu.education.required 로 알려 준다).
/* 서버가 규칙을 안 주면 프로토타입 카드를 쓴다 */
const RULES = [
  { ic: 'clock', c: ['#EAF0FF', '#3D6DF2'], t: '도운 시간만큼\n통장에 쌓여요', p: '1시간을 도우면 1시간이 쌓여요. 동행처럼 책임이 큰 일은 1.5배, 장보기처럼 수고가 드는 일은 1.2배예요.', ex: '예) 병원 동행 2시간 → +3.0시간' },
  { ic: 'check', c: ['#E6F7EE', '#1F9D63'], t: '도착하면 QR,\n끝나면 QR', p: '받는 분 화면이나 인쇄 카드의 QR을 찍어야 활동 시간이 재져요.', ex: 'QR이 안 찍히면 받는 분 화면의 번호 6자리를 입력해요' },
  { ic: 'pen', c: ['#FFF6D6', '#C99500'], t: '끝나면\n일지 한 줄', p: '무엇을 했는지 한 줄만 적으면 AI가 다듬어 줘요. 받는 분이 확인해야 시간이 들어와요.', ex: '예) 행복마트에서 쌀 10kg, 반찬거리 장보기' },
  { ic: 'shield', c: ['#FFEDED', '#E5484D'], t: '할 수 없는 일이\n있어요', p: '목욕·기저귀·파스 붙이기 같은 신체 돌봄, 약 먹이기, 돈과 카드 관리는 봉사로 하지 않아요.', ex: '부탁받으면 정중히 거절하고, 담당자나 공공 서비스로 안내해요' },
  { ic: 'msg', c: ['#F0ECFF', '#7656E3'], t: '연락처와 돈은\n주고받지 않아요', p: '전화번호, 카톡 아이디, 용돈, 기프티콘 모두 안 돼요. 다시 만나고 싶으면 앱의 단골 요청을 써요.', ex: '“마음만 감사히 받을게요. 대신 시간이 통장에 쌓여요!”' },
];
const RULE_FIT = {
  '디지털 도움': { ic: 'gear', c: ['#F0ECFF', '#7656E3'], t: '비밀번호는\n직접 입력하게 해요', p: '인증번호, 비밀번호, 계좌번호는 상대가 직접 누르게 하고 화면을 보지 않아요.', ex: '맞춤 · 디지털 도움을 골라서 넣었어요', fit: 1 },
  '장보기·심부름': { ic: 'coin', c: ['#FFF1E2', '#EE8424'], t: '영수증과 잔돈은\n그 자리에서', p: '장 본 돈은 받는 분이나 담당자가 미리 준비해요. 영수증과 잔돈을 함께 확인해요.', ex: '맞춤 · 장보기를 골라서 넣었어요', fit: 1 },
  '말동무·정서': { ic: 'heart', c: ['#FFEDF3', '#E2557F'], t: '같은 이야기는\n처음 듣는 것처럼', p: '귀가 어두운 분께는 천천히, 짧게, 또박또박. 같은 말을 반복하셔도 처음처럼 반응해요.', ex: '맞춤 · 말동무를 골라서 넣었어요', fit: 1 },
  '동행': { ic: 'pin', c: ['#EAF0FF', '#3D6DF2'], t: '진료 결정은\n본인이 해요', p: '병원 동행은 이동과 접수를 돕는 일이에요. 진료 내용은 받는 분이 결정하고, 부축까지만 해요.', ex: '맞춤 · 동행을 골라서 넣었어요', fit: 1 },
};
const PCOL = [['#FFEDF3', '#E2557F'], ['#FFF1E2', '#EE8424'], ['#EAF0FF', '#3D6DF2'], ['#E4F5F9', '#178FAD'], ['#FFF6D6', '#C99500']];
const pcol = (p, i) => p.col || PCOL[(i ?? (Number(p.id) || 0)) % PCOL.length];
const pinit = (p) => p.initial || p.init || (p.name || '?').replace(/\s*님$/, '').slice(-1);
const turnsOf = (p) => { let t = p.turns || p.turnsJson || []; if (typeof t === 'string') { try { t = JSON.parse(t); } catch { t = []; } } return t; };
const diagOf = (e) => { let d = e && e.diag; if (typeof d === 'string') { try { d = JSON.parse(d); } catch { d = null; } } return d; };

function rulesFor(edu) {
  const COLS = Object.fromEntries([...RULES, ...Object.values(RULE_FIT)].map((r) => [r.ic, r.c]));
  if (edu && Array.isArray(edu.rules) && edu.rules.length) return edu.rules.map((r, i) => { const ic = r.icon || r.ic || RULES[i % RULES.length].ic; return { ic, c: r.c || COLS[ic] || ['#EAF0FF', '#3D6DF2'], t: r.t || r.title, p: r.p || r.body || r.text, ex: r.ex || r.example || '', fit: r.fit }; });
  const base = RULES;
  const d = diagOf(edu);
  const fit = d ? arr(d.cats).filter((c) => RULE_FIT[c]).slice(0, 2).map((c) => RULE_FIT[c]) : [];
  return [...base, ...fit];
}
const rpRes = (edu, id) => (edu && edu.roleplay && (edu.roleplay[id] || edu.roleplay[String(id)])) || null;

/* 상황 하나 연습 시트 (프로토타입 rpStart / rpSay): 어르신(AI)이 상황을 던지고, 답하면 칭찬 또는 "이렇게 바꿔 말해 보세요" */
function PracticeSheet({ sit, onOther, onClose, onResult }) {
  const { toast } = useUi();
  const [said, setSaid] = useState(null);
  const [res, setRes] = useState(null);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const say = async (t) => {
    t = String(t || '').trim();
    if (!t || busy) return;
    setBusy(true); setSaid(t); setText('');
    try { const r = await post('/edu/practice', { key: sit.key, text: t }); setRes(r); if (onResult) onResult(r); } catch { setSaid(null); }
    setBusy(false);
  };
  const spch = useSpeechInput({ onFinal: (t) => say(t), onInterim: setText });
  const fb = res && res.feedback;
  return (
    <>
      <h2>{sit.title}</h2>
      <div className="rp-msg"><Spk /> {sit.line}</div>
      {said && <div className="rp-msg rp-me">{said}</div>}
      {busy && <div className="rp-msg mute2">AI가 답을 살펴보는 중…</div>}
      {res && res.reply && <div className="rp-msg"><Spk /> {res.reply}</div>}
      {res && res.ok && <div className="rp-msg rp-ok" role="status">{res.praise || '잘했어요! 안전 규칙을 지키면서 따뜻하게 말했어요.'}{res.education ? (res.education.done ? ' 상황을 모두 마쳐서 첫 활동 교육을 이수했어요.' : ` 이 상황을 마쳤어요 (${res.education.doneCount}/${res.education.total}).`) : ''}</div>}
      {res && !res.ok && fb && <div className="rp-msg rp-fb" role="status"><b>이렇게 바꿔 말해 보세요</b><br />{fb.reason || fb.message}<br />“{fb.better}”</div>}
      {res && <RuleNote source={res.source} />}
      {!said && (
        <>
          <div className="mute2" style={{ margin: '10px 0 4px' }}>어떻게 답할까요? 직접 적거나 말해도 돼요</div>
          <div className="stack">{(sit.options || []).map((o) => <button key={o} className="btn ghost" style={{ height: 'auto', minHeight: 48, padding: '10px 14px', whiteSpace: 'normal', textAlign: 'left' }} onClick={() => say(o)}>{o}</button>)}</div>
          <form className="composer" style={{ marginTop: 10 }} onSubmit={(e) => { e.preventDefault(); if (!text.trim()) return toast('할 말을 적어 주세요'); say(text); }}>
            <button type="button" className={'mic' + (spch.listening ? ' on' : '')} aria-label="음성 입력" onClick={spch.toggle}><Ic h={I.mic} /></button>
            <input className="in" placeholder="내 말로 대답하기" value={text} onChange={(e) => setText(e.target.value)} aria-label="대답" />
            <button type="submit" aria-label="보내기"><Ic h={I.send} /></button>
          </form>
        </>
      )}
      {res && (
        <div className="btns" style={{ marginTop: 14 }}>
          {!res.ok ? <button className="btn ghost" onClick={() => { setSaid(null); setRes(null); }}>다시 답하기</button> : <button className="btn ghost" onClick={onOther}>다른 상황</button>}
          {!res.ok ? <button className="btn ghost" onClick={onOther}>다른 상황</button> : null}
          <button className="btn" onClick={onClose}>연습 마치기</button>
        </div>
      )}
    </>
  );
}

/* ---------- 첫 활동 교육·연습 (AI 역할극) ---------- */
export function Edu() {
  const nav = useNavigate();
  const { sheet, closeSheet, toast } = useUi();
  const { refresh } = useAuth();
  const [edu, reload] = useLoad(() => api('/edu'), []);
  if (edu === undefined) return <Screen title="첫 활동 교육" noTabs><Loading /></Screen>;
  if (!edu) return <Screen title="첫 활동 교육" noTabs><Empty>교육을 불러오지 못했어요</Empty></Screen>;
  const sits = edu.situations || [];
  const ps = edu.personas || [];
  const ed = edu.education || { done: false, total: sits.length, doneCount: 0, doneKeys: [], required: false, on: true };
  const doneKeys = ed.doneKeys || [];
  // 답을 보낼 때마다 서버가 진행 상태를 알려 준다. 이수가 되면 내 정보(봉사 자격)를 다시 읽는다
  const onResult = (r) => { reload(); if (r && r.education && r.education.done && !ed.done) { refresh(); toast('첫 활동 교육(안전 교육)을 이수했어요'); } };
  const open = (i) => sheet(<PracticeSheet key={sits[i].key + Date.now()} sit={sits[i]} onResult={onResult} onOther={() => open((i + 1) % sits.length)} onClose={() => { closeSheet(); reload(); }} />);
  const nextIdx = sits.findIndex((r) => !doneKeys.includes(r.key));
  return (
    <Screen title="첫 활동 교육" noTabs>
      <h2 style={{ margin: 0, fontSize: 20 }}>첫 활동 교육 <span className="mod m1">AI 역할극</span></h2>
      <p className="mute2" style={{ margin: '6px 0 10px' }}>AI가 도움 받는 어르신이 되어 상황을 던져요. 상황 {sits.length}개를 모두 안전 규칙에 맞게 답하면 안전 교육 이수예요.{ed.required ? ' 봉사를 시작하기 전에 꼭 마쳐야 해요.' : ''}</p>
      <div className={'card item'} role="status" style={ed.done ? { background: '#E6F7EE' } : null}>
        <div className="ic" style={ed.done ? { background: '#fff', color: '#1F9D63' } : null}><Ic n={ed.done ? 'check' : 'shield'} s={20} /></div>
        <div className="grow"><div className="b">{ed.done ? '안전 교육을 이수했어요' : `안전 교육 ${ed.doneCount}/${ed.total || sits.length}개 마침`}</div><div className="mute2">{ed.done ? `${ed.completedAt ? `${String(ed.completedAt).slice(0, 10)} 이수 · ` : ''}봉사 인증과 프로필에 표시돼요` : ed.required ? '모두 마쳐야 봉사 제안·도움 가능 공고를 올릴 수 있어요' : '모두 마치면 봉사 인증과 프로필에 이수 표시가 붙어요'}</div></div>
        <span className={'st ' + (ed.done ? 'ok' : 'wait')}>{ed.done ? '이수' : ed.required ? '필수' : '선택'}</span>
      </div>
      {!ed.done && nextIdx >= 0 && <button className="btn" style={{ marginTop: 10 }} onClick={() => open(nextIdx)}>{ed.doneCount ? '이어서 하기' : '교육 시작하기'} · {sits[nextIdx].title}</button>}
      {sits.length ? sits.map((r, i) => {
        const ok = ed.done || doneKeys.includes(r.key);
        return (
          <div key={r.key} className="card item click" style={{ marginTop: 8 }} role="button" tabIndex={0} onClick={() => open(i)} onKeyDown={(e) => { if (e.key === 'Enter') open(i); }}>
            <div className="ic" style={ok ? { background: '#E6F7EE', color: '#1F9D63' } : null}><Ic n={ok ? 'check' : 'chat'} s={20} /></div><div className="grow"><div className="b">{r.title}</div><div className="mute2">상황 {i + 1}{ok ? ' · 마쳤어요' : ''}</div></div>›
          </div>
        );
      }) : <Empty>준비된 상황이 없어요</Empty>}
      {ps.length > 0 && (
        <>
          <div className="sec-t"><span>AI 어르신과 길게 대화해 보기</span><Mod n={6} /></div>
          <div className="mute2" style={{ margin: '-4px 0 6px' }}>대화 중에 위 상황이 하나씩 나와요. 답이 안전 규칙에 어긋나면 바로 알려 드려요.</div>
          {ps.map((p, i) => {
            const r = rpRes(edu, p.id);
            return (
              <div key={p.id} className="card click" style={{ marginTop: 10 }} role="button" tabIndex={0} onClick={() => nav(`/edu/rp/${p.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/edu/rp/${p.id}`); }}>
                <div className="persona" style={{ background: 'none', padding: 0 }}><span className="pa" style={{ background: pcol(p, i)[0], color: pcol(p, i)[1] }}>{pinit(p)}</span><div className="grow"><div className="b">{p.name} <span className="mute2" style={{ fontWeight: 500 }}>{p.ageLabel}</span></div><div className="small muted">{p.trait}</div></div>{r ? <span className="st end">해 봤어요</span> : null}</div>
                {p.goal && <div className="hint" style={{ marginTop: 12, background: 'var(--fill)', color: 'var(--sub)' }}>연습 목표 · {p.goal}</div>}
                {r && <div className="mute2" style={{ marginTop: 8 }}>지난 연습 · 소통 {r.comm} · 공감 {r.emp} · 안전 규칙 {r.safe ? '지킴' : '어긋남'}</div>}
              </div>
            );
          })}
        </>
      )}
      <div className="sec-t"><span>읽을거리</span><span className="mute2" style={{ fontWeight: 500 }}>선택</span></div>
      <div className="ml">
        <div role="button" tabIndex={0} onClick={() => nav('/edu/card')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/edu/card'); }}><Ic n="file" s={20} /><div className="grow">규칙 카드<span>활동할 때 지키는 규칙 {rulesFor(edu).length}장</span></div>›</div>
        <div role="button" tabIndex={0} onClick={() => nav('/edu/quiz')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/edu/quiz'); }}><Ic n="check" s={20} /><div className="grow">상황 퀴즈<span>{(edu.quiz || []).length || 5}문제 · 점수는 참고용이에요</span></div>›</div>
      </div>
      <div className="notice" style={{ marginTop: 12 }}>{ed.required ? '위 상황을 모두 마치면 안전 교육 이수예요. 길게 대화해 보기·규칙 카드·퀴즈는 더 연습하고 싶을 때 하는 선택이에요.' : '지금은 교육이 필수가 아니에요. 하지 않아도 신분증 확인만 되면 봉사할 수 있고, 마치면 이수 표시가 붙어요.'}</div>
    </Screen>
  );
}

/* ---------- 규칙 카드 ---------- */
export function EduCard() {
  const nav = useNavigate();
  const [edu] = useLoad(() => api('/edu'), []);
  const [i, setI] = useState(0);
  const [busy, run] = useBusy();
  if (edu === undefined) return <Screen title="규칙 카드" noTabs><Loading /></Screen>;
  const rs = rulesFor(edu);
  const r = rs[Math.min(i, rs.length - 1)];
  return (
    <Screen title="규칙 카드" noTabs>
      <div className="steps">{rs.map((_, k) => <span key={k} className={k <= i ? 'on' : ''} />)}</div>
      <div className="stepl"><span>{i + 1} / {rs.length}</span><span>{r.fit ? '맞춤 카드' : '규칙 카드'}</span></div>
      <div className="rcard" style={{ marginTop: 14 }}>
        <div className="big-ic" style={{ background: r.c[0], color: r.c[1] }}><Ic n={r.ic} s={32} /></div>
        <h3>{String(r.t).split('\n').map((x, k) => <span key={k}>{x}{k === 0 && String(r.t).includes('\n') ? <br /> : null}</span>)}</h3>
        <p>{r.p}</p>
        {r.ex && <div className="ex" style={{ marginTop: 20 }}>{r.ex}</div>}
      </div>
      <button className="btn soft sm" style={{ marginTop: 12 }} onClick={() => speak(`${String(r.t).replace('\n', ' ')}. ${r.p}. ${r.ex}`, { force: true })}><Ic n="mic" s={18} /> 소리로 읽어 주기</button>
      <div className="btns" style={{ marginTop: 10 }}>
        <button className="btn ghost" disabled={i === 0} style={i === 0 ? { opacity: 0.4 } : null} onClick={() => setI(i - 1)}>이전</button>
        <button className="btn" disabled={busy} onClick={() => (i < rs.length - 1 ? setI(i + 1) : run(async () => { if (!edu.cardsDone) await post('/edu/cards-done'); nav('/edu', { replace: true }); }))}>{i === rs.length - 1 ? '다 봤어요' : '다음'}</button>
      </div>
    </Screen>
  );
}

/* ---------- 상황 퀴즈 ---------- */
export function EduQuiz() {
  const nav = useNavigate();
  const { sheet, closeSheet } = useUi();
  const [edu] = useLoad(() => api('/edu'), []);
  const [i, setI] = useState(0);
  const [answers, setAnswers] = useState([]);
  const [picked, setPicked] = useState(null);
  const [busy, run] = useBusy();
  if (edu === undefined) return <Screen title="상황 퀴즈" noTabs><Loading /></Screen>;
  const QZ = (edu && edu.quiz) || [];
  if (!QZ.length) return <Screen title="상황 퀴즈" noTabs><Empty>퀴즈를 불러오지 못했어요</Empty></Screen>;
  const q = QZ[i];
  const opts = q.o || q.options || [];
  const ansIdx = q.a ?? q.answer;
  const hasAns = ansIdx != null;
  const okCount = hasAns ? answers.filter((a, k) => a === (QZ[k].a ?? QZ[k].answer)).length : null;
  const finish = (all) => run(async () => {
    const r = await post('/edu/quiz', { answers: all });
    const score = r.score ?? 0;
    const rs = (r.results || []).filter((x) => !x.correct);
    const Wrong = () => (rs.length ? <div style={{ marginTop: 10 }}>{rs.map((x) => <div key={x.no} className="fix soft"><div className="small b">{QZ[x.no] ? QZ[x.no].q : ''}</div><div className="now">정답: {QZ[x.no] ? (QZ[x.no].options || [])[x.answer] : ''} · {x.explain}</div></div>)}</div> : null);
    sheet(() => <><h2>{score}/{QZ.length} 맞혔어요</h2><p className="muted">점수는 참고용이에요. 통과나 수료는 없어요. AI 어르신과 대화하며 연습해 볼 수 있어요.</p><Wrong /><button className="btn" style={{ marginTop: 16 }} onClick={() => { closeSheet(); nav('/edu', { replace: true }); }}>연습하러 가기</button><button className="btn ghost" style={{ marginTop: 8 }} onClick={() => { closeSheet(); setI(0); setAnswers([]); setPicked(null); }}>다시 풀기</button></>);
  });
  const pick = (k) => {
    if (picked != null) return;
    setPicked(k);
    const all = [...answers]; all[i] = k; setAnswers(all);
    if (!hasAns) { if (i < QZ.length - 1) { setTimeout(() => { setI(i + 1); setPicked(null); }, 250); } else finish(all); }
  };
  return (
    <Screen title="상황 퀴즈" noTabs>
      <div className="steps">{QZ.map((_, k) => <span key={k} className={k <= i ? 'on' : ''} />)}</div>
      <div className="stepl"><span>{i + 1} / {QZ.length}</span>{okCount != null && <span>맞힌 문제 {okCount}</span>}</div>
      <div className="qz-q" style={{ marginTop: 18 }}>{q.q || q.question}</div>
      {opts.map((o, k) => {
        const cls = picked == null || !hasAns ? (picked === k ? 'on' : '') : k === ansIdx ? 'right' : k === picked ? 'wrong' : '';
        return <div key={k} className={'opt ' + cls} role="button" tabIndex={0} onClick={() => pick(k)}><span className="rd" /><div className="grow b">{o}</div>{picked != null && hasAns && k === ansIdx && <Ic n="check" s={18} />}</div>;
      })}
      {picked != null && hasAns && (
        <>
          <div className={'ai ' + (picked === ansIdx ? '' : 'warn')} style={{ marginTop: 14 }}><div className="ah"><Spk />{picked === ansIdx ? '정답이에요' : '다시 볼까요?'}</div><div className="small">{q.ex || q.explain}</div>{picked !== ansIdx && <button className="btn ghost sm" style={{ marginTop: 10 }} onClick={() => nav('/edu/card')}>관련 카드 다시 보기</button>}</div>
          <button className="btn" style={{ marginTop: 14 }} disabled={busy} onClick={() => (i < QZ.length - 1 ? (setI(i + 1), setPicked(null)) : finish(answers))}>{i === QZ.length - 1 ? '결과 보기' : '다음 문제'}</button>
        </>
      )}
    </Screen>
  );
}

/* ---------- 역할극 대화 ---------- */
export function EduRp() {
  const { id } = useParams();
  const nav = useNavigate();
  const [edu] = useLoad(() => api('/edu'), []);
  const [log, setLog] = useState(null); // {p:1|0, t, q, f, turn}
  const [i, setI] = useState(0);
  const [done, setDone] = useState(false);
  const [typing, setTyping] = useState(false);
  const [text, setText] = useState('');
  const [src, setSrc] = useState(null);
  const [opts, setOpts] = useState(null); // 서버가 준 다음 차례 선택지(대본이 있을 때만). null 이면 첫 차례 대본
  const bd = useRef(null);
  const ps = (edu && edu.personas) || [];
  const idx = ps.findIndex((x) => String(x.id) === String(id));
  const R = ps[idx];
  const spch = useSpeechInput({ onFinal: (t) => say(t), onInterim: setText });
  useEffect(() => { if (R && !log) setLog([{ p: 1, t: R.opening }]); }, [R]); // eslint-disable-line
  useEffect(() => { if (bd.current) bd.current.scrollTop = bd.current.scrollHeight; }, [log, typing]);
  if (edu === undefined) return <Screen title="역할극" noTabs><Loading /></Screen>;
  if (!R) return <Screen title="역할극" noTabs><Empty>상대를 찾을 수 없어요</Empty></Screen>;
  const col = pcol(R, idx);
  const turns = turnsOf(R);
  const choices = opts != null ? opts : ((turns[0] && (turns[0].o || turns[0].options)) || []);
  async function say(t) {
    t = String(t || '').trim(); if (!t || done || typing) return;
    setText('');
    const cur = log || [{ p: 1, t: R.opening }];
    const withMe = [...cur, { p: 0, t, turn: i }];
    setLog(withMe); setTyping(true);
    try {
      const history = cur.map((l) => ({ who: l.p ? 'them' : 'me', text: l.t }));
      const r = await post('/ai/roleplay', { personaId: R.id, history, text: t });
      setSrc(r.source);
      withMe[withMe.length - 1] = { ...withMe[withMe.length - 1], q: r.quality, f: r.flag, fb: r.feedback || null, b: r.feedback ? r.feedback.better : null };
      const next = [...withMe];
      if (r.next) next.push({ p: 1, t: r.next });
      setLog(next);
      setI(i + 1);
      setOpts(Array.isArray(r.options) ? r.options : []);
      if (r.done) setDone(true);
    } catch { setLog(cur); }
    setTyping(false);
  }
  return (
    <Screen title={`${R.name}과 연습`} noTabs cls="chatv" bodyRef={bd}>
      <div className="chat-wrap">
        <div className="chat-day"><span>첫 활동 연습 · AI가 어르신 역할을 해요</span></div>
        <div className="persona" style={{ marginBottom: 14 }}><span className="pa" style={{ background: col[0], color: col[1] }}>{pinit(R)}</span><div className="grow"><div className="b">{R.name} <span className="mute2" style={{ fontWeight: 500 }}>{R.ageLabel}</span></div><div className="small muted">{R.trait}{R.goal ? ` · ${R.goal}` : ''}</div></div></div>
        <div className="chat">
          {(log || []).map((l, k) => (l.p
            ? <div className="mrow" key={k}><span className="bot" style={{ background: col[0], color: col[1], fontWeight: 800, fontSize: 14, display: 'grid', placeItems: 'center' }}>{pinit(R)}</span><div className="mcol">{k === 0 && <div className="bot-n">{R.name} · AI 연기</div>}<div className="msg ai-m">{l.t}</div></div></div>
            : <div key={k} style={{ display: 'contents' }}><div className="msg me">{l.t}</div>
              {l.fb ? <div className="rp-msg rp-fb" role="status" style={{ marginLeft: 24 }}><b>{l.fb.violation ? `⚠ ${l.fb.message}` : l.fb.message}</b><br />이렇게 바꿔 말해 보세요 · “{l.fb.better}”</div>
                : l.q === 'good' ? <div className="rp-msg rp-ok" role="status" style={{ marginLeft: 24 }}>잘했어요! 안전 규칙을 지키면서 따뜻하게 말했어요.</div> : null}</div>))}
          {typing && <div className="mrow"><span className="bot" style={{ background: col[0], color: col[1], fontWeight: 800, fontSize: 14, display: 'grid', placeItems: 'center' }}>{pinit(R)}</span><div className="mcol"><div className="msg ai-m mute2">입력 중…</div></div></div>}
        </div>
        <RuleNote source={src} />
      </div>
      <div className="chat-dock">
        {done ? (
          <button className="btn" onClick={() => nav(`/edu/rp/${R.id}/result`, { replace: true, state: { log } })}><Spk /> 연습 마치고 피드백 보기</button>
        ) : typing ? null : (
          <>
            {choices.length > 0 && <><div className="mute2" style={{ marginBottom: 8 }}>이렇게 말해 볼까요? 직접 적어도 돼요</div><div className="stack">{choices.map((o, k) => <button key={k} className="opt" style={{ width: '100%', textAlign: 'left' }} onClick={() => say(o.t || o.text || o)}><div className="grow">{o.t || o.text || o}</div></button>)}</div></>}
            <form className="composer" onSubmit={(e) => { e.preventDefault(); say(text); }}>
              <button type="button" className={'mic' + (spch.listening ? ' on' : '')} aria-label="음성 입력" onClick={spch.toggle}><Ic h={I.mic} /></button>
              <input className="in" placeholder="내 말로 대답하기" value={text} onChange={(e) => setText(e.target.value)} aria-label="대답" />
              <button type="submit" aria-label="보내기"><Ic h={I.send} /></button>
            </form>
          </>
        )}
      </div>
    </Screen>
  );
}

/* ---------- 역할극 결과 ---------- */
export function EduRpRes() {
  const { id } = useParams();
  const nav = useNavigate();
  const [edu] = useLoad(() => api('/edu'), []);
  const [res, setRes] = useState(null);
  const log = (window.history.state && window.history.state.usr && window.history.state.usr.log) || null;
  useEffect(() => {
    let on = true;
    (async () => {
      try {
        const r = await post(`/edu/roleplay/${id}/finish`, { log: (log || []).map((l) => ({ who: l.p ? 'them' : 'me', text: l.t, quality: l.q, flag: l.f, better: l.b })) });
        if (on) setRes(r);
      } catch { if (on) setRes({ error: true }); }
    })();
    return () => { on = false; };
  }, [id]); // eslint-disable-line
  const ps = (edu && edu.personas) || [];
  const R = ps.find((x) => String(x.id) === String(id)) || {};
  const bar = (l, v, c) => <div className="sc-row"><span className="l">{l}</span><div className="bar"><i style={{ width: `${v}%`, ...(c ? { background: c } : {}) }} /></div><b>{v}</b></div>;
  if (!res) return <Screen title="연습 피드백" noTabs><AiLoading label="AI가 대화를 살펴보는 중…" /></Screen>;
  if (res.error) return <Screen title="연습 피드백" noTabs><Empty>피드백을 만들지 못했어요</Empty><button className="btn" onClick={() => nav(`/edu/rp/${id}`, { replace: true })}>다시 하기</button></Screen>;
  const fixes = res.fixes || [];
  const good = res.good ? { t: res.good.text } : (log || []).find((l) => !l.p && l.q === 'good');
  return (
    <Screen title="연습 피드백" noTabs>
      <div className="card"><div className="b" style={{ fontSize: 20 }}>{res.safe ? '안전 규칙을 잘 지켰어요' : '안전 규칙에 어긋난 말이 있었어요'}</div><div className="mute2" style={{ marginTop: 4 }}>{R.name} · {R.trait}</div></div>
      <div className="ai" style={{ marginTop: 12 }}>
        <div className="ah"><Spk />소통·공감 피드백<Mod n={6} /></div>
        <div className="sc-row"><span className="l">안전 규칙</span><div className="grow"><span className={'st ' + (res.safe ? 'ok' : 'no')}>{res.safe ? '지킴' : `어긋남${(res.flags || []).length ? ` · ${res.flags.join(', ')}` : ''}`}</span></div></div>
        {bar('소통', Number(res.comm) || 0)}{bar('공감', Number(res.emp) || 0, '#E2557F')}
        <div className="mute2" style={{ marginTop: 8 }}>{res.note || '연습이라 점수는 참고용이에요. 수료나 통과는 없어요.'}</div>
      </div>
      {fixes.length > 0 && <><div className="sec-t">이렇게 바꿔 말해 보세요</div><div className="card">{fixes.map((f, k) => { const bad = f.violation || f.bad || f.flag || f.type === 'safety'; return <div key={k} className={'fix ' + (bad ? '' : 'soft')}><div className="small b" style={{ color: bad ? '#E5484D' : '#A66F00' }}>{bad ? `안전 규칙에 어긋나요${f.flag ? ` · ${f.flag}` : ''}` : '이렇게 말하면 더 따뜻해요'}</div>{(f.was || f.said) && <div className="was">{f.was || f.said}</div>}<div className="now">→ {f.now || f.better || f.text || String(f)}</div></div>; })}</div></>}
      {good && <><div className="sec-t">잘한 말</div><div className="card"><div className="fix" style={{ borderLeftColor: '#1F9D63' }}><div className="now">“{good.t}”</div></div></div></>}
      <div className="btns" style={{ marginTop: 16 }}><button className="btn ghost" onClick={() => nav(`/edu/rp/${id}`, { replace: true })}>다시 해 보기</button><button className="btn" onClick={() => nav('/edu', { replace: true })}>연습 목록으로</button></div>
    </Screen>
  );
}
