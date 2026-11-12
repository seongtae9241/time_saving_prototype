import { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, TBar, Cats, PostCard, ReqRow, Avatar, Stars, StTag, Loading, Empty, RuleNote, useLoad, postWho, postCats } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, del } from '../api.js';
import { CATS, SHORT, AREAS, KIND_LABEL, W, fmt, hh, dlabel, lvName, lvWeight, todayISO, addDays } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { useReport } from './common.jsx';
import { useProfileSheet } from './request.jsx';
import { useTopCats, sortByTop, RegularButton } from '../ui/extras.jsx';
import { MatchWhy, EasyListen } from '../ui/assist.jsx';
import { FixTips } from './assist.jsx';
import { DIc, useMode } from '../ui/proto.jsx';
import { useLocationAsk } from '../ui/location.jsx';
import { useRepost } from '../ui/repost.jsx';
import { BundleLink } from './bundle.jsx';

const DATES = [['all', '전체 날짜'], ['today', '오늘'], ['week', '이번 주'], ['next', '다음 주']]; // + 날짜 고르기(pick)
const SLOTS = [['all', '시간대'], ['am', '오전'], ['pm', '오후'], ['eve', '저녁']];

/* ---------- 공고 게시판 (B-20) · 담당자 대리 요청(C-05) ---------- */
/* 정렬 칩(가까운 순 ⇅)은 줄바꿈되지 않게(프로토타입처럼 한 줄) */
const NW = { whiteSpace: 'nowrap' };

export function Board() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { sheet, closeSheet } = useUi();
  const [sp, setSp] = useSearchParams();
  const elderId = sp.get('elder');
  const { mode, setMode } = useMode();
  const isUser = !!(me && me.role === 'USER');
  // 2026-11-11: 주소에 type 이 없으면 헤더 모드를 따른다(도움 받기 → 도움 가능 공고, 봉사하기 → 봉사 요청 공고)
  const type = elderId ? 'OFFER' : sp.get('type') || (isUser && mode === 'give' ? 'NEED' : 'OFFER');
  // 프로토타입 render(): 공고 탭에서는 보는 공고 종류에 따라 화면 색이 바뀐다
  useEffect(() => {
    if (!isUser || elderId) return undefined;
    document.body.dataset.mode = type === 'OFFER' ? 'recv' : 'give';
    return () => { document.body.dataset.mode = mode; };
  }, [type, isUser, elderId, mode]);
  const pickType = (t) => { setSp({ type: t }, { replace: true }); setCat('전체'); if (isUser) setMode(t === 'OFFER' ? 'recv' : 'give'); };
  const [cat, setCat] = useState(sp.get('cat') || '전체');
  const top = useTopCats();
  // B-20·B-59: 가까운 순은 위치 정보를 쓴다. 처음에는 최신순이고, 가까운 순을 누르는 순간에만 위치를 묻는다(좌표는 화면 안에서만 쓰고 저장하지 않음)
  const [sort, setSort] = useState('new');
  const [pos, setPos] = useState(null);
  const askLocation = useLocationAsk();
  // 기능 U-02: 정렬은 최신순 → 가까운 순(거리순) → 시간순(활동 날짜·시작 시각이 빠른 순) 차례로 바뀐다
  const SORT_L = { new: '최신순', near: '가까운 순', time: '시간순' };
  const toggleSort = async () => {
    if (sort === 'near') return setSort('time');
    if (sort === 'time') return setSort('new');
    const got = await askLocation('가까운 순 정렬');
    if (!got) return setSort('time'); // 위치를 못 얻으면 거리순은 건너뛰고 시간순
    setPos(got); setSort('near');
  };
  const [area, setArea] = useState('');
  const [dt, setDt] = useState('all');
  const [pickDate, setPickDate] = useState('');
  const [slot, setSlot] = useState('all');
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  const q = new URLSearchParams({ type, sort });
  if (area) q.set('area', area);
  if (cat !== '전체') q.set('cat', cat);
  if (dt === 'today') q.set('date', todayISO());
  if (dt === 'pick' && pickDate) q.set('date', pickDate);
  if (dt === 'week') { q.set('dateFrom', todayISO()); q.set('dateTo', addDays(6)); }
  if (dt === 'next') { q.set('dateFrom', addDays(7)); q.set('dateTo', addDays(13)); }
  if (sort === 'near' && pos) { q.set('lat', pos.lat); q.set('lng', pos.lng); }
  const [list] = useLoad(() => api(`/posts?${q}`), [q.toString()]);
  let shown = (list || []).filter((p) => !p.mine && !p.proxyMine && (p.status || 'OPEN') === 'OPEN');
  if (slot !== 'all') shown = shown.filter((p) => (slot === 'am' ? p.startHour < 12 : slot === 'pm' ? p.endHour > 12 && p.startHour < 18 : p.endHour > 18));
  // U3: 도움 받고 싶을 때(도움 가능 공고)는 "내가 자주 받는 도움" 공고가 먼저, 칩도 그 순서로
  const favSet = new Set(((top && top.top) || []).map((t) => t.category));
  if (type === 'OFFER') shown = sortByTop(shown, top);
  const catOrder = type === 'OFFER' && top && top.order ? [...top.order.filter((c) => favSet.has(c)), ...CATS.map((c) => c.k).filter((c) => !favSet.has(c))] : CATS.map((c) => c.k);
  const pick = (title, opts, val, set) => sheet(() => (
    <>
      <h2>{title}</h2>
      {opts.map(([v, l]) => <div key={v} className={'opt ' + (val === v ? 'on' : '')} role="button" tabIndex={0} onClick={() => { set(v); closeSheet(); }}><span className="rd" /><div>{l}</div></div>)}
    </>
  ));
  // B-20 날짜 필터: 묶음(오늘·이번 주·다음 주) 말고 특정 날짜도 고를 수 있다
  const pickDay = () => sheet(() => (
    <>
      <h2>날짜</h2>
      {DATES.map(([v, l]) => <div key={v} className={'opt ' + (dt === v ? 'on' : '')} role="button" tabIndex={0} onClick={() => { setDt(v); closeSheet(); }}><span className="rd" /><div>{l}</div></div>)}
      <label className="f" htmlFor="board-date">날짜 고르기</label>
      <input id="board-date" className="in" type="date" min={todayISO()} defaultValue={dt === 'pick' ? pickDate : ''} onChange={(e) => { if (e.target.value) { setPickDate(e.target.value); setDt('pick'); closeSheet(); } }} />
    </>
  ));
  const dtLabel = dt === 'pick' && pickDate ? dlabel(pickDate) : (DATES.find((d) => d[0] === dt) || DATES[0])[1];
  const eName = elder ? elder.alias + ' 님' : '';
  return (
    <Screen title={elderId ? `${eName} 대신 요청` : '공고'} tab={me && me.role === 'ORG' ? '/o/elders' : '/board'} noTabs={!!elderId} back={elderId ? true : false}>
      {elderId ? (
        <><div className="lock"><Ic n="users" s={18} /><div><b>{eName}</b>({elder ? elder.dong : ''}) 대신 요청할 도움 가능 공고를 골라요. 대상 시간은 차감되지 않아요.</div></div><div style={{ height: 12 }} /></>
      ) : (
        <div className="bbtn" role="tablist" aria-label="공고 종류">
          <button role="tab" aria-selected={type === 'OFFER'} className={type === 'OFFER' ? 'on' : ''} onClick={() => pickType('OFFER')}><DIc k="recv" size={44} /><b>도움 받기</b><em>도움 가능 공고</em></button>
          <button role="tab" aria-selected={type === 'NEED'} className={type === 'NEED' ? 'on' : ''} onClick={() => pickType('NEED')}><DIc k="offer" size={44} /><b>봉사하기</b><em>봉사 요청 공고</em></button>
        </div>
      )}
      <div className="row" style={{ gap: 6, marginBottom: 8 }}>
        <button className="tag2" onClick={() => pick('지역', [['', '행복구 전체'], ...AREAS.map((a) => [a, a])], area, setArea)}><Ic n="pin" s={14} />{area || '행복구 전체'} <Ic h={I.down} /></button>
        <button className="tag2" onClick={pickDay}>{dtLabel} <Ic h={I.down} /></button>
        <button className="tag2" onClick={() => pick('시간대', SLOTS.map(([v, l]) => [v, v === 'all' ? '전체 시간대' : l]), slot, setSlot)}>{SLOTS.find((d) => d[0] === slot)[1]} <Ic h={I.down} /></button>
        <span className="grow" />
        <button className="tag2" style={NW} onClick={toggleSort} aria-label={sort === 'near' ? '지금 가까운 순. 누르면 시간순' : sort === 'time' ? '지금 시간순(활동 시간이 빠른 순). 누르면 최신순' : '지금 최신순. 누르면 가까운 순(위치 정보 이용)'}>{SORT_L[sort]} ⇅</button>
      </div>
      {sort === 'near' && pos && <div className="mute2" style={{ margin: '-2px 0 8px' }}><Ic n="pin" s={13} /> 지금 계신 곳에서 가까운 순이에요. 위치는 이번 정렬에만 쓰고 저장하지 않아요.</div>}
      <div className="chips">{['전체', ...catOrder].map((k) => <button key={k} className={'chip ' + (k === cat ? 'on' : '')} onClick={() => setCat(k)}>{type === 'OFFER' && favSet.has(k) && <span className="fav-mark" aria-label="자주 받는 도움">★</span>}{k === '전체' ? '전체' : SHORT[k]}</button>)}</div>
      {!elderId && me && me.role === 'USER' && <div className="tip" style={{ marginTop: 12, padding: '12px 16px' }}><div className="grow small"><Ic n="bell" s={16} /> 조건에 맞는 공고가 올라오면 알려드려요</div><button className="btn soft sm" style={{ width: 'auto', padding: '0 12px', minHeight: 36 }} onClick={() => nav('/my/alerts')}>관심 조건</button></div>}
      {sort === 'time' && <div className="mute2" style={{ margin: '8px 0 0' }}>활동 날짜와 시작 시각이 빠른 순이에요.</div>}
      {!elderId && isUser && type === 'NEED' && <BundleLink />}
      <div className="sec-t"><span>공고 <span style={{ color: 'var(--brand)' }}>{shown.length}</span>개</span></div>
      {list === undefined ? <Loading /> : shown.length ? shown.map((p) => <PostCard key={p.id} p={p} to={elderId ? `/post/${p.id}?elder=${elderId}` : undefined} />) : <Empty>조건에 맞는 공고가 없어요</Empty>}
      {!elderId && me && me.role === 'USER' && (
        <button className="fab" onClick={() => nav('/write')}><Ic n="plus" s={20} /> 공고 올리기</button>
      )}
    </Screen>
  );
}

function diffReason(p) {
  if (p.difficultyReason) return p.difficultyReason;
  const c = postCats(p);
  if (c.includes('동행')) return '병원 이동·안전 책임이 있어 책임 등급';
  if (c.includes('장보기·심부름') || c.includes('가벼운 집안일')) return '무거운 짐(신체 부담)이 있어 수고 등급';
  return '신체 부담·위험이 적어 기본 등급';
}

/* ---------- 공고 상세 ---------- */
export function PostDetail() {
  const { id } = useParams();
  const [sp] = useSearchParams();
  const elderId = sp.get('elder');
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast, confirm } = useUi();
  const repost = useRepost();
  const report = useReport();
  const profile = useProfileSheet();
  const [p, reload] = useLoad(() => api(`/posts/${id}`), [id]);
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  if (p === undefined) return <Screen title="공고" noTabs><Loading /></Screen>;
  if (!p) return <Screen title="공고" noTabs><Empty>공고를 찾을 수 없어요</Empty></Screen>;
  const w = postWho(p);
  const offer = p.type === 'OFFER';
  const mine = p.mine;
  const proxyMine = !!p.proxyMine; // B-57: 내가 가족 대신 올린 공고(주인은 가족) — 진행 상황은 읽기만
  const reqs = p.requests || [];
  const open = (p.status || 'OPEN') === 'OPEN';
  const accepted = reqs.some((r) => !['SENT', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(r.status));
  const start = () => {
    // B-01: 봉사 조건은 신분증 인증뿐이다(안전 교육은 선택 연습이라 막지 않는다)
    if (!offer && me && me.role === 'USER' && me.idStatus !== 'APPROVED') { toast('봉사는 신분증 인증이 끝난 뒤 할 수 있어요'); return nav('/my/verify'); }
    nav(`/req/new?post=${p.id}${elderId ? `&elder=${elderId}` : ''}`);
  };
  return (
    <Screen title={offer ? '도움 가능 공고' : '봉사 요청 공고'} noTabs>
      <div className="row" style={{ gap: 12 }}>
        <Avatar name={w.name} s={56} elder={w.elder} />
        <div className="grow"><div className="b" style={{ fontSize: 18, display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>{w.name}{w.badge}</div><div className="mute2">{w.sub}</div></div>
        {p.ownerId && !mine && !w.elder && <button className="btn soft sm" style={{ width: 'auto', padding: '0 12px' }} onClick={() => profile(p.ownerId)}>프로필</button>}
      </div>
      {offer && !mine && <MatchWhy text={p.matchReason} style={{ marginTop: 10 }} />}
      {offer && p.ownerId && !mine && !w.elder && me && me.role === 'USER' && <RegularButton volunteerId={p.ownerId} compact style={{ marginTop: 10 }} />}
      <div style={{ marginTop: 12 }}><EasyListen postId={p.id} showText /></div>
      {p.proxy && (
        <div className="lock" style={{ marginTop: 12 }}><Ic n="users" s={18} /><div>{proxyMine
          ? <><b>{p.ownerName} 님 대신 올린 공고예요.</b> 봉사 제안의 최종 수락·거부는 {p.ownerName} 님이 직접 하고, 시간도 {p.ownerName} 님 통장에서 보류·차감돼요. 여기서는 진행 상황만 볼 수 있어요.</>
          : mine ? <><b>{p.proxyByName || '가족'} 님이 대신 올려 준 공고예요.</b> 봉사 제안이 오면 최종 수락·거부는 직접 해 주세요.</>
            : <>가족이 대신 올린 공고예요. 최종 수락은 {p.ownerName} 님이 직접 해요.</>}</div></div>
      )}
      {p.seriesId && p.repeatRule && (
        <div className="notice" style={{ marginTop: 12 }}><Ic n="repeat" s={15} /> <b>{p.repeatRule}</b> 정기 요청의 회차 공고예요. 이 공고에도 요청 1건만 수락돼요.{(mine || proxyMine) && <> <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700 }} onClick={() => nav(p.elderId ? `/recurring?elder=${p.elderId}` : '/recurring')}>정기 요청 관리 ›</a></>}</div>
      )}
      {mine && <FixTips p={p} />}
      <div className="card" style={{ marginTop: 16 }}>
        <div className="mute2">{offer ? '봉사할 수 있는 시간' : '도움이 필요한 시간'}</div>
        <div className="b" style={{ fontSize: 20, marginTop: 2 }}>{dlabel(p.date)} {hh(p.startHour)} ~ {hh(p.endHour)}</div>
        <TBar s={p.startHour} e={p.endHour} />
        <div className="mute2 ic-line" style={{ marginTop: 6 }}><Ic n="pin" s={15} />{p.area}</div>
      </div>
      <label className="f">{offer ? '할 수 있는 도움' : '필요한 도움'}</label>
      <Cats list={postCats(p)} />
      <label className="f">{offer ? '한마디' : '내용'}</label>
      <div className="card">{offer ? (p.note || p.title || p.detail) : <><b>{p.title}</b>{p.detail && <div className="muted" style={{ marginTop: 4 }}>{p.detail}</div>}{p.note && <div className="mute2" style={{ marginTop: 4 }}>{p.note}</div>}</>}</div>
      {!offer && (
        <div className="ai" style={{ marginTop: 12 }}>
          <div className="ah"><Spk />난이도 제안<Mod n={2} /></div>
          <div className="between"><span>{p.estHours ?? '-'}시간 × {lvName(p.difficulty)} {lvWeight(p.difficulty, p.weight).toFixed(1)}</span><b className="cr plus" style={{ fontSize: 18 }}>예상 적립 +{fmt(p.estEarn)}시간</b></div>
          <div className="small muted" style={{ marginTop: 4 }}>근거: {diffReason(p)}</div>
        </div>
      )}
      {offer && postCats(p).length > 0 && (() => {
        // 기능 U-03: 도움 가능 공고에도 예상 적립 시간을 보여 준다(1시간 기준, 요청한 도움 종류의 가중치에 따라)
        const ws = postCats(p).map((c) => W(c));
        const lo = Math.min(...ws), hi = Math.max(...ws);
        return <div className="notice" style={{ marginTop: 12 }}>예상 적립: 1시간 도우면 <b>+{lo.toFixed(1)}{hi > lo ? `~${hi.toFixed(1)}` : ''}시간</b> (요청받은 시간 × 도움 종류 가중치)</div>;
      })()}
      {w.elder && <div className="lock" style={{ marginTop: 12 }}><Ic n="shield" s={18} /><div><b>방문 시 주의사항</b><br />{p.elderCaution || p.caution || '담당자가 따로 안내해요'}<div style={{ marginTop: 6, opacity: 0.85 }}>이름·주소·연락처는 보이지 않아요. 수락되면 담당자가 방문 장소를 따로 알려드려요.</div></div></div>}
      <div className="notice" style={{ marginTop: 12 }}>공고 1개에는 요청 1건만 수락돼요. 시간은 공고 범위 안에서 30분 단위, 최대 4시간이에요.</div>
      {mine || proxyMine ? (
        <>
          <div className="sec-t">받은 요청·제안 {reqs.length}건{proxyMine ? ' · 보기만' : ''}</div>
          {!reqs.length ? <Empty>아직 받은 요청이 없어요</Empty> : proxyMine
            ? reqs.map((r) => (
              <div className="card item" key={r.id}><div className="grow"><div className="b">{r.volunteer ? r.volunteer.name : ''} · {KIND_LABEL[r.kind] || '요청'}</div><div className="mute2">{dlabel(r.date)} {hh(r.startHour)}~{hh(Number(r.startHour) + Number(r.hours))} · {r.content}</div></div><StTag st={r.status} /></div>
            ))
            : reqs.map((r) => <ReqRow key={r.id} r={r} />)}
          {mine && open && !accepted && (
            <div className="btns" style={{ marginTop: 14 }}>
              <button className="btn ghost" onClick={() => nav(`/post/${p.id}/edit`)}>수정</button>
              <button className="btn ghost" onClick={async () => {
                if (!(await confirm('공고를 삭제할까요?', '받은 요청은 자동으로 거절돼요.', '삭제', true))) return;
                try { await del(`/posts/${p.id}`); nav(-1); toast('삭제했어요'); } catch { /* 토스트 */ }
              }}>삭제</button>
            </div>
          )}
          {mine && !open && <div className="notice" style={{ marginTop: 14 }}>요청이 수락되었거나 마감된 공고는 바꿀 수 없어요.</div>}
          {mine && <button className="btn soft" style={{ marginTop: 8 }} onClick={() => repost(p, (np) => nav(`/post/${np.id}`, { replace: true }))}>↻ 같은 내용으로 다시 올리기 (날짜만 골라요)</button>}
          {proxyMine && <div className="notice" style={{ marginTop: 14 }}>수정·삭제·다시 올리기는 {p.ownerName} 님이 직접 할 수 있어요.</div>}
        </>
      ) : open ? (
        me && me.role !== 'ADMIN' && (me.role !== 'ORG' || offer) && <button className="btn big-cta" style={{ marginTop: 18 }} onClick={start}>{offer ? (elderId ? `${elder ? elder.alias + ' 님' : '대상'} 대신 도움 요청하기` : '이 시간에 도움 요청하기') : '봉사 제안하기'}</button>
      ) : <div className="notice" style={{ marginTop: 16 }}>이 공고는 이미 요청이 수락되었거나 마감됐어요.</div>}
      {!mine && <button className="au-demo" onClick={() => report({ targetType: 'POST', targetId: p.id, targetLabel: `공고 · ${p.title || p.note || ''}` })}>이 공고 신고하기</button>}
      <span hidden onClick={reload} />
    </Screen>
  );
}
export const _x = [Stars, RuleNote];
