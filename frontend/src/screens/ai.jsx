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
        {f.alternatives && f.alternatives.length ? <><div className="small" style={{ marginTop: 6 }}>대신 받을 수 있는 공공 서비스예요.</div><PubCards list={f.alternatives} /></> : <button className="btn soft sm" style={{ marginTop: 10 }} onClick={() => nav('/public')}>공공 서비스 안내 보기</button>}
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

function AskBox({ title, sub, placeholder, quick, onAsk, q, setQ, mic }) {
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
  const [q, setQ] = useState('');
  const [asked, setAsked] = useState(null);
  const [res, setRes] = useState(null);
  const ask = async (t) => {
    setAsked(t); setRes({ loading: true });
    try { setRes(await post('/ai/recommend', { question: t })); } catch { setRes(null); }
  };
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
              <RecSection title={res.helper ? '단골 추천 · 내가 자주 도운 이웃' : '단골 추천'} sub={res.helper ? '3번 이상 도운 이웃이 올린 봉사 요청' : '내 단골이 올린, 시간·지역이 맞는 도움 가능 공고'} icon="regular" list={res.regularPosts || []}
                empty={res.helper ? '자주 도운 이웃이 올린 공고가 지금은 없어요' : '단골이 올린 공고가 지금은 없어요. 단골에게 바로 요청할 수도 있어요.'}
                action={!res.helper && <button className="btn soft sm" style={{ marginTop: 10 }} onClick={() => nav('/regular')}>단골에게 바로 요청하기</button>} />
              <RecSection title="가까운 사람 추천" sub="단골이 아니어도 시간·지역이 잘 맞는 공고" icon="search" list={res.nearbyPosts || []}
                empty={`지금 열린 공고가 없어요. ${res.helper ? '도움 가능' : '봉사 요청'} 공고를 올려 보세요.`}
                action={<button className="btn soft sm" style={{ marginTop: 10 }} onClick={() => nav(res.helper ? '/write/offer' : '/write/need')}>{res.helper ? '도움 가능 공고 올리기' : '봉사 요청 쓰기'}</button>} />
              <div className="card item click" style={{ marginTop: 8 }} role="button" tabIndex={0} onClick={() => nav(`/public?q=${encodeURIComponent(asked)}`)}><div className="ic"><Ic n="building" s={20} /></div><div className="grow"><div className="b">공공 서비스도 찾아볼까요?</div><div className="mute2">근처에서 받을 수 있는 공공 서비스를 찾아요</div></div>›</div>
            </>
          )}
        </div>
      )}
    </Screen>
  );
}

/* ---------- 공공 서비스 안내 (P-01) ---------- */
export function Public() {
  const [sp] = useSearchParams();
  const { me } = useAuth();
  const [q, setQ] = useState(sp.get('q') || '');
  const [asked, setAsked] = useState(null);
  const [res, setRes] = useState(null);
  const { toast } = useUi();
  const ask = async (t) => {
    setAsked(t); setRes({ loading: true });
    try { setRes(await post('/ai/public-services', { question: t, area: me && me.area, ageGroup: me && me.ageGroup })); } catch { setRes(null); }
  };
  /* U8: 내 위치로 찾기 — 위치는 동의할 때만 쓰고, 서버는 결과를 저장하지 않는다 */
  const nearby = (lat, lng) => post('/ai/public-services/nearby', { question: q.trim() || undefined, lat, lng, area: me && me.area });
  const findNear = () => {
    setAsked(q.trim() ? `${q.trim()} (내 위치 근처)` : '내 위치 근처 공공 서비스'); setRes({ loading: true, near: true });
    const fallback = async (why) => {
      if (why) toast(why);
      try { setRes({ ...(await nearby(undefined, undefined)), near: true }); } catch { setRes(null); }
    };
    if (!navigator.geolocation) { fallback(`위치를 쓸 수 없어 ${(me && me.area) || '내 동네'} 기준으로 찾을게요`); return; }
    navigator.geolocation.getCurrentPosition(
      async (pos) => { try { setRes({ ...(await nearby(pos.coords.latitude, pos.coords.longitude)), near: true }); } catch { setRes(null); } },
      () => fallback(`위치 권한이 없어 ${(me && me.area) || '내 동네'} 기준으로 찾을게요`),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 600000 },
    );
  };
  useLoad(async () => { if (sp.get('q')) ask(sp.get('q')); return null; }, []);
  return (
    <Screen title="공공 서비스 안내" noTabs>
      <AskBox mic title="필요한 걸 말해 주세요" sub="AI가 공공 서비스 DB에서 맞는 서비스를 찾아 쉬운 말로 알려드려요" placeholder="예) 혼자 사는데 쓰러지면 걱정돼요" q={q} setQ={setQ} onAsk={ask}
        quick={['목욕을 도와줄 사람이 필요해요', '혼자 사는데 쓰러지면 걱정돼요', '요즘 깜빡깜빡해요']} />
      <button type="button" className="loc-btn" onClick={findNear}><Ic n="pin" s={20} /> 내 위치로 찾기 <span className="mod m4" style={{ marginLeft: 2 }}>AI</span></button>
      <div className="mute2 center" style={{ marginTop: 6 }}>위치는 찾을 때 한 번만 쓰고 저장하지 않아요</div>
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
        </div>
      )}
    </Screen>
  );
}
export const _u = fmt;
