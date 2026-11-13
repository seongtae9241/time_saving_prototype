/* 담당자 PC 화면 2차 (프로토타입 5판, 계약서 7.23): 관리 대상 목록 + 상세(O-CARE-01·02) · 등록·수정 패널(O-CARE-03)
   폭 901px 이상에서만 쓰고, 좁으면 org.jsx 의 모바일 화면이 그대로 나온다. API·동작은 모바일 화면과 같고 배치만 다르다.
   실명·나이(숫자)·전화·주소·가구 형태는 저장하지 않으므로 그 자리는 별칭·나이대·동과 지금 있는 자료로 채운다. */
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Screen from '../ui/Frame.jsx';
import { Loading, RuleNote, hasBack, useLoad, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi } from '../ui/ctx.jsx';
import { api, post, put } from '../api.js';
import { arr, dshort, dlabel, hh, maskN, ACTIVE_ST, SHORT } from '../lib/format.js';
import { usePc, PIc, Avc, CPill, StTag5, PcDrawer } from '../ui/PcFrame.jsx';
import { OElders as OEldersMobile, EDetail as EDetailMobile, EForm as EFormMobile, ElderFormBody, ChangeCard, RepostSheet, TransferSheet, BlockList, elderName } from './org.jsx';
import { shownDays, SCH_STATE } from './orgPc.jsx';
import { useCareSignals, SignalCard } from './opsai.jsx';
import { ElderClasses } from './publicClass.jsx';
import { DoorCardSheet } from './request.jsx';
import { ReportView } from './my.jsx';
import { MicButton } from '../ui/VoiceInput.jsx';

const day1 = (dt) => dshort(dt).split(' ')[0];
const short = (c) => SHORT[c] || c || '도움';

/* ---------- 상세 (O-CARE-02) ---------- */
function ElderDetail({ id, onChanged }) {
  const nav = useNavigate();
  const { toast, confirm, sheet, closeSheet } = useUi();
  const [e, reload] = useLoad(() => api(`/org/elders/${id}`), [id]);
  const [reqs] = useLoad(() => api('/org/requests?box=all', { quiet: true }), [id]);
  const [reps] = useLoad(() => api('/org/reports', { quiet: true }), []);
  const [chg] = useLoad(() => api('/org/change-report', { quiet: true }).catch(() => null), [id]);
  const [sch] = useLoad(() => api(`/org/schedule?elderId=${id}`, { quiet: true }).catch(() => null), [id]);
  const sigs = useCareSignals();
  const [memo, setMemo] = useState('');
  const [cu, setCu] = useState('');
  const [tidyOpen, setTidyOpen] = useState(false);
  const [more, setMore] = useState(false);
  const [busy, run] = useBusy();
  if (e === undefined) return <div className="pnl"><Loading /></div>;
  if (!e) return <div className="pnl pempty">대상을 찾을 수 없어요</div>;
  const who = elderName(e);
  const ended = e.status === 'ENDED';
  const myReqs = (e.requests || (reqs || []).filter((r) => r.elder && String(r.elder.id) === String(id))).slice().sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
  const rep = (e.reports || [])[0] || (reps || []).filter((r) => r.subjectType === 'ELDER' && String(r.subjectId) === String(id))[0];
  const myChange = chg ? (chg.items || []).find((x) => String(x.elderId) === String(id)) : null;
  const memos = e.memos || [];
  const regs = e.regulars || [];
  const again = async () => { await reload(); onChanged && onChanged(); };
  const focusMemo = () => { const el = document.getElementById('elder-memo-in'); if (el) { el.scrollIntoView({ block: 'center' }); el.focus(); } };
  const repost = () => sheet(() => <RepostSheet elder={e} posts={e.posts} onDone={(np) => { closeSheet(); if (np && np.id) nav(`/post/${np.id}`); else reload(); }} />);
  const transfer = () => sheet(() => <TransferSheet e={e} onDone={() => { closeSheet(); again(); }} />);
  const consent = async () => {
    if (!(await confirm('서비스 동의를 받았나요?', `${who}께 서비스 이용을 설명하고 동의를 받았을 때만 기록해 주세요.`, '동의 받음으로 기록'))) return;
    run(async () => { await put(`/org/elders/${id}`, { consent: true }); await reload(); toast('서비스 동의를 기록했어요'); });
  };
  const endCare = async () => {
    const n = myReqs.filter((r) => ACTIVE_ST.includes(r.status)).length;
    if (!(await confirm(`${who} 지원을 종료할까요?`, n ? `진행 중인 요청 ${n}건을 먼저 정리해야 해요.` : '기록은 남고 새 요청은 할 수 없어요.', '종료', true))) return;
    run(async () => { await post(`/org/elders/${id}/end`); await again(); toast('지원을 종료했어요'); });
  };
  const days = shownDays(sch);
  const row = sch ? (sch.elders || []).find((x) => String(x.elderId) === String(id)) : null;
  const shown = more ? myReqs : myReqs.slice(0, 6);
  return (
    <>
      {/* 머리 카드: 별칭 · 나이대 · 동 + 필요한 도움 + 주요 버튼 */}
      <div className="pnl">
        <div className="prw" style={{ alignItems: 'flex-start', flexWrap: 'wrap', rowGap: 10 }}>
          <Avc n={e.alias} cat="acc" size={56} />
          <div style={{ flex: '1 1 220px', minWidth: 0 }}>
            <div className="d-t">{who} <span className="mu" style={{ fontSize: 14, fontWeight: 500 }}>{e.ageGroup} · {e.dong} · {day1(e.createdAt)}부터</span>{(e.flag || e.bigChange) && <span className="ptag r">변화 큼</span>}{sigs[e.id] && sigs[e.id].flagged && <span className="ptag a">확인 권장</span>}{ended && <span className="ptag">지원 종료</span>}</div>
            <div className="ptags">{arr(e.needs).map((c) => <CPill key={c} cat={c}>{short(c)}</CPill>)}<span className={'ptag ' + (e.reportConsent ? 'g' : '')}>{e.reportConsent ? '리포트 공유 동의 ✓' : '리포트 공유 동의 없음'}</span></div>
          </div>
          {!ended && (
            <div className="prw" style={{ gap: 8 }}>
              <button type="button" className="pbtn" onClick={() => nav(`/o/elders/${id}/edit`)}>정보 수정</button>
              <button type="button" className="pbtn" onClick={() => nav(`/board?elder=${id}`)}>도움 찾기</button>
              <button type="button" className="pbtn pri" onClick={() => nav(`/write/need?elder=${id}`)}>대신 요청 올리기</button>
            </div>
          )}
        </div>
        {!ended && (
          <div className="d-acts">
            {[['단골에게 요청', 'heart', () => nav(`/regular?elder=${id}`)], ['지난 요청 다시 올리기', 'redo', repost], ['정기 요청 등록·관리', 'clock', () => nav(`/recurring?elder=${id}`)], ['말로 공고 만들기', 'mic', () => nav(`/voice?elder=${id}`)], ['통화 메모로 여러 건', 'list', () => nav(`/o/memo-posts?elder=${id}`)], ['메모 쓰기', 'memo', focusMemo]]
              .map(([t, i, f]) => <button key={t} type="button" className="pchip" onClick={f}><PIc k={i} />{t}</button>)}
          </div>
        )}
      </div>
      {sigs[e.id] && sigs[e.id].flagged && <div className="mob"><SignalCard s={sigs[e.id]} who={who} /></div>}

      {/* 방문 주의사항(확정된 봉사자에게만) · 단골 봉사자 */}
      <div className="g2">
        <div className="warnbox">
          <b><PIc k="warn" />방문 주의사항</b> <span className="mu">일정이 확정된 봉사자에게만 보여요</span>
          <div style={{ marginTop: 6 }}>{e.caution || '적어 둔 주의사항이 없어요'}</div>
          {!ended && (
            <div className="prw" style={{ marginTop: 10 }}>
              <input className="pin" placeholder="주의사항 추가 (병명은 적지 않아요)" value={cu} onChange={(x) => setCu(x.target.value)} aria-label="주의사항 추가" />
              <button type="button" className="pbtn pri" disabled={busy} onClick={() => run(async () => { const v = cu.trim(); if (!v) return toast('주의사항을 적어 주세요'); await put(`/org/elders/${id}`, { caution: e.caution ? `${e.caution} / ${v}` : v }); setCu(''); await reload(); toast('봉사자에게 보내는 정보에 추가했어요'); })}>추가</button>
            </div>
          )}
        </div>
        <div className="pnl">
          <div className="ps-h"><b>단골 봉사자</b>{!ended && regs.length > 0 && <button type="button" className="ps-a" onClick={() => nav(`/regular?elder=${id}`)}>단골에게 요청 ›</button>}</div>
          {regs.length ? <div className="prw" style={{ flexWrap: 'wrap', gap: '8px 14px' }}>{regs.map((r) => <span key={r.id || r.name} className="prw" style={{ gap: 6 }}><Avc n={r.name} cat="acc" size={28} /><b>{r.name}</b><span className="ptag g">단골</span></span>)}</div>
            : <div className="mu">아직 없어요. 같은 봉사자와 여러 번 함께하면 단골이 돼요.</div>}
          <div className="mu" style={{ marginTop: 10 }}>이번 달 활동 <b style={{ color: '#1F2633' }}>{e.monthDone ?? 0}회</b> · 누적 {e.doneTotal ?? 0}회</div>
        </div>
      </div>

      {/* 이번 주 돌봄 */}
      <div className="pnl">
        <div className="ps-h"><b>이번 주 돌봄</b><button type="button" className="ps-a" onClick={() => nav(`/o/schedule?elder=${id}`)}>편성표에서 보기 ›</button></div>
        {sch === undefined ? <Loading /> : !sch || !days.length ? <div className="mu">편성표를 불러오지 못했어요</div> : (
          <div className="wk" style={{ gridTemplateColumns: `repeat(${days.length},minmax(0,1fr))` }}>
            {days.map((d) => {
              const c = row ? (row.cells || []).find((x) => x.date === d.date) : null;
              const items = (c && c.items) || [];
              return (
                <div key={d.date} className={'wk-c ' + (d.today ? 'today' : '')}>
                  <div className="wk-d">{d.dow} {Number(d.date.split('-')[2])}</div>
                  {items.map((it, i) => <button key={i} type="button" className="wk-i" onClick={() => nav(it.kind === 'POST' ? `/post/${it.postId}` : `/o/reqs/${it.requestId}`)}><b>{short(it.category)}</b> {hh(it.startHour)} · {it.volunteerName || SCH_STATE[it.state] || ''}</button>)}
                  {c && c.gap && <button type="button" className="wk-i gap" onClick={() => nav(`/o/schedule?view=d&day=${d.date}&elder=${id}`)}>● {short(c.gap.category)} 빈칸</button>}
                  {!items.length && !(c && c.gap) && <div className="wk-n">-</div>}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* 받은 도움 기록 · 담당자 메모 */}
      <div className="g2 g17">
        <div className="pnl">
          <div className="ps-h"><b>받은 도움 기록 · 요청 <span className="cnt">{myReqs.length}</span></b></div>
          {myReqs.length ? (
            <table className="tbl sm" aria-label="받은 도움 기록">
              <thead><tr><th scope="col">날짜</th><th scope="col">도움</th><th scope="col">상태</th><th scope="col">봉사자</th><th scope="col">내용 · 일지</th></tr></thead>
              <tbody>{shown.map((r) => (
                <tr key={r.id} tabIndex={0} onClick={() => nav(`/o/reqs/${r.id}`)} onKeyDown={(ev) => { if (ev.key === 'Enter') nav(`/o/reqs/${r.id}`); }}>
                  <td style={{ whiteSpace: 'nowrap' }}>{dlabel(r.date)}</td><td style={{ whiteSpace: 'nowrap' }}>{short(r.category)}</td><td><StTag5 st={r.status} kind={r.kind} /></td>
                  <td style={{ whiteSpace: 'nowrap' }}>{r.volunteer ? r.volunteer.name : '-'}</td><td className="ell">{r.logText || r.content || '-'}</td>
                </tr>
              ))}</tbody>
            </table>
          ) : <div className="pempty">진행 중인 요청이 없어요</div>}
          {myReqs.length > 6 && <button type="button" className="pmore" onClick={() => setMore(!more)}>{more ? '접기' : `+ ${myReqs.length - 6}건 더 보기`}</button>}
        </div>
        <div className="pnl">
          <div className="ps-h"><b>담당자 메모 <span className="mu" style={{ fontWeight: 500 }}>봉사자에게 안 보여요</span></b><button type="button" className="ps-a" onClick={() => (memos.length ? setTidyOpen(!tidyOpen) : toast('정리할 메모가 없어요'))}>AI 정리</button></div>
          {tidyOpen && memos.length > 0 && (
            <div className="ai" style={{ padding: '12px 14px', borderRadius: 16, boxShadow: 'none', marginBottom: 10 }}>
              <div className="ai-h">기록 정리 AI · 메모 {memos.length}건을 날짜순으로 정리했어요</div>
              <ul style={{ margin: '6px 0 0', paddingLeft: 18, fontSize: 13 }}>{memos.slice().reverse().map((m) => <li key={m.id}><span className="mu">{day1(m.createdAt)}</span> {(m.lines && m.lines.length ? m.lines : [m.text]).join(' · ')}</li>)}</ul>
              <div className="mu" style={{ marginTop: 8 }}>봉사자에게 보내는 정보에는 들어가지 않아요. 필요한 내용만 방문 주의사항에 따로 추가해요.</div>
              <RuleNote source={memos.some((m) => m.source === 'ai') ? 'ai' : 'rule'} />
            </div>
          )}
          <div className="memos">{memos.length ? memos.map((m) => <div key={m.id} className="memo"><span className="mu">{day1(m.createdAt)}</span> {m.text}</div>) : <div className="mu">메모가 없어요</div>}</div>
          <div className="prw mob" style={{ marginTop: 10 }}>
            <MicButton onText={(t) => setMemo((x) => (x.trim() ? `${x.trim()} ${t}` : t))} />
            <input id="elder-memo-in" className="pin" placeholder="특이사항·방문하고 느낀 점 (병명은 적지 않아요)" value={memo} onChange={(x) => setMemo(x.target.value)} aria-label="담당자 메모" />
            <button type="button" className="pbtn pri" disabled={busy} onClick={() => run(async () => { if (!memo.trim()) return toast('메모를 적어 주세요'); await post(`/org/elders/${id}/memos`, { text: memo.trim() }); setMemo(''); reload(); toast('메모를 저장하고 읽기 쉽게 정리했어요'); })}>추가</button>
          </div>
        </div>
      </div>

      {/* 변화 리포트 · 동의와 관리 */}
      <div className="g2">
        <div className="pnl mob">
          <div className="ps-h"><b>변화 리포트</b><button type="button" className="ps-a" onClick={() => nav(`/o/report?elder=${id}`)}>최근 활동 기록 · 월간 리포트 ›</button></div>
          {myChange && <ChangeCard x={myChange} />}
          <div style={{ marginTop: 10 }}>{rep ? <ReportView rep={rep} who={who} /> : <div className="pempty">아직 만들어진 리포트가 없어요</div>}</div>
        </div>
        <div className="pnl">
          <div className="ps-h"><b>동의 · 관리</b></div>
          <div className="li"><span className="li-i"><PIc k="check" /></span><div className="li-t">서비스 동의<small>{e.serviceConsentAt ? `받음 (${day1(e.serviceConsentAt)})` : '기록 없음'}</small></div>{!e.serviceConsentAt && !ended && <button type="button" className="pbtn" disabled={busy} onClick={consent}>동의 받음으로 기록</button>}</div>
          <div className="li click" role="switch" aria-checked={!!e.reportConsent} tabIndex={0}
            onClick={() => run(async () => { await put(`/org/elders/${id}/consent`, { reportConsent: !e.reportConsent }); await reload(); toast(!e.reportConsent ? '공유 동의를 입력했어요' : '공유 동의를 철회했어요'); })}
            onKeyDown={(ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); ev.currentTarget.click(); } }}>
            <span className="li-i"><PIc k="file" /></span><div className="li-t">리포트 공유 동의<small>대상에게 확인 후 대신 입력 · {e.reportConsent ? '동의함' : '동의 안 함'}</small></div><span className={'toggle ' + (e.reportConsent ? 'on' : '')} />
          </div>
          {!ended && <button type="button" className="li click" onClick={() => sheet(() => <DoorCardSheet elderId={id} name={who} manage onClose={() => { closeSheet(); reload(); }} />)}><span className="li-i"><PIc k="qr" /></span><div className="li-t">현관 QR 카드<small>{e.qrIssuedAt ? `${day1(e.qrIssuedAt)} 발급 · 보기·인쇄·다시 발급` : '출력해 현관 안쪽에 붙여 두면 봉사자가 찍어 시작·종료를 기록해요'}</small></div><span className="ar">›</span></button>}
          {!ended && <button type="button" className="li click" onClick={transfer}><span className="li-i"><PIc k="users" /></span><div className="li-t">다른 담당자에게 이관<small>받는 담당자가 수락해야 넘어가요</small></div><span className="ar">›</span></button>}
          {!ended && <button type="button" className="li click" onClick={endCare}><span className="li-i" style={{ background: '#FDECEA', color: '#E5655B' }}><PIc k="flag" /></span><div className="li-t" style={{ color: '#D0483E' }}>지원 종료<small>기록은 남고 새 요청은 할 수 없어요</small></div><span className="ar">›</span></button>}
          <div className="notebox" style={{ marginTop: 12 }}>봉사자에게는 가린 이름({maskN(e.alias)} 님)·동·도움 내용·시간까지만 보여요. 이름·주소·연락처는 앱에 저장하지 않아요.</div>
        </div>
      </div>
      <div className="mob d-extra"><BlockList elderId={id} />{!ended && <ElderClasses elderId={id} />}</div>
    </>
  );
}

/* ---------- 목록 340 + 상세 (O-CARE-01) + 등록·수정 패널(O-CARE-03) ---------- */
function OEldersPc({ form }) {
  const { id } = useParams();
  const nav = useNavigate();
  const { toast } = useUi();
  const { me } = useAuth();
  const [list, reloadList] = useLoad(() => api('/org/elders'), []);
  const [tr, reloadTr] = useLoad(() => api('/org/transfers', { quiet: true }), []);
  const sigs = useCareSignals();
  const [q, setQ] = useState('');
  const [fp, setFp] = useState('전체');
  const [ver, setVer] = useState(0);
  const [busy, run] = useBusy();
  const all = list || [];
  const live = all.filter((e) => e.status !== 'ENDED');
  const ended = all.filter((e) => e.status === 'ENDED');
  const flagged = (e) => e.flag || e.bigChange || (sigs[e.id] && sigs[e.id].flagged);
  const dongs = [...new Set(live.map((e) => e.dong).filter(Boolean))];
  const filters = ['전체', ...dongs, '살펴볼 분', ...(ended.length ? ['지원 종료'] : [])];
  const L = (fp === '지원 종료' ? ended : live.filter((e) => fp === '전체' || e.dong === fp || (fp === '살펴볼 분' && flagged(e)))).filter((e) => !q.trim() || String(e.alias || '').includes(q.trim()));
  const selId = id || (L[0] ? String(L[0].id) : live[0] ? String(live[0].id) : null);
  const isIn = (t) => (t.direction ? t.direction === 'in' : me && t.to && t.to.id === me.id);
  const incoming = (tr || []).filter((t) => t.status === 'PENDING' && isIn(t));
  const outgoing = (tr || []).filter((t) => t.status === 'PENDING' && !isIn(t));
  const tName = (t) => (t.elder && t.elder.alias) || t.elderAlias || '';
  const closeForm = () => (hasBack() ? nav(-1) : nav(id ? `/o/elders/${id}` : '/o/elders', { replace: true }));
  const saved = (nid) => { reloadList(); setVer((x) => x + 1); if (form === 'edit' && hasBack()) nav(-1); else nav(`/o/elders/${nid}`, { replace: true }); };
  return (
    <Screen title="관리 대상" wide stay>
      <div className="split">
        <div className="pnl lst">
          <input className="pin" style={{ width: '100%', marginBottom: 10 }} placeholder="별칭 검색" value={q} onChange={(e) => setQ(e.target.value)} aria-label="별칭 검색" />
          <div className="pchips wrap" style={{ marginBottom: 10 }}>{filters.map((x) => <button key={x} type="button" className={'pchip ' + (fp === x ? 'on' : '')} aria-pressed={fp === x} onClick={() => setFp(x)}>{x}</button>)}</div>
          {list === undefined ? <Loading /> : L.length ? L.map((x) => (
            <button key={x.id} type="button" className={'lrow ' + (String(x.id) === String(selId) ? 'on' : '')} aria-current={String(x.id) === String(selId) ? 'true' : undefined} onClick={() => nav(`/o/elders/${x.id}`, { replace: !!id })}>
              <Avc n={x.alias} cat="acc" size={32} />
              <span className="lr-t"><b>{elderName(x)}</b> <span className="mu">{x.ageGroup} · {x.dong}</span><small>{x.monthDone != null ? `이번 달 ${x.monthDone}회` : ''}{x.regularCount != null ? ` · 단골 ${x.regularCount}명` : x.regulars ? ` · 단골 ${x.regulars.length}명` : ''}</small></span>
              {x.status === 'ENDED' ? <span className="ptag">종료</span> : x.flag || x.bigChange ? <span className="ptag r">변화 큼</span> : sigs[x.id] && sigs[x.id].flagged ? <span className="ptag a">확인 권장</span> : null}
            </button>
          )) : <div className="pempty">{all.length ? '조건에 맞는 분이 없어요' : '등록된 대상이 없어요'}</div>}
          <button type="button" className="pbtn pri" style={{ width: '100%', marginTop: 12 }} onClick={() => nav('/o/elders/new')}><PIc k="plus" />관리 대상 등록</button>
          {(incoming.length > 0 || outgoing.length > 0) && <div className="lst-h">이관 요청</div>}
          {incoming.map((t) => (
            <div key={t.id} className="trbox">
              <div className="prw"><Avc n={tName(t)} cat="acc" size={32} /><span className="lr-t"><b>{tName(t)} 님</b>{t.elder && t.elder.dong ? <span className="mu"> · {t.elder.dong}</span> : null}<small>{t.from ? t.from.name : '운영자'} 담당자가 이관을 요청했어요</small></span></div>
              <div className="prw" style={{ marginTop: 8, justifyContent: 'flex-end', gap: 6 }}>
                <button type="button" className="pbtn sec2" disabled={busy} onClick={() => run(async () => { await post(`/org/transfers/${t.id}/answer`, { accept: false }); reloadTr(); toast('이관을 거절했어요'); })}>거절</button>
                <button type="button" className="pbtn pri" disabled={busy} onClick={() => run(async () => { await post(`/org/transfers/${t.id}/answer`, { accept: true }); reloadTr(); reloadList(); toast('이관받았어요'); })}>수락</button>
              </div>
            </div>
          ))}
          {outgoing.map((t) => <div key={t.id} className="trbox prw"><span className="lr-t"><b>{tName(t)} 님 → {t.to && t.to.name}</b><small>받는 담당자의 수락을 기다려요</small></span><span className="ptag a">대기</span></div>)}
          <div className="notebox" style={{ marginTop: 12 }}><PIc k="shield" />이름·주소·연락처는 입력하지 않아요(기관이 따로 보관). 별칭은 담당자 화면에만 보이고 봉사자에게는 가려서 보여요.</div>
        </div>
        <div className="dcol">
          {selId ? <ElderDetail key={`${selId}:${ver}`} id={selId} onChanged={() => { reloadList(); reloadTr(); }} /> : <div className="pnl pempty">{list === undefined ? '불러오는 중이에요' : '왼쪽에서 [관리 대상 등록]을 눌러 첫 대상을 등록해 주세요'}</div>}
        </div>
      </div>
      {form && (
        <PcDrawer title={form === 'edit' ? '관리 대상 정보 수정' : '관리 대상 등록'} onClose={closeForm}>
          <div className="mob"><ElderFormBody key={form === 'edit' ? id : 'new'} id={form === 'edit' ? id : undefined} onSaved={saved} onCancel={closeForm} /></div>
        </PcDrawer>
      )}
    </Screen>
  );
}

/** 네 경로(/o/elders · /:id · /new · /:id/edit)가 같은 컴포넌트를 쓰게 해서, PC 에서 목록·상세 사이를 오가도 화면이 새로 만들어지지 않게 한다. */
export function Elders({ m, form }) {
  const pc = usePc();
  if (pc) return <OEldersPc form={form} />;
  return m === 'list' ? <OEldersMobile /> : m === 'detail' ? <EDetailMobile /> : <EFormMobile />;
}
