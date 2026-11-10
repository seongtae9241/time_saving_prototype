import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, CatIc, RuleNote, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { CATS, SHORT, AREAS, fmt, hh, dlabel, lvName, lvWeight, addDays } from '../lib/format.js';
import { PubCards } from './ai.jsx';
import { useSpeechInput, SttServerToggle } from '../ui/VoiceInput.jsx';
import { PostCheck, mergeChecks } from './assist.jsx'; // LLM 추가 A(7.12) 빠진 정보 점검

/* ---------- 음성 도구: 받아쓰기는 공용 ui/VoiceInput.jsx(브라우저 → 서버 음성 인식) · 읽어 주기(speechSynthesis) ---------- */
export { useSpeechInput, speechSupported } from '../ui/VoiceInput.jsx';

export function getTts() { try { return localStorage.getItem('ts-tts') !== '0'; } catch { return true; } }
export function setTts(v) { try { localStorage.setItem('ts-tts', v ? '1' : '0'); } catch { /* 무시 */ } }
export function stopSpeak() { try { window.speechSynthesis && window.speechSynthesis.cancel(); } catch { /* 무시 */ } }
/** 한국어로 천천히 읽어 준다. 끝나면 resolve */
export function speak(text, { rate = 0.9, force = false } = {}) {
  return new Promise((resolve) => {
    if (!text || (!force && !getTts()) || !('speechSynthesis' in window)) return resolve(false);
    try {
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(String(text).replace(/<[^>]+>/g, ''));
      u.lang = 'ko-KR'; u.rate = rate;
      const v = window.speechSynthesis.getVoices().find((x) => /ko/i.test(x.lang));
      if (v) u.voice = v;
      let done = false;
      const fin = () => { if (!done) { done = true; resolve(true); } };
      u.onend = fin; u.onerror = fin;
      setTimeout(fin, Math.min(30000, 1500 + String(text).length * 180));
      window.speechSynthesis.speak(u);
    } catch { resolve(false); }
  });
}

/* ---------- 말로 요청하기 (voice_req, /voice) — 추가 요구(사용자 요청) ---------- */
const CONF_KEYS = { date: '날짜', time: '시간', area: '장소', category: '도움 종류' };
const confOf = (res, k) => (res && res.confidence && res.confidence[k]) || 'high';

function Wave({ on }) { return <div className={'vr-wave' + (on ? ' on' : '')} aria-hidden="true"><i /><i /><i /><i /><i /></div>; }

export function VoiceReq() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  const { me, refresh } = useAuth();
  const { toast } = useUi();
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  const [tts, setTtsState] = useState(getTts());
  const [msgs, setMsgs] = useState([]); // {role:'user'|'assistant', text}
  const [phase, setPhase] = useState('idle'); // idle | thinking | ask | card | block | emergency | posted
  const [res, setRes] = useState(null);
  const [draft, setDraft] = useState(null);
  const [asks, setAsks] = useState(0);
  const [typed, setTyped] = useState('');
  const [editing, setEditing] = useState(false);
  const [chk, setChk] = useState([]); // 빠진 정보 점검에서 답한 것(올릴 때 내용·주의사항에 붙임)
  const [busy, run] = useBusy();
  const sendRef = useRef(null);
  const sp2 = useSpeechInput({ onFinal: (t) => sendRef.current && sendRef.current(t), onInterim: setTyped, toastErrors: false });
  const who = elderId ? `${elder ? elder.alias + ' 님' : '대상'}` : null;

  const say = useCallback(async (t) => { if (tts) await speak(t); }, [tts]);

  const send = useCallback(async (text) => {
    const t = String(text || '').trim();
    if (!t) return;
    setTyped('');
    const next = [...msgs, { role: 'user', text: t }];
    setMsgs(next);
    setPhase('thinking');
    let r;
    try {
      r = await post('/ai/post-chat', { type: 'NEED', messages: next, inputMode: 'voice', forElderId: elderId ? Number(elderId) : undefined });
    } catch { setPhase(msgs.length ? 'ask' : 'idle'); return; }
    setRes(r);
    const withReply = r.reply ? [...next, { role: 'assistant', text: r.reply }] : next;
    setMsgs(withReply);
    if ((r.safety && r.safety.emergency) || r.intent === 'EMERGENCY') {
      setPhase('emergency');
      say('위급한 상황이면 지금 바로 119에 전화하세요. 화면의 빨간 버튼을 누르면 전화가 걸려요.');
      return;
    }
    if (r.filter && r.filter.level === 'BLOCK') {
      setPhase('block');
      say(`이 요청은 이웃끼리 할 수 없어요. ${r.filter.reason || ''} 대신 받을 수 있는 공공 서비스를 보여 드릴게요.`);
      return;
    }
    if (r.done && r.draft) {
      setDraft({ ...r.draft, categories: r.draft.categories && r.draft.categories.length ? r.draft.categories : [] });
      setPhase('card'); setEditing(false);
      const mids = Object.keys(CONF_KEYS).filter((k) => confOf(r, k) === 'medium').map((k) => CONF_KEYS[k]);
      const sum = r.draft.summarySpeech || `${dlabel(r.draft.date)} ${hh(r.draft.startHour)}부터 ${r.draft.estHours || ''}시간, ${(r.draft.categories || []).join(', ')} 요청서를 만들었어요. 올릴까요?`;
      say(sum + (mids.length ? ` ${mids.join(', ')}이 맞는지 한 번 더 봐 주세요.` : ''));
      return;
    }
    setAsks((a) => a + 1);
    setPhase('ask');
    if (r.reply) {
      await say(r.reply);
      if (tts && sp2.mode === 'browser' && !r.intent?.match?.(/QUESTION|REGULAR/)) sp2.start();
    }
  }, [msgs, elderId, say, tts, sp2]);
  sendRef.current = send;

  const reset = () => { stopSpeak(); sp2.stop(); setMsgs([]); setRes(null); setDraft(null); setAsks(0); setPhase('idle'); setTyped(''); setEditing(false); setChk([]); };

  const publish = () => run(async () => {
    const d = mergeChecks(draft, chk);
    if (!d.date || d.startHour == null || d.endHour == null) return toast('날짜와 시간을 정해 주세요');
    if (!d.categories || !d.categories.length) return toast('도움 종류를 골라 주세요');
    const body = {
      type: 'NEED', date: d.date, startHour: Number(d.startHour), endHour: Number(d.endHour), area: d.area || (elder ? elder.dong : me && me.area),
      categories: d.categories, estHours: Number(d.estHours) || (Number(d.endHour) - Number(d.startHour)), title: d.title || `${d.categories[0]} 도움`, detail: d.detail || '', note: d.caution ? `방문 시 주의: ${d.caution}` : (d.note || ''), caution: d.caution || undefined,
      repeat: d.repeat || undefined, elderId: elderId ? Number(elderId) : undefined, inputMode: 'voice',
    };
    const p = await post('/posts', body);
    const first = Array.isArray(p) ? p[0] : p;
    setPhase('posted');
    refresh();
    say('요청서를 올렸어요. 도와줄 이웃이 나타나면 알려 드릴게요.');
    toast(who ? `${who} 대신 공고를 올렸어요` : '공고를 올렸어요');
    setRes((x) => ({ ...x, postedId: first && first.id }));
  });

  // 되묻기가 3번을 넘으면 말 대신 큰 선택 버튼
  const missing = (res && res.missing) || [];
  const lows = res ? Object.keys(CONF_KEYS).filter((k) => confOf(res, k) === 'low') : [];
  const need = (k) => missing.some((m) => String(m).toLowerCase().includes(k)) || lows.includes(k);
  const choiceMode = phase === 'ask' && asks > 3;
  const lastReply = res && res.reply;
  const intent = res && res.intent;
  const diff = res && res.difficulty;
  const dWeight = diff ? lvWeight(diff.level, diff.weight) : 1;
  const dHours = draft ? Number(draft.estHours || (Number(draft.endHour) - Number(draft.startHour)) || 1) : 0;
  const deduct = diff && diff.estEarn != null ? diff.estEarn : Math.round(dHours * dWeight * 2) / 2;

  const field = (k, label, node) => {
    const c = confOf(res, k);
    return <div className={'vr-f ' + (c === 'medium' ? 'mid' : c === 'low' ? 'low' : '')}><span className="vr-l">{label}{c === 'medium' && <em> 확인해 주세요</em>}{c === 'low' && <em> 다시 말해 주세요</em>}</span><span className="vr-v">{node}</span></div>;
  };

  return (
    <Screen title={who ? `${who} 대신 말로 요청` : '말로 요청하기'} noTabs cls="vr">
      <div className="between" style={{ marginBottom: 8 }}>
        <span className="mute2">{who ? '어르신께 들은 내용을 말하거나 적어 주세요' : '말씀하시면 요청서로 정리해 드려요'}{!who && <><br /><a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700 }} onClick={() => nav('/ask')}>궁금한 건 물어보기 ›</a></>}</span>
        <span className="row" style={{ gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end' }}><SttServerToggle compact /><button className={'vr-tts' + (tts ? ' on' : '')} onClick={() => { const v = !tts; setTtsState(v); setTts(v); if (!v) stopSpeak(); }} aria-pressed={tts}>{tts ? '🔊 소리로 읽어 주기 켜짐' : '🔇 소리 꺼짐'}</button></span>
      </div>

      {msgs.length > 0 && (
        <div className="vr-log">
          {msgs.map((m, i) => (
            <div key={i} className={'vr-m ' + (m.role === 'user' ? 'me' : 'bt')}>
              {m.role === 'user' ? (
                <>
                  <div className="vr-bub">{m.text}</div>
                  {i === msgs.length - 1 || (i === msgs.length - 2 && msgs[msgs.length - 1].role === 'assistant') ? (
                    <button className="vr-fix" disabled={phase === 'thinking'} onClick={() => { setTyped(m.text); setMsgs(msgs.slice(0, i)); setPhase(i ? 'ask' : 'idle'); }}>글자 고쳐서 다시 보내기</button>
                  ) : null}
                </>
              ) : <div className="vr-bub"><Spk />{m.text}</div>}
            </div>
          ))}
        </div>
      )}

      {phase === 'thinking' && <div className="ai" style={{ marginTop: 12 }}><div className="ah"><Spk />요청서로 정리하는 중…</div><div className="skel" /><div className="skel" style={{ width: '70%' }} /></div>}

      {phase === 'emergency' && (
        <div className="vr-emg">
          <div className="vr-emg-t">위급한 상황인가요?</div>
          <p>쓰러지셨거나 숨쉬기 힘들면 지금 바로 119에 전화하세요. 요청서 만들기는 잠시 멈췄어요.</p>
          <a className="btn warn vr-big" href="tel:119">📞 119 전화하기</a>
          <div className="mute2" style={{ marginTop: 10 }}>혼자 사시면 응급안전안심서비스(주민센터 신청)도 있어요.</div>
          <button className="btn ghost vr-big" style={{ marginTop: 10 }} onClick={reset}>괜찮아요, 처음으로</button>
        </div>
      )}

      {phase === 'block' && res && (
        <div className="ai warn" style={{ marginTop: 12 }}>
          <div className="ah"><Spk />이 요청은 올릴 수 없어요<Mod n={2} /></div>
          <div className="vr-say">{res.filter.reason}</div>
          {(res.filter.alternatives || []).length > 0 && <><div className="vr-say" style={{ marginTop: 8 }}>대신 이런 공공 서비스가 있어요</div><PubCards big list={res.filter.alternatives} /></>}
          <div className="vr-say" style={{ marginTop: 10, fontSize: 16 }}>말벗, 장보기, 병원 동행, 휴대폰 도움처럼 이웃이 할 수 있는 일은 요청할 수 있어요.</div>
          <button className="btn vr-big" style={{ marginTop: 12 }} onClick={reset}>다시 말하기</button>
          <RuleNote source={res.source} />
        </div>
      )}

      {phase === 'ask' && intent === 'QUESTION' && <button className="btn soft vr-big" style={{ marginTop: 10 }} onClick={() => nav(`/public?q=${encodeURIComponent(msgs.filter((m) => m.role === 'user').map((m) => m.text).join(' '))}`)}>공공 서비스 안내 보러 가기</button>}
      {phase === 'ask' && intent === 'REGULAR_REQUEST' && <button className="btn soft vr-big" style={{ marginTop: 10 }} onClick={() => nav(elderId ? `/regular?elder=${elderId}` : '/regular')}>단골에게 바로 요청하기</button>}

      {choiceMode && (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="vr-say">말 대신 눌러서 골라 주세요</div>
          {(need('date') || !missing.length) && <><div className="b" style={{ marginTop: 12 }}>날짜</div><div className="vr-choices">{Array.from({ length: 7 }, (_, i) => addDays(i + 1)).map((d) => <button key={d} onClick={() => send(`날짜는 ${dlabel(d)}`)}>{dlabel(d)}</button>)}</div></>}
          {(need('time') || need('hour') || !missing.length) && <><div className="b" style={{ marginTop: 12 }}>시간</div><div className="vr-choices">{[['오전 (9시~12시)', '오전 9시부터 12시까지'], ['오후 (1시~4시)', '오후 1시부터 4시까지'], ['오후 늦게 (3시~6시)', '오후 3시부터 6시까지']].map(([l, v]) => <button key={l} onClick={() => send(v)}>{l}</button>)}</div></>}
          {(need('categor') || !missing.length) && <><div className="b" style={{ marginTop: 12 }}>도움 종류</div><div className="vr-choices">{CATS.map((c) => <button key={c.k} onClick={() => send(`${c.k} 도움이 필요해요`)}><CatIc k={c.k} size={30} /> {SHORT[c.k]}</button>)}</div></>}
          {need('area') && <><div className="b" style={{ marginTop: 12 }}>장소</div><div className="vr-choices">{AREAS.map((a) => <button key={a} onClick={() => send(`장소는 ${a}`)}>{a}</button>)}</div></>}
        </div>
      )}

      {phase === 'card' && draft && (
        <div className="vr-card">
          <div className="ah"><Spk />요청서가 만들어졌어요<Mod n={1} /></div>
          {!editing ? (
            <>
              <div className="vr-title">{draft.title}</div>
              {field('date', '날짜', dlabel(draft.date))}
              {field('time', '시간', `${hh(draft.startHour)} ~ ${hh(draft.endHour)}${draft.estHours ? ` (${draft.estHours}시간)` : ''}`)}
              {field('area', '장소', draft.area || (elder ? elder.dong : me && me.area))}
              {field('category', '도움 종류', (draft.categories || []).join(', ') || '고르지 않음')}
              {mergeChecks(draft, chk).detail && <div className="vr-f"><span className="vr-l">내용</span><span className="vr-v">{mergeChecks(draft, chk).detail}</span></div>}
              {mergeChecks(draft, chk).caution && <div className="vr-f"><span className="vr-l">방문 시 주의사항</span><span className="vr-v">{mergeChecks(draft, chk).caution}</span></div>}
              <div className="vr-f"><span className="vr-l">난이도</span><span className="vr-v">{diff ? `${lvName(diff.level)} ${dWeight.toFixed(1)}` : '-'}</span></div>
              <div className="vr-f"><span className="vr-l">{who ? '대상 차감' : '예상 차감 시간'}</span><span className="vr-v">{who ? '없음 (기관 대상)' : `${fmt(deduct)}시간 (확정되면 보류, 끝나면 차감)`}</span></div>
              {diff && diff.reason && <div className="mute2" style={{ marginTop: 6 }}>근거: {diff.reason}</div>}
              {res.filter && res.filter.level === 'CAUTION' && <div className="notice" style={{ marginTop: 10 }}>주의: {res.filter.reason}</div>}
              <RuleNote source={res.source} />
            </>
          ) : (
            <DraftEditor draft={draft} setDraft={setDraft} />
          )}
          {!editing && <PostCheck draft={draft} elderId={elderId} onChange={setChk} big />}
          <div className="stack" style={{ marginTop: 16 }}>
            <button className="btn vr-big" disabled={busy} onClick={publish}>{busy ? '올리는 중…' : '이대로 올리기'}</button>
            <div className="btns">
              <button className="btn ghost vr-big" onClick={() => { setEditing(!editing); if (!editing) say('고칠 칸을 직접 고치거나, 아래 마이크로 고칠 내용을 말씀해 주세요.'); }}>{editing ? '고친 내용 보기' : '고칠래요'}</button>
              <button className="btn ghost vr-big" onClick={reset}>다시 말하기</button>
            </div>
          </div>
          <div className="mute2 center" style={{ marginTop: 8 }}>누르기 전에는 올라가지 않아요</div>
        </div>
      )}

      {phase === 'posted' && (
        <div className="cert-c" style={{ marginTop: 14 }}>
          <div className="done-ic" style={{ margin: '0 auto 12px' }}>✓</div>
          <div className="vr-title" style={{ textAlign: 'center' }}>요청서를 올렸어요</div>
          <p className="vr-say">도와줄 이웃이 요청하면 알림으로 알려 드려요.</p>
          {res && res.postedId && <button className="btn vr-big" onClick={() => nav(`/post/${res.postedId}`, { replace: true })}>올린 공고 보기</button>}
          <button className="btn ghost vr-big" style={{ marginTop: 8 }} onClick={() => nav(-1)}>처음 화면으로</button>
        </div>
      )}

      {!['emergency', 'block', 'posted'].includes(phase) && (
        <div className="vr-dock">
          {phase === 'idle' && <div className="vr-q">{who ? `${who}께 어떤 도움이 필요한가요?` : '어떤 도움이 필요하세요?'}<div className="mute2" style={{ fontSize: 15, marginTop: 4 }}>예) “다음 주 화요일 오후에 병원 같이 가 줄 사람”</div></div>}
          {phase === 'ask' && lastReply && !choiceMode && <div className="vr-q">{lastReply}</div>}
          {sp2.supported ? (
            <div className="center">
              <button className={'vr-mic' + (sp2.listening ? ' on' : '')} disabled={phase === 'thinking' || sp2.transcribing} onClick={sp2.toggle} aria-label={sp2.listening ? '다 말했어요' : '눌러서 말하기'} data-voice-mode={sp2.mode}>
                <Ic n="mic" s={44} />
              </button>
              <Wave on={sp2.listening} />
              <div className="vr-hint">{sp2.transcribing ? '글자로 바꾸는 중…' : sp2.listening ? (sp2.mode === 'server' ? `녹음 중 ${sp2.seconds}초 · 다 말하면 아래 버튼을 눌러 주세요 (1분까지)` : '듣고 있어요… 말이 끝나면 잠깐 기다려 주세요') : phase === 'card' ? '고칠 내용을 말하려면 누르세요' : '누르고 말씀하세요'}</div>
              {sp2.listening && <button className="btn soft vr-big" style={{ marginTop: 10 }} onClick={sp2.finish}>다 말했어요</button>}
            </div>
          ) : (
            <div className="vr-nosr">이 기기에서는 음성 입력이 안 돼요. 아래에 적어 주세요</div>
          )}
          {sp2.msg && sp2.supported && <div className="mute2 center" style={{ marginTop: 6 }}>{sp2.msg}</div>}
          <form className="vr-type" onSubmit={(e) => { e.preventDefault(); if (typed.trim()) send(typed); }}>
            <textarea className="in" placeholder={sp2.supported ? '말한 글자가 여기에 보여요. 직접 고치거나 적어도 돼요' : '여기에 적어 주세요'} value={typed} onChange={(e) => setTyped(e.target.value)} aria-label="요청 내용" rows={2} />
            <button className="btn vr-big" type="submit" disabled={!typed.trim() || phase === 'thinking' || sp2.listening || sp2.transcribing}>보내기</button>
          </form>
          {msgs.length > 0 && <button className="au-demo" onClick={reset}>↺ 처음부터 다시</button>}
        </div>
      )}
    </Screen>
  );
}

function DraftEditor({ draft, setDraft }) {
  const set = (k, v) => setDraft({ ...draft, [k]: v });
  const hours = []; for (let h = 8; h <= 20; h += 0.5) hours.push(h);
  return (
    <div className="vr-edit">
      <label className="f">제목</label><input className="in" value={draft.title || ''} onChange={(e) => set('title', e.target.value)} />
      <label className="f">날짜</label><input className="in" type="date" value={draft.date || ''} onChange={(e) => set('date', e.target.value)} />
      <label className="f">시간</label>
      <div className="row"><select className="in grow" value={draft.startHour ?? ''} onChange={(e) => set('startHour', +e.target.value)}>{hours.map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select><span>~</span><select className="in grow" value={draft.endHour ?? ''} onChange={(e) => set('endHour', +e.target.value)}>{hours.map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select></div>
      <label className="f">장소 (동)</label><select className="in" value={draft.area || ''} onChange={(e) => set('area', e.target.value)}><option value="">고르기</option>{AREAS.map((a) => <option key={a}>{a}</option>)}</select>
      <label className="f">도움 종류</label>
      <div className="vr-choices">{CATS.map((c) => <button key={c.k} type="button" className={(draft.categories || []).includes(c.k) ? 'on' : ''} onClick={() => set('categories', (draft.categories || []).includes(c.k) ? draft.categories.filter((x) => x !== c.k) : [...(draft.categories || []), c.k])}>{SHORT[c.k]}</button>)}</div>
      <label className="f">내용</label><textarea className="in" value={draft.detail || ''} onChange={(e) => set('detail', e.target.value)} />
      <label className="f">방문 시 주의사항</label><textarea className="in" style={{ minHeight: 70 }} value={draft.caution || ''} onChange={(e) => set('caution', e.target.value)} placeholder="예) 무릎이 불편하셔서 천천히 걸어 주세요 (병명은 적지 않아요)" />
    </div>
  );
}
