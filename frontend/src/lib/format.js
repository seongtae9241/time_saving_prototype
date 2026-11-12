// 프로토타입 규칙·카테고리·표시 도우미를 옮긴 것
export const CATS = [
  { k: '말동무·정서', w: 1.0, lv: '기본', code: 'BASIC' },
  { k: '디지털 도움', w: 1.0, lv: '기본', code: 'BASIC' },
  { k: '생활 정리', w: 1.0, lv: '기본', code: 'BASIC' },
  { k: '장보기·심부름', w: 1.2, lv: '수고', code: 'EFFORT' },
  { k: '가벼운 집안일', w: 1.2, lv: '수고', code: 'EFFORT' },
  { k: '동행', w: 1.5, lv: '책임', code: 'RESPONSIBLE' },
];
export const CAT_KEYS = CATS.map((c) => c.k);
export const W = (k) => (CATS.find((c) => c.k === k) || { w: 1 }).w;
export const LV = (k) => (CATS.find((c) => c.k === k) || { lv: '기본' }).lv;
export const LEVEL = { BASIC: ['기본', 1.0], EFFORT: ['수고', 1.2], RESPONSIBLE: ['책임', 1.5] };
export const lvName = (code) => (LEVEL[code] || [code || '기본'])[0];
export const lvWeight = (code, w) => (w != null ? Number(w) : (LEVEL[code] || [0, 1])[1]);
export const fmt = (n) => (Math.round((Number(n) || 0) * 2) / 2).toFixed(1);

export const CI = {
  '동행': '<path d="M12 6v12M6 12h12"/><rect x="3" y="3" width="18" height="18" rx="5"/>',
  '장보기·심부름': '<path d="M3 4h2.2l2.3 11h10.3L20 8H6.4"/><circle cx="9.5" cy="19.5" r="1.4"/><circle cx="16.5" cy="19.5" r="1.4"/>',
  '말동무·정서': '<path d="M20 11.5a7.5 7.5 0 0 1-11 6.6L4 19l1-4.2A7.5 7.5 0 1 1 20 11.5z"/><path d="M9 11h.01M12.5 11h.01M16 11h.01"/>',
  '디지털 도움': '<rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M10.5 18h3"/>',
  '생활 정리': '<path d="M3.5 7.5 12 3.5l8.5 4v9L12 20.5l-8.5-4z"/><path d="M3.5 7.5 12 11.5l8.5-4M12 11.5v9"/>',
  '가벼운 집안일': '<path d="M4 11 12 4l8 7v8.5a.5.5 0 0 1-.5.5H15v-5H9v5H4.5a.5.5 0 0 1-.5-.5z"/>',
};
export const CC = {
  '동행': ['#EAF0FF', '#5B8DEF'], '장보기·심부름': ['#FFF1E2', '#EE8424'], '말동무·정서': ['#FFEDF3', '#E2557F'],
  '디지털 도움': ['#F0ECFF', '#7656E3'], '생활 정리': ['#FFF6D6', '#C99500'], '가벼운 집안일': ['#E4F5F9', '#178FAD'],
};
export const SHORT = { '동행': '동행', '장보기·심부름': '장보기', '말동무·정서': '말동무', '디지털 도움': '디지털', '생활 정리': '생활 정리', '가벼운 집안일': '집안일' };
export const ccOf = (k) => CC[k] || ['#EEF3FF', '#5B8DEF'];

export const hh = (h) => { h = Number(h) || 0; const H = Math.floor(h), m = Math.round((h - H) * 60); return `${H}:${m ? '30' : '00'}`; };
export const ap = (h) => { h = Number(h) || 0; return `${h < 12 ? '오전' : '오후'} ${h > 12 ? Math.floor(h - 12) : Math.floor(h)}시${h % 1 ? ' 30분' : ''}`; };

const WD = ['일', '월', '화', '수', '목', '금', '토'];
export function dlabel(d) {
  if (!d) return '';
  const s = String(d).slice(0, 10).split('-');
  if (s.length < 3) return String(d);
  const dt = new Date(+s[0], +s[1] - 1, +s[2]);
  return `${+s[1]}/${+s[2]}(${WD[dt.getDay()]})`;
}
export function dshort(dt) {
  if (!dt) return '';
  const s = String(dt);
  const [d, t] = s.split('T');
  const p = d.split('-');
  return `${+p[1]}/${+p[2]}${t ? ' ' + t.slice(0, 5) : ''}`;
}
export function ago(dt) {
  if (!dt) return '';
  const t = new Date(dt).getTime();
  if (!t) return '';
  const m = Math.round((Date.now() - t) / 60000);
  if (m < 1) return '방금';
  if (m < 60) return `${m}분 전`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.round(h / 24);
  if (d === 1) return '어제';
  if (d < 7) return `${d}일 전`;
  return dshort(dt).split(' ')[0];
}
export const todayISO = () => { const d = new Date(); return iso(d); };
export const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
export const addDays = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return iso(d); };
export const arr = (v) => (Array.isArray(v) ? v : v == null || v === '' ? [] : String(v).split(',').map((x) => x.trim()).filter(Boolean));
/** 받침에 맞는 조사 붙이기: josa('가까운 순 정렬', '을', '를') → '가까운 순 정렬을' */
export const josa = (word, withFinal, noFinal) => {
  const w = String(word || '');
  const c = w.charCodeAt(w.length - 1);
  const has = c >= 0xac00 && c <= 0xd7a3 ? (c - 0xac00) % 28 !== 0 : false;
  return w + (has ? withFinal : noFinal);
};
/** (으)로: 받침이 없거나 ㄹ 받침이면 '로', 그 밖의 받침은 '으로'. ro('확인 중') → '확인 중으로' */
export const ro = (word) => {
  const w = String(word || '');
  const c = w.charCodeAt(w.length - 1);
  const j = c >= 0xac00 && c <= 0xd7a3 ? (c - 0xac00) % 28 : 0;
  return w + (j === 0 || j === 8 ? '로' : '으로');
};
export const maskN = (n) => (n ? (n.length <= 1 ? n + '○' : n[0] + '○' + n.slice(-1)) : '');

export const ST = {
  SENT: ['wait', '수락 대기'], VOL_ACCEPTED: ['wait', '최종 수락 대기'], CONFIRMED: ['go', '일정 확정'],
  IN_PROGRESS: ['go', '활동 중'], CHECKED_OUT: ['go', '일지 대기'], LOG_SUBMITTED: ['go', '확인 대기'],
  DONE: ['ok', '완료'], REJECTED: ['no', '거절됨'], DECLINED: ['no', '거부됨'], EXPIRED: ['end', '만료'],
  CANCELLED: ['end', '취소'], NOSHOW: ['no', '노쇼'],
};
export const KIND_LABEL = { REQUEST: '요청', PROPOSAL: '제안', REGULAR: '단골 요청' };
export const ACTIVE_ST = ['SENT', 'VOL_ACCEPTED', 'CONFIRMED', 'IN_PROGRESS', 'CHECKED_OUT', 'LOG_SUBMITTED'];
export const PAST_ST = ['DONE', 'REJECTED', 'DECLINED', 'EXPIRED', 'CANCELLED', 'NOSHOW'];
export const AREAS = ['행복동', '희망동', '사랑동', '미래동'];
export const AGE_GROUPS = ['10대', '20대', '30대', '40대', '50대', '60대', '70대', '80대 이상'];
export const TIME_SLOTS = ['평일 오전', '평일 오후', '평일 저녁', '주말 오전', '주말 오후'];
