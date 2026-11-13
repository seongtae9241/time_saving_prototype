/* 돌봄 편성표 PC 화면 (프로토타입 5판 O-SCHED-01, 계약서 7.22): [편성표 | 일 | 주 | 월] 보기, 처음 값은 주.
   자료는 GET /api/org/schedule(주 단위) 그대로이고, 여기서는 시간 격자에 놓기만 한다. 요청·공고는 담당자가 눌러야 보내진다.
   폭 901px 이상에서만 쓰고, 좁으면 schedule.jsx 의 표 화면이 그대로 나온다. */
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Loading, useLoad } from '../ui/bits.jsx';
import { useUi } from '../ui/ctx.jsx';
import { api } from '../api.js';
import { usePc, Avc, dc5 } from '../ui/PcFrame.jsx';
import { OSchedule as OScheduleMobile, ScheduleBody, SuggestSheet, BulkSheet } from './schedule.jsx';
import { shownDays, dayEvents, SCH_STATE } from './orgPc.jsx';
import { SHORT, iso } from '../lib/format.js';

const H0 = 8, H1 = 20, RH = 62; // 08~20시, 한 시간 62px (5판 값)
const VIEWS = [['t', '편성표'], ['d', '일'], ['w', '주'], ['m', '월']];
const WD = ['일', '월', '화', '수', '목', '금', '토'];
const md = (d) => { const [, m, day] = d.split('-'); return `${Number(m)}/${Number(day)}`; };
const fmt = (h) => `${String(Math.floor(h)).padStart(2, '0')}:${h % 1 ? '30' : '00'}`;
const short = (c) => SHORT[c] || c || '도움';
const toDate = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const gapKey = (x) => `${x.row.elderId}|${x.c.date}`;

/** 겹치는 일정은 칸을 나눠 나란히(5판 lanes) */
function lanes(list) {
  const L = list.map((x) => ({ ...x })).sort((a, b) => a.st - b.st);
  let cl = [], end = -1, le = [];
  const flush = () => { const n = le.length; cl.forEach((x) => { x.n = n; }); cl = []; le = []; };
  L.forEach((x) => {
    if (x.st >= end && cl.length) flush();
    let li = le.findIndex((e) => e <= x.st);
    if (li < 0) { li = le.length; le.push(0); }
    le[li] = x.st + x.du; x.lane = li; cl.push(x); end = Math.max(end, x.st + x.du);
  });
  flush();
  return L;
}

function EvBlock({ x, wide, sel, caution, onGap }) {
  const nav = useNavigate();
  const [a, b, bg] = dc5(x.cat);
  const st = Math.min(Math.max(x.st, H0), H1 - 0.5), en = Math.max(Math.min(x.st + x.du, H1), st + 0.5);
  const w = 100 / (x.n || 1);
  const pos = { left: `calc(${x.lane * w}% + 4px)`, width: `calc(${w}% - 8px)`, top: (st - H0) * RH + 2, height: (en - st) * RH - 4 };
  const cls = `${x.n > 1 ? 'narrow' : ''} ${en - st < 1.4 ? 'short' : ''}`;
  const time = `${fmt(x.st)}–${fmt(x.st + x.du)}`;
  if (x.gap) {
    return (
      <button type="button" className={`cev gap ${sel ? 'sel' : ''} ${cls}`} style={pos} aria-pressed={sel} title={`빈칸 · ${x.row.displayName} · ${short(x.cat)}\n${x.gap.reason || ''}`} onClick={() => onGap(x)}>
        <div className="ce-t">빈칸 · {short(x.cat)}</div>
        <div className="ce-s">{x.row.displayName} · {time}</div>
        {wide && x.gap.reason && <div className="ce-alt">→ {x.gap.reason}</div>}
        <div className="ce-tags"><span className="ce-tag r">대안 보기</span></div>
      </button>
    );
  }
  const it = x.it, post = it.kind === 'POST';
  return (
    <button type="button" className={`cev ${post ? 'open' : ''} ${it.state === 'DONE' ? 'done' : ''} ${cls}`} style={{ ...pos, background: bg, borderColor: b, '--ca': a }}
      title={`${x.row.displayName} · ${short(x.cat)}\n${time}${it.volunteerName ? ` · 봉사자 ${it.volunteerName}` : ''} · ${SCH_STATE[it.state] || ''}`}
      onClick={() => nav(post ? `/post/${it.postId}` : `/o/reqs/${it.requestId}`)}>
      <div className="ce-t">{x.row.displayName} · {short(x.cat)}</div>
      <div className="ce-s">{time} · {x.row.dong}</div>
      {wide && caution && <div className="ce-alt">{caution}</div>}
      <div className="ce-foot">
        {it.volunteerName && <span className="ce-v"><span className="ce-av">{it.volunteerName[0]}</span>{it.volunteerName}</span>}
        <span className={'ce-tag ' + (it.state === 'PROGRESS' ? 'g' : it.state === 'PENDING' ? 'a' : '')}>{SCH_STATE[it.state] || it.state}</span>
        {post && it.pendingCount > 0 && <span className="ce-tag a">제안 {it.pendingCount}건</span>}
      </div>
    </button>
  );
}

/** 주·일 보기: 요일 머리 + 시간 열 + 날짜 열 */
function TimeGrid({ data, days, gsel, cautions, onGap, onDay }) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const t = setInterval(() => setNow(new Date()), 60000); return () => clearInterval(t); }, []);
  const nowH = now.getHours() + now.getMinutes() / 60;
  const one = days.length === 1;
  return (
    <div className="cw" style={{ '--cols': days.length }}>
      <div className="cw-hd"><div />{days.map((d) => <button key={d.date} type="button" className={'cw-d ' + (d.today ? 'today' : '')} onClick={() => onDay(d.date)} aria-label={`${d.label} ${d.dow}요일 하루 보기`}><span>{d.dow}</span><b>{Number(d.date.split('-')[2])}</b></button>)}</div>
      <div className="cw-bd">
        <div className="ct">{Array.from({ length: H1 - H0 }, (_, i) => <div key={i} className="ct-h" style={{ height: RH }}><span>{String(H0 + i).padStart(2, '0')}:00</span></div>)}</div>
        {days.map((d) => (
          <div key={d.date} className={'cw-col ' + (d.today ? 'today' : '')} style={{ height: (H1 - H0) * RH }}>
            {Array.from({ length: H1 - H0 }, (_, i) => <div key={i} className="cw-cell" style={{ top: i * RH, height: RH }} />)}
            {lanes(dayEvents(data, d.date)).map((x) => <EvBlock key={x.gap ? `g${gapKey(x)}` : `${x.it.kind}${x.it.requestId || x.it.postId}`} x={x} wide={one} sel={!!x.gap && gsel === gapKey(x)} caution={cautions[x.row.elderId]} onGap={onGap} />)}
            {d.today && nowH >= H0 && nowH <= H1 && <div className="cnow" style={{ top: (nowH - H0) * RH }}><i /><span>{fmt(Math.floor(nowH)).slice(0, 3)}{String(now.getMinutes()).padStart(2, '0')}</span></div>}
          </div>
        ))}
      </div>
    </div>
  );
}

/** 월 보기: 주 단위 API 를 그 달의 주 수만큼 불러 한 달 달력으로 */
function MonthGrid({ ym, elderId, onDay }) {
  const [y, m] = ym.split('-').map(Number);
  const first = new Date(y, m - 1, 1), n = new Date(y, m, 0).getDate();
  const lead = (first.getDay() + 6) % 7; // 월요일 시작
  const mondays = [];
  for (let d = new Date(y, m - 1, 1 - lead); d <= new Date(y, m - 1, n); d.setDate(d.getDate() + 7)) mondays.push(iso(d));
  const [weeks] = useLoad(() => Promise.all(mondays.map((w) => api(`/org/schedule?week=${w}${elderId ? `&elderId=${elderId}` : ''}`, { quiet: true }).catch(() => null))), [ym, elderId]);
  if (weeks === undefined) return <Loading />;
  const today = iso(new Date());
  const cells = [];
  for (let i = 0; i < lead; i++) cells.push(null);
  for (let d = 1; d <= n; d++) cells.push(d);
  while (cells.length % 7) cells.push(null);
  const evOf = (date) => { const wk = (weeks || []).find((w) => w && w.days.some((x) => x.date === date)); return wk ? dayEvents(wk, date) : []; };
  return (
    <div className="cm">
      <div className="cm-hd">{['월', '화', '수', '목', '금', '토', '일'].map((w) => <div key={w}>{w}</div>)}</div>
      <div className="cm-g">
        {cells.map((d, i) => {
          if (!d) return <div key={`o${i}`} className="cm-c off" />;
          const date = `${ym}-${String(d).padStart(2, '0')}`;
          const L = evOf(date);
          const we = i % 7 >= 5;
          return (
            <button key={date} type="button" className={`cm-c ${date === today ? 'today' : ''} ${we ? 'we' : ''}`} onClick={() => onDay(date)} aria-label={`${m}월 ${d}일 · 일정 ${L.filter((x) => !x.gap).length}건${L.some((x) => x.gap) ? ' · 빈칸 있음' : ''}`}>
              <div className="cm-n">{d}</div>
              {L.slice(0, 3).map((x, j) => { const [a, , bg] = dc5(x.cat); return <div key={j} className={'cm-e ' + (x.gap ? 'gap' : '')} style={x.gap ? null : { background: bg, '--ca': a }} title={`${x.row.displayName} · ${short(x.cat)}${x.gap ? ' · 빈칸' : x.it.volunteerName ? ` · 봉사자 ${x.it.volunteerName}` : ` · ${SCH_STATE[x.it.state] || ''}`}`}>{x.gap ? '빈칸 ' : ''}{x.row.displayName} · {short(x.cat)}</div>; })}
              {L.length > 3 && <div className="cm-more">+{L.length - 3}</div>}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function OSchedulePc() {
  const nav = useNavigate();
  const { sheet, closeSheet } = useUi();
  const [sp, setSp] = useSearchParams();
  const view = VIEWS.some(([k]) => k === sp.get('view')) ? sp.get('view') : 'w';
  const week = sp.get('week') || '';
  const elderId = sp.get('elder') || '';
  const dayP = sp.get('day') || '';
  const [tick, setTick] = useState(0);
  const [gsel, setGsel] = useState('');
  const q = new URLSearchParams();
  if (week) q.set('week', week);
  if (elderId) q.set('elderId', elderId);
  const [data] = useLoad(() => api(`/org/schedule${q.toString() ? `?${q}` : ''}`), [week, elderId, tick]);
  const [elders] = useLoad(() => api('/org/elders', { quiet: true }).catch(() => null), []);
  const cautions = {};
  (elders || []).forEach((e) => { if (e.caution) cautions[e.id] = e.caution; });
  const go = (o) => {
    const cur = { view, week, elder: elderId, day: dayP, ...o };
    const n = {};
    if (cur.view && cur.view !== 'w') n.view = cur.view;
    if (cur.week) n.week = cur.week;
    if (cur.elder) n.elder = cur.elder;
    if (cur.day && cur.view === 'd') n.day = cur.day;
    setGsel(''); setSp(n, { replace: true });
  };
  const seg = <div className="cseg" role="tablist" aria-label="보기">{VIEWS.map(([k, t]) => <button key={k} type="button" role="tab" aria-selected={view === k} className={view === k ? 'on' : ''} onClick={() => go({ view: k })}>{t}</button>)}</div>;

  if (view === 't') {
    return (
      <Screen title="돌봄 편성표" back={false} wide>
        <div className="prw" style={{ marginBottom: 14 }}><span className="mu">대상 × 요일 표예요. 빈 칸(+)을 골라 추천을 보거나 공고를 한꺼번에 올려요.</span><span className="sp" />{seg}</div>
        <div className="pnl"><ScheduleBody /></div>
      </Screen>
    );
  }

  const days = shownDays(data);
  const anchor = week || (data && data.today) || iso(new Date());
  const ym = anchor.slice(0, 7);
  const selDay = view === 'd' ? ((data && data.days.find((d) => d.date === dayP)) || days.find((d) => d.today) || days[0]) : null;
  const gaps = [];
  ((data && data.elders) || []).forEach((row) => row.cells.forEach((c) => { if (c.gap) gaps.push({ row, c, gap: c.gap, cat: c.gap.category }); }));
  const cellInfo = (x) => { const d = (data.days || []).find((y) => y.date === x.c.date) || {}; return { elderId: x.row.elderId, displayName: x.row.displayName, date: x.c.date, dow: d.dow, category: x.gap.category || (x.row.needs || [])[0] || '', startHour: x.gap.startHour, hours: x.gap.hours }; };
  const openBulk = (cells) => sheet(() => <BulkSheet cells={cells} onDone={() => { closeSheet(); setGsel(''); setTick((t) => t + 1); }} />);
  const openSuggest = (cell) => sheet(() => <SuggestSheet cell={cell} onClose={closeSheet} onPost={(d) => openBulk([{ ...cell, category: d.category, startHour: d.startHour, hours: d.endHour - d.startHour }])} />);
  const picked = gaps.find((x) => gapKey(x) === gsel);
  const shiftMonth = (k) => { const [y, m] = ym.split('-').map(Number); return iso(new Date(y, m - 1 + k, 1)); };
  const label = !data ? '' : view === 'm' ? `${Number(ym.slice(0, 4))}년 ${Number(ym.slice(5))}월` : view === 'd' && selDay ? `${selDay.label} (${selDay.dow})` : `${md(data.weekStart)} ~ ${md(data.weekEnd)}`;
  // 요일별 편성 · 이번 주 봉사자: 주간 응답으로 여기서 센다
  const perDay = days.map((d) => { const L = dayEvents(data, d.date); return [d, L.filter((x) => !x.gap).length, L.filter((x) => x.gap).length]; });
  const maxDay = Math.max(4, ...perDay.map(([, f, g]) => f + g));
  const vc = {};
  ((data && data.elders) || []).forEach((row) => row.cells.forEach((c) => (c.items || []).forEach((it) => { if (it.kind === 'REQUEST' && it.volunteerName) vc[it.volunteerName] = (vc[it.volunteerName] || 0) + 1; })));
  const vols = Object.entries(vc).sort((a, b) => b[1] - a[1]).slice(0, 6);
  const cnt = (data && data.counts) || {};
  return (
    <Screen title="돌봄 편성표" back={false} wide>
      <div className="prw" style={{ marginBottom: 14, flexWrap: 'wrap', rowGap: 10 }}>
        <button type="button" className="pbtn" aria-label={view === 'm' ? '이전 달' : '이전 주'} disabled={!data} onClick={() => go(view === 'm' ? { week: shiftMonth(-1) } : { week: data.prevWeek, day: '' })}>‹</button>
        <b style={{ fontSize: 16, whiteSpace: 'nowrap' }}>{label}</b>
        <button type="button" className="pbtn" aria-label={view === 'm' ? '다음 달' : '다음 주'} disabled={!data} onClick={() => go(view === 'm' ? { week: shiftMonth(1) } : { week: data.nextWeek, day: '' })}>›</button>
        <div className="pchips" role="tablist" aria-label="대상 필터">
          <button type="button" role="tab" aria-selected={!elderId} className={'pchip ' + (!elderId ? 'on' : '')} onClick={() => go({ elder: '' })}>전체</button>
          {((data && data.filter) || []).map((e) => <button key={e.elderId} type="button" role="tab" aria-selected={String(e.elderId) === elderId} className={'pchip ' + (String(e.elderId) === elderId ? 'on' : '')} onClick={() => go({ elder: String(e.elderId) })}>{e.displayName}</button>)}
          {data && !data.thisWeek && <button type="button" className="pchip" onClick={() => go({ week: '', day: '' })}>이번 주로</button>}
        </div>
        {seg}
        <button type="button" className="pbtn pri" disabled={!gaps.length} onClick={() => openBulk(gaps.map(cellInfo))}>빈칸 {gaps.length}개 한꺼번에 공고 올리기</button>
      </div>
      {data === undefined ? <Loading /> : !data ? <div className="pnl pempty">편성표를 불러오지 못했어요</div> : (
        <div className="g21c">
          <div className="pnl ccal">
            {view === 'm' ? <MonthGrid ym={ym} elderId={elderId} onDay={(date) => go({ view: 'd', week: date, day: date })} />
              : view === 'd' ? (
                <>
                  <div className="cday-tabs">{days.map((d) => <button key={d.date} type="button" className={selDay && selDay.date === d.date ? 'on' : ''} onClick={() => go({ day: d.date })}>{d.dow} {d.label}</button>)}</div>
                  {selDay && <TimeGrid data={data} days={[selDay]} gsel={gsel} cautions={cautions} onGap={(x) => setGsel(gapKey(x))} onDay={() => {}} />}
                </>
              ) : <TimeGrid data={data} days={days} gsel={gsel} cautions={cautions} onGap={(x) => setGsel(gapKey(x))} onDay={(date) => go({ view: 'd', day: date })} />}
            <div className="cleg">
              <span><i style={{ background: '#EDF3FE', borderColor: '#CFE0FC' }} />확정 {cnt.fixed ?? 0}</span>
              <span><i style={{ background: '#fff', borderColor: '#CFE0FC', borderStyle: 'dashed' }} />열린 공고 {cnt.open ?? 0}</span>
              <span>응답 대기 {cnt.pending ?? 0}</span>
              <span><i className="g" />빈칸 {cnt.gaps ?? 0} · 누르면 오른쪽에 대안</span>
              <span><i style={{ background: '#D9705A', width: 10, height: 2, border: 0 }} />지금</span>
            </div>
          </div>
          <div>
            {picked && (
              <div className="pnl sp-box" role="region" aria-label="빈칸 상세">
                <b>{picked.row.displayName} · {md(picked.c.date)}({WD[toDate(picked.c.date).getDay()]})</b>
                <div className="mu" style={{ margin: '2px 0 8px' }}>빈칸 · {short(picked.cat)}{picked.gap.startHour != null ? ` · ${fmt(Number(picked.gap.startHour))}–${fmt(Number(picked.gap.startHour) + (Number(picked.gap.hours) || 1))}` : ''}</div>
                <div style={{ fontSize: 13 }}>{picked.gap.reason}</div>
                <div className="prw" style={{ marginTop: 12, flexWrap: 'wrap', gap: 6 }}>
                  <button type="button" className="pbtn pri" onClick={() => openSuggest(cellInfo(picked))}>봉사자 추천 보기</button>
                  <button type="button" className="pbtn" onClick={() => openBulk([cellInfo(picked)])}>공고로 올리기</button>
                  <button type="button" className="pbtn sec2" onClick={() => nav(`/o/elders/${picked.row.elderId}`)}>대상 보기</button>
                </div>
                <div className="mu" style={{ marginTop: 8 }}>추천은 후보만 보여 줘요. 요청과 공고는 담당자가 눌러야 보내져요.</div>
              </div>
            )}
            <div className="pnl sp-box">
              <b>이번 주 빈칸 {gaps.length}개</b>
              <div className="mu" style={{ margin: '2px 0 0' }}>지난 4주 방문 흐름 기준</div>
              {gaps.length ? gaps.map((x) => (
                <button key={gapKey(x)} type="button" className={'gaprow ' + (gsel === gapKey(x) ? 'sel' : '')} aria-pressed={gsel === gapKey(x)} onClick={() => setGsel(gapKey(x))}>
                  <b>{x.row.displayName} · {md(x.c.date)}({WD[toDate(x.c.date).getDay()]}) · {short(x.cat)}</b><span>{x.gap.reason}</span>
                </button>
              )) : <div className="pempty">비어 보이는 일정이 없어요</div>}
            </div>
            <div className="pnl sp-box">
              <b>요일별 편성</b>
              <div className="mu" style={{ margin: '2px 0 6px' }}>일정 · 빈칸</div>
              {perDay.map(([d, f, g]) => (
                <div key={d.date} className="dayrow">
                  <b>{d.dow} {Number(d.date.split('-')[2])}</b>
                  <div className="db" title={`${d.label} 일정 ${f}건 · 빈칸 ${g}건`}><i style={{ flex: f, background: 'var(--acc)' }} /><i style={{ flex: g, background: '#EFA898' }} /><i style={{ flex: Math.max(0, maxDay - f - g) }} /></div>
                  <span>{f}건{g ? <> · <b style={{ color: '#C25E48' }}>{g}</b></> : null}</span>
                </div>
              ))}
            </div>
            <div className="pnl sp-box">
              <b>이번 주 봉사자</b>
              <div className="mu" style={{ margin: '2px 0 4px' }}>편성 많은 순</div>
              {vols.length ? vols.map(([name, c]) => <div key={name} className="dayrow"><Avc n={name} cat="acc" size={28} /><b style={{ width: 'auto', flex: 1 }}>{name}</b><span>{c}건</span></div>) : <div className="pempty">편성된 봉사자가 없어요</div>}
            </div>
            {elderId && <button type="button" className="pbtn" style={{ marginTop: 14, width: '100%' }} onClick={() => nav(`/recurring?elder=${elderId}`)}>이 대상의 정기 요청 관리 ›</button>}
          </div>
        </div>
      )}
    </Screen>
  );
}
export function OSchedule() { return usePc() ? <OSchedulePc /> : <OScheduleMobile />; }
