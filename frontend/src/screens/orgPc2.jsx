/* 담당자 PC 화면 2차 (프로토타입 5판, 계약서 7.23)
   요청 상세(O-POST-03: 목록 340 + 상세) · 대신 요청 올리기(O-POST-02 자리) · 특보 알림(O-ALERT-01) · 공공 서비스 연계(O-PUB-01·02)
   · 리포트(O-RPT-01·02) · 기관 행사(O-POST-06) · 내 정보·설정(O-HOME-03 자리).
   폭 901px 이상에서만 쓰고, 좁으면 기존 모바일 화면이 그대로 나온다. API·동작은 모바일 화면과 같고 배치만 다르다.
   앱에 자료가 없는 5판 구역(실명·전화·주소, 안부 확인 기록, 권한 위임, 기관 공고 3종, AI 자격 미리 보기)은 만들지 않았다. */
import { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Loading, Mi, RuleNote, hbars, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { SHORT, ACTIVE_ST, PAST_ST, arr, dlabel, dshort, hh } from '../lib/format.js';
import { usePc, PcSideCtx, PIc, Avc, CPill, StTag5 } from '../ui/PcFrame.jsx';
import { OReport as OReportMobile, OMy as OMyMobile, useElderPick, elderName } from './org.jsx';
import { OWeather as OWeatherMobile, OPublicRefs as OPublicMobile, PR_ST, PR_NEXT } from './orgExtra.jsx';
import { OEvents as OEventsMobile, OEventNew as OEventNewMobile, EventFormBody, host } from './news.jsx';
import { RequestDetail } from './request.jsx';
import { WriteNeed } from './write.jsx';
import { RecentReport } from './insight.jsx';
import { TextSizeRow, useLogout } from './my.jsx';
import { needsMe, todoTxt } from './home.jsx';

const md = (d) => { const m = /^\d{4}-(\d{2})-(\d{2})/.exec(String(d || '')); return m ? `${Number(m[1])}/${Number(m[2])}` : ''; };
const short = (c) => SHORT[c] || c || '도움';
const Seg = ({ value, onChange, items, label }) => (
  <div className="cseg" role="tablist" aria-label={label}>
    {items.map(([k, t]) => <button key={k} type="button" role="tab" aria-selected={value === k} className={value === k ? 'on' : ''} onClick={() => onChange(k)}>{t}</button>)}
  </div>
);
const Kpi = ({ items }) => (
  <div className="kpis" style={{ gridTemplateColumns: `repeat(${items.length},minmax(0,1fr))` }}>
    {items.map(([k, v]) => <div key={k} className="kpi"><span>{k}</span><b>{v}</b></div>)}
  </div>
);

/* ================= 요청 상세 (O-POST-03): 위 줄 + 목록 340 + 상세 =================
   오른쪽 상세는 기존 RequestDetail 그대로다(대신 수락·거절, 최종 수락, 일지 확인·평가, 메시지, QR, 신고 … 버튼이 모두 거기 있다). */
function useReqSide(on, curId) {
  const nav = useNavigate();
  const pick = useElderPick();
  const [tick, setTick] = useState(0);
  const [reqs] = useLoad(() => (on ? api('/org/requests?box=all', { quiet: true }) : Promise.resolve(null)), [on, tick]);
  const [elders] = useLoad(() => (on ? api('/org/elders', { quiet: true }) : Promise.resolve(null)), [on]);
  const [seg, setSeg] = useState(null);
  // 상세에서 수락·확인 같은 처리를 하면 목록도 다시 받는다
  useEffect(() => {
    if (!on) return undefined;
    let t = null;
    const h = () => { clearTimeout(t); t = setTimeout(() => setTick((x) => x + 1), 400); };
    window.addEventListener('ts-mutated', h);
    return () => { clearTimeout(t); window.removeEventListener('ts-mutated', h); };
  }, [on]);
  if (!on) return null;
  const list = (reqs || []).map((r) => ({ ...r, myRole: r.myRole || 'MANAGER' }));
  const byDate = (a, b) => (a.date === b.date ? Number(a.startHour) - Number(b.startHour) : a.date < b.date ? -1 : 1);
  const need = list.filter(needsMe);
  const going = list.filter((r) => ACTIVE_ST.includes(r.status) && !need.includes(r));
  const past = list.filter((r) => PAST_ST.includes(r.status));
  const G = { need, going, past };
  const cur = list.find((r) => String(r.id) === String(curId));
  const auto = cur ? (need.includes(cur) ? 'need' : going.includes(cur) ? 'going' : 'past') : 'need';
  const s = seg || auto;
  const rows = G[s].slice().sort(s === 'past' ? (a, b) => byDate(b, a) : byDate);
  return {
    top: (
      <div className="prw" style={{ marginBottom: 14, flexWrap: 'wrap', rowGap: 10 }}>
        <Seg label="요청 묶음" value={s} onChange={setSeg} items={[['need', `처리할 일 ${need.length}`], ['going', `진행 중 ${going.length}`], ['past', `완료 ${past.length}`]]} />
        <span className="sp" />
        <button type="button" className="pbtn" onClick={() => nav('/o/reqs')}>보드로 보기</button>
        <button type="button" className="pbtn" onClick={() => pick('누구 대신 요청할까요?', elders, (e) => nav(`/board?elder=${e.id}`))}><PIc k="search" />도움 찾기</button>
        <button type="button" className="pbtn pri" onClick={() => pick('누구 대신 공고를 올릴까요?', elders, (e) => nav(`/write/need?elder=${e.id}`))}><PIc k="plus" />대리 요청</button>
      </div>
    ),
    side: (
      <div className="pnl lst sticky" aria-label="요청 목록">
        {reqs === undefined ? <Loading /> : rows.length ? rows.map((x) => {
          const on2 = String(x.id) === String(curId);
          return (
            <button key={x.id} type="button" className={'lrow col ' + (on2 ? 'on' : '')} aria-current={on2 ? 'true' : undefined} onClick={() => nav(`/o/reqs/${x.id}`, { replace: true })}>
              <span className="prw" style={{ gap: 6 }}><StTag5 st={x.status} kind={x.kind} /><span className="mu">{dlabel(x.date)} {hh(x.startHour)}</span></span>
              <b>{x.elder ? x.elder.displayName : '대상'} · {short(x.category)}</b>
              <small>봉사자 {x.volunteer ? x.volunteer.name : '없음'}{s === 'need' ? ` · ${todoTxt(x)}` : ''}</small>
            </button>
          );
        }) : <div className="pempty">없어요</div>}
      </div>
    ),
  };
}
export function OReqDetail() {
  const { id } = useParams();
  const pc = usePc();
  const side = useReqSide(pc, id);
  return <PcSideCtx.Provider value={side}><RequestDetail /></PcSideCtx.Provider>;
}

/* ================= 대신 요청 올리기 (O-POST-02 자리): 왼쪽 대상·단골 + 오른쪽 작성 =================
   5판은 [전화 내용 + AI로 정리] | [정리 결과] 두 칸이지만, 앱의 작성 화면은 대화형(말하듯 적기 → AI 정리 → 초안 → 올리기)이라 그 흐름을 오른쪽에 그대로 두고
   왼쪽에 5판의 "관리 대상 고르기"와 "단골에게 바로 요청" 카드를 놓았다. */
function useWriteSide(on, elderId) {
  const nav = useNavigate();
  const [elders] = useLoad(() => (on ? api('/org/elders', { quiet: true }) : Promise.resolve(null)), [on]);
  const [e] = useLoad(() => (on ? api(`/org/elders/${elderId}`, { quiet: true }).catch(() => null) : Promise.resolve(null)), [on, elderId]);
  if (!on) return null;
  const live = (elders || []).filter((x) => x.status !== 'ENDED');
  const regs = (e && e.regulars) || [];
  return {
    side: (
      <div className="sidecol sticky">
        <div className="pnl">
          <div className="ps-h"><b>관리 대상</b></div>
          <div className="pchips wrap">{live.map((x) => <button key={x.id} type="button" className={'pchip ' + (String(x.id) === String(elderId) ? 'on' : '')} aria-pressed={String(x.id) === String(elderId)} onClick={() => nav(`/write/need?elder=${x.id}`, { replace: true })}>{elderName(x)}</button>)}</div>
          {e && (
            <>
              <div className="prw" style={{ marginTop: 14 }}><Avc n={e.alias} cat="acc" size={40} /><span className="lr-t"><b>{elderName(e)}</b><small>{e.ageGroup} · {e.dong}</small></span><button type="button" className="ps-a" onClick={() => nav(`/o/elders/${e.id}`)}>정보 ›</button></div>
              <div className="ptags">{arr(e.needs).map((c) => <CPill key={c} cat={c}>{short(c)}</CPill>)}</div>
              {e.caution && <div className="warnbox" style={{ marginTop: 10 }}><b><PIc k="warn" />방문 주의사항</b><div style={{ marginTop: 4 }}>{e.caution}</div></div>}
            </>
          )}
        </div>
        <div className="pnl">
          <div className="ps-h"><b>{e ? `${elderName(e)}의 단골` : '단골'}</b></div>
          <div className="mu" style={{ marginBottom: 8 }}>공고 없이 단골에게 바로 요청할 수도 있어요.</div>
          {regs.length ? regs.map((r) => <div key={r.id || r.name} className="prw" style={{ padding: '4px 0' }}><Avc n={r.name} cat="acc" size={30} /><b style={{ flex: 1 }}>{r.name}</b><button type="button" className="pbtn" onClick={() => nav(`/regular?elder=${elderId}`)}>바로 요청</button></div>) : <div className="mu">아직 단골이 없어요</div>}
        </div>
        <div className="pnl">
          <div className="ps-h"><b>다른 방법으로</b></div>
          {[['봉사 가능 공고에서 찾기', 'search', `/board?elder=${elderId}`], ['직접 적기 (AI 없이)', 'memo', `/write/need/manual?elder=${elderId}`], ['말로 공고 만들기', 'mic', `/voice?elder=${elderId}`], ['통화 메모로 여러 건', 'list', `/o/memo-posts?elder=${elderId}`], ['정기 요청 (매주 같은 요일)', 'clock', `/recurring?elder=${elderId}`]]
            .map(([t, i, to]) => <button key={t} type="button" className="li click" onClick={() => nav(to)}><span className="li-i"><PIc k={i} /></span><div className="li-t">{t}</div><span className="ar">›</span></button>)}
        </div>
      </div>
    ),
  };
}
export function OWriteNeed() {
  const [sp] = useSearchParams();
  const elder = sp.get('elder');
  const pc = usePc();
  const side = useWriteSide(pc && !!elder, elder);
  return <PcSideCtx.Provider value={side}><WriteNeed key={elder || 'me'} /></PcSideCtx.Provider>;
}

/* ================= 특보 알림 (O-ALERT-01): 두 탭 + 목록 340 + 상세 ================= */
const wName = (a) => `${a.type || '특보'}${a.level ? ` ${a.level}` : ''}`;
const wArea = (a) => (a.areas || []).join('·');
function OWeatherPc() {
  const nav = useNavigate();
  const [sp, setSp] = useSearchParams();
  const [d] = useLoad(() => api('/org/weather'), []);
  // 진행 중인 특보 가운데 내 담당 대상이 있는 지역 것을 맨 위에(처음 열었을 때 그것이 골라지게), 지난 특보는 아래에
  const alerts = d ? [...(d.alerts || []).slice().sort((x, y) => Number(!!y.mineArea) - Number(!!x.mineArea)), ...(d.past || [])] : [];
  const guides = (d && d.guides) || [];
  const tab = sp.get('tab') === 'guide' ? 'guide' : 'wx';
  const L = tab === 'wx' ? alerts : guides;
  const cur = L.find((x) => String(x.id) === sp.get('id')) || L[0];
  const go = (t, id) => setSp(id != null ? { tab: t, id: String(id) } : { tab: t }, { replace: true });
  const guideOf = (a) => guides.find((g) => String(g.id) === String(a.id));
  const kp = (a) => <Kpi items={[['이 지역 내 담당 대상', a.mineArea ? `${a.myElders ?? 0}명` : '없음'], ['특보 기간 예정 활동', `${a.myActivities ?? 0}건`], ['운영자 안내', guideOf(a) || a.sent ? '받음' : '아직 없음']]} />;
  const links = (
    <div className="prw" style={{ gap: 8, flexWrap: 'wrap', marginTop: 12 }}>
      <button type="button" className="pbtn" onClick={() => nav('/o/elders')}>담당 대상 보기 ›</button>
      <button type="button" className="pbtn" onClick={() => nav('/o/schedule')}>편성표 ›</button>
      <button type="button" className="pbtn" onClick={() => nav('/o/public')}>공공 서비스 연계 ›</button>
    </div>
  );
  return (
    <Screen title="특보 알림" wide stay>
      <div className="prw" style={{ marginBottom: 14, flexWrap: 'wrap', rowGap: 8 }}>
        <Seg label="특보 종류" value={tab} onChange={(t) => go(t)} items={[['wx', `기상 특보 ${d ? (d.alerts || []).length : ''}`], ['guide', `운영자 안내 ${d ? guides.length : ''}`]]} />
        <span className="mu">날씨 특보와 운영자가 보낸 안내를 여기서 확인해요. 재연락·방문·일정 조정은 담당자가 판단해요.</span>
      </div>
      {d === undefined ? <div className="pnl"><Loading /></div> : !d ? <div className="pnl pempty">불러오지 못했어요</div> : (
        <div className="split">
          <div className="pnl lst">
            <div className="lst-t">{tab === 'wx' ? '기상 특보' : '운영자가 보낸 안내'}</div>
            {L.length ? L.map((x) => {
              const on = cur && x.id === cur.id;
              return (
                <button key={x.id} type="button" className={'lrow ' + (on ? 'on' : '')} aria-current={on ? 'true' : undefined} onClick={() => go(tab, x.id)}>
                  <span className="lr-i" style={x.active === false ? { background: '#F1F4F8', color: '#8A94A6' } : undefined}><PIc k={tab === 'wx' ? 'snow' : 'mega'} /></span>
                  <span className="lr-t">
                    <b>{tab === 'wx' ? wName(x) : (x.guideTitle || `[특보] ${wArea(x)} ${wName(x)}`)}</b>
                    <small>{tab === 'wx' ? `${wArea(x)} · ${md(x.from)} ~ ${md(x.to)}` : `${x.sentAt ? dshort(x.sentAt) : ''}${x.from ? ` · ${md(x.from)} ~ ${md(x.to)}` : ''}`}</small>
                  </span>
                  {tab === 'wx'
                    ? (x.active === false ? <span className="ptag">지난 특보</span> : x.mineArea ? <span className="ptag r">우리 지역</span> : <span className="ptag">다른 지역</span>)
                    : (x.active === false ? <span className="ptag">종료</span> : <span className="ptag r">진행 중</span>)}
                </button>
              );
            }) : <div className="pempty">{tab === 'wx' ? '지금 내려진 특보가 없어요' : '운영자가 보낸 안내가 아직 없어요'}</div>}
            <div className="notebox" style={{ marginTop: 10 }}>{tab === 'wx' ? '내 담당 대상이 있는 지역의 특보를 운영자가 안내로 보내면 홈 위쪽에도 보여요.' : '운영자가 특보를 보고 담당자에게 "이렇게 대비해 주세요" 하고 보낸 안내예요.'}</div>
          </div>
          <div className="dcol">
            {!cur ? <div className="pnl pempty">{tab === 'wx' ? '지금 내려진 특보가 없어요' : '운영자가 보낸 안내가 아직 없어요'}</div> : tab === 'wx' ? (
              <>
                <div className={cur.mineArea && cur.active !== false ? 'dangerbox' : 'notebox big'}>
                  <div className="prw"><span className="bx-i"><PIc k="snow" /></span><div style={{ flex: 1, minWidth: 0 }}><b style={{ fontSize: 16 }}>{wName(cur)} · {wArea(cur)}</b><div className="bx-s">{md(cur.from)} ~ {md(cur.to)} · {cur.active === false ? '지난 특보' : '진행 중'}{cur.sent ? ' · 운영자 안내 받음' : ''}</div></div></div>
                  <div style={{ marginTop: 8 }}>{cur.mineArea ? <>내 담당 대상 <b>{cur.myElders ?? 0}명</b>이 있는 지역이에요{cur.myActivities ? ` · 기간 안 예정 활동 ${cur.myActivities}건` : ''}.</> : '내 담당 대상이 있는 지역은 아니에요.'}</div>
                </div>
                {cur.mineArea ? (
                  <>
                    {kp(cur)}
                    <div className="pnl">
                      <div className="ps-h"><b>이 지역의 담당 대상</b><button type="button" className="ps-a" onClick={() => nav('/o/elders')}>관리 대상에서 보기 ›</button></div>
                      {(cur.myElderNames || []).length ? <div className="ptags" style={{ marginTop: 0 }}>{cur.myElderNames.map((n) => <span key={n} className="ptag k">{n}</span>)}</div> : <div className="mu">이름을 불러오지 못했어요. 관리 대상에서 확인해 주세요.</div>}
                      <div className="mu" style={{ marginTop: 10 }}>안부 연락·방문·일정 조정은 담당자가 판단해요. 연락처는 앱에 저장하지 않아요.</div>
                    </div>
                    {guideOf(cur) && (
                      <div className="pnl">
                        <div className="prw"><b style={{ flex: 1 }}>이 특보에 대한 운영자 안내</b><button type="button" className="pbtn pri" onClick={() => go('guide', cur.id)}>안내 보기 ›</button></div>
                        <div className="mu" style={{ marginTop: 6, fontSize: 13 }}>{String(guideOf(cur).guide || '').split('\n').slice(0, 2).join(' ')}</div>
                      </div>
                    )}
                    {(cur.actions || []).length > 0 && (
                      <div className="ai">
                        <div className="ai-h">권하는 조치</div>
                        {cur.actions.map((x, i) => <div key={i} className="act-r"><span className="li-i"><PIc k="check" /></span><span>{x}</span></div>)}
                        <div className="ai-why">특보 종류와 기간, 내 담당 대상의 지역·예정 활동을 기준으로 한 안내예요.</div>
                      </div>
                    )}
                    <div className="pnl">{links}<div className="mu" style={{ marginTop: 10 }}>위급한 상황은 119에 먼저 알려 주세요. 여기 안내는 기록 정리이고 건강 판단이 아니에요.</div></div>
                  </>
                ) : <div className="pnl"><div className="mu">내 담당 대상이 있는 지역이 아니라서 따로 할 일은 없어요.</div>{(cur.actions || []).length > 0 && <ul style={{ margin: '8px 0 0', paddingLeft: 18, fontSize: 13 }}>{cur.actions.map((x, i) => <li key={i}>{x}</li>)}</ul>}</div>}
              </>
            ) : (
              <>
                <div className={cur.active !== false ? 'dangerbox' : 'notebox big'}>
                  <div className="prw"><span className="bx-i"><PIc k="mega" /></span><div style={{ flex: 1, minWidth: 0 }}><b style={{ fontSize: 16 }}>{cur.guideTitle || `[운영자 안내] ${wArea(cur)} ${wName(cur)}`}</b><div className="bx-s">운영자 안내{cur.from ? ` · ${md(cur.from)} ~ ${md(cur.to)}` : ''}{cur.sentAt ? ` · ${dshort(cur.sentAt)} 도착` : ''}</div></div></div>
                  <div style={{ marginTop: 8, whiteSpace: 'pre-line' }}>{cur.guide || '안내 내용이 없어요'}</div>
                </div>
                {cur.mineArea && kp(cur)}
                <div className="pnl">
                  <div className="ps-h"><b>이어서 할 일</b></div>
                  <div className="mu">{cur.mineArea ? '내 담당 대상이 있는 지역의 안내예요. 담당 대상과 편성표를 살펴 주세요.' : '내 담당 대상이 있는 지역은 아니에요.'}</div>
                  {links}
                  <div className="mu" style={{ marginTop: 10 }}>위급한 상황은 119에 먼저 알려 주세요. 여기 안내는 기록 정리이고 건강 판단이 아니에요.</div>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </Screen>
  );
}
export function OWeather() { return usePc() ? <OWeatherPc /> : <OWeatherMobile />; }

/* ================= 공공 서비스 연계 (O-PUB-01·02): 숫자 4칸 + 목록 340 + 상세 ================= */
const PR_STEP = ['NEW', 'CONTACTED', 'HELPING', 'DONE'];
const PR_TAG = { NEW: 'r', CONTACTED: 'a', HELPING: 'a', DONE: 'g', NOT_ELIGIBLE: '' };
const svcName = (s) => s.name || s.title || '서비스';
function PubDetail({ r, onDone }) {
  const { toast } = useUi();
  const [memo, setMemo] = useState(r.memo || '');
  const [busy, run] = useBusy();
  const u = r.user || {};
  const st = PR_ST[r.status] || PR_ST.NEW;
  const step = r.status === 'NOT_ELIGIBLE' ? 3 : Math.max(0, PR_STEP.indexOf(r.status));
  const svcs = r.services || [];
  return (
    <>
      <div className="pnl">
        <div className="prw">
          <Avc n={u.name} cat="acc" size={56} />
          <div style={{ flex: 1, minWidth: 0 }}><div className="d-t">{u.name || '사용자'} <span className="mu" style={{ fontSize: 14, fontWeight: 500 }}>{[u.ageGroup, u.area].filter(Boolean).join(' · ')}</span></div><div className="mu">{dshort(r.createdAt)} 앱에서 전달</div></div>
          <span className={'ptag ' + PR_TAG[r.status]}>{st[1]}</span>
        </div>
        <div className="steps" aria-hidden="true">{PR_STEP.map((k, i) => <i key={k} className={i <= step ? 'on' : ''} />)}</div>
        <div className="stl">{PR_STEP.map((k, i) => <span key={k} className={i === step ? 'on' : ''}>{k === 'DONE' && r.status === 'NOT_ELIGIBLE' ? '대상이 아니에요' : PR_ST[k][1]}</span>)}</div>
      </div>
      <div className="g2">
        <div className="pnl">
          <div className="prw"><b style={{ flex: 1 }}>본인이 전달한 AI 대화</b><span className="ptag g">본인이 전달함 · {md(r.createdAt)}</span></div>
          <div className="notebox" style={{ margin: '8px 0' }}>앱에서 "담당자에게 전달하기"를 누른 대화만 보여요. 누르지 않은 대화와 찾아본 기록은 담당자에게 보이지 않아요.</div>
          <div className="pchat"><div className="pmsg me">{r.question}</div>{r.answer && <div className="pmsg">{r.answer}</div>}</div>
          {r.note && <div className="mu" style={{ marginTop: 10 }}>담당자에게 남긴 말: {r.note}</div>}
        </div>
        <div className="pnl">
          <div className="ps-h"><b>연락처</b></div>
          <div style={{ fontSize: 15 }}>{u.phone ? <a href={`tel:${u.phone}`} className="tel"><PIc k="phone" />{u.phone}</a> : '등록된 전화번호가 없어요'}</div>
          <div className="mu" style={{ marginTop: 8 }}>전달에 동의한 분의 정보예요. 이 요청을 돕는 데에만 써 주세요.</div>
          <div className="notebox" style={{ marginTop: 12 }}>받을 수 있는지는 신청하는 기관에서 확인해요. AI가 찾은 정보는 틀릴 수 있어요.</div>
        </div>
      </div>
      <div className="pnl">
        <div className="prw"><b style={{ flex: 1 }}>AI가 찾은 서비스</b><span className="mu">전달한 대화에서 찾은 기록</span></div>
        {svcs.length ? (
          <table className="tbl sm plain" style={{ marginTop: 8 }} aria-label="AI가 찾은 서비스">
            <thead><tr><th scope="col">서비스</th><th scope="col">대상</th><th scope="col">내용</th><th scope="col">신청</th></tr></thead>
            <tbody>{svcs.map((s, i) => <tr key={i}><td><b>{svcName(s)}</b></td><td>{s.target || '-'}</td><td style={{ color: '#5B6576' }}>{s.summary || s.desc || s.description || '-'}</td><td>{[s.apply || s.agency || s.org, s.phone && `☎ ${s.phone}`].filter(Boolean).join(' · ') || '-'}</td></tr>)}</tbody>
          </table>
        ) : <div className="pempty">AI가 찾은 서비스가 없어요</div>}
      </div>
      <div className="pnl">
        <label className="plabel" htmlFor="pr-memo">처리 메모</label>
        <textarea id="pr-memo" className="pin area" rows={2} value={memo} maxLength={500} onChange={(e) => setMemo(e.target.value)} placeholder="예) 11/14 통화, 주민센터 방문 신청 안내" />
        <div className="prw" style={{ gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
          {PR_NEXT.map(([k, t]) => (
            <button key={k} type="button" className={'pbtn lg ' + (r.status === k ? 'sel' : k === 'DONE' ? 'pri' : k === 'NOT_ELIGIBLE' ? 'sec2' : '')} aria-pressed={r.status === k} style={k === 'NOT_ELIGIBLE' ? { marginLeft: 'auto' } : undefined} disabled={busy}
              onClick={() => run(async () => { await post(`/org/public-referrals/${r.id}/status`, { status: k, memo }); toast(`'${t}'(으)로 바꾸고 사용자에게 알렸어요`); onDone(); })}>{r.status === k ? `✓ ${t}` : t}</button>
          ))}
        </div>
        <div className="mu" style={{ marginTop: 8 }}>상태를 바꾸면 {u.name || '사용자'} 님 앱에 알림이 가요.</div>
      </div>
    </>
  );
}
function OPublicPc() {
  const [d, reload] = useLoad(() => api('/org/public-referrals'), []);
  const [seg, setSeg] = useState('open');
  const [sel, setSel] = useState(null);
  const items = (d && d.items) || [];
  const closed = (r) => r.status === 'DONE' || r.status === 'NOT_ELIGIBLE';
  const list = items.filter((r) => (seg === 'open' ? !closed(r) : closed(r)));
  const cur = list.find((r) => r.id === sel) || list[0];
  const cnt = {};
  items.forEach((r) => { const s = (r.services || [])[0]; if (s) cnt[svcName(s)] = (cnt[svcName(s)] || 0) + 1; });
  const top = Object.entries(cnt).sort((a, b) => b[1] - a[1])[0];
  return (
    <Screen title="공공 서비스 연계" wide stay>
      <Kpi items={[['새 요청', `${d ? d.newCount ?? items.filter((r) => r.status === 'NEW').length : '-'}건`], ['진행 중', `${d ? items.filter((r) => r.status === 'CONTACTED' || r.status === 'HELPING').length : '-'}건`], ['완료', `${d ? items.filter((r) => r.status === 'DONE').length : '-'}건`], ['연계 많은 서비스', top ? top[0] : '-']]} />
      <div className="prw" style={{ margin: '0 0 14px', flexWrap: 'wrap', rowGap: 8 }}>
        <Seg label="처리 상태" value={seg} onChange={(k) => { setSeg(k); setSel(null); }} items={[['open', `진행 중 ${items.filter((r) => !closed(r)).length}`], ['done', `완료 ${items.filter(closed).length}`]]} />
        <span className="sp" />
        <span className="mu">본인이 앱에서 "담당자에게 전달하기"를 눌렀을 때만 그 대화와 AI가 찾은 서비스가 여기로 와요</span>
      </div>
      {d === undefined ? <div className="pnl"><Loading /></div> : (
        <div className="split">
          <div className="pnl lst">
            {list.length ? list.map((x) => {
              const on = cur && x.id === cur.id;
              const u = x.user || {};
              return (
                <button key={x.id} type="button" className={'lrow col ' + (on ? 'on' : '')} aria-current={on ? 'true' : undefined} onClick={() => setSel(x.id)}>
                  <span className="prw" style={{ gap: 6 }}><span className={'ptag ' + PR_TAG[x.status]}>{(PR_ST[x.status] || PR_ST.NEW)[1]}</span><span className="mu">{dshort(x.createdAt)}</span></span>
                  <b>{u.name || '사용자'} 님{(x.services || [])[0] ? ` · ${svcName(x.services[0])}` : ''}</b>
                  <small>"{String(x.question || '').split('\n')[0]}"</small>
                </button>
              );
            }) : <div className="pempty">{seg === 'open' ? '처리할 연계 요청이 없어요' : '완료한 요청이 없어요'}</div>}
          </div>
          <div className="dcol">{cur ? <PubDetail key={cur.id} r={cur} onDone={reload} /> : <div className="pnl pempty">요청을 골라 주세요</div>}</div>
        </div>
      )}
    </Screen>
  );
}
export function OPublicRefs() { return usePc() ? <OPublicPc /> : <OPublicMobile />; }

/* ================= 리포트 (O-RPT-01·02): 왼쪽 요약·안부 순서 400 + 오른쪽 대상별 상세 ================= */
function OReportPc() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [sp, setSp] = useSearchParams();
  const range = sp.get('range') === 'today' ? 'today' : 'recent';
  const [elders] = useLoad(() => api('/org/elders'), []);
  const [chg] = useLoad(() => api(`/org/change-report${range === 'today' ? '?range=today' : ''}`), [range]);
  const [list, reload] = useLoad(() => api('/org/reports'), []);
  const reps = list || [];
  const active = (elders || []).filter((e) => e.status === 'ACTIVE');
  const items = (chg && chg.items) || [];
  const sel = sp.get('elder') || (items[0] && String(items[0].elderId)) || (active[0] && String(active[0].id)) || null;
  const pick = (id) => { const n = { elder: String(id) }; if (range === 'today') n.range = 'today'; setSp(n, { replace: true }); };
  const setRange = (k) => { const n = {}; if (sp.get('elder')) n.elder = sp.get('elder'); if (k === 'today') n.range = 'today'; setSp(n, { replace: true }); };
  const x = items.find((i) => String(i.elderId) === String(sel));
  const e = (elders || []).find((i) => String(i.id) === String(sel));
  const others = active.filter((a) => !items.some((i) => String(i.elderId) === String(a.id)));
  const first = x && x.level !== 'LOW';
  return (
    <Screen title="관리 대상 리포트" wide stay>
      <div className="split r400">
        <div className="sidecol">
          <div className="ai">
            <div className="ai-h">관리 대상 리포트 AI</div>
            <div><b>봉사 신청 내역</b>(성사·거절·만료)과 <b>활동 일지</b>를 모아 대상별 동향을 정리하고, <b>안부 확인 우선순위</b>를 매겼어요. 진단이나 건강 판단은 하지 않아요.</div>
            <div className="ai-why">신청·활동 횟수와 순서는 시스템이 계산하고, AI는 봉사자 일지 내용만 정리해요.</div>
            <RuleNote source={chg && chg.source} />
          </div>
          <div className="pnl lst">
            <div className="prw" style={{ padding: '4px 6px 10px' }}><b style={{ flex: 1 }}>먼저 안부를 물어볼 순서</b><Seg label="기간" value={range} onChange={setRange} items={[['recent', `최근 ${(chg && chg.windowDays) || 30}일`], ['today', '오늘']]} /></div>
            {chg === undefined ? <Loading /> : items.length ? items.map((i) => {
              const on = String(i.elderId) === String(sel);
              const change = (i.changes && i.changes.length ? i.changes : i.reasons && i.reasons.length ? i.reasons : ['이전 기록과 비슷해요.']).join(' · ');
              return (
                <button key={i.elderId} type="button" className={'lrow top ' + (on ? 'on' : '')} aria-current={on ? 'true' : undefined} onClick={() => pick(i.elderId)}>
                  <b className="rk">{i.rank}</b>
                  <span className="lr-t wrap"><b>{i.displayName}</b> <span className="mu">{i.dong}</span> {i.level !== 'LOW' ? <span className="ptag r">우선</span> : <span className="ptag">비슷해요</span>}<small>{change}</small></span>
                </button>
              );
            }) : <div className="pempty">{range === 'today' ? '오늘 들어온 일지가 없어요' : '담당 대상이 없어요'}</div>}
            {others.length > 0 && <><div className="lst-h">다른 담당 대상</div><div className="pchips wrap">{others.map((a) => <button key={a.id} type="button" className={'pchip ' + (String(a.id) === String(sel) ? 'on' : '')} onClick={() => pick(a.id)}>{elderName(a)}</button>)}</div></>}
          </div>
        </div>
        <div className="dcol">
          {!sel ? <div className="pnl pempty">{elders === undefined ? '불러오는 중이에요' : '담당 대상이 없어요'}</div> : (
            <>
              <div className="pnl">
                <div className="prw">
                  <Avc n={(x && (x.alias || x.displayName)) || (e && e.alias)} cat="acc" size={56} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="d-t">{x ? x.displayName : e ? elderName(e) : '대상'} <span className="mu" style={{ fontSize: 14, fontWeight: 500 }}>{[e && e.ageGroup, (x && x.dong) || (e && e.dong)].filter(Boolean).join(' · ')}</span>{x && <span className={'ptag ' + (first ? 'r' : '')}>{x.rank}순위 · {first ? '안부 먼저 확인' : '비슷해요'}</span>}</div>
                    {e && <div className="ptags">{arr(e.needs).map((c) => <CPill key={c} cat={c}>{short(c)}</CPill>)}</div>}
                  </div>
                  <button type="button" className="pbtn" onClick={() => nav(`/o/elders/${sel}`)}>관리 대상 정보</button>
                </div>
              </div>
              {x && (
                <div className="ai">
                  <div className="ai-h">동향 요약</div>
                  <div style={{ fontWeight: 600 }}>{(x.changes && x.changes.length ? x.changes : x.reasons && x.reasons.length ? x.reasons : ['이전 기록과 비슷해요.']).join(' · ')}</div>
                  <div className="ai-in"><div className="mu">특이사항</div><div>{x.summary}</div>{x.applyText && <><div className="mu" style={{ marginTop: 8 }}>신청 내역</div><div>{x.applyText}</div></>}</div>
                  {first && x.reasons && x.reasons.length > 0 && <div className="ai-why">먼저 살펴보는 이유: {x.reasons.join(' · ')}</div>}
                </div>
              )}
              <div className="pnl mob">
                <div className="ps-h"><b>최근 활동 기록 <span className="mu" style={{ fontWeight: 500 }}>인수인계용</span></b></div>
                <RecentReport key={sel} subject={`elder:${sel}`} showMonthly={false} />
                <div className="mu" style={{ marginTop: 10 }}>전달 메모는 담당자만 봐요. 봉사자에게는 별칭·동·도움 내용·주의사항만 넘어가요.</div>
              </div>
            </>
          )}
          <div className="pnl">
            <div className="ps-h"><b>지난 월간 리포트 <span className="cnt">{reps.length}</span></b></div>
            {list === undefined ? <Loading /> : !reps.length ? <div className="pempty">아직 만들어진 월간 리포트가 없어요</div> : (
              <div className="g2 in">
                {reps.map((r) => {
                  let st = r.stats || r.statsJson || {};
                  if (typeof st === 'string') { try { st = JSON.parse(st); } catch { st = {}; } }
                  const series = st.prevCount != null ? [['지난달', Number(st.prevCount) || 0], ['이번 달', Number(st.count) || 0]] : null;
                  return (
                    <div className="mcard mob" key={r.id}>
                      <div className="prw"><b style={{ flex: 1 }}>{r.subjectLabel || `대상 #${r.subjectId}`} <span className="mu" style={{ fontWeight: 500 }}>{r.month}</span></b>{r.bigChange ? <span className="ptag r">변화 큼</span> : <span className="ptag">비슷함</span>}</div>
                      {series && hbars(series, r.bigChange ? '#E5655B' : '#4FAE96')}
                      <div style={{ marginTop: 8, fontSize: 13 }}>{r.narrative}</div>
                      <RuleNote source={r.source} />
                      {!r.bigChange && <button type="button" className="pbtn" style={{ marginTop: 10 }} onClick={async () => { try { await post(`/org/reports/${r.id}/flag`); reload(); toast('변화가 큰 사례로 표시했어요'); } catch { /* 토스트 */ } }}>변화 큰 사례로 표시</button>}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
          <div className="mu" style={{ textAlign: 'center', marginTop: 12 }}>이 리포트는 기록을 정리할 뿐, 건강 상태를 진단하지 않아요.</div>
        </div>
      </div>
    </Screen>
  );
}
export function OReport() { return usePc() ? <OReportPc /> : <OReportMobile />; }

/* ================= 기관 행사 (O-POST-06): 왼쪽 올린 행사 + 오른쪽 새 행사 폼 ================= */
function OEventsPc() {
  const nav = useNavigate();
  const [evs, reload] = useLoad(() => api('/org/events', { quiet: true }).catch(() => []), []);
  const [ver, setVer] = useState(0);
  return (
    <Screen title="기관 행사" wide stay>
      <div className="split ev">
        <div className="dcol">
          <div className="lst-t" style={{ padding: '0 2px 10px' }}>올린 행사 {(evs || []).length}<span className="mu" style={{ fontWeight: 500, marginLeft: 8 }}>사용자의 "동네 기관 소식"에 함께 보이고, 신청은 기관 페이지 링크에서 받아요</span></div>
          {evs === undefined ? <div className="pnl"><Loading /></div> : evs.length ? evs.map((e) => (
            <button key={e.id} type="button" className="pnl evc" onClick={() => nav(`/news/${e.id}`)}>
              <div className="prw"><span className={'ptag ' + (e.status === 'OPEN' ? 'g' : '')}>{e.status === 'OPEN' ? '올림' : '내림'}</span><span className="mu">{e.date ? `${dlabel(e.date)}${e.startHour != null ? ` ${hh(e.startHour)}` : ''}` : '날짜 미정'}</span></div>
              <div className="ev-t">{e.title}</div>
              <div className="mu">{[e.place, e.capacity ? `정원 ${e.capacity}명` : null, e.url ? host(e.url) : '링크 없음'].filter(Boolean).join(' · ')}</div>
            </button>
          )) : <div className="pnl pempty">아직 올린 소식이 없어요</div>}
        </div>
        <div className="pnl">
          <div className="ps-h"><b>새 기관 행사</b></div>
          <div className="mob"><EventFormBody key={ver} onDone={() => { reload(); setVer((v) => v + 1); }} /></div>
        </div>
      </div>
    </Screen>
  );
}
export function OEvents({ form }) { return usePc() ? <OEventsPc /> : form ? <OEventNewMobile /> : <OEventsMobile />; }

/* ================= 내 정보·설정 (O-HOME-03 자리): 프로필 카드 + 묶음 메뉴 ================= */
function OMyPc() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast } = useUi();
  const logout = useLogout();
  const org = (me && (me.orgName || (me.org && me.org.name))) || '기관';
  const G = [
    ['돌봄', [
      ['돌봄 편성표', '주간 일정 · 빈 칸 추천 · 공고 한꺼번에 올리기', 'cal', () => nav('/o/schedule')],
      ['대상 정기 요청', '매주 같은 요일·시간으로 대신 등록한 요청 · 일시정지·해지', 'clock', () => nav('/recurring')],
      ['특보 알림', '날씨 특보 · 운영자 안내', 'flag', () => nav('/o/weather')],
      ['공공 서비스 연계', '사용자가 전달한 요청 처리', 'shield', () => nav('/o/public')],
    ]],
    ['기관', [
      ['기관 정보', org, 'building', () => toast(`${org} · 담당자 ${me && me.name}`)],
      ['단체 봉사 공고', '단체 모집 · 봉사 가능한 단체 받기 · 참석 확인', 'users', () => nav('/board?tab=group')],
      ['기관 소식 운영', '기관 행사·교실·모임 올리기', 'cal', () => nav('/o/events')],
      ['카테고리 건의', '6가지 종류에 없는 도움을 운영자에게 건의', 'flag', () => nav('/my/suggest')],
    ]],
    ['알림 · 화면', [
      ['알림', '대상 관련 알림을 대신 받아요', 'bell', () => nav('/notif')],
      ['알림 설정', '담당 대상 관련 알림을 종류별로 켜고 꺼요', 'bell', () => nav('/my/noti')],
      'text',
    ]],
    ['도움말', [
      ['1:1 문의 · 신고 내역', null, 'msg', () => nav('/qna')],
      ['공지사항', null, 'file', () => nav('/notice')],
      ['공공 서비스 안내', null, 'shield', () => nav('/public')],
    ]],
    ['계정', [
      ['내 정보 수정 · 비밀번호 변경', null, 'pen', () => nav('/my/info')],
      ['로그아웃', null, 'logout', logout],
      ['회원 탈퇴', '담당 대상을 이관·종료한 뒤 탈퇴할 수 있어요', 'flag', () => nav('/my/withdraw')],
    ]],
  ];
  return (
    <Screen title="내 정보·설정" wide>
      <div className="pnl">
        <div className="prw">
          <Avc n={me && me.name} cat="acc" size={56} />
          <div style={{ flex: 1, minWidth: 0 }}><div className="d-t">{me && me.name}</div><div className="mu" style={{ fontSize: 13 }}>{org} · 담당자 계정{me && me.loginId ? ` · ${me.loginId}` : ''}</div></div>
          <button type="button" className="pbtn" onClick={() => nav('/my/info')}>내 정보 수정</button>
          <button type="button" className="pbtn" onClick={() => nav('/my/noti')}>알림 설정</button>
        </div>
      </div>
      <div className="mygrid">
        {G.map(([g, rows]) => (
          <div key={g} className="pnl">
            <div className="ps-h"><b>{g}</b></div>
            <div className="ml mob">{rows.map((r) => (r === 'text' ? <TextSizeRow key="text" /> : <Mi key={r[0]} t={r[0]} s={r[1]} i={r[2]} onClick={r[3]} />))}</div>
          </div>
        ))}
      </div>
      <div className="notebox" style={{ marginTop: 18 }}>담당자 계정으로는 봉사할 수 없어요. 봉사하려면 사용자 계정을 따로 만들어 주세요.</div>
    </Screen>
  );
}
export function OMy() { return usePc() ? <OMyPc /> : <OMyMobile />; }
