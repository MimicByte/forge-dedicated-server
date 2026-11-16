// The picture of a booster pack. A set's own booster picture is used when the server has it. Otherwise Forge prints its own wrapper:
// pearl foil, or a picture such as a plane's art, with the pack's name and a strip printed on it and then lit as foil

import { imageUrl } from './images';

export interface PackPrint {
  /** The booster picture's image key, or null when the pack has none. */
  booster: string | null;
  title: string;
  /** The wrapper's picture, as a URL. Null prints pearl foil. */
  art?: string | null;
  /** An SVG drawn on dark foil in place of the art, such as the chaos symbol. */
  mark?: string | null;
  /** The strip's words. They are printed artwork, in English like the rest of the wrapper's lettering. */
  strip?: string;
  /** How many cards the pack holds, printed in its corner. Left off when it is not known. */
  cards?: number;
}

/** The pack's picture as a URL, with its seal SEAL percent of its height. */
export const SEAL = 6.3;
const W = 480, H = 912, S = W / 175;
const printed = new Map<string, Promise<string>>();

/** The pack's picture. A set's booster picture is waited for at most a second before the printed wrapper is used instead. */
export async function packPicture(pack: PackPrint): Promise<string> {
  if (pack.booster) {
    const src = imageUrl(pack.booster);
    if (await loadImage(src, 1000)) return src;
  }
  const key = JSON.stringify([pack.title, pack.art ?? null, pack.mark ?? null, pack.strip ?? null, pack.cards ?? null]);
  let picture = printed.get(key);
  if (!picture) {
    picture = print(pack);
    printed.set(key, picture);
  }
  return picture;
}

function loadImage(src: string, wait = 0): Promise<HTMLImageElement | null> {
  return new Promise(done => {
    const img = new Image();
    const timer = wait ? setTimeout(() => done(null), wait) : 0;
    img.onload = () => { clearTimeout(timer); done(img); };
    img.onerror = () => { clearTimeout(timer); done(null); };
    img.src = src;
  });
}

async function print(pack: PackPrint): Promise<string> {
  const pearl = !pack.art && !pack.mark;
  // The pearl artwork has its lettering printed in dark ink. Any other wrapper takes the same lettering in silver
  const [base, crinkle, lettering] = await Promise.all([
    pack.art ? loadImage(pack.art) : pack.mark ? loadImage(`data:image/svg+xml,${encodeURIComponent(pack.mark)}`) : loadImage('/booster/generic.jpg'),
    loadImage('/booster/crinkle.jpg'), pearl ? null : loadImage('/booster/lettering.png')]);
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const g = canvas.getContext('2d')!;
  // The heat-sealed ends are crimped into teeth
  const teeth = 50, depth = H * .012;
  g.beginPath();
  g.moveTo(0, depth);
  for (let i = 1; i <= teeth; i++) g.lineTo(W * i / teeth, i % 2 ? 0 : depth);
  for (let i = teeth; i >= 0; i--) g.lineTo(W * i / teeth, i % 2 ? H : H - depth);
  g.clip();

  if (pearl) {
    g.fillStyle = '#dfe3ea';
    g.fillRect(0, 0, W, H);
    if (base) g.drawImage(base, 0, 0, W, H);
  } else if (pack.mark) {
    const field = g.createRadialGradient(W / 2, H * .38, 0, W / 2, H * .38, H * .6);
    [[0, '#3a2a6e'], [.46, '#171033'], [1, '#07060f']].forEach(([at, colour]) => field.addColorStop(at as number, colour as string));
    g.fillStyle = field;
    g.fillRect(0, 0, W, H);
    if (base) g.drawImage(base, W * .21, H * .24, W * .58, W * .58);
  } else {
    g.fillStyle = '#111';
    g.fillRect(0, 0, W, H);
    if (base) {
      // The art covers the front, cut to the pack's shape round its upper third, where a card's subject usually is
      const scale = Math.max(W / base.width, H / base.height), w = base.width * scale, h = base.height * scale;
      g.drawImage(base, (W - w) / 2, (H - h) * .3, w, h);
    }
  }
  if (!pearl) {
    // Darker print behind the lettering, so it reads over any picture
    const shade = g.createLinearGradient(0, 0, 0, H);
    [[0, '#00000080'], [.2, '#00000000'], [.56, '#00000000'], [.8, '#000000b0'], [1, '#000000c0']].forEach(([at, colour]) => shade.addColorStop(at as number, colour as string));
    g.fillStyle = shade;
    g.fillRect(0, 0, W, H);
    if (lettering) g.drawImage(lettering, 0, 0, W, W * lettering.height / lettering.width);
  }

  const ui = getComputedStyle(document.body).fontFamily;
  if (pack.cards) {
    g.textAlign = 'left';
    g.textBaseline = 'middle';
    g.fillStyle = pearl ? '#3b4250c0' : '#dfe8f5b0';
    g.font = `600 ${6.5 * S}px ${ui}`;
    g.letterSpacing = `${.9 * S}px`;
    g.fillText(`${pack.cards} CARDS`, W * .08, H * .086 + 3.25 * S);
  }

  const strip = { y: H * (1 - .095 - .052), h: H * .052 };
  g.fillStyle = pack.mark ? '#3a2a6e' : pearl ? '#3a414e' : '#262c37';
  g.fillRect(0, strip.y, W, strip.h);
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillStyle = '#f4f7fb';
  g.font = `800 ${8.5 * S}px ${ui}`;
  g.letterSpacing = `${1.9 * S}px`;
  g.fillText((pack.strip ?? 'Booster').toUpperCase(), W / 2 + .95 * S, strip.y + strip.h / 2, W - 12 * S);

  g.letterSpacing = '0px';
  g.textBaseline = 'alphabetic';
  let size = 23 * S;
  const font = () => `small-caps 700 ${size}px Georgia, "Times New Roman", serif`;
  g.font = font();
  const lines = balance(g, pack.title, W - 16 * S);
  const widest = Math.max(...lines.map(line => g.measureText(line).width));
  if (widest > W - 16 * S) {
    size *= (W - 16 * S) / widest;
    g.font = font();
  }
  const bottom = H * (1 - .175) - size * .2;
  lines.forEach((line, i) => {
    const y = bottom - (lines.length - 1 - i) * size * .95;
    g.fillStyle = pearl ? '#ffffffb0' : '#000000c0';
    g.fillText(line, W / 2, y + S);
    g.fillStyle = pearl ? '#20242c' : '#f4f7fb';
    g.fillText(line, W / 2, y);
  });

  // The filled wrapper darkens towards its edges and catches light across its middle
  const across = g.createLinearGradient(0, 0, W, 0);
  [[0, '#00000070'], [.06, '#00000018'], [.16, '#00000000'], [.34, '#ffffff14'], [.5, '#00000000'], [.82, '#00000000'], [.94, '#00000030'], [1, '#00000078']]
    .forEach(([at, colour]) => across.addColorStop(at as number, colour as string));
  g.fillStyle = across;
  g.fillRect(0, 0, W, H);
  const down = g.createLinearGradient(0, 0, 0, H);
  [[0, '#00000040'], [.09, '#00000000'], [.91, '#00000000'], [1, '#00000055']].forEach(([at, colour]) => down.addColorStop(at as number, colour as string));
  g.fillStyle = down;
  g.fillRect(0, 0, W, H);
  if (crinkle) {
    g.globalCompositeOperation = 'soft-light';
    g.globalAlpha = .7;
    g.drawImage(crinkle, 0, 0, W, H);
    g.globalAlpha = 1;
  }
  g.globalCompositeOperation = 'overlay';
  for (const y of [0, H - H * .065]) {
    for (let x = 0; x < W; x += 2.4 * S) {
      g.fillStyle = '#ffffff55';
      g.fillRect(x, y, .8 * S, H * .065);
      g.fillStyle = '#00000030';
      g.fillRect(x + .8 * S, y, 1.6 * S, H * .065);
    }
  }
  // A PNG keeps the gaps between the teeth clear
  return new Promise(done => canvas.toBlob(blob => done(blob ? URL.createObjectURL(blob) : canvas.toDataURL())));
}

/** Splits a title that is too wide for one line into two lines of about the same width. */
function balance(g: CanvasRenderingContext2D, title: string, width: number): string[] {
  const words = title.split(' ');
  if (words.length < 2 || g.measureText(title).width <= width) return [title];
  let best = [title], worst = Infinity;
  for (let i = 1; i < words.length; i++) {
    const pair = [words.slice(0, i).join(' '), words.slice(i).join(' ')];
    const wide = Math.max(...pair.map(line => g.measureText(line).width));
    if (wide < worst) { worst = wide; best = pair; }
  }
  return best;
}
