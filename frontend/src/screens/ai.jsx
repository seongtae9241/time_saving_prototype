import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Spk, Mod, CatIc, Avatar, Stars, AiLoading, RuleNote, useLoad } from '../ui/bits.jsx';
import { DIc, Pav } from '../ui/proto.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { post } from '../api.js';
import { hh, dlabel, lvName, lvWeight, LV, W, fmt } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { useSpeechInput } from './voice.jsx';
import { useLocationAsk } from '../ui/location.jsx';
import { ForwardSheet } from './orgExtra.jsx'; // 담당자에게 전달하기(전달하겠다고 눌렀을 때만)

/* ---------- 공공 서비스 카드 (프로토타입 pubCards) ---------- */
export function PubCards({ list, big, ai }) {
  if (!list || !list.length) return null;
  return (
    <>
      {list.map((p, i) => (
        <div className="policy" style={{ marginTop: 8 }} key={p.id || i}>
          <div>
            <b style={big ? { fontSize: 19 } : null}>{p.name}</b>
            <span style={big ? { fontSize: 17 } : null}>
              {p.where && <><b style={{ display: 'inline', fontSize: 13 }}>위치</b> {p.where}<br /></>}
              {p.target && <><b style={{ display: 'inline', fontSize: 13 }}>대상</b> {p.target}<br /></>}
              {p.content && <><b style={{ display: 'inline', fontSize: 13 }}>내용</b> {p.content}<br /></>}
              <b style={{ display: 'inline', fontSize: 13 }}>신청</b> {p.applyHow}{p.contact ? ` · ☎ ${p.contact}` : ''}
            </span>
            <div className="plinks">
              {/\d{3,}/.test(p.contact || '') && <a className="plink ghost" href={`tel:${String(p.contact).replace(/[^\d]/g, '')}`}><Ic n="phone" s={16} /> {p.contact}</a>}
              {/복지로/.test(`${p.applyHow} ${p.content}`) && <a className="plink" href="https://www.bokjiro.go.kr" target="_blank" rel="noopener noreferrer"><Ic n="building" s={16} /> 복지로 바로가기 ↗</a>}
            </div>
          </div>
        </div>
      ))}
      <div className="mute2" style={{ marginTop: 8 }}>{ai ? 'AI가 위치를 보고 찾은 정보예요 · 저장하지 않아요 · 전화번호·자격은 꼭 기관에 확인하세요' : '출처: 공공 서비스 DB (운영자 등록) · 자격은 기관에서 확인해요'}</div>
    </>
  );
}

/** B-67·B-09: 금지 요청(불가)의 대체 안내 → 근처에서 받을 수 있는 공공 서비스 안내(위치 기준 안내 화면)로 가는 버튼 */
export function NearbyPublicBtn({ f, style }) {
  const nav = useNavigate();
  const n = (f && f.nearby) || {};
  const link = n.link || '/public?near=1';
  return (
    <>
      <button type="button" className="btn soft sm" style={{ marginTop: 10, ...style }} onClick={() => nav(link)}><Ic n="pin" s={16} /> {n.label || '근처에서 받을 수 있는 공공 서비스 찾기'}</button>
      <div className="mute2" style={{ marginTop: 4 }}>지금 계신 곳 기준으로 찾아요. 위치를 쓰지 않으면 사는 동네 기준으로 안내해요.</div>
    </>
  );
}

/* ---------- 금지 요청·난이도 판단 결과 (B-08, B-09) ---------- */
export function FilterBox({ f, diff }) {
  const nav = useNavigate();
  if (!f) return null;
  const lvT = { ALLOW: '허용', CAUTION: '주의', BLOCK: '불가' }[f.level] || f.level;
  if (f.level === 'BLOCK') {
    return (
      <div className="ai warn">
        <div className="ah"><Spk />이 요청은 보낼 수 없어요 · 불가<Mod n={2} /></div>
        <div className="small">{f.reason}</div>
        {f.alternatives && f.alternatives.length ? <><div className="small" style={{ marginTop: 6 }}>대신 받을 수 있는 공공 서비스예요.</div><PubCards list={f.alternatives} /></> : null}
        <NearbyPublicBtn f={f} />
        <RuleNote source={f.source} />
      </div>
    );
  }
  return (
    <div className={'ai ' + (f.level === 'CAUTION' ? 'amber' : '')}>
      <div className="ah"><Spk />보낼 수 있어요 · {lvT}<Mod n={2} /></div>
      {f.reason && <div className="small">{f.reason}</div>}
      {diff && <><div className="between" style={{ marginTop: 8 }}><span className="small">난이도 제안</span><b>{lvName(diff.level)} {lvWeight(diff.level, diff.weight).toFixed(1)}</b></div>{diff.reason && <div className="small muted">근거: {diff.reason}</div>}</>}
      <RuleNote source={(diff && diff.source) || f.source} />
    </div>
  );
}

export function AskBox({ title, sub, placeholder, quick, onAsk, q, setQ, mic }) {
  const sp = useSpeechInput({ onFinal: (t) => { setQ(t); onAsk(t); }, onInterim: setQ });
  return (
    <div className="ask-hero">
      <div className="b" style={{ fontSize: 19 }}>{title}</div><div className="mute2" style={{ marginTop: 4 }}>{sub}</div>
      <form className="composer" onSubmit={(e) => { e.preventDefault(); if (q.trim()) onAsk(q.trim()); }}>
        {mic && <button type="button" className={'mic' + (sp.listening ? ' on' : '')} aria-label="음성 입력" onClick={sp.toggle}><Ic h={I.mic} /></button>}
        <input className="in" placeholder={placeholder} value={q} onChange={(e) => setQ(e.target.value)} aria-label={title} />
        <button type="submit" aria-label="보내기"><Ic h={I.send} /></button>
      </form>
      {sp.msg && <div className="mute2" style={{ marginTop: 6 }}>{sp.msg}</div>}
      <div className="qr-row" style={{ marginTop: 10 }}><div className="qr">{quick.map((x) => <button key={x} type="button" onClick={() => { setQ(x); onAsk(x); }}>{x}</button>)}</div></div>
    </div>
  );
}

const CAT_DESC = { '동행': '병원·약국까지 함께 가요 · 책임 1.5 · 보통 2~3시간', '장보기·심부름': '대신 장을 보거나 같이 가요 · 수고 1.2 · 1~2시간', '디지털 도움': '폰 설정·영상통화·키오스크 · 기본 1.0 · 1시간', '생활 정리': '옷장·서랍 정리 · 기본 1.0 · 2시간', '가벼운 집안일': '집안일 거들기 · 수고 1.2', '말동무·정서': '안부 방문·이야기 나누기 · 기본 1.0 · 1시간' };

/** 추천 한 섹션(단골 / 가까운 사람). 왜 맞는지(fitText)는 서버가 계산한 값 그대로. */
function RecSection({ title, sub, icon, list, empty, action }) {
  const nav = useNavigate();
  return (
    <div className="ai" style={{ marginTop: 8 }}>
      <div className="ah"><Spk />{title}<Mod n={4} /></div>
      <div className="row" style={{ gap: 10 }}><DIc k={icon} size={36} /><div className="mute2 grow">{sub}</div></div>
      {list.length ? list.map((p) => {
        const name = p.elderDisplayName || p.ownerName;
        return (
          <div key={p.id} className="pc2 click" style={{ marginTop: 10, boxShadow: 'none' }} role="button" tabIndex={0} onClick={() => nav(`/post/${p.id}`)} onKeyDown={(e) => { if (e.key === 'Enter') nav(`/post/${p.id}`); }}>
            <div className="top"><Pav name={name} s={40} /><div className="grow"><div className="who">{name}{p.fit && p.fit.regular && <span className="badge">★ 단골</span>}</div><div className="sub">{dlabel(p.date)} {hh(p.startHour)}~{hh(p.endHour)}{p.distanceKm != null ? ` · ${p.distanceKm}km` : ''}{p.owner ? <> · <Stars r={p.owner.ratingAvg} /></> : null}</div></div>›</div>
            <div className="small" style={{ marginTop: 8 }}>{p.type === 'OFFER' ? (p.note || p.title) : p.title}</div>
            {p.fitText && <div className="rec-why" style={{ marginTop: 6 }}><Spk /> {p.fitText}</div>}
          </div>
        );
      }) : <div className="small muted" style={{ marginTop: 10 }}>{empty}</div>}
      {action}
    </div>
  );
}

/* ---------- AI 도움 추천 ---------- */
export function Recommend() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const [q, setQ] = useState(sp.get('q') || '');
  const [asked, setAsked] = useState(null);
  const [res, setRes] = useState(null);
  const ask = async (t) => {
    setAsked(t); setRes({ loading: true });
    try { setRes(await post('/ai/recommend', { question: t })); } catch { setRes(null); }
  };
  useLoad(async () => { if (sp.get('q')) ask(sp.get('q')); return null; }, []); // 기능 U-27: 플래너에서 적은 문장으로 바로 찾는다
  return (
    <Screen title="AI 도움 추천" noTabs>
      <AskBox mic title="어떤 일이 힘드세요?" sub="우리 사이트에서 받을 수 있는 도움과 도와줄 수 있는 사람을 찾아드려요" placeholder="예) 무릎이 아파서 장보기가 힘들어요" q={q} setQ={setQ} onAsk={ask}
        quick={['무릎이 아파서 병원 가기랑 장보기가 힘들어요', '폰으로 손주랑 영상통화 하고 싶어요', '이사하고 짐 정리가 안 끝나요']} />
      {asked && (
        <div className="ans">
          <div className="msg me" style={{ marginLeft: 'auto' }}>{asked}</div>
          {res && res.loading ? <div style={{ marginTop: 10 }}><AiLoading label="공고와 봉사자를 찾는 중…" /></div> : res && (
            <>
              <div className="ai" style={{ marginTop: 10 }}>
                <div className="ah"><Spk />받을 수 있는 도움<Mod n={4} /></div>
                {res.answer && <div className="small">{res.answer}</div>}
                {(res.categories || []).map((k) => <div key={k} className="row" style={{ gap: 12, marginTop: 10 }}><CatIc k={k} size={40} /><div className="grow"><div className="b">{k}</div><div className="mute2">{CAT_DESC[k] || `${LV(k)} ${W(k).toFixed(1)}`}</div></div></div>)}
                <RuleNote source={res.source} />
              </div>
              {res.help && res.help.blocked && (
                <div className="ai warn" style={{ marginTop: 8 }}>
                  <div className="ah"><Spk />이 도움은 이웃에게 받을 수 없어요<Mod n={2} /></div>
                  <div className="small">{res.help.reason}</div>
                  {(res.help.publicServices || []).length > 0 && <PubCards list={res.help.publicServices} />}
                </div>
              )}
              {res.help && !res.help.blocked && <div className="mute2" style={{ margin: '8px 2px 0' }}>{res.help.note}</div>}
              {/* 2026-11-11: 단골 추천 / 가까운 사람 추천 두 섹션 (순위는 서버 코드: 같은 동 → 가능 시간 → 거리) */}
              <RecSection title={res.helper ? '단골 추천 · 내가 자주 도운 이웃' : '내 단골 중에서'} sub={res.helper ? '3번 이상 도운 이웃이 올린 봉사 요청' : '내 단골이 올린, 시간·지역이 맞는 도움 가능 공고'} icon="regular" list={res.regularPosts || []}
                empty={res.helper ? '자주 도운 이웃이 올린 공고가 지금은 없어요' : '이 도움을 올린 단골 공고가 아직 없어요'}
                action={!res.helper && <button className="btn soft sm" style={{ marginTop: 10 }} onClick={() => nav('/regular')}>단골에게 바로 요청하기</button>} />
              <RecSection title={res.helper ? '가까운 사람 추천' : '가까운 · 시간 맞는 사람'} sub="단골이 아니어도 시간·지역이 잘 맞는 공고" icon="search" list={res.nearbyPosts || []}
                empty={res.helper ? '지금 열린 공고가 없어요. 도움 가능 공고를 올려 보세요.' : '지금 열린 공고가 없어요'}
                action={<button className="btn soft sm" style={{ marginTop: 10 }} onClick={() => nav(res.helper ? '/write/offer' : '/write/need')}>{res.helper ? '도움 가능 공고 올리기' : '봉사 요청 공고 올리기'}</button>} />
              <div className="card item click" style={{ marginTop: 8 }} role="button" tabIndex={0} onClick={() => nav(`/public?q=${encodeURIComponent(asked)}`)}><div className="ic"><Ic n="building" s={20} /></div><div className="grow"><div className="b">공공 서비스도 찾아볼까요?</div><div className="mute2">근처에서 받을 수 있는 공공 서비스를 찾아요</div></div>›</div>
            </>
          )}
        </div>
      )}
    </Screen>
  );
}

/* ---------- 기능 U-30: 봉사로 가능한 일인지와 봉사 기준 (서버 volunteer 값 그대로) ---------- */
const VG = { YES: ['', '봉사로 가능'], CAUTION: ['amber', '주의해서 가능'], NO: ['warn', '봉사로 불가'], UNKNOWN: ['', '확인 필요'] };
export function VolunteerGuide({ v }) {
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const [cls, tag] = VG[v.possible] || VG.UNKNOWN;
  return (
    <div className={'ai ' + cls} style={{ marginTop: 10 }}>
      <div className="ah"><Spk />이웃 봉사로 받을 수 있나요? · {tag}<Mod n={2} /></div>
      <div className="b" style={{ fontSize: 16 }}>{v.label}</div>
      <div className="small" style={{ marginTop: 4 }}>{v.message}</div>
      {v.reason && <div className="small muted" style={{ marginTop: 6 }}>{v.reason}</div>}
      {(v.categories || []).map((c) => <div key={c.category} className="row" style={{ gap: 12, marginTop: 10 }}><CatIc k={c.category} size={40} /><div className="grow"><div className="b">{c.category}</div><div className="mute2">난이도 {c.levelLabel} · 받은 시간 × {Number(c.weight).toFixed(1)}</div></div></div>)}
      <button type="button" className="btn ghost sm" style={{ marginTop: 10 }} aria-expanded={open} onClick={() => setOpen(!open)}>{open ? '봉사 기준 접기' : '봉사 기준 보기'}</button>
      {open && <ul className="small muted" style={{ margin: '8px 0 0', paddingLeft: 18 }}>{(v.criteria || []).map((x) => <li key={x} style={{ marginTop: 4 }}>{x}</li>)}</ul>}
      {v.writeLink && v.possible !== 'UNKNOWN' && <button type="button" className="btn sm" style={{ marginTop: 10 }} onClick={() => nav(v.writeLink)}>이 내용으로 봉사 요청 공고 쓰기</button>}
      {v.writeLink && v.possible === 'UNKNOWN' && <button type="button" className="btn soft sm" style={{ marginTop: 10 }} onClick={() => nav('/recommend')}>받을 수 있는 도움 찾아보기</button>}
      <RuleNote source={v.source} />
    </div>
  );
}

/* ---------- 공공 서비스 안내 (P-01) ---------- */
export function Public() {
  const [sp] = useSearchParams();
  const { me } = useAuth();
  const nav = useNavigate();
  const { sheet, closeSheet } = useUi();
  const [q, setQ] = useState(sp.get('q') || '');
  const [asked, setAsked] = useState(null);
  const [res, setRes] = useState(null);
  const askLocation = useLocationAsk();
  /* B-67: 필요한 도움을 말하면 접속 위치 기준으로 안내한다. 위치는 공용 위치 도우미(B-59)로 그 순간에만 얻는다.
     위치를 못 얻으면(허용 안 함·기기 미지원) 프로필 동네 기준 공공 서비스 DB 안내로 대신하고, 그 사실을 알려 준다. */
  const [locNote, setLocNote] = useState(null);
  const ask = async (t) => {
    const pos = await askLocation('위치 기준 공공 서비스 안내', { quiet: true });
    setAsked(t); setRes({ loading: true, near: !!pos });
    setLocNote(pos ? null : `위치를 쓰지 않아 ${(me && me.area) || '내 동네'} 기준으로 찾았어요. 지금 계신 곳 기준으로 보려면 ‘내 위치로 찾기’를 눌러 주세요.`);
    try {
      if (pos) {
        const r = await post('/ai/public-services/nearby', { question: t, lat: pos.lat, lng: pos.lng, area: me && me.area });
        if (r && (r.services || []).length) return setRes({ ...r, near: r.usedLocation !== false });
      }
      setRes(await post('/ai/public-services', { question: t, area: me && me.area, ageGroup: me && me.ageGroup }));
    } catch { setRes(null); }
  };
  /* U8: 내 위치로 찾기 — 위치는 동의할 때만 쓰고, 서버는 결과를 저장하지 않는다 */
  const nearby = (lat, lng) => post('/ai/public-services/nearby', { question: q.trim() || undefined, lat, lng, area: me && me.area });
  const findNear = async () => {
    // 거절한 뒤 다시 누르면 위치가 필요한 기능임과 허용 방법을 안내한다(B-59)
    const pos = await askLocation('내 위치로 공공 서비스 찾기');
    if (!pos) return;
    setLocNote(null);
    setAsked(q.trim() ? `${q.trim()} (내 위치 근처)` : '내 위치 근처 공공 서비스'); setRes({ loading: true, near: true });
    try { setRes({ ...(await nearby(pos.lat, pos.lng)), near: true }); } catch { setRes(null); }
  };
  /* B-67·B-09: 금지 요청 안내에서 넘어오면(near=1) 바로 위치 기준으로 찾는다. 위치를 못 쓰면 사는 동네 기준 안내로 대신한다. */
  const nearFirst = async (t) => {
    const pos = await askLocation('근처 공공 서비스 안내');
    if (!pos) return ask(t || '근처에서 받을 수 있는 공공 서비스');
    setLocNote(null);
    setAsked(t ? `${t} (내 위치 근처)` : '내 위치 근처 공공 서비스'); setRes({ loading: true, near: true });
    try {
      const r = await post('/ai/public-services/nearby', { question: t || undefined, lat: pos.lat, lng: pos.lng, area: me && me.area });
      if (r && (r.services || []).length) return setRes({ ...r, near: r.usedLocation !== false });
      setRes(await post('/ai/public-services', { question: t || '공공 서비스', area: me && me.area, ageGroup: me && me.ageGroup }));
    } catch { setRes(null); }
    return null;
  };
  useLoad(async () => { if (sp.get('near')) nearFirst(sp.get('q') || ''); else if (sp.get('q')) ask(sp.get('q')); return null; }, []);
  return (
    <Screen title="공공 서비스 안내" noTabs>
      <AskBox mic title="필요한 걸 말해 주세요" sub="근처 공공기관과 신청 방법을 쉬운 말로 알려드리고, 이웃 봉사로 받을 수 있는 일인지도 함께 알려드려요" placeholder="예) 혼자 사는데 쓰러지면 걱정돼요" q={q} setQ={setQ} onAsk={ask}
        quick={['목욕을 도와줄 사람이 필요해요', '혼자 사는데 쓰러지면 걱정돼요', '요즘 깜빡깜빡해요']} />
      <button type="button" className="loc-btn" onClick={findNear}><Ic n="pin" s={20} /> 내 위치로 찾기 <span className="mod m4" style={{ marginLeft: 2 }}>AI</span></button>
      <div className="mute2 center" style={{ marginTop: 6 }}>질문과 위치는 찾을 때 한 번만 쓰고 저장하지 않아요. 담당자에게 전달하기를 누른 내용만 전달돼요</div>
      {locNote && asked && <div className="notice" style={{ marginTop: 8 }}>{locNote}</div>}
      {asked && (
        <div className="ans">
          <div className="msg me" style={{ marginLeft: 'auto' }}>{asked}</div>
          {res && res.loading ? <div style={{ marginTop: 10 }}><AiLoading label={res.near ? '내 위치 근처에서 찾는 중…' : '공공 서비스 DB에서 찾는 중…'} /></div> : res && (
            <div className="ai" style={{ marginTop: 10 }}>
              <div className="ah"><Spk />{res.near ? '내 위치 근처 공공 서비스' : '이런 공공 서비스가 있어요'}<Mod n={4} /></div>
              {res.aiGenerated && <div className="ai-found" role="note"><Ic n="flag" s={14} /> {res.notice || 'AI가 찾은 정보예요 · 꼭 기관에 확인하세요'}</div>}
              <div className="small" style={{ marginTop: res.aiGenerated ? 8 : 0 }}>{res.answer || `${(res.services || []).length}개를 찾았어요. 받을 수 있는지는 신청하는 곳에서 확인해 줘요.`}</div>
              <PubCards list={res.services} ai={!!res.aiGenerated} />
              {!(res.services || []).length && <div className="small muted" style={{ marginTop: 8 }}>DB에서 맞는 서비스를 찾지 못했어요. 가까운 주민센터나 ☎ 129(보건복지상담센터)에 물어보세요.</div>}
              <RuleNote source={res.source} />
            </div>
          )}
          {res && !res.loading && res.volunteer && <VolunteerGuide v={res.volunteer} />}
          {res && !res.loading && me && me.role === 'USER' && (
            <div className="card" style={{ marginTop: 10 }}>
              <div className="b">혼자 신청하기 어려우세요?</div>
              <div className="small muted" style={{ marginTop: 4 }}>가까운 기관 담당자에게 이 내용을 전달하면 신청을 도와드려요. 전달하기를 눌렀을 때만 내용이 넘어가요.</div>
              <div className="btns" style={{ marginTop: 10 }}>
                <button className="btn ghost sm" onClick={() => nav('/public-referrals')}>전달한 요청 보기</button>
                <button className="btn soft sm" onClick={() => sheet(() => <ForwardSheet question={asked} answer={res.answer} services={res.services} onDone={closeSheet} />)}>담당자에게 전달하기</button>
              </div>
            </div>
          )}
        </div>
      )}
    </Screen>
  );
}
export const _u = fmt;
