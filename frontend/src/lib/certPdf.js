// 기능 U-35 봉사 확인서 PDF: 확인서를 캔버스에 그려(한글은 브라우저 글꼴 그대로) JPEG 로 만든 뒤, 그 그림을 담은 PDF 파일을 직접 짠다.
// 새 패키지 없이 브라우저만으로 .pdf 파일을 내려받게 한다. 숫자는 모두 서버가 준 값(GET /api/certificate)을 그대로 쓴다.
import { fmt, dlabel, SHORT } from './format.js';

const W = 1240, H = 1754; // A4 150dpi
const A4 = [595.28, 841.89]; // pt
// 한 쪽에 넣는 활동 줄 수: [확인 문구가 없는 쪽, 확인 문구(마지막 쪽)가 함께 들어가는 쪽]
const FIRST = [19, 12], REST = [35, 28];
const FOOT_Y = H - 320; // 마지막 쪽의 확인 문구 자리

function fontOf(px, weight = 400) {
  let fam = 'sans-serif';
  try { fam = getComputedStyle(document.body).fontFamily || fam; } catch { /* 기본 글꼴 */ }
  return `${weight} ${px}px ${fam}`;
}

function clip(g, text, max) {
  let t = String(text == null ? '' : text);
  if (g.measureText(t).width <= max) return t;
  while (t.length > 1 && g.measureText(`${t}…`).width > max) t = t.slice(0, -1);
  return `${t}…`;
}

function drawPage(c, items, pageNo, pages, from) {
  const cv = document.createElement('canvas');
  cv.width = W; cv.height = H;
  const g = cv.getContext('2d');
  g.fillStyle = '#fff'; g.fillRect(0, 0, W, H);
  g.strokeStyle = '#1B1D22'; g.lineWidth = 4; g.strokeRect(60, 60, W - 120, H - 120);
  g.lineWidth = 1; g.strokeRect(72, 72, W - 144, H - 144);
  g.fillStyle = '#1B1D22'; g.textBaseline = 'alphabetic';
  let y = 190;
  if (pageNo === 1) {
    g.textAlign = 'center';
    g.font = fontOf(64, 800); g.fillText('봉사 활동 확인서', W / 2, y);
    g.font = fontOf(26, 500); g.fillStyle = '#5F6672'; g.fillText(`시간 적금 · ${c.period || '활동 기록 없음'}`, W / 2, y + 50);
    y += 130;
    const rows = [
      ['이름', c.name || '-'], ['통장 번호', c.accountNo || '-'],
      ['활동 횟수', `${c.count || 0}회`], ['활동 시간', `${fmt(c.totalHours)}시간 (예정 시간 기준)`],
      ['적립 시간', `${fmt(c.totalEarned)}시간 (난이도 가중치 포함)`],
      ['주요 활동', (c.byCategory || []).map((b) => `${SHORT[b.category] || b.category} ${b.count}회`).join(' · ') || '-'],
      ['독거노인 대상', `${c.elderCount || 0}회`], ['발급일', c.issuedAt || ''],
    ];
    g.textAlign = 'left';
    rows.forEach(([k, v]) => {
      g.fillStyle = '#F4F6FA'; g.fillRect(130, y - 38, 250, 56);
      g.strokeStyle = '#D5D9E2'; g.lineWidth = 1; g.strokeRect(130, y - 38, W - 260, 56);
      g.fillStyle = '#5F6672'; g.font = fontOf(26, 600); g.fillText(k, 150, y);
      g.fillStyle = '#1B1D22'; g.font = fontOf(28, 600); g.fillText(clip(g, v, W - 260 - 290), 400, y);
      y += 56;
    });
    y += 40;
  } else {
    g.textAlign = 'left'; g.font = fontOf(30, 700); g.fillText(`봉사 활동 확인서 · 활동 내역 (이어서) · ${c.name || ''}`, 130, y - 40);
    y += 20;
  }
  if (items.length) {
    g.textAlign = 'left'; g.font = fontOf(28, 700); g.fillStyle = '#1B1D22';
    if (pageNo === 1) { g.fillText('활동 내역', 130, y); y += 20; }
    g.font = fontOf(22, 600); g.fillStyle = '#5F6672';
    y += 30;
    g.fillText('번호', 130, y); g.fillText('날짜', 210, y); g.fillText('도움 종류', 470, y); g.fillText('받는 분', 720, y);
    g.textAlign = 'right'; g.fillText('시간', W - 130, y); g.textAlign = 'left';
    g.strokeStyle = '#1B1D22'; g.lineWidth = 2; g.beginPath(); g.moveTo(130, y + 12); g.lineTo(W - 130, y + 12); g.stroke();
    y += 46;
    g.font = fontOf(24, 500); g.fillStyle = '#1B1D22';
    items.forEach((it, i) => {
      g.fillText(String(from + i + 1), 130, y);
      g.fillText(dlabel(it.date), 210, y);
      g.fillText(clip(g, it.category || '', 230), 470, y);
      g.fillText(clip(g, it.recipient || it.recipientLabel || '', 300), 720, y);
      g.textAlign = 'right'; g.fillText(`${fmt(it.hours)}시간`, W - 130, y); g.textAlign = 'left';
      g.strokeStyle = '#E3E6EC'; g.lineWidth = 1; g.beginPath(); g.moveTo(130, y + 12); g.lineTo(W - 130, y + 12); g.stroke();
      y += 38;
    });
  }
  if (pageNo === pages) {
    const by = FOOT_Y;
    g.textAlign = 'center'; g.fillStyle = '#1B1D22';
    g.font = fontOf(30, 600); g.fillText('위 사람은 시간 적금에서 위와 같이 봉사 활동을 하였음을 확인합니다.', W / 2, by);
    g.font = fontOf(28, 500); g.fillText(c.issuedAt || '', W / 2, by + 70);
    g.font = fontOf(40, 800); g.fillText('시간 적금 운영팀', W / 2, by + 140);
    g.font = fontOf(20, 400); g.fillStyle = '#5F6672';
    g.fillText('적립이 확정된 활동(일지 제출·확인 완료)만 들어가요 · 받는 분 이름은 가려서 표시돼요 · 시간은 돈으로 바꿀 수 없어요', W / 2, by + 200);
  }
  g.textAlign = 'center'; g.font = fontOf(20, 400); g.fillStyle = '#8A919E'; g.fillText(`${pageNo} / ${pages}`, W / 2, H - 90);
  return cv;
}

function jpegBytes(cv) {
  const b64 = cv.toDataURL('image/jpeg', 0.92).split(',')[1];
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
  return out;
}

/** JPEG 그림들(쪽마다 한 장)을 담은 PDF 1.4 파일. */
export function pdfFromJpegs(jpegs, w = W, h = H) {
  const enc = new TextEncoder();
  const parts = [];
  const offsets = [];
  let len = 0;
  const push = (x) => { const b = typeof x === 'string' ? enc.encode(x) : x; parts.push(b); len += b.length; };
  const obj = (n, body) => { offsets[n] = len; push(`${n} 0 obj\n`); body(); push('\nendobj\n'); };
  const n = jpegs.length;
  push('%PDF-1.4\n');
  obj(1, () => push('<< /Type /Catalog /Pages 2 0 R >>'));
  obj(2, () => push(`<< /Type /Pages /Kids [${jpegs.map((_, i) => `${3 + i * 3} 0 R`).join(' ')}] /Count ${n} >>`));
  jpegs.forEach((jpg, i) => {
    const page = 3 + i * 3, content = page + 1, image = page + 2;
    const stream = `q ${A4[0]} 0 0 ${A4[1]} 0 0 cm /Im0 Do Q`;
    obj(page, () => push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${A4[0]} ${A4[1]}] /Resources << /XObject << /Im0 ${image} 0 R >> >> /Contents ${content} 0 R >>`));
    obj(content, () => push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`));
    obj(image, () => { push(`<< /Type /XObject /Subtype /Image /Width ${w} /Height ${h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.length} >>\nstream\n`); push(jpg); push('\nendstream'); });
  });
  const total = 2 + n * 3;
  const xref = len;
  push(`xref\n0 ${total + 1}\n0000000000 65535 f \n`);
  for (let i = 1; i <= total; i += 1) push(`${String(offsets[i]).padStart(10, '0')} 00000 n \n`);
  push(`trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`);
  return new Blob(parts, { type: 'application/pdf' });
}

/** 확인서(c = GET /api/certificate 응답)를 PDF Blob 으로. */
export function certPdfBlob(c) {
  const items = c.items || [];
  // 마지막 쪽에는 확인 문구가 들어갈 자리를 남긴다(자리가 모자라면 다음 쪽으로 넘긴다)
  const chunks = [];
  let rest = items.slice();
  for (;;) {
    const [cap, capFoot] = chunks.length ? REST : FIRST;
    if (rest.length <= capFoot) { chunks.push(rest); break; }
    const take = Math.min(cap, rest.length - 3); // 마지막 쪽이 확인 문구만 있는 빈 쪽이 되지 않게 활동 3줄은 넘긴다
    chunks.push(rest.slice(0, take));
    rest = rest.slice(take);
  }
  let from = 0;
  const jpegs = chunks.map((ch, i) => { const cv = drawPage(c, ch, i + 1, chunks.length, from); from += ch.length; return jpegBytes(cv); });
  return pdfFromJpegs(jpegs);
}

/** 확인서 PDF 파일을 내려받는다. 파일 이름: 봉사활동확인서_이름_발급일.pdf */
export function downloadCertPdf(c) {
  const blob = certPdfBlob(c);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `봉사활동확인서_${c.name || '시간적금'}_${String(c.issuedAt || '').replace(/[^0-9]/g, '') || 'cert'}.pdf`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  return blob.size;
}
