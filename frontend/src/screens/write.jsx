import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Logo, TBar, Loading, Empty, RuleNote, useLoad, useBusy, postCats } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post, put } from '../api.js';
import { CATS, AREAS, SHORT, hh, fmt, dlabel, addDays, lvName, lvWeight } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { PubCards, NearbyPublicBtn } from './ai.jsx';
import { useSpeechInput } from './voice.jsx';
import { PostCheck, mergeChecks } from './assist.jsx'; // LLM 추가 A(7.12) 빠진 정보 점검
import { DIc } from '../ui/proto.jsx';
import { SuggestLink } from '../ui/suggest.jsx';
import { useRepost } from '../ui/repost.jsx';

/* ---------- 봉사 요청 · 도움 가능 선택 ---------- */
export function Write() {
  const nav = useNavigate();
  const { me } = useAuth();
  const [mine] = useLoad(() => api('/posts/mine', { quiet: true }), []);
  const repost = useRepost();
  const past = (mine || []).filter((p) => !p.proxyMine).slice(0, 3); // 가족 대신 올린 공고는 읽기만이라 다시 올리기 목록에 넣지 않는다
  return (
    <Screen title="공고 올리기" noTabs>
      <h2 className="su-h" style={{ marginTop: 4 }}>어떤 공고를<br />올릴까요?</h2>
      <p className="su-p">자동 매칭은 없어요. 공고를 보고 온 요청을 직접 골라요.</p>
      <div className="bbtn big">
        <button onClick={() => nav('/write/offer')}><DIc k="offer" size={48} /><b>도움 가능</b><em>내가 도와줄 수 있는 시간을 올려요</em></button>
        <button onClick={() => nav('/write/need')}><DIc k="req" size={48} /><b>봉사 요청</b><em>도움이 필요한 일을 AI와 정리해요</em></button>
      </div>
      <div className="ml" style={{ marginTop: 12 }}>
        <div role="button" tabIndex={0} onClick={() => nav('/write/need/manual')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/write/need/manual'); }}><Ic n="pen" s={20} /><div className="grow">봉사 요청 직접 적기<span>AI 없이 칸을 직접 채워요 (수동)</span></div>›</div>
        <div role="button" tabIndex={0} onClick={() => nav('/group/new')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/group/new'); }}><Ic n="users" s={20} /><div className="grow">단체로 봉사하기<span>동아리·모임처럼 2명 이상이 함께 봉사할 때 (단체 공고)</span></div>›</div>
        <div role="button" tabIndex={0} onClick={() => nav('/voice')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/voice'); }}><Ic n="mic" s={20} /><div className="grow">말로 요청하기<span>마이크를 누르고 말하면 큰 글씨 요청서로 만들어 드려요</span></div>›</div>
      </div>
      {past.length > 0 && <><div className="sec-t">지난 공고 다시 올리기</div><div className="mute2" style={{ margin: '-4px 0 8px' }}>누르면 같은 내용으로 불러와요. 날짜만 고르면 돼요.</div></>}
      {past.map((p) => (
        <div key={p.id} className="card item click" role="button" tabIndex={0} onClick={() => repost(p)} onKeyDown={(e) => { if (e.key === 'Enter') repost(p); }}>
          <span className={`kind ${p.type === 'OFFER' ? 'offer' : 'need'}`}>{p.type === 'OFFER' ? '도움 가능' : '봉사 요청'}</span>
          <div className="grow"><div className="b">{p.type === 'OFFER' ? p.note || p.title : p.title}</div><div className="mute2">{p.proxyMine ? `${p.ownerName} 님 대신 · ` : ''}{postCats(p).map((c) => SHORT[c]).join('·')} · {dlabel(p.date)} {hh(p.startHour)}~{hh(p.endHour)}</div></div><span className="mute2">↻</span>
        </div>
      ))}
      {me && me.idStatus !== 'APPROVED' && <div className="notice" style={{ marginTop: 14 }}>도움 가능 공고는 신분증 인증을 마친 뒤 올릴 수 있어요.</div>}
    </Screen>
  );
}

/** 대화형 작성의 빠른 답 칩: 지금 묻는 칸에 맞춘다(시작 시각 ↔ 걸리는 시간 길이를 섞지 않는다). */
export function quickFor(field, forElder) {
  switch (field) {
    case 'date': return ['내일', '이번 주 토요일', '다음 주 화요일'];
    case 'startHour': return ['오전 10시', '오후 2시', '저녁 6시'];
    case 'endHour': return ['오후 1시까지', '오후 5시까지', '저녁 8시까지'];
    case 'estHours': return ['1시간', '1시간 30분', '2시간', '3시간'];
    case 'area': return forElder ? ['대상 집 (주소는 담당자가 안내)', '행복동 주민센터'] : ['우리 집', '근처 카페', '행복동 주민센터'];
    default: return [];
  }
}

/* ---------- 도움 가능 공고 (B-04, B-06) · 공고 수정(B-11) ---------- */
function PostForm({ init, type, onSubmit, submitLabel, extra, needDate }) {
  const [f, setF] = useState(init);
  const sp = useSpeechInput({ onFinal: (t) => setF((x) => ({ ...x, note: t })), onInterim: (t) => setF((x) => ({ ...x, note: t })) });
  const dates = Array.from({ length: 10 }, (_, i) => addDays(i + 1));
  if (f.date && !dates.includes(f.date)) dates.unshift(f.date);
  const set = (k, v) => setF({ ...f, [k]: v });
  const offer = type === 'OFFER';
  return (
    <>
      <label className="f">날짜</label>
      {needDate && !f.date && <div className="notice" style={{ marginBottom: 8 }}>지난 공고를 불러왔어요. 날짜를 골라 주세요.</div>}
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
      <SuggestLink />
      {!offer && <><label className="f">제목</label><input className="in" value={f.title || ''} onChange={(e) => set('title', e.target.value)} placeholder="예) 일주일치 장보기" /><label className="f">내용</label><textarea className="in" value={f.detail || ''} onChange={(e) => set('detail', e.target.value)} /></>}
      <label className="f">{offer ? '한마디' : '메모'}</label>
      <div className="row">
        <input className="in grow" placeholder={offer ? '예) 폰 설정·키오스크 설명 자신 있어요' : '예) 엘리베이터 있어요'} value={f.note || ''} onChange={(e) => set('note', e.target.value)} />
        <button type="button" className={'mic' + (sp.listening ? ' on' : '')} aria-label="음성 입력" onClick={sp.toggle}><Ic h={I.mic} /></button>
      </div>
      {sp.msg && <div className="mute2" style={{ marginTop: 6 }}>{sp.msg}</div>}
      {init.elderIssue !== undefined && <IssueField value={f.elderIssue} onChange={(v) => set('elderIssue', v)} />}
      {extra}
      <button className="btn big-cta" style={{ marginTop: 18 }} disabled={!f.date} onClick={() => f.date && onSubmit(f)}>{f.date ? submitLabel : '날짜를 골라 주세요'}</button>
    </>
  );
}

/** 담당자 대리 공고의 "관리 대상 이슈": 대상 등록 때의 주의사항을 기본값으로 채우고 공고별로 고친다. */
export function IssueField({ value, onChange, base }) {
  return (
    <>
      <label className="f" htmlFor="elder-issue">관리 대상 이슈 <span className="mute2" style={{ fontWeight: 500 }}>(메이트에게 보일 주의사항)</span></label>
      <textarea id="elder-issue" className="in" style={{ minHeight: 76 }} maxLength={300} value={value || ''} onChange={(e) => onChange(e.target.value)} placeholder="예) 오전에는 기운이 없으셔서 천천히 걸어 주세요" />
      <div className="mute2" style={{ marginTop: 6 }}>이 공고에만 적용돼요. 병명·연락처·주소는 적지 않아요{base ? ` · 대상 기본 주의사항: ${base}` : ''}</div>
    </>
  );
}

/* ---------- 봉사 요청 직접 적기(수동) · 담당자 대리 수동 등록 ---------- */
export function WriteNeedManual() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  const { me } = useAuth();
  const { toast } = useUi();
  const [busy, run] = useBusy();
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  if (!me || (elderId && elder === undefined)) return <Screen title="봉사 요청" noTabs><Loading /></Screen>;
  const who = elder ? `${elder.alias} 님` : null;
  return (
    <Screen title={who ? '대신 봉사 요청' : '봉사 요청'} noTabs>
      {who ? <div className="lock"><Ic n="users" s={18} /><div><b>{who}</b>({elder.dong}) 대신 올려요. 대상 시간은 차감되지 않아요.</div></div>
        : <div className="mute2" style={{ margin: '-2px 0 6px' }}>칸을 직접 채워 올려요. AI와 대화로 만들려면 <button type="button" className="linkbtn" onClick={() => nav('/write/need', { replace: true })}>AI로 정리하기</button></div>}
      <PostForm type="NEED" submitLabel={busy ? '올리는 중…' : '공고 등록하기'}
        init={{ date: addDays(1), startHour: 10, endHour: 12, area: (elder && elder.dong) || me.area || '행복동', categories: elder && elder.needs && elder.needs.length ? elder.needs.slice(0, 1) : [], title: '', detail: '', note: '', ...(elder ? { elderIssue: elder.caution || '' } : {}) }}
        onSubmit={(f) => run(async () => {
          if (!f.categories.length) return toast('필요한 도움을 골라 주세요');
          if (!(f.title || '').trim()) return toast('제목을 적어 주세요');
          const chk = await post('/ai/filter', { text: `${f.title} ${f.detail || ''}` }, { quiet: true }).catch(() => null);
          if (chk && chk.level === 'BLOCK') return toast(chk.reason || '이 요청은 올릴 수 없어요');
          const r = await post('/posts', { type: 'NEED', date: f.date, startHour: f.startHour, endHour: f.endHour, estHours: Math.min(4, f.endHour - f.startHour), area: f.area, categories: f.categories, title: f.title.trim(), detail: f.detail || '', note: f.note,
            ...(elder ? { elderId: Number(elderId), elderIssue: f.elderIssue || '' } : {}) });
          toast(who ? `${who} 대신 봉사 요청을 올렸어요` : '공고를 올렸어요');
          const first = Array.isArray(r) ? r[0] : r;
          nav(`/post/${first.id}`, { replace: true });
        })} />
    </Screen>
  );
}

export function WriteOffer() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast, sheet, closeSheet } = useUi();
  const [busy, run] = useBusy();
  // 프로토타입(2026-11-12) woReuse: 자동 반복 대신 지난 공고를 직접 불러와 날짜만 바꾼다(B-06)
  const [mine] = useLoad(() => api('/posts/mine', { quiet: true }), []);
  const [loaded, setLoaded] = useState(null); // 불러온 지난 공고(칸을 그 내용으로 다시 채운다)
  const pastOffers = (mine || []).filter((p) => p.type === 'OFFER').slice(0, 8);
  const reuse = () => sheet(() => (
    <>
      <h2>지난 공고 불러오기</h2>
      <p className="mute2" style={{ margin: '0 0 10px' }}>자동으로 반복되지 않아요. 필요할 때 직접 불러와서 날짜만 바꿔요.</p>
      {pastOffers.length ? pastOffers.map((p) => (
        <div key={p.id} className="opt" role="button" tabIndex={0} onClick={() => { setLoaded(p); closeSheet(); toast('지난 공고를 불러왔어요. 날짜를 골라 주세요'); }}><span className="rd" /><div className="grow"><div className="b">{p.note || p.title}</div><div className="mute2">{dlabel(p.date)} · {hh(p.startHour)}~{hh(p.endHour)}</div></div></div>
      )) : <Empty>아직 올린 도움 가능 공고가 없어요</Empty>}
    </>
  ));
  if (!me) return <Screen title="도움 가능" noTabs><Loading /></Screen>;
  return (
    <Screen title="도움 가능" noTabs>
      <div className="mute2" style={{ margin: '-2px 0 6px' }}>도울 수 있는 시간을 올리면, 도움이 필요한 이웃이 그 시간에 요청해요.</div>
      {me.idStatus !== 'APPROVED' && <div className="notice">도움 가능 공고는 신분증 인증을 마친 뒤 올릴 수 있어요. <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700 }} onClick={() => nav('/my/verify')}>인증하러 가기 ›</a></div>}
      <PostForm key={loaded ? `re-${loaded.id}` : 'new'} type="OFFER" needDate={!!loaded} submitLabel={busy ? '올리는 중…' : '공고 등록하기'}
        extra={<><button type="button" className="btn soft" style={{ marginTop: 10 }} onClick={reuse}>지난 공고 불러오기</button><div className="mute2" style={{ marginTop: 6 }}>공고 1개에는 요청 1건만 받아요 · 자동으로 반복되지 않아요</div></>}
        init={loaded
          ? { date: '', startHour: Number(loaded.startHour), endHour: Number(loaded.endHour), area: loaded.area || me.area || '행복동', categories: postCats(loaded), note: loaded.note || loaded.title || '' }
          : { date: addDays(1), startHour: 13, endHour: 17, area: me.area || '행복동', categories: (me.categories && me.categories.length ? me.categories : ['디지털 도움']).slice(0, 3), note: '' }}
        onSubmit={(f) => run(async () => {
          if (!f.date) return toast('날짜를 골라 주세요');
          if (!f.categories.length) return toast('할 수 있는 도움을 골라 주세요');
          const r = await post('/posts', { type: 'OFFER', date: f.date, startHour: f.startHour, endHour: f.endHour, area: f.area, categories: f.categories, title: f.note || '봉사할 수 있어요', detail: '', note: f.note });
          toast('공고를 올렸어요. 요청이 오면 알려드려요');
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

/* ---------- 대화형 봉사 요청 공고 (B-02, B-03, C-04) ---------- */
export function WriteNeed() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  // B-57 가족 대신 공고: 주소의 family(이름) 또는 familyId(회원 번호)로 들어온다. 서버가 연결(수락)된 가족인지 다시 확인한다
  const famParam = sp.get('family');
  const famIdParam = sp.get('familyId');
  const familyMode = !elderId && !!(famParam || famIdParam);
  const { me } = useAuth();
  const { toast, sheet, closeSheet } = useUi();
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  const [links] = useLoad(() => (familyMode ? api('/family', { quiet: true }) : Promise.resolve([])), [familyMode]);
  const linked = (links || []).filter((f) => f.status === 'ACCEPTED' && f.user);
  const [famPick, setFamPick] = useState(null); // 연결된 가족이 여럿이면 고를 수 있다
  const famUser = !familyMode ? null
    : (famPick && linked.find((f) => f.user.id === famPick) || linked.find((f) => String(f.user.id) === String(famIdParam)) || linked.find((f) => f.user.name === famParam) || linked[0] || {}).user || null;
  const family = familyMode ? (famUser ? famUser.name : famParam || '가족') : null;
  const who = elderId ? (elder ? elder.alias + ' 님' : '대상') : family ? `${family} 님` : null;
  // B-02·B-14: 대화를 시작하기 전에 누구에게 부탁할지 먼저 묻는다(가족 대신 공고와 미리 채운 문장으로 들어온 경우는 바로 공고 작성)
  const [target, setTarget] = useState(familyMode || sp.get('prefill') || sp.get('to') === 'new' ? 'new' : null);
  const goRegular = () => nav(elderId ? `/regular?elder=${elderId}` : '/regular', { replace: true });
  // 프로토타입(2026-11-12) writeNeed: 시트 "누구에게 도움을 청할까요?" 의 두 선택지. 시트를 그냥 닫아도 화면 안에 같은 선택지가 남는다
  const WhoOpts = ({ after }) => (
    <>
      <div className="opt" role="button" tabIndex={0} onClick={() => { if (after) after(); setTarget('new'); }} onKeyDown={(e) => { if (e.key === 'Enter') { if (after) after(); setTarget('new'); } }}><span className="rd" /><div className="grow"><div className="b">새로운 사람에게</div><div className="mute2">공고를 올리면 이웃이 제안해요</div></div></div>
      <div className="opt" role="button" tabIndex={0} onClick={() => { if (after) after(); goRegular(); }} onKeyDown={(e) => { if (e.key === 'Enter') { if (after) after(); goRegular(); } }}><span className="rd" /><div className="grow"><div className="b">자주 만난 단골에게</div><div className="mute2">단골에게 바로 요청을 보내요</div></div></div>
    </>
  );
  const asked = useRef(false);
  useEffect(() => {
    if (target || asked.current) return;
    asked.current = true;
    sheet(() => <><h2>누구에게 도움을 청할까요?</h2><WhoOpts after={closeSheet} /></>);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const first = elderId ? `${who} 대신 봉사 요청 공고를 만들어 볼게요. 어떤 도움이 필요하신가요?` : family ? `${who} 대신 공고를 만들어 볼게요. 어떤 도움이 필요하세요? 공고는 ${who} 이름으로 올라가고, 최종 수락·거부는 ${who}이 직접 해요.` : '어떤 도움이 필요하세요? 편하게 말해 주시면 제가 공고로 정리할게요.';
  const [chat, setChat] = useState([]); // {role, text} | {card:'done'|'block', res}
  const [res, setRes] = useState(null);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [posting, run] = useBusy();
  // O2: 담당자 대리 공고 "AI로 정리하기" — 정리한 제목·내용·주의사항은 올릴 때 그대로 쓴다(날짜·시간·장소는 대화로)
  const [tidyOpen, setTidyOpen] = useState(!!elderId); // 2026-11-11: 담당자 대리 요청은 프로토타입 o_write 처럼 적는 칸이 먼저 열려 있다
  const [tidyText, setTidyText] = useState('');
  const [tidyRes, setTidyRes] = useState(null);
  const [tidyFix, setTidyFix] = useState(null);
  const [tidyBusy, setTidyBusy] = useState(false);
  const [chk, setChk] = useState([]); // 빠진 정보 점검에서 답한 것
  // 2026-11-11: 담당자 대리 공고의 "관리 대상 이슈" — 대상 등록 때의 주의사항이 기본값, 공고별로 고칠 수 있다
  const [issue, setIssue] = useState(null);
  const issueVal = issue != null ? issue : (elder && elder.caution) || '';
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
  const prefilled = useRef(false); // 기관 클래스 "같이 갈 봉사자 요청": 미리 채운 문장으로 초안까지 만들고, 올리기는 사용자가 한다
  useEffect(() => { const pf = sp.get('prefill'); if (pf && !prefilled.current) { prefilled.current = true; say(pf.slice(0, 300)); } }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const done = chat.some((m) => m.card === 'done');
  const blocked = res && res.filter && res.filter.level === 'BLOCK';
  const missing = (res && res.missing) || [];
  const qs = !target ? [] : !chat.length
    ? (elderId ? ['다음 주 화요일 오전에 병원 같이 가 줄 사람', '이번 주 금요일 장보기 도와줄 사람', '약 챙겨서 먹여 줄 사람'] : ['다음 주 수요일 오후에 폰 사진 옮기는 거 도와줄 사람', '주말에 같이 장 봐줄 사람', '약 챙겨서 먹여 줄 사람'])
    : quickFor(missing[0], !!elderId); // 서버가 지금 묻는 칸(missing 의 첫 칸)에 맞는 빠른 답
  const publish = (r) => run(async () => {
    const d = r.draft;
    const tf = tidyFix || {};
    const m = mergeChecks({ ...d, detail: tf.detail || d.detail, caution: tf.caution || d.caution }, chk);
    const title = tf.title || d.title; // 가족 대신 공고도 제목에 이름을 붙이지 않는다(공고 주인이 가족이라 가족 이름으로 보인다)
    if (familyMode && !famUser) return toast('연결된 가족을 찾지 못했어요. 마이페이지에서 가족 연결을 먼저 해 주세요');
    const caution = m.caution;
    const cats = tf.categories && tf.categories.length && !(d.categories || []).length ? tf.categories : d.categories;
    const p = await post('/posts', { type: 'NEED', date: d.date, startHour: d.startHour, endHour: d.endHour, estHours: Number(d.estHours) || (Number(d.endHour) - Number(d.startHour)), area: d.area || (elder ? elder.dong : me.area), ...(elderId ? { elderIssue: [issueVal, caution].filter((x, i, a) => x && a.indexOf(x) === i).join(' / ').slice(0, 300) } : {}), categories: cats, title, detail: m.detail, note: elderId ? (d.note || '') : caution ? `방문 시 주의: ${caution}` : d.note, caution, elderId: elderId ? Number(elderId) : undefined, forUserId: familyMode ? famUser.id : undefined });
    const pf = Array.isArray(p) ? p[0] : p;
    toast(elderId ? `${who} 대신 공고를 올렸어요` : family ? `${who} 이름으로 올렸어요. 최종 수락·거부는 ${who}이 직접 해요` : '공고를 올렸어요');
    nav(`/post/${pf.id}`, { replace: true });
  });
  let firstAI = true;
  const Bot = () => <span className="bot"><Logo /></span>;
  return (
    <Screen title={elderId ? '대신 봉사 요청' : family ? '가족 대신 봉사 요청' : '봉사 요청'} noTabs cls="chatv" bodyRef={bd}>
      <div className="chat-wrap">
        <div className="chat-day"><span>AI가 공고를 정리해요 · 작성 도우미 AI</span></div>
        <div className="chat">
          {!target ? (
            <div className="mrow"><Bot /><div className="mcol wide"><div className="bot-n">작성 도우미</div>
              <div className="msg ai-m">새로운 사람에게 부탁할까요, 자주 만난 사람(단골)에게 부탁할까요?</div>
              <div style={{ marginTop: 10 }}><WhoOpts /></div>
            </div></div>
          ) : <div className="mrow"><Bot /><div className="mcol"><div className="bot-n">작성 도우미</div><div className="msg ai-m">{first}</div></div></div>}
          {familyMode && links !== undefined && !chat.length && (
            <div className="mrow"><Bot /><div className="mcol wide">{!famUser
              ? <div className="notice">연결된 가족이 없어요. <button type="button" className="linkbtn" onClick={() => nav('/my/family')}>가족 연결하러 가기</button></div>
              : linked.length > 1 && <><div className="mute2" style={{ marginBottom: 6 }}>누구 대신 올릴까요?</div><div className="tags">{linked.map((f) => <button key={f.user.id} className={'chip ' + (famUser.id === f.user.id ? 'on' : '')} onClick={() => setFamPick(f.user.id)}>{f.relation ? `${f.relation} ` : ''}{f.user.name}</button>)}</div></>}
            </div></div>
          )}
          {target && !elderId && !chat.length && <div className="mrow"><Bot /><div className="mcol wide"><SuggestLink style={{ marginTop: 0 }} /></div></div>}
          {target && canTidy && !chat.length && (
            <div className="mrow"><Bot /><div className="mcol wide">
              {!tidyOpen ? <button className="btn soft sm" style={{ width: 'auto', padding: '0 14px' }} onClick={() => setTidyOpen(true)}><Spk /> 적어 둔 글을 AI로 정리하기</button> : (
                <div className="tidy-box">
                  <div className="ah2"><Spk />AI로 정리하기<Mod n={1} /></div>
                  <div className="mute2" style={{ margin: '4px 0 8px' }}>{elderId ? '전화로 들은 내용을 편하게 적으면 AI가 공고 칸에 맞게 정리해요. 정리된 내용은 고칠 수 있어요. 전화번호·주소는 지워요.' : '메모처럼 적으면 제목·내용·주의사항으로 다듬어요. 전화번호·주소는 지워요.'}</div>
                  <textarea className="in" value={tidyText} onChange={(e) => setTidyText(e.target.value)} placeholder="예) 어르신 다음 주 화요일 오전 정형외과 재진. 무릎이 안 좋으셔서 천천히 걸어야 함. 택시 같이 타 줄 분" aria-label="정리할 글" />
                  <button className="btn sm" style={{ marginTop: 8 }} disabled={tidyBusy} onClick={runTidy}>{tidyBusy ? '정리하는 중…' : elderId ? 'AI로 공고 정리하기' : 'AI로 정리하기'}</button>
                  {tidyRes && (
                    <div className="ai" style={{ marginTop: 10 }}>
                      <div className="ah"><Spk />이렇게 정리했어요</div>
                      <label className="f" style={{ marginTop: 0 }}>제목</label><input className="in" value={tidyRes.title || ''} onChange={(e) => setTidyRes({ ...tidyRes, title: e.target.value })} />
                      <label className="f">내용</label><textarea className="in" value={tidyRes.detail || ''} onChange={(e) => setTidyRes({ ...tidyRes, detail: e.target.value })} />
                      <label className="f">{elderId ? '관리 대상 이슈 (주의사항)' : '주의사항'}</label><input className="in" value={tidyRes.caution || ''} onChange={(e) => setTidyRes({ ...tidyRes, caution: e.target.value })} placeholder="없으면 비워 두세요" />
                      {(tidyRes.piiRemoved || []).length > 0 && <div className="mute2" style={{ marginTop: 6 }}>개인정보({tidyRes.piiRemoved.map((x) => ({ phone: '전화번호', account: '계좌', address: '주소' }[x] || x)).join('·')})를 지웠어요</div>}
                      <RuleNote source={tidyRes.source} />
                      <button className="btn sm" style={{ marginTop: 10 }} onClick={() => { setTidyFix(tidyRes); if (elderId && tidyRes.caution) setIssue(tidyRes.caution); say(tidyText); }}>이 내용으로 공고 만들기 ›</button>
                    </div>
                  )}
                </div>
              )}
            </div></div>
          )}
          {target && elderId && elder && (
            <div className="mrow"><Bot /><div className="mcol wide"><div className="tidy-box"><IssueField value={issueVal} onChange={setIssue} base={elder.caution} /></div>
              {!chat.length && <button className="btn ghost sm" style={{ width: 'auto', padding: '0 14px', marginTop: 8 }} onClick={() => nav(`/write/need/manual?elder=${elderId}`, { replace: true })}>AI 없이 직접 적기 (수동)</button>}
            </div></div>
          )}
          {chat.map((m, i) => {
            if (m.card === 'block') {
              const f = m.res.filter;
              return <div className="mrow" key={i}><Bot /><div className="mcol wide"><div className="ai warn"><div className="ah"><Spk />이 요청은 보낼 수 없어요 · 불가<Mod n={2} /></div><div className="small">{f.reason}</div>{(f.alternatives || []).length > 0 && <PubCards list={f.alternatives} />}<NearbyPublicBtn f={f} /><RuleNote source={m.res.source} /></div></div></div>;
            }
            if (m.card === 'done') {
              const base = tidyFix ? { ...m.res.draft, title: tidyFix.title || m.res.draft.title, detail: tidyFix.detail || m.res.draft.detail, caution: tidyFix.caution || m.res.draft.caution } : m.res.draft;
              return <div className="mrow" key={i}><Bot /><div className="mcol wide"><DraftCard r={{ ...m.res, draft: mergeChecks(base, chk) }} elderId={elderId} forName={family ? who : null} onReset={reset} onPost={() => publish(m.res)} posting={posting}
                check={<PostCheck draft={base} elderId={elderId} onChange={setChk} />} /></div></div>;
            }
            if (m.role === 'assistant') { firstAI = false; return <div className="mrow" key={i}><Bot /><div className="mcol"><div className="msg ai-m">{m.text}</div></div></div>; }
            return <div className="msg me" key={i}>{m.text}</div>;
          })}
          {busy && <div className="mrow"><Bot /><div className="mcol"><div className="msg ai-m mute2">정리하는 중…</div></div></div>}
        </div>
      </div>
      <div className="chat-dock">
        {target && (!done || blocked) && (
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

function DraftCard({ r, elderId, forName, onReset, onPost, posting, check }) {
  const d = r.draft;
  const df = r.difficulty || {};
  const w = lvWeight(df.level, df.weight);
  const json = JSON.stringify({ type: '봉사 요청 공고', date: d.date, time_range: [hh(d.startHour), hh(d.endHour)], area: d.area, categories: d.categories, title: d.title, detail: d.detail, caution: d.caution, difficulty: df.level, expected_hours: d.estHours, filter: r.filter && r.filter.level }, null, 1);
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
          {forName && <><dt>도움 받는 분</dt><dd>{forName} (최종 수락·거부는 본인이 직접)</dd></>}
        </dl>
        {d.repeatNotice && <div className="notice" style={{ marginTop: 8 }}>{d.repeatNotice}</div>}
        <TBar s={d.startHour} e={d.endHour} />
        <details style={{ marginTop: 10 }}><summary className="mute2" style={{ cursor: 'pointer' }}>AI가 만든 JSON 보기</summary><pre className="json">{json}</pre></details>
        <RuleNote source={r.source} />
      </div>
      <div className="ai" style={{ marginTop: 8 }}>
        <div className="ah"><Spk />난이도·예상 적립<Mod n={2} /></div>
        <div className="between"><span className="small">{d.estHours}시간 × {lvName(df.level)} {w.toFixed(1)}</span><b className="cr plus">+{fmt(df.estEarn != null ? df.estEarn : d.estHours * w)}시간</b></div>
        {df.reason && <div className="small muted">근거: {df.reason}</div>}
        <div className="small muted">금지 요청 확인: {{ ALLOW: '허용 ✓', CAUTION: '주의' }[r.filter && r.filter.level] || '허용 ✓'}{r.filter && r.filter.level === 'CAUTION' && r.filter.reason ? ` (${r.filter.reason})` : ''} · {elderId ? '기관 대상이라 시간 차감 없음' : forName ? `확정되면 ${forName} 통장에서 예정 시간 기준으로 보류, 완료 후 차감` : '확정되면 예정 시간 기준으로 보류, 완료 후 차감'}</div>
      </div>
      {check}
      <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost" onClick={onReset}>다시 쓰기</button><button className="btn" disabled={posting} onClick={onPost}>공고 등록</button></div>
    </>
  );
}
