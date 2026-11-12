import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, Loading, Empty, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { ap, dlabel, addDays, AREAS } from '../lib/format.js';
import '../styles/meeting.css';

/* ---------- 동네 기관 소식 (B-64·C-15, 41판): 담당자가 올린 소식과 AI가 찾은 소식을 한 목록으로. 신청은 기관 페이지 링크로만 ---------- */
const host = (u) => { try { return new URL(u).host; } catch { return u; } };
const when = (e) => (e.date ? `${dlabel(e.date)}${e.startHour != null ? ` ${ap(e.startHour)}` : ''}` : '날짜 미정');

/** B-64 소식 카드: 누가 올렸는지(담당자가 올림 / AI가 찾음 · 출처 확인) · 제목 · 일시·장소 · 기관 페이지 링크 */
const BY = { ORG: ['go', '담당자가 올림'], AI: ['ok', 'AI가 찾음 · 출처 확인'], PUBLIC: ['end', '공공데이터 · 출처 확인'] };
function NewsCard({ n }) {
  const nav = useNavigate();
  const [k, t] = BY[n.kind] || BY.ORG;
  const whenTxt = n.when || when(n);
  return (
    <div className="card" style={{ marginTop: 10 }}>
      <div className="between"><span className="mute2">{n.orgName || '기관'}</span><span className={`st ${k}`}>{t}</span></div>
      <div className="b" style={{ fontSize: 16, marginTop: 4 }}>{n.title}</div>
      <div className="mute2">{whenTxt}{n.place || n.area ? ` · ${n.place || n.area}` : ''}</div>
      {n.url && <div className="mute2" style={{ marginTop: 4, wordBreak: 'break-all' }}><Ic n="building" s={16} /> {host(n.url)} ↗</div>}
      {n.aiFound && <div className="mute2" style={{ marginTop: 4 }}>{n.notice || 'AI가 찾은 정보 · 신청 전 기관에 확인'}</div>}
      <div className="btns" style={{ marginTop: 10 }}>
        <button className="btn ghost sm" onClick={() => nav(n.kind === 'ORG' ? `/news/${n.id}` : `/news/class/${n.id}`)}>요약 보기</button>
        {n.url ? <a className="btn sm" href={n.url} target="_blank" rel="noopener noreferrer nofollow">기관 페이지로 이동</a> : <button className="btn sm" disabled style={{ opacity: 0.45 }}>링크 없음</button>}
      </div>
    </div>
  );
}

export function News() {
  const { me } = useAuth();
  const { toast } = useUi();
  const [area, setArea] = useState('');
  const [data, reload] = useLoad(() => api(`/news${area ? `?area=${encodeURIComponent(area)}` : ''}`), [area]);
  const [busy, run] = useBusy();
  const myArea = me && me.area;
  const list = (data && data.items) || [];
  // 근처 소식 다시 찾기: 위치는 찾는 순간 한 번만 쓰고 저장하지 않는다. 출처 링크를 서버가 열어 확인한 것만 목록에 들어온다
  const research = () => run(async () => {
    const pos = await new Promise((res) => {
      if (!navigator.geolocation) return res(null);
      navigator.geolocation.getCurrentPosition((p) => res(p.coords), () => res(null), { enableHighAccuracy: false, timeout: 8000, maximumAge: 600000 });
    });
    const r = await post('/public-classes/search', pos ? { lat: pos.latitude, lng: pos.longitude } : {});
    if (r.searched) { toast(`AI가 ${r.found || 0}개를 찾았고 출처를 확인한 ${r.verified || 0}개를 보여 줘요`); await reload(); } else toast(r.message || '지금은 찾을 수 없어요');
  });
  return (
    <Screen title="동네 기관 소식" noTabs>
      <div className="locb">📍 {area || myArea || '동네 미설정'} 근처 <span>{area ? '고른 지역 기준' : '내 동네 기준'}</span></div>
      <div className="chips" role="tablist" aria-label="지역" style={{ marginTop: 10 }}>
        {[['', '가까운 순'], ...AREAS.map((a) => [a, a === myArea ? `${a} (내 동네)` : a])].map(([v, l]) => <button key={v || 'all'} role="tab" aria-selected={area === v} className={'chip ' + (area === v ? 'on' : '')} onClick={() => setArea(v)}>{l}</button>)}
      </div>
      <div className="ai" style={{ marginTop: 10 }}>
        <div className="ah"><Spk /><span className="mod m1">소식 검색 AI</span><Mod n={4} /></div>
        <div className="small">근처 기관의 교실·모임·행사를 찾았어요. <b>출처 링크를 확인한 소식만</b> 보여요.</div>
        <div className="notice" style={{ marginTop: 8 }}>AI가 찾은 정보라서 틀릴 수 있어요. 날짜·장소·신청 방법은 기관 페이지나 전화로 꼭 확인해 주세요.</div>
        {data && data.searchAvailable && <button type="button" className="btn soft sm" style={{ marginTop: 10 }} disabled={busy} onClick={research}><Ic n="pin" s={16} /> {busy ? 'AI가 근처 기관 소식을 찾는 중…' : '접속 위치로 다시 찾기'}</button>}
      </div>
      {data === undefined ? <Loading /> : list.length ? list.map((n) => <NewsCard key={n.key} n={n} />) : <Empty>{area ? `${area}에 올라온 기관 소식이 없어요` : '지금 올라온 기관 소식이 없어요'}</Empty>}
      <div className="mute2" style={{ marginTop: 10 }}>신청과 자세한 내용은 기관 페이지에서 해요. 기관 행사는 시간 통장 적립·차감이 없어요.</div>
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
  const { toast, confirm } = useUi();
  const [e, reload] = useLoad(() => api(`/events/${id}`), [id]);
  const [busy, run] = useBusy();
  if (e === undefined) return <Screen title="동네 기관 소식" noTabs><Loading /></Screen>;
  if (!e) return <Screen title="동네 기관 소식" noTabs><Empty>소식을 찾을 수 없어요</Empty></Screen>;
  const open = e.status === 'OPEN';
  return (
    <Screen title="동네 기관 소식" noTabs>
      <div className="ev-card" style={{ cursor: 'default', marginTop: 0 }}>
        <div className="ev-cv" style={{ height: 150 }}>{e.preview && e.preview.image ? <img src={e.preview.image} alt="" referrerPolicy="no-referrer" onError={(x) => { x.currentTarget.style.display = 'none'; }} /> : <Ic n="cal" s={48} />}<span className="ev-org">{e.orgName || '기관'}</span></div>
        <div className="ev-b"><div className="between"><div className="ev-t" style={{ fontSize: 20 }}>{e.title}</div><span className="st go">담당자가 올림</span></div>{e.content && <div className="small muted" style={{ marginTop: 8, whiteSpace: 'pre-line' }}>{e.content}</div>}</div>
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <dl className="kv"><dt>일시</dt><dd>{when(e)}</dd><dt>장소</dt><dd>{e.place || '-'}</dd><dt>신청</dt><dd>기관 페이지에서 신청</dd></dl>
      </div>
      {e.url && <LinkCard url={e.url} preview={e.preview} />}
      <div style={{ marginTop: 16 }}>
        {!open ? <div className="notice">내려간 소식이에요.</div>
          : e.url ? <a className="btn" href={e.url} target="_blank" rel="noopener noreferrer nofollow">기관 페이지로 이동 ↗</a>
            : <div className="notice">기관 페이지 링크가 없는 예전 소식이에요. 기관에 직접 문의해 주세요.</div>}
      </div>
      <div className="mute2" style={{ marginTop: 10 }}>신청과 자세한 내용은 기관 페이지에서 해요. 기관 행사는 시간 통장 적립·차감이 없어요.</div>
      {e.owner && open && <button className="btn ghost" style={{ marginTop: 12 }} disabled={busy} onClick={async () => { if (await confirm('소식을 내릴까요?', '내리면 동네 기관 소식 목록에서 빠져요.', '내리기', true)) run(async () => { await post(`/events/${e.id}/close`); await reload(); toast('소식을 내렸어요'); }); }}>소식 내리기</button>}
    </Screen>
  );
}

/* ---------- 담당자: 우리 기관 소식 관리 ---------- */
export function OEvents() {
  const nav = useNavigate();
  const [evs] = useLoad(() => api('/org/events', { quiet: true }).catch(() => []), []);
  return (
    <Screen title="기관 소식 운영" noTabs>
      <div className="sec-t" style={{ marginTop: 0 }}><span>기관 소식 {(evs || []).length}</span><a role="button" tabIndex={0} onClick={() => nav('/o/events/new')}>+ 소식 올리기</a></div>
      <div className="mute2" style={{ margin: '-4px 0 4px' }}>우리 기관의 교실·모임·행사 소식을 올려요. 사용자의 동네 기관 소식에 함께 보이고, 신청은 기관 페이지 링크에서 받아요.</div>
      {evs === undefined ? <Loading /> : evs.map((e) => (
        <div key={e.id} className="card item click" role="button" tabIndex={0} onClick={() => nav(`/news/${e.id}`)}><div className="ic"><Ic n="cal" s={20} /></div><div className="grow" style={{ minWidth: 0 }}><div className="b">{e.title}</div><div className="mute2">{e.date ? dlabel(e.date) : '날짜 미정'}{e.place ? ` · ${e.place}` : ''} · {e.url ? host(e.url) : '링크 없음'}</div></div><span className={'st ' + (e.status === 'OPEN' ? 'go' : 'end')}>{e.status === 'OPEN' ? '올림' : '내림'}</span></div>
      ))}
      {evs && !evs.length && <Empty>아직 올린 소식이 없어요</Empty>}
      <button className="btn soft" style={{ marginTop: 10 }} onClick={() => nav('/o/events/new')}><Ic n="plus" s={18} /> 교실·모임·행사 소식 올리기</button>
    </Screen>
  );
}

/* ---------- 담당자: 행사·클래스 홍보 올리기 ---------- */
export function OEventNew() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [f, setF] = useState({ title: '', content: '', date: addDays(7), startHour: 14, place: '', url: '' });
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
    if (!f.url.trim()) return toast('기관 페이지 링크를 넣어 주세요. 신청은 그 페이지에서 받아요');
    const body = { title: f.title.trim(), content: f.content, date: f.date || undefined, startHour: f.startHour, place: f.place, url: f.url.trim() };
    if (pv && pv.url) { body.ogTitle = pv.title || undefined; body.ogImage = pv.image || undefined; body.ogDesc = pv.description || undefined; }
    const e = await post('/events', body);
    toast('기관 소식을 올렸어요. 근처 사용자의 동네 기관 소식에 보여요');
    nav(`/news/${e.id}`, { replace: true });
  });
  return (
    <Screen title="기관 소식 올리기" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>기관이 여는 교실·모임·행사를 제목·일시·장소와 기관 페이지 링크로 알려요. 자세한 내용과 신청은 기관 페이지에서 받아요.</p>
      <label className="f">기관 페이지 링크 (필수)</label>
      <div className="row"><input className="in grow" inputMode="url" placeholder="https://… 행사 안내 페이지" value={f.url} onChange={(e) => { set('url', e.target.value); setPv(null); }} aria-label="안내 링크" /><button type="button" className="btn soft sm" style={{ width: 'auto', padding: '0 14px', flex: '0 0 auto' }} disabled={pvBusy} onClick={fetchPreview}>{pvBusy ? '불러오는 중…' : '불러오기'}</button></div>
      <div className="mute2" style={{ marginTop: 6 }}>링크를 넣으면 그 페이지의 제목·사진을 가져와 카드로 보여 줘요.</div>
      {pv && <LinkCard url={pv.url} preview={pv} />}
      <label className="f">제목</label><input className="in" value={f.title} onChange={(e) => set('title', e.target.value)} placeholder="예) 스마트폰 사진 정리 교실" maxLength={100} />
      <label className="f">내용</label><textarea className="in" value={f.content} onChange={(e) => set('content', e.target.value)} placeholder="무엇을 하는지 짧게 적어 주세요. 자세한 내용은 기관 페이지에서 봐요" />
      <label className="f">날짜</label>
      <div className="tags">{dates.map((d) => <button key={d} type="button" className={'chip ' + (f.date === d ? 'on' : '')} onClick={() => set('date', d)}>{dlabel(d)}</button>)}</div>
      <label className="f">시작 시각</label>
      <select className="in" value={f.startHour} onChange={(e) => set('startHour', +e.target.value)}>{Array.from({ length: 27 }, (_, i) => 8 + i * 0.5).map((h) => <option key={h} value={h}>{ap(h)}</option>)}</select>
      <label className="f">장소</label><input className="in" value={f.place} onChange={(e) => set('place', e.target.value)} placeholder="예) 행복동 주민센터 1층 다목적실" />
      <div className="notice" style={{ marginTop: 12 }}>기관 행사는 시간 통장 적립·차감이 없어요. 올리면 근처 사용자의 "동네 기관 소식"에 AI가 찾은 소식과 함께 보여요.</div>
      <button className="btn big-cta" style={{ marginTop: 16 }} disabled={busy} onClick={submit}>올리기</button>
    </Screen>
  );
}
