import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { SUG_ST } from '../ui/suggest.jsx';
import { Ic, Mi, Spk, Mod, RuleNote, Loading, Empty, useLoad, useBusy, hbars } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api, post, put, del, openFile } from '../api.js';
import { loadCats } from '../lib/cats.js'; // 기능 A-15 더한 카테고리 다시 읽기
import { CATS, CC, SHORT, AREAS, fmt, dshort, arr, lvName, ro } from '../lib/format.js';
import { I } from '../ui/icons.js';
import { useLogout } from './my.jsx';
import { JudgeAssist } from '../ui/ActivityAi.jsx'; // 7.13 B-4
import { INQ_ST } from './common.jsx';

const VERIFY_KO = { NONE: '미제출', PENDING: '확인 중', APPROVED: '승인', REJECTED: '반려' };
import { ReportMenu, ReportContext } from './adminReports.jsx'; // 기능명세서 운영자 리포트 진입 · 신고 관련 기록
import { useTriage, TriageBox, SevChip, useAnomalyNotes, AnomalyNote, InquiryDraft, PersonaFromCases, PersonaDraftNote } from './opsai.jsx';

function groupBars(rows) {
  const W2 = 320, H = 170, pt = 22, pb = 28, bw = 16, gap = 5;
  const max = Math.max(1, ...rows.flatMap((r) => [r[1], r[2]])) * 1.1;
  const cw = (W2 - 16) / Math.max(1, rows.length);
  return (
    <svg viewBox={`0 0 ${W2} ${H}`} className="chart" role="img" aria-label="수요 공급">
      {rows.map(([d, a, b], i) => {
        const cx = 8 + cw * i + cw / 2, ha = ((H - pt - pb) * a) / max, hb = ((H - pt - pb) * b) / max, by = H - pb;
        return (
          <g key={d}>
            <rect x={cx - bw - gap / 2} y={by - ha} width={bw} height={ha} rx="6" fill="#F59E4B" />
            <rect x={cx + gap / 2} y={by - hb} width={bw} height={hb} rx="6" fill="#5B8DEF" />
            <text x={cx - bw / 2 - gap / 2} y={by - ha - 6} textAnchor="middle" fontSize="10.5" fontWeight="700" fill="#C46A17">{a}</text>
            <text x={cx + bw / 2 + gap / 2} y={by - hb - 6} textAnchor="middle" fontSize="10.5" fontWeight="700" fill="#5B8DEF">{b}</text>
            <text x={cx} y={H - 8} textAnchor="middle" fontSize="12" fill="#5F6672">{d}</text>
          </g>
        );
      })}
    </svg>
  );
}

/* ---------- 5장 분석: 나이대·월별 추이·수요 공급·AI 인사이트 ---------- */
const ccOf2 = (k) => (CC[k] || ['#EEF3FF', '#5B8DEF'])[1];
function stackBars(months) {
  const W2 = 320, H = 170, pt = 18, pb = 26;
  const max = Math.max(1, ...months.map((m) => Number(m.total) || 0)) * 1.1;
  const cw = (W2 - 8) / Math.max(1, months.length), bw = Math.min(16, cw - 6);
  return (
    <svg viewBox={`0 0 ${W2} ${H}`} className="chart" role="img" aria-label="월별 카테고리 추이">
      {months.map((m, i) => {
        const cx = 4 + cw * i + cw / 2;
        let y = H - pb;
        const segs = Object.entries(m.counts || {}).filter(([, v]) => v > 0).map(([k, v]) => {
          const h = ((H - pt - pb) * v) / max; y -= h;
          return <rect key={k} x={cx - bw / 2} y={y} width={bw} height={h} fill={ccOf2(k)}><title>{`${k} ${v}건`}</title></rect>;
        });
        const mo = Number(String(m.month).slice(5));
        return (
          <g key={m.month}>
            {segs}
            {m.total > 0 && <text x={cx} y={y - 4} textAnchor="middle" fontSize="9.5" fontWeight="700" fill="#5F6672">{m.total}</text>}
            <text x={cx} y={H - 8} textAnchor="middle" fontSize="10.5" fill={i === months.length - 1 ? '#1B1D22' : '#687080'} fontWeight={i === months.length - 1 ? 700 : 400}>{mo}월</text>
          </g>
        );
      })}
    </svg>
  );
}

function AgeCard({ a }) {
  const [seg, setSeg] = useState('rec');
  const rows = (seg === 'rec' ? a.byRecipientAge : a.byVolunteerAge) || [];
  const mx = Math.max(1, ...rows.map((r) => r.total));
  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div className="between"><span className="b">나이대별 인기 봉사</span><span className="mute2">최근 {a.periodMonths || 12}개월</span></div>
      <div className="chips" style={{ margin: '8px 0 4px' }}>{[['rec', '봉사받는 사람'], ['vol', '봉사자']].map(([k, t]) => <button key={k} className={'chip ' + (seg === k ? 'on' : '')} onClick={() => setSeg(k)}>{t}</button>)}</div>
      {rows.length ? rows.map((r) => (
        <div key={r.ageGroup} style={{ padding: '8px 0', borderTop: '1px dashed var(--line)' }}>
          <div className="between"><b>{r.ageGroup}</b><span className="mute2">{r.total}건</span></div>
          <div style={{ display: 'flex', height: 10, borderRadius: 6, overflow: 'hidden', background: 'var(--fill, #F1F2F5)', margin: '6px 0', width: `${Math.max(12, (r.total / mx) * 100)}%` }}>
            {Object.entries(r.counts || {}).map(([k, v]) => <i key={k} title={`${k} ${v}`} style={{ width: `${(v / r.total) * 100}%`, background: ccOf2(k) }} />)}
          </div>
          <div className="small muted">{(r.top || []).map((t, i) => `${i + 1}위 ${SHORT[t.category] || t.category} ${t.count}`).join(' · ')}</div>
        </div>
      )) : <Empty>아직 데이터가 없어요</Empty>}
    </div>
  );
}

function InsightCard() {
  const nav = useNavigate();
  const [ins, setIns] = useState(undefined);
  const [busy, run] = useBusy();
  const load = (refresh) => run(async () => { const r = await api(`/admin/insights${refresh ? '?refresh=true' : ''}`); setIns(r); });
  useEffect(() => { load(false); }, []); // eslint-disable-line
  return (
    <div className="ai" style={{ marginTop: 12 }}>
      <div className="ah"><Spk />AI 인사이트<Mod n={5} /></div>
      {ins === undefined ? <div className="skel" /> : !ins ? <div className="small">불러오지 못했어요</div> : (
        <>
          <div className="small">{ins.summary}</div>
          {(ins.shortages || []).length > 0 && <><div className="b" style={{ fontSize: 13.5, marginTop: 10 }}>공급이 부족한 봉사</div>
            {ins.shortages.map((s) => <div key={s.category} className="small" style={{ marginTop: 6 }}><b>{s.category}</b> <span className="mute2">수요 {s.demand} · 공급 {s.supply} (−{s.gap})</span><div>{s.reason}</div></div>)}</>}
          {(ins.categorySuggestions || []).length > 0 && <div className="small" style={{ marginTop: 12 }}><span className="b" style={{ fontSize: 13.5 }}>사용자 건의</span> {ins.categorySuggestions.slice(0, 4).map((c) => <span key={c.key} className="tag2" style={{ marginLeft: 6 }}>{c.name} {c.count}건</span>)} <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontWeight: 700, marginLeft: 6 }} onClick={() => nav('/a/suggest')} onKeyDown={(e) => { if (e.key === 'Enter') nav('/a/suggest'); }}>처리하기 ›</a></div>}
          {(ins.newCategories || []).length > 0 && <><div className="b" style={{ fontSize: 13.5, marginTop: 12 }}>새 카테고리 후보</div>
            {ins.newCategories.map((c) => <div key={c.name} className="small" style={{ marginTop: 6 }}><span className="tag2">{c.name}</span> {c.reason}{(c.examples || []).length > 0 && <div className="mute2" style={{ marginTop: 3 }}>예: “{c.examples.join('”, “')}”</div>}</div>)}</>}
          <div className="mute2" style={{ marginTop: 10 }}>카테고리에 안 맞는 요청 문장 {ins.unmatchedCount ?? 0}개 분석 · 숫자는 시스템 집계, 이유는 AI 짐작 · {ins.cached ? '1시간 안 결과(캐시)' : '방금 분석'} {ins.generatedAt ? `· ${dshort(ins.generatedAt)}` : ''}</div>
          <RuleNote source={ins.source} />
        </>
      )}
      <button className="btn soft sm" style={{ marginTop: 10 }} disabled={busy} onClick={() => load(true)}>{busy ? '분석 중…' : '다시 분석'}</button>
    </div>
  );
}

/** D-04 지역별 부족한 도움: 지역마다 요청 대비 부족 건수(막대) + 가장 부족한 지역의 부족한 도움 종류 */
function AreaShortCard({ a }) {
  const rows = ((a && a.areaShortage) || []).filter((r) => r.demand > 0 || r.supply > 0);
  const top = rows.find((r) => r.shortTotal > 0);
  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div className="b">지역별 부족한 도움 <span className="mute2" style={{ fontWeight: 500 }}>(요청 대비 부족 건수)</span></div>
      {rows.length ? hbars(rows.map((r) => [r.area, Number(r.shortTotal) || 0]), '#F59E4B') : <Empty>아직 데이터가 없어요</Empty>}
      {rows.filter((r) => r.shortTotal > 0).slice(0, 4).map((r) => <div key={r.area} className="small" style={{ marginTop: 6 }}><b>{r.area}</b> <span className="mute2">도움 요청 {r.demand} · 봉사 가능 {r.supply}</span> {(r.short || []).map((c) => <span key={c.category} className="tag2" style={{ marginLeft: 6 }}>{SHORT[c.category] || c.category} −{c.gap}</span>)}</div>)}
      <div className="mute2" style={{ marginTop: 8 }}>{top ? `${top.area}은 ${(top.short || []).slice(0, 2).map((c) => SHORT[c.category] || c.category).join('·')} 봉사가 특히 부족해요` : '지금은 부족한 지역이 없어요'} · 최근 {a.supplyDemandDays}일</div>
    </div>
  );
}

/** D-11 계절별 봉사 요청·봉사 가능량(봉사 유형별) */
function SeasonCard({ a }) {
  const seasons = (a && a.seasonSupplyDemand) || [];
  const firstWith = seasons.find((s) => s.demand > 0 || s.supply > 0);
  const [sel, setSel] = useState(null);
  const cur = seasons.find((s) => s.season === (sel || (firstWith && firstWith.season))) || seasons[0];
  if (!seasons.length) return null;
  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div className="between"><span className="b">계절별 봉사 요청 · 봉사 가능</span><span className="legend"><i style={{ background: '#F59E4B' }} />봉사 요청<i style={{ background: '#5B8DEF', marginLeft: 8 }} />봉사 가능</span></div>
      <div className="chips" style={{ margin: '8px 0 4px' }}>{seasons.map((s) => <button key={s.season} className={'chip ' + (cur && cur.season === s.season ? 'on' : '')} onClick={() => setSel(s.season)}>{s.season} {s.demand}/{s.supply}</button>)}</div>
      {cur && (cur.demand > 0 || cur.supply > 0) ? groupBars((cur.categories || []).map((c) => [SHORT[c.category] || c.category, Number(c.demand) || 0, Number(c.supply) || 0])) : <Empty>이 계절에는 아직 데이터가 없어요</Empty>}
      <div className="small muted" style={{ marginTop: 4 }}>{cur ? ((cur.categories || []).filter((c) => c.gap > 0).map((c) => `${SHORT[c.category] || c.category} ${c.gap}건 부족`).join(' · ') || '부족한 봉사 유형이 없어요') : ''}</div>
      <div className="mute2" style={{ marginTop: 6 }}>최근 {a.periodMonths || 12}개월 · 공고·요청을 만든 달의 계절로 나눠요 (봄 3~5월 · 여름 6~8월 · 가을 9~11월 · 겨울 12~2월)</div>
    </div>
  );
}

function AnalyticsCards({ a }) {
  if (!a) return a === undefined ? <Loading /> : null;
  const sd = a.supplyDemand || [];
  const cats = Object.keys(((a.monthlyCategory || [])[0] || {}).counts || {});
  return (
    <>
      <AgeCard a={a} />
      <div className="card" style={{ marginTop: 12 }}>
        <div className="between"><span className="b">월(계절)별 인기 봉사</span><span className="mute2">완료 활동</span></div>
        {stackBars(a.monthlyCategory || [])}
        <div className="legend" style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 10px' }}>{cats.map((k) => <span key={k}><i style={{ background: ccOf2(k) }} />{SHORT[k] || k}</span>)}</div>
        <div className="small muted" style={{ marginTop: 8 }}>{(a.seasons || []).filter((s) => s.total > 0).map((s) => `${s.season}: ${(s.top || []).map((t) => SHORT[t.category] || t.category).join('·')}`).join(' / ')}</div>
      </div>
      <div className="card" style={{ marginTop: 12 }}>
        <div className="between"><span className="b">카테고리별 수요 vs 공급</span><span className="legend"><i style={{ background: '#F59E4B' }} />수요<i style={{ background: '#5B8DEF', marginLeft: 8 }} />공급</span></div>
        {groupBars(sd.map((s) => [SHORT[s.category] || s.category, Number(s.demand) || 0, Number(s.supply) || 0]))}
        <div className="small muted" style={{ marginTop: 4 }}>{sd.filter((s) => s.gap > 0).map((s) => `${SHORT[s.category] || s.category} ${s.gap}건 부족`).join(' · ') || '부족한 카테고리가 없어요'}</div>
        <div className="mute2" style={{ marginTop: 6 }}>최근 {a.supplyDemandDays}일 · 수요 = 봉사 요청 공고 + 받는 쪽 요청, 공급 = 도움 가능 공고 + 수락된 건</div>
      </div>
      <SeasonCard a={a} />
      <InsightCard />
    </>
  );
}

/* ---------- 운영 대시보드 (D-04, D-05, P-07) ---------- */
export function ADash() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [d, reload] = useLoad(() => api('/admin/dashboard'), []);
  const [an] = useLoad(() => api('/admin/analytics', { quiet: true }).catch(() => null), []);
  const [busy, run] = useBusy();
  if (d === undefined) return <Screen title="운영 대시보드" back={false}><Loading /></Screen>;
  const x = d || {};
  const tb = x.timeBalance || {};
  const pend = x.pending || {};
  const used = tb.useRatio ?? (tb.issued ? Math.round((Number(tb.consumed) / Number(tb.issued)) * 100) : 0);
  const kpi = x.kpi || {};
  const monthly = (x.weekly || x.monthly || []).map((m) => [m.label || String(m.month).slice(5) + '월', Number(m.demand || 0), Number(m.supply || 0)]);
  const delta = (a, b, unit = '') => { const d = (Number(a) || 0) - (Number(b) || 0); return <em className={d >= 0 ? 'up' : 'dn'}>{d >= 0 ? '▲' : '▼'} {Math.abs(d)}{unit}</em>; };
  return (
    <Screen title="운영 대시보드" back={false}>
      {/* 상단 네 칸(2026-11-13 대표 지시): 이번 달 활동 · 매칭률 · 신규 가입/총 가입자 · 등록 기관 수 */}
      <div className="kpis" style={{ gridTemplateColumns: 'repeat(2,1fr)' }}>
        <div><b>{kpi.activities ?? 0}건</b><span>이번 달 활동</span>{delta(kpi.activities, kpi.prevActivities)}</div>
        <div><b>{kpi.matchRate ?? 0}%</b><span>매칭률</span></div>
        <div><b>{kpi.newUsers ?? 0}명 <small style={{ fontSize: 13, fontWeight: 600, color: 'var(--sub)' }}>/ {kpi.totalUsers ?? 0}명</small></b><span>신규 가입 / 총 가입자</span></div>
        <div><b>{kpi.orgCount ?? 0}곳</b><span>등록 기관 수</span></div>
      </div>
      <div className="stat2" style={{ marginTop: 8 }}><div><b>{x.demand ?? 0}</b><span>이번 달 봉사 요청 공고</span></div><div><b>{x.supply ?? 0}</b><span>이번 달 도움 가능 공고</span></div></div>
      {Object.values(pend).some((v) => v) && (
        <div className="card" style={{ marginTop: 12 }}>
          <div className="b">처리할 일</div>
          <div className="tags" style={{ marginTop: 8 }}>
            {[['orgs', '기관 신청', '/a/approve?seg=org'], ['ids', '신분증', '/a/approve?seg=vol'], ['crimes', '범죄경력', '/a/approve?seg=vol'], ['appeals', '난이도 이의', '/a/approve?seg=diff'], ['reports', '신고', '/a/safety'], ['welfare', '안부 확인 접수', '/a/safety'], ['inquiries', '문의', '/a/inquiries']].filter(([k]) => pend[k]).map(([k, l, to]) => <button key={k} className="tag2" onClick={() => nav(to)}>{l} {pend[k]}</button>)}
          </div>
        </div>
      )}
      <ReportMenu />
      {monthly.length > 0 && <div className="card" style={{ marginTop: 12 }}><div className="between"><span className="b">수요 vs 공급 (주별 공고)</span><span className="legend"><i style={{ background: '#F59E4B' }} />봉사 요청<i style={{ background: '#5B8DEF', marginLeft: 8 }} />도움 가능</span></div>{groupBars(monthly)}</div>}
      <div className="card" style={{ marginTop: 12 }}><div className="b">지역별 매칭 성공률</div>{(x.matchRateByArea || []).length ? hbars(x.matchRateByArea.map((a) => [a.area, Number(a.rate) || 0]), '#5B8DEF') : <Empty>아직 데이터가 없어요</Empty>}<div className="mute2" style={{ marginTop: 8 }}>요청·제안 중 일정 확정까지 간 비율(%)이에요.</div></div>
      <AnalyticsCards a={an} />
      {an && <AreaShortCard a={an} />}
      <div className="card" style={{ marginTop: 12 }}>
        <div className="b">시간 균형</div>
        <div className="stat2" style={{ marginTop: 10 }}><div><b>{fmt(tb.issued)}시간</b><span>적립(발행)</span></div><div><b>{fmt(tb.consumed)}시간</b><span>사용(소비)</span></div><div><b>{fmt(tb.elderFree)}시간</b><span>기관 대상 무차감</span></div></div>
        <div className="bar" style={{ marginTop: 12, height: 12 }}><i style={{ width: `${Math.min(100, used)}%` }} /></div>
        <div className="between mute2" style={{ marginTop: 6 }}><span>사용 {used}%</span><span>남은 발행 {100 - Math.min(100, used)}%</span></div>
        <div className="small muted" style={{ marginTop: 8 }}>기관 대상 도움은 받는 쪽 차감이 없어서 쌓이는 시간이 더 많아요. 총량을 계속 지켜봐야 해요.</div>
      </div>
      <div className="card" style={{ marginTop: 12 }}><div className="between"><span className="b">첫 활동 연습</span><span className="mod m6">교육 AI</span></div><div className="small muted" style={{ marginTop: 8 }}>선택해서 해 보는 AI 역할극이에요. 역할극 상대와 상황은 가상 상대 관리에서 고쳐요.</div><button className="btn soft sm" style={{ marginTop: 10 }} onClick={() => nav('/a/persona')}>가상 상대 관리</button></div>
      <button className="btn ghost" style={{ marginTop: 14 }} disabled={busy} onClick={() => run(async () => { const r = await post('/admin/scheduler/run', {}); toast(r && r.message ? r.message : '만료·자동 확정·노쇼·마감·리마인드를 한 번 실행했어요'); reload(); })}>스케줄러 지금 실행 (시연)</button>
    </Screen>
  );
}

/* ---------- 승인 (D-01, D-03, B-28) ---------- */
const VKIND = { id: '신분증', crime: '범죄경력 회보서' };
export function AApprove() {
  const [sp, setSp] = useSearchParams();
  const seg = sp.get('seg') || 'org';
  const { toast, sheet, closeSheet } = useUi();
  const [orgs, reloadO] = useLoad(() => api('/admin/orgs'), []);
  const [vers, reloadV] = useLoad(() => api('/admin/verifications'), []);
  const [aps, reloadA] = useLoad(() => api('/admin/appeals'), []);
  const [busy, run] = useBusy();
  const po = (orgs || []).filter((o) => o.status === 'PENDING');
  const pv = Array.isArray(vers) ? vers : vers ? [...(vers.id || vers.ids || []).map((v) => ({ ...v, kind: 'id' })), ...(vers.crime || vers.crimes || []).map((v) => ({ ...v, kind: 'crime' }))] : [];
  const pa = (aps || []).filter((a) => a.status === 'RECEIVED');
  return (
    <Screen title="승인" back={false}>
      <div className="chips" style={{ marginBottom: 8 }}>{[['org', '기관 신청', po.length], ['vol', '봉사자 신원', pv.length], ['diff', '난이도 이의', pa.length]].map(([k, t, n]) => <button key={k} className={'chip ' + (seg === k ? 'on' : '')} onClick={() => setSp({ seg: k }, { replace: true })}>{t} {n}</button>)}</div>
      {seg === 'org' && (orgs === undefined ? <Loading /> : po.length ? po.map((o) => (
        <div className="card" key={o.id}>
          <div className="between"><span className="b" style={{ fontSize: 16 }}>{o.name}</span><span className="mute2">{dshort(o.createdAt).split(' ')[0]}</span></div>
          <div className="mute2">{o.orgType} · 담당자 {o.managerName} · {o.managerPhone || o.phone}</div>
          {o.address && <div className="small muted">{o.address}</div>}
          {o.memo && <div className="small" style={{ marginTop: 6 }}>{o.memo}</div>}
          <div className="small" style={{ marginTop: 6 }}><b>재직 증빙</b> {o.proofFileUrl ? <a role="button" tabIndex={0} style={{ color: 'var(--brand)', cursor: 'pointer' }} onClick={() => openFile(o.proofFileUrl)} onKeyDown={(e) => { if (e.key === 'Enter') openFile(o.proofFileUrl); }}>재직증명서 열어 보기 ↗</a> : <span className="warnc">증빙 없음 (41판 이전 신청)</span>}</div>
          <div className="btns" style={{ marginTop: 12 }}>
            <button className="btn ghost sm" disabled={busy} onClick={() => run(async () => { await post(`/admin/orgs/${o.id}/reject`); reloadO(); toast(`${o.name}: 반려`); })}>반려</button>
            <button className="btn sm" disabled={busy} onClick={() => run(async () => {
              const r = await post(`/admin/orgs/${o.id}/approve`);
              reloadO();
              sheet(() => <><h2>담당자 계정을 발급했어요</h2><div className="sum"><div className="between"><span className="muted">기관</span><b>{o.name}</b></div><div className="between"><span className="muted">아이디</span><b>{r.managerLoginId}</b></div><div className="between"><span className="muted">임시 비밀번호</span><b>{r.tempPassword}</b></div></div><div className="mute2" style={{ marginTop: 8 }}>기관 전화로 안내하고, 첫 로그인 뒤 비밀번호를 바꾸게 해 주세요.</div><button className="btn" style={{ marginTop: 14 }} onClick={closeSheet}>확인</button></>);
            })}>승인 · 계정 발급</button>
          </div>
        </div>
      )) : <Empty>처리할 항목이 없어요</Empty>)}
      {seg === 'vol' && (vers === undefined ? <Loading /> : pv.length ? pv.map((v) => {
        const uid = v.userId || v.id;
        return (
          <div className="card" key={`${v.kind}-${uid}`}>
            <div className="between"><span className="b" style={{ fontSize: 16 }}>{v.name || v.userName}</span><span className="st wait">{VKIND[v.kind] || v.kind}</span></div>
            <div className="mute2">{v.loginId ? `${v.loginId} · ` : ''}{v.area || ''} {v.ageGroup || ''}</div>
            {v.fileUrl && <a role="button" tabIndex={0} style={{ color: 'var(--brand)', fontSize: 14, cursor: 'pointer' }} onClick={() => openFile(v.fileUrl)} onKeyDown={(e) => { if (e.key === 'Enter') openFile(v.fileUrl); }}>제출 파일 보기 ↗</a>}
            <div className="btns" style={{ marginTop: 12 }}>
              <button className="btn ghost sm" disabled={busy} onClick={() => run(async () => { await post(`/admin/verifications/${uid}/${v.kind}/reject`); reloadV(); toast('반려했어요'); })}>반려</button>
              <button className="btn sm" disabled={busy} onClick={() => run(async () => { await post(`/admin/verifications/${uid}/${v.kind}/approve`); reloadV(); toast('승인했어요'); })}>승인</button>
            </div>
          </div>
        );
      }) : <Empty>처리할 항목이 없어요</Empty>)}
      {seg === 'diff' && (aps === undefined ? <Loading /> : pa.length ? pa.map((a) => (
        <div className="card" key={a.id}>
          <div className="between"><span className="b" style={{ fontSize: 16 }}>{(a.user && a.user.name) || '봉사자'}</span><span className="mute2">{dshort(a.createdAt).split(' ')[0]}</span></div>
          <div className="mute2">{a.content || `요청 #${a.requestId}`}{a.recipient ? ` · ${a.recipient}` : ''} · {lvName(a.currentLevel)} → {lvName(a.requestedLevel)} 요청{a.hours ? ` · ${a.hours}시간` : ''}</div>
          <div className="small" style={{ marginTop: 6 }}>“{a.reason}”</div>
          <JudgeAssist kind="APPEAL" id={a.id} />
          <div className="btns" style={{ marginTop: 12 }}>
            <button className="btn ghost sm" disabled={busy} onClick={() => run(async () => { await post(`/admin/appeals/${a.id}/answer`, { accept: false, resultLevel: a.currentLevel, note: '' }); reloadA(); toast('반려했어요'); })}>반려</button>
            <button className="btn sm" disabled={busy} onClick={() => run(async () => { await post(`/admin/appeals/${a.id}/answer`, { accept: true, resultLevel: a.requestedLevel, note: '' }); reloadA(); toast(`${lvName(a.requestedLevel)}로 조정했어요. 차액이 적립돼요`); })}>{lvName(a.requestedLevel)}로 조정</button>
          </div>
        </div>
      )) : <Empty>처리할 항목이 없어요</Empty>)}
    </Screen>
  );
}

/* ---------- 신고·점검 (D-02, D-06) ---------- */
const ACTS = [['WARNING', '경고'], ['SUSPEND', '이용 정지'], ['BAN', '퇴출'], ['NONE', '신고 기각']];
function ReportHandle({ r, onDone, t, onTriage }) {
  const [a, setA] = useState('WARNING');
  const [result, setResult] = useState('');
  const { toast } = useUi();
  return (
    <>
      <h2>{r.type}</h2>
      <p className="mute2" style={{ margin: 0 }}>{r.targetLabel || `${r.targetType} #${r.targetId}`} · 신고자 {r.reporter ? r.reporter.name : ''}{r.targetUser ? ` · 대상 ${r.targetUser.name}` : ''} · {dshort(r.createdAt)}</p>
      {r.content && <div className="thread" style={{ marginTop: 12 }}><div className="tm"><div className="bub">{r.content}</div></div></div>}
      {r.messageText && <div className="thread" style={{ marginTop: 8 }}><div className="tm"><div className="bub">신고된 메시지: {r.messageText}</div></div></div>}
      {r.welfare ? (
        <>
          {/* D-02: 독거노인 이상 징후 접수는 제재 없이 접수 기록 확인용 */}
          <div className="notice" style={{ marginTop: 12 }}>이상 징후(안부 확인) 접수예요. 제재 대상이 없고, 접수 때 담당자{r.manager ? `(${r.manager.name})` : ''}에게 알림이 갔어요. 운영자는 기록을 확인만 해요.</div>
          <textarea className="in" style={{ marginTop: 10, minHeight: 70 }} placeholder="확인 메모 (접수한 사람에게 알림으로 가요 · 선택)" value={result} onChange={(e) => setResult(e.target.value)} />
          <button className="btn" style={{ marginTop: 12 }} onClick={async () => { try { await post(`/admin/reports/${r.id}/handle`, { action: 'NONE', result }); onDone(); toast('접수 기록을 확인했어요'); } catch { /* 토스트 */ } }}>기록 확인</button>
        </>
      ) : (
        <>
      <TriageBox t={t} onRefresh={onTriage} />
      <ReportContext id={r.id} />
      <label className="f">처리</label>
      {ACTS.map(([k, l]) => <div key={k} className={'opt ' + (a === k ? 'on' : '')} role="button" tabIndex={0} onClick={() => setA(k)}><span className="rd" /><div>{l}</div></div>)}
      <textarea className="in" style={{ marginTop: 10, minHeight: 70 }} placeholder="처리 결과 (신고자에게 알림으로 가요)" value={result} onChange={(e) => setResult(e.target.value)} />
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => { try { await post(`/admin/reports/${r.id}/handle`, { action: a, result: result || ACTS.find((x) => x[0] === a)[1] }); onDone(); toast('처리했어요. 신고자에게 결과를 알렸어요'); } catch { /* 토스트 */ } }}>처리하고 결과 알림 보내기</button>
        </>
      )}
    </>
  );
}
export function ASafety() {
  const { toast, sheet, closeSheet } = useUi();
  const [rps, reload] = useLoad(() => api('/admin/reports'), []);
  const [abs, reloadAb] = useLoad(() => api('/admin/anomalies'), []);
  const [busy, run] = useBusy();
  const [tri, reloadTri] = useTriage();
  const noteOf = useAnomalyNotes();
  const ord = (r) => (tri[r.id] ? tri[r.id].order : 9999);
  const open = (rps || []).filter((r) => r.status !== 'DONE' && !r.welfare).sort((a, b) => ord(a) - ord(b));
  const done = (rps || []).filter((r) => r.status === 'DONE' && !r.welfare);
  // D-02: 독거노인 이상 징후(안부 확인)는 제재 없는 접수 기록으로 따로 묶는다
  const wf = (rps || []).filter((r) => r.welfare);
  const handle = (r) => sheet(() => <ReportHandle r={r} t={tri[r.id]} onTriage={() => { closeSheet(); reloadTri(); }} onDone={() => { closeSheet(); reload(); reloadTri(); }} />);
  return (
    <Screen title="신고·점검" back={false}>
      <div className="sec-t" style={{ marginTop: 4 }}>신고 {open.length}건</div>
      {rps === undefined ? <Loading /> : open.length ? open.map((r) => <div key={r.id} className="card click" role="button" tabIndex={0} onClick={() => sheet(() => <ReportHandle r={r} t={tri[r.id]} onTriage={() => { closeSheet(); reloadTri(); }} onDone={() => { closeSheet(); reload(); reloadTri(); }} />)}><div className="between"><span className="b">{r.type}</span><span style={{ display: 'flex', gap: 4 }}><SevChip s={tri[r.id] && tri[r.id].severity} /><span className="st wait">{r.status === 'REVIEWING' ? '검토 중' : '접수'}</span></span></div><div className="mute2">{r.targetLabel || r.targetType} · {dshort(r.createdAt).split(' ')[0]}</div>{tri[r.id] && <div className="small muted" style={{ marginTop: 4 }}>{tri[r.id].summary}</div>}</div>) : <Empty>새 신고가 없어요</Empty>}
      <div className="sec-t"><span>안부 확인 접수 {wf.filter((r) => r.status !== 'DONE').length}건</span><span className="mute2" style={{ fontWeight: 500 }}>제재 없음 · 기록 확인용</span></div>
      {rps === undefined ? <Loading /> : wf.length ? wf.map((r) => (
        <div key={r.id} className={'card' + (r.status !== 'DONE' ? ' click' : '')} role={r.status !== 'DONE' ? 'button' : undefined} tabIndex={r.status !== 'DONE' ? 0 : undefined} onClick={r.status !== 'DONE' ? () => handle(r) : undefined}>
          <div className="between"><span className="b">{r.elder ? `${r.elder.displayName} · ${r.elder.dong || ''}` : r.targetLabel}</span><span style={{ display: 'flex', gap: 4 }}><span className="badge">접수 기록</span><span className={'st ' + (r.status === 'DONE' ? 'ok' : 'wait')}>{r.status === 'DONE' ? '확인함' : '확인 전'}</span></span></div>
          <div className="mute2">접수 {r.reporter ? r.reporter.name : ''}{r.manager ? ` · 담당자 ${r.manager.name}에게 알림` : ''} · {dshort(r.createdAt)}</div>
          {r.content && <div className="small muted" style={{ marginTop: 4 }}>{r.content}</div>}
        </div>
      )) : <Empty>안부 확인 접수가 없어요</Empty>}
      <div className="sec-t">이상 적립 패턴</div>
      {abs === undefined ? <Loading /> : (abs || []).length ? abs.map((x, i) => {
        const rqs = x.requests || [];
        const held = rqs.filter((q) => q.held).length;
        const all = (op) => run(async () => { for (const q of rqs) await post(`/admin/anomalies/${q.id}/${op}`); reloadAb(); toast(op === 'hold' ? '적립을 보류하고 재확인을 요청했어요' : '정상으로 표시했어요'); });
        return (
          <div className="card" key={i}>
            <div className="b">{x.reason}</div>
            <div className="mute2">{x.volunteer ? x.volunteer.name : ''} ↔ {x.recipient} · {x.count}회 · 적립 {fmt(x.totalEarned)}시간</div>
            <div className="small muted" style={{ marginTop: 6 }}>{rqs.map((q) => `${q.date.slice(5)} ${q.hours}시간${q.held ? '(보류)' : ''}`).join(' · ')}</div>
            {held > 0 && <div className="mute2 warnc">{held}건 적립 보류 중</div>}
            <AnomalyNote n={noteOf(x)} />
            <div className="btns" style={{ marginTop: 10 }}><button className="btn ghost sm" disabled={busy} onClick={() => all('release')}>정상</button><button className="btn sm warn" disabled={busy} onClick={() => all('hold')}>적립 보류</button></div>
          </div>
        );
      }) : <Empty>이상 패턴이 없어요</Empty>}
      {done.length > 0 && <><div className="sec-t">처리한 신고 {done.length}</div>{done.map((r) => <div key={r.id} className="card"><div className="between"><span className="b">{r.type}</span><span className="st ok">처리 완료</span></div><div className="mute2">{r.targetLabel} · {(ACTS.find((a) => a[0] === r.action) || [])[1] || ''} {r.result || ''}</div></div>)}</>}
    </Screen>
  );
}

/* ---------- 관리 메뉴 ---------- */
export function AManage() {
  const nav = useNavigate();
  const logout = useLogout();
  return (
    <Screen title="관리" back={false}>
      <div className="ml">
        <Mi t="회원 관리" s="회원 목록·상세·정지·해제" i="users" onClick={() => nav('/a/members')} />
        <Mi t="담당자 계정" s="정지·해제 · 대상 대신 이관" i="building" onClick={() => nav('/a/managers')} />
        <Mi t="담당자 계정 신청" s="신청·승인 내역 · 승인 취소" i="shield" onClick={() => nav('/a/mgr-apps')} />
        <Mi t="1:1 문의" s="답변 등록 · 처리 상태 변경" i="msg" onClick={() => nav('/a/inquiries')} />
        <Mi t="공지사항" s="등록·수정·고정·삭제" i="file" onClick={() => nav('/a/notices')} />
        <Mi t="리포트" s="재정 추계 · 정책 시뮬레이션 · 봉사 리포트" i="chart" onClick={() => nav('/a/forecast')} />
        <Mi t="대응 추천 · 특보 알림" s="모집 문구 초안 · 기상 특보 리포트와 전달" i="flag" onClick={() => nav('/a/weather')} />
        <Mi t="봉사 인증 항목 설정" s="신분증 · 첫 활동 교육 · 범죄경력(선택)" i="shield" onClick={() => nav('/a/verify')} />
        <Mi t="카테고리 건의" s="사용자·담당자가 건의한 새 종류 · 답변" i="flag" onClick={() => nav('/a/suggest')} />
        <Mi t="공공 서비스 정보" s="AI 안내에 쓰는 DB" i="building" onClick={() => nav('/a/pub')} />
        <Mi t="기관 클래스" s="공공기관 강좌 · CSV 가져오기 · AI 다시 찾기" i="cap" onClick={() => nav('/a/classes')} />
        <Mi t="가상 상대 관리" s="AI 역할극 상대 · 숨은 시험 상황" i="users" onClick={() => nav('/a/persona')} />
        <Mi t="로그아웃" i="logout" onClick={logout} />
        <Mi t="회원 탈퇴" s="운영자가 한 명뿐이면 탈퇴할 수 없어요" i="flag" onClick={() => nav('/my/withdraw')} />
      </div>
      <div className="notice" style={{ marginTop: 14 }}>운영자 계정은 운영만 해요. 봉사하려면 사용자 계정을 따로 만들어야 해요.</div>
    </Screen>
  );
}

/* ---------- 회원 관리 (D-07) ---------- */
const RL = { USER: '사용자', ORG: '담당자', ADMIN: '운영자' };
function MemberSheet({ id, onDone }) {
  const [u, reload] = useLoad(() => api(`/admin/users/${id}`), [id]);
  const { toast } = useUi();
  if (!u) return <Loading />;
  const sus = u.status === 'SUSPENDED';
  return (
    <>
      <h2>{u.name} <span className="mute2" style={{ fontSize: 14, fontWeight: 500 }}>{u.loginId}</span></h2>
      <dl className="kv">
        <dt>역할</dt><dd>{RL[u.role] || u.role}</dd><dt>상태</dt><dd>{u.banned ? '퇴출 (다시 로그인할 수 없어요)' : { ACTIVE: '정상', SUSPENDED: '정지', WITHDRAWN: '탈퇴' }[u.status] || u.status}</dd>
        <dt>지역</dt><dd>{u.area} · {u.ageGroup}</dd><dt>연락처</dt><dd>{u.phone}</dd>
        <dt>봉사</dt><dd>{u.volunteerCount ?? u.helpCount ?? '-'}회</dd><dt>도움 받음</dt><dd>{u.recipientCount ?? u.helpedCount ?? '-'}회</dd>
        <dt>잔액</dt><dd>{u.balance != null ? `${fmt(u.balance)}시간` : '-'}</dd><dt>경고·노쇼</dt><dd>경고 {u.warningCount || 0} · 패널티 {u.penaltyCount || 0}</dd>
        {u.role === 'USER' && <><dt>인증</dt><dd>신분증 {VERIFY_KO[u.idStatus] || u.idStatus} · 범죄경력 회보서 {VERIFY_KO[u.crimeStatus] || u.crimeStatus}</dd></>}
        <dt>가입</dt><dd>{dshort(u.createdAt)}</dd>
      </dl>
      {u.banned && <div className="notice" style={{ marginTop: 14 }}>신고 처리로 퇴출된 계정이에요. 진행 중이던 요청은 취소되고 열린 공고는 마감됐어요. 정지와 달리 다시 풀 수 없어요.</div>}
      {u.role !== 'ADMIN' && u.status !== 'WITHDRAWN' && !u.banned && <button className={'btn ' + (sus ? '' : 'warn')} style={{ marginTop: 14 }} onClick={async () => { try { await post(`/admin/users/${u.id}/${sus ? 'unsuspend' : 'suspend'}`); await reload(); onDone(); toast(sus ? '정지를 해제했어요' : '정지했어요'); } catch { /* 토스트 */ } }}>{sus ? '정지 해제' : '회원 정지'}</button>}
    </>
  );
}
export function AMembers() {
  const { sheet } = useUi();
  const [q, setQ] = useState('');
  const [role, setRole] = useState('');
  const [qq, setQq] = useState('');
  const [list, reload] = useLoad(() => api(`/admin/users?q=${encodeURIComponent(qq)}&role=${role}`), [qq, role]);
  return (
    <Screen title="회원 관리" noTabs>
      <form className="row" onSubmit={(e) => { e.preventDefault(); setQq(q.trim()); }}><input className="in grow" placeholder="이름·아이디 검색" value={q} onChange={(e) => setQ(e.target.value)} /><button className="btn sm" style={{ width: 'auto', padding: '0 16px', flex: '0 0 auto' }}>검색</button></form>
      <div className="chips" style={{ marginTop: 10 }}>{[['', '전체'], ['USER', '사용자'], ['ORG', '담당자'], ['ADMIN', '운영자']].map(([k, l]) => <button key={k} className={'chip ' + (role === k ? 'on' : '')} onClick={() => setRole(k)}>{l}</button>)}</div>
      {list === undefined ? <Loading /> : (list || []).length ? list.map((u) => (
        <div key={u.id} className="card item click" style={{ marginTop: 8 }} role="button" tabIndex={0} onClick={() => sheet(() => <MemberSheet id={u.id} onDone={reload} />)}>
          <div className="grow"><div className="b">{u.name} <span className="mute2" style={{ fontWeight: 500 }}>{u.loginId}</span></div><div className="mute2">{RL[u.role]} · {u.area || ''}{u.volunteerCount != null ? ` · 봉사 ${u.volunteerCount}` : ''}{u.recipientCount != null ? ` · 받음 ${u.recipientCount}` : ''}{u.warningCount ? ` · 경고 ${u.warningCount}` : ''}</div></div>
          <span className={'st ' + (u.status === 'ACTIVE' ? 'ok' : 'no')}>{u.banned ? '퇴출' : { ACTIVE: '정상', SUSPENDED: '정지', WITHDRAWN: '탈퇴' }[u.status] || u.status}</span>
        </div>
      )) : <Empty>회원이 없어요</Empty>}
    </Screen>
  );
}

/* ---------- 담당자 계정 (D-01, C-03 대신 이관) ---------- */
function AdminTransfer({ m, managers, onDone }) {
  const [to, setTo] = useState(null);
  const { toast } = useUi();
  const elders = (m.elders || []).filter((e) => e.status !== 'ENDED');
  const others = managers.filter((x) => x.id !== m.id && x.status === 'ACTIVE');
  return (
    <>
      <h2>{m.name} 담당 대상 대신 이관</h2>
      <label className="f">받는 담당자</label>
      {others.map((x) => <div key={x.id} className={'opt ' + (to === x.id ? 'on' : '')} role="button" tabIndex={0} onClick={() => setTo(x.id)}><span className="rd" /><div className="grow"><div className="b">{x.name}</div><div className="mute2">{x.orgName || ''}</div></div></div>)}
      <label className="f">대상 {elders.length}명</label>
      {elders.length ? elders.map((e) => <div key={e.id} className="card item" style={{ marginTop: 6 }}><div className="grow"><div className="b">{e.alias} 님</div><div className="mute2">{e.dong || ''}</div></div><button className="btn soft sm" style={{ width: 'auto', padding: '0 12px' }} disabled={!to} onClick={async () => { try { await post(`/admin/elders/${e.id}/transfer`, { toManagerId: to }); toast(`${e.alias} 님을 이관했어요`); onDone(); } catch { /* 토스트 */ } }}>이관</button></div>) : <Empty>담당 대상 정보가 없어요</Empty>}
    </>
  );
}
export function AManagers() {
  const { toast, sheet, closeSheet } = useUi();
  const [list, reload] = useLoad(() => api('/admin/managers'), []);
  const [busy, run] = useBusy();
  return (
    <Screen title="담당자 계정" noTabs>
      {list === undefined ? <Loading /> : (list || []).length ? list.map((m) => {
        const sus = m.status === 'SUSPENDED';
        return (
          <div key={m.id} className="card" style={{ marginTop: 8 }}>
            <div className="between"><span className="b">{m.orgName || (m.org && m.org.name) || ''} · {m.name}</span><span className={'st ' + (sus ? 'no' : 'ok')}>{m.banned ? '퇴출' : sus ? '정지' : '정상'}</span></div>
            <div className="mute2">{m.loginId} · 대상 {(m.elders || []).length}명</div>
            <div className="btns" style={{ marginTop: 10 }}>
              <button className="btn ghost sm" disabled={busy || m.banned} onClick={() => run(async () => { await post(`/admin/managers/${m.id}/${sus ? 'unsuspend' : 'suspend'}`); reload(); toast(sus ? '정지를 해제했어요' : '정지했어요'); })}>{sus ? '정지 해제' : '정지'}</button>
              <button className="btn soft sm" onClick={() => sheet(() => <AdminTransfer m={m} managers={list} onDone={() => { closeSheet(); reload(); }} />)}>대신 이관</button>
            </div>
          </div>
        );
      }) : <Empty>담당자가 없어요</Empty>}
    </Screen>
  );
}

/* ---------- 1:1 문의 관리 (D-08) ---------- */
function AnswerSheet({ q, onDone }) {
  const [a, setA] = useState(q.answer || '');
  const { toast } = useUi();
  return (
    <>
      <h2>{q.title}</h2>
      <p className="mute2" style={{ margin: 0 }}>{q.user ? q.user.name : ''} · {q.type} · {dshort(q.createdAt)}</p>
      <div className="card" style={{ marginTop: 10, whiteSpace: 'pre-wrap' }}>{q.content}</div>
      <InquiryDraft q={q} onUse={(t) => setA(t)} />
      <label className="f">답변</label><textarea className="in" style={{ minHeight: 120 }} value={a} onChange={(e) => setA(e.target.value)} />
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => { if (!a.trim()) return toast('답변을 적어 주세요'); try { await post(`/admin/inquiries/${q.id}/answer`, { answer: a.trim() }); onDone(); toast('답변을 등록했어요'); } catch { /* 토스트 */ } }}>답변 등록 (알림 발송)</button>
    </>
  );
}
export function AInquiries() {
  const { sheet, closeSheet, toast } = useUi();
  const [busy, run] = useBusy();
  const [list, reload] = useLoad(() => api('/admin/inquiries'), []);
  return (
    <Screen title="1:1 문의" noTabs>
      {list === undefined ? <Loading /> : (list || []).length ? list.map((q) => (
        <div key={q.id} className="card" style={{ marginTop: 8 }}>
          <div className="between"><span className="b">{q.title}</span><span className={'st ' + (INQ_ST[q.stage || q.status] || INQ_ST.RECEIVED)[0]}>{(INQ_ST[q.stage || q.status] || INQ_ST.RECEIVED)[1]}</span></div>
          <div className="mute2">{q.user ? q.user.name : ''} · {q.type} · {dshort(q.createdAt).split(' ')[0]}</div>
          {q.answer && <div className="small muted" style={{ marginTop: 6 }}>답변: {q.answer}</div>}
          <div className="chips" role="group" aria-label="처리 상태" style={{ marginTop: 8, flexWrap: 'wrap' }}>{Object.entries(INQ_ST).map(([k, [, t]]) => <button key={k} className={'chip ' + ((q.stage || q.status) === k ? 'on' : '')} disabled={busy} onClick={() => (q.stage || q.status) !== k && run(async () => { await put(`/admin/inquiries/${q.id}/status`, { status: k }); reload(); toast(`처리 상태를 '${t}'${ro(t).slice(t.length)} 바꿨어요`); })}>{t}</button>)}</div>
          <button className="btn soft sm" style={{ marginTop: 8 }} onClick={() => sheet(() => <AnswerSheet q={q} onDone={() => { closeSheet(); reload(); }} />)}>{q.answer ? '답변 고치기' : '답변 쓰기'}</button>
        </div>
      )) : <Empty>문의가 없어요</Empty>}
    </Screen>
  );
}

/* ---------- 공지 관리 (D-09) ---------- */
function NoticeForm({ n, onDone }) {
  const [t, setT] = useState(n ? n.title : '');
  const [c, setC] = useState(n ? n.content : '');
  const { toast } = useUi();
  return (
    <>
      <h2>{n ? '공지 수정' : '새 공지'}</h2>
      <label className="f">제목</label><input className="in" value={t} onChange={(e) => setT(e.target.value)} />
      <label className="f">내용</label><textarea className="in" value={c} onChange={(e) => setC(e.target.value)} />
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => { if (!t.trim()) return toast('제목을 적어 주세요'); try { if (n) await put(`/admin/notices/${n.id}`, { title: t, content: c }); else await post('/admin/notices', { title: t, content: c }); onDone(); toast('저장했어요'); } catch { /* 토스트 */ } }}>저장</button>
    </>
  );
}
export function ANotices() {
  const { toast, confirm, sheet, closeSheet } = useUi();
  const [list, reload] = useLoad(() => api('/notices'), []);
  const done = () => { closeSheet(); reload(); };
  return (
    <Screen title="공지사항 관리" noTabs>
      <button className="btn sm" onClick={() => sheet(() => <NoticeForm onDone={done} />)}><Ic h={I.plus} /> 새 공지</button>
      {list === undefined ? <Loading /> : (list || []).map((n) => (
        <div key={n.id} className="card item" style={{ marginTop: 8 }}>
          <div className="grow"><div className="b">{n.pinned && <span className="badge" style={{ marginRight: 6 }}>고정</span>}{n.title}</div><div className="mute2">{dshort(n.createdAt).split(' ')[0]}</div></div>
          <a className="mute2" role="button" tabIndex={0} style={{ cursor: 'pointer', whiteSpace: 'nowrap' }} onClick={async () => { try { await post(`/admin/notices/${n.id}/pin`, { pinned: !n.pinned }); reload(); toast(n.pinned ? '고정을 풀었어요' : '맨 위에 고정했어요'); } catch { /* 토스트 */ } }}>{n.pinned ? '고정 해제' : '고정'}</a>
          <a className="mute2" role="button" tabIndex={0} style={{ cursor: 'pointer' }} onClick={async () => { const full = await api(`/notices/${n.id}`); sheet(() => <NoticeForm n={full} onDone={done} />); }}>수정</a>
          <a className="mute2 danger" role="button" tabIndex={0} style={{ cursor: 'pointer' }} onClick={async () => { if (await confirm('공지를 지울까요?', n.title, '삭제', true)) { try { await del(`/admin/notices/${n.id}`); reload(); toast('지웠어요'); } catch { /* 토스트 */ } } }}>삭제</a>
        </div>
      ))}
    </Screen>
  );
}

/* ---------- 공공 서비스 정보 (P-02) ---------- */
function PubForm({ p, onDone }) {
  const [f, setF] = useState(p ? { ...p, keywords: arr(p.keywords).join(', ') } : { name: '', target: '', content: '', applyHow: '', contact: '', region: '전국', keywords: '' });
  const { toast } = useUi();
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  return (
    <>
      <h2>{p ? '서비스 수정' : '서비스 등록'}</h2>
      {[['name', '서비스 이름'], ['target', '대상'], ['content', '내용'], ['applyHow', '신청 방법·신청처'], ['contact', '문의처 (전화)'], ['region', '지역'], ['keywords', '찾기 낱말 (쉼표로)']].map(([k, l]) => <div key={k}><label className="f">{l}</label>{k === 'content' ? <textarea className="in" style={{ minHeight: 80 }} value={f[k] || ''} onChange={set(k)} /> : <input className="in" value={f[k] || ''} onChange={set(k)} />}</div>)}
      <div className="mute2" style={{ marginTop: 8 }}>이름·신청 방법은 공식 사이트에서 확인한 뒤 입력해요.</div>
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => {
        if (!f.name.trim()) return toast('서비스 이름을 적어 주세요');
        const body = { name: f.name, target: f.target, content: f.content, applyHow: f.applyHow, contact: f.contact, region: f.region, keywords: f.keywords.split(',').map((x) => x.trim()).filter(Boolean) };
        try { if (p) await put(`/admin/public-services/${p.id}`, body); else await post('/admin/public-services', body); onDone(); toast('저장했어요'); } catch { /* 토스트 */ }
      }}>저장</button>
    </>
  );
}
export function APub() {
  const { toast, confirm, sheet, closeSheet } = useUi();
  const [list, reload] = useLoad(() => api('/admin/public-services'), []);
  const done = () => { closeSheet(); reload(); };
  return (
    <Screen title="공공 서비스 정보" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>AI 공공 서비스 안내는 이 목록에서만 찾아요. 없는 서비스는 만들어 내지 않아요. 이름·신청 방법은 공식 사이트에서 확인 후 입력해요.</p>
      <button className="btn" onClick={() => sheet(() => <PubForm onDone={done} />)}><Ic h={I.plus} /> 서비스 등록</button>
      {list === undefined ? <Loading /> : (list || []).map((p) => (
        <div className="card" style={{ marginTop: 10 }} key={p.id}>
          <div className="between"><span className="b">{p.name}</span><span><a className="mute2" role="button" tabIndex={0} style={{ cursor: 'pointer', marginRight: 10 }} onClick={() => sheet(() => <PubForm p={p} onDone={done} />)}>수정</a><a className="mute2 danger" role="button" tabIndex={0} style={{ cursor: 'pointer' }} onClick={async () => { if (await confirm('이 서비스를 지울까요?', p.name, '삭제', true)) { try { await del(`/admin/public-services/${p.id}`); reload(); toast('지웠어요'); } catch { /* 토스트 */ } } }}>삭제</a></span></div>
          <div className="small muted" style={{ marginTop: 4 }}>대상: {p.target}<br />신청: {p.applyHow}{p.contact ? ` · ☎ ${p.contact}` : ''}<br />지역: {p.region} · 낱말: {arr(p.keywords).join(', ')}</div>
        </div>
      ))}
    </Screen>
  );
}

/* ---------- 가상 상대 관리 ---------- */
function PersonaForm({ p, onDone, draft }) {
  const src = p || (draft && draft.draft);
  const [f, setF] = useState(src ? { ...src, turnsText: typeof src.turns === 'string' ? src.turns : JSON.stringify(src.turns || src.turnsJson || [], null, 1) } : { name: '', initial: '', ageLabel: '', fitCategory: CATS[0].k, trait: '', goal: '', opening: '', turnsText: '[]', active: true });
  const { toast } = useUi();
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  return (
    <>
      <h2>{p ? '상대 설정 수정' : '새 상대 만들기'}</h2>
      <PersonaDraftNote res={draft} />
      {[['name', '이름 (예: 이○례 님)'], ['initial', '머리글자'], ['ageLabel', '나이·설명 (예: 82세 · 어르신)'], ['trait', '성격'], ['goal', '교육 목표'], ['opening', '첫마디']].map(([k, l]) => <div key={k}><label className="f">{l}</label><input className="in" value={f[k] || ''} onChange={set(k)} /></div>)}
      <label className="f">맞는 도움 종류</label><select className="in" value={f.fitCategory} onChange={set('fitCategory')}>{CATS.map((c) => <option key={c.k}>{c.k}</option>)}</select>
      <label className="f">대화 흐름 (JSON · 보기와 숨은 시험 상황)</label><textarea className="in" style={{ minHeight: 140, fontFamily: 'ui-monospace,monospace', fontSize: 12 }} value={f.turnsText} onChange={set('turnsText')} />
      <div className={'su-check ' + (f.active ? 'on' : '')} role="button" tabIndex={0} onClick={() => setF({ ...f, active: !f.active })}><span className="bx" /><div>교육에 사용</div></div>
      <button className="btn" style={{ marginTop: 12 }} onClick={async () => {
        let turns; try { turns = JSON.parse(f.turnsText || '[]'); } catch { return toast('대화 흐름 JSON을 확인해 주세요'); }
        const body = { name: f.name, initial: f.initial, ageLabel: f.ageLabel, fitCategory: f.fitCategory, trait: f.trait, goal: f.goal, opening: f.opening, turns, active: f.active };
        try { if (p) await put(`/admin/personas/${p.id}`, body); else await post('/admin/personas', body); onDone(); toast('저장했어요. 바로 교육에 반영돼요'); } catch { /* 토스트 */ }
      }}>저장</button>
    </>
  );
}
export function APersona() {
  const { toast, confirm, sheet, closeSheet } = useUi();
  const [list, reload] = useLoad(() => api('/admin/personas'), []);
  const done = () => { closeSheet(); reload(); };
  const PC = [['#FFEDF3', '#E2557F'], ['#FFF1E2', '#EE8424'], ['#EAF0FF', '#5B8DEF'], ['#E4F5F9', '#178FAD'], ['#FFF6D6', '#C99500']];
  return (
    <Screen title="가상 상대 관리" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>AI 역할극에서 쓰는 상대예요. 성격과 숨은 시험 상황을 여기서 고치면 코드 배포 없이 바로 바뀌어요.</p>
      <button className="btn" onClick={() => sheet(() => <PersonaForm onDone={done} />)}><Ic h={I.plus} /> 새 상대 만들기</button>
      <button className="btn soft" style={{ marginTop: 8 }} onClick={() => sheet(() => <PersonaFromCases onDraft={(r) => sheet(() => <PersonaForm draft={r} onDone={done} />)} />)}><Ic n="spark" s={18} /> 실제 사례로 만들기</button>
      {list === undefined ? <Loading /> : (list || []).map((r, i) => {
        let turns = r.turns || r.turnsJson || []; if (typeof turns === 'string') { try { turns = JSON.parse(turns); } catch { turns = []; } }
        const test = turns.map((t) => ((t.o || t.options || []).find((o) => o.f || o.flag) || {})).map((o) => o.f || o.flag).filter(Boolean).join(' · ');
        return (
          <div className="card" style={{ marginTop: 10 }} key={r.id}>
            <div className="persona" style={{ background: 'none', padding: 0 }}><span className="pa" style={{ background: PC[i % 5][0], color: PC[i % 5][1] }}>{r.initial || (r.name || '?')[0]}</span><div className="grow"><div className="b">{r.name} <span className="mute2" style={{ fontWeight: 500 }}>{r.ageLabel}</span></div><div className="small muted">{r.trait}</div></div><a className="mute2" role="button" tabIndex={0} style={{ cursor: 'pointer', whiteSpace: 'nowrap' }} onClick={() => sheet(() => <PersonaForm p={r} onDone={done} />)}>수정</a></div>
            <div className="small" style={{ marginTop: 10 }}>숨은 시험: <b>{test || '반복 설명, 쉬운 말'}</b></div>
            <div className="between mute2" style={{ marginTop: 4 }}><span>{SHORT[r.fitCategory] || r.fitCategory} 교육에 사용{r.active === false ? ' · 꺼짐' : ''}</span><a role="button" tabIndex={0} className="danger" style={{ cursor: 'pointer' }} onClick={async () => { if (await confirm('이 상대를 지울까요?', r.name, '삭제', true)) { try { await del(`/admin/personas/${r.id}`); reload(); toast('지웠어요'); } catch { /* 토스트 */ } } }}>삭제</a></div>
          </div>
        );
      })}
    </Screen>
  );
}
export const _x = AREAS;

/* ---------- 봉사 인증 항목 (프로토타입 a_verify) ---------- */
export function AVerify() {
  const { toast } = useUi();
  const [d] = useLoad(() => api('/verify-items'), []);
  const [items, setItems] = useState(null);
  const [busy, run] = useBusy();
  // 41판: 휴대폰 본인인증(필수) · 신분증 인증(필수) · 범죄경력 회보서(선택) 세 줄만. 안전 교육·오리엔테이션은 인증 항목이 아니다
  const VI = { phone: ['휴대폰 본인인증', '가입할 때 · 필수'], id: ['신분증 인증', '운영자가 확인하고 승인 · 필수'], crime: ['범죄경력 회보서', '내면 신뢰 배지 · 선택'] };
  const list = (items || (d && d.items) || []).filter((x) => VI[x.key]).map((x) => ({ ...x, title: VI[x.key][0], desc: VI[x.key][1] }));
  const upd = (k, patch) => setItems(list.map((x) => (x.key === k ? { ...x, ...patch } : x)));
  return (
    <Screen title="봉사 인증 항목" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>봉사하는 사용자가 받을 인증을 정해요. 바꾸면 새로 가입한 사람부터 적용돼요.</p>
      {d === undefined ? <Loading /> : (
        <div className="ml">
          {list.map((x) => (
            <div key={x.key}>
              <div className="grow">{x.title}<span>{x.desc}</span></div>
              <button type="button" className={`st ${x.required ? 'go' : 'end'}`} style={{ marginRight: 8, border: 0, cursor: x.locked || !x.on ? 'default' : 'pointer', minHeight: 44, minWidth: 44 }} disabled={x.locked || !x.on} aria-label={`${x.title} ${x.required ? '필수' : '선택'} · 누르면 바뀌어요`} onClick={() => upd(x.key, { required: !x.required })}>{x.required ? '필수' : '선택'}</button>
              <button type="button" className={'toggle ' + (x.on ? 'on' : '')} role="switch" aria-checked={x.on} aria-label={`${x.title} 받기`} disabled={x.locked} onClick={() => upd(x.key, { on: !x.on, required: !x.on ? x.required : false })} />
            </div>
          ))}
        </div>
      )}
      <div className="notice" style={{ marginTop: 12 }}>기관 대상(혼자 사시는 분) 방문은 범죄경력 회보서를 낸 봉사자만 하도록 설정할 수 있어요. 휴대폰 본인인증은 끌 수 없어요.</div>
      <div className="mute2" style={{ marginTop: 8 }}>지금은 설정을 저장해 가입·인증 안내에 쓰고, 실제 봉사 조건(신분증 승인)은 기존 규칙대로 적용돼요.</div>
      <button className="btn big-cta" style={{ marginTop: 14 }} disabled={busy || !list.length} onClick={() => run(async () => { const r = await api('/admin/verify-items', { method: 'PUT', body: { items: list.map((x) => ({ key: x.key, on: x.on, required: x.required })) } }); setItems(r.items); toast('저장했어요'); })}>저장</button>
    </Screen>
  );
}

/* ---------- 카테고리 건의 처리 (2026-11-11) ---------- */
function SuggestAnswer({ g, onDone }) {
  const { toast } = useUi();
  const [status, setStatus] = useState(g.status === 'RECEIVED' ? 'REVIEWING' : g.status);
  const [answer, setAnswer] = useState((g.items[0] && g.items[0].answer) || '');
  const [busy, run] = useBusy();
  const [level, setLevel] = useState('BASIC');
  return (
    <>
      <h2>{g.name} · {g.count}건</h2>
      <p className="mute2" style={{ margin: '0 0 8px' }}>같은 이름의 건의 {g.count}건에 한꺼번에 답해요. 건의한 사람 모두에게 알림이 가요.</p>
      <div className="tags">{Object.entries(SUG_ST).map(([k, v]) => <button key={k} type="button" className={'chip ' + (status === k ? 'on' : '')} aria-pressed={status === k} onClick={() => setStatus(k)}>{v[1]}</button>)}</div>
      {status === 'ADOPTED' && (
        <>
          <label className="f">등급 (적립 가중치)</label>
          <div className="tags">{[['BASIC', '기본 1.0'], ['EFFORT', '수고 1.2']].map(([k, l]) => <button key={k} type="button" className={'chip ' + (level === k ? 'on' : '')} aria-pressed={level === k} onClick={() => setLevel(k)}>{l}</button>)}</div>
          <div className="notice" style={{ marginTop: 8 }}>반영으로 보내면 ‘{g.name}’ 종류가 공고 카테고리에 더해져요. 답변을 비우면 안내 문장이 자동으로 가요.</div>
        </>
      )}
      <label className="f" htmlFor="sug-ans">답변</label>
      <textarea id="sug-ans" className="in" style={{ minHeight: 90 }} maxLength={500} value={answer} onChange={(e) => setAnswer(e.target.value)} placeholder="예) 비슷한 요청이 많아 다음 개편 때 검토할게요. 지금은 '가벼운 집안일'로 올려 주세요." />
      <button className="btn" style={{ marginTop: 12 }} disabled={busy} onClick={() => run(async () => {
        if (status === 'ADOPTED') { await post(`/admin/category-suggestions/${g.items[0].id}/adopt`, { level, answer }); await loadCats(); toast(`'${g.name}' 종류를 카테고리에 더했어요. 건의자에게 알렸어요`); }
        else { await post(`/admin/category-suggestions/${g.items[0].id}/answer`, { status, answer }); toast('답변했어요. 건의자에게 알렸어요'); }
        onDone();
      })}>{status === 'ADOPTED' ? '승인하고 카테고리에 더하기' : '답변 보내기'}</button>
    </>
  );
}
export function ASuggest() {
  const { sheet, closeSheet } = useUi();
  const [d, reload] = useLoad(() => api('/admin/category-suggestions'), []);
  const groups = (d && d.groups) || [];
  return (
    <Screen title="카테고리 건의" noTabs>
      <div className="stat2"><div><b>{d ? d.total : 0}건</b><span>전체 건의</span></div><div><b>{d ? d.waiting : 0}건</b><span>답변 기다리는 중</span></div></div>
      <div className="notice" style={{ marginTop: 12 }}>{(d && d.note) || "건의를 '반영'으로 답하면 공고 카테고리에 더해져요."}</div>
      {d === undefined ? <Loading /> : groups.length ? groups.map((g) => (
        <div key={g.key} className="card" style={{ marginTop: 10 }}>
          <div className="between"><span className="b" style={{ fontSize: 16 }}>{g.name} <span className="pill">{g.count}건</span></span><span className={`st ${(SUG_ST[g.status] || SUG_ST.RECEIVED)[0]}`}>{(SUG_ST[g.status] || SUG_ST.RECEIVED)[1]}</span></div>
          {g.items.map((it) => <div key={it.id} className="small" style={{ marginTop: 8 }}><span className="mute2">{dshort(it.createdAt).split(' ')[0]} · {it.userRole === 'ORG' ? '담당자' : '사용자'} {it.userName}</span><br />{it.reason}</div>)}
          {g.items[0].answer && <div className="hnote" style={{ marginTop: 8 }}>답변: {g.items[0].answer}</div>}
          <button className="btn soft sm" style={{ marginTop: 10 }} onClick={() => sheet(() => <SuggestAnswer g={g} onDone={() => { closeSheet(); reload(); }} />)}>{g.items[0].answer ? '답변 고치기' : '답변하기'}</button>
        </div>
      )) : <Empty>아직 건의가 없어요</Empty>}
    </Screen>
  );
}
