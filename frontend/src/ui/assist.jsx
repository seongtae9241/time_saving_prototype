// LLM 추가 A(계약서 7.12): 카드·목록 어디에나 붙는 작은 조각. bits.jsx 가 이 파일을 불러 쓰므로 여기서는 bits.jsx 를 불러오지 않는다(순환 방지).
import { useState } from 'react';
import { api } from '../api.js';
import { I } from './icons.js';
import '../styles/assist.css';

const Spark = () => <span className="spark" dangerouslySetInnerHTML={{ __html: I.spark || '' }} />;

/** 한국어로 천천히 읽어 준다(rate 0.9). 지원하지 않으면 false */
export function speakKo(text, rate = 0.9) {
  return new Promise((resolve) => {
    if (!text || typeof window === 'undefined' || !('speechSynthesis' in window)) return resolve(false);
    try {
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(String(text));
      u.lang = 'ko-KR'; u.rate = rate;
      const v = window.speechSynthesis.getVoices().find((x) => /ko/i.test(x.lang));
      if (v) u.voice = v;
      let done = false;
      const fin = () => { if (!done) { done = true; resolve(true); } };
      u.onend = fin; u.onerror = fin;
      setTimeout(fin, Math.min(30000, 1500 + String(text).length * 180));
      window.speechSynthesis.speak(u);
    } catch { resolve(false); }
  });
}

/** "왜 이 봉사자?" 한 줄 — 숫자는 서버 코드 값 */
export function MatchWhy({ text, style }) {
  if (!text) return null;
  return <div className="match-why" style={style}><Spark /><span><b>왜 이 봉사자?</b> {text}</span></div>;
}

const easyCache = new Map();

/** "쉬운 말로 듣기" — 공고별로 서버가 한 번 만들어 저장한 문장을 받아 읽는다. 카드 안에서는 눌러도 상세로 넘어가지 않는다. */
export function EasyListen({ postId, compact, showText }) {
  const [state, setState] = useState('idle'); // idle | load | play
  const [text, setText] = useState(easyCache.get(postId) || '');
  const play = async (e) => {
    if (e) { e.stopPropagation(); e.preventDefault(); }
    if (state === 'play') { try { window.speechSynthesis.cancel(); } catch { /* 무시 */ } setState('idle'); return; }
    let t = easyCache.get(postId);
    if (!t) {
      setState('load');
      try { const r = await api(`/posts/${postId}/easy`, { quiet: true }); t = r.text; easyCache.set(postId, t); } catch { setState('idle'); return; }
    }
    setText(t);
    setState('play');
    const ok = await speakKo(t, 0.9);
    setState('idle');
    if (!ok) setText(t);
  };
  return (
    <>
      <button type="button" className={'easy-btn' + (compact ? ' sm' : '') + (state === 'play' ? ' on' : '')} onClick={play} onKeyDown={(e) => e.stopPropagation()} aria-label="쉬운 말로 듣기">
        <span aria-hidden="true">{state === 'play' ? '■' : '🔊'}</span>{state === 'load' ? '준비 중…' : state === 'play' ? '멈추기' : '쉬운 말로 듣기'}
      </button>
      {showText && text && <div className="easy-text">“{text}”</div>}
    </>
  );
}
