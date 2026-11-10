// LLM 추가 A(계약서 7.12): 요청서 빠진 정보 점검 · 안 맞춰지는 공고 고치기 제안 · 음성 비서 "물어보기"
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, RuleNote } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { post } from '../api.js';
import { useSpeechInput } from '../ui/VoiceInput.jsx';
import { speakKo } from '../ui/assist.jsx';

/* ---------- 1. 요청서 빠진 정보 점검 ---------- */
/** 답한 내용을 요청서에 붙인다: detail 은 내용 뒤에, caution 은 주의사항 뒤에. */
export function mergeChecks(d, answers) {
  if (!d || !answers || !answers.length) return d;
  const det = answers.filter((a) => a.target === 'detail').map((a) => `${a.label}: ${a.text}`);
  const cau = answers.filter((a) => a.target !== 'detail').map((a) => `${a.label}: ${a.text}`);
  return {
    ...d,
    detail: [d.detail, ...det].filter(Boolean).join(' · '),
    caution: [d.caution, ...cau].filter(Boolean).join(' · ') || d.caution,
  };
}

/**
 * 올리기 직전 요청서 카드 아래: 빠진 정보 1~2개만 되묻는다(체크리스트는 서버 코드). 건너뛰기 가능.
 * onChange([{key, label, target, text}]) — 부모가 mergeChecks 로 요청서에 붙인다.
 */
export function PostCheck({ draft, elderId, onChange, big }) {
  const key = JSON.stringify([draft && draft.categories, draft && draft.title, draft && draft.detail]);
  const [res, setRes] = useState(null);
  const [ans, setAns] = useState({});
  const [typed, setTyped] = useState({});
  const [skip, setSkip] = useState(false);
  useEffect(() => {
    let on = true;
    setRes(null); setAns({}); setSkip(false);
    if (onChange) onChange([]);
    if (!draft || !(draft.categories || []).length) return undefined;
    post('/ai/post-check', { draft: { categories: draft.categories, title: draft.title, detail: draft.detail, caution: draft.caution, note: draft.note }, elderId: elderId ? Number(elderId) : undefined }, { quiet: true })
      .then((r) => { if (on) setRes(r); }).catch(() => {});
    return () => { on = false; };
  }, [key]); // eslint-disable-line
  const qs = (res && res.questions) || [];
  const pick = (q, text) => {
    const n = { ...ans, [q.key]: text };
    if (!text) delete n[q.key];
    setAns(n);
    onChange && onChange(qs.filter((x) => n[x.key]).map((x) => ({ key: x.key, label: x.label, target: x.target, text: n[x.key] })));
  };
  if (!res || skip) return null;
  if (!qs.length) return <div className="pc-done">✓ 빠진 정보 점검: 필요한 정보가 다 들어 있어요</div>;
  const answered = qs.filter((q) => ans[q.key]).length;
  return (
    <div className="ai amber" style={{ marginTop: 10 }}>
      <div className="ah"><Spk />올리기 전에 {qs.length === 1 ? '하나만' : '두 가지만'} 여쭤볼게요<Mod n={1} /></div>
      {qs.map((q) => (
        <div key={q.key} className="pc-q">
          <div className="q" style={big ? { fontSize: 18 } : undefined}>{q.text}</div>
          <div className="tags">
            {(q.choices || []).map((c) => <button key={c} type="button" className={'chip ' + (ans[q.key] === c ? 'on' : '')} style={big ? { fontSize: 16, padding: '10px 14px' } : undefined} onClick={() => pick(q, ans[q.key] === c ? '' : c)}>{c}</button>)}
          </div>
          <div className="row" style={{ marginTop: 6, gap: 6 }}>
            <input className="in grow" style={{ minHeight: 40 }} placeholder="직접 적기" value={typed[q.key] || ''} onChange={(e) => setTyped({ ...typed, [q.key]: e.target.value })} aria-label={`${q.label} 직접 적기`} />
            <button type="button" className="btn soft sm" style={{ width: 'auto', padding: '0 12px' }} disabled={!(typed[q.key] || '').trim()} onClick={() => pick(q, typed[q.key].trim())}>넣기</button>
          </div>
        </div>
      ))}
      <div className="between" style={{ marginTop: 10 }}>
        <span className="mute2">{answered ? `답한 ${answered}개는 ${qs.some((q) => ans[q.key] && q.target === 'detail') ? '내용' : ''}${qs.some((q) => ans[q.key] && q.target === 'detail') && qs.some((q) => ans[q.key] && q.target !== 'detail') ? '·' : ''}${qs.some((q) => ans[q.key] && q.target !== 'detail') ? '주의사항' : ''}에 붙여 올려요` : '몰라도 괜찮아요'}</span>
        <button type="button" className="au-demo" style={{ margin: 0 }} onClick={() => { setSkip(true); onChange && onChange([]); }}>건너뛰기</button>
      </div>
      <RuleNote source={res.source} />
    </div>
  );
}

/* ---------- 3. 안 맞춰지는 공고 고치기 제안 ---------- */
const ACT_LABEL = { TIME: '시간대 바꾸기', SPLIT: '카테고리 나누기', DATE: '날짜 바꾸기', EDIT: '내용 고치기' };

export function FixTips({ p }) {
  const nav = useNavigate();
  const ft = p && p.fixTips;
  const tips = (ft && ft.tips) || [];
  if (!tips.length) return null;
  const ev = ft.evidence || {};
  const go = (t) => {
    const s = t.suggest || {};
    const q = new URLSearchParams({ tip: t.action });
    if (t.action === 'TIME' && s.startHour != null) { q.set('startHour', s.startHour); q.set('endHour', s.endHour); }
    if (t.action === 'SPLIT' && s.categories) { q.set('cats', s.categories.join(',')); if (s.rest) q.set('rest', s.rest.join(',')); }
    if (t.action === 'DATE' && s.date) q.set('date', s.date);
    nav(`/post/${p.id}/edit?${q}`);
  };
  return (
    <div className="ai amber" style={{ marginTop: 12 }}>
      <div className="ah"><Spk />24시간 동안 요청이 없어요 · 이렇게 바꿔 보세요<Mod n={4} /></div>
      {tips.map((t, i) => (
        <div key={i} className="fix-tip">
          <span className="n">{i + 1}</span>
          <div className="grow">
            <div className="small" style={{ fontWeight: 600 }}>{t.text}</div>
            <button type="button" className="btn soft sm" style={{ width: 'auto', padding: '0 12px', marginTop: 6, minHeight: 36 }} onClick={() => go(t)}>{ACT_LABEL[t.action] || '고치기'} ›</button>
          </div>
        </div>
      ))}
      <div className="fix-ev">근거: 같은 도움의 {p.type === 'NEED' ? '봉사 가능' : '도움 요청'} 공고 {ev.oppositeOpen ?? 0}개 · 시간 맞는 것 {ev.overlapTime ?? 0}개 · 최근 연결 {ev.recentMatched ?? 0}건{ev.bestSlot ? ` · 많은 시간대 ${ev.bestSlot}` : ''}</div>
      <RuleNote source={ft.source} />
    </div>
  );
}

/* ---------- 5. 음성 비서 "물어보기" ---------- */
export function AskBar() {
  const nav = useNavigate();
  return (
    <button className="ask-bar" onClick={() => nav('/ask')} aria-label="물어보기">
      <span className="o"><Ic n="mic" s={20} /></span>
      <span className="grow"><span className="t" style={{ display: 'block' }}>물어보기</span><span className="d">“내 시간 얼마 남았어?” “내일 누가 와?”</span></span>
      <span className="mute2">›</span>
    </button>
  );
}

const EXAMPLES = ['내 시간 얼마 남았어?', '내일 누가 와?', '다음 일정 언제야?', '목요일 거 취소해 줘', '단골 누구야?', '새 알림 있어?'];

export function Ask() {
  const nav = useNavigate();
  const { confirm } = useUi();
  const { role } = useAuth();
  const [log, setLog] = useState([]); // {q, r}
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const askRef = useRef(null);
  const sp = useSpeechInput({ onFinal: (t) => askRef.current && askRef.current(t), onInterim: setText, toastErrors: false });
  const ask = async (t) => {
    t = String(t || '').trim();
    if (!t || busy) return;
    setText(''); setBusy(true);
    try {
      const r = await post('/ai/assistant', { text: t });
      setLog((l) => [...l, { q: t, r }]);
      speakKo(r.speech || r.answer, 0.9);
    } catch { /* 토스트 */ }
    setBusy(false);
  };
  askRef.current = ask;
  const doAction = async (a) => {
    if (a.confirm) {
      const ok = await confirm(a.label, a.kind === 'cancel' ? '일정 화면을 열어요. 취소는 그 화면에서 이유를 고르고 직접 눌러야 돼요.' : '화면을 열어요. 올리기·바꾸기는 그 화면에서 직접 확인해요.', '화면 열기');
      if (!ok) return;
    }
    let l = a.link;
    if (role === 'ORG') l = l.replace(/^\/req\/(\d+)/, '/o/reqs/$1');
    nav(l);
  };
  return (
    <Screen title="물어보기" noTabs>
      <div className="mute2" style={{ marginBottom: 8 }}>말이나 글로 물어보면 내 기록으로 답해 드려요. 취소·요청은 화면을 열어 직접 확인해요.</div>
      {log.map((x, i) => (
        <div key={i}>
          <div className="ask-me">{x.q}</div>
          <div className="ai" style={{ marginTop: 8 }}>
            <div className="ah"><Spk />답변<Mod n={4} /></div>
            <div className="ask-ans">{x.r.answer}</div>
            {(x.r.actions || []).length > 0 && (
              <div className="stack" style={{ marginTop: 10, gap: 6, display: 'flex', flexDirection: 'column' }}>
                {x.r.actions.map((a, j) => <button key={j} className={'btn sm ' + (a.kind === 'cancel' ? 'ghost' : 'soft')} onClick={() => doAction(a)}>{a.label}{a.confirm ? ' (확인 후 열기)' : ' ›'}</button>)}
              </div>
            )}
            <div className="between" style={{ marginTop: 8 }}>
              <button className="easy-btn sm" onClick={() => speakKo(x.r.speech || x.r.answer, 0.9)}>🔊 다시 듣기</button>
              {x.r.executed === false && (x.r.actions || []).some((a) => a.confirm) && <span className="mute2" style={{ fontSize: 12 }}>자동으로 실행하지 않아요</span>}
            </div>
            <RuleNote source={x.r.source} />
          </div>
        </div>
      ))}
      {busy && <div className="ai" style={{ marginTop: 10 }}><div className="ah"><Spk />찾아보는 중…</div><div className="skel" /></div>}
      <div className="center" style={{ marginTop: 18 }}>
        {sp.supported && <button className={'ask-mic' + (sp.listening ? ' on' : '')} onClick={sp.toggle} disabled={busy || sp.transcribing} aria-label={sp.listening ? '다 말했어요' : '눌러서 물어보기'}><Ic n="mic" s={40} /></button>}
        <div className="mute2" style={{ marginTop: 6 }}>{sp.transcribing ? '글자로 바꾸는 중…' : sp.listening ? '듣고 있어요…' : sp.supported ? '누르고 물어보세요' : '아래에 적어 주세요'}</div>
        {sp.listening && sp.finish && <button className="btn soft sm" style={{ marginTop: 8 }} onClick={sp.finish}>다 말했어요</button>}
      </div>
      <div className="ask-q">{EXAMPLES.map((q) => <button key={q} onClick={() => ask(q)} disabled={busy}>{q}</button>)}</div>
      <form className="composer" style={{ marginTop: 12 }} onSubmit={(e) => { e.preventDefault(); ask(text); }}>
        <input className="in" placeholder="예) 내일 누가 와?" value={text} onChange={(e) => setText(e.target.value)} aria-label="물어볼 말" />
        <button type="submit" aria-label="보내기" disabled={busy}><Ic n="send" s={20} /></button>
      </form>
    </Screen>
  );
}
