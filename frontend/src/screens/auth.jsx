import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Screen, { useDemoSheet } from '../ui/Frame.jsx';
import { Ic, Logo, ChipsPick, Upload, useBusy } from '../ui/bits.jsx';
import { useAuth, useUi, HOME_OF } from '../ui/ctx.jsx';
import { api, post } from '../api.js';
import { firebaseConfigured, sendSms, confirmSms, clearSms, smsErrorText } from '../lib/firebasePhone.js';
import { AREAS, AGE_GROUPS, CATS, SHORT, TIME_SLOTS } from '../lib/format.js';

/* ---------- 스플래시 ---------- */
export function Splash() {
  const nav = useNavigate();
  const { me, ready } = useAuth();
  useEffect(() => {
    if (!ready) return undefined;
    const t = setTimeout(() => nav(me ? HOME_OF[me.role] || '/login' : '/login', { replace: true }), me ? 600 : 1800);
    return () => clearTimeout(t);
  }, [ready, me, nav]);
  return <Screen noHeader noTabs cls="full splash"><div className="sp-in"><div className="sp-icon"><Logo /></div><div className="sp-word">시간 적금</div></div></Screen>;
}

/* ---------- 온보딩 (프로토타입 login) ---------- */
const P_ = (x, y, o = {}) => { const sk = o.skin || '#F2C9A8', top = o.top || '#3D6DF2', bot = o.bot || '#1F2A44', hair = o.hair || '#2B2B2B'; return `<g transform="translate(${x} ${y})"><path d="M-9 58 L-12 92 M9 58 L13 92" stroke="${bot}" stroke-width="9" stroke-linecap="round"/><path d="M-14 92h8M9 92h9" stroke="#222" stroke-width="5" stroke-linecap="round"/><rect x="-16" y="14" width="32" height="48" rx="13" fill="${top}"/><path d="M-14 22 L-22 50 M14 22 L22 50" stroke="${top}" stroke-width="8" stroke-linecap="round"/><circle cx="0" cy="0" r="12" fill="${sk}"/>${o.gray ? '<path d="M-12 -2a12 12 0 0 1 24 0c-4-6-20-6-24 0z" fill="#D9D9D9"/>' : `<path d="M-12 -1a12 12 0 0 1 24 0c-6-8-18-8-24 0z" fill="${hair}"/>`}${o.cane ? '<path d="M26 48 L30 94" stroke="#8A6B4E" stroke-width="3.5" stroke-linecap="round"/>' : ''}${o.bag ? '<rect x="22" y="44" width="18" height="20" rx="3" fill="#FFD66B"/><path d="M26 44v-4a5 5 0 0 1 10 0v4" stroke="#E0A92E" stroke-width="2.5" fill="none"/>' : ''}</g>`; };
const ILL = [
  `<svg viewBox="0 0 260 170"><ellipse cx="130" cy="160" rx="110" ry="8" fill="#EEF2FB"/><circle cx="210" cy="38" r="16" fill="#FFE29A"/>${P_(95, 58, { bag: 1 })}${P_(160, 62, { top: '#F28C6B', bot: '#5A4A42', gray: 1, cane: 1 })}</svg>`,
  '<svg viewBox="0 0 260 170"><ellipse cx="130" cy="160" rx="110" ry="8" fill="#EEF2FB"/><rect x="40" y="40" width="180" height="40" rx="12" fill="#fff" stroke="#DCE5FD" stroke-width="2"/><rect x="56" y="56" width="148" height="8" rx="4" fill="#EDF0F5"/><rect x="100" y="56" width="70" height="8" rx="4" fill="#BCD0FB"/><rect x="120" y="53" width="34" height="14" rx="5" fill="#3D6DF2"/><rect x="40" y="94" width="180" height="40" rx="12" fill="#fff" stroke="#DCE5FD" stroke-width="2"/><circle cx="64" cy="114" r="10" fill="#E6F7EE"/><path d="M59 114l4 4 7-8" stroke="#1F9D63" stroke-width="3" fill="none" stroke-linecap="round"/><rect x="84" y="108" width="90" height="10" rx="5" fill="#EDF0F5"/></svg>',
  '<svg viewBox="0 0 260 170"><ellipse cx="130" cy="160" rx="110" ry="8" fill="#EEF2FB"/><rect x="58" y="34" width="144" height="96" rx="14" fill="#3D6DF2"/><rect x="74" y="50" width="22" height="16" rx="4" fill="#FFD66B"/><rect x="74" y="84" width="70" height="10" rx="5" fill="#fff" opacity=".9"/><rect x="74" y="102" width="44" height="8" rx="4" fill="#fff" opacity=".55"/><circle cx="190" cy="118" r="30" fill="#fff" stroke="#3D6DF2" stroke-width="5"/><path d="M190 102v16l10 7" stroke="#3D6DF2" stroke-width="5" stroke-linecap="round" fill="none"/></svg>',
];
const SLIDES = [['비는 시간에<br>이웃을 도와요', '“금요일 1시~5시 비어요” 공고를 올리면<br>필요한 이웃이 요청을 보내요'], ['알림 보고<br>내가 직접 골라요', '자동 매칭 없이 요청 내용을 보고<br>수락·거절을 직접 정해요'], ['도운 시간은<br>통장에 쌓여요', '쌓인 시간으로 나도 도움을 받고<br>가족에게 선물할 수 있어요']];

export function Onboard() {
  const nav = useNavigate();
  const demo = useDemoSheet();
  const [i, setI] = useState(0);
  const [fade, setFade] = useState(false);
  const goI = (n) => { setFade(true); setTimeout(() => { setI(n); setFade(false); }, 260); };
  useEffect(() => { const t = setInterval(() => goI((i + 1) % 3), 3200); return () => clearInterval(t); }, [i]);
  return (
    <Screen noHeader noTabs cls="full ob">
      <div className="ob-stage">
        <div className={'ob-text' + (fade ? ' fade' : '')}><h2 className="ob-h" dangerouslySetInnerHTML={{ __html: SLIDES[i][0] }} /><p className="ob-p" dangerouslySetInnerHTML={{ __html: SLIDES[i][1] }} /></div>
        <div className={'ob-ill' + (fade ? ' fade' : '')} dangerouslySetInnerHTML={{ __html: ILL[i] }} />
        <div className="ob-dots">{SLIDES.map((_, k) => <i key={k} className={k === i ? 'on' : ''} onClick={() => goI(k)} />)}</div>
      </div>
      <p className="ob-note">돈은 오가지 않아요. 도운 시간으로만 쌓여요.</p>
      <div className="ob-btns"><button className="btn ob-login" onClick={() => nav('/login/form')}>로그인</button><button className="btn ob-sign" onClick={() => nav('/signup')}>회원가입</button></div>
      <button className="au-demo" onClick={demo}>시연용 계정으로 둘러보기</button>
    </Screen>
  );
}

/* ---------- 로그인 ---------- */
export function LoginForm() {
  const nav = useNavigate();
  const { login } = useAuth();
  const { toast } = useUi();
  const [id, setId] = useState('');
  const [pw, setPw] = useState('');
  const [keep, setKeep] = useState(true);
  const [busy, run] = useBusy();
  const doLogin = (lid, lpw) => run(async () => {
    const u = await login(lid, lpw, keep);
    toast(`${u.name} 님, 반가워요`);
    nav(HOME_OF[u.role] || '/', { replace: true });
  });
  return (
    <Screen noTabs cls="lf">
      <div className="lf-top"><div className="lf-mark"><Logo /></div><h2 className="su-h">아이디로<br />로그인해 주세요</h2><p className="su-p">사용자·담당자·운영자 모두 여기서 로그인해요.</p></div>
      <form className="au-form" onSubmit={(e) => { e.preventDefault(); if (!id || !pw) return toast('아이디와 비밀번호를 적어 주세요'); doLogin(id.trim(), pw); }}>
        <div className="fl"><label htmlFor="lid">아이디</label><input id="lid" className="in" placeholder="아이디" autoComplete="username" value={id} onChange={(e) => setId(e.target.value)} /></div>
        <div className="fl"><label htmlFor="lpw">비밀번호</label><input id="lpw" className="in" type="password" placeholder="비밀번호" autoComplete="current-password" value={pw} onChange={(e) => setPw(e.target.value)} /></div>
        <div className="lf-row"><label className="lf-keep"><input type="checkbox" checked={keep} onChange={(e) => setKeep(e.target.checked)} /> 로그인 유지</label><a role="button" tabIndex={0} onClick={() => nav('/pw-reset')}>비밀번호 찾기</a></div>
        <button className="btn big-cta" type="submit" disabled={busy}>{busy ? '로그인 중…' : '로그인'}</button>
      </form>
      <div className="au-or" style={{ margin: '26px 0 12px' }}>데모 계정으로 빠른 로그인</div>
      <div className="btns">
        {[['yujin', '사용자'], ['org1', '담당자'], ['admin', '운영자']].map(([lid, l]) => <button key={lid} className="btn ghost sm" disabled={busy} onClick={() => doLogin(lid, '1234')}>{l}<span className="mute2" style={{ fontSize: 12 }}>{lid}</span></button>)}
      </div>
      <p className="lf-sign">아직 회원이 아니신가요? <a role="button" tabIndex={0} onClick={() => nav('/signup', { replace: true })}>회원가입</a></p>
      <div className="tip" style={{ marginTop: 22 }}><div className="grow"><div className="b">주민센터·복지관이신가요?</div><div className="mute2">기관 신청이 승인되면 담당자 계정을 드려요</div></div><button className="btn soft sm" style={{ width: 'auto', padding: '0 14px' }} onClick={() => nav('/org-apply')}>기관 신청</button></div>
    </Screen>
  );
}

/* ---------- 휴대폰 본인인증: Firebase 실제 문자 + 프로토타입 데모 코드 123456 (A-01, A-03) ---------- */
const DEMO_CODE = '123456';
function PhoneVerify({ phone, setPhone, onVerified, verified }) {
  const { toast } = useUi();
  const [cfg, setCfg] = useState(null); // {firebase, demo}
  const [stage, setStage] = useState('phone'); // phone | code
  const [code, setCode] = useState('');
  const [demoShown, setDemoShown] = useState(''); // Firebase 꺼짐: 서버가 알려 준 테스트 코드
  const [fbErr, setFbErr] = useState('');
  const [sending, setSending] = useState(false);
  const conf = useRef(null);
  const capRef = useRef(null);
  useEffect(() => {
    let on = true;
    api('/auth/phone/config', { quiet: true }).then((c) => on && setCfg(c || {})).catch(() => on && setCfg({ firebase: false, demo: true }));
    return () => { on = false; clearSms(); };
  }, []);
  const useFb = !!(cfg && cfg.firebase && firebaseConfigured);
  const demoOn = !cfg || cfg.demo !== false;

  const send = async () => {
    if (!/^01\d-?\d{3,4}-?\d{4}$/.test(phone.trim())) return toast('휴대폰 번호를 확인해 주세요');
    setFbErr('');
    if (useFb) {
      setSending(true);
      try {
        conf.current = await sendSms(phone.trim(), capRef.current);
        setStage('code');
        toast('인증번호 문자를 보냈어요');
      } catch (e) {
        conf.current = null;
        setFbErr(smsErrorText(e));
        if (demoOn) setStage('code');
      } finally { setSending(false); }
      return;
    }
    try { const r = await post('/auth/phone/send', { phone: phone.trim() }); setDemoShown(r.demoCode || ''); setStage('code'); toast('인증번호를 보냈어요'); } catch { /* 토스트 */ }
  };
  const verify = async () => {
    const c = code.trim();
    if (!/^\d{6}$/.test(c)) return toast('인증번호 6자리를 넣어 주세요');
    try {
      let r;
      if (demoOn && c === DEMO_CODE) r = await post('/auth/phone/verify', { phone: phone.trim(), code: c });
      else if (useFb && conf.current) {
        let token;
        try { token = await confirmSms(conf.current, c); } catch (e) { return toast(smsErrorText(e)); }
        r = await post('/auth/phone/verify', { phone: phone.trim(), firebaseIdToken: token });
      } else r = await post('/auth/phone/verify', { phone: phone.trim(), code: c });
      conf.current = null; clearSms();
      onVerified(r.verifyToken);
      toast('본인인증이 끝났어요');
    } catch { /* 토스트 */ }
  };
  return (
    <>
      <label className="f" htmlFor="ph">휴대폰 번호</label>
      <div className="row"><input id="ph" className="in grow" placeholder="010-0000-0000" inputMode="tel" autoComplete="tel" value={phone} disabled={verified} onChange={(e) => { setPhone(e.target.value); if (stage === 'code') { setStage('phone'); conf.current = null; } }} /><button type="button" className="btn soft sm" style={{ width: 'auto', flex: '0 0 auto', padding: '0 16px' }} disabled={verified || sending} onClick={send}>{sending ? '보내는 중…' : stage === 'code' ? '다시 받기' : '인증번호 받기'}</button></div>
      <div ref={capRef} />
      {fbErr && !verified && <div className="notice" style={{ marginTop: 8 }} role="alert">{fbErr}{demoOn ? <> 프로토타입 테스트용 인증번호 <b>{DEMO_CODE}</b>를 넣으면 계속할 수 있어요.</> : ' 잠시 뒤 다시 시도해 주세요.'}</div>}
      {(stage === 'code' || (useFb && demoOn)) && !verified && (
        <>
          <label className="f" htmlFor="cd">인증번호</label>
          <div className="row"><input id="cd" className="in grow" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="6자리" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} /><button type="button" className="btn sm" style={{ width: 'auto', flex: '0 0 auto', padding: '0 16px' }} onClick={verify}>확인</button></div>
          {!useFb && demoShown && <div className="notice" style={{ marginTop: 8 }}>테스트용 인증번호: <b>{demoShown}</b> (실제 문자는 보내지 않아요)</div>}
        </>
      )}
      {useFb && demoOn && !verified && <div className="mute2" style={{ marginTop: 6, fontSize: 12.5 }}>프로토타입 테스트: {DEMO_CODE} 입력으로도 인증돼요</div>}
      {verified && <div className="mute2 ok" style={{ marginTop: 8 }}>✓ 본인인증 완료</div>}
    </>
  );
}

/* ---------- 회원가입 ---------- */
export function Signup() {
  const nav = useNavigate();
  const { loginWithToken } = useAuth();
  const { toast } = useUi();
  const [st, setSt] = useState(1);
  const [f, setF] = useState({ name: '', phone: '', loginId: '', password: '', password2: '', area: '행복동', ageGroup: '20대', categories: [], timeSlots: [], intro: '' });
  const [vt, setVt] = useState(null);
  const [agree, setAgree] = useState([0, 0, 0, 0]);
  const [busy, run] = useBusy();
  const set = (k) => (e) => setF({ ...f, [k]: e && e.target ? e.target.value : e });
  const head = <div className="su-prog"><i style={{ width: `${Math.min(st, 3) * 33.4}%` }} /></div>;
  const next = () => {
    if (st === 1) { if (!f.name.trim()) return toast('이름을 적어 주세요'); if (!vt) return toast('휴대폰 본인인증을 해 주세요'); }
    if (st === 2) {
      if (!/^[a-zA-Z0-9]{4,}$/.test(f.loginId)) return toast('아이디는 영문·숫자 4자 이상이에요');
      if (f.password.length < 4) return toast('비밀번호를 4자 이상 적어 주세요');
      if (f.password !== f.password2) return toast('비밀번호가 서로 달라요');
    }
    setSt(st + 1);
  };
  const done = () => run(async () => {
    const r = await post('/auth/signup', { loginId: f.loginId.trim(), password: f.password, name: f.name.trim(), phone: f.phone.trim(), verifyToken: vt, area: f.area, ageGroup: f.ageGroup, categories: f.categories, timeSlots: f.timeSlots, intro: f.intro });
    await loginWithToken(r.token);
    setSt(4);
  });
  const all = agree.every(Boolean);
  if (st === 4) {
    return (
      <Screen noHeader noTabs cls="full">
        <div className="sp-in" style={{ color: 'var(--ink)' }}><div className="done-ic">✓</div><h2 className="su-h center" style={{ marginTop: 20 }}>가입을 환영해요</h2><p className="su-p center">도움 받기는 바로 할 수 있어요.<br />봉사를 하려면 신원 인증과 안전 교육을 먼저 마쳐 주세요.</p></div>
        <div className="su-bottom stack"><button className="btn big-cta" onClick={() => { toast('가입이 완료됐어요'); nav('/u/home', { replace: true }); }}>시작하기</button><button className="btn ghost" onClick={() => { nav('/u/home', { replace: true }); nav('/my/verify'); }}>봉사 신원 인증 하러 가기</button></div>
      </Screen>
    );
  }
  return (
    <Screen title="회원가입" noTabs>
      {head}
      {st === 1 && (
        <>
          <h2 className="su-h">휴대폰으로<br />본인인증을 해 주세요</h2><p className="su-p">한 계정으로 봉사도 하고, 도움도 받을 수 있어요.</p>
          <label className="f" htmlFor="nm">이름</label><input id="nm" className="in" placeholder="이름" value={f.name} onChange={set('name')} />
          <PhoneVerify phone={f.phone} setPhone={(v) => setF({ ...f, phone: v })} onVerified={setVt} verified={!!vt} />
          <div className="notice" style={{ marginTop: 16 }}>담당자·운영자 계정은 가입하지 않고 따로 발급해요. 담당자나 운영자가 봉사도 하려면 사용자 계정을 하나 더 만들어요.</div>
        </>
      )}
      {st === 2 && (
        <>
          <h2 className="su-h">계정 정보를 만들어요</h2>
          <label className="f" htmlFor="li">아이디</label><input id="li" className="in" placeholder="영문·숫자 4자 이상" value={f.loginId} onChange={set('loginId')} autoComplete="username" />
          <label className="f" htmlFor="p1">비밀번호</label><input id="p1" className="in" type="password" placeholder="4자 이상" value={f.password} onChange={set('password')} autoComplete="new-password" />
          <label className="f" htmlFor="p2">비밀번호 확인</label><input id="p2" className="in" type="password" placeholder="한 번 더 입력" value={f.password2} onChange={set('password2')} autoComplete="new-password" />
          <label className="f">사는 동네</label><select className="in" value={f.area} onChange={set('area')}>{AREAS.map((a) => <option key={a} value={a}>행복구 {a}</option>)}</select>
          <label className="f">나이대</label><select className="in" value={f.ageGroup} onChange={set('ageGroup')}>{AGE_GROUPS.map((a) => <option key={a}>{a}</option>)}</select>
          <label className="f">할 수 있는 도움 (선택)</label><ChipsPick multi options={CATS.map((c) => c.k)} value={f.categories} onChange={(v) => setF({ ...f, categories: v })} label={(k) => SHORT[k]} />
          <label className="f">가능한 시간대 (선택)</label><ChipsPick multi options={TIME_SLOTS} value={f.timeSlots} onChange={(v) => setF({ ...f, timeSlots: v })} />
          <label className="f" htmlFor="it">한 줄 소개 (선택)</label><input id="it" className="in" placeholder="예) 퇴근 후·주말에 시간 있어요" value={f.intro} onChange={set('intro')} />
        </>
      )}
      {st === 3 && (
        <>
          <h2 className="su-h">약관에 동의해 주세요</h2>
          <div className={'su-check all ' + (all ? 'on' : '')} role="button" tabIndex={0} onClick={() => setAgree(agree.map(() => (all ? 0 : 1)))}><span className="bx" /><div className="b">전체 동의</div></div>
          {['[필수] 서비스 이용약관', '[필수] 개인정보 수집·이용', '[필수] 위치 정보 이용 (근처 공고)', '[선택] 관심 공고 알림 받기'].map((t, i) => (
            <div key={t} className={'su-check ' + (agree[i] ? 'on' : '')} role="button" tabIndex={0} onClick={() => setAgree(agree.map((v, k) => (k === i ? (v ? 0 : 1) : v)))}><span className="bx" /><div className="grow">{t}</div><span className="mute2">보기</span></div>
          ))}
          <div className="notice" style={{ marginTop: 16 }}>시간은 돈으로 사고팔 수 없고, 가족 선물 외에는 넘길 수 없어요.</div>
        </>
      )}
      <div className="su-bottom">
        {st < 3 ? <button className="btn big-cta" onClick={next}>다음</button> : <button className="btn big-cta" disabled={!agree.slice(0, 3).every(Boolean) || busy} onClick={done}>{busy ? '가입하는 중…' : '가입하기'}</button>}
        {st > 1 && <button className="au-demo" onClick={() => setSt(st - 1)}>이전 단계로</button>}
      </div>
    </Screen>
  );
}

/* ---------- 비밀번호 찾기 (A-03) ---------- */
export function PwReset() {
  const nav = useNavigate();
  const { toast } = useUi();
  const [loginId, setLoginId] = useState('');
  const [phone, setPhone] = useState('');
  const [vt, setVt] = useState(null);
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [busy, run] = useBusy();
  return (
    <Screen title="비밀번호 찾기" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>휴대폰 본인인증 후 새 비밀번호를 정해요.</p>
      <label className="f" htmlFor="rid">아이디</label><input id="rid" className="in" value={loginId} onChange={(e) => setLoginId(e.target.value)} placeholder="아이디" />
      <PhoneVerify phone={phone} setPhone={setPhone} onVerified={setVt} verified={!!vt} />
      {vt && (
        <>
          <label className="f" htmlFor="np">새 비밀번호</label><input id="np" className="in" type="password" value={pw} onChange={(e) => setPw(e.target.value)} placeholder="4자 이상" />
          <label className="f" htmlFor="np2">새 비밀번호 확인</label><input id="np2" className="in" type="password" value={pw2} onChange={(e) => setPw2(e.target.value)} placeholder="한 번 더" />
        </>
      )}
      <button className="btn big-cta" style={{ marginTop: 20 }} disabled={!vt || busy} onClick={() => run(async () => {
        if (!loginId.trim()) return toast('아이디를 적어 주세요');
        if (pw.length < 4 || pw !== pw2) return toast('새 비밀번호를 확인해 주세요');
        await post('/auth/password/reset', { loginId: loginId.trim(), phone: phone.trim(), verifyToken: vt, newPassword: pw });
        toast('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요');
        nav('/login/form', { replace: true });
      })}>비밀번호 바꾸기</button>
    </Screen>
  );
}

/* ---------- 기관 서비스 신청 (C-01) ---------- */
export function OrgApply() {
  const nav = useNavigate();
  const { toast, confirm } = useUi();
  const [f, setF] = useState({ name: '', orgType: '주민센터', address: '', phone: '', managerName: '', managerPhone: '', managerEmail: '', memo: '' });
  const [file, setFile] = useState(null);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });
  return (
    <Screen title="기관 서비스 신청" noTabs>
      <p className="su-p" style={{ marginTop: 0 }}>주민센터·복지관 등 기관이 신청하면 운영자가 검토 후 담당자 계정을 발급해요. 도움 대상 등록은 담당자만 할 수 있어요.</p>
      <label className="f">기관 이름</label><input className="in" placeholder="예) 행복동 주민센터" value={f.name} onChange={set('name')} />
      <label className="f">기관 종류</label><select className="in" value={f.orgType} onChange={set('orgType')}><option>주민센터</option><option>노인복지관</option><option>노인맞춤돌봄 수행기관</option></select>
      <label className="f">기관 주소</label><input className="in" placeholder="주소" value={f.address} onChange={set('address')} />
      <label className="f">기관 전화</label><input className="in" placeholder="02-000-0000" value={f.phone} onChange={set('phone')} />
      <label className="f">담당자 이름</label><input className="in" placeholder="이름" value={f.managerName} onChange={set('managerName')} />
      <label className="f">담당자 연락처 (기관 전화)</label><input className="in" placeholder="02-000-0000" value={f.managerPhone} onChange={set('managerPhone')} />
      <label className="f">담당자 이메일</label><input className="in" placeholder="name@example.go.kr" value={f.managerEmail} onChange={set('managerEmail')} />
      <label className="f">재직 증빙 (필수)</label>
      <input className="in" type="file" accept="image/*,.pdf" aria-label="재직증명서 파일" onChange={(e) => setFile((e.target.files && e.target.files[0]) || null)} />
      <div className="mute2" style={{ marginTop: 6 }}>{file ? `${file.name} · 신청할 때 함께 보내요` : '재직증명서 사진(jpg·png)이나 PDF를 올려 주세요. 운영자가 확인한 뒤 승인해요.'}</div>
      <button className="btn big-cta" style={{ marginTop: 20 }} onClick={async () => {
        if (!f.name.trim() || !f.managerName.trim()) return toast('기관 이름과 담당자 이름을 적어 주세요');
        if (!file) return toast('재직 증빙(재직증명서) 파일을 올려 주세요');
        if (!(await confirm('기관 서비스를 신청할까요?', '운영자가 재직 증빙을 확인한 뒤 담당자 계정(아이디·임시 비밀번호)을 기관 전화로 안내해요.', '신청하기'))) return;
        // 로그인 전이라 신청 내용과 증빙 파일을 한 번에(multipart) 보낸다
        const fd = new FormData();
        Object.entries(f).forEach(([k, v]) => { if (v) fd.append(k, v); });
        fd.append('proof', file);
        try { await api('/orgs/apply', { method: 'POST', form: fd }); nav(-1); toast('신청했어요. 보통 3일 안에 결과가 나와요'); } catch { /* 토스트 */ }
      }}>신청하기</button>
    </Screen>
  );
}

