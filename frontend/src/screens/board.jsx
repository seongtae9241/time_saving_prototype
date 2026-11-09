import { useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, TBar, Cats, PostCard, ReqRow, Avatar, Stars, Loading, Empty, RuleNote, useLoad, postWho, postCats } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, del, post as apost } from '../api.js';
import { CATS, SHORT, AREAS, fmt, hh, dlabel, lvName, lvWeight, todayISO, addDays } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { useReport } from './common.jsx';
import { useProfileSheet } from './request.jsx';

const DATES = [['all', '전체 날짜'], ['today', '오늘'], ['week', '이번 주'], ['next', '다음 주']];
const SLOTS = [['all', '시간대'], ['am', '오전'], ['pm', '오후'], ['eve', '저녁']];

/* ---------- 공고 게시판 (B-20) · 담당자 대리 요청(C-05) ---------- */
export function Board() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { sheet, closeSheet } = useUi();
  const [sp, setSp] = useSearchParams();
  const elderId = sp.get('elder');
  const type = elderId ? 'OFFER' : sp.get('type') || 'OFFER';
  const [cat, setCat] = useState('전체');
  const [sort, setSort] = useState('near');
  const [area, setArea] = useState('');
  const [dt, setDt] = useState('all');
  const [slot, setSlot] = useState('all');
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  const q = new URLSearchParams({ type, sort });
  if (area) q.set('area', area);
  if (cat !== '전체') q.set('cat', cat);
  if (dt === 'today') q.set('date', todayISO());
  const [list] = useLoad(() => api(`/posts?${q}`), [q.toString()]);
  let shown = (list || []).filter((p) => !p.mine && (p.status || 'OPEN') === 'OPEN');
  if (dt === 'week' || dt === 'next') {
    const a = dt === 'week' ? todayISO() : addDays(7), b = dt === 'week' ? addDays(7) : addDays(14);
    shown = shown.filter((p) => p.date >= a && p.date < b);
  }
  if (slot !== 'all') shown = shown.filter((p) => (slot === 'am' ? p.startHour < 12 : slot === 'pm' ? p.endHour > 12 && p.startHour < 18 : p.endHour > 18));
  const pick = (title, opts, val, set) => sheet(() => (
    <>
      <h2>{title}</h2>
      {opts.map(([v, l]) => <div key={v} className={'opt ' + (val === v ? 'on' : '')} role="button" tabIndex={0} onClick={() => { set(v); closeSheet(); }}><span className="rd" /><div>{l}</div></div>)}
    </>
  ));
  const eName = elder ? elder.alias + ' 님' : '';
  return (
    <Screen title={elderId ? `${eName} 대신 요청` : '공고'} tab={me && me.role === 'ORG' ? '/o/elders' : '/board'} noTabs={!!elderId} back={elderId ? true : false}>
      {elderId ? (
        <><div className="lock"><Ic n="users" s={18} /><div><b>{eName}</b>({elder ? elder.dong : ''}) 대신 요청할 봉사 가능 공고를 골라요. 대상 시간은 차감되지 않아요.</div></div><div style={{ height: 12 }} /></>
      ) : (
        <div className="seg2">
          <button className={type === 'OFFER' ? 'on' : ''} onClick={() => { setSp({ type: 'OFFER' }, { replace: true }); setCat('전체'); }}>봉사 가능 공고<br /><span style={{ fontSize: 12, fontWeight: 500 }}>도움 받고 싶을 때</span></button>
          <button className={type === 'NEED' ? 'on' : ''} onClick={() => { setSp({ type: 'NEED' }, { replace: true }); setCat('전체'); }}>도움 요청 공고<br /><span style={{ fontSize: 12, fontWeight: 500 }}>봉사하고 싶을 때</span></button>
        </div>
      )}
      <div className="row" style={{ gap: 6, marginBottom: 8, flexWrap: 'wrap' }}>
        <button className="tag2" onClick={() => pick('지역', [['', '행복구 전체'], ...AREAS.map((a) => [a, a])], area, setArea)}><Ic n="pin" s={14} />{area || '행복구 전체'} <Ic h={I.down} /></button>
        <button className="tag2" onClick={() => pick('날짜', DATES, dt, setDt)}>{DATES.find((d) => d[0] === dt)[1]} <Ic h={I.down} /></button>
        <button className="tag2" onClick={() => pick('시간대', SLOTS.map(([v, l]) => [v, v === 'all' ? '전체 시간대' : l]), slot, setSlot)}>{SLOTS.find((d) => d[0] === slot)[1]} <Ic h={I.down} /></button>
        <span className="grow" />
        <button className="tag2" onClick={() => setSort(sort === 'near' ? 'new' : 'near')}>{sort === 'near' ? '가까운 순' : '최신순'} ⇅</button>
      </div>
      <div className="chips">{['전체', ...CATS.map((c) => c.k)].map((k) => <button key={k} className={'chip ' + (k === cat ? 'on' : '')} onClick={() => setCat(k)}>{k === '전체' ? '전체' : SHORT[k]}</button>)}</div>
      {!elderId && <div className="tip" style={{ marginTop: 12, padding: '12px 16px' }}><div className="grow small"><Ic n="bell" s={16} /> 조건에 맞는 공고가 올라오면 알려드려요</div><button className="btn soft sm" style={{ width: 'auto', padding: '0 12px', minHeight: 36 }} onClick={() => nav('/my/alerts')}>관심 조건</button></div>}
      <div className="sec-t"><span>공고 <span style={{ color: 'var(--brand)' }}>{shown.length}</span>개</span></div>
      {list === undefined ? <Loading /> : shown.length ? shown.map((p) => <PostCard key={p.id} p={p} to={elderId ? `/post/${p.id}?elder=${elderId}` : undefined} />) : <Empty>조건에 맞는 공고가 없어요</Empty>}
      {!elderId && me && me.role === 'USER' && <button className="fab" onClick={() => nav('/write')}><Ic h={I.plus} /> 공고 올리기</button>}
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
  const report = useReport();
  const profile = useProfileSheet();
  const [p, reload] = useLoad(() => api(`/posts/${id}`), [id]);
  const [elder] = useLoad(() => (elderId ? api(`/org/elders/${elderId}`) : Promise.resolve(null)), [elderId]);
  if (p === undefined) return <Screen title="공고" noTabs><Loading /></Screen>;
  if (!p) return <Screen title="공고" noTabs><Empty>공고를 찾을 수 없어요</Empty></Screen>;
  const w = postWho(p);
  const offer = p.type === 'OFFER';
  const mine = p.mine;
  const reqs = p.requests || [];
  const open = (p.status || 'OPEN') === 'OPEN';
  const accepted = reqs.some((r) => !['SENT', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(r.status));
  const start = () => {
    if (!offer && me && me.role === 'USER' && !me.eduDone) return toast('봉사는 신원 인증과 첫 활동 교육 후 할 수 있어요');
    nav(`/req/new?post=${p.id}${elderId ? `&elder=${elderId}` : ''}`);
  };
  return (
    <Screen title={offer ? '봉사 가능 공고' : '도움 요청 공고'} noTabs>
      <div className="row" style={{ gap: 12 }}>
        <Avatar name={w.name} s={56} elder={w.elder} />
        <div className="grow"><div className="b" style={{ fontSize: 18, display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>{w.name}{w.badge}</div><div className="mute2">{w.sub}</div></div>
        {p.ownerId && !mine && !w.elder && <button className="btn soft sm" style={{ width: 'auto', padding: '0 12px' }} onClick={() => profile(p.ownerId)}>프로필</button>}
      </div>
      <div className="card" style={{ marginTop: 16 }}>
        <div className="mute2">{offer ? '봉사할 수 있는 시간' : '도움이 필요한 시간'}</div>
        <div className="b" style={{ fontSize: 20, marginTop: 2 }}>{dlabel(p.date)} {hh(p.startHour)} ~ {hh(p.endHour)}</div>
        <TBar s={p.startHour} e={p.endHour} />
        {p.repeatRule && <div className="mute2 ic-line" style={{ marginTop: 8 }}><Ic n="repeat" s={15} />{p.repeatRule} 반복 공고</div>}
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
      {w.elder && <div className="lock" style={{ marginTop: 12 }}><Ic n="shield" s={18} /><div><b>방문 시 주의사항</b><br />{p.elderCaution || p.caution || '담당자가 따로 안내해요'}<div style={{ marginTop: 6, opacity: 0.85 }}>이름·주소·연락처는 보이지 않아요. 수락되면 담당자가 방문 장소를 따로 알려드려요.</div></div></div>}
      <div className="notice" style={{ marginTop: 12 }}>공고 1개에는 요청 1건만 수락돼요. 시간은 공고 범위 안에서 30분 단위, 최대 4시간이에요.</div>
      {mine ? (
        <>
          <div className="sec-t">받은 요청·제안 {reqs.length}건</div>
          {reqs.length ? reqs.map((r) => <ReqRow key={r.id} r={r} />) : <Empty>아직 받은 요청이 없어요</Empty>}
          {open && !accepted && (
            <div className="btns" style={{ marginTop: 14 }}>
              <button className="btn ghost" onClick={() => nav(`/post/${p.id}/edit`)}>수정</button>
              <button className="btn ghost" onClick={async () => {
                if (!(await confirm('공고를 삭제할까요?', '받은 요청은 자동으로 거절돼요.', '삭제', true))) return;
                try { await del(`/posts/${p.id}`); nav(-1); toast('삭제했어요'); } catch { /* 토스트 */ }
              }}>삭제</button>
            </div>
          )}
          {!open && <div className="notice" style={{ marginTop: 14 }}>요청이 수락되었거나 마감된 공고는 바꿀 수 없어요.</div>}
          <button className="btn soft" style={{ marginTop: 8 }} onClick={async () => {
            const d = new Date(p.date); d.setDate(d.getDate() + 7);
            const nd = d.toISOString().slice(0, 10) < todayISO() ? addDays(7) : d.toISOString().slice(0, 10);
            try {
              const np = await apost('/posts', { type: p.type, date: nd, startHour: p.startHour, endHour: p.endHour, area: p.area, categories: postCats(p), title: p.title, detail: p.detail, note: p.note, estHours: p.estHours, elderId: p.elderId || undefined });
              toast('같은 내용으로 다음 주에 다시 올렸어요');
              nav(`/post/${(Array.isArray(np) ? np[0] : np).id}`, { replace: true });
            } catch { /* 토스트 */ }
          }}>↻ 같은 내용으로 다시 올리기</button>
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
