import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Loading, Empty, useLoad, useBusy, RuleNote } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api, post, del } from '../api.js';
import { speak, stopSpeak } from './voice.jsx';
import '../styles/meeting.css';
import '../styles/publicClass.css';

/* ---------- 기관 클래스 추천 (2026-11-10): 공공기관 강좌를 모아 추천, 신청은 기관 사이트에서 ---------- */
const CHIPS = ['전체', '디지털', '정서·건강', '생활'];
const SRC = { AI_SEARCH: ['ai', 'AI가 찾은 정보'], PUBLIC_DATA: ['pub', '공공데이터'], ORG: ['org', '기관 소식'] };
const AI_NOTE = 'AI가 찾은 정보 · 신청 전 기관에 확인';
const host = (u) => { try { return new URL(u).host; } catch { return u; } };
export const classLink = (c) => (c.source === 'ORG' && c.orgEventId ? `/news/${c.orgEventId}` : `/news/class/${c.id}`);

function SrcBadge({ c }) {
  const [k, t] = SRC[c.source] || ['pub', '기관'];
  return <span className={`pc-src ${k}`}>{t}</span>;
}

export function ClassCard({ c, onOpen }) {
  const nav = useNavigate();
  return (
    <div className="pc-card" role="button" tabIndex={0} onClick={() => (onOpen ? onOpen(c) : nav(classLink(c)))} onKeyDown={(e) => { if (e.key === 'Enter') nav(classLink(c)); }}>
      <div className="pc-top"><span className="pc-org">{c.orgName || '공공기관'}</span><SrcBadge c={c} /></div>
      <div className="pc-t">{c.title}</div>
      <div className="pc-m">
        <span><Ic n="clock" s={14} />{c.when}{c.schedule ? ` · ${c.schedule}` : ''}</span>
        {c.place && <span><Ic n="pin" s={14} />{c.place}</span>}
        {c.target && <span><Ic n="users" s={14} />{c.target}</span>}
        {c.fee && <span><Ic n="coin" s={14} />{c.fee}</span>}
      </div>
      {c.reason && <div className="pc-why"><Spk /><span>{c.reason}</span></div>}
      {c.aiFound && <div className="pc-ainote" role="note"><Ic n="flag" s={14} /> {c.notice || AI_NOTE}</div>}
    </div>
  );
}

/** 동네 소식 맨 위 "가까운 기관 클래스" */
export function ClassSection() {
  const { toast } = useUi();
  const [data, reload] = useLoad(() => api('/public-classes/recommend', { quiet: true }).catch(() => null), []);
  const [cat, setCat] = useState('전체');
  const [busy, run] = useBusy();
  const [more, setMore] = useState(false);
  if (data === undefined) return <div className="pc-sec"><div className="sec-t" style={{ marginTop: 0 }}><span>가까운 기관 클래스</span></div><Loading /></div>;
  if (!data) return null;
  const items = (data.items || []).filter((c) => cat === '전체' || c.category === cat);
  const shown = more ? items : items.slice(0, 4);
  const research = () => run(async () => {
    const go = async (lat, lng) => {
      const r = await post('/public-classes/search', lat != null ? { lat, lng } : {});
      if (r.searched) { toast(`AI가 ${r.found || 0}개를 찾았고 ${r.verified || 0}개를 확인했어요`); await reload(); } else toast(r.message || '지금은 찾을 수 없어요');
    };
    const pos = await new Promise((res) => {
      if (!navigator.geolocation) return res(null);
      navigator.geolocation.getCurrentPosition((p) => res(p.coords), () => res(null), { enableHighAccuracy: false, timeout: 8000, maximumAge: 600000 });
    });
    await go(pos ? pos.latitude : null, pos ? pos.longitude : null);
  });
  return (
    <div className="pc-sec">
      <div className="sec-t" style={{ marginTop: 0 }}><span>가까운 기관 클래스 <span className="slide-cnt">{(data.items || []).length}개</span></span></div>
      <div className="mute2" style={{ margin: '-4px 0 6px' }}>주민센터·평생학습관·복지관이 여는 강좌예요. 신청은 기관 사이트에서 해요{data.basis && data.basis.area ? ` · ${data.basis.area} 기준` : ''}.</div>
      <div className="pc-chips" role="tablist" aria-label="강좌 분류">{CHIPS.map((k) => <button key={k} type="button" role="tab" aria-selected={cat === k} className={'chip ' + (cat === k ? 'on' : '')} onClick={() => setCat(k)}>{k}</button>)}</div>
      {shown.length ? shown.map((c) => <ClassCard key={c.id} c={c} />) : <Empty>{cat === '전체' ? '가까운 기관 클래스를 아직 찾지 못했어요' : `${cat} 강좌가 아직 없어요`}</Empty>}
      {items.length > 4 && !more && <button className="btn ghost sm" style={{ marginTop: 10 }} onClick={() => setMore(true)}>{items.length - 4}개 더 보기</button>}
      {data.searchAvailable && <button type="button" className="loc-btn" disabled={busy} onClick={research}><Ic n="pin" s={20} /> {busy ? 'AI가 찾고 확인하는 중…' : '내 위치로 다시 찾기'} <span className="mod m4" style={{ marginLeft: 2 }}>AI</span></button>}
      {data.searchAvailable && <div className="mute2 center" style={{ marginTop: 6 }}>위치는 찾을 때 한 번만 쓰고 저장하지 않아요 · 하루 한 번</div>}
      <RuleNote source={data.source} />
      <div className="sec-t"><span>기관 행사 소식</span></div>
    </div>
  );
}

/** 사용자 홈 "이번 주 가까운 클래스" 한 줄 카드 */
export function WeekClassCard() {
  const nav = useNavigate();
  const [data] = useLoad(() => api('/public-classes/recommend', { quiet: true }).catch(() => null), []);
  const items = (data && data.items) || [];
  const soon = (x) => x.startDate && (new Date(x.startDate) - new Date()) / 86400000 <= 7;
  const c = items.find(soon) || items[0];
  if (!c) return null;
  return (
    <div className="pc-week" role="button" tabIndex={0} onClick={() => nav(classLink(c))} aria-label={`가까운 기관 클래스: ${c.title}`}>
      <span className="ic"><Ic n="cap" s={22} /></span>
      <div className="grow" style={{ minWidth: 0 }}><div className="t">{soon(c) ? '이번 주 가까운 클래스' : '가까운 기관 클래스'}</div><div className="b">{c.title}</div><div className="d">{c.orgName || '공공기관'} · {c.when}{c.reason ? ` · ${c.reason}` : ''}</div></div>
      <span style={{ color: 'var(--mute)' }}>›</span>
    </div>
  );
}

/** 상세 (/news/class/:id) */
export function ClassDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { toast } = useUi();
  const [c] = useLoad(() => api(`/public-classes/${id}`), [id]);
  const [playing, setPlaying] = useState(false);
  if (c === undefined) return <Screen title="기관 클래스" noTabs><Loading /></Screen>;
  if (!c) return <Screen title="기관 클래스" noTabs><Empty>강좌를 찾을 수 없어요</Empty></Screen>;
  const listen = async () => {
    if (playing) { stopSpeak(); setPlaying(false); return; }
    setPlaying(true);
    const ok = await speak(c.easySummary, { force: true });
    setPlaying(false);
    if (!ok) toast('이 브라우저는 소리로 읽기를 지원하지 않아요');
  };
  const companion = () => nav(`/write/need?prefill=${encodeURIComponent(c.companionText)}`);
  return (
    <Screen title="기관 클래스" noTabs>
      <div className="pc-card" style={{ cursor: 'default', marginTop: 0 }}>
        <div className="pc-top"><span className="pc-org">{c.orgName || '공공기관'}</span><SrcBadge c={c} /></div>
        <div className="pc-t" style={{ fontSize: 20 }}>{c.title}</div>
        {c.description && <div className="small muted" style={{ marginTop: 8, whiteSpace: 'pre-line' }}>{c.description}</div>}
        {c.aiFound && <div className="pc-ainote" role="note"><Ic n="flag" s={14} /> {c.notice || AI_NOTE}</div>}
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <dl className="kv">
          <dt>일정</dt><dd>{c.when}{c.endDate && c.endDate !== c.startDate ? ` ~ ${c.endDate.slice(5).replace('-', '/')}` : ''}{c.schedule ? ` · ${c.schedule}` : ''}</dd>
          <dt>장소</dt><dd>{c.place || '기관에 확인'}</dd>
          <dt>대상</dt><dd>{c.target || '기관에 확인'}</dd>
          <dt>수강료</dt><dd>{c.fee || '기관에 확인'}</dd>
          {c.capacity && <><dt>정원</dt><dd>{c.capacity}</dd></>}
          <dt>신청</dt><dd>{c.applyHow || (c.applyUrl ? '기관 홈페이지' : '기관에 문의')}</dd>
        </dl>
      </div>
      <button className="btn soft" style={{ marginTop: 12 }} onClick={listen}><Ic n="mic" s={20} /> {playing ? '그만 듣기' : '쉬운 말로 소리로 듣기'}</button>
      {c.applyUrl
        ? <a className="btn" style={{ marginTop: 10 }} href={c.applyUrl} target="_blank" rel="noopener noreferrer nofollow">신청하기 · {host(c.applyUrl)} ↗</a>
        : <div className="notice" style={{ marginTop: 10 }}>{c.applyHow ? `${c.applyHow}으로 신청해요.` : '신청 방법은 기관에 물어보세요.'}</div>}
      <div className="ai" style={{ marginTop: 14 }}>
        <div className="ah"><Ic n="heart" s={16} /> 혼자 가기 어려우세요?</div>
        <div className="small">이웃 봉사자에게 같이 가 달라고 요청할 수 있어요. 요청서를 미리 채워 드릴게요. 올리기는 직접 해요.</div>
        <button className="btn sm" style={{ marginTop: 10 }} onClick={companion}><Ic n="users" s={18} /> 같이 갈 봉사자 요청</button>
        <div className="mute2" style={{ marginTop: 8 }}>{c.timeNote || '강좌는 시간 통장 시간을 쓰지 않아요. 동행 봉사만 시간으로 정산돼요.'}</div>
      </div>
    </Screen>
  );
}

/** 담당자 대상 상세 안: "이 어르신께 맞는 기관 클래스" */
export function ElderClasses({ elderId }) {
  const nav = useNavigate();
  const [data] = useLoad(() => api(`/org/elders/${elderId}/public-classes`, { quiet: true }).catch(() => null), [elderId]);
  const [open, setOpen] = useState(null);
  if (!data) return null;
  const items = (data.items || []).slice(0, 3);
  return (
    <>
      <div className="sec-t"><span>이 어르신께 맞는 기관 클래스</span></div>
      {items.length ? items.map((c) => (
        <div key={c.id}>
          <ClassCard c={c} onOpen={() => setOpen(open === c.id ? null : c.id)} />
          {open === c.id && (
            <div className="btns" style={{ marginTop: 8 }}>
              <button className="btn ghost sm" onClick={() => nav(classLink(c))}>강좌 자세히</button>
              <button className="btn sm" onClick={async () => {
                let text = `'${c.title}' 강좌에 같이 가 주세요 (동행)`;
                if (c.source !== 'ORG') { try { text = (await api(`/public-classes/${c.id}`, { quiet: true })).companionText || text; } catch { /* 기본 문장 */ } }
                nav(`/write/need?elder=${elderId}&prefill=${encodeURIComponent(text)}`);
              }}>동행 요청 대신 만들기</button>
            </div>
          )}
        </div>
      )) : <Empty>대상 동네에 맞는 기관 클래스가 아직 없어요</Empty>}
      <div className="mute2" style={{ marginTop: 6 }}>{(data.basis && data.basis.area) || ''} · 필요한 도움 기준으로 골랐어요. 카드를 누르면 동행 요청을 대신 만들 수 있어요.</div>
    </>
  );
}

/* ---------- 운영자: 기관 클래스 관리 (/a/classes) ---------- */
export function APublicClasses() {
  const { toast, confirm } = useUi();
  const [src, setSrc] = useState('');
  const [data, reload] = useLoad(() => api(`/admin/public-classes${src ? `?source=${src}` : ''}`), [src]);
  const [file, setFile] = useState(null);
  const [imp, setImp] = useState(null);
  const [region, setRegion] = useState('');
  const [sres, setSres] = useState(null);
  const [busy, run] = useBusy();
  const upload = () => run(async () => {
    if (!file) return toast('CSV 파일을 골라 주세요');
    const fd = new FormData();
    fd.append('file', file);
    try { const r = await api('/admin/public-classes/import', { method: 'POST', form: fd }); setImp(r); toast(`${r.imported}개를 새로 넣었어요`); await reload(); } catch { /* 토스트 */ }
  });
  const research = () => run(async () => {
    if (!region.trim()) return toast('찾을 지역(시군구·동)을 적어 주세요');
    try { const r = await post('/admin/public-classes/search', { region: region.trim(), force: true }); setSres(r); if (r.searched) await reload(); else toast(r.message || '찾지 못했어요'); } catch { /* 토스트 */ }
  });
  const counts = (data && data.counts) || {};
  return (
    <Screen title="기관 클래스" noTabs>
      <div className="mute2">공공기관 강좌를 모아 사용자에게 추천해요. 공공데이터 파일·AI 웹 검색·동네 소식 기관 행사가 한 목록에 모여요.</div>
      <div className="card" style={{ marginTop: 10 }}>
        <dl className="kv"><dt>사용자에게 보임</dt><dd>{counts.visible ?? '-'}개 (숨김 {counts.hidden ?? 0})</dd><dt>공공데이터</dt><dd>{counts.PUBLIC_DATA ?? 0}개</dd><dt>AI 웹 검색</dt><dd>{counts.AI_SEARCH ?? 0}개 · {data && data.searchAvailable ? '켜짐' : '꺼짐 (Gemini 키 필요)'}</dd><dt>기관 소식</dt><dd>{counts.ORG ?? 0}개</dd></dl>
      </div>

      <div className="pc-imp">
        <div className="b">공공데이터 CSV 가져오기</div>
        <div className="mute2" style={{ marginTop: 4 }}>공공데이터포털 "전국평생학습강좌표준데이터" CSV(UTF-8·CP949 모두 돼요). 끝난 강좌는 빼고 넣어요.</div>
        <input type="file" accept=".csv,text/csv" style={{ marginTop: 10 }} aria-label="CSV 파일" onChange={(e) => setFile(e.target.files && e.target.files[0])} />
        <button className="btn sm" style={{ marginTop: 10 }} disabled={busy} onClick={upload}>가져오기</button>
        {imp && <div className="small" style={{ marginTop: 10 }}>글자 코드 {imp.encoding} · 전체 {imp.total}줄 → 새로 {imp.imported} · 고침 {imp.updated} · 끝난 강좌 {imp.skippedEnded} · 빈 줄 {imp.skippedInvalid}<div className="mute2" style={{ marginTop: 4 }}>맞춘 열: {Object.entries(imp.mapped || {}).map(([k, v]) => `${v}`).join(', ')}{(imp.unmapped || []).length ? ` · 원본으로만 보관: ${imp.unmapped.join(', ')}` : ''}</div></div>}
      </div>

      <div className="pc-imp">
        <div className="b">지역 다시 찾기 <span className="mod m4">AI</span></div>
        <div className="mute2" style={{ marginTop: 4 }}>Gemini 구글 검색으로 찾고, 서버가 안내 페이지를 직접 열어 확인한 것만 보여요(주소 없는 항목은 버림).</div>
        <div className="row" style={{ marginTop: 10 }}><input className="in grow" value={region} onChange={(e) => setRegion(e.target.value)} placeholder="예) 금천구 독산동" aria-label="지역" /><button className="btn sm" style={{ width: 'auto', padding: '0 14px', flex: '0 0 auto' }} disabled={busy || !(data && data.searchAvailable)} onClick={research}>{busy ? '찾는 중…' : '다시 찾기'}</button></div>
        {data && !data.searchAvailable && <div className="mute2" style={{ marginTop: 6 }}>지금은 꺼져 있어요(Gemini 키가 없거나 다른 AI 공급자). CSV 가져오기는 쓸 수 있어요.</div>}
        {sres && sres.searched && <div className="small" style={{ marginTop: 8 }}>후보 {sres.found} · 주소 없어 버림 {sres.dropped} · 확인 {sres.verified} · 확인 못 해 숨김 {sres.hidden}</div>}
      </div>

      <div className="pc-chips" style={{ marginTop: 14 }}>{[['', '전체'], ['PUBLIC_DATA', '공공데이터'], ['AI_SEARCH', 'AI 검색'], ['ORG', '기관 소식']].map(([k, t]) => <button key={k} type="button" className={'chip ' + (src === k ? 'on' : '')} onClick={() => setSrc(k)}>{t}</button>)}</div>
      {data === undefined ? <Loading /> : (data && data.items || []).length ? data.items.map((c) => (
        <div key={c.id} className={'pc-adm ' + (c.visible ? '' : 'off')}>
          <div className="pc-top"><span className="pc-org">{c.orgName || '-'}</span><span><SrcBadge c={c} /> <span className={'st ' + (c.verified ? 'ok' : 'no')}>{c.verified ? '확인됨' : '확인 못 함'}</span></span></div>
          <div className="b" style={{ marginTop: 4 }}>{c.title}</div>
          <div className="mute2">{c.when} · {c.region || '-'} · {c.category}{c.hidden ? ' · 숨김' : ''}{c.searchKey ? ` · 검색 "${c.searchKey}"` : ''}</div>
          {c.applyUrl && <div className="mute2" style={{ wordBreak: 'break-all' }}>{c.applyUrl}{c.pageTitle ? ` (${c.pageTitle})` : ''}</div>}
          <div className="btns" style={{ marginTop: 8 }}>
            <button className="btn ghost sm" disabled={busy || (!c.verified && c.hidden)} onClick={() => run(async () => { try { await post(`/admin/public-classes/${c.id}/hide`, { hidden: !c.hidden }); await reload(); toast(c.hidden ? '보이게 했어요' : '숨겼어요'); } catch { /* 토스트 */ } })}>{c.hidden ? '보이기' : '숨기기'}</button>
            {c.source !== 'ORG' && <button className="btn ghost sm" disabled={busy} onClick={async () => { if (await confirm('이 강좌를 지울까요?', '목록에서 완전히 지워요.', '삭제', true)) run(async () => { try { await del(`/admin/public-classes/${c.id}`); await reload(); toast('지웠어요'); } catch { /* 토스트 */ } }); }}>삭제</button>}
          </div>
        </div>
      )) : <Empty>아직 모은 강좌가 없어요</Empty>}
    </Screen>
  );
}
