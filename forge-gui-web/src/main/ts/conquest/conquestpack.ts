// A booster pack above its cards, animated in code with the Web Animations API. The server decides which cards it holds

import { skinIconUrl, imageUrl } from '../images';
import { artUrl } from '../sleeves';
import { CHAOS_MARK } from './conquestwheel';
import type { PackCard } from '../protocol';
import { reducedMotion } from '../dom';
import { SEAL, packPicture } from '../boosterprint';
import { FIRST_CARD, FLIGHT, SLICES, STAGGER, fly, tear } from '../packopen';

interface PackSpec {
  name: string;
  /** The line under the name. It gives the kind of pack, or for a chaos pack the world its cards come from. */
  sub: string;
  /** The art's image key, or null for a chaos pack, which shows the chaos symbol instead. */
  art: string | null;
  chaos: boolean;
  /** The booster picture's image key, for a pack from one set. */
  booster: string | null;
  cards: PackCard[];
  hint: string;
}

interface PackHooks {
  /** A card has turned face up at this position on the screen. */
  flipped(card: PackCard, at: DOMRect): void;
  /** The pack has been clicked and is opening. */
  opening(): void;
  /** Every card is face up. */
  shown(): void;
}

export interface Pack {
  /** Opens the pack. The promise resolves when every card is face up. */
  open(): Promise<void>;
  /** Shows every card face up at once, and ends any animation in progress. */
  finish(): void;
}

/** The pack's width. The peel calculation and the stylesheet both use it. */
const PACK_W = 170;
const SOFT = 'cubic-bezier(.22,.8,.26,1)';
/** The delay before a card of each rarity flips, counted from the flip of the card before it. */
const GAP: Record<string, number> = { Common: 70, BasicLand: 70, Uncommon: 200, Rare: 500, Special: 500, MythicRare: 900 };
export const isRare = (rarity: string): boolean => rarity === 'Rare' || rarity === 'Special' || rarity === 'MythicRare';

// The outline of the pack's crimped picture, used as the shape of its glow
const teeth = (n: number, y0: string, y1: string): string[] => Array.from({ length: n + 1 }, (_, k) => `${(100 * k / n).toFixed(1)}% ${k % 2 ? y0 : y1}`);
const OUTLINE = `polygon(${teeth(50, '0%', '1.2%').join(', ')}, ${teeth(50, '100%', '98.8%').reverse().join(', ')})`;

function el(spec: string, style: Partial<CSSStyleDeclaration> = {}, ...kids: (Node | string | null)[]): HTMLElement {
  const [tag, ...cls] = spec.split('.');
  const e = document.createElement(tag || 'div');
  if (cls.length) e.className = cls.join(' ');
  Object.assign(e.style, style);
  e.append(...kids.filter((k): k is Node | string => k !== null));
  return e;
}

export function mountPack(host: HTMLElement, spec: PackSpec, hooks: PackHooks): Pack {
  const cards = spec.cards;
  const up = new Set<number>();
  const slots = cards.map((c, i) => {
    const face = el('div.cq-rv-face');
    const img = document.createElement('img');
    img.alt = c.name;
    img.src = imageUrl(c.image);
    face.append(img);
    const plus = c.shards ? el('span.cq-rv-plus', {}, '+') : null;
    if (plus) {
      const shard = document.createElement('img');
      shard.alt = '';
      shard.src = skinIconUrl('IMG_AETHER_SHARD');
      plus.append(shard, String(c.shards));
    }
    const cls = ['cq-rv-card', c.shards && 'dup', isRare(c.rarity) && 'rare', c.rarity === 'MythicRare' && 'mythic'].filter(Boolean).join('.');
    const card = el(`div.${cls}`, {}, el('div.cq-rv-y', {}, el('i.rise'), el('div.cq-rv-in', {}, el('div.cq-rv-face.cq-rv-back'), face), plus));
    card.setAttribute('role', 'img');
    card.setAttribute('aria-label', c.name);
    return el('div.cq-rv-slot', {}, card);
  });
  const grid = el('div.cq-rv-grid', {}, ...slots);
  grid.style.setProperty('--per-row', String(Math.min(8, Math.max(1, cards.length))));

  // The seal is cut into strips, so it can roll up behind the tear. Each strip paints only its own piece of the picture
  const slices = Array.from({ length: SLICES }, (_, i) => el('div.cq-pk-slice', { left: `${i * PACK_W / SLICES}px`, width: `${PACK_W / SLICES + .6}px`,
    backgroundSize: `${PACK_W}px auto`, backgroundPosition: `${-i * PACK_W / SLICES}px 0` }));
  const top = el('div.cq-pk-top', { height: `${SEAL}%` }, ...slices);
  const body = document.createElement('img');
  body.className = 'cq-pk-body';
  body.alt = '';
  body.style.clipPath = `inset(${SEAL}% 0 0 0)`;
  const whole = el('div.cq-pk-whole', {}, body, top);
  const zip = el('div.cq-pk-zip', { top: `${SEAL}%` }), mouth = el('div.cq-pk-mouth', { top: `${SEAL}%` });
  const glow = [el('div.cq-pk-glow.far', { clipPath: OUTLINE }), el('div.cq-pk-glow.near', { clipPath: OUTLINE }),
    ...[20, 50, 78].map((x, i) => el('i.cq-pk-speck', { left: `${x}%`, animationDelay: `${-i * .9}s` }))];
  const pk = el(spec.chaos ? 'button.cq-pk.chaos' : 'button.cq-pk', {}, ...glow, mouth, el('div.cq-pk-lit', {}, whole), zip);
  pk.setAttribute('aria-label', spec.hint);
  const hint = el('p.cq-pk-hint', {}, spec.hint);
  const cell = el('div.cq-bp-cell', {}, pk, hint);
  const stage = el('div.cq-bp-stage', {}, grid, cell);
  host.replaceChildren(stage);
  // Hidden until its picture is ready, so it never opens empty
  cell.style.visibility = 'hidden';
  const ready = packPicture({ booster: spec.booster, title: spec.name, strip: spec.chaos ? spec.sub : undefined,
    art: spec.art ? artUrl(spec.art) : null, mark: spec.chaos ? CHAOS_MARK.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" fill="none" stroke="#b79cff" stroke-width="2.4" stroke-linecap="round"') : null }).then(picture => {
    body.src = picture;
    slices.forEach(slice => { slice.style.backgroundImage = `url("${picture}")`; });
    cell.style.visibility = '';
  });
  let pictured = false;
  void ready.then(() => { pictured = true; });

  let opening: Promise<void> | null = null;
  let settle: () => void = () => { };
  const timers: number[] = [];

  const turnUp = (i: number) => {
    if (up.has(i)) return;
    up.add(i);
    const card = slots[i].firstElementChild as HTMLElement;
    card.classList.add('up');
    // Once the card is face up and landed, it shows a preview on hover like any other card
    card.dataset.image = cards[i].image;
    hooks.flipped(cards[i], slots[i].getBoundingClientRect());
  };

  function finish(): void {
    timers.splice(0).forEach(clearTimeout);
    // The pack's idle animations repeat forever and cannot be finished, so they are cancelled
    stage.getAnimations({ subtree: true }).forEach(a => { try { a.finish(); } catch { a.cancel(); } });
    cell.remove();
    slots.forEach(slot => slot.classList.add('shown'));
    cards.forEach((_, i) => turnUp(i));
    opening ??= Promise.resolve();
    settle();
    hooks.shown();
  }

  function open(): Promise<void> {
    if (!pictured) return ready.then(open);
    if (opening) return opening;
    opening = new Promise<void>(done => { settle = done; });
    hooks.opening();
    if (reducedMotion()) {
      finish();
      return opening;
    }
    hint.remove();
    pk.style.animation = 'none';
    pk.classList.add('open');
    glow.forEach(g => g.animate([{ opacity: getComputedStyle(g).opacity }, { opacity: 0 }], { duration: 380, fill: 'forwards' }));
    const box = pk.getBoundingClientRect();
    const n = cards.length, mid = (n - 1) / 2;
    const px = box.left + box.width / 2;

    tear({ pk, top, zip, mouth, seal: SEAL }, box.width);
    pk.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 460, delay: FIRST_CARD + n * STAGGER + 120, easing: 'ease-in', fill: 'forwards' });

    let flipAt = 0;
    const flips = slots.map((slot, i) => {
      const card = slot.firstElementChild as HTMLElement, y = card.firstElementChild as HTMLElement, inner = y.firstElementChild as HTMLElement;
      const r = slot.getBoundingClientRect();
      const dx = px - (r.left + r.width / 2), mouthDy = box.top + box.height * SEAL / 100 - (r.top + r.height / 2);
      const start = FIRST_CARD + i * STAGGER;
      slot.classList.add('shown');
      fly(card, y, dx, mouthDy + 96, mouthDy - 118, start, (i - mid) * 3.2);
      // A card flips as it lands, but no sooner after the previous card than its rarity's delay allows
      const rare = isRare(cards[i].rarity);
      flipAt = Math.max(start + FLIGHT * .6, i ? flipAt + (GAP[cards[i].rarity] ?? 70) : 0);
      const turn = rare ? 700 : 440;
      timers.push(window.setTimeout(() => turnUp(i), flipAt + turn / 2));
      return inner.animate([{ transform: 'rotateY(180deg)' }, { transform: 'rotateY(0deg)' }], { duration: turn, delay: flipAt, easing: rare ? SOFT : 'cubic-bezier(.4,0,.2,1)', fill: 'forwards' }).finished;
    });
    void Promise.all(flips).then(() => {
      if (!cell.isConnected) return;
      cell.remove();
      cards.forEach((_, i) => turnUp(i));
      settle();
      hooks.shown();
    }, () => { });
    return opening;
  }

  pk.addEventListener('click', e => { e.stopPropagation(); void open(); });
  return { open, finish };
}
