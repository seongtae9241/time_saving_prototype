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
const dayTxt = (d) => { const p = String(d).slice(0, 10).split('-'); const dt = new Date(+p[0], +p[1] - 1, +p[2]); return `${+p[1]}월 ${+p[2]}일 (${WD[dt.getDay()]})`; };
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

/** 내 일정 (프로토타입 planCard): 헤더 모드에 맞는 확정 일정, 가까운 날짜 3개 (기관 소식은 앱 안 신청이 없어 일정에 넣지 않는다) */
function PlanCard({ list, give }) {
  const nav = useNavigate();
  const today = todayISO();
  const up = list.filter((r) => ['CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT'].includes(r.status) && ((r.myRole === 'VOLUNTEER') === give) && String(r.date).slice(0, 10) >= today)
    .map((r) => ({ k: give ? 'give' : 'recv', cat: r.category, t: r.content, w: give ? (r.elder ? r.elder.displayName : r.recipient && `${r.recipient.name} 님`) : r.volunteer && `${r.volunteer.name} 님`, d: String(r.date).slice(0, 10), h: r.startHour, v: give ? `+${hmTxt(r.estEarn)}` : `−${hmTxt(r.hours)}`, to: `/req/${r.id}`, r }));
  const rows = up.sort((a, b) => a.d.localeCompare(b.d) || (a.h || 0) - (b.h || 0)).slice(0, 3);
  return (
    <>
      <div className="pc-up"><span>내 일정</span><a role="button" tabIndex={0} style={{ marginLeft: 'auto', fontSize: 13, fontWeight: 600, color: 'var(--sub)' }} onClick={() => nav('/planner')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/planner'); }}>달력 ›</a></div>
      {rows.length ? (
        <div className="tline pc-tl">
          {rows.map((x, i) => (
            <div key={i} className="tl2 click" role="button" tabIndex={0} onClick={() => nav(x.to)} onKeyDown={(e) => { if (e.key === 'Enter') nav(x.to); }}>
              <span className="tl2-dot" style={{ borderColor: KC[x.k] }} />
              <div className="tl2-card">
                <DIc k={x.cat} size={44} />
                <div className="grow" style={{ minWidth: 0 }}><div className="b">{x.t}</div><div className="mute2">{x.w} · {dayTxt(x.d)}</div></div>
                <div className="tl2-r"><b>{x.h != null ? hh(x.h) : ''}</b><span style={{ color: KC[x.k] }}>{x.r && x.r.status === 'IN_PROGRESS' ? '활동 중' : x.r && x.r.status === 'CHECKED_OUT' ? '일지 쓰기' : x.v}</span></div>
              </div>
            </div>
          ))}
        </div>
      ) : <div className="empty" style={{ padding: '18px 0' }}>확정된 {give ? '봉사' : '도움'} 일정이 없어요</div>}
      {/* 7.13 B-1 방문 전 브리핑: 가장 가까운 봉사 일정 하나 */}
      {give && rows[0] && rows[0].r && rows[0].r.status === 'CONFIRMED' && <BriefingButton id={rows[0].r.id} />}
    </>
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
function NCard({ p }) {
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
  const qa = give
    ? [['/write/offer', '도움 가능', 'offer'], ['/regular', '단골 관리', 'regular'], ['/edu', '첫 활동 연습', 'edu'], ['/my/cert', '봉사 확인서', 'cert'], ['/planner', '플래너', 'cal']]
    : [['/write/need', '봉사 요청', 'req'], ['/regular', '단골 요청', 'regular'], ['/public', '공공 서비스', 'public'], ['/recommend', 'AI 추천', 'ai'], ['/planner', '플래너', 'cal']];
  return (
    <Screen title={<span className="hlogo"><i><Ic h={I.logo} /></i>시간 적금</span>} back={false}>
      <BankCard me={me} />
      <PlanCard list={list} give={give} />
      <div className="qa" style={{ marginTop: 18 }}>
        {qa.map(([g, t, i, sub]) => <button key={t} onClick={() => nav(g)} aria-label={sub ? `${t} ${sub}` : t}><DIc k={i} size={56} />{t}{sub && <small className="qm-sub">{sub}</small>}</button>)}
      </div>
      {reqs === undefined ? null : <WaitBox need={need} give={give} />}
      {give && <RecBox />}
      {give && <BundleLink />}
      <div className="sec-t"><span>{give ? '도움이 필요한 이웃' : '도와줄 수 있는 이웃'}</span><span className="sec-r"><a role="button" tabIndex={0} onClick={() => nav(`/board?type=${give ? 'NEED' : 'OFFER'}`, { replace: true })} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/board?type=${give ? 'NEED' : 'OFFER'}`, { replace: true }); }}>전체 ›</a></span></div>
      <div className="npanel"><div className="hs hs2">{near.map((p) => <NCard key={p.id} p={p} />)}{posts && !near.length && <Empty>근처에 열린 공고가 없어요</Empty>}</div></div>
      {evs.length > 0 && (
        <>
          <div className="sec-t"><span>동네 기관 소식 <span className="mute2" style={{ fontWeight: 500 }}>{me && me.area ? `${me.area} 근처` : '내 동네 근처'}</span></span><span className="sec-r"><a role="button" tabIndex={0} onClick={() => nav('/news')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/news'); }}>전체 ›</a></span></div>
          <EvSlider events={evs} />
        </>
      )}
      {/* 프로토타입에 자리가 없는 기존 기능: 물어보기 · 말로 요청하기 · 이번 주 기관 클래스 */}
      <div className="sec-t"><span>말로 편하게</span></div>
      <AskBar />
      {!give && <VoiceCard />}
      <WeekClassCard />
    </Screen>
  );
}
export const _st = StTag;
