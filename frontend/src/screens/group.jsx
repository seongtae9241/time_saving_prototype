// 단체 봉사 (기능 U-10 목록·참여, U-11 사용자 단체 공고, O-07 담당자 단체 공고)
// 1:1 공고(board.jsx)와 따로 둔다. 서버: /api/group-posts. 인원·적립 시간은 모두 서버가 계산한 값을 그대로 보여 준다.
import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, CatIc, Cats, TBar, Loading, Empty, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post, del } from '../api.js';
import { CATS, AREAS, SHORT, hh, fmt, dlabel, addDays, todayISO } from '../lib/format.js';
import { HelpableStrip } from './home.jsx';

const KINDS = [['', '전체'], ['RECRUIT', '단체 모집'], ['OFFER', '단체 봉사 가능'], ['mine', '내 단체 봉사']];
const ST_KO = { OPEN: null, DONE: ['ok', '완료'], CANCELLED: ['end', '취소'] };
const MY_KO = { JOINED: ['go', '참여 신청함'], ATTENDED: ['ok', '참석 확인'], ABSENT: ['no', '불참'] };

/** 공고 화면 맨 위 탭 (2026-11-13 대표 지시): 1:1 공고 / 단체 봉사 / 동네 기관 소식 */
const BOARD_TABS = [['one', '1:1 공고', '/board'], ['group', '단체 봉사', '/board?tab=group'], ['news', '동네 기관 소식', '/board?tab=news']];
export function BoardSeg({ cur = 'one' }) {
  const nav = useNavigate();
  return (
    <div className="seg" role="tablist" aria-label="공고 종류" style={{ marginBottom: 12 }}>
      {BOARD_TABS.map(([k, t, to]) => <button key={k} role="tab" aria-selected={cur === k} className={cur === k ? 'on' : ''} style={{ fontSize: 14, whiteSpace: 'nowrap' }} onClick={() => cur !== k && nav(to, { replace: true })}>{t}</button>)}
    </div>
  );
}

/** 모집 인원 / 현재 참여 인원 막대 */
function People({ g }) {
  const pct = Math.min(100, Math.round((g.joined / Math.max(1, g.capacity)) * 100));
  return (
    <div style={{ marginTop: 12 }}>
      <div className="between small"><span className="b"><Ic n="users" s={15} /> {g.kind === 'OFFER' ? '함께' : '모집'} {g.capacity}명 · 지금 {g.joined}명 참여</span><span className="mute2">{g.full ? '모집 마감' : `${g.left}자리 남음`}</span></div>
      <div className="bar" style={{ marginTop: 6 }} role="img" aria-label={`모집 ${g.capacity}명 중 ${g.joined}명 참여`}><i style={{ width: `${pct}%` }} /></div>
    </div>
  );
}

const whoOf = (g) => (g.kind === 'OFFER' ? g.groupName || g.ownerName : g.ownerName);

export function GroupCard({ g }) {
  const nav = useNavigate();
  const st = ST_KO[g.status];
  const my = MY_KO[g.myStatus];
  return (
    <div className="pc2 click" role="button" tabIndex={0} onClick={() => nav(`/group/${g.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/group/${g.id}`); }}>
      <div className="top">
        <CatIc k={g.category} size={44} />
        <div className="grow"><div className="who">{whoOf(g)}{g.byOrg && <span className="badge elder">기관</span>}</div><div className="sub">{g.area}{g.place ? ` · ${g.place}` : ''}</div></div>
        <span className={`kind ${g.kind === 'OFFER' ? 'offer' : 'need'}`}>{g.kindLabel}</span>
      </div>
      <div className="ttl">{g.title}</div>
      <div className="between" style={{ marginTop: 10 }}>
        <span className="tr-t">{dlabel(g.date)} {hh(g.startHour)}~{hh(g.endHour)}</span>
        {st ? <span className={`st ${st[0]}`}>{st[1]}</span> : my ? <span className={`st ${my[0]}`}>{my[1]}</span> : <span className="pill">한 사람 +{fmt(g.estEarn)}시간</span>}
      </div>
      <People g={g} />
      {g.kind === 'OFFER' && g.status === 'OPEN' && <div className="mute2" style={{ marginTop: 8 }}>{g.hosted ? `${g.hostOrgName}에서 받기로 했어요` : '받을 기관을 기다리는 중이에요'}</div>}
    </div>
  );
}

/* ---------- 단체 공고 목록 (공고 화면의 단체 탭) ---------- */
export function GroupBoard() {
  const nav = useNavigate();
  const { me } = useAuth();
  const [sp, setSp] = useSearchParams();
  const kind = sp.get('kind') || '';
  const [area, setArea] = useState('');
  const mine = kind === 'mine';
  const q = new URLSearchParams();
  if (kind && !mine) q.set('kind', kind);
  if (area) q.set('area', area);
  const [list] = useLoad(() => api(mine ? '/group-posts/mine' : `/group-posts?${q}`), [kind, area]);
  const org = me && me.role === 'ORG';
  const shown = (list || []).filter((g) => mine || !area || g.area === area);
  return (
    <Screen title="공고" tab={org ? '/o/home' : '/board'} back={org ? true : false}>
      {!org && <BoardSeg cur="group" />}
      <div className="lock"><Ic n="users" s={18} /><div><b>여럿이 함께 하는 봉사예요.</b> 담당자가 그날 참석을 확인하면 참여한 사람마다 자기 통장에 활동 시간만큼 적립돼요.</div></div>
      <div className="chips" role="tablist" aria-label="단체 공고 종류" style={{ marginTop: 12 }}>
        {KINDS.map(([k, t]) => <button key={k} role="tab" aria-selected={kind === k} className={'chip ' + (kind === k ? 'on' : '')} onClick={() => setSp(k ? { tab: 'group', kind: k } : { tab: 'group' }, { replace: true })}>{k === 'mine' && org ? '우리 기관' : t}</button>)}
      </div>
      {!mine && (
        <div className="chips" style={{ marginTop: 8 }} aria-label="지역">
          {['', ...AREAS].map((a) => <button key={a} className={'chip ' + (area === a ? 'on' : '')} onClick={() => setArea(a)}>{a || '행복구 전체'}</button>)}
        </div>
      )}
      <div className="sec-t"><span>{mine ? (org ? '우리 기관 단체 봉사' : '내 단체 봉사') : '단체 봉사'} <span style={{ color: 'var(--brand)' }}>{shown.length}</span>개</span></div>
      {list === undefined ? <Loading /> : shown.length ? shown.map((g) => <GroupCard key={g.id} g={g} />)
        : <Empty>{mine ? '아직 올리거나 참여한 단체 봉사가 없어요' : '조건에 맞는 단체 봉사가 없어요'}</Empty>}
      {!org && <HelpableStrip />}
      {me && me.role !== 'ADMIN' && <button className="fab" onClick={() => nav('/group/new')}><Ic n="plus" s={20} /> 단체 공고 올리기</button>}
    </Screen>
  );
}

/* ---------- 단체 공고 상세: 참여 신청 · 받기 · 참석 확인 ---------- */
export function GroupDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast, confirm } = useUi();
  const [busy, run] = useBusy();
  const [g, reload] = useLoad(() => api(`/group-posts/${id}`), [id]);
  const [came, setCame] = useState(null); // 참석 확인에서 고른 사람(null = 아직 안 건드림 → 모두 참석)
  if (g === undefined) return <Screen title="단체 공고" noTabs><Loading /></Screen>;
  if (!g) return <Screen title="단체 공고" noTabs><Empty>단체 공고를 찾을 수 없어요</Empty></Screen>;
  const offer = g.kind === 'OFFER';
  const open = g.status === 'OPEN';
  const people = g.participants || [];
  const picked = came || people.filter((p) => p.status === 'JOINED').map((p) => p.userId);
  const act = (path, okMsg, method = 'POST') => run(async () => {
    if (method === 'DELETE') await del(path); else await post(path, {});
    toast(okMsg);
    await reload();
  });
  const join = async () => {
    if (me.idStatus !== 'APPROVED') { toast('단체 봉사도 신분증 인증이 끝난 뒤 참여할 수 있어요'); return nav('/my/verify'); }
    if (!(await confirm('이 단체 봉사에 참여할까요?', `${dlabel(g.date)} ${hh(g.startHour)}~${hh(g.endHour)} · ${g.area}${g.place ? ` ${g.place}` : ''}\n참석이 확인되면 내 통장에 ${fmt(g.estEarn)}시간이 적립돼요.`, '참여 신청'))) return;
    act(`/group-posts/${g.id}/join`, '참여 신청했어요');
  };
  const complete = async () => {
    const n = picked.length;
    if (!(await confirm(`참석 ${n}명으로 완료할까요?`, `참석한 ${n}명에게 각각 ${fmt(g.estEarn)}시간이 적립돼요. 완료한 뒤에는 바꿀 수 없어요.`, '참석 확인·완료'))) return;
    run(async () => {
      const r = await post(`/group-posts/${g.id}/complete`, { attended: picked });
      toast(`참석 ${r.result.attended}명에게 ${fmt(r.result.earnedEach)}시간씩 적립했어요`);
      setCame(null);
      await reload();
    });
  };
  const toggle = (uid) => setCame(picked.includes(uid) ? picked.filter((x) => x !== uid) : [...picked, uid]);
  const st = ST_KO[g.status];
  return (
    <Screen title={g.kindLabel} noTabs>
      <div className="row" style={{ gap: 12 }}>
        <CatIc k={g.category} size={56} />
        <div className="grow"><div className="b" style={{ fontSize: 18, display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>{whoOf(g)}{g.byOrg && <span className="badge elder">기관</span>}</div>
          <div className="mute2">{offer ? `${g.capacity}명이 함께 봉사할 수 있어요` : `${g.capacity}명을 모집해요`}{offer && g.groupName && g.ownerName !== g.groupName ? ` · 올린 곳 ${g.ownerName}` : ''}</div></div>
        {st && <span className={`st ${st[0]}`}>{st[1]}</span>}
      </div>
      <div className="card" style={{ marginTop: 16 }}>
        <div className="mute2">{offer ? '봉사할 수 있는 시간' : '함께 봉사하는 시간'}</div>
        <div className="b" style={{ fontSize: 20, marginTop: 2 }}>{dlabel(g.date)} {hh(g.startHour)} ~ {hh(g.endHour)}</div>
        <TBar s={g.startHour} e={g.endHour} />
        <div className="mute2 ic-line" style={{ marginTop: 6 }}><Ic n="pin" s={15} />{g.area}{g.place ? ` · ${g.place}` : ''}</div>
        <People g={g} />
      </div>
      <label className="f">봉사 종류</label>
      <Cats list={[g.category]} />
      <label className="f">내용</label>
      <div className="card"><b>{g.title}</b>{g.detail && <div className="muted" style={{ marginTop: 4, whiteSpace: 'pre-line' }}>{g.detail}</div>}</div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="between"><span>{g.hours}시간 × 기본 {Number(g.weight).toFixed(1)}</span><b className="cr plus" style={{ fontSize: 18 }}>한 사람 +{fmt(g.estEarn)}시간</b></div>
        <div className="small muted" style={{ marginTop: 4 }}>참석이 확인된 사람마다 자기 통장에 적립돼요. 1시간 봉사는 1시간이에요.</div>
      </div>
      {offer && open && <div className="notice" style={{ marginTop: 12 }}>{g.hosted ? <><b>{g.hostOrgName}</b>에서 이 단체의 봉사를 받기로 했어요. 그날 담당자가 참석을 확인해요.</> : '단체 봉사 가능 공고예요. 기관 담당자가 "우리 기관에서 받기"를 누르면 진행돼요.'}</div>}
      {!offer && open && <div className="notice" style={{ marginTop: 12 }}>정원까지 먼저 신청한 순서로 참여해요. 그날 담당자가 참석을 확인하면 적립돼요.</div>}

      {g.myStatus && (
        <div className="lock" style={{ marginTop: 12 }}><Ic n="check" s={18} /><div>
          {g.myStatus === 'JOINED' && <><b>참여 신청했어요.</b> {dlabel(g.date)} {hh(g.startHour)}에 만나요. 플래너 달력에서도 볼 수 있어요.</>}
          {g.myStatus === 'ATTENDED' && <><b>참석이 확인됐어요.</b> 내 통장에 {fmt(g.myEarned)}시간이 적립됐어요.</>}
          {g.myStatus === 'ABSENT' && <><b>참석하지 않은 것으로 기록됐어요.</b> 사실과 다르면 문의해 주세요.</>}
        </div></div>
      )}

      {people.length > 0 || g.mine || g.confirmSide ? (
        <>
          <div className="sec-t"><span>참여자 {people.length}명</span>{g.canComplete && <span className="mute2" style={{ fontWeight: 500 }}>참석한 사람만 남겨 주세요</span>}</div>
          {!people.length && <Empty>아직 참여 신청한 사람이 없어요</Empty>}
          {people.map((p) => {
            const m = MY_KO[p.status];
            const on = picked.includes(p.userId);
            return g.canComplete ? (
              <div key={p.userId} className={'opt ' + (on ? 'on' : '')} role="checkbox" aria-checked={on} tabIndex={0} onClick={() => toggle(p.userId)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(p.userId); } }}>
                <span className="rd" /><div className="grow"><div className="b">{p.name}{p.owner ? ' · 대표' : ''}</div><div className="mute2">{on ? `참석 · +${fmt(g.estEarn)}시간 적립` : '불참 · 적립 없음'}</div></div>
              </div>
            ) : (
              <div key={p.userId} className="card item" style={{ marginTop: 8 }}><div className="grow"><div className="b">{p.name}{p.owner ? ' · 대표' : ''}</div>{p.earned != null && <div className="mute2">+{fmt(p.earned)}시간 적립</div>}</div>{m && <span className={`st ${m[0]}`}>{m[1]}</span>}</div>
            );
          })}
        </>
      ) : null}

      {g.canComplete && <button className="btn big-cta" style={{ marginTop: 18 }} disabled={busy} onClick={complete}>참석 {picked.length}명 확인하고 완료하기</button>}
      {g.confirmSide && open && !g.canComplete && <div className="notice" style={{ marginTop: 14 }}>활동하는 날({dlabel(g.date)})부터 참석을 확인할 수 있어요.</div>}
      {g.canHost && <button className="btn big-cta" style={{ marginTop: 18 }} disabled={busy} onClick={async () => {
        if (!(await confirm('이 단체의 봉사를 우리 기관에서 받을까요?', `${whoOf(g)} · ${dlabel(g.date)} ${hh(g.startHour)}~${hh(g.endHour)}\n그날 참석을 확인하면 참여자에게 시간이 적립돼요. 기관에서 빠지는 시간은 없어요.`, '우리 기관에서 받기'))) return;
        act(`/group-posts/${g.id}/host`, '우리 기관에서 받기로 했어요');
      }}>우리 기관에서 받기</button>}
      {g.canUnhost && <button className="btn ghost" style={{ marginTop: 8 }} disabled={busy} onClick={async () => {
        if (!(await confirm('받기를 취소할까요?', '다른 기관이 이 단체의 봉사를 받을 수 있게 돼요.', '받기 취소', true))) return;
        act(`/group-posts/${g.id}/unhost`, '받기를 취소했어요');
      }}>받기 취소</button>}
      {me && me.role === 'USER' && open && !g.past && !g.myStatus && (
        g.canJoin || me.idStatus !== 'APPROVED'
          ? <button className="btn big-cta" style={{ marginTop: 18 }} disabled={busy} onClick={join}>참여 신청하기</button>
          : <div className="notice" style={{ marginTop: 16 }}>{g.joinBlock || '지금은 참여할 수 없어요'}</div>
      )}
      {g.canLeave && <button className="btn ghost" style={{ marginTop: 14 }} disabled={busy} onClick={async () => {
        if (!(await confirm('참여를 취소할까요?', '자리가 다른 사람에게 돌아가요.', '참여 취소', true))) return;
        act(`/group-posts/${g.id}/leave`, '참여를 취소했어요');
      }}>참여 취소</button>}
      {g.canCancel && <button className="btn ghost" style={{ marginTop: 8 }} disabled={busy} onClick={async () => {
        if (!(await confirm('단체 공고를 내릴까요?', '참여 신청한 사람들에게 취소 알림이 가요.', '공고 내리기', true))) return;
        act(`/group-posts/${g.id}`, '공고를 내렸어요', 'DELETE');
      }}>공고 내리기</button>}
      {!open && <div className="notice" style={{ marginTop: 16 }}>{g.status === 'DONE' ? '참석 확인이 끝난 단체 봉사예요.' : '취소된 단체 공고예요.'}</div>}
    </Screen>
  );
}

/* ---------- 단체 공고 올리기 (U-11 사용자: 단체 봉사 가능 · O-07 담당자: 단체 모집 / 기관 봉사단) ---------- */
export function GroupNew() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast } = useUi();
  const [busy, run] = useBusy();
  const org = me && me.role === 'ORG';
  const [f, setF] = useState({ kind: org ? 'RECRUIT' : 'OFFER', date: addDays(3), startHour: 10, hours: 2, area: (me && me.area) || '행복동', place: '', category: '', title: '', detail: '', capacity: org ? 5 : 3, groupName: '' });
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const offer = f.kind === 'OFFER';
  const cap = (d) => set('capacity', Math.max(1, Math.min(50, f.capacity + d)));
  const submit = () => run(async () => {
    if (f.capacity < 2) return toast('단체 공고는 2명 이상이어야 해요. 혼자 하는 봉사는 일반 공고로 올려 주세요');
    if (offer && !f.groupName.trim()) return toast('단체 이름을 적어 주세요');
    if (!f.category) return toast('봉사 종류를 골라 주세요');
    if (!f.date) return toast('날짜를 골라 주세요');
    const g = await post('/group-posts', { ...f, title: f.title.trim(), detail: f.detail.trim(), place: f.place.trim(), groupName: f.groupName.trim() });
    toast('단체 공고를 올렸어요');
    nav(`/group/${g.id}`, { replace: true });
  });
  if (!me) return <Screen title="단체 공고 올리기" noTabs><Loading /></Screen>;
  const blocked = !org && me.idStatus !== 'APPROVED';
  return (
    <Screen title="단체 공고 올리기" noTabs>
      {org ? (
        <>
          <label className="f" style={{ marginTop: 0 }}>어떤 단체 공고인가요?</label>
          <div className={'opt ' + (!offer ? 'on' : '')} role="radio" aria-checked={!offer} tabIndex={0} onClick={() => set('kind', 'RECRUIT')}><span className="rd" /><div><div className="b">단체 모집</div><div className="mute2">우리 기관 봉사거리에 함께 할 사람들을 모아요</div></div></div>
          <div className={'opt ' + (offer ? 'on' : '')} role="radio" aria-checked={offer} tabIndex={0} onClick={() => set('kind', 'OFFER')}><span className="rd" /><div><div className="b">기관 봉사단의 봉사 가능</div><div className="mute2">우리 기관 봉사단이 봉사할 수 있는 날을 알려요</div></div></div>
        </>
      ) : (
        <div className="lock"><Ic n="users" s={18} /><div><b>동아리·모임처럼 2명 이상이 함께 봉사할 때 올려요.</b> 기관 담당자가 받으면 진행되고, 그날 참석이 확인된 사람마다 시간이 적립돼요. 혼자 하는 봉사는 <button type="button" className="linkbtn" onClick={() => nav('/write/offer', { replace: true })}>도움 가능 공고</button>로 올려 주세요.</div></div>
      )}
      {blocked && <div className="notice" style={{ marginTop: 12 }}>단체 봉사 공고는 신분증 인증을 마친 뒤 올릴 수 있어요. <button type="button" className="linkbtn" onClick={() => nav('/my/verify')}>인증하러 가기</button></div>}
      {offer && <><label className="f" htmlFor="grp-name">단체 이름</label><input id="grp-name" className="in" maxLength={50} value={f.groupName} onChange={(e) => set('groupName', e.target.value)} placeholder="예) 행복동 청년 봉사단" /></>}
      <label className="f" id="grp-cap">{offer ? '함께 봉사할 인원' : '모집 인원'} <span className="mute2" style={{ fontWeight: 500 }}>(2명 이상)</span></label>
      <div className="stepper" role="group" aria-labelledby="grp-cap"><button type="button" aria-label="인원 줄이기" onClick={() => cap(-1)}>−</button><div className="v">{f.capacity}명</div><button type="button" aria-label="인원 늘리기" onClick={() => cap(1)}>+</button></div>
      {f.capacity < 2 && <div className="notice" style={{ marginTop: 8 }}>1명이면 단체 공고가 아니에요. 2명 이상으로 정해 주세요.</div>}
      {!org && offer && <div className="mute2" style={{ marginTop: 6 }}>올리는 나도 한 명으로 세요. 함께 할 친구들은 이 공고에서 참여 신청을 해요.</div>}
      <label className="f" htmlFor="grp-date">날짜</label>
      <input id="grp-date" className="in" type="date" min={todayISO()} value={f.date} onChange={(e) => set('date', e.target.value)} />
      <label className="f">시간</label>
      <div className="row">
        <select className="in grow" aria-label="시작 시각" value={f.startHour} onChange={(e) => set('startHour', +e.target.value)}>{Array.from({ length: 24 }, (_, i) => 8 + i * 0.5).filter((h) => h < 20).map((h) => <option key={h} value={h}>{hh(h)} 시작</option>)}</select>
        <select className="in grow" aria-label="활동 시간" value={f.hours} onChange={(e) => set('hours', +e.target.value)}>{[0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4].map((h) => <option key={h} value={h}>{h}시간</option>)}</select>
      </div>
      <label className="f">지역</label>
      <select className="in" aria-label="지역" value={f.area} onChange={(e) => set('area', e.target.value)}>{AREAS.map((a) => <option key={a}>{a}</option>)}</select>
      <label className="f" htmlFor="grp-place">모이는 곳 <span className="mute2" style={{ fontWeight: 500 }}>(선택)</span></label>
      <input id="grp-place" className="in" maxLength={100} value={f.place} onChange={(e) => set('place', e.target.value)} placeholder="예) 행복동 경로당" />
      <div className="mute2" style={{ marginTop: 6 }}>기관·공공장소 이름만 적어요. 개인 주소·연락처는 적지 않아요.</div>
      <label className="f">봉사 종류</label>
      <div className="tags">{CATS.map((c) => <button type="button" key={c.k} className={'chip ' + (f.category === c.k ? 'on' : '')} onClick={() => set('category', c.k)}>{SHORT[c.k]}</button>)}</div>
      <div className="mute2" style={{ marginTop: 6 }}>가벼운 봉사만 할 수 있어요. 몸을 직접 돌보는 일, 의료 행위, 돈 관리, 위험한 작업은 올릴 수 없어요.</div>
      <label className="f" htmlFor="grp-title">제목</label>
      <input id="grp-title" className="in" maxLength={100} value={f.title} onChange={(e) => set('title', e.target.value)} placeholder={offer ? '예) 어르신 말벗 함께 할게요' : '예) 경로당 창문 닦기와 바닥 청소'} />
      <label className="f" htmlFor="grp-detail">내용</label>
      <textarea id="grp-detail" className="in" maxLength={1000} value={f.detail} onChange={(e) => set('detail', e.target.value)} placeholder="무슨 일을 하는지, 준비물이 있는지 적어 주세요" />
      <div className="card" style={{ marginTop: 12 }}><div className="between"><span>{f.hours}시간 × 기본 1.0</span><b className="cr plus">한 사람 +{fmt(f.hours)}시간</b></div><div className="small muted" style={{ marginTop: 4 }}>참석이 확인된 사람마다 적립돼요. {org && !offer ? '기관에서 빠지는 시간은 없어요.' : ''}</div></div>
      <button className="btn big-cta" style={{ marginTop: 18 }} disabled={busy || blocked} onClick={submit}>{busy ? '올리는 중…' : '단체 공고 올리기'}</button>
    </Screen>
  );
}

/** 내 활동(봉사한 활동)에 붙이는 "내 단체 봉사" 묶음. 참여·올린 단체 봉사가 없으면 아무것도 그리지 않는다. */
export function MyGroups() {
  const nav = useNavigate();
  const [list] = useLoad(() => api('/group-posts/mine', { quiet: true }).catch(() => []), []);
  if (!list || !list.length) return null;
  return (
    <>
      <div className="sec-t"><span>단체 봉사 {list.length}</span><a role="button" tabIndex={0} onClick={() => nav('/board?tab=group&kind=mine')}>전체 ›</a></div>
      {list.slice(0, 3).map((g) => <GroupCard key={g.id} g={g} />)}
    </>
  );
}
