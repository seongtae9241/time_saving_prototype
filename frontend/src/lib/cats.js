// 기능 A-15: 운영자가 건의를 승인해 더한 카테고리를 서버에서 읽어 화면의 카테고리 목록(CATS)에 붙인다. 기본 6종은 그대로다.
import { api } from '../api.js';
import { CATS, CAT_KEYS, CC, SHORT, LEVEL } from './format.js';

const BASE = CATS.length;
export async function loadCats() {
  try {
    const list = await api('/categories', { quiet: true });
    const extra = (Array.isArray(list) ? list : []).filter((c) => c.extra && c.name);
    CATS.length = BASE;
    CAT_KEYS.length = BASE;
    extra.forEach((c) => {
      if (CATS.some((x) => x.k === c.name)) return;
      CATS.push({ k: c.name, w: Number(c.weight) || 1, lv: (LEVEL[c.level] || ['기본'])[0], code: c.level || 'BASIC', extra: true });
      CAT_KEYS.push(c.name);
      if (!CC[c.name]) CC[c.name] = ['#EEF3FF', '#5B8DEF'];
      if (!SHORT[c.name]) SHORT[c.name] = c.name;
    });
  } catch { /* 못 읽으면 기본 6종만 쓴다 */ }
}
