import { useCallback, useEffect, useRef, useState } from 'react';
import { Ic } from './bits.jsx';

/*
 * 실제 QR 인식 + 6자리 번호 입력(항상 같이 보임).
 * 카메라(후면) 영상 → 브라우저 내장 BarcodeDetector, 없으면 jsQR 로 프레임을 읽는다.
 * QR 내용 형식: TS1|<REQ|CLS|ONE>|<대상 id>|<6자리 코드>
 * 인식되면 submit({code, method:'qr'}), 번호로 넣으면 submit({code, method:'code'}).
 * 영상은 화면에만 보이고 저장·전송하지 않는다.
 */
export function qrText(kind, id, code) { return `TS1|${kind}|${id}|${code || ''}`; }

export function parseQr(text) {
  const m = /^TS1\|(REQ|CLS|ONE)\|(\d+)\|(\d{6})$/.exec(String(text || '').trim());
  return m ? { kind: m[1], id: Number(m[2]), code: m[3] } : null;
}

const HINT = {
  starting: '카메라를 켜는 중…',
  live: 'QR을 네모 안에 맞춰 주세요',
  insecure: '카메라는 https 주소(또는 이 기기 localhost)에서만 켜져요 · 아래 번호로 확인해요',
  denied: '카메라 권한이 꺼져 있어요 · 아래 번호 6자리로 확인해요',
  nocam: '카메라를 찾을 수 없어요 · 아래 번호 6자리로 확인해요',
  error: '카메라를 켜지 못했어요 · 아래 번호 6자리로 확인해요',
  other: '다른 활동의 QR이에요. 맞는 QR을 찍어 주세요',
  wrong: '번호가 맞지 않아요. 다시 찍어 주세요',
  sending: '확인하는 중…',
  ok: '확인됐어요',
};

let jsqrP = null;
const loadJsQR = () => (jsqrP = jsqrP || import('jsqr').then((m) => m.default || m));

async function makeDetector() {
  try {
    if (!('BarcodeDetector' in window)) return null;
    const fm = window.BarcodeDetector.getSupportedFormats ? await window.BarcodeDetector.getSupportedFormats() : ['qr_code'];
    return fm.includes('qr_code') ? new window.BarcodeDetector({ formats: ['qr_code'] }) : null;
  } catch { return null; }
}

/**
 * props: kind('REQ'|'CLS'|'ONE'), id(대상 id), submit(body) → Promise(실패하면 throw), onDone(), codeLabel, foot(아래 작은 안내)
 */
/** U9: QR을 처음 쓸 때 한 줄 설명(한 번 닫으면 다시 안 보임 · 브라우저에만 기억) */
const QR_INTRO_KEY = 'ts-qr-intro-seen';
export function QrIntro() {
  const [show, setShow] = useState(() => { try { return !localStorage.getItem(QR_INTRO_KEY); } catch { return true; } });
  if (!show) return null;
  return (
    <div className="qr-intro" role="note">
      <Ic n="qr" s={18} />
      <span className="grow">QR은 <b>만났을 때와 끝날 때</b> 서로 확인하는 장치예요. 찍은 시간만큼 시간이 정확히 쌓여요.</span>
      <button type="button" onClick={() => { try { localStorage.setItem(QR_INTRO_KEY, '1'); } catch { /* 무시 */ } setShow(false); }}>알겠어요</button>
    </div>
  );
}

export default function QrScanner({ kind, id, submit, onDone, codeLabel = '번호 6자리', foot }) {
  const [state, setState] = useState('starting');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const videoRef = useRef(null);
  const canvasRef = useRef(null);
  const live = useRef({ stream: null, timer: null, paused: false, alive: true, engine: '' });
  const [engine, setEngine] = useState('');

  const stopCamera = useCallback(() => {
    const l = live.current;
    clearTimeout(l.timer);
    if (l.stream) l.stream.getTracks().forEach((t) => t.stop());
    l.stream = null;
  }, []);

  const go = useCallback(async (c, method) => {
    const l = live.current;
    if (l.paused) return;
    l.paused = true;
    setBusy(true);
    setState('sending');
    try {
      await submit({ code: c, method });
      setState('ok');
      if (navigator.vibrate) navigator.vibrate(40);
      stopCamera();
      setTimeout(() => { if (live.current.alive) onDone && onDone(); }, 650);
    } catch {
      setState(method === 'qr' ? 'wrong' : (l.stream ? 'live' : 'error'));
      setTimeout(() => { l.paused = false; if (live.current.alive && l.stream) setState('live'); }, 1500);
    } finally { setBusy(false); }
  }, [submit, onDone, stopCamera]);

  const onText = useCallback((text) => {
    const p = parseQr(text);
    if (!p || p.kind !== kind || Number(p.id) !== Number(id)) {
      // 6자리 숫자만 담긴 QR(인쇄 카드 등)도 받는다
      if (/^\d{6}$/.test(String(text || '').trim())) { go(String(text).trim(), 'qr'); return; }
      if (!live.current.paused) setState('other');
      return;
    }
    go(p.code, 'qr');
  }, [kind, id, go]);

  useEffect(() => {
    const l = live.current;
    l.alive = true;
    (async () => {
      if (!window.isSecureContext) { setState('insecure'); return; }
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { setState('nocam'); return; }
      let stream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
      } catch (e) {
        if (!l.alive) return;
        setState(e && (e.name === 'NotAllowedError' || e.name === 'SecurityError') ? 'denied' : e && (e.name === 'NotFoundError' || e.name === 'OverconstrainedError') ? 'nocam' : 'error');
        return;
      }
      if (!l.alive) { stream.getTracks().forEach((t) => t.stop()); return; }
      l.stream = stream;
      const v = videoRef.current;
      v.srcObject = stream;
      try { await v.play(); } catch { /* 자동 재생 실패는 무시(muted·playsInline) */ }
      setState('live');
      const detector = await makeDetector();
      const jsQR = detector ? null : await loadJsQR().catch(() => null);
      const eng = detector ? 'BarcodeDetector' : jsQR ? 'jsQR' : '';
      l.engine = eng; setEngine(eng);
      const tick = async () => {
        if (!l.alive || !l.stream) return;
        try {
          if (!l.paused && v.readyState >= 2 && v.videoWidth) {
            let text = null;
            if (detector) {
              const codes = await detector.detect(v);
              text = codes && codes[0] ? codes[0].rawValue : null;
            } else if (jsQR) {
              const cv = canvasRef.current;
              const scale = Math.min(1, 640 / v.videoWidth);
              const w = Math.round(v.videoWidth * scale), h = Math.round(v.videoHeight * scale);
              cv.width = w; cv.height = h;
              const ctx = cv.getContext('2d', { willReadFrequently: true });
              ctx.drawImage(v, 0, 0, w, h);
              const img = ctx.getImageData(0, 0, w, h);
              const r = jsQR(img.data, w, h, { inversionAttempts: 'dontInvert' });
              text = r ? r.data : null;
            }
            if (text) onTextRef.current(text);
          }
        } catch { /* 한 프레임 실패는 넘어간다 */ }
        l.timer = setTimeout(tick, 180);
      };
      tick();
    })();
    return () => { l.alive = false; stopCamera(); };
  }, [stopCamera]);
  const onTextRef = useRef(onText);
  onTextRef.current = onText;

  const camLive = !!live.current.stream && !['ok'].includes(state);
  return (
    <>
      <QrIntro />
      <div className={'qr-cam' + (camLive ? ' live' : '') + (state === 'ok' ? ' ok' : '')} data-qr-state={state} data-qr-engine={engine}>
        <video ref={videoRef} className="qr-video" playsInline muted autoPlay aria-hidden="true" />
        <canvas ref={canvasRef} hidden />
        <div className="qr-frame"><i className="c1" /><i className="c2" /><i className="c3" /><i className="c4" />{state !== 'ok' && camLive && <div className="qr-line" />}</div>
        <div className="qr-okm"><Ic n="check" s={34} /></div>
        <div className="qr-hint" role="status" aria-live="polite">{HINT[state] || HINT.live}</div>
      </div>
      <form className="row" style={{ marginTop: 10 }} onSubmit={(e) => { e.preventDefault(); if (/^\d{6}$/.test(code)) go(code, 'code'); }}>
        <input className="in grow" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder={codeLabel} value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} aria-label={`QR이 안 찍히면 ${codeLabel}`} />
        <button className="btn sm" style={{ width: 'auto', padding: '0 18px', flex: '0 0 auto' }} disabled={busy || code.length !== 6}>확인</button>
      </form>
      <div className="mute2 center" style={{ marginTop: 6 }}>QR이 안 찍히면 상대 화면의 번호 6자리를 넣어 주세요{foot ? <><br />{foot}</> : null}</div>
    </>
  );
}
