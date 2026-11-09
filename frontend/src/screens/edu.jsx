import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Loading, Empty, AiLoading, RuleNote, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post, put } from '../api.js';
import { CATS, SHORT, arr } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { speak, useSpeechInput } from './voice.jsx';

const EDU_STEPS = [['규칙 카드', '꼭 알아야 할 규칙 · 5분'], ['상황 퀴즈', '5문제 · 5분'], ['AI 역할극', '가상 상대와 대화 · 15분'], ['수료', '프로필에 교육 수료 배지']];
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
function eduStep(edu, me) {
  if (!edu) return 0;
  if (edu.completed || (me && me.eduDone)) return 5;
  if (edu.quizScore != null && edu.quizScore >= 4) return 3;
  if (edu.cardsDone) return 2;
  if (diagOf(edu)) return 1;
  return 0;
}
const rpRes = (edu, id) => (edu && edu.roleplay && (edu.roleplay[id] || edu.roleplay[String(id)])) || null;

function EduPlan({ edu, diag }) {
  const ps = (edu && edu.personas) || [];
  const order = ps.slice().sort((x, y) => (arr(diag.cats).includes(y.fitCategory) ? 1 : 0) - (arr(diag.cats).includes(x.fitCategory) ? 1 : 0));
  const fit = arr(diag.cats).filter((c) => RULE_FIT[c]).slice(0, 2);
  return (
    <div className="ai">
      <div className="ah"><Spk />AI가 짠 내 교육 · 약 25분<Mod n={6} /></div>
      <div className="small"><b>규칙 카드 {rulesFor(edu).length}장</b>{fit.length ? ` · 맞춤 ${fit.length}장 (${fit.map((c) => SHORT[c]).join(', ')})` : ''}</div>
      <div className="small" style={{ marginTop: 4 }}><b>상황 퀴즈 {((edu && edu.quiz) || []).length || 5}문제</b> · 4개 이상 맞히면 통과</div>
      <div className="small" style={{ marginTop: 4 }}><b>AI 역할극 {ps.length}개</b> · 1명 이상 통과하면 수료</div>
      {order.map((r, i) => <div key={r.id} className="row" style={{ gap: 10, marginTop: 8 }}><span className="avatar" style={{ width: 34, height: 34, fontSize: 13, background: pcol(r, i)[0], color: pcol(r, i)[1] }}>{pinit(r)}</span><div className="grow small"><b>{r.name}</b> <span className="mute2">{r.ageLabel}</span><br /><span className="muted">{r.trait}</span></div></div>)}
      <div className="hint">{diag.who === '어르신' ? '어르신을 돕고 싶다고 해서 어르신 상황을 먼저 두었어요. ' : diag.who === '청년·1인 가구' ? '청년·1인 가구를 돕고 싶다고 해서 젊은 상대 상황을 먼저 두었어요. ' : '누구든 도울 수 있게 어르신과 청년 상황을 섞었어요. '}{diag.exp === '처음이에요' ? '봉사가 처음이라 카드마다 예시를 더 넣었어요.' : '고른 도움 종류에 맞는 상황을 앞에 두었어요.'}</div>
    </div>
  );
}

/* ---------- 첫 활동 교육 개요 ---------- */
export function Edu() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast } = useUi();
  const [edu, reload] = useLoad(() => api('/edu'), []);
  const [diag, setDiag] = useState(null);
  const [planning, setPlanning] = useState(false);
  if (edu === undefined) return <Screen title="첫 활동 교육" noTabs><Loading /></Screen>;
  const d0 = diagOf(edu);
  const d = diag || d0 || { cats: arr(me && me.categories).slice(0, 3), who: '상관없어요', exp: '조금 있어요' };
  const s = eduStep(edu, me);
  const planned = !!d0;
  const stepAct = ['/edu/card', '/edu/quiz', '/edu/rp', '/edu/cert'];
  const chip = (on, f, t) => <button key={t} className={'chip ' + (on ? 'on' : '')} onClick={f}>{t}</button>;
  return (
    <Screen title="첫 활동 교육" noTabs>
      <div className="b" style={{ fontSize: 22, letterSpacing: '-.6px', lineHeight: 1.35 }}>첫 도움 전에<br />딱 한 번, 25분이면 돼요</div>
      <p className="su-p" style={{ margin: '8px 0 14px' }}>규칙을 보고, 퀴즈를 풀고, AI가 연기하는 이웃과 실제처럼 대화해 봐요. 나이와 상관없이 도움을 처음 주는 모든 분이 받아요.</p>
      <div className="card">{EDU_STEPS.map(([t, sub], i) => {
        const ok = planned && i + 1 < s, on = planned && i + 1 === Math.min(s, 4);
        return <div key={t} className={'estep ' + (ok ? 'ok' : on ? 'on' : '')}><span className="no">{ok ? <Ic n="check" s={16} /> : i + 1}</span><div className="grow"><div className="b">{t}</div><div className="mute2">{sub}</div></div>{ok ? <span className="st ok">완료</span> : on ? <span className="st go">지금</span> : null}</div>;
      })}</div>
      {planned && !planning ? (
        <>
          <div style={{ marginTop: 14 }}><EduPlan edu={edu} diag={d} /></div>
          <button className="btn" style={{ marginTop: 14 }} onClick={() => nav(stepAct[Math.min(s, 4) - 1])}>{['규칙 카드 보기', '퀴즈 풀기', '역할극 하기', '수료증 보기'][Math.min(s, 4) - 1]}</button>
          {s >= 2 && s < 5 && <button className="btn ghost sm" style={{ marginTop: 8 }} onClick={() => nav('/edu/card')}>규칙 카드 다시 보기</button>}
        </>
      ) : (
        <>
          <div className="sec-t">나에게 맞게 짜기 (3문항)</div>
          <div className="card">
            <div className="b">어떤 도움을 주고 싶으세요?</div><div className="mute2">프로필에서 불러왔어요. 여러 개 골라도 돼요</div>
            <div className="tags" style={{ marginTop: 10 }}>{CATS.map((c) => chip(arr(d.cats).includes(c.k), () => setDiag({ ...d, cats: arr(d.cats).includes(c.k) ? d.cats.filter((x) => x !== c.k) : [...arr(d.cats), c.k] }), SHORT[c.k]))}</div>
            <div className="b" style={{ marginTop: 18 }}>주로 누구를 돕고 싶으세요?</div>
            <div className="tags" style={{ marginTop: 10 }}>{['어르신', '청년·1인 가구', '상관없어요'].map((v) => chip(d.who === v, () => setDiag({ ...d, who: v }), v))}</div>
            <div className="b" style={{ marginTop: 18 }}>봉사해 본 적 있으세요?</div>
            <div className="tags" style={{ marginTop: 10 }}>{['처음이에요', '조금 있어요', '많아요'].map((v) => chip(d.exp === v, () => setDiag({ ...d, exp: v }), v))}</div>
          </div>
          {planning && <div style={{ marginTop: 14 }}><AiLoading label="AI가 내 교육을 짜는 중…" /></div>}
          <button className="btn" style={{ marginTop: 14 }} disabled={planning} onClick={async () => {
            if (!arr(d.cats).length) return toast('도움 종류를 하나 이상 골라 주세요');
            setPlanning(true);
            try { await put('/edu/diag', { cats: d.cats, who: d.who, exp: d.exp }); await reload(); toast('맞춤 교육이 준비됐어요'); } catch { /* 토스트 */ }
            setPlanning(false); setDiag(null);
          }}><Spk /> AI로 내 교육 짜기</button>
          <div className="notice" style={{ marginTop: 12 }}>글씨가 작으면 마이 › 큰 글씨로 보기를 켜세요. 역할극은 보기를 고르기만 해도 돼요.</div>
        </>
      )}
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
      <div className="stepl"><span>{i + 1} / {rs.length}</span><span>{r.fit ? '맞춤 카드' : '필수 카드'}</span></div>
      <div className="rcard" style={{ marginTop: 14 }}>
        <div className="big-ic" style={{ background: r.c[0], color: r.c[1] }}><Ic n={r.ic} s={32} /></div>
        <h3>{String(r.t).split('\n').map((x, k) => <span key={k}>{x}{k === 0 && String(r.t).includes('\n') ? <br /> : null}</span>)}</h3>
        <p>{r.p}</p>
        {r.ex && <div className="ex" style={{ marginTop: 20 }}>{r.ex}</div>}
      </div>
      <button className="btn soft sm" style={{ marginTop: 12 }} onClick={() => speak(`${String(r.t).replace('\n', ' ')}. ${r.p}. ${r.ex}`, { force: true })}><Ic n="mic" s={18} /> 소리로 읽어 주기</button>
      <div className="btns" style={{ marginTop: 10 }}>
        <button className="btn ghost" disabled={i === 0} style={i === 0 ? { opacity: 0.4 } : null} onClick={() => setI(i - 1)}>이전</button>
        <button className="btn" disabled={busy} onClick={() => (i < rs.length - 1 ? setI(i + 1) : run(async () => { if (!edu.cardsDone) await post('/edu/cards-done'); nav('/edu/quiz', { replace: true }); }))}>{i === rs.length - 1 ? '퀴즈 풀러 가기' : '다음'}</button>
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
    if (score >= 4) {
      sheet(() => <><h2>{score}/{QZ.length} 맞혔어요</h2><p className="muted">이제 AI가 연기하는 이웃과 대화해 볼 차례예요. 대화 중 숨은 시험 상황이 하나씩 있어요.</p><Wrong /><button className="btn" style={{ marginTop: 16 }} onClick={() => { closeSheet(); nav('/edu/rp', { replace: true }); }}>역할극 하러 가기</button></>);
    } else {
      sheet(() => <><h2>{score}/{QZ.length} 맞혔어요</h2><p className="muted">4개 이상 맞혀야 다음으로 갈 수 있어요. 틀린 문제의 카드를 다시 보고 풀어 볼까요?</p><Wrong /><button className="btn" style={{ marginTop: 16 }} onClick={() => { closeSheet(); setI(0); setAnswers([]); setPicked(null); }}>다시 풀기</button><button className="btn ghost" style={{ marginTop: 8 }} onClick={() => { closeSheet(); nav('/edu/card'); }}>규칙 카드 다시 보기</button></>);
    }
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

/* ---------- AI 역할극 목록 ---------- */
export function EduRpList() {
  const nav = useNavigate();
  const { refresh } = useAuth();
  const { toast } = useUi();
  const [edu] = useLoad(() => api('/edu'), []);
  const [busy, run] = useBusy();
  if (edu === undefined) return <Screen title="AI 역할극" noTabs><Loading /></Screen>;
  const ps = (edu && edu.personas) || [];
  const passed = ps.filter((p) => { const r = rpRes(edu, p.id); return r && (r.passed || r.pass); }).length;
  const can = passed >= 1 && edu.cardsDone && (edu.quizScore ?? 0) >= 4;
  return (
    <Screen title="AI 역할극" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>AI가 실제 이웃처럼 말해요. 대화 중에 <b>숨은 시험 상황</b>이 하나씩 있어요. 끝나면 AI가 말투, 공감, 안전 수칙을 채점해요.</p>
      {ps.map((p, i) => {
        const r = rpRes(edu, p.id);
        const st = r ? ((r.passed || r.pass) ? <span className="st ok">통과</span> : <span className="st no">다시 하기</span>) : <span className="st end">시작 전</span>;
        return (
          <div key={p.id} className="card click" style={{ marginTop: 10 }} role="button" tabIndex={0} onClick={() => nav(`/edu/rp/${p.id}`)}>
            <div className="persona" style={{ background: 'none', padding: 0 }}><span className="pa" style={{ background: pcol(p, i)[0], color: pcol(p, i)[1] }}>{pinit(p)}</span><div className="grow"><div className="b">{p.name} <span className="mute2" style={{ fontWeight: 500 }}>{p.ageLabel}</span></div><div className="small muted">{p.trait}</div></div>{st}</div>
            {p.goal && <div className="hint" style={{ marginTop: 12, background: 'var(--fill)', color: 'var(--sub)' }}>목표 · {p.goal}</div>}
            {r && <div className="mute2" style={{ marginTop: 8 }}>소통 {r.comm} · 공감 {r.emp} · 안전 {r.safe ? '통과' : '위반'}</div>}
          </div>
        );
      })}
      <button className="btn" style={{ marginTop: 16, ...(can ? {} : { opacity: 0.45 }) }} disabled={busy} onClick={() => run(async () => {
        if (!can) return toast(!edu.cardsDone ? '규칙 카드를 먼저 봐 주세요' : (edu.quizScore ?? 0) < 4 ? '퀴즈를 4개 이상 맞혀 주세요' : '역할극을 1개 이상 통과해 주세요');
        await post('/edu/complete'); await refresh(); toast('첫 활동 교육을 수료했어요'); nav('/edu/cert', { replace: true });
      })}>{can ? '수료하기' : '역할극 1개 이상 통과하면 수료할 수 있어요'}</button>
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
  const turn = turns[i];
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
      withMe[withMe.length - 1] = { ...withMe[withMe.length - 1], q: r.quality, f: r.flag };
      const next = [...withMe];
      if (r.next) next.push({ p: 1, t: r.next });
      setLog(next);
      setI(i + 1);
      if (r.done || i + 1 >= (turns.length || 3)) setDone(true);
    } catch { setLog(cur); }
    setTyping(false);
  }
  return (
    <Screen title={`${R.name}과 연습`} noTabs cls="chatv" bodyRef={bd}>
      <div className="chat-wrap">
        <div className="chat-day"><span>AI 역할극 · {R.trait}</span></div>
        <div className="persona" style={{ marginBottom: 14 }}><span className="pa" style={{ background: col[0], color: col[1] }}>{pinit(R)}</span><div className="grow"><div className="b">{R.name} <span className="mute2" style={{ fontWeight: 500 }}>{R.ageLabel}</span></div><div className="small muted">목표 · {R.goal}</div></div></div>
        <div className="chat">
          {(log || []).map((l, k) => (l.p
            ? <div className="mrow" key={k}><span className="bot" style={{ background: col[0], color: col[1], fontWeight: 800, fontSize: 14, display: 'grid', placeItems: 'center' }}>{pinit(R)}</span><div className="mcol">{k === 0 && <div className="bot-n">{R.name} · AI 연기</div>}<div className="msg ai-m">{l.t}</div></div></div>
            : <div className="msg me" key={k}>{l.t}{l.f && <div style={{ fontSize: 12, opacity: 0.85, marginTop: 4 }}>⚠ {l.f}</div>}</div>))}
          {typing && <div className="mrow"><span className="bot" style={{ background: col[0], color: col[1], fontWeight: 800, fontSize: 14, display: 'grid', placeItems: 'center' }}>{pinit(R)}</span><div className="mcol"><div className="msg ai-m mute2">입력 중…</div></div></div>}
        </div>
        <RuleNote source={src} />
      </div>
      <div className="chat-dock">
        {done ? (
          <button className="btn" onClick={() => nav(`/edu/rp/${R.id}/result`, { replace: true, state: { log } })}><Spk /> 대화 끝내고 채점받기</button>
        ) : typing ? null : (
          <>
            {turn && (turn.o || turn.options) && <><div className="mute2" style={{ marginBottom: 8 }}>이렇게 말해 볼까요? 직접 적어도 돼요</div><div className="stack">{(turn.o || turn.options).map((o, k) => <button key={k} className="opt" style={{ width: '100%', textAlign: 'left' }} onClick={() => say(o.t || o.text || o)}><div className="grow">{o.t || o.text || o}</div></button>)}</div></>}
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
        const r = await post(`/edu/roleplay/${id}/finish`, { log: (log || []).map((l) => ({ who: l.p ? 'them' : 'me', text: l.t, quality: l.q, flag: l.f })) });
        if (on) setRes(r);
      } catch { if (on) setRes({ error: true }); }
    })();
    return () => { on = false; };
  }, [id]); // eslint-disable-line
  const ps = (edu && edu.personas) || [];
  const R = ps.find((x) => String(x.id) === String(id)) || {};
  const bar = (l, v, c) => <div className="sc-row"><span className="l">{l}</span><div className="bar"><i style={{ width: `${v}%`, ...(c ? { background: c } : {}) }} /></div><b>{v}</b></div>;
  if (!res) return <Screen title="역할극 결과" noTabs><AiLoading label="AI가 대화를 채점하는 중…" /></Screen>;
  if (res.error) return <Screen title="역할극 결과" noTabs><Empty>채점하지 못했어요</Empty><button className="btn" onClick={() => nav(`/edu/rp/${id}`, { replace: true })}>다시 하기</button></Screen>;
  const pass = res.pass ?? res.passed;
  const fixes = res.fixes || [];
  const good = res.good ? { t: res.good.text } : (log || []).find((l) => !l.p && l.q === 'good');
  return (
    <Screen title="역할극 결과" noTabs>
      <div className="cert-c" style={{ padding: '22px 18px', ...(pass ? {} : { background: 'linear-gradient(180deg,#FFF3F3 0%,#fff 100%)', borderColor: '#F6D5D5' }) }}><div className="b" style={{ fontSize: 20 }}>{pass ? '통과했어요' : '한 번 더 해 볼까요?'}</div><div className="mute2" style={{ marginTop: 4 }}>{R.name} · {R.trait}</div></div>
      <div className="ai" style={{ marginTop: 12 }}>
        <div className="ah"><Spk />AI 채점<Mod n={6} /></div>
        <div className="sc-row"><span className="l">안전 수칙</span><div className="grow"><span className={'st ' + (res.safe ? 'ok' : 'no')}>{res.safe ? '통과' : '위반'}</span></div></div>
        {bar('소통', Number(res.comm) || 0)}{bar('공감', Number(res.emp) || 0, '#E2557F')}
        <div className="mute2" style={{ marginTop: 8 }}>안전 수칙은 점수가 아니라 통과/재시도로만 봐요. 소통·공감은 60점 이상이면 통과예요.</div>
        <RuleNote source={res.source} />
      </div>
      {fixes.length > 0 && <><div className="sec-t">바꿔 말해 보세요</div><div className="card">{fixes.map((f, k) => { const bad = f.bad || f.flag || f.type === 'safety'; return <div key={k} className={'fix ' + (bad ? '' : 'soft')}><div className="small b" style={{ color: bad ? '#E5484D' : '#A66F00' }}>{bad ? `안전 수칙 · ${f.flag || ''}` : '이렇게 말하면 더 좋아요'}</div>{(f.was || f.said) && <div className="was">{f.was || f.said}</div>}<div className="now">→ {f.now || f.better || f.text || String(f)}</div></div>; })}</div></>}
      {good && <><div className="sec-t">잘한 말</div><div className="card"><div className="fix" style={{ borderLeftColor: '#1F9D63' }}><div className="now">“{good.t}”</div></div></div></>}
      <div className="btns" style={{ marginTop: 16 }}>{pass ? <button className="btn" onClick={() => nav('/edu/rp', { replace: true })}>다음 상황으로</button> : <><button className="btn ghost" onClick={() => nav('/edu/rp', { replace: true })}>목록으로</button><button className="btn" onClick={() => nav(`/edu/rp/${id}`, { replace: true })}>다시 하기</button></>}</div>
    </Screen>
  );
}

/* ---------- 수료증 ---------- */
export function EduCert() {
  const nav = useNavigate();
  const { me } = useAuth();
  const [edu] = useLoad(() => api('/edu'), []);
  if (edu === undefined) return <Screen title="교육 수료" noTabs><Loading /></Screen>;
  const rs = Object.values((edu && edu.roleplay) || {});
  const avg = (k) => (rs.length ? Math.round(rs.reduce((a, b) => a + (Number(b[k]) || 0), 0) / rs.length) : '-');
  const done = (edu && edu.completed) || (me && me.eduDone);
  return (
    <Screen title="교육 수료" noTabs>
      {!done && <div className="notice" style={{ marginBottom: 12 }}>아직 수료 전이에요. 남은 단계를 마쳐 주세요. <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700 }} onClick={() => nav('/edu', { replace: true })}>교육으로 ›</a></div>}
      <div className="cert-c">
        <div className="medal"><Ic n="cap" s={40} /></div>
        <div className="mute2">시간 적금 첫 활동 교육</div>
        <div className="b" style={{ fontSize: 24, letterSpacing: '-.6px', marginTop: 4 }}>{me && me.name} 님, {done ? '수료를 축하해요' : '거의 다 왔어요'}</div>
        <div className="tags" style={{ justifyContent: 'center', marginTop: 12 }}>{done && <span className="badge trust">✓ 교육 수료</span>}<span className="badge">역할극 {rs.filter((r) => r.passed || r.pass).length}개 통과</span></div>
        <div className="kpis" style={{ marginTop: 18, textAlign: 'left' }}><div><b>{edu.quizScore ?? '-'}/{((edu && edu.quiz) || []).length || 5}</b><span>퀴즈</span></div><div><b>{avg('comm')}</b><span>평균 소통</span></div><div><b>{avg('emp')}</b><span>평균 공감</span></div></div>
      </div>
      <div className="ai" style={{ marginTop: 12 }}><div className="ah"><Spk />AI 총평<Mod n={6} /></div><div className="small">상대의 말을 받아 주며 대화를 이어 가는 연습을 했어요. 연락처나 선물을 거절할 때는 <b>단골 요청</b>을 대안으로 알려 주세요. 첫 활동에서는 도착하자마자 QR부터 찍는 것만 기억해 주세요.</div></div>
      <div className="notice" style={{ marginTop: 12 }}>새 도움 종류를 프로필에 추가하면 그 종류의 역할극 1개만 더 하면 돼요. 신고 처리 결과에 따라 운영자가 재교육을 요청할 수 있어요.</div>
      <button className="btn" style={{ marginTop: 14 }} onClick={() => nav('/board?type=NEED', { replace: true })}>도움 줄 공고 보러 가기</button>
    </Screen>
  );
}
