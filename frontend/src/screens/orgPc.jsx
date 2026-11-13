/* 담당자 PC 화면 (프로토타입 5판, 계약서 7.22): 홈(O-HOME-01) · 요청 관리 보드(O-POST-01)
   폭 901px 이상에서만 쓰고, 좁으면 org.jsx 의 모바일 화면이 그대로 나온다. 숫자·순서는 모두 서버 값이고 여기서는 배치만 한다. */
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Loading, useLoad } from '../ui/bits.jsx';
import { useAuth } from '../ui/ctx.jsx';
import { api } from '../api.js';
import { usePc, PIc, Avc, CPill, StTag5, dc5 } from '../ui/PcFrame.jsx';
import { OHome as OHomeMobile, OReqs as OReqsMobile, useElderPick } from './org.jsx';
import { needsMe, todoTxt } from './home.jsx';
import { OrgBriefing } from './opsai.jsx';
import { SHORT, KIND_LABEL, ACTIVE_ST, PAST_ST, dlabel, dshort, hh } from '../lib/format.js';

const md = (d) => { const m = /^\d{4}-(\d{2})-(\d{2})/.exec(String(d || '')); return m ? `${Number(m[1])}/${Number(m[2])}` : ''; };
const short = (c) => SHORT[c] || c || '도움';

/** 주간 편성 응답에서 보여 줄 요일: 월~금은 늘, 토·일은 일정·빈 일정이 있거나 오늘일 때만(5판은 월~금 다섯 칸) */
export function shownDays(data) {
  const days = (data && data.days) || [];
  const busy = new Set();
  ((data && data.elders) || []).forEach((row) => row.cells.forEach((c) => { if ((c.items || []).length || c.gap) busy.add(c.date); }));
  return days.filter((d, i) => i < 5 || d.today || busy.has(d.date));
}
/** 하루치 일정·빈 일정을 시작 시각 순으로 */
export function dayEvents(data, date) {
  const out = [];
  ((data && data.elders) || []).forEach((row) => row.cells.forEach((c) => {
    if (c.date !== date) return;
    (c.items || []).forEach((it) => out.push({ row, c, it, st: Number(it.startHour) || 0, du: it.kind === 'POST' ? Math.max(0.5, Number(it.endHour) - Number(it.startHour)) : Math.max(0.5, Number(it.hours) || 1), cat: it.category }));
    if (c.gap) out.push({ row, c, gap: c.gap, st: c.gap.startHour != null ? Number(c.gap.startHour) : 10, du: Math.max(0.5, Number(c.gap.hours) || 1), cat: c.gap.category });
  }));
  return out.sort((a, b) => a.st - b.st);
}
export const SCH_STATE = { CONFIRMED: '확정', PROGRESS: '진행 중', PENDING: '응답 대기', OPEN: '열린 공고', DONE: '완료' };

/* ---------- 홈 (O-HOME-01) ---------- */
function OHomePc() {
  const nav = useNavigate();
  const { me } = useAuth();
  const pick = useElderPick();
  const [home] = useLoad(() => api('/org/home', { quiet: true }), []);
  const [elders] = useLoad(() => api('/org/elders', { quiet: true }), []);
  const [reqs] = useLoad(() => api('/org/requests?box=all'), []);
  const [chg] = useLoad(() => api('/org/change-report', { quiet: true }).catch(() => null), []);
  const [wx] = useLoad(() => api('/org/weather', { quiet: true }).catch(() => null), []);
  const [pub] = useLoad(() => api('/org/public-referrals', { quiet: true }).catch(() => null), []);
  const [sch] = useLoad(() => api('/org/schedule', { quiet: true }).catch(() => null), []);
  const [day, setDay] = useState(null);

  const counts = (home && home.counts) || {};
  const todo = (reqs || []).filter(needsMe);
  const logs = (reqs || []).filter((r) => r.logText || r.logRaw).sort((a, b) => String(b.logSubmittedAt || b.date).localeCompare(String(a.logSubmittedAt || a.date)));
  const watch = chg ? (chg.items || []).filter((x) => x.level !== 'LOW') : [];
  const b = wx && wx.banner;
  const wxN = wx ? (wx.alerts || []).length + (wx.guides || []).length : null;
  const sc = (sch && sch.counts) || {};
  const days = shownDays(sch);
  const selDay = day || (days.find((d) => d.today) || days[0] || {}).date;
  const evs = selDay ? dayEvents(sch, selDay) : [];
  const elderN = counts.elders ?? (elders ? elders.filter((e) => e.status !== 'ENDED').length : null);
  const num = (v, u) => (v == null ? '-' : `${v}${u}`);
  const chips = [
    ['관리 대상', num(elderN, '명'), '/o/elders', 'users'],
    ['이번 주 돌봄', num(sc.fixed, '건'), '/o/schedule', 'cal'],
    ['편성 빈칸', num(sc.gaps, '개'), '/o/schedule', 'warn'],
    ['공공 서비스 연계', num(pub ? pub.openCount : null, '건'), '/o/public', 'building'],
  ];
  const fresh = [
    ['특보 알림', '기상 특보 · 운영자 안내', wxN, '/o/weather', '#FDECE6', '#C25E48'],
    ['공공 서비스 연계', '사용자가 전달한 요청 · 새로 온 것', pub ? pub.newCount : null, '/o/public', '#E8F0FE', '#3A6FD8'],
    ['관리 대상 이관 요청', '다른 담당자가 넘기려는 대상', counts.transfersIn ?? null, '/o/elders', '#FFF4DC', '#A6760C'],
  ];
  const quick = [
    ['관리 대상 등록', '별칭·나이대·동만 적어요', 'plus', () => nav('/o/elders/new')],
    ['공고 대신 등록', '대상을 골라 도움 요청 공고 올리기', 'memo', () => pick('누구 대신 공고를 올릴까요?', elders, (e) => nav(`/write/need?elder=${e.id}`))],
    ['봉사 공고 찾기', '봉사 가능 공고에 대신 요청', 'search', () => pick('누구 대신 요청할까요?', elders, (e) => nav(`/board?elder=${e.id}`))],
    ['단골에게 요청', '대상의 단골 봉사자에게 바로', 'heart', () => pick('누구의 단골에게 요청할까요?', elders, (e) => nav(`/regular?elder=${e.id}`))],
    ['말로 공고 만들기', '들은 내용을 말하면 공고로 정리', 'mic', () => pick('누구 대신 말로 요청할까요?', elders, (e) => nav(`/voice?elder=${e.id}`))],
    ['통화 메모로 여러 건', '메모 하나로 공고 여러 건 만들기', 'list', () => nav('/o/memo-posts')],
  ];
  const mon = selDay ? Number(selDay.split('-')[1]) : new Date().getMonth() + 1;
  return (
    <Screen title="홈" back={false} wide>
      <div className="hb-hero">
        <div>
          <div className="hb-hi">{me ? me.name : ''} 님, 오늘 처리할 일이 <b>{reqs === undefined ? (home && home.todo ? home.todo.length : '-') : todo.length}건</b> 있어요</div>
          <div className="hb-sub">{b
            ? `${b.type}${b.level ? ` ${b.level}` : ''} 기간이에요 · 담당 대상 ${b.myElders}명이 있는 지역이에요${b.myActivities ? ` · 기간 안 예정 활동 ${b.myActivities}건` : ''}`
            : `이번 달 활동 ${counts.monthDone ?? 0}건 · 진행 중 ${counts.active ?? 0}건`}</div>
        </div>
        <div className="hb-kpi">{chips.map(([k, v, to, i]) => <button key={k} type="button" className="hk" onClick={() => nav(to)}><i className="hk-i"><PIc k={i} /></i><div><span>{k}</span><b>{v}</b></div></button>)}</div>
      </div>
      <div className="hb-grid">
        <div className="pnl">
          {b && (
            <button type="button" className="nbar" onClick={() => nav('/o/weather')}>
              <span className="nb-ic"><PIc k="mega" /></span>
              <b>[{b.type}{b.level ? ` ${b.level}` : ''}] {md(b.from)}~{md(b.to)} {(b.areas || []).join('·')} · {(b.actions || [])[0] || '안부 확인과 일정 조정을 살펴 주세요'}</b>
              <span className="nb-r">운영자 안내{b.sentAt ? ` · ${dshort(b.sentAt)}` : ''}</span>
            </button>
          )}
          <div className="pg2">
            <div className="psec">
              <div className="ps-h"><b>오늘 처리할 일 <span className="cnt">{todo.length}</span></b><button type="button" className="ps-a" onClick={() => nav('/o/reqs')}>전체보기 ›</button></div>
              {reqs === undefined ? <Loading /> : todo.length ? todo.slice(0, 3).map((r) => (
                <button key={r.id} type="button" className="hrow" onClick={() => nav(`/o/reqs/${r.id}`)}>
                  <StTag5 st={r.status} kind={r.kind} />
                  <span className="hr-t">{r.elder ? r.elder.displayName : '대상'} · {short(r.category)}<small>{todoTxt(r)}</small></span>
                  <span className="hr-d">{r.leftText ? `${r.leftText} 남음` : dlabel(r.date)}</span>
                </button>
              )) : <div className="pempty">지금 처리할 일이 없어요</div>}
              {todo.length > 3 && <button type="button" className="pmore" onClick={() => nav('/o/reqs')}>+ {todo.length - 3}건 더 보기</button>}
            </div>
            <div className="psec">
              <div className="ps-h"><b>새로 온 요청</b></div>
              {fresh.map(([t, s, n, to, bg, fg]) => (
                <button key={t} type="button" className="hrow" onClick={() => nav(to)}>
                  <span className="hr-n" style={{ background: bg, color: fg }}>{n == null ? '-' : n}</span>
                  <span className="hr-t">{t}<small>{s}</small></span><span className="hr-d">›</span>
                </button>
              ))}
            </div>
            <div className="psec">
              <div className="ps-h"><b>먼저 안부 물어볼 분 <span className="cnt">{watch.length}</span></b><button type="button" className="ps-a" onClick={() => nav('/o/report')}>리포트 ›</button></div>
              {chg === undefined ? <Loading /> : watch.length ? watch.slice(0, 3).map((x, i) => (
                <button key={x.elderId} type="button" className="hrow" onClick={() => nav(`/o/report?elder=${x.elderId}`)}>
                  <Avc n={x.alias || x.displayName} cat="acc" />
                  <span className="hr-t">{x.displayName}<small>{[x.dong, (x.reasons && x.reasons[0]) || (x.changes && x.changes[0]) || x.summary].filter(Boolean).join(' · ')}</small></span>
                  {i < 2 ? <span className="stag" style={{ background: '#FDECE6', color: '#C25E48' }}>우선</span> : <span className="hr-d">›</span>}
                </button>
              )) : <div className="pempty">먼저 살펴볼 대상이 없어요</div>}
            </div>
            <div className="psec">
              <div className="ps-h"><b>최근 도착한 일지 <span className="cnt">{logs.length}</span></b><button type="button" className="ps-a" onClick={() => nav('/o/reqs?view=list&seg=past')}>전체보기 ›</button></div>
              {reqs === undefined ? <Loading /> : logs.length ? logs.slice(0, 3).map((r) => {
                const txt = String(r.logText || r.logRaw || '');
                return (
                  <button key={r.id} type="button" className="hrow" onClick={() => nav(`/o/reqs/${r.id}`)}>
                    <Avc n={r.volunteer && r.volunteer.name} cat={r.category} />
                    <span className="hr-t">{r.volunteer ? r.volunteer.name : '봉사자'} → {r.elder ? r.elder.displayName : '대상'}<small>{txt.slice(0, 30)}{txt.length > 30 ? '…' : ''}</small></span>
                    <span className="hr-d">{md(r.logSubmittedAt || r.date)}</span>
                  </button>
                );
              }) : <div className="pempty">아직 도착한 일지가 없어요</div>}
            </div>
          </div>
        </div>
        <div className="pnl">
          <div className="ps-h"><b>이번 주 돌봄</b><button type="button" className="ps-a" onClick={() => nav('/o/schedule')}>편성표 ›</button></div>
          {sch === undefined ? <Loading /> : !sch ? <div className="pempty">편성표를 불러오지 못했어요</div> : (
            <>
              <div className="dstrip">
                <div className="ds-m"><b>{mon}</b>월</div>
                {days.map((d) => <button key={d.date} type="button" className={selDay === d.date ? 'on' : ''} aria-pressed={selDay === d.date} onClick={() => setDay(d.date)}><b>{Number(d.date.split('-')[2])}</b><span>{d.dow}</span></button>)}
              </div>
              <div className="slist">
                {evs.length ? evs.map((x) => {
                  const [a, , bg] = dc5(x.cat);
                  const key = x.gap ? `g${x.row.elderId}` : `${x.it.kind}${x.it.requestId || x.it.postId}`;
                  const to = x.gap ? `/o/schedule?view=d&day=${selDay}` : x.it.kind === 'POST' ? `/post/${x.it.postId}` : `/o/reqs/${x.it.requestId}`;
                  return (
                    <button key={key} type="button" className="srow" onClick={() => nav(to)}>
                      <span className="s-tm" style={x.gap ? { background: '#FDECE6', color: '#C25E48' } : { background: bg, color: a }}>{hh(x.st)}</span>
                      <span className="s-t">{x.row.displayName} · {x.gap ? `빈칸 ${short(x.cat)}` : short(x.cat)}</span>
                      <span className="s-v">{x.gap ? <em>대안 보기</em> : x.it.volunteerName || SCH_STATE[x.it.state]}</span>
                    </button>
                  );
                }) : <div className="pempty">일정이 없어요</div>}
              </div>
            </>
          )}
          <div className="ps-h" style={{ marginTop: 18 }}><b>바로 하기</b></div>
          {quick.map(([t, s, i, f]) => (
            <button key={t} type="button" className="hrow" style={{ padding: '7px 2px' }} title={s} onClick={f}>
              <span className="hr-i"><PIc k={i} /></span>
              <span className="hr-t">{t}</span><span className="hr-d">›</span>
            </button>
          ))}
        </div>
      </div>
      <OrgBriefing />
    </Screen>
  );
}
export function OHome() { return usePc() ? <OHomePc /> : <OHomeMobile />; }

/* ---------- 요청 관리 보드 (O-POST-01) ----------
   5판 열 ↔ 실제 상태값. 끌어 옮기기는 없다(상태는 상세 화면의 버튼으로만 바뀐다). */
export const BOARD_COLS = [
  ['새 제안·수락', ['SENT', 'VOL_ACCEPTED']],
  ['확정', ['CONFIRMED']],
  ['진행 중', ['IN_PROGRESS', 'CHECKED_OUT']],
  ['일지 확인', ['LOG_SUBMITTED']],
  ['완료', ['DONE', 'REJECTED', 'DECLINED', 'EXPIRED', 'CANCELLED', 'NOSHOW']],
];
const DONE_MAX = 12;

function KCard({ r }) {
  const nav = useNavigate();
  const e = r.elder || {};
  const v = r.volunteer;
  const issue = e && Object.prototype.hasOwnProperty.call(e, 'baseCaution') ? e.caution : null; // 공고에 적은 "이번 요청 특이사항"만(대상 기본 주의사항은 상세에서)
  const mine = needsMe(r);
  const end = Number(r.startHour) + Number(r.hours);
  return (
    <button type="button" className="kcard" onClick={() => nav(`/o/reqs/${r.id}`)}>
      <div className="kc-top"><Avc n={e.alias || e.displayName} cat={r.category} /><div><b>{e.displayName || '대상'}</b><small>{[e.ageGroup, e.dong].filter(Boolean).join(' · ')}</small></div><StTag5 st={r.status} kind={r.kind} /></div>
      <div className="kc-tags"><CPill cat={r.category} />{r.kind && r.kind !== 'REQUEST' && <span className="cpill" style={{ background: '#F1F4F8', color: '#6B7585', borderColor: '#E3E8EF' }}>{KIND_LABEL[r.kind]}</span>}</div>
      <div className="kc-m"><span><PIc k="cal" />{dlabel(r.date)}</span><span><PIc k="clock" />{hh(r.startHour)}~{hh(end)} · {r.hours}시간</span></div>
      {issue && <div className="kc-i"><PIc k="memo" />{issue}</div>}
      <div className="kc-f">
        <span className="kc-v">{v ? <><Avc n={v.name} cat="acc" />{v.name}</> : '봉사자 없음'}</span>
        <span className={'kc-w ' + (mine ? 'do' : '')}>{mine ? todoTxt(r) : r.leftText && ['SENT', 'VOL_ACCEPTED'].includes(r.status) ? `${r.leftText} 남음` : ''}</span>
      </div>
    </button>
  );
}

function OReqsPc() {
  const nav = useNavigate();
  const pick = useElderPick();
  const [sp, setSp] = useSearchParams();
  const view = sp.get('view') === 'list' ? 'list' : 'board';
  const seg = sp.get('seg') || 'need';
  const [reqs] = useLoad(() => api('/org/requests?box=all'), []);
  const [elders] = useLoad(() => api('/org/elders', { quiet: true }), []);
  const list = (reqs || []).map((r) => ({ ...r, myRole: r.myRole || 'MANAGER' }));
  const byDate = (a, b) => (a.date === b.date ? Number(a.startHour) - Number(b.startHour) : a.date < b.date ? -1 : 1);
  const need = list.filter(needsMe);
  const going = list.filter((r) => ACTIVE_ST.includes(r.status) && !need.includes(r));
  const past = list.filter((r) => PAST_ST.includes(r.status));
  const rows = (seg === 'need' ? need : seg === 'going' ? going : past).slice().sort(seg === 'past' ? (a, b) => byDate(b, a) : byDate);
  const setView = (v) => setSp(v === 'list' ? { view: 'list', seg } : {}, { replace: true });
  return (
    <Screen title="요청 관리" back={false} wide>
      <div className="prw" style={{ marginBottom: 14, flexWrap: 'wrap', rowGap: 10 }}>
        <div className="cseg" role="tablist" aria-label="보기">
          <button type="button" role="tab" aria-selected={view === 'board'} className={view === 'board' ? 'on' : ''} onClick={() => setView('board')}>보드</button>
          <button type="button" role="tab" aria-selected={view === 'list'} className={view === 'list' ? 'on' : ''} onClick={() => setView('list')}>목록</button>
        </div>
        <span className="mu">카드를 누르면 상세로 가요 · 상태는 상세에서 바꿔요</span>
        <span className="sp" />
        <button type="button" className="pbtn" onClick={() => nav('/recurring')}>정기 요청</button>
        <button type="button" className="pbtn" onClick={() => pick('누구 대신 요청할까요?', elders, (e) => nav(`/board?elder=${e.id}`))}>도움 찾기</button>
        <button type="button" className="pbtn pri" onClick={() => pick('누구 대신 공고를 올릴까요?', elders, (e) => nav(`/write/need?elder=${e.id}`))}>+ 대리 요청</button>
      </div>
      {reqs === undefined ? <Loading /> : view === 'board' ? (
        <div className="kb">
          {BOARD_COLS.map(([t, ss], ci) => {
            const all = list.filter((r) => ss.includes(r.status)).sort(ci === 4 ? (a, b) => byDate(b, a) : byDate);
            const L = ci === 4 ? all.slice(0, DONE_MAX) : all;
            return (
              <section key={t} className="kcol" aria-label={`${t} ${all.length}건`}>
                <div className="kc-h"><b>{t}</b><span>{all.length}</span></div>
                {L.length ? L.map((r) => <KCard key={r.id} r={r} />) : <div className="kc-empty">없어요</div>}
                {all.length > L.length && <button type="button" className="kc-more" onClick={() => setSp({ view: 'list', seg: 'past' }, { replace: true })}>+ {all.length - L.length}건 더 · 목록에서 보기</button>}
              </section>
            );
          })}
        </div>
      ) : (
        <div className="pnl">
          <div className="pchips" style={{ marginBottom: 12 }}>{[['need', '처리할 일', need.length], ['going', '진행 중', going.length], ['past', '완료', past.length]].map(([k, t, n]) => <button key={k} type="button" className={'pchip ' + (seg === k ? 'on' : '')} onClick={() => setSp({ view: 'list', seg: k }, { replace: true })}>{t} {n}</button>)}</div>
          {rows.length ? (
            <table className="tbl" aria-label="요청 목록">
              <thead><tr><th scope="col">상태</th><th scope="col">대상</th><th scope="col">도움</th><th scope="col">날짜 · 시간</th><th scope="col">봉사자</th><th scope="col">내용</th><th scope="col">할 일</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} tabIndex={0} onClick={() => nav(`/o/reqs/${r.id}`)} onKeyDown={(ev) => { if (ev.key === 'Enter') nav(`/o/reqs/${r.id}`); }}>
                    <td><StTag5 st={r.status} kind={r.kind} /></td>
                    <td><b>{r.elder ? r.elder.displayName : '-'}</b>{r.elder && r.elder.dong ? <span className="mu"> · {r.elder.dong}</span> : null}</td>
                    <td><CPill cat={r.category} /></td>
                    <td style={{ whiteSpace: 'nowrap' }}>{dlabel(r.date)} {hh(r.startHour)}~{hh(Number(r.startHour) + Number(r.hours))}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>{r.volunteer ? r.volunteer.name : '-'}</td>
                    <td style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.content}</td>
                    <td style={{ whiteSpace: 'nowrap', color: needsMe(r) ? '#C25E48' : '#9AA3B2', fontWeight: needsMe(r) ? 800 : 500 }}>{needsMe(r) ? todoTxt(r) : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : <div className="pempty">없어요</div>}
        </div>
      )}
    </Screen>
  );
}
export function OReqs() { return usePc() ? <OReqsPc /> : <OReqsMobile />; }
