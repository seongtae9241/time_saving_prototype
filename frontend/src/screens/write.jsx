import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Logo, TBar, Loading, Empty, RuleNote, useLoad, useBusy, postCats } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post, put } from '../api.js';
import { CATS, AREAS, SHORT, hh, fmt, dlabel, addDays, lvName, lvWeight } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { PubCards } from './ai.jsx';
import { useSpeechInput } from './voice.jsx';
import { PostCheck, mergeChecks } from './assist.jsx'; // LLM 추가 A(7.12) 빠진 정보 점검

/* ---------- 봉사 요청 · 도움 가능 선택 ---------- */
export function Write() {
  const nav = useNavigate();
  const { me } = useAuth();
  const [mine] = useLoad(() => api('/posts/mine', { quiet: true }), []);
  const past = (mine || []).slice(0, 3);
  return (
    <Screen title="봉사 요청 · 도움 가능" noTabs>
      <h2 className="su-h" style={{ marginTop: 4 }}>무엇을<br />올릴까요?</h2>
      <p className="su-p">자동 매칭은 없어요. 공고를 보고 온 사람의 요청을 직접 골라요.</p>
      <div className="su-role" role="button" tabIndex={0} onClick={() => nav('/write/offer')}><div className="su-ic" style={{ color: '#1F9D63' }}><Ic h={I.hand} /></div><div className="grow"><div className="su-rt">도움 가능 <span className="mute2" style={{ fontWeight: 500 }}>도울 수 있어요</span></div><div className="su-rd">“금요일 1시~5시 비어요” → 필요한 사람이 원하는 시간에 요청해요</div></div>›</div>
      <div className="su-role" role="button" tabIndex={0} onClick={() => nav('/write/need')}><div className="su-ic" style={{ color: '#EE8424' }}><Ic h={I.chat} /></div><div className="grow"><div className="su-rt">봉사 요청 <span className="mute2" style={{ fontWeight: 500 }}>도움이 필요해요</span> <span className="mod m1" style={{ marginLeft: 4 }}>AI 작성</span></div><div className="su-rd">말로 설명하면 AI가 날짜·시간·장소를 물어보고 공고로 정리해요</div></div>›</div>
      <div className="su-role" role="button" tabIndex={0} onClick={() => nav('/voice')}><div className="su-ic" style={{ color: '#3D6DF2' }}><Ic h={I.mic} /></div><div className="grow"><div className="su-rt">말로 요청하기</div><div className="su-rd">마이크를 누르고 말하면 큰 글씨 요청서로 만들어 드려요</div></div>›</div>
      {past.length > 0 && <div className="sec-t">지난 공고 다시 올리기</div>}
      {past.map((p) => (
        <div key={p.id} className="card item click" role="button" tabIndex={0} onClick={() => nav(`/post/${p.id}`)}>
          <span className={`kind ${p.type === 'OFFER' ? 'offer' : 'need'}`}>{p.type === 'OFFER' ? '도움 가능' : '봉사 요청'}</span>
          <div className="grow"><div className="b">{p.type === 'OFFER' ? p.note || p.title : p.title}</div><div className="mute2">{postCats(p).map((c) => SHORT[c]).join('·')} · {hh(p.startHour)}~{hh(p.endHour)}</div></div><span className="mute2">↻</span>
        </div>
      ))}
      {me && !me.eduDone && <div className="notice" style={{ marginTop: 14 }}>도움 가능 공고는 신원 인증과 안전 교육을 마친 뒤 올릴 수 있어요.</div>}
    </Screen>
  );
}

/* ---------- 봉사 가능 공고 (B-04, B-06) · 공고 수정(B-11) ---------- */
function PostForm({ init, type, onSubmit, submitLabel }) {
  const [f, setF] = useState(init);
  const sp = useSpeechInput({ onFinal: (t) => setF((x) => ({ ...x, note: t })), onInterim: (t) => setF((x) => ({ ...x, note: t })) });
  const dates = Array.from({ length: 10 }, (_, i) => addDays(i + 1));
  if (f.date && !dates.includes(f.date)) dates.unshift(f.date);
  const set = (k, v) => setF({ ...f, [k]: v });
  const offer = type === 'OFFER';
  return (
    <>
      <label className="f">날짜</label>
      <div className="tags">{dates.map((d) => <button key={d} className={'chip ' + (f.date === d ? 'on' : '')} onClick={() => set('date', d)}>{dlabel(d)}</button>)}</div>
      <label className="f">{offer ? '비는 시간' : '도움이 필요한 시간'}</label>
      <div className="row">
        <select className="in grow" value={f.startHour} onChange={(e) => { const s = +e.target.value; setF({ ...f, startHour: s, endHour: f.endHour <= s ? Math.min(20, s + 1) : f.endHour }); }}>{Array.from({ length: 24 }, (_, i) => 8 + i * 0.5).filter((h) => h < 20).map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select>
        <span>~</span>
        <select className="in grow" value={f.endHour} onChange={(e) => set('endHour', +e.target.value)}>{Array.from({ length: 24 }, (_, i) => 8.5 + i * 0.5).filter((h) => h <= 20 && h > f.startHour).map((h) => <option key={h} value={h}>{hh(h)}</option>)}</select>
      </div>
      <div className="card" style={{ marginTop: 10, padding: '12px 16px' }}><TBar s={f.startHour} e={f.endHour} /></div>
      <label className="f">지역</label>
      <select className="in" value={f.area} onChange={(e) => set('area', e.target.value)}>{AREAS.map((a) => <option key={a}>{a}</option>)}</select>
      <label className="f">{offer ? '할 수 있는 도움 (여러 개)' : '필요한 도움'}</label>
      <div className="tags">{CATS.map((c) => <button key={c.k} className={'chip ' + (f.categories.includes(c.k) ? 'on' : '')} onClick={() => set('categories', f.categories.includes(c.k) ? f.categories.filter((x) => x !== c.k) : [...f.categories, c.k])}>{c.k}</button>)}</div>
      {!offer && <><label className="f">제목</label><input className="in" value={f.title || ''} onChange={(e) => set('title', e.target.value)} placeholder="예) 일주일치 장보기" /><label className="f">내용</label><textarea className="in" value={f.detail || ''} onChange={(e) => set('detail', e.target.value)} /></>}
      <label className="f">{offer ? '한마디' : '메모'}</label>
      <div className="row">
        <input className="in grow" placeholder={offer ? '예) 폰 설정·키오스크 설명 자신 있어요' : '예) 엘리베이터 있어요'} value={f.note || ''} onChange={(e) => set('note', e.target.value)} />
        <button type="button" className={'mic' + (sp.listening ? ' on' : '')} aria-label="음성 입력" onClick={sp.toggle}><Ic h={I.mic} /></button>
      </div>
      {sp.msg && <div className="mute2" style={{ marginTop: 6 }}>{sp.msg}</div>}
      {init.repeatable && (
        <div className={'su-check ' + (f.rep ? 'on' : '')} role="button" tabIndex={0} style={{ marginTop: 10 }} onClick={() => set('rep', !f.rep)}><span className="bx" /><div><div className="b">매주 같은 요일 반복 (4주)</div><div className="mute2">공고 1개에는 요청 1건만 받아요</div></div></div>
      )}
      <button className="btn big-cta" style={{ marginTop: 18 }} onClick={() => onSubmit(f)}>{submitLabel}</button>
    </>
  );
}

export function WriteOffer() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast } = useUi();
  const [busy, run] = useBusy();
  if (!me) return <Screen title="도움 가능" noTabs><Loading /></Screen>;
  return (
    <Screen title="도움 가능" noTabs>
      <div className="mute2" style={{ margin: '-2px 0 6px' }}>도울 수 있는 시간을 올리면, 도움이 필요한 이웃이 그 시간에 요청해요.</div>
      {!me.eduDone && <div className="notice">도움 가능 공고는 신원 인증과 첫 활동 교육을 마친 뒤 올릴 수 있어요. <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700 }} onClick={() => nav('/my/verify')}>인증하러 가기 ›</a></div>}
      <PostForm type="OFFER" submitLabel={busy ? '올리는 중…' : '공고 등록하기'} init={{ date: addDays(1), startHour: 13, endHour: 17, area: me.area || '행복동', categories: (me.categories && me.categories.length ? me.categories : ['디지털 도움']).slice(0, 3), note: '', rep: false, repeatable: true }}
        onSubmit={(f) => run(async () => {
          if (!f.categories.length) return toast('할 수 있는 도움을 골라 주세요');
          const r = await post('/posts', { type: 'OFFER', date: f.date, startHour: f.startHour, endHour: f.endHour, area: f.area, categories: f.categories, title: f.note || '봉사할 수 있어요', detail: '', note: f.note, repeat: f.rep ? { weekly: true, weeks: 4 } : undefined });
          toast(f.rep ? '4주 반복 공고를 올렸어요' : '공고를 올렸어요. 요청이 오면 알려드려요');
          const first = Array.isArray(r) ? r[0] : r;
          nav(`/post/${first.id}`, { replace: true });
        })} />
    </Screen>
  );
}

export function PostEdit() {
  const { id } = useParams();
  const nav = useNavigate();
  const { toast } = useUi();
  const [sp] = useSearchParams(); // LLM 추가 A(7.12): "이렇게 바꿔 보세요" 버튼이 넘긴 값(시간대·카테고리·날짜)
  const tip = sp.get('tip');
  const [p] = useLoad(() => api(`/posts/${id}`), [id]);
  if (p === undefined) return <Screen title="공고 수정" noTabs><Loading /></Screen>;
  if (!p) return <Screen title="공고 수정" noTabs><Empty>공고를 찾을 수 없어요</Empty></Screen>;
  return (
    <Screen title="공고 수정" noTabs>
      <div className="notice" style={{ marginBottom: 6 }}>요청을 수락하기 전까지만 수정할 수 있어요.</div>
      {tip && <div className="ai amber" style={{ marginBottom: 6 }}><div className="ah"><Spk />AI 제안을 미리 넣었어요<Mod n={4} /></div><div className="small">{{ TIME: '시간대를 바꿔 두었어요.', SPLIT: `도움 종류를 ${sp.get('cats') || ''}만 남겼어요.${sp.get('rest') ? ` 저장한 뒤 ${sp.get('rest')}는 새 공고로 올려 주세요.` : ''}`, DATE: '날짜를 바꿔 두었어요.', EDIT: '할 일을 한두 줄 더 적어 주세요.' }[tip] || ''} 확인하고 저장을 눌러야 바뀌어요.</div></div>}
      <PostForm type={p.type} submitLabel="저장" init={{ date: sp.get('date') || String(p.date).slice(0, 10), startHour: sp.get('startHour') ? Number(sp.get('startHour')) : Number(p.startHour), endHour: sp.get('endHour') ? Number(sp.get('endHour')) : Number(p.endHour), area: p.area || '행복동', categories: sp.get('cats') ? sp.get('cats').split(',') : postCats(p), title: p.title, detail: p.detail, note: p.note }}
        onSubmit={async (f) => {
          try { await put(`/posts/${p.id}`, { type: p.type, date: f.date, startHour: f.startHour, endHour: f.endHour, area: f.area, categories: f.categories, title: p.type === 'OFFER' ? f.note || p.title : f.title, detail: f.detail, estHours: p.type === 'NEED' ? Math.min(Number(p.estHours) || 4, f.endHour - f.startHour) : undefined, note: f.note, elderId: p.elderId || undefined }); toast('저장했어요'); nav(-1); } catch { /* 토스트 */ }
        }} />
    </Screen>
  );
}

/* ---------- 대화형 도움 요청 공고 (B-02, B-03, C-04) ---------- */
export function WriteNeed() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  const family = sp.get('family');
  const { me } = useAuth();
  const { toast } = useUi();
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  const who = elderId ? (elder ? elder.alias + ' 님' : '대상') : family ? `${family} 님` : null;
  const first = elderId ? `${who} 대신 도움 요청 공고를 만들어 볼게요. 어떤 도움이 필요하신가요?` : family ? `${who} 대신 공고를 만들어 볼게요. 어떤 도움이 필요하세요? 최종 수락은 가족이 직접 하세요.` : '어떤 도움이 필요하세요? 편하게 말해 주시면 제가 공고로 정리할게요.';
  const [chat, setChat] = useState([]); // {role, text} | {card:'done'|'block', res}
  const [res, setRes] = useState(null);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [posting, run] = useBusy();
  // O2: 담당자 대리 공고 "AI로 정리하기" — 정리한 제목·내용·주의사항은 올릴 때 그대로 쓴다(날짜·시간·장소는 대화로)
  const [tidyOpen, setTidyOpen] = useState(false);
  const [tidyText, setTidyText] = useState('');
  const [tidyRes, setTidyRes] = useState(null);
  const [tidyFix, setTidyFix] = useState(null);
  const [tidyBusy, setTidyBusy] = useState(false);
  const [chk, setChk] = useState([]); // 빠진 정보 점검에서 답한 것
  const canTidy = !!elderId || (me && me.role === 'ORG');
  const runTidy = async () => {
    if (!tidyText.trim()) return toast('정리할 글을 먼저 적어 주세요');
    setTidyBusy(true);
    try { setTidyRes(await post('/ai/tidy-post', { text: tidyText, elderId: elderId ? Number(elderId) : undefined })); } catch { /* 토스트 */ }
    setTidyBusy(false);
  };
  const bd = useRef(null);
  const spch = useSpeechInput({ onFinal: (t) => say(t), onInterim: setText });
  useEffect(() => { if (bd.current) bd.current.scrollTop = bd.current.scrollHeight; }, [chat, busy]);
  const history = chat.filter((m) => m.role).map((m) => ({ role: m.role, text: m.text }));
  const say = async (t) => {
    t = String(t || '').trim(); if (!t) return;
    setText('');
    const next = [...chat, { role: 'user', text: t }];
    setChat(next); setBusy(true);
    try {
      const r = await post('/ai/post-chat', { type: 'NEED', messages: [...history, { role: 'user', text: t }], forElderId: elderId ? Number(elderId) : undefined });
      setRes(r);
      const add = [];
      if (r.filter && r.filter.level === 'BLOCK') {
        add.push({ card: 'block', res: r });
        add.push({ role: 'assistant', text: '대신 말벗, 장보기, 동행, 디지털 도움처럼 이웃이 할 수 있는 일은 공고로 올릴 수 있어요.' });
      } else {
        if (r.reply) add.push({ role: 'assistant', text: r.reply });
        if (r.done && r.draft) add.push({ card: 'done', res: r });
      }
      setChat([...next, ...add]);
    } catch { setChat(next); }
    setBusy(false);
  };
  const reset = () => { setChat([]); setRes(null); setText(''); setTidyFix(null); setTidyRes(null); setChk([]); };
  const done = chat.some((m) => m.card === 'done');
  const blocked = res && res.filter && res.filter.level === 'BLOCK';
  const missing = (res && res.missing) || [];
  const qs = !chat.length
    ? (elderId ? ['다음 주 화요일 오전에 병원 같이 가 줄 사람', '이번 주 금요일 장보기 도와줄 사람', '약 챙겨서 먹여 줄 사람'] : ['다음 주 수요일 오후에 폰 사진 옮기는 거 도와줄 사람', '주말에 같이 장 봐줄 사람', '약 챙겨서 먹여 줄 사람'])
    : missing.some((m) => /time|hour|시간/i.test(m)) ? ['오후 2시~5시', '오전 10시~12시', '저녁 6시~8시']
      : missing.some((m) => /area|place|장소/i.test(m)) ? (elderId ? ['대상 집 (주소는 담당자가 안내)', '행복동 주민센터'] : ['우리 집', '근처 카페', '행복동 주민센터'])
        : missing.some((m) => /date|날짜/i.test(m)) ? ['내일', '이번 주 토요일', '다음 주 화요일'] : [];
  const publish = (r) => run(async () => {
    const d = r.draft;
    const tf = tidyFix || {};
    const m = mergeChecks({ ...d, detail: tf.detail || d.detail, caution: tf.caution || d.caution }, chk);
    const baseTitle = tf.title || d.title;
    const title = family ? `${baseTitle} (${family} 님)` : baseTitle;
    const caution = m.caution;
    const cats = tf.categories && tf.categories.length && !(d.categories || []).length ? tf.categories : d.categories;
    const p = await post('/posts', { type: 'NEED', date: d.date, startHour: d.startHour, endHour: d.endHour, estHours: Number(d.estHours) || (Number(d.endHour) - Number(d.startHour)), area: d.area || (elder ? elder.dong : me.area), categories: cats, title, detail: m.detail, note: caution ? `방문 시 주의: ${caution}` : d.note, caution, elderId: elderId ? Number(elderId) : undefined });
    const pf = Array.isArray(p) ? p[0] : p;
    toast(elderId ? `${who} 대신 공고를 올렸어요` : family ? `${who} 이름으로 올렸어요. 최종 수락은 가족이 해요` : '공고를 올렸어요');
    nav(`/post/${pf.id}`, { replace: true });
  });
  let firstAI = true;
  const Bot = () => <span className="bot"><Logo /></span>;
  return (
    <Screen title={elderId ? `${who} 대신 봉사 요청` : family ? '가족 대신 봉사 요청' : '봉사 요청'} noTabs cls="chatv" bodyRef={bd}>
      <div className="chat-wrap">
        <div className="chat-day"><span>AI가 공고를 정리해요 · 작성 도우미 AI</span></div>
        <div className="chat">
          <div className="mrow"><Bot /><div className="mcol"><div className="bot-n">작성 도우미</div><div className="msg ai-m">{first}</div></div></div>
          {canTidy && !chat.length && (
            <div className="mrow"><Bot /><div className="mcol wide">
              {!tidyOpen ? <button className="btn soft sm" style={{ width: 'auto', padding: '0 14px' }} onClick={() => setTidyOpen(true)}><Spk /> 적어 둔 글을 AI로 정리하기</button> : (
                <div className="tidy-box">
                  <div className="ah2"><Spk />AI로 정리하기<Mod n={1} /></div>
                  <div className="mute2" style={{ margin: '4px 0 8px' }}>메모처럼 적으면 제목·내용·주의사항으로 다듬어요. 전화번호·주소는 지워요.</div>
                  <textarea className="in" value={tidyText} onChange={(e) => setTidyText(e.target.value)} placeholder="예) 어르신 다음 주 화요일 오전 정형외과 재진. 무릎이 안 좋으셔서 천천히 걸어야 함. 택시 같이 타 줄 분" aria-label="정리할 글" />
                  <button className="btn sm" style={{ marginTop: 8 }} disabled={tidyBusy} onClick={runTidy}>{tidyBusy ? '정리하는 중…' : 'AI로 정리하기'}</button>
                  {tidyRes && (
                    <div className="ai" style={{ marginTop: 10 }}>
                      <div className="ah"><Spk />이렇게 정리했어요</div>
                      <label className="f" style={{ marginTop: 0 }}>제목</label><input className="in" value={tidyRes.title || ''} onChange={(e) => setTidyRes({ ...tidyRes, title: e.target.value })} />
                      <label className="f">내용</label><textarea className="in" value={tidyRes.detail || ''} onChange={(e) => setTidyRes({ ...tidyRes, detail: e.target.value })} />
                      <label className="f">주의사항</label><input className="in" value={tidyRes.caution || ''} onChange={(e) => setTidyRes({ ...tidyRes, caution: e.target.value })} placeholder="없으면 비워 두세요" />
                      {(tidyRes.piiRemoved || []).length > 0 && <div className="mute2" style={{ marginTop: 6 }}>개인정보({tidyRes.piiRemoved.map((x) => ({ phone: '전화번호', account: '계좌', address: '주소' }[x] || x)).join('·')})를 지웠어요</div>}
                      <RuleNote source={tidyRes.source} />
                      <button className="btn sm" style={{ marginTop: 10 }} onClick={() => { setTidyFix(tidyRes); say(tidyText); }}>이 내용으로 공고 만들기 ›</button>
                    </div>
                  )}
                </div>
              )}
            </div></div>
          )}
          {chat.map((m, i) => {
            if (m.card === 'block') {
              const f = m.res.filter;
              return <div className="mrow" key={i}><Bot /><div className="mcol wide"><div className="ai warn"><div className="ah"><Spk />이 요청은 보낼 수 없어요 · 불가<Mod n={2} /></div><div className="small">{f.reason}</div>{(f.alternatives || []).length > 0 && <PubCards list={f.alternatives} />}<RuleNote source={m.res.source} /></div></div></div>;
            }
            if (m.card === 'done') {
              const base = tidyFix ? { ...m.res.draft, title: tidyFix.title || m.res.draft.title, detail: tidyFix.detail || m.res.draft.detail, caution: tidyFix.caution || m.res.draft.caution } : m.res.draft;
              return <div className="mrow" key={i}><Bot /><div className="mcol wide"><DraftCard r={{ ...m.res, draft: mergeChecks(base, chk) }} elderId={elderId} onReset={reset} onPost={() => publish(m.res)} posting={posting}
                check={<PostCheck draft={base} elderId={elderId} onChange={setChk} />} /></div></div>;
            }
            if (m.role === 'assistant') { firstAI = false; return <div className="mrow" key={i}><Bot /><div className="mcol"><div className="msg ai-m">{m.text}</div></div></div>; }
            return <div className="msg me" key={i}>{m.text}</div>;
          })}
          {busy && <div className="mrow"><Bot /><div className="mcol"><div className="msg ai-m mute2">정리하는 중…</div></div></div>}
        </div>
      </div>
      <div className="chat-dock">
        {(!done || blocked) && (
          <>
            <div className="qr-row"><div className="qr">{qs.map((q) => <button key={q} onClick={() => say(q)} disabled={busy}>{q}</button>)}</div><button className="qr-reset" onClick={reset}>↺ 처음부터</button></div>
            <form className="composer" onSubmit={(e) => { e.preventDefault(); say(text); }}>
              <button type="button" className={'mic' + (spch.listening ? ' on' : '')} aria-label="음성 입력" onClick={spch.toggle}><Ic h={I.mic} /></button>
              <input className="in" placeholder={spch.listening ? '듣고 있어요…' : '말하듯 적어 주세요'} value={text} onChange={(e) => setText(e.target.value)} aria-label="요청 내용" />
              <button type="submit" aria-label="보내기" disabled={busy}><Ic h={I.send} /></button>
            </form>
            {spch.msg && <div className="mute2" style={{ marginTop: 6 }}>{spch.msg}</div>}
          </>
        )}
      </div>
      <span hidden>{String(firstAI)}</span>
    </Screen>
  );
}

function DraftCard({ r, elderId, onReset, onPost, posting, check }) {
  const d = r.draft;
  const df = r.difficulty || {};
  const w = lvWeight(df.level, df.weight);
  const json = JSON.stringify({ type: '도움 요청 공고', date: d.date, time_range: [hh(d.startHour), hh(d.endHour)], area: d.area, categories: d.categories, title: d.title, detail: d.detail, caution: d.caution, difficulty: df.level, expected_hours: d.estHours, filter: r.filter && r.filter.level }, null, 1);
  return (
    <>
      <div className="ai">
        <div className="ah"><Spk />AI가 정리한 공고<Mod n={1} /></div>
        <dl className="kv">
          <dt>종류</dt><dd>{(d.categories || []).join(', ')}</dd>
          <dt>내용</dt><dd>{d.title}{d.detail ? ` · ${d.detail}` : ''}</dd>
          <dt>날짜</dt><dd>{dlabel(d.date)}</dd>
          <dt>시간 범위</dt><dd>{hh(d.startHour)} ~ {hh(d.endHour)}</dd>
          <dt>장소</dt><dd>{d.area}{d.place ? ` · ${d.place}` : ''}</dd>
          {d.caution && <><dt>주의사항</dt><dd>{d.caution}</dd></>}
        </dl>
        <TBar s={d.startHour} e={d.endHour} />
        <details style={{ marginTop: 10 }}><summary className="mute2" style={{ cursor: 'pointer' }}>AI가 만든 JSON 보기</summary><pre className="json">{json}</pre></details>
        <RuleNote source={r.source} />
      </div>
      <div className="ai" style={{ marginTop: 8 }}>
        <div className="ah"><Spk />난이도·예상 적립<Mod n={2} /></div>
        <div className="between"><span className="small">{d.estHours}시간 × {lvName(df.level)} {w.toFixed(1)}</span><b className="cr plus">+{fmt(df.estEarn != null ? df.estEarn : d.estHours * w)}시간</b></div>
        {df.reason && <div className="small muted">근거: {df.reason}</div>}
        <div className="small muted">금지 요청 확인: {{ ALLOW: '허용 ✓', CAUTION: '주의' }[r.filter && r.filter.level] || '허용 ✓'}{r.filter && r.filter.level === 'CAUTION' && r.filter.reason ? ` (${r.filter.reason})` : ''} · {elderId ? '기관 대상이라 시간 차감 없음' : '완료 후 실제 시간만큼 차감'}</div>
      </div>
      {check}
      <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost" onClick={onReset}>다시 쓰기</button><button className="btn" disabled={posting} onClick={onPost}>공고 등록</button></div>
    </>
  );
}
