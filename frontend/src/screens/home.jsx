import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, CatIc, Stars, TBar, Cats, Avatar, StTag, Empty, useLoad, postCats } from '../ui/bits.jsx';
import { useAuth } from '../ui/ctx.jsx';
import { api } from '../api.js';
import { fmt, hh, ap, dlabel, todayISO } from '../lib/format.js';
import { ClsMini } from './cls.jsx';

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

export function UHome() {
  const nav = useNavigate();
  const { me } = useAuth();
  const [bank] = useLoad(() => api('/bank', { quiet: true }), []);
  const [reqs] = useLoad(() => api('/requests?box=all'), []);
  const [posts] = useLoad(() => api('/posts?type=OFFER&sort=near'), []);
  const [classes] = useLoad(() => api('/classes', { quiet: true }), []);
  const list = reqs || [];
  const need = list.filter(needsMe);
  const up = list.filter((r) => ['CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT'].includes(r.status));
  const today = todayISO();
  const offer = (posts || []).filter((p) => !p.mine && p.status === 'OPEN');
  const cls = (classes || []).filter((c) => c.status === 'OPEN').sort((a, b) => ((b.mode === 'ONE') - (a.mode === 'ONE')) || ((b.elderTeacher ? 1 : 0) - (a.elderTeacher ? 1 : 0)));
  const bal = me ? me.available ?? me.balance : 0;
  return (
    <Screen title={`안녕하세요, ${me ? me.name : ''} 님`} back={false}>
      <VoiceCard />
      <div className="hero" style={{ marginTop: 14 }}>
        <div className="bk-top"><span className="bk-name">시간 통장</span><span className="bk-no">{me ? `TS-${String(me.id).padStart(6, '0')}` : ''}</span></div>
        <div className="lbl">쓸 수 있는 시간</div><div className="num">{fmt(bal)}<small>시간</small></div>
        <div className="row3"><div><b>+{fmt(bank ? bank.monthIn : 0)}</b>이번 달 적립</div><div><b>−{fmt(bank ? bank.monthOut : 0)}</b>이번 달 사용</div><div><b>{fmt(me ? me.hold : 0)}</b>보류 중</div></div>
      </div>
      <EduBanner me={me} />
      <div className="sec-t"><span>응답이 필요해요 <span style={{ color: '#E5484D' }}>{need.length}</span></span><a role="button" tabIndex={0} onClick={() => nav('/act', { replace: true })}>전체 ›</a></div>
      {need.slice(0, 3).map((r) => (
        <div key={r.id} className="todo" role="button" tabIndex={0} onClick={() => nav(`/req/${r.id}`)}>
          <CatIc k={r.category} size={40} />
          <div className="grow"><div className="t">{todoTxt(r)}</div><div className="d">{r.myRole === 'VOLUNTEER' ? (r.elder ? `${r.elder.displayName} (기관)` : r.sender && r.sender.name) : r.volunteer && r.volunteer.name} · {r.content}</div></div>
          {r.leftText && r.status !== 'LOG_SUBMITTED' ? <span className="dl">{r.leftText}</span> : <span className="st go">확인</span>}
        </div>
      ))}
      {reqs && !need.length && <Empty>지금은 응답할 일이 없어요</Empty>}
      {up.length > 0 && <div className="sec-t">다가오는 일정</div>}
      {up.map((r) => {
        const isToday = String(r.date).slice(0, 10) === today;
        const k = r.status === 'IN_PROGRESS' ? 1 : r.status === 'CHECKED_OUT' ? 2 : 0;
        const mine = r.myRole === 'VOLUNTEER';
        return (
          <div key={r.id} className="reg click" role="button" tabIndex={0} onClick={() => nav(`/req/${r.id}`)}>
            <div className="between"><span className="st go">{k === 1 ? '활동 중' : k === 2 ? '활동 끝' : isToday ? '오늘' : '일정 확정'}</span><span className="mute2">{dlabel(r.date)} {ap(r.startHour)}</span></div>
            <div className="row" style={{ marginTop: 12, gap: 12 }}><CatIc k={r.category} size={44} /><div className="grow"><div className="b" style={{ fontSize: 16.5 }}>{r.content}</div><div className="mute2">{r.elder ? `${r.elder.displayName} · ${r.elder.dong} (기관 등록)` : mine ? r.recipient && r.recipient.name : r.volunteer && r.volunteer.name} · {r.hours}시간</div></div><span className="pill">+{fmt(r.estEarn)}</span></div>
            {mine && (k === 2
              ? <button className="btn sm" style={{ marginTop: 14 }} onClick={(e) => { e.stopPropagation(); nav(`/req/${r.id}/log`); }}><Ic n="pen" s={18} /> 활동 일지 쓰기</button>
              : <button className="btn sm" style={{ marginTop: 14 }} onClick={(e) => { e.stopPropagation(); nav(`/req/${r.id}`); }}><Ic n="qr" s={18} /> {k === 0 ? '도착하면 시작 QR 찍기' : '끝나면 종료 QR 찍기'}</button>)}
          </div>
        );
      })}
      {cls.length > 0 && <><div className="sec-t"><span>동네 클래스 · 1:1로 배워요</span><a role="button" tabIndex={0} onClick={() => nav('/cls', { replace: true })}>전체 ›</a></div><div className="cl-hs">{cls.map((c) => <ClsMini key={c.id} c={c} />)}</div></>}
      <div className="ai-ban click" role="button" tabIndex={0} onClick={() => nav('/recommend')}><div className="grow"><div className="ah2"><Spk />AI 도움 추천</div><div className="b" style={{ fontSize: 16.5, marginTop: 4 }}>어떤 도움을 받을 수 있을지<br />말로 물어보세요</div><div className="mute2" style={{ marginTop: 4 }}>“무릎이 아파서 장보기가 힘들어요”</div></div><div className="ai-orb"><Ic n="chat" s={28} /></div></div>
      <div className="qa">{[['/write', '공고 올리기', 'plus', '#3D6DF2'], ['/regular', '단골에게 요청', 'heart', '#E2557F'], ['/public', '공공 서비스 안내', 'building', '#1F9D63'], ['/my/alerts', '관심 공고 알림', 'bell', '#EE8424']].map(([g, t, i, c]) => <button key={g} onClick={() => nav(g)}><span style={{ background: `${c}14`, color: c }}><Ic n={i} s={22} /></span>{t}</button>)}</div>
      <div className="sec-t"><span>내 근처 봉사 가능 공고</span><a role="button" tabIndex={0} onClick={() => nav('/board?type=OFFER', { replace: true })}>더 보기 ›</a></div>
      <div className="hs">
        {offer.map((p) => (
          <div key={p.id} className="nc click" role="button" tabIndex={0} onClick={() => nav(`/post/${p.id}`)}>
            <div className="row" style={{ gap: 8 }}><Avatar name={p.ownerName} s={36} /><div className="grow"><div className="b" style={{ fontSize: 14.5 }}>{p.ownerName}</div><div className="mute2" style={{ fontSize: 12 }}><Stars r={p.owner && p.owner.ratingAvg} />{p.distanceKm != null ? ` · ${p.distanceKm}km` : ''}</div></div></div>
            <div className="nc-t" style={{ minHeight: 'auto', marginTop: 12 }}>{dlabel(p.date)} {hh(p.startHour)}~{hh(p.endHour)}</div>
            <TBar s={p.startHour} e={p.endHour} />
            <Cats list={postCats(p)} style={{ marginTop: 10, gap: 4 }} />
          </div>
        ))}
        {posts && !offer.length && <Empty>근처에 열린 공고가 없어요</Empty>}
      </div>
    </Screen>
  );
}
export const _st = StTag;
