/* 운영자(ADMIN) PC 화면 (프로토타입 5판, 계약서 7.24)
   폭 901px 이상에서만 쓰고, 좁으면 admin.jsx · adminReports.jsx 의 모바일 화면이 그대로 나온다.
   숫자·순위는 모두 서버 값이고 여기서는 배치만 한다(빼기·비율처럼 화면에 보이는 두 수의 셈만 한다). 처리(승인·반려·제재·답변)는 모바일과 같은 API·같은 폼을 쓴다. */
import { useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Loading, useLoad, useBusy, Spk, RuleNote } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { SUG_ST } from '../ui/suggest.jsx';
import { JudgeAssist } from '../ui/ActivityAi.jsx';
import { api, post, put, del, openFile } from '../api.js';
import { SHORT, fmt, dshort, arr, lvName, ro } from '../lib/format.js';
import { usePc, PIc, Avc, dc5, PcDrawer, pendSum } from '../ui/PcFrame.jsx';
import { INQ_ST } from './common.jsx';
import * as M from './admin.jsx';
import * as R from './adminReports.jsx';
import { useTriage, SevChip, useAnomalyNotes, AnomalyNote } from './opsai.jsx';

/* 5판 그래프 색: 도움 요청 · 봉사 가능 · 셋째 · 넷째 / 주는 쪽 · 받는 쪽 */
const C_NEED = '#5B8DEF', C_OFFER = '#2F9C7F', C_3 = '#E0A23E', C_4 = '#D9705A', C_GIVE = '#6DB3A0', C_RECV = '#6F9CF0';
const day = (dt) => (dt ? dshort(dt).split(' ')[0] : '');
const short = (c) => SHORT[c] || c || '';
const Tg = ({ k = 'k', children }) => <span className={'ptag ' + k}>{children}</span>;
const ST_K = { ok: 'g', go: 'g', wait: 'a', no: 'r', end: 'k' };
const Kv = ({ k, children }) => <div className="kv5"><span>{k}</span><b>{children}</b></div>;
const tops = (top) => (top || []).map((c, i) => `${i + 1}위 ${short(c.category)}(${c.count})`).join(' · ') || '기록 없음';

/** 5판 표(.tbl): head = [머리글], rows = [{ key, cells: […] }], 고른 줄은 역할 색 연한 바탕 */
function Tbl({ head, rows, sel, onSel, empty = '없어요', plain }) {
  if (!rows.length) return <div className="pempty">{empty}</div>;
  return (
    <table className={'tbl' + (plain ? ' plain' : '')}>
      <thead><tr>{head.map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
      <tbody>{rows.map((r) => (
        <tr key={r.key} className={sel === r.key ? 'on' : ''} tabIndex={onSel ? 0 : undefined} aria-selected={onSel ? sel === r.key : undefined}
          onClick={onSel ? () => onSel(r.key) : undefined} onKeyDown={onSel ? (e) => { if (e.key === 'Enter') onSel(r.key); } : undefined}>
          {r.cells.map((c, i) => <td key={i}>{c}</td>)}
        </tr>
      ))}</tbody>
    </table>
  );
}
/** 가로 막대 한 줄(5판 .hbar): segs = [[값, 색]] — 폭은 max 대비 % */
function HBar({ label, segs, max, right }) {
  return (
    <div className="hbar5"><span>{label}</span>
      <div className="bar5">{segs.map(([v, c], i) => <i key={i} title={String(v)} style={{ width: `${max ? (Number(v) / max) * 100 : 0}%`, background: c }} />)}</div>
      <b>{right}</b>
    </div>
  );
}
const Lg = ({ items }) => <span className="lg5">{items.map(([c, t]) => <span key={t}><i style={{ background: c }} />{t}</span>)}</span>;

/** 5판 꺾은선(lineChart): 2.5px 선 · 첫 계열 아래 옅은 면 · 끝점 링 · 마우스를 올리면 세로선과 값 말풍선. 라이브러리 없이 SVG. */
export function LineChart5({ x, series, h = 210, label }) {
  const [hi, setHi] = useState(null);
  const W = 640, pl = 40, pb = 28, pt = 18, pr = 52, n = x.length;
  const top = Math.max(1, ...series.flatMap((s) => s.d.map((v) => Number(v) || 0)));
  // 눈금 한 칸(q)은 1·2·5·10·25·50·100… 가운데 값을 다 담는 가장 작은 것, 맨 위는 그 네 배
  const q = [1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000].find((v) => v * 4 >= top) || Math.ceil(top / 4);
  const mx = q * 4;
  const X = (i) => (n > 1 ? pl + (i * (W - pl - pr)) / (n - 1) : pl + (W - pl - pr) / 2);
  const Y = (v) => pt + (h - pt - pb) * (1 - (Number(v) || 0) / mx);
  const band = n > 1 ? (W - pl - pr) / (n - 1) : W - pl - pr;
  return (
    <div className="lc5" onMouseLeave={() => setHi(null)}>
      <svg viewBox={`0 0 ${W} ${h}`} role="img" aria-label={label} style={{ width: '100%', height: 'auto', overflow: 'visible', display: 'block' }}>
        {[0, 1, 2, 3, 4].map((i) => (
          <g key={i}>
            <line x1={pl} x2={W - pr} y1={Y(q * i)} y2={Y(q * i)} stroke={i ? '#EEF1F5' : '#D9DEE6'} strokeDasharray={i ? '2 4' : undefined} />
            <text x={pl - 10} y={Y(q * i) + 4} fontSize="11" textAnchor="end" fill="#A3ACBA">{Math.round(q * i * 10) / 10}</text>
          </g>
        ))}
        {x.map((t, i) => <text key={i} x={X(i)} y={h - 8} fontSize="11" textAnchor="middle" fill="#A3ACBA">{t}</text>)}
        {series.map((s, k) => {
          const pts = s.d.map((v, i) => `${X(i)},${Y(v)}`).join(' '), L = s.d.length - 1;
          return (
            <g key={s.l}>
              {k === 0 && n > 1 && <polygon points={`${X(0)},${Y(0)} ${pts} ${X(L)},${Y(0)}`} fill={s.c} opacity=".08" />}
              <polyline fill="none" stroke={s.c} strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" points={pts} />
              {L >= 0 && <><circle cx={X(L)} cy={Y(s.d[L])} r="5" fill={s.c} stroke="#fff" strokeWidth="2.5" /><text x={X(L) + 10} y={Y(s.d[L]) + 4} fontSize="11.5" fill="#4A5262" fontWeight="700">{s.l}</text></>}
            </g>
          );
        })}
        {hi != null && (
          <g pointerEvents="none">
            <line x1={X(hi)} x2={X(hi)} y1={pt} y2={h - pb} stroke="#C9D1DC" />
            {series.map((s) => <circle key={s.l} cx={X(hi)} cy={Y(s.d[hi])} r="4" fill="#fff" stroke={s.c} strokeWidth="2" />)}
          </g>
        )}
        {x.map((t, i) => <rect key={i} x={X(i) - band / 2} y={pt} width={band} height={h - pb - pt} fill="transparent" tabIndex={0} aria-label={`${t} ${series.map((s) => `${s.l} ${s.d[i]}`).join(', ')}`} onMouseEnter={() => setHi(i)} onFocus={() => setHi(i)} onBlur={() => setHi(null)} />)}
      </svg>
      {hi != null && (
        <div className="lc5-tip" role="status" style={{ left: `${(X(hi) / W) * 100}%`, transform: hi > (n - 1) / 2 ? 'translateX(calc(-100% - 12px))' : 'translateX(12px)' }}>
          <b>{x[hi]}</b>
          {series.map((s) => <div key={s.l}><i style={{ background: s.c }} />{s.l} <b>{s.d[hi]}</b></div>)}
        </div>
      )}
    </div>
  );
}

/* ====================== A-01 대시보드 ====================== */
const quiet = (path, norm) => () => api(path, { quiet: true }).then((r) => (norm ? norm(r) : r)).catch(() => null);
const appList = (r) => (Array.isArray(r) ? r : (r && (r.items || r.applications)) || []);
const APP_ST = { APPROVED: ['g', '승인'], PENDING: ['a', '확인 중'], REJECTED: ['r', '반려'], CANCELLED: ['r', '취소'], REVOKED: ['r', '취소'], SUSPENDED: ['r', '정지'] };
const inqOpen = (q) => ['RECEIVED', 'CHECKING'].includes(q.stage || q.status);

function ADashPc() {
  const nav = useNavigate();
  const { me } = useAuth();
  const { toast } = useUi();
  const [d, reload] = useLoad(() => api('/admin/dashboard'), []);
  const [an] = useLoad(quiet('/admin/analytics'), []);
  const [wx] = useLoad(quiet('/admin/weather/alerts'), []);
  const [orgs] = useLoad(quiet('/admin/orgs'), []);
  const [rps] = useLoad(quiet('/admin/reports'), []);
  const [inq] = useLoad(quiet('/admin/inquiries'), []);
  const [sug] = useLoad(quiet('/admin/category-suggestions'), []);
  const [apps] = useLoad(quiet('/admin/manager-applications', appList), []);
  const [need] = useLoad(quiet('/admin/stats/by-type?type=NEED'), []);
  const [offer] = useLoad(quiet('/admin/stats/by-type?type=OFFER'), []);
  const [busy, run] = useBusy();
  if (d === undefined) return <Screen title="대시보드" back={false} wide><Loading /></Screen>;
  const x = d || {}, kpi = x.kpi || {}, pend = x.pending || {}, tb = x.timeBalance || {};
  const total = pendSum(pend);
  const weekly = x.weekly || [];
  const wk = weekly[weekly.length - 1] || {}, pw = weekly[weekly.length - 2] || {};
  const md = (s) => { const m = /^\d{4}-(\d{2})-(\d{2})/.exec(String(s || '')); return m ? `${Number(m[1])}/${Number(m[2])}` : ''; };
  const diff = (a, b) => { const v = (Number(a) || 0) - (Number(b) || 0); return `지난주보다 ${v >= 0 ? '+' : '−'}${Math.abs(v)}건`; };
  const gap = (Number(wk.demand) || 0) - (Number(wk.supply) || 0);
  const shortTop = an ? ((an.areaShortage || []).find((r) => r.shortTotal > 0) || null) : null;
  const alerts = (wx && wx.alerts) || [];
  const al = alerts.find((a) => !a.sentAt) || alerts[0];
  const kpis = [
    ['이번 달 활동', `${kpi.activities ?? 0}건`, '/a/stats', 'trend'],
    ['매칭률', `${kpi.matchRate ?? 0}%`, '/a/stats', 'check'],
    ['신규 가입 / 총 가입자', `${kpi.newUsers ?? 0}명 / ${kpi.totalUsers ?? 0}명`, '/a/members', 'users'],
    ['등록 기관 수', `${kpi.orgCount ?? 0}곳`, '/a/approve?seg=org', 'building'],
  ];
  const pendRows = [
    ['기관 가입 승인', pend.orgs, '/a/approve?seg=org', '#FFF4DC', '#A6760C', true],
    ['봉사자 신원(신분증·범죄경력)', (Number(pend.ids) || 0) + (Number(pend.crimes) || 0), '/a/approve?seg=vol', '#E8F0FE', '#3A6FD8', true],
    ['신고 검토', pend.reports, '/a/safety', '#FDECE6', '#C25E48', true],
    ['미답변 문의', pend.inquiries, '/a/inquiries', '#E2F3EC', '#2F8069', true],
    ['난이도 이의', pend.appeals, '/a/approve?seg=diff', '#FFF4DC', '#A6760C'],
    ['안부 확인 접수', pend.welfare, '/a/safety?tab=wf', '#FDECE6', '#C25E48'],
    ['이상 적립 패턴(묶음)', pend.anomalies, '/a/safety?tab=ab', '#F1F4F8', '#6B7585'],
  ].filter((r) => r[5] || Number(r[1]) > 0);
  const openRps = (rps || []).filter((r) => r.status !== 'DONE' && !r.welfare);
  const newOrgs = (orgs || []).filter((o) => o.status === 'PENDING');
  const openInq = (inq || []).filter(inqOpen);
  const sugWait = ((sug && sug.groups) || []).filter((g) => g.status === 'RECEIVED' || g.status === 'REVIEWING');
  // 지역별 수요·공급: 도움 요청 공고 수 / 봉사 가능 공고 수 (최근 n개월, 이용 리포트와 같은 값)
  const areas = {};
  ((need && need.byArea) || []).forEach((a) => { (areas[a.area] = areas[a.area] || [0, 0])[0] = Number(a.total) || 0; });
  ((offer && offer.byArea) || []).forEach((a) => { (areas[a.area] = areas[a.area] || [0, 0])[1] = Number(a.total) || 0; });
  const reg = Object.entries(areas).sort((a, b) => b[1][0] - a[1][0]).slice(0, 6);
  const regMax = Math.max(1, ...reg.flatMap(([, v]) => v));
  const cats = ((an && an.supplyDemand) || []).slice().sort((a, b) => (Number(b.demand) || 0) - (Number(a.demand) || 0)).slice(0, 4);
  const catMax = Math.max(1, ...cats.map((c) => Number(c.demand) || 0));
  const used = tb.useRatio ?? (tb.issued ? Math.round((Number(tb.consumed) / Number(tb.issued)) * 100) : 0);
  const rates = x.matchRateByArea || [];
  return (
    <Screen title="대시보드" back={false} wide>
      <div className="hb-hero">
        <div>
          <div className="hb-hi">{me ? me.name : ''} 님, 처리 대기가 <b>{total}건</b> 있어요</div>
          <div className="hb-sub">이번 달 도움 요청 공고 {x.demand ?? 0}건 · 봉사 가능 공고 {x.supply ?? 0}건{shortTop ? ` · ${shortTop.area}이 가장 부족해요(${(shortTop.short || []).slice(0, 2).map((c) => short(c.category)).join('·')})` : ''}</div>
        </div>
        <div className="hb-kpi">{kpis.map(([k, v, to, i]) => <button key={k} type="button" className="hk" onClick={() => nav(to)}><i className="hk-i"><PIc k={i} /></i><div><span>{k}</span><b>{v}</b></div></button>)}</div>
      </div>

      <div className="hb-grid">
        <div className="pnl">
          {al && (
            <button type="button" className="nbar" onClick={() => nav('/a/weather')}>
              <span className="nb-ic"><PIc k="snow" /></span>
              <b>[{al.type} {al.level}] {(al.facts && al.facts.from) ? `${md(al.facts.from)}~${md(al.facts.to)} ` : ''}{(al.areas || []).join('·')} · {al.sentAt ? `공지로 게시하고 담당자 ${al.sentManagers ?? 0}명에게 알렸어요` : '아직 전달 전이에요'}</b>
              <span className="nb-r">{al.sentAt ? dshort(al.sentAt) : '전달하기 ›'}</span>
            </button>
          )}
          <div className="pg2">
            <div className="psec">
              <div className="ps-h"><b>처리 대기 <span className="cnt">{total}</span></b><button type="button" className="ps-a" onClick={() => nav('/a/approve')}>전체보기 ›</button></div>
              {pendRows.map(([t, n, to, bg, fg]) => (
                <button key={t} type="button" className="hrow" onClick={() => nav(to)}><span className="hr-n" style={{ background: bg, color: fg }}>{n ?? 0}</span><span className="hr-t">{t}</span><span className="hr-d">›</span></button>
              ))}
            </div>
            <div className="psec">
              <div className="ps-h"><b>최근 신고 <span className="cnt">{openRps.length}</span></b><button type="button" className="ps-a" onClick={() => nav('/a/safety')}>전체보기 ›</button></div>
              {rps === undefined ? <Loading /> : openRps.length ? openRps.slice(0, 3).map((r) => (
                <button key={r.id} type="button" className="hrow" onClick={() => nav(`/a/safety?id=${r.id}`)}>
                  <span className="hr-t">{r.type}<small>{r.targetLabel || r.targetType} · {day(r.createdAt)}</small></span>
                  <span className="stag" style={{ background: '#FDECE6', color: '#C25E48' }}>{r.status === 'REVIEWING' ? '검토 중' : '접수'}</span>
                </button>
              )) : <div className="pempty">새 신고가 없어요</div>}
            </div>
            <div className="psec">
              <div className="ps-h"><b>새 기관 신청 <span className="cnt">{newOrgs.length}</span></b><button type="button" className="ps-a" onClick={() => nav('/a/approve?seg=org')}>승인 ›</button></div>
              {orgs === undefined ? <Loading /> : newOrgs.length ? newOrgs.slice(0, 3).map((o) => (
                <button key={o.id} type="button" className="hrow" onClick={() => nav(`/a/approve?seg=org&id=${o.id}`)}>
                  <Avc n={o.name} cat="acc" />
                  <span className="hr-t">{o.name}<small>{[o.orgType, day(o.createdAt)].filter(Boolean).join(' · ')}</small></span>
                  <span className="stag" style={{ background: '#FFF4DC', color: '#A6760C' }}>대기</span>
                </button>
              )) : <div className="pempty">새 기관 신청이 없어요</div>}
            </div>
            <div className="psec">
              <div className="ps-h"><b>미답변 문의 <span className="cnt">{openInq.length}</span></b><button type="button" className="ps-a" onClick={() => nav('/a/inquiries')}>문의 ›</button></div>
              {inq === undefined ? <Loading /> : openInq.length ? openInq.slice(0, 3).map((q) => (
                <button key={q.id} type="button" className="hrow" onClick={() => nav(`/a/inquiries?id=${q.id}`)}>
                  <Avc n="문" cat="acc" />
                  <span className="hr-t">{q.title}<small>{q.type} · {day(q.createdAt)}</small></span><span className="hr-d">›</span>
                </button>
              )) : <div className="pempty">답변할 문의가 없어요</div>}
            </div>
          </div>
        </div>

        <div className="pnl">
          {/* 5판 "이번 주 운영 일정"은 자료가 없어 카테고리 건의 · 담당자 계정 신청으로 대신했다 */}
          <div className="ps-h"><b>카테고리 건의 <span className="cnt">{sugWait.length}</span></b><button type="button" className="ps-a" onClick={() => nav('/a/suggest')}>건의 ›</button></div>
          {sug === undefined ? <Loading /> : sugWait.length ? sugWait.slice(0, 3).map((g) => (
            <button key={g.key} type="button" className="srow" onClick={() => nav('/a/suggest')}><span className="s-tm" style={{ background: '#FFF4DC', color: '#A6760C' }}>{g.count}건</span><span className="s-t">{g.name}</span><span className="s-v">{(SUG_ST[g.status] || SUG_ST.RECEIVED)[1]}</span></button>
          )) : <div className="pempty" style={{ padding: '12px 0' }}>답변을 기다리는 건의가 없어요</div>}
          <div className="ps-h" style={{ marginTop: 16 }}><b>담당자 계정 신청</b><button type="button" className="ps-a" onClick={() => nav('/a/approve?seg=mgr')}>내역 ›</button></div>
          {apps === undefined ? <Loading /> : (apps || []).length ? apps.slice(0, 3).map((a, i) => {
            const st = APP_ST[a.status] || APP_ST.APPROVED;
            return <button key={a.id || i} type="button" className="srow" onClick={() => nav('/a/approve?seg=mgr')}><span className="s-tm" style={st[0] === 'g' ? { background: '#E2F3EC', color: '#2F8069' } : st[0] === 'a' ? { background: '#FFF4DC', color: '#A6760C' } : { background: '#FDECE6', color: '#C25E48' }}>{st[1]}</span><span className="s-t">{a.name || a.managerName || (a.user && a.user.name) || '담당자'}</span><span className="s-v">{a.orgName || (a.org && a.org.name) || ''}</span></button>;
          }) : <div className="pempty" style={{ padding: '12px 0' }}>신청 내역이 없어요</div>}
          <div className="ps-h" style={{ marginTop: 18 }}><b>지역별 수요·공급</b><button type="button" className="ps-a" onClick={() => nav('/a/stats?tab=area')}>리포트 ›</button></div>
          {need === undefined || offer === undefined ? <Loading /> : reg.length ? reg.map(([a, [dm, sp]]) => {
            const ratio = sp ? dm / sp : null;
            return (
              <div key={a} className="rgrow"><b>{a}</b>
                <div className="rg-bars"><i title={`${a} 도움 요청 ${dm}건`} style={{ width: `${(dm / regMax) * 100}%`, background: C_NEED }} /><i title={`${a} 봉사 가능 ${sp}건`} style={{ width: `${(sp / regMax) * 100}%`, background: C_OFFER }} /></div>
                <span className={ratio == null || ratio > 1.1 ? 'rg-w' : ''}>{ratio == null ? (dm ? '가능 0' : '-') : `${ratio.toFixed(2)}배`}</span>
              </div>
            );
          }) : <div className="pempty" style={{ padding: '12px 0' }}>아직 공고가 없어요</div>}
          <div className="rg-lg"><span><i style={{ background: C_NEED }} />도움 요청</span><span><i style={{ background: C_OFFER }} />봉사 가능</span><span>1.1배 넘으면 부족{need ? ` · 최근 ${need.periodMonths}개월 공고` : ''}</span></div>
        </div>
      </div>

      <div className="hb-grid2">
        <div className="pnl">
          <div className="ps-h"><b>주별 수요·공급</b><span className="mu">도움 요청 vs 봉사 가능 공고 · 최근 {weekly.length}주</span></div>
          {weekly.length ? <LineChart5 label="주별 도움 요청과 봉사 가능 공고 수" x={weekly.map((w) => `${md(w.from)}~${md(w.to)}`)} series={[{ l: '요청', c: C_NEED, d: weekly.map((w) => Number(w.demand) || 0) }, { l: '봉사', c: C_OFFER, d: weekly.map((w) => Number(w.supply) || 0) }]} /> : <div className="pempty">아직 데이터가 없어요</div>}
          <div className="mst">
            <div><span>이번 주 요청</span><b>{wk.demand ?? 0}건</b><em>{diff(wk.demand, pw.demand)}</em></div>
            <div><span>봉사 가능</span><b>{wk.supply ?? 0}건</b><em>{diff(wk.supply, pw.supply)}</em></div>
            <div><span>{gap > 0 ? '부족' : '여유'}</span><b>{Math.abs(gap)}건</b><em>요청 − 봉사 가능</em></div>
            <div><span>이번 달 공고</span><b>{x.demand ?? 0} / {x.supply ?? 0}</b><em>요청 / 봉사 가능</em></div>
          </div>
          <div className="ps-h" style={{ marginTop: 20 }}><b>지역별 매칭 성공률</b><span className="mu">최근 90일</span></div>
          {rates.length ? rates.map((a) => <HBar key={a.area} label={a.area} segs={[[Number(a.rate) || 0, C_NEED]]} max={100} right={`${a.rate}%`} />) : <div className="pempty">아직 데이터가 없어요</div>}
          <div className="mu" style={{ marginTop: 8 }}>요청·제안 중 일정 확정까지 간 비율이에요.</div>
          <div className="ps-h" style={{ marginTop: 20 }}><b>시간 균형</b><span className="mu">이번 달</span></div>
          <div className="mst c3"><div><span>적립(발행)</span><b>{fmt(tb.issued)}시간</b></div><div><span>사용(소비)</span><b>{fmt(tb.consumed)}시간</b></div><div><span>기관 대상 무차감</span><b>{fmt(tb.elderFree)}시간</b></div></div>
          <div className="bar5" style={{ marginTop: 14, height: 12 }}><i style={{ width: `${Math.min(100, used)}%`, background: 'var(--acc)' }} /></div>
          <div className="prw mu" style={{ marginTop: 6, justifyContent: 'space-between' }}><span>사용 {used}%</span><span>남은 발행 {100 - Math.min(100, used)}%</span></div>
          <div className="mu" style={{ marginTop: 8 }}>기관 대상 도움은 받는 쪽 차감이 없어서 쌓이는 시간이 더 많아요. 총량을 계속 지켜봐야 해요.</div>
        </div>
        <div className="pnl">
          <div className="ps-h"><b>카테고리별 수요·공급</b><button type="button" className="ps-a" onClick={() => nav('/a/stats?tab=sd')}>리포트 ›</button></div>
          {an === undefined ? <Loading /> : cats.length ? (
            <div className="opgrid">{cats.map((c) => {
              const [a, , bg] = dc5(c.category), g = Number(c.gap) || 0;
              return (
                <button key={c.category} type="button" className="opc" style={{ background: bg }} onClick={() => nav('/a/stats?tab=sd')}>
                  <div className="opc-d">{c.category}</div>
                  <div className="opc-t">{c.demand}<small> 건 요청</small></div>
                  <div className="opc-k">봉사 가능 {c.supply}건 · <b style={{ color: a }}>{g > 0 ? `${g}건 부족` : '부족 없음'}</b></div>
                  <div className="opc-bar"><i style={{ width: `${((Number(c.demand) || 0) / catMax) * 100}%`, background: a }} /></div>
                </button>
              );
            })}</div>
          ) : <div className="pempty">아직 데이터가 없어요</div>}
          {an && <div className="mu" style={{ margin: '2px 0 10px' }}>최근 {an.supplyDemandDays}일 · 수요 = 봉사 요청 공고 + 받는 쪽 요청, 공급 = 도움 가능 공고 + 수락된 건</div>}
          <div className="mob"><M.InsightCard /></div>
        </div>
      </div>

      <div className="pnl wide">
          <div className="ps-h"><b>운영 도구</b></div>
          <div className="tools4">
          <button type="button" className="li click" onClick={() => nav('/a/persona')}><span className="li-i"><PIc k="heart" /></span><span className="li-t">첫 활동 연습 · 가상 상대 관리<small>AI 역할극 상대와 상황을 고쳐요</small></span><span className="ar">›</span></button>
          <button type="button" className="li click" onClick={() => nav('/a/sim')}><span className="li-i"><PIc k="fore" /></span><span className="li-t">정책 시뮬레이션<small>조건을 말로 바꿔 전후 비교</small></span><span className="ar">›</span></button>
          <button type="button" className="li click" onClick={() => nav('/a/manage')}><span className="li-i"><PIc k="gear" /></span><span className="li-t">운영자 계정<small>전체 관리 메뉴 · 회원 탈퇴</small></span><span className="ar">›</span></button>
          <button type="button" className="li click" disabled={busy} onClick={() => run(async () => { const r = await post('/admin/scheduler/run', {}); toast(r && r.message ? r.message : '만료·자동 확정·노쇼·마감·리마인드를 한 번 실행했어요'); reload(); })}><span className="li-i"><PIc k="redo" /></span><span className="li-t">스케줄러 지금 실행 (시연)<small>만료·자동 확정·노쇼·마감·리마인드</small></span><span className="ar">›</span></button>
          </div>
      </div>
    </Screen>
  );
}
export function ADash() { return usePc() ? <ADashPc /> : <M.ADash />; }

/* ====================== A-02 이용 리포트 ====================== */
const UT = { GIVE: ['주로 봉사', C_GIVE], BOTH: ['주고받음', '#E3B25A'], GET: ['주로 받음', C_RECV], NONE: ['활동 없음', '#B4BCC8'] };
function AiBox({ title, x2 }) {
  if (!x2) return null;
  return <div className="ai"><div className="ai-h"><Spk />{title}</div><div>{x2.text}</div><div className="mu" style={{ marginTop: 6 }}>숫자는 시스템 집계, 풀이는 {x2.source === 'ai' ? 'AI' : '규칙'} 문장이에요</div><RuleNote source={x2.source} /></div>;
}
function AStatsPc() {
  const [sp, setSp] = useSearchParams();
  const tab = sp.get('tab') || 'type';
  const type = sp.get('type') === 'OFFER' ? 'OFFER' : 'NEED';
  const go = (k, v) => { const n = new URLSearchParams(sp); n.set(k, v); setSp(n, { replace: true }); };
  const [t] = useLoad(() => api('/admin/stats/types'), []);
  const [s] = useLoad(() => api('/admin/stats/season'), []);
  const [d] = useLoad(() => api(`/admin/stats/by-type?type=${type}`), [type]);
  const [an] = useLoad(quiet('/admin/analytics'), []);
  const color = type === 'NEED' ? C_NEED : C_OFFER;
  const cats = ((t && t.categories) || []).filter((c) => c.count > 0);
  const pmax = Math.max(1, ...cats.map((c) => (Number(c.givers) || 0) + (Number(c.receivers) || 0)));
  const mkeys = (m) => Object.keys((((m || [])[0]) || {}).counts || {});
  const CatLg = ({ keys }) => <div className="lg5 wrap">{keys.map((k) => <span key={k}><i style={{ background: (dc5(k) || [])[0] }} />{short(k)}</span>)}</div>;
  const ns = s && s.nextSeason;
  const sd = (an && an.supplyDemand) || [];
  const sdMax = Math.max(1, ...sd.flatMap((c) => [Number(c.demand) || 0, Number(c.supply) || 0]));
  const byType = tab === 'age' || tab === 'season' || tab === 'area';
  return (
    <Screen title="이용 리포트" back={false} wide>
      <div className="prw" style={{ marginBottom: 16, flexWrap: 'wrap' }}>
        <div className="cseg" role="tablist">{[['type', '봉사 유형'], ['age', '나이별 인기'], ['season', '계절별'], ['area', '지역별'], ['sd', '수요·공급']].map(([k, l]) => <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => go('tab', k)}>{l}</button>)}</div>
        <span className="sp" />
        {byType && <span className="mu">{d ? `최근 ${d.periodMonths}개월 공고 ${d.postCount}건 기준` : ''}</span>}
        {byType && <div className="cseg" role="group" aria-label="공고 종류" style={{ marginRight: 0 }}>{[['NEED', '도움 요청'], ['OFFER', '봉사 가능']].map(([k, l]) => <button key={k} type="button" aria-pressed={type === k} className={type === k ? 'on' : ''} onClick={() => go('type', k)}>{l}</button>)}</div>}
      </div>

      {tab === 'type' && (t === undefined ? <Loading /> : !t ? <div className="pnl"><div className="pempty">불러오지 못했어요</div></div> : (
        <div className="dcol">
          <div className="g2">
            <div className="pnl">
              <div className="ps-h"><b>종류별 주는 쪽 · 받는 쪽</b><Lg items={[[C_GIVE, '주는 사람'], [C_RECV, '받는 사람']]} /></div>
              {cats.length ? cats.map((c) => <HBar key={c.category} label={short(c.category)} segs={[[c.givers, C_GIVE], [c.receivers, C_RECV]]} max={pmax} right={`${c.givers}/${c.receivers}명`} />) : <div className="pempty">최근 12개월에 끝난 활동이 없어요</div>}
              {cats.length > 0 && <table className="tbl sm plain" style={{ marginTop: 12 }}><thead><tr><th>종류</th><th>활동</th><th>시간</th><th>주는 사람</th><th>받는 사람</th></tr></thead><tbody>{cats.map((c) => <tr key={c.category}><td><b>{short(c.category)}</b></td><td>{c.count}건</td><td>{c.hours}시간</td><td>{c.givers}명 ({c.giverPct}%)</td><td>{c.receivers}명 ({c.receiverPct}%)</td></tr>)}</tbody></table>}
              <div className="mu" style={{ marginTop: 8 }}>최근 {t.periodMonths}개월에 끝난 활동 {t.doneCount}건 · 사람 수는 겹치지 않게 셌어요</div>
            </div>
            <div className="pnl">
              <div className="ps-h"><b>사용자 유형 분포</b></div>
              {(t.userTypes || []).map((u) => <HBar key={u.key} label={(UT[u.key] || [u.label])[0]} segs={[[u.pct, (UT[u.key] || [0, C_NEED])[1]]]} max={100} right={`${u.count}명 · ${u.pct}%`} />)}
              <div className="mu" style={{ marginTop: 8 }}>회원 {t.members}명 · 기관 관리 대상(도움만 받음) {t.elders}명</div>
            </div>
          </div>
          <AiBox title="AI 해석" x2={t.interpretation} />
        </div>
      ))}

      {tab === 'age' && (
        <div className="g2">
          <div className="pnl">
            <div className="ps-h"><b>나이대별 인기 · {type === 'NEED' ? '도움 요청' : '봉사 가능'} 공고</b></div>
            {d === undefined ? <Loading /> : (d && (d.byAge || []).length) ? (
              <Tbl plain head={['나이대', '1위', '2위', '3위', '공고']} rows={d.byAge.map((a) => ({ key: a.ageGroup, cells: [<b key="a">{a.ageGroup}</b>, ...[0, 1, 2].map((i) => (a.top && a.top[i] ? `${short(a.top[i].category)} (${a.top[i].count})` : '-')), `${a.total}건`] }))} />
            ) : <div className="pempty">나이대를 알 수 있는 공고가 아직 없어요</div>}
            <div className="mu" style={{ marginTop: 8 }}>나이대는 공고를 올린 사람(기관 대리 공고는 그 관리 대상) 기준이에요</div>
          </div>
          <div className="pnl mob">{an === undefined ? <Loading /> : an ? <M.AgeCard a={an} /> : <div className="pempty">불러오지 못했어요</div>}<div className="mu" style={{ marginTop: 8 }}>끝난 활동 기준이에요(위 토글과 상관없어요).</div></div>
        </div>
      )}

      {tab === 'season' && (
        <div className="dcol">
          <div className="g2">
            <div className="pnl">
              <div className="ps-h"><b>월별 {type === 'NEED' ? '도움 요청' : '봉사 가능'} 공고</b><span className="mu">{d ? `최근 ${d.periodMonths}개월` : ''}</span></div>
              {d === undefined ? <Loading /> : d ? <>{R.monthBars(d.monthly || [])}<CatLg keys={mkeys(d.monthly)} /><table className="tbl sm plain" style={{ marginTop: 10 }}><thead><tr><th>계절</th><th>공고</th><th>많은 도움</th></tr></thead><tbody>{(d.seasons || []).map((q) => <tr key={q.season}><td><b>{q.season}</b></td><td>{q.total}건</td><td>{tops(q.top)}</td></tr>)}</tbody></table></> : <div className="pempty">불러오지 못했어요</div>}
            </div>
            <div className="pnl">
              <div className="ps-h"><b>{ns ? `다음 계절(${ns.season}) 예상 수요` : '다음 계절 예상 수요'}</b>{ns && <Tg k="a">약 {ns.total}건</Tg>}</div>
              {s === undefined ? <Loading /> : ns && ns.total > 0 ? ns.categories.map((c) => <HBar key={c.category} label={short(c.category)} segs={[[c.expected, C_3]]} max={Math.max(1, ...ns.categories.map((q) => Number(q.expected) || 0))} right={`${c.expected}건`} />) : <div className="pempty">수요 기록이 적어 예상을 내기 어려워요</div>}
              {ns && <div className="mu" style={{ marginTop: 8 }}>{ns.basis} × 최근 석 달 추세 {ns.growth}배(최근 90일 {ns.recent90}건 ÷ 그 앞 90일 {ns.prev90}건, 0.5~2배로 자름) · 시스템 계산</div>}
              {s && <div style={{ marginTop: 14 }}><AiBox title="AI 해석" x2={s.interpretation} /></div>}
            </div>
          </div>
          <div className="g2">
            <div className="pnl">
              <div className="ps-h"><b>월별 도움 요청</b><span className="mu">{s ? `최근 ${s.periodMonths}개월 · 요청 기록` : ''}</span></div>
              {s === undefined ? <Loading /> : s ? <>{R.monthBars(s.monthly || [])}<CatLg keys={mkeys(s.monthly)} /><div className="mu" style={{ marginTop: 8 }}>{(s.seasons || []).map((q) => `${q.season} 요청 ${q.demand} · 가능 ${q.supply}`).join(' / ')}</div></> : <div className="pempty">불러오지 못했어요</div>}
            </div>
            <div className="pnl">
              <div className="ps-h"><b>월(계절)별 인기 봉사</b><span className="mu">끝난 활동</span></div>
              {an === undefined ? <Loading /> : an ? <>{M.stackBars(an.monthlyCategory || [])}<CatLg keys={mkeys(an.monthlyCategory)} /><div className="mu" style={{ marginTop: 8 }}>{(an.seasons || []).filter((q) => q.total > 0).map((q) => `${q.season}: ${(q.top || []).map((c) => short(c.category)).join('·')}`).join(' / ')}</div></> : <div className="pempty">불러오지 못했어요</div>}
            </div>
          </div>
        </div>
      )}

      {tab === 'area' && (
        <div className="g2">
          <div className="pnl">
            <div className="ps-h"><b>지역별 {type === 'NEED' ? '도움 요청' : '봉사 가능'} 공고</b></div>
            {d === undefined ? <Loading /> : d && (d.byArea || []).length ? (
              <>
                {d.byArea.map((a) => <HBar key={a.area} label={a.area} segs={[[a.total, color]]} max={Math.max(1, ...d.byArea.map((q) => Number(q.total) || 0))} right={`${a.total}건`} />)}
                <table className="tbl sm plain" style={{ marginTop: 12 }}><thead><tr><th>지역</th><th>공고</th><th>많은 도움</th></tr></thead><tbody>{d.byArea.map((a) => <tr key={a.area}><td><b>{a.area}</b></td><td>{a.total}건</td><td>{tops(a.top)}</td></tr>)}</tbody></table>
              </>
            ) : <div className="pempty">아직 공고가 없어요</div>}
          </div>
          <div className="pnl mob">{an === undefined ? <Loading /> : an ? <M.AreaShortCard a={an} color={C_4} /> : <div className="pempty">불러오지 못했어요</div>}</div>
        </div>
      )}

      {tab === 'sd' && (
        <div className="g2">
          <div className="pnl">
            <div className="ps-h"><b>카테고리별 수요 vs 공급</b><Lg items={[[C_NEED, '수요'], [C_OFFER, '공급']]} /></div>
            {an === undefined ? <Loading /> : sd.length ? sd.map((c) => (
              <div key={c.category} className="rgrow"><b style={{ width: 72 }}>{short(c.category)}</b>
                <div className="rg-bars"><i title={`수요 ${c.demand}`} style={{ width: `${((Number(c.demand) || 0) / sdMax) * 100}%`, background: C_NEED }} /><i title={`공급 ${c.supply}`} style={{ width: `${((Number(c.supply) || 0) / sdMax) * 100}%`, background: C_OFFER }} /></div>
                <span style={{ width: 96 }} className={c.gap > 0 ? 'rg-w' : ''}>{c.demand} / {c.supply}{c.gap > 0 ? ` · −${c.gap}` : ''}</span>
              </div>
            )) : <div className="pempty">아직 데이터가 없어요</div>}
            {an && <div className="mu" style={{ marginTop: 8 }}>{sd.filter((c) => c.gap > 0).map((c) => `${short(c.category)} ${c.gap}건 부족`).join(' · ') || '부족한 카테고리가 없어요'} · 최근 {an.supplyDemandDays}일 · 수요 = 봉사 요청 공고 + 받는 쪽 요청, 공급 = 도움 가능 공고 + 수락된 건</div>}
          </div>
          <div className="pnl mob">{an === undefined ? <Loading /> : an ? <M.SeasonCard a={an} c1={C_NEED} c2={C_OFFER} t1={C_NEED} /> : <div className="pempty">불러오지 못했어요</div>}</div>
        </div>
      )}
    </Screen>
  );
}
export function AStats() { return usePc() ? <AStatsPc /> : <R.AStats />; }

/* ====================== A-06 승인: 탭 + 표 + 오른쪽 상세 ====================== */
const ORG_ST = { PENDING: ['a', '대기'], APPROVED: ['g', '승인'], REJECTED: ['r', '반려'] };
function AApprovePc() {
  const nav = useNavigate();
  const [sp, setSp] = useSearchParams();
  const seg = sp.get('seg') || 'org';
  const { toast, sheet, closeSheet, confirm } = useUi();
  const [orgs, reloadO] = useLoad(() => api('/admin/orgs'), []);
  const [vers, reloadV] = useLoad(() => api('/admin/verifications'), []);
  const [aps, reloadA] = useLoad(() => api('/admin/appeals'), []);
  const [apps, reloadP] = useLoad(quiet('/admin/manager-applications', appList), []);
  const [mgrs, reloadM] = useLoad(quiet('/admin/managers'), []);
  const [busy, run] = useBusy();
  const [pick, setPick] = useState(sp.get('id') ? `${seg}:${sp.get('id')}` : null);
  const ol = (orgs || []).slice().sort((a, b) => (a.status === 'PENDING' ? 0 : 1) - (b.status === 'PENDING' ? 0 : 1));
  const po = ol.filter((o) => o.status === 'PENDING');
  const pv = Array.isArray(vers) ? vers : vers ? [...(vers.id || vers.ids || []).map((v) => ({ ...v, kind: 'id' })), ...(vers.crime || vers.crimes || []).map((v) => ({ ...v, kind: 'crime' }))] : [];
  const pa = (aps || []).filter((a) => a.status === 'RECEIVED');
  const pl = apps || [];
  const keyOf = { org: (o) => `org:${o.id}`, mgr: (a, i) => `mgr:${a.id || i}`, vol: (v) => `vol:${v.kind}-${v.userId || v.id}`, diff: (a) => `diff:${a.id}` };
  const list = { org: ol, mgr: pl, vol: pv, diff: pa }[seg] || [];
  const idx = Math.max(0, list.findIndex((r, i) => keyOf[seg](r, i) === pick));
  const cur = list[idx];
  const curKey = cur ? keyOf[seg](cur, idx) : null;
  const tab = (k) => { setPick(null); setSp({ seg: k }, { replace: true }); };
  const mgrOf = (a) => { const uid = a.userId || a.managerId || (a.user && a.user.id); const m = (mgrs || []).find((q) => q.id === uid); const sus = (m && m.status === 'SUSPENDED') || ['SUSPENDED', 'CANCELLED', 'REVOKED'].includes(a.status) || a.userStatus === 'SUSPENDED'; return { uid, m, sus, st: APP_ST[sus ? 'SUSPENDED' : a.status] || APP_ST.APPROVED, name: a.name || a.managerName || (a.user && a.user.name) || (m && m.name) || '담당자', org: a.orgName || (a.org && a.org.name) || (m && m.orgName) || '' }; };
  const loading = { org: orgs, mgr: apps, vol: vers, diff: aps }[seg] === undefined;
  return (
    <Screen title="승인" back={false} wide>
      <div className="prw" style={{ marginBottom: 16 }}>
        <div className="cseg" role="tablist">{[['org', '기관 신청', po.length], ['mgr', '담당자 계정', null], ['vol', '봉사자 신원', pv.length], ['diff', '난이도 이의', pa.length]].map(([k, t, n]) => <button key={k} type="button" role="tab" aria-selected={seg === k} className={seg === k ? 'on' : ''} onClick={() => tab(k)}>{t}{n != null ? ` ${n}` : ''}</button>)}</div>
        <span className="sp" />
        {seg === 'mgr' && <button type="button" className="pbtn" onClick={() => nav('/a/managers')}>담당자 계정 관리 ›</button>}
      </div>
      <div className="g21a">
        <div className="pnl">
          {loading ? <Loading /> : seg === 'org' ? (
            <Tbl head={['기관명', '종류', '증빙', '신청일', '상태']} sel={curKey} onSel={setPick} empty="기관 신청이 없어요"
              rows={ol.map((o) => ({ key: keyOf.org(o), cells: [<b key="n">{o.name}</b>, o.orgType, o.proofFileUrl ? '재직증명서' : '없음', day(o.createdAt), <Tg key="s" k={(ORG_ST[o.status] || ['k'])[0]}>{(ORG_ST[o.status] || [0, o.status])[1]}</Tg>] }))} />
          ) : seg === 'mgr' ? (
            <>
              <Tbl head={['이름', '소속 기관', '본인 확인', '신청일', '상태']} sel={curKey} onSel={setPick} empty="신청·승인 내역이 없어요"
                rows={pl.map((a, i) => { const q = mgrOf(a); return { key: keyOf.mgr(a, i), cells: [<b key="n">{q.name}</b>, q.org, a.phoneVerified === false ? '안 됨' : '확인', day(a.createdAt || a.appliedAt), <Tg key="s" k={q.st[0]}>{q.st[1]}</Tg>] }; })} />
              <div className="notebox" style={{ marginTop: 12 }}>승인된 기관 소속이고 본인 확인이 끝난 담당자 계정 신청은 자동으로 승인돼요. 여기서 내역을 보고, 문제가 있으면 계정을 정지할 수 있어요.</div>
            </>
          ) : seg === 'vol' ? (
            <Tbl head={['이름', '제출 서류', '아이디', '지역 · 나이대', '상태']} sel={curKey} onSel={setPick} empty="처리할 항목이 없어요"
              rows={pv.map((v) => ({ key: keyOf.vol(v), cells: [<b key="n">{v.name || v.userName}</b>, M.VKIND[v.kind] || v.kind, v.loginId || '', [v.area, v.ageGroup].filter(Boolean).join(' · '), <Tg key="s" k="a">대기</Tg>] }))} />
          ) : (
            <Tbl head={['봉사자', '활동', '등급', '신청일', '상태']} sel={curKey} onSel={setPick} empty="처리할 항목이 없어요"
              rows={pa.map((a) => ({ key: keyOf.diff(a), cells: [<b key="n">{(a.user && a.user.name) || '봉사자'}</b>, a.content || `요청 #${a.requestId}`, `${lvName(a.currentLevel)} → ${lvName(a.requestedLevel)}`, day(a.createdAt), <Tg key="s" k="a">대기</Tg>] }))} />
          )}
        </div>
        <div className="pnl sticky">
          {!cur ? <div className="pempty">왼쪽에서 항목을 골라 주세요</div> : seg === 'org' ? (
            <>
              <div className="d-t">{cur.name} <Tg k={(ORG_ST[cur.status] || ['k'])[0]}>{(ORG_ST[cur.status] || [0, cur.status])[1]}</Tg></div>
              <div className="mu">{cur.orgType} · {day(cur.createdAt)} 신청</div>
              <div style={{ marginTop: 10 }}>
                {cur.address && <Kv k="주소">{cur.address}</Kv>}
                <Kv k="담당자">{cur.managerName}</Kv>
                <Kv k="연락처">{cur.managerPhone || cur.phone || '-'}</Kv>
                {cur.memo && <Kv k="메모">{cur.memo}</Kv>}
              </div>
              <div className="proof">{cur.proofFileUrl ? <button type="button" className="lk" onClick={() => openFile(cur.proofFileUrl)}>재직증명서 열어 보기 ↗</button> : <span>증빙 없음 (41판 이전 신청)</span>}</div>
              {cur.status === 'PENDING' ? (
                <div className="acts5">
                  <button type="button" className="pbtn lg" disabled={busy} onClick={() => run(async () => { await post(`/admin/orgs/${cur.id}/reject`); reloadO(); toast(`${cur.name}: 반려`); })}>반려</button>
                  <button type="button" className="pbtn pri lg" disabled={busy} onClick={() => run(async () => {
                    const r = await post(`/admin/orgs/${cur.id}/approve`);
                    reloadO();
                    sheet(() => <><h2>담당자 계정을 발급했어요</h2><div className="sum"><div className="between"><span className="muted">기관</span><b>{cur.name}</b></div><div className="between"><span className="muted">아이디</span><b>{r.managerLoginId}</b></div><div className="between"><span className="muted">임시 비밀번호</span><b>{r.tempPassword}</b></div></div><div className="mute2" style={{ marginTop: 8 }}>기관 전화로 안내하고, 첫 로그인 뒤 비밀번호를 바꾸게 해 주세요.</div><button className="btn" style={{ marginTop: 14 }} onClick={closeSheet}>확인</button></>);
                  })}>승인 · 계정 발급</button>
                </div>
              ) : <div className="notebox" style={{ marginTop: 12 }}>{(ORG_ST[cur.status] || [0, cur.status])[1]} 처리한 신청이에요</div>}
            </>
          ) : seg === 'mgr' ? (() => {
            const q = mgrOf(cur);
            return (
              <>
                <div className="d-t">{q.name} <Tg k={q.st[0]}>{q.st[1]}</Tg></div>
                <div className="mu">{q.org}{cur.loginId ? ` · ${cur.loginId}` : ''}</div>
                <div style={{ marginTop: 10 }}>
                  <Kv k="기관 승인">{cur.orgApproved === false ? '안 됨' : '확인'}</Kv>
                  <Kv k="본인 확인">{cur.phoneVerified === false ? '안 됨' : '확인'}</Kv>
                  <Kv k="신청일">{day(cur.createdAt || cur.appliedAt) || '-'}</Kv>
                </div>
                {cur.existingManagers > 0 && <div className="warnbox" style={{ marginTop: 10 }}>신청할 때 이 기관에 이미 담당자가 {cur.existingManagers}명 있었어요</div>}
                <div className="proof">{cur.proofFileUrl ? <button type="button" className="lk" onClick={() => openFile(cur.proofFileUrl)}>재직 증빙 열기 ↗</button> : <span>재직 증빙 파일 없음</span>}</div>
                {q.uid && !q.sus && <div className="acts5"><button type="button" className="pbtn lg" disabled={busy} onClick={async () => { if (await confirm('이 담당자 계정을 정지할까요?', '정지하면 로그인할 수 없어요. 담당자 계정 화면에서 다시 풀 수 있어요.', '정지', true)) run(async () => { if (cur.id) await post(`/admin/manager-applications/${cur.id}/revoke`, { reason: '운영자 승인 취소' }); else await post(`/admin/managers/${q.uid}/suspend`); toast('승인을 취소하고 계정을 정지했어요'); reloadP(); reloadM(); }); }}>승인 취소(계정 정지)</button></div>}
              </>
            );
          })() : seg === 'vol' ? (() => {
            const uid = cur.userId || cur.id;
            return (
              <>
                <div className="d-t">{cur.name || cur.userName} <Tg k="a">{M.VKIND[cur.kind] || cur.kind}</Tg></div>
                <div className="mu">{[cur.loginId, cur.area, cur.ageGroup].filter(Boolean).join(' · ')}</div>
                <div className="proof">{cur.fileUrl ? <button type="button" className="lk" onClick={() => openFile(cur.fileUrl)}>제출 파일 보기 ↗</button> : <span>제출 파일 없음</span>}</div>
                <div className="acts5">
                  <button type="button" className="pbtn lg" disabled={busy} onClick={() => run(async () => { await post(`/admin/verifications/${uid}/${cur.kind}/reject`); reloadV(); toast('반려했어요'); })}>반려</button>
                  <button type="button" className="pbtn pri lg" disabled={busy} onClick={() => run(async () => { await post(`/admin/verifications/${uid}/${cur.kind}/approve`); reloadV(); toast('승인했어요'); })}>승인</button>
                </div>
              </>
            );
          })() : (
            <>
              <div className="d-t">{(cur.user && cur.user.name) || '봉사자'}</div>
              <div className="mu">{cur.content || `요청 #${cur.requestId}`}{cur.recipient ? ` · ${cur.recipient}` : ''}{cur.hours ? ` · ${cur.hours}시간` : ''} · {day(cur.createdAt)}</div>
              <div style={{ marginTop: 10 }}><Kv k="등급">{lvName(cur.currentLevel)} → {lvName(cur.requestedLevel)} 요청</Kv></div>
              <div className="notebox" style={{ marginTop: 10 }}>“{cur.reason}”</div>
              <div className="mob"><JudgeAssist key={cur.id} kind="APPEAL" id={cur.id} /></div>
              <div className="acts5">
                <button type="button" className="pbtn lg" disabled={busy} onClick={() => run(async () => { await post(`/admin/appeals/${cur.id}/answer`, { accept: false, resultLevel: cur.currentLevel, note: '' }); reloadA(); toast('반려했어요'); })}>반려</button>
                <button type="button" className="pbtn pri lg" disabled={busy} onClick={() => run(async () => { await post(`/admin/appeals/${cur.id}/answer`, { accept: true, resultLevel: cur.requestedLevel, note: '' }); reloadA(); toast(`${lvName(cur.requestedLevel)}로 조정했어요. 차액이 적립돼요`); })}>{lvName(cur.requestedLevel)}로 조정</button>
              </div>
            </>
          )}
        </div>
      </div>
    </Screen>
  );
}
export function AApprove() { return usePc() ? <AApprovePc /> : <M.AApprove />; }
export function AMgrApps() { return usePc() ? <Navigate to="/a/approve?seg=mgr" replace /> : <R.AMgrApps />; }

/* ====================== A-07 신고: 표 + 상세 ====================== */
const ACT_KO = Object.fromEntries(M.ACTS);
function ASafetyPc() {
  const [sp, setSp] = useSearchParams();
  const tab = sp.get('tab') || 'open';
  const { toast } = useUi();
  const [rps, reload] = useLoad(() => api('/admin/reports'), []);
  const [abs, reloadAb] = useLoad(() => api('/admin/anomalies'), []);
  const [busy, run] = useBusy();
  const [tri, reloadTri] = useTriage();
  const noteOf = useAnomalyNotes();
  const [pick, setPick] = useState(sp.get('id') ? Number(sp.get('id')) : null);
  const ord = (r) => (tri[r.id] ? tri[r.id].order : 9999);
  const open = (rps || []).filter((r) => r.status !== 'DONE' && !r.welfare).sort((a, b) => ord(a) - ord(b));
  const done = (rps || []).filter((r) => r.status === 'DONE' && !r.welfare);
  const wf = (rps || []).filter((r) => r.welfare);
  const al = abs || [];
  const list = { open, wf, done, ab: al }[tab] || [];
  const cur = tab === 'ab' ? al[Math.min(pick == null || pick >= al.length ? 0 : pick, al.length - 1)] : (list.find((r) => r.id === pick) || list[0]);
  const curKey = tab === 'ab' ? al.indexOf(cur) : cur && cur.id;
  const go = (k) => { setPick(null); setSp({ tab: k }, { replace: true }); };
  const stTag = (r) => (r.status === 'DONE' ? <Tg k="g">{r.welfare ? '확인함' : '처리 완료'}</Tg> : <Tg k="a">{r.welfare ? '확인 전' : r.status === 'REVIEWING' ? '검토 중' : '접수'}</Tg>);
  return (
    <Screen title="신고" back={false} wide>
      <div className="prw" style={{ marginBottom: 16 }}>
        <div className="cseg" role="tablist">{[['open', `신고 ${open.length}`], ['wf', `안부 확인 접수 ${wf.filter((r) => r.status !== 'DONE').length}`], ['ab', `이상 적립 ${al.length}`], ['done', `처리 완료 ${done.length}`]].map(([k, l]) => <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => go(k)}>{l}</button>)}</div>
        {tab === 'wf' && <span className="mu">제재 없음 · 기록 확인용</span>}
      </div>
      <div className="g21a">
        <div className="pnl">
          {(tab === 'ab' ? abs : rps) === undefined ? <Loading /> : tab === 'ab' ? (
            <Tbl head={['사유', '봉사자 ↔ 받는 사람', '횟수', '적립', '보류']} sel={curKey} onSel={setPick} empty="이상 패턴이 없어요"
              rows={al.map((q, i) => ({ key: i, cells: [<b key="r">{q.reason}</b>, `${q.volunteer ? q.volunteer.name : ''} ↔ ${q.recipient}`, `${q.count}회`, `${fmt(q.totalEarned)}시간`, (q.requests || []).filter((z) => z.held).length ? <Tg key="h" k="r">{(q.requests || []).filter((z) => z.held).length}건 보류</Tg> : '-'] }))} />
          ) : tab === 'wf' ? (
            <Tbl head={['대상', '접수한 사람', '담당자', '날짜', '상태']} sel={curKey} onSel={setPick} empty="안부 확인 접수가 없어요"
              rows={wf.map((r) => ({ key: r.id, cells: [<b key="t">{r.elder ? `${r.elder.displayName}${r.elder.dong ? ` · ${r.elder.dong}` : ''}` : r.targetLabel}</b>, r.reporter ? r.reporter.name : '', r.manager ? r.manager.name : '-', day(r.createdAt), stTag(r)] }))} />
          ) : tab === 'done' ? (
            <Tbl head={['사유', '대상', '조치', '날짜', '상태']} sel={curKey} onSel={setPick} empty="처리한 신고가 없어요"
              rows={done.map((r) => ({ key: r.id, cells: [<b key="t">{r.type}</b>, r.targetLabel || r.targetType, ACT_KO[r.action] || '', day(r.createdAt), stTag(r)] }))} />
          ) : (
            <Tbl head={['사유', '대상', '신고자', '날짜', '심각도', '상태']} sel={curKey} onSel={setPick} empty="새 신고가 없어요"
              rows={open.map((r) => ({ key: r.id, cells: [<b key="t">{r.type}</b>, r.targetLabel || r.targetType, r.reporter ? r.reporter.name : '', day(r.createdAt), <span key="s" className="mob"><SevChip s={tri[r.id] && tri[r.id].severity} /></span>, stTag(r)] }))} />
          )}
        </div>
        <div className="pnl sticky">
          {!cur ? <div className="pempty">왼쪽에서 항목을 골라 주세요</div> : tab === 'ab' ? (() => {
            const rqs = cur.requests || [], held = rqs.filter((q) => q.held).length;
            const all = (op) => run(async () => { for (const q of rqs) await post(`/admin/anomalies/${q.id}/${op}`); reloadAb(); toast(op === 'hold' ? '적립을 보류하고 재확인을 요청했어요' : '정상으로 표시했어요'); });
            return (
              <>
                <div className="d-t">{cur.reason}</div>
                <div className="mu">{cur.volunteer ? cur.volunteer.name : ''} ↔ {cur.recipient} · {cur.count}회 · 적립 {fmt(cur.totalEarned)}시간</div>
                <table className="tbl sm plain" style={{ marginTop: 10 }}><thead><tr><th>날짜</th><th>시간</th><th>상태</th></tr></thead><tbody>{rqs.map((q) => <tr key={q.id}><td>{q.date}</td><td>{q.hours}시간</td><td>{q.held ? <Tg k="r">보류</Tg> : '-'}</td></tr>)}</tbody></table>
                {held > 0 && <div className="warnbox" style={{ marginTop: 10 }}>{held}건 적립 보류 중</div>}
                <div className="mob"><AnomalyNote n={noteOf(cur)} /></div>
                <div className="acts5"><button type="button" className="pbtn lg" disabled={busy} onClick={() => all('release')}>정상</button><button type="button" className="pbtn pri lg" disabled={busy} onClick={() => all('hold')}>적립 보류</button></div>
              </>
            );
          })() : cur.status === 'DONE' ? (
            <>
              <div className="d-t">{cur.welfare ? (cur.elder ? cur.elder.displayName : cur.targetLabel) : cur.type} {stTag(cur)}</div>
              <div className="mu">{cur.targetLabel || cur.targetType} · 신고자 {cur.reporter ? cur.reporter.name : ''} · {dshort(cur.createdAt)}</div>
              {cur.content && <div className="notebox" style={{ marginTop: 10 }}>{cur.content}</div>}
              <div style={{ marginTop: 10 }}>{!cur.welfare && <Kv k="조치">{ACT_KO[cur.action] || '-'}</Kv>}<Kv k="처리 결과">{cur.result || '-'}</Kv></div>
            </>
          ) : (
            <div className="mob inl"><M.ReportHandle key={cur.id} r={cur} t={tri[cur.id]} onTriage={reloadTri} onDone={() => { reload(); reloadTri(); }} /></div>
          )}
        </div>
      </div>
    </Screen>
  );
}
export function ASafety() { return usePc() ? <ASafetyPc /> : <M.ASafety />; }

/* ====================== A-08 문의: 표 + 답변 ====================== */
function AInquiriesPc() {
  const [sp] = useSearchParams();
  const { toast } = useUi();
  const [busy, run] = useBusy();
  const [list, reload] = useLoad(() => api('/admin/inquiries'), []);
  const [pick, setPick] = useState(sp.get('id') ? Number(sp.get('id')) : null);
  const l = list || [];
  const cur = l.find((q) => q.id === pick) || l[0];
  const st = (q) => INQ_ST[q.stage || q.status] || INQ_ST.RECEIVED;
  return (
    <Screen title="문의" back={false} wide>
      <div className="g21a">
        <div className="pnl">
          {list === undefined ? <Loading /> : <Tbl head={['유형', '제목', '작성자', '날짜', '상태']} sel={cur && cur.id} onSel={setPick} empty="문의가 없어요"
            rows={l.map((q) => ({ key: q.id, cells: [q.type, <b key="t">{q.title}</b>, q.user ? q.user.name : '', day(q.createdAt), <Tg key="s" k={inqOpen(q) ? 'r' : 'g'}>{st(q)[1]}</Tg>] }))} />}
        </div>
        <div className="pnl sticky">
          {!cur ? <div className="pempty">왼쪽에서 문의를 골라 주세요</div> : (
            <>
              <div className="plabel">처리 상태</div>
              <div className="pchips wrap" role="group" aria-label="처리 상태" style={{ marginBottom: 14 }}>{Object.entries(INQ_ST).map(([k, [, t]]) => <button key={k} type="button" className={'pchip ' + ((cur.stage || cur.status) === k ? 'on' : '')} aria-pressed={(cur.stage || cur.status) === k} disabled={busy} onClick={() => (cur.stage || cur.status) !== k && run(async () => { await put(`/admin/inquiries/${cur.id}/status`, { status: k }); reload(); toast(`처리 상태를 '${t}'${ro(t).slice(t.length)} 바꿨어요`); })}>{t}</button>)}</div>
              <div className="mob inl"><M.AnswerSheet key={`${cur.id}-${cur.answer || ''}`} q={cur} onDone={reload} /></div>
            </>
          )}
        </div>
      </div>
    </Screen>
  );
}
export function AInquiries() { return usePc() ? <AInquiriesPc /> : <M.AInquiries />; }

/* ====================== A-09 공지사항: 왼쪽 표 · 오른쪽 새 공지 ====================== */
function ANoticesPc() {
  const nav = useNavigate();
  const { toast, confirm } = useUi();
  const [list, reload] = useLoad(() => api('/notices'), []);
  const [wx] = useLoad(quiet('/admin/weather/alerts'), []);
  const [edit, setEdit] = useState(null); // 고치는 공지(전체 내용)
  const [ver, setVer] = useState(0); // 저장 뒤 폼을 비우려고
  const waiting = ((wx && wx.alerts) || []).filter((a) => !a.sentAt);
  return (
    <Screen title="공지사항" back={false} wide>
      <div className="g21a">
        <div className="dcol">
          {waiting.length > 0 && (
            <div className="pnl">
              <div className="ps-h"><b>특보로 공지 쓰기</b><span className="mu">특보 알림에서 공지 게시와 담당자 알림을 함께 보내요</span></div>
              {waiting.map((a) => (
                <div key={a.id} className="li"><span className="li-i"><PIc k="snow" /></span><span className="li-t">{a.type} {a.level} · {(a.areas || []).join('·')} <Tg k="a">전달 전</Tg><small>{a.facts ? `${a.facts.from} ~ ${a.facts.to}` : ''}</small></span><button type="button" className="pbtn pri" onClick={() => nav('/a/weather')}>특보 알림에서 전달 ›</button></div>
              ))}
            </div>
          )}
          <div className="pnl">
            <div className="ps-h"><b>올린 공지 <span className="cnt">{(list || []).length}</span></b></div>
            {list === undefined ? <Loading /> : (
              <Tbl head={['제목', '날짜', '고정', '']} sel={edit && edit.id} empty="올린 공지가 없어요" plain
                rows={(list || []).map((n) => ({ key: n.id, cells: [
                  <b key="t">{n.title}</b>, day(n.createdAt), n.pinned ? <Tg k="a">고정</Tg> : '-',
                  <span key="a" className="prw" style={{ gap: 12, justifyContent: 'flex-end' }}>
                    <button type="button" className="lk" onClick={async () => { try { await post(`/admin/notices/${n.id}/pin`, { pinned: !n.pinned }); reload(); toast(n.pinned ? '고정을 풀었어요' : '맨 위에 고정했어요'); } catch { /* 토스트 */ } }}>{n.pinned ? '고정 해제' : '고정'}</button>
                    <button type="button" className="lk" onClick={async () => { try { setEdit(await api(`/notices/${n.id}`)); } catch { /* 토스트 */ } }}>수정</button>
                    <button type="button" className="lk" style={{ color: '#C25E48' }} onClick={async () => { if (await confirm('공지를 지울까요?', n.title, '삭제', true)) { try { await del(`/admin/notices/${n.id}`); if (edit && edit.id === n.id) setEdit(null); reload(); toast('지웠어요'); } catch { /* 토스트 */ } } }}>삭제</button>
                  </span>,
                ] }))} />
            )}
          </div>
        </div>
        <div className="pnl sticky">
          <div className="mob inl"><M.NoticeForm key={edit ? `e${edit.id}` : `n${ver}`} n={edit || undefined} onDone={() => { setEdit(null); setVer(ver + 1); reload(); }} /></div>
          {edit && <button type="button" className="pbtn" style={{ marginTop: 8, width: '100%' }} onClick={() => setEdit(null)}>수정 그만두고 새 공지 쓰기</button>}
        </div>
      </div>
    </Screen>
  );
}
export function ANotices() { return usePc() ? <ANoticesPc /> : <M.ANotices />; }

/* ====================== A-10 카테고리 건의: 표 + 답변 ====================== */
function ASuggestPc() {
  const [d, reload] = useLoad(() => api('/admin/category-suggestions'), []);
  const [pick, setPick] = useState(null);
  const groups = (d && d.groups) || [];
  const cur = groups.find((g) => g.key === pick) || groups[0];
  const st = (g) => SUG_ST[g.status] || SUG_ST.RECEIVED;
  return (
    <Screen title="카테고리 건의" back={false} wide>
      <div className="kpis" style={{ gridTemplateColumns: 'repeat(2,minmax(0,220px))' }}><div className="kpi"><span>전체 건의</span><b>{d ? d.total : 0}건</b></div><div className="kpi"><span>답변 기다리는 중</span><b>{d ? d.waiting : 0}건</b></div></div>
      <div className="g21a">
        <div className="pnl">
          {d === undefined ? <Loading /> : <Tbl head={['이름', '설명', '건의자', '같은 건의', '상태']} sel={cur && cur.key} onSel={setPick} empty="아직 건의가 없어요"
            rows={groups.map((g) => ({ key: g.key, cells: [<b key="n">{g.name}</b>, <span key="r" className="clip2">{(g.items[0] && g.items[0].reason) || ''}</span>, g.items[0] ? `${g.items[0].userRole === 'ORG' ? '담당자' : '사용자'} ${g.items[0].userName}` : '', `${g.count}건`, <Tg key="s" k={ST_K[st(g)[0]]}>{st(g)[1]}</Tg>] }))} />}
          <div className="notebox" style={{ marginTop: 12 }}>{(d && d.note) || "건의를 '반영'으로 답하면 공고 카테고리에 더해져요."} 승인할 때 등급(가중치 기본 1.0 · 수고 1.2)을 정해요.</div>
        </div>
        <div className="pnl sticky">
          {!cur ? <div className="pempty">왼쪽에서 건의를 골라 주세요</div> : (
            <>
              {cur.items.map((it) => <div key={it.id} className="li"><span className="li-t" style={{ fontWeight: 500 }}>{it.reason}<small>{day(it.createdAt)} · {it.userRole === 'ORG' ? '담당자' : '사용자'} {it.userName}</small></span></div>)}
              <div className="mob inl" style={{ marginTop: 14 }}><M.SuggestAnswer key={`${cur.key}-${cur.status}`} g={cur} onDone={reload} /></div>
            </>
          )}
        </div>
      </div>
    </Screen>
  );
}
export function ASuggest() { return usePc() ? <ASuggestPc /> : <M.ASuggest />; }

/* ====================== A-11 공공 서비스 정보: 표 + 패널 ====================== */
function APubPc() {
  const { toast, confirm } = useUi();
  const [list, reload] = useLoad(() => api('/admin/public-services'), []);
  const [form, setForm] = useState(null); // 'new' 또는 고치는 서비스
  const close = () => setForm(null);
  return (
    <Screen title="공공 서비스 정보" back={false} wide>
      <div className="prw" style={{ marginBottom: 16 }}>
        <span className="mu" style={{ flex: 1, fontSize: 13 }}>AI 공공 서비스 안내는 이 목록에서만 찾아요. 없는 서비스는 만들어 내지 않아요. 이름·신청 방법은 공식 사이트에서 확인 후 입력해요.</span>
        <button type="button" className="pbtn pri" onClick={() => setForm('new')}><PIc k="plus" />서비스 등록</button>
      </div>
      <div className="pnl">
        {list === undefined ? <Loading /> : <Tbl head={['서비스 이름', '대상', '신청 방법·신청처', '문의', '지역', '찾기 낱말', '']} plain empty="등록한 서비스가 없어요"
          rows={(list || []).map((p) => ({ key: p.id, cells: [
            <b key="n">{p.name}</b>, p.target, p.applyHow, p.contact || '-', p.region, arr(p.keywords).join(', '),
            <span key="a" className="prw" style={{ gap: 12, justifyContent: 'flex-end' }}>
              <button type="button" className="lk" onClick={() => setForm(p)}>수정</button>
              <button type="button" className="lk" style={{ color: '#C25E48' }} onClick={async () => { if (await confirm('이 서비스를 지울까요?', p.name, '삭제', true)) { try { await del(`/admin/public-services/${p.id}`); reload(); toast('지웠어요'); } catch { /* 토스트 */ } } }}>삭제</button>
            </span>,
          ] }))} />}
      </div>
      {form && <PcDrawer title={form === 'new' ? '서비스 등록' : '서비스 수정'} onClose={close}><div className="mob inl noh"><M.PubForm p={form === 'new' ? undefined : form} onDone={() => { close(); reload(); }} /></div></PcDrawer>}
    </Screen>
  );
}
export function APub() { return usePc() ? <APubPc /> : <M.APub />; }

/* ====================== 회원 관리(5판 메뉴에 없음): 검색 + 표 + 상세 ====================== */
const U_ST = { ACTIVE: ['g', '정상'], SUSPENDED: ['r', '정지'], WITHDRAWN: ['k', '탈퇴'] };
function AMembersPc() {
  const [q, setQ] = useState('');
  const [role, setRole] = useState('');
  const [qq, setQq] = useState('');
  const [pick, setPick] = useState(null);
  const [list, reload] = useLoad(() => api(`/admin/users?q=${encodeURIComponent(qq)}&role=${role}`), [qq, role]);
  const l = list || [];
  const cur = l.find((u) => u.id === pick) || l[0];
  return (
    <Screen title="회원 관리" back={false} wide>
      <form className="prw" style={{ marginBottom: 16, flexWrap: 'wrap' }} onSubmit={(e) => { e.preventDefault(); setQq(q.trim()); }}>
        <input className="pin" style={{ maxWidth: 280 }} placeholder="이름·아이디 검색" aria-label="이름·아이디 검색" value={q} onChange={(e) => setQ(e.target.value)} />
        <button className="pbtn pri" style={{ height: 40 }}>검색</button>
        <div className="pchips" style={{ marginLeft: 8 }}>{[['', '전체'], ['USER', '사용자'], ['ORG', '담당자'], ['ADMIN', '운영자']].map(([k, t]) => <button key={k} type="button" className={'pchip ' + (role === k ? 'on' : '')} aria-pressed={role === k} onClick={() => setRole(k)}>{t}</button>)}</div>
        <span className="mu">{list ? `${l.length}명` : ''}</span>
      </form>
      <div className="g21a">
        <div className="pnl">
          {list === undefined ? <Loading /> : <Tbl head={['이름', '아이디', '역할', '지역', '봉사', '받음', '경고', '상태']} sel={cur && cur.id} onSel={setPick} empty="회원이 없어요"
            rows={l.map((u) => ({ key: u.id, cells: [<b key="n">{u.name}</b>, u.loginId, M.RL[u.role] || u.role, u.area || '-', u.volunteerCount ?? '-', u.recipientCount ?? '-', u.warningCount || 0, u.banned ? <Tg key="s" k="r">퇴출</Tg> : <Tg key="s" k={(U_ST[u.status] || ['k'])[0]}>{(U_ST[u.status] || [0, u.status])[1]}</Tg>] }))} />}
        </div>
        <div className="pnl sticky">{cur ? <div className="mob inl"><M.MemberSheet key={cur.id} id={cur.id} onDone={reload} /></div> : <div className="pempty">왼쪽에서 회원을 골라 주세요</div>}</div>
      </div>
    </Screen>
  );
}
export function AMembers() { return usePc() ? <AMembersPc /> : <M.AMembers />; }

/* ====================== 담당자 계정(5판 메뉴에 없음): 표 ====================== */
function AManagersPc() {
  const nav = useNavigate();
  const { toast, sheet, closeSheet } = useUi();
  const [list, reload] = useLoad(() => api('/admin/managers'), []);
  const [busy, run] = useBusy();
  return (
    <Screen title="담당자 계정" back={false} wide>
      <div className="prw" style={{ marginBottom: 16 }}>
        <span className="mu" style={{ flex: 1, fontSize: 13 }}>담당자 계정을 정지·해제하고, 담당 대상을 다른 담당자에게 대신 넘길 수 있어요.</span>
        <button type="button" className="pbtn" onClick={() => nav('/a/approve?seg=mgr')}>담당자 계정 신청 내역 ›</button>
      </div>
      <div className="pnl">
        {list === undefined ? <Loading /> : <Tbl head={['기관', '이름', '아이디', '담당 대상', '상태', '']} plain empty="담당자가 없어요"
          rows={(list || []).map((m) => {
            const sus = m.status === 'SUSPENDED';
            return { key: m.id, cells: [
              <b key="o">{m.orgName || (m.org && m.org.name) || '-'}</b>, m.name, m.loginId, `${(m.elders || []).length}명`,
              <Tg key="s" k={m.banned || sus ? 'r' : 'g'}>{m.banned ? '퇴출' : sus ? '정지' : '정상'}</Tg>,
              <span key="a" className="prw" style={{ gap: 6, justifyContent: 'flex-end' }}>
                <button type="button" className="pbtn" disabled={busy || m.banned} onClick={() => run(async () => { await post(`/admin/managers/${m.id}/${sus ? 'unsuspend' : 'suspend'}`); reload(); toast(sus ? '정지를 해제했어요' : '정지했어요'); })}>{sus ? '정지 해제' : '정지'}</button>
                <button type="button" className="pbtn sel" onClick={() => sheet(() => <M.AdminTransfer m={m} managers={list} onDone={() => { closeSheet(); reload(); }} />)}>대신 이관</button>
              </span>,
            ] };
          })} />}
      </div>
    </Screen>
  );
}
export function AManagers() { return usePc() ? <AManagersPc /> : <M.AManagers />; }
