import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, CatIc, Stars, TBar, Cats, Avatar, StTag, Empty, useLoad, postCats } from '../ui/bits.jsx';
import { useAuth } from '../ui/ctx.jsx';
import { api } from '../api.js';
import { fmt, hh, ap, dlabel, todayISO, SHORT } from '../lib/format.js';
import { Slider, useHomeMode, useTopCats, sortByTop } from '../ui/extras.jsx';
import { BriefingButton } from '../ui/ActivityAi.jsx'; // 7.13 B-1

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

export function EduBanner({ me }) {
  const nav = useNavigate();
  const [edu] = useLoad(() => (me && !me.eduDone ? api('/edu', { quiet: true }) : Promise.resolve(null)), [me && me.eduDone]);
  if (me && me.eduDone) {
    return <div className="edu-ban done" role="button" tabIndex={0} onClick={() => nav('/edu/cert')}><div className="eo"><Ic n="check" s={26} /></div><div className="grow"><div className="ah2" style={{ color: '#1F9D63' }}><Spk />첫 활동 교육</div><div className="b" style={{ fontSize: 16, marginTop: 2 }}>교육을 마쳤어요 · 봉사할 수 있어요</div><div className="mute2">수료증 보기 ›</div></div></div>;
  }
  const st = edu ? (edu.quizScore >= 4 ? 3 : edu.cardsDone ? 2 : edu.diag ? 1 : 0) : 0;
  return <div className="edu-ban" role="button" tabIndex={0} onClick={() => nav('/edu')}><div className="eo"><Ic n="cap" s={28} /></div><div className="grow"><div className="ah2" style={{ color: '#A66F00' }}><Spk />첫 활동 교육 · 필수<span className="new-badge">NEW</span></div><div className="b" style={{ fontSize: 16.5, marginTop: 4 }}>도움을 주기 전에<br />AI 상대와 미리 연습해요</div><div className="mute2" style={{ marginTop: 4 }}>약 25분 · {st ? `${st}/4단계 진행 중` : '아직 시작 안 함'}</div></div>›</div>;
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

/* 응답 필요 카드 한 장 */
function TodoCard({ r }) {
  const nav = useNavigate();
  return (
    <div className="todo" role="button" tabIndex={0} onClick={() => nav(`/req/${r.id}`)}>
      <CatIc k={r.category} size={40} />
      <div className="grow" style={{ minWidth: 0 }}><div className="t">{todoTxt(r)}</div><div className="d" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.myRole === 'VOLUNTEER' ? (r.elder ? `${r.elder.displayName} (기관)` : r.sender && r.sender.name) : r.volunteer && r.volunteer.name} · {r.content}</div></div>
      {r.leftText && r.status !== 'LOG_SUBMITTED' ? <span className="dl">{r.leftText}</span> : <span className="st go">확인</span>}
    </div>
  );
}
/* 다가오는 일정 카드 한 장 */
function UpCard({ r, today }) {
  const nav = useNavigate();
  const isToday = String(r.date).slice(0, 10) === today;
  const k = r.status === 'IN_PROGRESS' ? 1 : r.status === 'CHECKED_OUT' ? 2 : 0;
  const mine = r.myRole === 'VOLUNTEER';
  return (
    <div className="reg click" role="button" tabIndex={0} onClick={() => nav(`/req/${r.id}`)}>
      <div className="between"><span className="st go">{k === 1 ? '활동 중' : k === 2 ? '활동 끝' : isToday ? '오늘' : '일정 확정'} · {mine ? '내가 봉사' : '내가 받음'}</span><span className="mute2">{dlabel(r.date)} {ap(r.startHour)}</span></div>
      <div className="row" style={{ marginTop: 12, gap: 12 }}><CatIc k={r.category} size={44} /><div className="grow" style={{ minWidth: 0 }}><div className="b" style={{ fontSize: 16.5 }}>{r.content}</div><div className="mute2">{r.elder ? `${r.elder.displayName} · ${r.elder.dong} (기관 등록)` : mine ? r.recipient && r.recipient.name : r.volunteer && r.volunteer.name} · {r.hours}시간</div></div>{mine && <span className="pill">+{fmt(r.estEarn)}</span>}</div>
      {mine && (k === 2
        ? <button className="btn sm" style={{ marginTop: 14 }} onClick={(e) => { e.stopPropagation(); nav(`/req/${r.id}/log`); }}><Ic n="pen" s={18} /> 활동 일지 쓰기</button>
        : <button className="btn sm" style={{ marginTop: 14 }} onClick={(e) => { e.stopPropagation(); nav(`/req/${r.id}`); }}><Ic n="qr" s={18} /> {k === 0 ? '도착하면 시작 QR 찍기' : '끝나면 종료 QR 찍기'}</button>)}
      {mine && r.status === 'CONFIRMED' && <BriefingButton id={r.id} />}
    </div>
  );
}

/* U2: 홈 상단 빠른 메뉴(교육은 진행률·미수료 빨간 점) */
function QuickMenu({ me, mode }) {
  const nav = useNavigate();
  const [edu] = useLoad(() => (me && !me.eduDone ? api('/edu', { quiet: true }) : Promise.resolve(null)), [me && me.eduDone]);
  const st = edu ? (edu.quizScore >= 4 ? 3 : edu.cardsDone ? 2 : edu.diag ? 1 : 0) : 0;
  const done = !!(me && me.eduDone);
  const items = [
    [done ? '/edu/cert' : '/edu', done ? '교육 수료' : '첫 활동 교육', 'cap', done ? '#1F9D63' : '#A66F00', done ? null : `${st}/4단계`, !done],
    mode === 'give' ? ['/write/offer', '도움 가능', 'hand', '#3D6DF2'] : ['/write/need', '봉사 요청', 'chat', '#EE8424'],
    ['/regular', '단골에게 요청', 'heart', '#E2557F'],
    ['/public', '공공 서비스', 'building', '#1F9D63'],
    ['/my/alerts', '관심 알림', 'bell', '#6A4FD8'],
  ];
  return (
    <div className="qa qm">
      {items.map(([g, t, i, c, sub, dot]) => (
        <button key={g} onClick={() => nav(g)} aria-label={sub ? `${t} ${sub}` : t}>
          <span style={{ background: `${c}14`, color: c }}><Ic n={i} s={22} />{dot && <i className="qm-dot" aria-hidden="true" />}</span>
          {t}{sub && <small className="qm-sub">{sub}</small>}
        </button>
      ))}
    </div>
  );
}

export function UHome() {
  const nav = useNavigate();
  const { me } = useAuth();
  const [mode, setMode] = useHomeMode();
  const give = mode === 'give';
  const top = useTopCats();
  const [bank] = useLoad(() => api('/bank', { quiet: true }), []);
  const [reqs] = useLoad(() => api('/requests?box=all'), []);
  const [posts] = useLoad(() => api(`/posts?type=${give ? 'NEED' : 'OFFER'}&sort=near`), [give]);
  const [events] = useLoad(() => api('/events', { quiet: true }).catch(() => []), []);
  const list = reqs || [];
  // 고른 쪽(받기 = 내가 받는 요청, 하기 = 내가 봉사하는 요청) 카드가 먼저
  const prefer = (r) => ((r.myRole === 'VOLUNTEER') === give ? 0 : 1);
  const byMode = (a, b) => prefer(a) - prefer(b);
  const need = list.filter(needsMe).sort(byMode);
  const up = list.filter((r) => ['CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT'].includes(r.status)).sort((a, b) => byMode(a, b) || String(a.date).localeCompare(String(b.date)) || a.startHour - b.startHour);
  const today = todayISO();
  const shown = (posts || []).filter((p) => !p.mine && p.status === 'OPEN');
  const near = give ? shown : sortByTop(shown, top);
  const favs = (top && top.top) || [];
  const evs = (events || []).slice(0, 4);
  const bal = me ? me.available ?? me.balance : 0;
  const needSec = (
    <>
      <div className="sec-t"><span>응답이 필요해요 <span style={{ color: '#E5484D' }}>{need.length}</span></span><a role="button" tabIndex={0} onClick={() => nav('/act', { replace: true })}>전체 ›</a></div>
      {reqs && !need.length ? <Empty>지금은 응답할 일이 없어요</Empty> : <Slider label="응답이 필요한 요청">{need.map((r) => <TodoCard key={r.id} r={r} />)}</Slider>}
    </>
  );
  const upSec = up.length > 0 && (
    <>
      <div className="sec-t"><span>{give ? '내 봉사 일정' : '다가오는 일정'} <span className="slide-cnt">{up.length}건</span></span></div>
      <Slider label="다가오는 일정">{up.map((r) => <UpCard key={r.id} r={r} today={today} />)}</Slider>
    </>
  );
  const nearSec = (
    <>
      <div className="sec-t"><span>{give ? '내 근처 도움 요청' : favs.length ? '자주 받는 도움부터 · 봉사 가능 공고' : '내 근처 봉사 가능 공고'}</span><a role="button" tabIndex={0} onClick={() => nav(`/board?type=${give ? 'NEED' : 'OFFER'}`, { replace: true })}>더 보기 ›</a></div>
      <div className="hs">
        {near.map((p) => (
          <div key={p.id} className="nc click" role="button" tabIndex={0} onClick={() => nav(`/post/${p.id}`)}>
            <div className="row" style={{ gap: 8 }}><Avatar name={p.elderDisplayName || p.ownerName} s={36} elder={!!p.elderId} /><div className="grow" style={{ minWidth: 0 }}><div className="b" style={{ fontSize: 14.5, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{give ? p.title : p.ownerName}</div><div className="mute2" style={{ fontSize: 12 }}>{give ? (p.elderDisplayName || p.ownerName) : <Stars r={p.owner && p.owner.ratingAvg} />}{p.distanceKm != null ? ` · ${p.distanceKm}km` : ''}</div></div></div>
            <div className="nc-t" style={{ minHeight: 'auto', marginTop: 12 }}>{dlabel(p.date)} {hh(p.startHour)}~{hh(p.endHour)}</div>
            <TBar s={p.startHour} e={p.endHour} />
            <Cats list={postCats(p)} style={{ marginTop: 10, gap: 4 }} />
          </div>
        ))}
        {posts && !near.length && <Empty>근처에 열린 공고가 없어요</Empty>}
      </div>
    </>
  );
  const favSec = !give && favs.length > 0 && (
    <>
      <div className="sec-t"><span>내가 자주 받는 도움</span></div>
      <div className="fav-cats">{favs.slice(0, 4).map((f) => <button key={f.category} className="fav-cat" onClick={() => nav(`/board?type=OFFER&cat=${encodeURIComponent(f.category)}`)}><CatIc k={f.category} size={34} /><span>{SHORT[f.category] || f.category}<small>{f.count ? `${f.count}번 받았어요` : '자주 찾아요'}</small></span></button>)}</div>
    </>
  );
  const newsSec = evs.length > 0 && (
    <>
      <div className="sec-t"><span>동네 소식</span><a role="button" tabIndex={0} onClick={() => nav('/news', { replace: true })}>전체 ›</a></div>
      <div className="hs">{evs.map((e) => <div key={e.id} className="ev-mini" role="button" tabIndex={0} onClick={() => nav(`/news/${e.id}`)}><div className="mute2" style={{ color: '#6A4FD8', fontWeight: 700 }}>{e.orgName || '기관'}</div><div className="ev-t" style={{ marginTop: 4 }}>{e.title}</div><div className="mute2" style={{ marginTop: 6 }}>{e.date ? dlabel(e.date) : '날짜 미정'}{e.startHour != null ? ` ${ap(e.startHour)}` : ''}</div></div>)}</div>
    </>
  );
  return (
    <Screen title={`안녕하세요, ${me ? me.name : ''} 님`} back={false}>
      <div className="seg home-mode" role="tablist" aria-label="홈 보기">
        <button role="tab" aria-selected={!give} className={!give ? 'on' : ''} onClick={() => setMode('get')}>도움 받기</button>
        <button role="tab" aria-selected={give} className={give ? 'on' : ''} onClick={() => setMode('give')}>봉사하기</button>
      </div>
      <QuickMenu me={me} mode={mode} />
      {!give && <VoiceCard />}
      <div className="hero" style={{ marginTop: 14 }}>
        <div className="bk-top"><span className="bk-name">시간 통장</span><span className="bk-no">{me ? `TS-${String(me.id).padStart(6, '0')}` : ''}</span></div>
        <div className="lbl">쓸 수 있는 시간</div><div className="num">{fmt(bal)}<small>시간</small></div>
        <div className="row3"><div><b>+{fmt(bank ? bank.monthIn : 0)}</b>이번 달 적립</div><div><b>−{fmt(bank ? bank.monthOut : 0)}</b>이번 달 사용</div><div><b>{fmt(me ? me.hold : 0)}</b>보류 중</div></div>
      </div>
      {needSec}
      {upSec}
      {give ? nearSec : <>{favSec}{nearSec}</>}
      {!give && <div className="ai-ban click" role="button" tabIndex={0} onClick={() => nav('/recommend')}><div className="grow"><div className="ah2"><Spk />AI 도움 추천</div><div className="b" style={{ fontSize: 16.5, marginTop: 4 }}>어떤 도움을 받을 수 있을지<br />말로 물어보세요</div><div className="mute2" style={{ marginTop: 4 }}>“무릎이 아파서 장보기가 힘들어요”</div></div><div className="ai-orb"><Ic n="chat" s={28} /></div></div>}
      {newsSec}
      {give && <div style={{ marginTop: 14 }}><VoiceCard /></div>}
    </Screen>
  );
}
export const _st = StTag;
