import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Loading, Empty, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { ap, dlabel, addDays, AREAS } from '../lib/format.js';
import '../styles/meeting.css';
import { ClassSection } from './publicClass.jsx';

/* ---------- 동네 소식 (2026-11-09 회의 2장): 기관 소식만 (사용자 클래스는 2026-11-10 삭제) ---------- */
const host = (u) => { try { return new URL(u).host; } catch { return u; } };
const when = (e) => (e.date ? `${dlabel(e.date)}${e.startHour != null ? ` ${ap(e.startHour)}` : ''}` : '날짜 미정');

function EventCard({ e }) {
  const nav = useNavigate();
  const img = e.preview && e.preview.image;
  return (
    <div className="ev-card" role="button" tabIndex={0} onClick={() => nav(`/news/${e.id}`)}>
      <div className="ev-cv">{img ? <img src={img} alt="" loading="lazy" referrerPolicy="no-referrer" onError={(x) => { x.currentTarget.style.display = 'none'; }} /> : <Ic n="cal" s={40} />}<span className="ev-org">{e.orgName || '기관'}</span></div>
      <div className="ev-b">
        <div className="ev-t">{e.title}</div>
        <div className="ev-m"><span><Ic n="clock" s={14} />{when(e)}</span>{(e.place || e.area) && <span><Ic n="pin" s={14} />{e.place || e.area}</span>}{e.near ? <span className="st go">내 동네</span> : e.area ? <span className="mute2">{e.area}{e.distanceKm != null ? ` · ${e.distanceKm}km` : ''}</span> : null}</div>
        <div className="ev-ft">
          <span className="mute2">{e.applyType === 'LINK' ? `외부 신청 · ${host(e.url)}` : e.capacity ? `앱 신청 ${e.applied}/${e.capacity}명` : `앱 신청 ${e.applied}명`}</span>
          {e.myApplied ? <span className="st ok">신청함</span> : e.full ? <span className="st end">정원 마감</span> : <span className="st go">{e.applyType === 'LINK' ? '링크로 신청' : '신청 가능'}</span>}
        </div>
      </div>
    </div>
  );
}

export function News() {
  const { me } = useAuth();
  const [area, setArea] = useState('');
  // 2026-11-11: 근처 지역 기준 — 지역을 안 고르면 서버가 내 동네 → 가까운 동 순으로 준다
  const [list] = useLoad(() => api(`/events${area ? `?area=${encodeURIComponent(area)}` : ''}`), [area]);
  const myArea = me && me.area;
  return (
    <Screen title="동네 기관 소식" noTabs>
      <div className="locb">📍 {myArea || '동네 미설정'} <span>내 동네 소식이 먼저 나와요</span></div>
      <div className="chips" role="tablist" aria-label="지역" style={{ marginTop: 10 }}>
        {[['', '가까운 순'], ...AREAS.map((a) => [a, a === myArea ? `${a} (내 동네)` : a])].map(([v, l]) => <button key={v || 'all'} role="tab" aria-selected={area === v} className={'chip ' + (area === v ? 'on' : '')} onClick={() => setArea(v)}>{l}</button>)}
      </div>
      <div className="mute2" style={{ marginTop: 8 }}>주민센터·복지관이 여는 교실·모임·행사예요. 시간 통장 적립·차감은 없어요.</div>
      {list === undefined ? <Loading /> : list && list.length ? list.map((e) => <EventCard key={e.id} e={e} />) : <Empty>{area ? `${area}에 올라온 기관 소식이 없어요` : '지금 올라온 기관 소식이 없어요'}</Empty>}
      <ClassSection />
    </Screen>
  );
}

function LinkCard({ url, preview }) {
  return (
    <a className="ev-link" href={url} target="_blank" rel="noopener noreferrer nofollow">
      {preview && preview.image ? <img src={preview.image} alt="" referrerPolicy="no-referrer" /> : <span className="ph"><Ic n="file" s={24} /></span>}
      <span className="grow" style={{ minWidth: 0 }}><b>{(preview && preview.title) || '안내 페이지'}</b>{preview && preview.description && <span style={{ color: 'var(--sub)' }}>{preview.description}</span>}<span>{host(url)} ↗</span></span>
    </a>
  );
}

export function EventDetail() {
  const { id } = useParams();
  const { me } = useAuth();
  const { toast, confirm } = useUi();
  const [e, reload] = useLoad(() => api(`/events/${id}`), [id]);
  const [busy, run] = useBusy();
  if (e === undefined) return <Screen title="동네 소식" noTabs><Loading /></Screen>;
  if (!e) return <Screen title="동네 소식" noTabs><Empty>소식을 찾을 수 없어요</Empty></Screen>;
  const user = me && me.role === 'USER';
  const open = e.status === 'OPEN';
  return (
    <Screen title="동네 소식" noTabs>
      <div className="ev-card" style={{ cursor: 'default', marginTop: 0 }}>
        <div className="ev-cv" style={{ height: 150 }}>{e.preview && e.preview.image ? <img src={e.preview.image} alt="" referrerPolicy="no-referrer" onError={(x) => { x.currentTarget.style.display = 'none'; }} /> : <Ic n="cal" s={48} />}<span className="ev-org">{e.orgName || '기관'}</span></div>
        <div className="ev-b"><div className="ev-t" style={{ fontSize: 20 }}>{e.title}</div>{e.content && <div className="small muted" style={{ marginTop: 8, whiteSpace: 'pre-line' }}>{e.content}</div>}</div>
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <dl className="kv"><dt>일시</dt><dd>{when(e)}</dd><dt>장소</dt><dd>{e.place || '-'}</dd><dt>정원</dt><dd>{e.capacity ? `${e.capacity}명 (신청 ${e.applied}명)` : `제한 없음 (신청 ${e.applied}명)`}</dd><dt>신청</dt><dd>{e.applyType === 'LINK' ? '안내 링크에서 신청' : '앱에서 신청'}</dd></dl>
      </div>
      {e.url && <LinkCard url={e.url} preview={e.preview} />}
      {e.owner && (
        <>
          <div className="sec-t">신청한 분 {(e.applicants || []).length}</div>
          {(e.applicants || []).length ? e.applicants.map((a) => <div key={a.userId} className="card item"><div className="grow"><div className="b">{a.name}</div><div className="mute2">{a.ageGroup || ''}</div></div></div>) : <Empty>{e.applyType === 'LINK' ? '외부 링크로 신청받는 소식이에요' : '아직 신청한 분이 없어요'}</Empty>}
          {open && <button className="btn ghost" style={{ marginTop: 12 }} disabled={busy} onClick={async () => { if (await confirm('신청을 마감할까요?', '마감하면 동네 소식 목록에서 내려가요.', '마감', true)) run(async () => { await post(`/events/${e.id}/close`); await reload(); toast('마감했어요'); }); }}>신청 마감하기</button>}
        </>
      )}
      <div style={{ marginTop: 16 }}>
        {!open ? <div className="notice">신청이 끝난 소식이에요.</div>
          : e.applyType === 'LINK' ? (e.url && <a className="btn" href={e.url} target="_blank" rel="noopener noreferrer nofollow">안내 링크에서 신청하기 ↗</a>)
            : user && (e.myApplied
              ? <><div className="chk on" style={{ marginTop: 0 }}><span className="dotp" /><div className="grow"><b>신청했어요</b><div className="mute2">당일 {e.place || '행사 장소'}로 오시면 돼요</div></div></div><button className="btn ghost" style={{ marginTop: 8 }} disabled={busy} onClick={() => run(async () => { await post(`/events/${e.id}/cancel`); await reload(); toast('신청을 취소했어요'); })}>신청 취소</button></>
              : e.full ? <button className="btn" disabled style={{ opacity: 0.45 }}>정원이 찼어요</button>
                : <button className="btn" disabled={busy} onClick={() => run(async () => { await post(`/events/${e.id}/apply`); await reload(); toast('신청했어요. 담당자에게 알렸어요'); })}>앱에서 신청하기</button>)}
      </div>
    </Screen>
  );
}

/* ---------- 담당자: 우리 기관 소식 관리 ---------- */
export function OEvents() {
  const nav = useNavigate();
  const [evs] = useLoad(() => api('/org/events', { quiet: true }).catch(() => []), []);
  return (
    <Screen title="동네 소식 운영" noTabs>
      <div className="sec-t" style={{ marginTop: 0 }}><span>기관 소식 {(evs || []).length}</span><a role="button" tabIndex={0} onClick={() => nav('/o/events/new')}>+ 행사 올리기</a></div>
      <div className="mute2" style={{ margin: '-4px 0 4px' }}>우리 기관 행사·클래스를 동네 소식에 올려요. 링크를 넣으면 제목·사진을 가져와 카드로 보여 줘요.</div>
      {evs === undefined ? <Loading /> : evs.map((e) => (
        <div key={e.id} className="card item click" role="button" tabIndex={0} onClick={() => nav(`/news/${e.id}`)}><div className="ic"><Ic n="cal" s={20} /></div><div className="grow" style={{ minWidth: 0 }}><div className="b">{e.title}</div><div className="mute2">{e.date ? dlabel(e.date) : '날짜 미정'} · {e.applyType === 'LINK' ? '외부 링크 신청' : `앱 신청 ${e.applied}${e.capacity ? `/${e.capacity}` : ''}명`}</div></div><span className={'st ' + (e.status === 'OPEN' ? 'go' : 'end')}>{e.status === 'OPEN' ? '모집 중' : '마감'}</span></div>
      ))}
      {evs && !evs.length && <Empty>아직 올린 소식이 없어요</Empty>}
      <button className="btn soft" style={{ marginTop: 10 }} onClick={() => nav('/o/events/new')}><Ic n="plus" s={18} /> 행사·클래스 홍보 올리기</button>
    </Screen>
  );
}

/* ---------- 담당자: 행사·클래스 홍보 올리기 ---------- */
export function OEventNew() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [f, setF] = useState({ title: '', content: '', date: addDays(7), startHour: 14, place: '', capacity: 20, applyType: 'APP', url: '' });
  const [pv, setPv] = useState(null);
  const [pvBusy, setPvBusy] = useState(false);
  const [busy, run] = useBusy();
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const dates = Array.from({ length: 21 }, (_, i) => addDays(i + 1));
  const fetchPreview = async () => {
    if (!f.url.trim()) return toast('링크 주소를 먼저 넣어 주세요');
    setPvBusy(true);
    try {
      const p = await post('/link-preview', { url: f.url.trim() });
      setPv(p);
      if (!f.title.trim() && p.title) set('title', p.title.slice(0, 100));
    } catch { setPv(null); }
    setPvBusy(false);
  };
  const submit = () => run(async () => {
    if (!f.title.trim()) return toast('제목을 적어 주세요');
    if (f.applyType === 'LINK' && !f.url.trim()) return toast('외부 링크로 신청받으려면 링크 주소를 넣어 주세요');
    const body = { title: f.title.trim(), content: f.content, date: f.date || undefined, startHour: f.startHour, place: f.place, capacity: Number(f.capacity) || 0, applyType: f.applyType, url: f.url.trim() || undefined };
    if (pv && pv.url) { body.ogTitle = pv.title || undefined; body.ogImage = pv.image || undefined; body.ogDesc = pv.description || undefined; }
    const e = await post('/events', body);
    toast('기관 소식을 올렸어요. 근처 사용자 홈에 보여요');
    nav(`/news/${e.id}`, { replace: true });
  });
  return (
    <Screen title="기관 소식 올리기" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>기관이 여는 교실·모임·행사를 알려요. 링크만 넣으면 제목과 이미지를 가져와요.</p>
      <label className="f">기관 공지 링크</label>
      <div className="row"><input className="in grow" inputMode="url" placeholder="https://… 행사 안내 페이지" value={f.url} onChange={(e) => { set('url', e.target.value); setPv(null); }} aria-label="안내 링크" /><button type="button" className="btn soft sm" style={{ width: 'auto', padding: '0 14px', flex: '0 0 auto' }} disabled={pvBusy} onClick={fetchPreview}>{pvBusy ? '불러오는 중…' : '불러오기'}</button></div>
      <div className="mute2" style={{ marginTop: 6 }}>링크를 넣으면 그 페이지의 제목·사진을 가져와 카드로 보여 줘요.</div>
      {pv && <LinkCard url={pv.url} preview={pv} />}
      <label className="f">제목</label><input className="in" value={f.title} onChange={(e) => set('title', e.target.value)} placeholder="예) 스마트폰 사진 정리 교실" maxLength={100} />
      <label className="f">내용</label><textarea className="in" value={f.content} onChange={(e) => set('content', e.target.value)} placeholder="무엇을 하는지, 준비물, 대상 등을 적어 주세요" />
      <label className="f">날짜</label>
      <div className="tags">{dates.map((d) => <button key={d} type="button" className={'chip ' + (f.date === d ? 'on' : '')} onClick={() => set('date', d)}>{dlabel(d)}</button>)}</div>
      <label className="f">시작 시각</label>
      <select className="in" value={f.startHour} onChange={(e) => set('startHour', +e.target.value)}>{Array.from({ length: 27 }, (_, i) => 8 + i * 0.5).map((h) => <option key={h} value={h}>{ap(h)}</option>)}</select>
      <label className="f">장소</label><input className="in" value={f.place} onChange={(e) => set('place', e.target.value)} placeholder="예) 행복동 주민센터 1층 다목적실" />
      <label className="f">신청 방법</label>
      <div className="seg"><button type="button" className={f.applyType === 'APP' ? 'on' : ''} onClick={() => set('applyType', 'APP')}>앱에서 신청</button><button type="button" className={f.applyType === 'LINK' ? 'on' : ''} onClick={() => set('applyType', 'LINK')}>외부 링크로 신청</button></div>
      {f.applyType === 'APP' && <><label className="f">정원</label><div className="tags">{[[4, '4명'], [8, '8명'], [20, '20명'], [0, '제한 없음']].map(([n, t]) => <button key={n} type="button" className={'chip ' + (Number(f.capacity) === n ? 'on' : '')} onClick={() => set('capacity', n)}>{t}</button>)}</div><label className="f">직접 입력 (0이면 제한 없음)</label><input className="in" type="number" min={0} max={500} value={f.capacity} onChange={(e) => set('capacity', e.target.value)} aria-label="정원" /></>}
      <div className="notice" style={{ marginTop: 12 }}>기관 행사는 시간 통장 적립·차감이 없어요. 올리면 근처 사용자 홈의 "동네 기관 소식"에 보여요.</div>
      <button className="btn big-cta" style={{ marginTop: 16 }} disabled={busy} onClick={submit}>올리기</button>
    </Screen>
  );
}
