import { useCallback, useEffect, useRef, useState } from 'react';
import { aiStatus, transcribe } from '../api.js';
import { useUi } from './ctx.jsx';
import { Ic } from './bits.jsx';
import { I } from './icons.js';

/*
 * 공용 음성 입력.
 * ① 브라우저 받아쓰기(SpeechRecognition)가 있으면 그걸로 실시간 글자
 * ② 없거나 사용자가 "서버 음성 인식"을 고르면 MediaRecorder 로 녹음 → /api/stt → 글자 채움
 * ③ 둘 다 안 되면(서버 stt=false) 입력칸에 직접 적도록 안내
 * 마이크 권한은 버튼을 누를 때만 요청한다. 녹음은 1분에서 자동으로 멈춘다.
 */
const SR = typeof window !== 'undefined' ? window.SpeechRecognition || window.webkitSpeechRecognition : null;
export const speechSupported = !!SR;
const MAX_SEC = 60;

export function getSttServer() { try { return localStorage.getItem('ts-stt-server') === '1'; } catch { return false; } }
export function setSttServer(v) { try { localStorage.setItem('ts-stt-server', v ? '1' : '0'); } catch { /* 무시 */ } }
export const canRecord = () => typeof window !== 'undefined' && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.MediaRecorder);
const quietTts = () => { try { window.speechSynthesis && window.speechSynthesis.cancel(); } catch { /* 무시 */ } };
const pickMime = () => {
  if (!window.MediaRecorder || !MediaRecorder.isTypeSupported) return '';
  return ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'].find((t) => MediaRecorder.isTypeSupported(t)) || '';
};
const extOf = (mime) => (/ogg/.test(mime) ? 'ogg' : /mp4|m4a/.test(mime) ? 'm4a' : 'webm');

/** 서버 음성 인식을 쓸 수 있는지(서버 키 있음) — 화면 표시용 */
export function useSttAvailable() {
  const [stt, setStt] = useState(null);
  useEffect(() => { let on = true; aiStatus().then((s) => on && setStt(!!(s && s.stt))); return () => { on = false; }; }, []);
  return stt;
}

/**
 * 받아쓰기 훅. 반환: { supported, mode('browser'|'server'|'none'), listening, transcribing, seconds, interim, msg, start, stop, finish, toggle }
 * onFinal(전체 글) · onInterim(지금까지 글, 브라우저 모드만). toastErrors=false 면 msg 만 채우고 토스트는 띄우지 않는다.
 */
export function useSpeechInput({ onFinal, onInterim, toastErrors = true } = {}) {
  const ui = useUi();
  const stt = useSttAvailable();
  const [pref, setPref] = useState(getSttServer());
  const [listening, setListening] = useState(false);
  const [transcribing, setTranscribing] = useState(false);
  const [seconds, setSeconds] = useState(0);
  const [interim, setInterim] = useState('');
  const [msg, setMsgState] = useState('');
  const [denied, setDenied] = useState(false);
  const rec = useRef(null);
  const media = useRef(null); // { mr, stream, chunks, cancel, timers }
  const finalText = useRef('');
  const cb = useRef({ onFinal, onInterim });
  cb.current = { onFinal, onInterim };
  const toastRef = useRef(null);
  toastRef.current = toastErrors && ui ? ui.toast : null;
  const setMsg = useCallback((m) => { setMsgState(m); if (m && toastRef.current) toastRef.current(m); }, []);

  // 설정 화면에서 바꾸면 바로 따라간다
  useEffect(() => {
    const f = () => setPref(getSttServer());
    window.addEventListener('ts-stt-pref', f);
    return () => window.removeEventListener('ts-stt-pref', f);
  }, []);

  const serverOk = !!stt && canRecord();
  const mode = denied ? 'none' : SR && !(pref && serverOk) ? 'browser' : serverOk ? 'server' : 'none';

  /* ---- ① 브라우저 받아쓰기 ---- */
  const startBrowser = useCallback(() => {
    try {
      const r = new SR();
      r.lang = 'ko-KR'; r.interimResults = true; r.continuous = false; r.maxAlternatives = 1;
      finalText.current = '';
      r.onresult = (e) => {
        let fin = '', mid = '';
        for (let i = 0; i < e.results.length; i++) {
          const t = e.results[i][0].transcript;
          if (e.results[i].isFinal) fin += t; else mid += t;
        }
        finalText.current = fin;
        const all = (fin + mid).trim();
        setInterim(all);
        cb.current.onInterim && cb.current.onInterim(all);
      };
      r.onerror = (e) => {
        if (e.error === 'not-allowed' || e.error === 'service-not-allowed') { setDenied(true); setMsg('마이크 권한이 꺼져 있어요. 직접 적어 주세요'); }
        else if (e.error === 'no-speech') setMsg('말소리가 들리지 않았어요. 다시 눌러 말씀해 주세요');
        else if (e.error !== 'aborted') setMsg('음성 인식이 잠깐 안 됐어요. 다시 눌러 주세요');
      };
      r.onend = () => {
        setListening(false);
        const t = (finalText.current || '').trim();
        rec.current = null;
        if (t) cb.current.onFinal && cb.current.onFinal(t);
      };
      rec.current = r;
      setMsgState(''); setInterim('');
      r.start();
      setListening(true);
      return true;
    } catch { setMsg('음성 인식을 시작하지 못했어요'); return false; }
  }, [setMsg]);

  /* ---- ② 녹음 → 서버 음성 인식 ---- */
  const startServer = useCallback(async () => {
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      if (e && (e.name === 'NotAllowedError' || e.name === 'SecurityError')) { setDenied(true); setMsg('마이크 권한이 꺼져 있어요. 직접 적어 주세요'); }
      else setMsg(e && e.name === 'NotFoundError' ? '마이크를 찾을 수 없어요. 직접 적어 주세요' : '마이크를 켜지 못했어요. 직접 적어 주세요');
      return false;
    }
    const mime = pickMime();
    let mr;
    try { mr = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined); } catch { stream.getTracks().forEach((t) => t.stop()); setMsg('이 기기에서는 녹음이 안 돼요. 직접 적어 주세요'); return false; }
    const m = { mr, stream, chunks: [], cancel: false, timers: [] };
    media.current = m;
    mr.ondataavailable = (e) => { if (e.data && e.data.size) m.chunks.push(e.data); };
    mr.onstop = async () => {
      m.timers.forEach(clearInterval); m.timers.forEach(clearTimeout);
      m.stream.getTracks().forEach((t) => t.stop());
      if (media.current === m) media.current = null;
      setListening(false);
      if (m.cancel) return;
      const type = mr.mimeType || mime || 'audio/webm';
      const blob = new Blob(m.chunks, { type });
      if (!blob.size) { setMsg('녹음된 소리가 없어요. 다시 눌러 말씀해 주세요'); return; }
      setTranscribing(true);
      try {
        const r = await transcribe(blob, `voice.${extOf(type)}`);
        const t = String((r && r.text) || '').trim();
        if (!t) { setMsg('말소리가 들리지 않았어요. 다시 눌러 말씀해 주세요'); return; }
        setInterim(t);
        cb.current.onInterim && cb.current.onInterim(t);
        cb.current.onFinal && cb.current.onFinal(t);
      } catch (e) {
        setMsg((e && e.message) || '음성을 글자로 바꾸지 못했어요. 직접 적어 주세요');
      } finally { setTranscribing(false); }
    };
    setMsgState(''); setInterim(''); setSeconds(0);
    mr.start(1000);
    setListening(true);
    const t0 = Date.now();
    m.timers.push(setInterval(() => setSeconds(Math.min(MAX_SEC, Math.round((Date.now() - t0) / 1000))), 500));
    m.timers.push(setTimeout(() => { if (mr.state === 'recording') mr.stop(); }, MAX_SEC * 1000));
    return true;
  }, [setMsg]);

  const start = useCallback(() => {
    if (transcribing) return false;
    if (mode === 'none') {
      setMsg(denied ? '마이크 권한이 꺼져 있어요. 직접 적어 주세요' : '이 기기에서는 음성 입력이 안 돼요. 직접 적어 주세요');
      return false;
    }
    quietTts();
    if (mode === 'browser') return startBrowser();
    startServer();
    return true;
  }, [mode, denied, transcribing, startBrowser, startServer, setMsg]);

  /** 취소(녹음은 보내지 않음) */
  const stop = useCallback(() => {
    try { rec.current && rec.current.stop(); } catch { /* 무시 */ }
    const m = media.current;
    if (m) { m.cancel = true; try { m.mr.state !== 'inactive' && m.mr.stop(); } catch { /* 무시 */ } }
  }, []);
  /** "다 말했어요": 브라우저는 지금까지 들은 글로, 서버는 녹음을 보내 글자로 */
  const finish = useCallback(() => {
    if (rec.current) { if (!finalText.current && interim) finalText.current = interim; try { rec.current.stop(); } catch { /* 무시 */ } return; }
    const m = media.current;
    if (m) { try { m.mr.state !== 'inactive' && m.mr.stop(); } catch { /* 무시 */ } }
  }, [interim]);
  const toggle = useCallback(() => (listening ? finish() : start()), [listening, finish, start]);

  useEffect(() => () => {
    try { rec.current && rec.current.abort(); } catch { /* 무시 */ }
    const m = media.current;
    if (m) { m.cancel = true; try { m.mr.state !== 'inactive' && m.mr.stop(); } catch { /* 무시 */ } m.stream.getTracks().forEach((t) => t.stop()); }
    quietTts();
  }, []);

  return { supported: mode !== 'none', mode, serverAvailable: serverOk, listening, transcribing, seconds, interim, msg, start, stop, finish, toggle };
}

/**
 * 프로토타입 .mic 모양 마이크 버튼. onText(받은 글) — 보통 기존 글 뒤에 붙인다.
 * small=true 면 입력칸 안 오른쪽 아래에 들어가는 작은 버튼.
 */
export function MicButton({ onText, onInterim, small = false, label = '음성 입력', disabled, style }) {
  const sp = useSpeechInput({ onFinal: onText, onInterim });
  const cls = 'mic' + (small ? ' vi-mic' : '') + (sp.listening ? ' on' : '') + (sp.transcribing ? ' busy' : '');
  const title = sp.transcribing ? '글자로 바꾸는 중…' : sp.listening ? (sp.mode === 'server' ? `녹음 중 ${sp.seconds}초 · 다시 누르면 끝` : '듣고 있어요 · 다시 누르면 끝') : label;
  return (
    <button type="button" className={cls} style={style} aria-label={title} title={title} aria-pressed={sp.listening} disabled={disabled || sp.transcribing}
      data-voice-mode={sp.mode} onClick={sp.toggle}>
      {sp.transcribing ? <span className="vi-dots" aria-hidden="true"><i /><i /><i /></span> : <Ic h={I.mic} />}
      {sp.listening && sp.mode === 'server' && <span className="vi-sec" aria-hidden="true">{sp.seconds}</span>}
    </button>
  );
}

/** textarea·input 을 감싸 오른쪽 아래에 마이크를 띄운다. 받은 글은 기존 글 뒤에 붙인다. */
export function VoiceField({ value, onChange, children, disabled }) {
  return (
    <div className="vi-wrap">
      {children}
      <MicButton small disabled={disabled} onText={(t) => onChange(value && String(value).trim() ? `${String(value).replace(/\s+$/, '')} ${t}` : t)} />
    </div>
  );
}

/** 설정용: 서버 음성 인식 켜기/끄기 (서버 키가 있을 때만 보인다) */
export function SttServerToggle({ compact = false }) {
  const stt = useSttAvailable();
  const [on, setOn] = useState(getSttServer());
  if (!stt || !canRecord()) return null;
  const flip = () => { const v = !on; setOn(v); setSttServer(v); window.dispatchEvent(new Event('ts-stt-pref')); };
  if (compact) return <button type="button" className={'vr-tts' + (on ? ' on' : '')} aria-pressed={on} onClick={flip}>{on ? '🎙 서버 음성 인식' : '🎙 기기 음성 인식'}</button>;
  return (
    <>
      <div className="sec-t">음성 입력</div>
      <div className="ml">
        <div role="switch" aria-checked={on} tabIndex={0} onClick={flip} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); flip(); } }}>
          <div className="grow">서버 음성 인식 쓰기<span>{speechSupported ? '기기 받아쓰기가 잘 안 될 때 켜요' : '이 기기는 받아쓰기가 없어 서버로 바꿔요'} · 녹음은 글자로 바꾼 뒤 저장하지 않아요</span></div>
          <span className={'toggle ' + (on ? 'on' : '')} aria-hidden="true" />
        </div>
      </div>
    </>
  );
}
