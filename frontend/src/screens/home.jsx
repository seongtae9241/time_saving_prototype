import { useEffect, useRef, useState } from 'react';
import { BundleLink } from './bundle.jsx'; // 기능 U-12 빈 시간 묶음 추천
import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, CatIc, Stars, TBar, Cats, Avatar, StTag, Empty, useLoad, postCats } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { fmt, hh, ap, dlabel, todayISO, SHORT } from '../lib/format.js';
import { useTopCats, sortByTop } from '../ui/extras.jsx';
import { DIc, Pav, HmBig, hmTxt, useMode } from '../ui/proto.jsx';
import { I } from '../ui/icons.js';
import { BriefingButton } from '../ui/ActivityAi.jsx'; // 7.13 B-1
import { AskBar } from './assist.jsx';
import { WeekClassCard } from './publicClass.jsx';

/* 응답이 필요한 요청 (프로토타입 needMe) */
export function needsMe(r) {
  const s = r.status, k = r.kind;
  if (r.myRole === 'VOLUNTEER') return (s === 'SENT' && k !== 'PROPOSAL') || s === 'CHECKED_OUT';
  if (r.myRole === 'RECIPIENT' || r.myRole === 'MANAGER') return (s === 'SENT' && k === 'PROPOSAL') || s === 'VOL_ACCEPTED' || s === 'LOG_SUBMITTED' || (r.adjust && r.adjust.status === 'PENDING');
  return false;
}
export function todoTxt(r) {
  const m = r.myRole === 'MANAGER';
  if (r.status === 'VOL_ACCEPTED') return m ? '최종 수락/거부 대리 입력' : '최종 수락/거부';
  if (r.status === 'LOG_SUBMITTED') return m ? '활동 확인·평가 대리' : '활동 확인';
  if (r.status === 'CHECKED_OUT') return '활동 일지 쓰기';
  if (r.adjust && r.adjust.status === 'PENDING') return '시간 조정 확인';
  return r.kind === 'PROPOSAL' ? '제안 수락/거절' : '요청 수락/거절';
}

export function VoiceCard() {
  const nav = useNavigate();
  return (
    <button className="voice-card" onClick={() => nav('/voice')} aria-label="말로 요청하기">
      <span className="vc-mic"><Ic n="mic" s={34} /></span>
      <span className="grow" style={{ textAlign: 'left' }}>
        <span className="vc-t">말로 요청하기</span>
        <span className="vc-d">누르고 말씀하시면 요청서를 만들어 드려요</span>
      </span>
    </button>
  );
}

/* ===== 2026-11-11 역할별 정리판: 홈 (프로토타입 SCREENS.u_home 최종) ===== */
const WD = ['일', '월', '화', '수', '목', '금', '토'];
const KC = { give: '#6DB3A0', recv: '#EE9A7E', ev: '#8DB4F7' };

/** 큰 통장 카드 (프로토타입 bankCard) */
function BankCard({ me }) {
  const nav = useNavigate();
  const bal = me ? me.available ?? me.balance : 0;
  return (
    <div className="pb pbh">
      <div className="pb-top"><b>시간 적금 통장</b><span className="pb-no">{me ? me.accountNo || `TS-${String(me.id).padStart(6, '0')}` : ''}</span><span className="pb-who">예금주 <b>{me ? me.name : ''}</b></span></div>
      <div className="pb-l">쓸 수 있는 시간</div><div className="pb-n"><HmBig v={bal} /></div>
      <div className="pb-btns"><button onClick={() => nav('/bank', { replace: true })}>내역 보기</button><button onClick={() => nav('/gift')}>선물하기</button></div>
    </div>
  );
}

/** 내 다음 일정 (2026-11-13 대표 지시, 프로토타입 nextSec): 헤더 모드에 맞는 확정 일정, 가까운 날짜 2개 (기관 소식은 앱 안 신청이 없어 일정에 넣지 않는다) */
function NextSec({ list, give }) {
  const nav = useNavigate();
  const today = todayISO();
  const rows = list.filter((r) => ['CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT'].includes(r.status) && ((r.myRole === 'VOLUNTEER') === give) && String(r.date).slice(0, 10) >= today)
    .sort((a, b) => String(a.date).localeCompare(String(b.date)) || (a.startHour || 0) - (b.startHour || 0)).slice(0, 2);
  const c = give ? ['#2F8069', '#E2F3EC'] : ['#3A6FD8', '#EDF3FE'];
  const go = () => nav('/planner?seg=cal', { replace: true });
  return (
    <div className="nx">
      <div className="sec-t" style={{ marginTop: 0 }}><span>내 다음 일정</span><a className="sec-lk" role="button" tabIndex={0} onClick={go} onKeyDown={(e) => { if (e.key === 'Enter') go(); }}>플래너 ›</a></div>
      {rows.length ? rows.map((r) => {
        const p = String(r.date).slice(0, 10).split('-');
        const dt = new Date(+p[0], +p[1] - 1, +p[2]);
        const who = give ? (r.elder ? r.elder.displayName : r.recipient && `${r.recipient.name} 님`) : r.volunteer && `${r.volunteer.name} 님`;
        const st = r.status === 'IN_PROGRESS' ? '활동 중' : r.status === 'CHECKED_OUT' ? '일지 쓰기' : give ? '봉사 확정' : '도움 받기 확정';
        return (
          <div key={r.id} className="nx-card" role="button" tabIndex={0} style={{ '--nxc': c[0], '--nxs': c[1] }} onClick={() => nav(`/req/${r.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/req/${r.id}`); }}>
            <div className="nx-d"><b>{+p[2]}</b><span>{WD[dt.getDay()]}요일</span></div>
            <div className="grow" style={{ minWidth: 0 }}><div className="nx-tag">{st} · {hh(r.startHour)}</div><div className="b">{r.content}</div><div className="mute2">{who || ''}{who ? ' · ' : ''}{give ? `+${hmTxt(r.estEarn)}` : `−${hmTxt(r.hours)}`}</div></div>›
          </div>
        );
      }) : <div className="nx-empty">{give ? '확정된 봉사 일정이 없어요' : '확정된 도움 받기 일정이 없어요'}</div>}
      {/* 7.13 B-1 방문 전 브리핑: 가장 가까운 봉사 일정 하나 */}
      {give && rows[0] && rows[0].status === 'CONFIRMED' && <BriefingButton id={rows[0].id} />}
    </div>
  );
}

/** 도움 받기 홈의 AI 도움 찾기 (프로토타입 aisrch): 적은 문장으로 AI 추천 화면(/recommend)을 연다. 빠른 메뉴 'AI 추천' 자리를 대신한다 */
const HQ = ['무릎이 아파서 병원 가기가 힘들어요', '폰으로 손주랑 영상통화 하고 싶어요', '혼자 장보기가 버거워요'];
function AiSearch() {
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const ask = (t) => nav(`/recommend?q=${encodeURIComponent(t)}`);
  return (
    <div className="aisrch-wrap">
      <h3>어떤 도움이 필요하신가요?</h3>
      <p>편하게 적으면 AI가 받을 수 있는 도움과 맞는 공고를 찾아 드려요</p>
      <form className="aisrch" onSubmit={(e) => { e.preventDefault(); const v = q.trim(); if (v) ask(v); }}>
        <span aria-hidden="true">✨</span><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="예) 무릎이 아파서 병원 가기가 힘들어요" aria-label="필요한 도움 적기" autoComplete="off" /><button type="submit" aria-label="AI에게 묻기"><Ic h={I.send} /></button>
      </form>
      <div className="aisrch-q">{HQ.map((t) => <button key={t} type="button" onClick={() => ask(t)}>{t}</button>)}</div>
    </div>
  );
}

/** 나를 기다리는 요청: 폴더 탭 (프로토타입 wSec·hcard) */
function wType(r) {
  if (r.status === 'LOG_SUBMITTED' || r.status === 'CHECKED_OUT' || (r.adjust && r.adjust.status === 'PENDING')) return 'done';
  if (r.status === 'VOL_ACCEPTED') return 'acc';
  if (r.myRole !== 'VOLUNTEER' && r.kind === 'PROPOSAL') return 'offer';
  return r.elder ? 'org' : 'ask';
}
const W_L = { acc: '수락됨', offer: '받은 제안', done: '일지', ask: '받은 요청', org: '기관 요청' };
const W_T = { acc: '최종 확정', offer: '수락 / 거절', ask: '수락 / 거절', org: '수락 / 거절' };
function WaitBox({ need, give }) {
  const nav = useNavigate();
  const order = give ? ['ask', 'org', 'done'] : ['acc', 'offer', 'done'];
  const g = {};
  need.forEach((r) => { const t = wType(r); (g[t] = g[t] || []).push(r); });
  const ks = order.filter((k) => g[k]);
  const [tab, setTab] = useState(null);
  const cur = ks.includes(tab) ? tab : ks[0];
  if (!need.length) return <div className="empty" style={{ padding: '18px 0' }}>지금은 기다리는 요청이 없어요</div>;
  return (
    <div className={`wfold f-${cur}`}>
      <div className="wtabs" role="tablist" aria-label="나를 기다리는 요청">{ks.map((k) => <button key={k} role="tab" aria-selected={k === cur} className={`wt t-${k} ${k === cur ? 'on' : ''}`} onClick={() => setTab(k)}>{k === 'done' ? (give ? '일지 쓰기' : '일지 확인') : W_L[k]} <b>{g[k].length}</b></button>)}</div>
      <div className="wpanel"><div className="hs hs2 wrow">
        {g[cur].map((r) => {
          const who = r.myRole === 'VOLUNTEER' ? (r.elder ? r.elder.displayName : (r.recipient && r.recipient.name) || (r.sender && r.sender.name) || '') : (r.volunteer && r.volunteer.name) || '';
          return (
            <div key={r.id} className={`wcard t-${cur} click`} role="button" tabIndex={0} onClick={() => nav(`/req/${r.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/req/${r.id}`); }}>
              <div className="w-top"><Pav name={who} s={26} /><div className="w-nm">{who}{r.elder ? '' : ' 님'}{r.leftText && ['SENT', 'VOL_ACCEPTED'].includes(r.status) && <> · <em className="w-dl2">{r.leftText} 남음</em></>}</div></div>
              <div className="w-ttl">{r.content}</div>
              <div className="w-f"><span>{dlabel(r.date)} {hh(r.startHour)}</span><b>{cur === 'done' ? todoTxt(r) : W_T[cur]} ›</b></div>
            </div>
          );
        })}
      </div></div>
    </div>
  );
}

/** 이웃 카드 (프로토타입 nCard) */
export function NCard({ p }) {
  const nav = useNavigate();
  const off = p.type === 'OFFER';
  const name = p.elderDisplayName || p.ownerName || '이웃';
  return (
    <div className="ncard3 click" role="button" tabIndex={0} onClick={() => nav(`/post/${p.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/post/${p.id}`); }}>
      <div className="n3-top"><Pav name={name} s={38} /><div className="grow" style={{ minWidth: 0 }}><div className="n3-name">{name}</div><div className="n3-sub">{p.area}{p.distanceKm != null ? ` · ${p.distanceKm}km` : ''}</div></div>{!off && p.estEarn != null && <span className="n3-h">+{hmTxt(p.estEarn)}</span>}</div>
      <div className="n3-note">{off ? (p.note || p.title) : p.title}</div>
      <div className="n3-f"><span className="n3-tag">{postCats(p)[0]}</span><span>{dlabel(p.date)} {hh(p.startHour)}</span></div>
    </div>
  );
}

/** 공고 화면 세 탭에 공통으로 두는 "내가 도와줄 수 있는 이웃"(2026-11-13 대표 지시): 가까운 봉사 요청 공고를 가로로 넘겨 본다 */
export function HelpableStrip() {
  const nav = useNavigate();
  const [posts] = useLoad(() => api('/posts?type=NEED&sort=near', { quiet: true }).catch(() => []), []);
  const near = (posts || []).filter((p) => !p.mine && !p.proxyMine && (p.status || 'OPEN') === 'OPEN').slice(0, 10);
  if (!near.length) return null;
  const all = () => nav('/board?type=NEED', { replace: true });
  return (
    <>
      <div className="sec-t"><span>내가 도와줄 수 있는 이웃</span><span className="sec-r"><a role="button" tabIndex={0} onClick={all} onKeyDown={(e) => { if (e.key === 'Enter') all(); }}>전체 ›</a></span></div>
      <div className="npanel"><div className="hs hs2">{near.map((p) => <NCard key={p.id} p={p} />)}</div></div>
    </>
  );
}

/** 기관 소식 카드 색·그림: 제목에서 어림(행사에는 카테고리가 없다) */
const EV_C = [['#EAF0FF', '#5B8DEF'], ['#FFEDF3', '#E2557F'], ['#E6F7EE', '#1F9D63'], ['#FFF6DD', '#B98516'], ['#FFEFE7', '#D9775A']];
export function evLook(e) {
  const t = `${e.title || ''} ${e.content || ''}`;
  const ic = /폰|스마트|디지털|키오스크|사진|컴퓨터/.test(t) ? '디지털 도움' : /산책|걷|말벗|읽어|이야기|노래|모임/.test(t) ? '말동무·정서' : /검진|병원|건강|치매|접종/.test(t) ? '동행' : /김장|나눔|장터|반찬/.test(t) ? '장보기·심부름' : /정리|청소|수리/.test(t) ? '생활 정리' : 'news';
  return { ic, c: EV_C[(Number(e.id) || 0) % EV_C.length] };
}
export function EvCard2({ e }) {
  const nav = useNavigate();
  const { ic, c } = evLook(e);
  return (
    <div className="evc2 click" role="button" tabIndex={0} style={{ '--ec': c[0], '--ek': c[1] }} onClick={() => nav(`/news/${e.id}`)} onKeyDown={(ev) => { if (ev.key === 'Enter') nav(`/news/${e.id}`); }}>
      <div className="e2-top"><span className="e2-org">{e.orgName || '기관'}</span><span className="e2-ic"><DIc k={ic} size={46} /></span></div>
      <div className="e2-t">{e.title}</div><div className="e2-d">{e.date ? dlabel(e.date) : '날짜 미정'}{e.startHour != null ? ` ${ap(e.startHour)}` : ''}{e.area ? ` · ${e.area}` : ''}</div>
      <div className="e2-f"><span className="e2-left">{e.capacity > 0 ? `정원 ${e.capacity}명` : '누구나'}</span><b>자세히 ›</b></div>
    </div>
  );
}
/** 기관 소식 가로 슬라이드: 5초마다 넘어가고(만지면 8초 쉼), 움직임 줄이기 설정이면 자동으로 넘기지 않는다 */
function EvSlider({ events }) {
  const ref = useRef(null);
  const hold = useRef(0);
  const [ix, setIx] = useState(0);
  const idx = () => { const s = ref.current; if (!s) return 0; const c = [...s.children]; let k = 0; c.forEach((el, i) => { if (Math.abs(el.offsetLeft - c[0].offsetLeft - s.scrollLeft) < Math.abs(c[k].offsetLeft - c[0].offsetLeft - s.scrollLeft)) k = i; }); return s.scrollLeft + s.clientWidth >= s.scrollWidth - 4 ? c.length - 1 : k; };
  useEffect(() => {
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return undefined;
    const t = setInterval(() => {
      const s = ref.current;
      if (!s || Date.now() - hold.current < 8000 || s.matches(':hover') || s.children.length < 2) return;
      const c = [...s.children]; let k = idx() + 1; if (k >= c.length) k = 0;
      s.scrollTo({ left: c[k].offsetLeft - c[0].offsetLeft, behavior: 'smooth' });
    }, 5000);
    return () => clearInterval(t);
  }, []); // eslint-disable-line
  const touch = () => { hold.current = Date.now(); };
  return (
    <>
      <div className="hs hs2 ev-auto" ref={ref} onScroll={() => setIx(idx())} onPointerDown={touch} onTouchStart={touch} onWheel={touch}>{events.map((e) => <EvCard2 key={e.id} e={e} />)}</div>
      <div className="ev-dots" aria-hidden="true">{events.map((e, i) => <i key={e.id} className={i === ix ? 'on' : ''} />)}</div>
    </>
  );
}

/** 추천 봉사 (B-60, 프로토타입 2026-11-12): 내 지역·시간대·가능한 도움에 맞는 봉사 3건. 골라서 "계획 만들기" → 플래너(B-61) */
function RecBox() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [d, reload] = useLoad(() => api('/me/recommends', { quiet: true }).catch(() => null), []);
  const [picked, setPicked] = useState([]);
  const [busy, setBusy] = useState(false);
  const recs = ((d && d.recommends) || []).slice(0, 3);
  const build = async () => {
    if (!picked.length || busy) return;
    setBusy(true);
    try {
      const r = await post('/me/planner/picks', { postIds: picked });
      const made = r.made || [], skipped = r.skipped || [];
      setPicked([]);
      toast(made.length ? `${made.map((m) => `${dlabel(m.date)} ${hh(m.startHour)}`).join(', ')}에 계획을 넣었어요${skipped.length ? ` · ${skipped.length}건은 빈 시간이 없어요` : ''}` : '빈 시간이 없어서 계획에 넣지 못했어요');
      if (made.length) nav('/planner?seg=cal', { replace: true }); else reload();
    } catch { /* 토스트 */ }
    setBusy(false);
  };
  return (
    <>
      <div className="sec-t"><span>추천 봉사</span><span className="sec-r"><a role="button" tabIndex={0} onClick={() => nav('/planner', { replace: true })} onKeyDown={(e) => { if (e.key === 'Enter') nav('/planner', { replace: true }); }}>플래너 ›</a></span></div>
      <div className="mute2" style={{ margin: '-4px 0 6px' }}>{(d && d.basisText) || '내 지역·시간대·가능한 도움에 맞는 봉사예요.'} 골라서 플래너에 넣어요</div>
      {d === undefined ? null : recs.length ? (
        <>
          <div className="recs">
            {recs.map((r) => {
              const on = picked.includes(r.id);
              return (
                <button key={r.id} className={'rec ' + (on ? 'on' : '')} aria-pressed={on} onClick={() => setPicked(on ? picked.filter((x) => x !== r.id) : [...picked, r.id])}>
                  <DIc k={(r.categories || [])[0]} size={46} />
                  <div className="grow" style={{ minWidth: 0 }}><div className="b">{r.title}</div><div className="mute2">{r.ownerName} · {r.distanceKm}km · {dlabel(r.date)} {hh(r.startHour)}~{hh(r.endHour)}</div>{r.why && <div className="rec-why"><Spk /> {r.why}</div>}</div>
                  <div className="rec-r"><span className="pill">+{fmt(r.estEarn)}</span><span className="rec-ck">{on ? '✓' : ''}</span></div>
                </button>
              );
            })}
          </div>
          <button className="ai-fill" disabled={!picked.length || busy} style={picked.length ? undefined : { opacity: 0.5 }} onClick={build}><DIc k="ai" size={40} bare /><div className="grow" style={{ textAlign: 'left' }}><b>{busy ? '계획을 만드는 중…' : picked.length ? `선택한 ${picked.length}개로 계획 만들기` : '추천 봉사를 골라 주세요'}</b><span>AI가 내 빈 시간에 맞춰 날짜·시간을 정해 플래너에 넣어요</span></div>›</button>
        </>
      ) : <div className="empty" style={{ padding: '14px 0' }}>지금은 내 조건에 맞는 추천 봉사가 없어요{d && d.planCount ? ` · 플래너에 계획 ${d.planCount}개` : ''}</div>}
    </>
  );
}

export function UHome() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { give } = useMode();
  const top = useTopCats();
  const [reqs] = useLoad(() => api('/requests?box=all'), []);
  const [posts] = useLoad(() => api(`/posts?type=${give ? 'NEED' : 'OFFER'}&sort=near`), [give]);
  const [events] = useLoad(() => api('/events?nearby=true', { quiet: true }).catch(() => []), []); // 기능 U-31: 내 동네 근처 기관 행사만
  const list = reqs || [];
  const need = list.filter(needsMe).filter((r) => (r.myRole === 'VOLUNTEER') === give);
  const shown = (posts || []).filter((p) => !p.mine && p.status === 'OPEN');
  const near = (give ? shown : sortByTop(shown, top)).slice(0, 10);
  const evs = (events || []).slice(0, 5);
  const todo = (to, ic, t, d2, st) => <div className="todo" role="button" tabIndex={0} onClick={() => nav(to)} onKeyDown={(e) => { if (e.key === 'Enter') nav(to); }}><DIc k={ic} size={44} /><div className="grow"><div className="t">{t}</div><div className="d">{d2}</div></div><span className="st go">{st}</span></div>;
  const boardTo = `/board?type=${give ? 'NEED' : 'OFFER'}`;
  return (
    <Screen title={<span className="hlogo"><i><Ic h={I.logo} /></i>시간 적금</span>} back={false}>
      {/* 2026-11-13 대표 지시: 시간 통장 요약 → 내 다음 일정 → (모드별) → 이웃. 작은 사각형 빠른 메뉴는 두 모드 모두 없앴다.
          옮긴 진입점: 공고 올리기 = 공고 화면, 단골 = 마이 · 단골 관리, 공공 서비스 = 마이 · 플래너 AI 도움 추천, AI 추천 = 아래 "어떤 도움이 필요하신가요?" */}
      <BankCard me={me} />
      <NextSec list={list} give={give} />
      {give ? (
        <>
          {/* 봉사하기: "도움 가능"은 하나의 메뉴(프로토타입 givecta) */}
          <button className="givecta" onClick={() => nav('/write/offer')}><DIc k="offer" size={48} /><div><b>도움 가능</b><span>내가 도울 수 있는 시간을 공고로 올려요</span></div><span className="gc-ar" aria-hidden="true">›</span></button>
          <a className="givelink" role="button" tabIndex={0} onClick={() => nav(boardTo, { replace: true })} onKeyDown={(e) => { if (e.key === 'Enter') nav(boardTo, { replace: true }); }}>도움이 필요한 이웃 공고 보기 ›</a>
        </>
      ) : <AiSearch />}
      {reqs === undefined ? null : <WaitBox need={need} give={give} />}
      {give && <RecBox />}
      {give && <BundleLink />}
      <div className="sec-t"><span>{give ? '도움이 필요한 이웃' : '도와줄 수 있는 이웃'}</span><span className="sec-r"><a role="button" tabIndex={0} onClick={() => nav(boardTo, { replace: true })} onKeyDown={(e) => { if (e.key === 'Enter') nav(boardTo, { replace: true }); }}>전체 ›</a></span></div>
      <div className="npanel"><div className="hs hs2">{near.map((p) => <NCard key={p.id} p={p} />)}{posts && !near.length && <Empty>근처에 열린 공고가 없어요</Empty>}</div></div>
      {evs.length > 0 && (
        <>
          <div className="sec-t"><span>동네 기관 소식 <span className="mute2" style={{ fontWeight: 500 }}>{me && me.area ? `${me.area} 근처` : '내 동네 근처'}</span></span><span className="sec-r"><a role="button" tabIndex={0} onClick={() => nav('/news')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/news'); }}>전체 ›</a></span></div>
          <EvSlider events={evs} />
        </>
      )}
      {/* 봉사하기: 봉사 확인서는 아래쪽에 하나의 메뉴로(2026-11-13 대표 지시) */}
      {give && <><div className="sec-t"><span>봉사 확인서</span></div>{todo('/my/cert', 'cert', '봉사 확인서', '기간을 골라 봉사 활동 확인서를 받아요', '발급')}</>}
      {/* 프로토타입에 자리가 없는 기존 기능: 물어보기 · 말로 요청하기 · 이번 주 기관 클래스 */}
      <div className="sec-t"><span>말로 편하게</span></div>
      <AskBar />
      {!give && <VoiceCard />}
      <WeekClassCard />
    </Screen>
  );
}
export const _st = StTag;
