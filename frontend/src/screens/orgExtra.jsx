/* 담당자 화면 추가분 (2026-11-13 대표 지시)
 * 1) 특보 알림: 날씨 특보와 운영자가 보낸 안내를 한곳에서. 홈에는 배너로.
 * 2) 공공 서비스 연계: 사용자가 "담당자에게 전달하기"를 눌렀을 때만 온 대화·AI가 찾은 서비스 기록을 처리. */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Ic, Loading, Empty, useLoad, useBusy } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { dshort } from '../lib/format.js';

const md = (d) => { const m = /^\d{4}-(\d{2})-(\d{2})/.exec(String(d || '')); return m ? `${Number(m[1])}/${Number(m[2])}` : ''; };
const wTitle = (a) => `${(a.areas || []).join('·')} ${a.type} ${a.level}`;

/* ---------- 홈 배너: 운영자가 보낸, 지금 진행 중이고 내 담당 대상이 있는 지역의 특보 ---------- */
export function WeatherBanner() {
  const nav = useNavigate();
  const [d] = useLoad(() => api('/org/weather', { quiet: true }).catch(() => null), []);
  const b = d && d.banner;
  const key = b ? `wb-closed-${b.id}` : null;
  const [closed, setClosed] = useState(() => { try { return key ? sessionStorage.getItem(key) === '1' : false; } catch { return false; } });
  const hidden = closed || (() => { try { return key ? sessionStorage.getItem(key) === '1' : false; } catch { return false; } })();
  if (!b || hidden) return null;
  return (
    <div className="card" style={{ marginTop: 12, background: '#FFF6E9', border: '1px solid #F3D9A8' }} role="alert">
      <div className="between"><span className="b" style={{ color: '#8A5A12' }}><Ic n="flag" s={16} /> {wTitle(b)}</span><span className="mute2">{md(b.from)} ~ {md(b.to)}</span></div>
      <div className="small" style={{ marginTop: 6 }}>담당 대상 {b.myElders}명이 있는 지역이에요{b.myActivities ? ` · 기간 안 예정 활동 ${b.myActivities}건` : ''}. {(b.actions || [])[0] || '안부 확인과 일정 조정을 살펴 주세요.'}</div>
      <div className="btns" style={{ marginTop: 10 }}>
        <button className="btn ghost sm" onClick={() => { try { sessionStorage.setItem(key, '1'); } catch { /* 저장 못 해도 닫는다 */ } setClosed(true); }}>닫기</button>
        <button className="btn soft sm" onClick={() => nav('/o/weather')}>특보 알림 보기</button>
      </div>
    </div>
  );
}

function AlertCard({ a }) {
  return (
    <div className="card" style={{ marginTop: 10 }}>
      <div className="between"><span className="b">{wTitle(a)}</span>{a.active ? <span className="st go">진행 중</span> : <span className="st end">지난 특보</span>}</div>
      <div className="mute2" style={{ marginTop: 2 }}>{md(a.from)} ~ {md(a.to)}{a.sent ? ' · 운영자 안내 받음' : ''}</div>
      <div className="small" style={{ marginTop: 8 }}>
        {a.mineArea
          ? <><b>내 담당 대상 {a.myElders}명</b>이 있는 지역이에요{a.myActivities ? ` · 기간 안 예정 활동 ${a.myActivities}건` : ''}.{(a.myElderNames || []).length > 0 && <div className="mute2" style={{ marginTop: 4 }}>{a.myElderNames.join(', ')}</div>}</>
          : <span className="mute2">내 담당 대상이 있는 지역은 아니에요.</span>}
      </div>
      {(a.actions || []).length > 0 && <ul className="small" style={{ margin: '8px 0 0', paddingLeft: 18 }}>{a.actions.map((x, i) => <li key={i}>{x}</li>)}</ul>}
    </div>
  );
}

/* ---------- 특보 알림 화면 ---------- */
export function OWeather() {
  const nav = useNavigate();
  const [d] = useLoad(() => api('/org/weather'), []);
  const [open, setOpen] = useState(null);
  return (
    <Screen title="특보 알림" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>날씨 특보와 운영자가 보낸 안내를 여기서 확인해요. 재연락·방문·일정 조정은 담당자가 판단해요.</p>
      {d === undefined ? <Loading /> : !d ? <Empty>불러오지 못했어요</Empty> : (
        <>
          <div className="sec-t" style={{ marginTop: 6 }}><span>지금 날씨 특보</span><span className="mute2" style={{ fontWeight: 500 }}>{(d.alerts || []).length}건</span></div>
          {(d.alerts || []).length ? d.alerts.map((a) => <AlertCard key={a.id} a={a} />) : <Empty>지금 내려진 특보가 없어요</Empty>}
          <div className="sec-t"><span>운영자 안내</span><span className="mute2" style={{ fontWeight: 500 }}>{(d.guides || []).length}건</span></div>
          {(d.guides || []).length ? d.guides.map((g) => (
            <div key={g.id} className="card" style={{ marginTop: 10 }}>
              <div className="between"><span className="b">{g.guideTitle || `[특보] ${wTitle(g)}`}</span><span className="mute2">{g.sentAt ? dshort(g.sentAt) : ''}</span></div>
              <div className="small" style={{ marginTop: 6, whiteSpace: 'pre-line' }}>{open === g.id ? (g.guide || '안내 내용이 없어요') : String(g.guide || '').split('\n').slice(0, 2).join(' ')}</div>
              <div className="btns" style={{ marginTop: 8 }}>
                <button className="btn ghost sm" onClick={() => setOpen(open === g.id ? null : g.id)}>{open === g.id ? '접기' : '전체 보기'}</button>
                {g.mineArea && <button className="btn soft sm" onClick={() => nav('/o/elders')}>담당 대상 보기</button>}
              </div>
            </div>
          )) : <Empty>운영자가 보낸 안내가 아직 없어요</Empty>}
          {(d.past || []).length > 0 && <><div className="sec-t"><span>지난 특보</span></div>{d.past.map((a) => <AlertCard key={a.id} a={a} />)}</>}
          <div className="notice" style={{ marginTop: 14 }}>위급한 상황은 119에 먼저 알려 주세요. 여기 안내는 기록 정리이고 건강 판단이 아니에요.</div>
        </>
      )}
    </Screen>
  );
}

/* ---------- 공공 서비스 연계 ---------- */
export const PR_ST = { NEW: ['wait', '새 요청'], CONTACTED: ['go', '연락함'], HELPING: ['go', '신청 도움 중'], DONE: ['ok', '완료'], NOT_ELIGIBLE: ['end', '대상이 아니에요'] };
export const PR_NEXT = [['CONTACTED', '연락했어요'], ['HELPING', '신청 도와드리는 중'], ['DONE', '완료'], ['NOT_ELIGIBLE', '대상이 아니에요']];

export function PrServices({ list }) {
  if (!(list || []).length) return <div className="small muted">AI가 찾은 서비스가 없어요</div>;
  return list.map((s, i) => (
    <div key={i} style={{ padding: '8px 0', borderTop: i ? '1px dashed var(--line)' : 'none' }}>
      <div className="b">{s.name || s.title || '서비스'}</div>
      {(s.summary || s.desc || s.description) && <div className="small" style={{ marginTop: 2 }}>{s.summary || s.desc || s.description}</div>}
      <div className="mute2" style={{ marginTop: 2 }}>{[s.target && `대상: ${s.target}`, (s.apply || s.agency || s.org) && `신청: ${s.apply || s.agency || s.org}`, s.phone && `☎ ${s.phone}`].filter(Boolean).join(' · ')}</div>
    </div>
  ));
}

export function OPublicRefs() {
  const { toast } = useUi();
  const [d, reload] = useLoad(() => api('/org/public-referrals'), []);
  const [seg, setSeg] = useState('open');
  const [sel, setSel] = useState(null);
  const [memo, setMemo] = useState('');
  const [busy, run] = useBusy();
  const items = (d && d.items) || [];
  const closed = (r) => r.status === 'DONE' || r.status === 'NOT_ELIGIBLE';
  const list = items.filter((r) => (seg === 'open' ? !closed(r) : closed(r)));
  const cur = items.find((r) => r.id === sel);
  const pick = (r) => { setSel(sel === r.id ? null : r.id); setMemo(r.memo || ''); };
  return (
    <Screen title="공공 서비스 연계" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>사용자가 공공 서비스 안내에서 <b>“담당자에게 전달하기”</b>를 눌렀을 때만, 그 대화와 AI가 찾은 서비스 기록이 여기로 와요.</p>
      <div className="chips" style={{ marginBottom: 8 }}>
        {[['open', `진행 중 ${items.filter((r) => !closed(r)).length}`], ['done', `완료 ${items.filter(closed).length}`]].map(([k, t]) => <button key={k} className={'chip ' + (seg === k ? 'on' : '')} onClick={() => { setSeg(k); setSel(null); }}>{t}</button>)}
      </div>
      {d === undefined ? <Loading /> : !list.length ? <Empty>{seg === 'open' ? '처리할 연계 요청이 없어요' : '완료한 요청이 없어요'}</Empty> : list.map((r) => {
        const st = PR_ST[r.status] || PR_ST.NEW;
        const u = r.user || {};
        return (
          <div key={r.id} className="card" style={{ marginTop: 10 }}>
            <div role="button" tabIndex={0} onClick={() => pick(r)} onKeyDown={(e) => { if (e.key === 'Enter') pick(r); }}>
              <div className="between"><span className="b">{u.name || '사용자'} <span className="mute2" style={{ fontWeight: 500 }}>{[u.ageGroup, u.area].filter(Boolean).join(' · ')}</span></span><span className={'st ' + st[0]}>{st[1]}</span></div>
              <div className="mute2" style={{ marginTop: 2 }}>{dshort(r.createdAt)} 전달{(r.services || [])[0] ? ` · ${r.services[0].name || r.services[0].title || ''}` : ''}</div>
              <div className="small" style={{ marginTop: 6 }}>“{String(r.question || '').split('\n')[0]}”</div>
            </div>
            {cur && cur.id === r.id && (
              <div style={{ marginTop: 10, borderTop: '1px solid var(--line)', paddingTop: 10 }}>
                <label className="f" style={{ marginTop: 0 }}>본인이 한 말</label>
                <div className="small" style={{ whiteSpace: 'pre-line' }}>{r.question}</div>
                {r.note && <><label className="f">담당자에게 한마디</label><div className="small">{r.note}</div></>}
                {r.answer && <><label className="f">AI가 한 안내</label><div className="small" style={{ whiteSpace: 'pre-line' }}>{r.answer}</div></>}
                <label className="f">AI가 찾은 서비스</label>
                <PrServices list={r.services} />
                <label className="f">연락처</label>
                <div className="small">{u.phone ? <a href={`tel:${u.phone}`} style={{ color: 'var(--brand)', fontWeight: 700 }}>☎ {u.phone}</a> : '등록된 전화번호가 없어요'}<span className="mute2"> · 전달에 동의한 분의 정보예요. 이 요청을 돕는 데에만 써 주세요.</span></div>
                <label className="f">처리 메모</label>
                <textarea className="in" rows={2} value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} placeholder="예) 11/14 통화, 주민센터 방문 신청 안내" />
                <div className="tags" style={{ marginTop: 10 }}>
                  {PR_NEXT.map(([k, t]) => <button key={k} className={'chip ' + (r.status === k ? 'on' : '')} disabled={busy} onClick={() => run(async () => { await post(`/org/public-referrals/${r.id}/status`, { status: k, memo }); toast(`'${t}'(으)로 바꾸고 사용자에게 알렸어요`); reload(); })}>{t}</button>)}
                </div>
                <div className="mute2" style={{ marginTop: 8 }}>받을 수 있는지는 신청하는 기관에서 확인해요. AI가 찾은 정보는 틀릴 수 있어요.</div>
              </div>
            )}
          </div>
        );
      })}
    </Screen>
  );
}

/* ---------- 담당자 홈의 진입 줄 ---------- */
export function OrgExtraEntries() {
  const nav = useNavigate();
  const [w] = useLoad(() => api('/org/weather', { quiet: true }).catch(() => null), []);
  const [p] = useLoad(() => api('/org/public-referrals', { quiet: true }).catch(() => null), []);
  const wn = w ? (w.alerts || []).length : 0;
  const pn = p ? p.newCount || 0 : 0;
  return (
    <>
      <div className="todo" role="button" tabIndex={0} onClick={() => nav('/o/weather')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/o/weather'); }}><div className="n" style={{ background: '#FFF1DC', color: '#B26A12' }}><Ic n="flag" s={20} /></div><div className="grow"><div className="t">특보 알림</div><div className="d">날씨 특보와 운영자 안내를 확인해요</div></div><span className={'st ' + (wn ? 'wait' : 'end')}>{wn ? `특보 ${wn}` : '없음'}</span></div>
      <div className="todo" role="button" tabIndex={0} onClick={() => nav('/o/public')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/o/public'); }}><div className="n" style={{ background: '#EAF1FF', color: '#3A6FD8' }}><Ic n="shield" s={20} /></div><div className="grow"><div className="t">공공 서비스 연계</div><div className="d">사용자가 전달한 공공 서비스 요청을 도와요</div></div><span className={'st ' + (pn ? 'wait' : 'end')}>{pn ? `새 요청 ${pn}` : '보기'}</span></div>
    </>
  );
}

/* ---------- 사용자: 공공 서비스 안내를 담당자에게 전달하기 (전달하겠다고 눌렀을 때만 기록이 간다) ---------- */
export function ForwardSheet({ question, answer, services, onDone }) {
  const { toast } = useUi();
  const [orgs] = useLoad(() => api('/public-referrals/orgs'), []);
  const [orgId, setOrgId] = useState(null);
  const [note, setNote] = useState('');
  const [agree, setAgree] = useState(false);
  const [busy, run] = useBusy();
  return (
    <>
      <h2>담당자에게 전달하기</h2>
      <p className="mute2" style={{ margin: '0 0 10px' }}>가까운 기관의 담당자가 신청을 도와드려요. 전달하기를 눌렀을 때만 내용이 넘어가요.</p>
      <label className="f" style={{ marginTop: 0 }}>어느 기관에 전달할까요?</label>
      {orgs === undefined ? <Loading /> : !(orgs || []).length ? <Empty>지금 전달할 수 있는 기관이 없어요</Empty> : orgs.map((o) => (
        <div key={o.id} className={'opt ' + (orgId === o.id ? 'on' : '')} role="button" tabIndex={0} onClick={() => setOrgId(o.id)} onKeyDown={(e) => { if (e.key === 'Enter') setOrgId(o.id); }}>
          <span className="rd" /><div className="grow"><div className="b">{o.name}{o.near && <span className="badge" style={{ marginLeft: 6 }}>내 동네</span>}</div><div className="mute2">{[o.orgType, o.address].filter(Boolean).join(' · ')}</div></div>
        </div>
      ))}
      <label className="f">담당자에게 한마디 (선택)</label>
      <textarea className="in" rows={2} maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} placeholder="예) 오후에 전화 주시면 좋아요" />
      <label className="f">전달되는 내용</label>
      <div className="card" style={{ boxShadow: 'none', background: '#F4F6FA' }}>
        <div className="small">· 방금 물어본 말: “{String(question || '').split('\n')[0]}”</div>
        <div className="small">· AI가 찾은 서비스 {(services || []).length}개와 안내 문장</div>
        <div className="small">· 내 이름, 전화번호, 사는 동네, 나이대</div>
      </div>
      <div className={'su-check ' + (agree ? 'on' : '')} role="button" tabIndex={0} style={{ marginTop: 10 }} onClick={() => setAgree(!agree)} onKeyDown={(e) => { if (e.key === 'Enter') setAgree(!agree); }}>
        <span className="bx" /><div className="grow">위 내용을 고른 기관의 담당자에게 전달하는 데 동의해요</div>
      </div>
      <button className="btn big-cta" style={{ marginTop: 12 }} disabled={!orgId || !agree || busy} onClick={() => run(async () => {
        await post('/public-referrals', { orgId, question, answer, services: (services || []).slice(0, 10), note, consent: true });
        toast('담당자에게 전달했어요. 처리되면 알림으로 알려드려요');
        onDone && onDone();
      })}>전달하기</button>
    </>
  );
}

/* 사용자: 내가 전달한 공공 서비스 요청 */
export function MyPublicRefs() {
  const [list] = useLoad(() => api('/public-referrals/mine'), []);
  return (
    <Screen title="전달한 공공 서비스 요청" noTabs>
      {list === undefined ? <Loading /> : !(list || []).length ? <Empty>담당자에게 전달한 요청이 없어요</Empty> : list.map((r) => {
        const st = PR_ST[r.status] || PR_ST.NEW;
        return (
          <div key={r.id} className="card" style={{ marginTop: 10 }}>
            <div className="between"><span className="b">{r.orgName || '기관'}</span><span className={'st ' + st[0]}>{st[1]}</span></div>
            <div className="mute2" style={{ marginTop: 2 }}>{dshort(r.createdAt)} 전달</div>
            <div className="small" style={{ marginTop: 6 }}>“{String(r.question || '').split('\n')[0]}”</div>
            {(r.services || []).length > 0 && <div className="mute2" style={{ marginTop: 4 }}>전달한 서비스: {r.services.map((s) => s.name || s.title).filter(Boolean).slice(0, 3).join(', ')}</div>}
          </div>
        );
      })}
      <div className="notice" style={{ marginTop: 14 }}>전달하기를 누른 요청만 담당자에게 보여요. 누르지 않은 질문은 저장하지 않아요.</div>
    </Screen>
  );
}
