// The opening of a draft or sealed event: the boosters rise on a dark screen, tear open, and deal their cards onto the real cards' places

import { SEAL, packPicture } from '../boosterprint';
import { sleeveUrl } from '../avatarsleeves';
import { rememberedSleeve } from '../identity';
import { make, reducedMotion } from '../dom';
import { FIRST_CARD, FLIGHT, SLICES, STAGGER, fly, tear } from '../packopen';
import { t } from '../text';
import type { PacksOpened } from '../protocol';

export interface PackIntro {
  title: string;
  line: string;
  /** The booster picture's image key, or null for Forge's own pack with the title on it. */
  packImage: string | null;
  packs: number;
}

const TURN = 440;
/** A card turns face up no sooner than this after the card before it. */
const FLIP_GAP = 70;
/** A sealed pool shows at most this many packs, and this many cards running on below the visible ones. */
const MAX_PACKS = 6;
const MAX_UNSEEN = 10;
/** Offsets that make a row of sealed packs look set down by hand: x, y, angle, and extra time to rise. */
const ROW_JITTER = [[-6, 10, -3, 60], [4, -8, 2, -40], [-3, 14, -1, 110], [7, -4, 3, 0], [-5, 6, -2, 80], [3, -12, 4, -20]];
const ROW_RISE = [0, 180, 70, 320, 140, 260];
/** When each sealed pack tears, after the first. The middle packs go first and the ends last. */
const ROW_TEAR = [380, 140, 0, 230, 520, 660];

/** A sealed event's intro, and whether the server has opened its packs. A pool that failed to open never plays it. */
let pending: { intro: PackIntro; opened: boolean } | null = null;

export function sealedIntro(title: string, packs: number): PackIntro {
  return { title, line: [t('lblSealed'), t('lblWebSetupNPacks', packs), t('lblWebSetupFortyOrMore')].join(' · '), packImage: null, packs };
}

/** Keeps the intro of the sealed event this browser's form is opening. */
export function expectSealedIntro(intro: PackIntro): void {
  pending = { intro, opened: false };
}

/** The server opened a sealed pool's packs. An online event names its product. Offline, the intro kept from the form is used, and nothing plays without one. */
export function packsOpened(msg: PacksOpened): void {
  const packImage = msg.packImage ?? null;
  if (msg.product) pending = { intro: { ...sealedIntro(msg.product, msg.packs), packImage }, opened: true };
  else if (pending) pending = { intro: { ...pending.intro, packImage }, opened: true };
}

/** The sealed intro to play now, once, when the packs it was kept for are open. */
export function takeSealedIntro(): PackIntro | null {
  if (!pending?.opened) return null;
  const { intro } = pending;
  pending = null;
  return intro;
}

interface BuiltPack { pk: HTMLElement; inner: HTMLElement; body: HTMLElement; top: HTMLElement; zip: HTMLElement; mouth: HTMLElement; seal: number }

function buildPack(width: number, picture: string): BuiltPack {
  const pk = make('div', 'pi-pack');
  pk.style.width = `${width}px`;
  const inner = make('div', 'pi-pack-in');
  const seal = SEAL;
  const top = make('div', 'pi-top');
  top.style.height = `${seal}%`;
  const w = width / SLICES;
  for (let i = 0; i < SLICES; i++) {
    const slice = make('div', 'pi-slice');
    // Each strip paints only its own piece of the picture, so no part of it can show outside the strip
    Object.assign(slice.style, { left: `${i * w}px`, width: `${w + .6}px`, backgroundImage: `url("${picture}")`, backgroundSize: `${width}px auto`, backgroundPosition: `${-i * w}px 0` });
    top.append(slice);
  }
  const body = make('img', 'pi-body');
  body.src = picture;
  body.alt = '';
  body.style.clipPath = `inset(${seal}% 0 0 0)`;
  const zip = make('div', 'pi-zip'), mouth = make('div', 'pi-mouth');
  zip.style.top = mouth.style.top = `${seal}%`;
  inner.append(mouth, body, top, zip);
  pk.append(inner);
  return { pk, inner, body, top, zip, mouth, seal };
}

/**
 * Plays the intro over the screen. grid holds the cards the packs deal, and is hidden until they land. tiles are the cards to deal to,
 * in reading order. unseen is how many more cards the pool holds below them. fold is the heading a draft's waiting packs fold into.
 * Sealed passes null, and every pack opens, in a row.
 * Returns a function that ends the intro at once.
 */
export function playPackIntro(intro: PackIntro, grid: HTMLElement, tiles: HTMLElement[], unseen: number, fold: HTMLElement | null): () => void {
  if (reducedMotion() || !tiles.length) return () => { };
  const animations: Animation[] = [];
  const anim = (el: Element, frames: Keyframe[], opts: KeyframeAnimationOptions) => {
    const a = el.animate(frames, { fill: 'both', easing: 'cubic-bezier(.2,.8,.2,1)', ...opts });
    animations.push(a);
    return a;
  };
  const layer = make('div', 'pack-intro');
  const scrim = make('div', 'pi-scrim');
  const title = make('div', 'pi-title');
  title.append(Object.assign(make('div', 'pi-set'), { textContent: intro.title }), Object.assign(make('div', 'pi-line'), { textContent: intro.line }));
  const skip = Object.assign(make('div', 'pi-skip'), { textContent: t('lblSkip') });
  const flyers = make('div', 'pi-flyers'), packs = make('div', 'pi-packs');
  layer.append(scrim, title, flyers, packs, skip);
  grid.style.opacity = '0';
  document.body.append(layer);
  let timer = 0;
  let ended = false;
  const end = () => {
    if (ended) return;
    ended = true;
    clearTimeout(timer);
    animations.forEach(a => { try { a.cancel(); } catch { /* already gone */ } });
    grid.style.opacity = '';
    layer.remove();
    removeEventListener('keydown', onKey, true);
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' || e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); end(); }
  };
  layer.addEventListener('click', end);
  addEventListener('keydown', onKey, true);

  // A draft opens one pack, so its size is known. A sealed pool's packs are only estimated, so their count is left off
  void packPicture({ booster: intro.packImage, title: intro.title, cards: fold ? tiles.length : undefined }).then(picture => {
    if (ended) return;
    run(picture);
  });

  function run(picture: string): void {
    const draft = !!fold;
    const vw = innerWidth, vh = innerHeight, k = Math.min(vw / 1440, vh / 900, 1.15);
    const count = draft ? Math.max(1, Math.min(3, intro.packs)) : Math.max(1, Math.min(MAX_PACKS, intro.packs));
    const pw = (draft ? 210 : 150) * k;
    const cx = vw / 2, cy = vh * (draft ? .52 : .53);
    const rest = draft ? 1650 : 1800, tearAt = draft ? 2300 : 2050;
    const ps = Array.from({ length: count }, (_, i) => {
      const p = buildPack(pw, picture);
      packs.append(p.pk);
      const ph = p.pk.offsetHeight || pw * 1.9;
      const off = i - (count - 1) / 2;
      const j = draft ? [0, 0, 0, 0] : ROW_JITTER[i];
      const spread = (draft ? 200 : 165) * k, fan = draft ? 11 : 4;
      const x = cx - pw / 2 + off * spread + j[0] * k, y = cy - ph / 2 + Math.abs(off) * (draft ? 14 : 6) * k + j[1] * k, r = off * fan + j[2];
      anim(p.pk, [{ transform: `translate(${cx - pw / 2 + j[0] * 3}px, ${vh + 20}px) rotate(${-j[2] * 2}deg)`, opacity: 0 },
        { transform: `translate(${x}px, ${y}px) rotate(${r}deg)`, opacity: 1 }], { duration: 620 + j[3], delay: 380 + (draft ? i * 120 : ROW_RISE[i]) });
      return { ...p, x, y, r, ph, scale: 1, open: 0 };
    });
    let openers = ps;
    if (draft && ps.length > 1) {
      // A draft opens one pack. The others fold into the "Pack 1" heading, and the first grows a little in the centre
      const head = fold!.getBoundingClientRect();
      const first = ps[Math.floor((ps.length - 1) / 2)];
      ps.filter(p => p !== first).forEach((p, n) => {
        anim(p.pk, [{ transform: `translate(${p.x}px, ${p.y}px) rotate(${p.r}deg) scale(1)`, opacity: 1 },
          { transform: `translate(${head.left + 8 - pw * .45}px, ${head.top + head.height / 2 - p.ph * .45}px) rotate(0deg) scale(.1)`, opacity: 0 }],
        { duration: 480, delay: rest + n * 70, easing: 'cubic-bezier(.5,0,.2,1)', fill: 'forwards' });
      });
      // These fill forwards only, so they do not hold the packs in place while the packs rise
      anim(first.pk, [{ transform: `translate(${first.x}px, ${first.y}px) scale(1)` }, { transform: `translate(${cx - pw / 2}px, ${cy - first.ph / 2}px) scale(1.1)` }],
        { duration: 420, delay: rest, fill: 'forwards' });
      Object.assign(first, { x: cx - pw / 2, y: cy - first.ph / 2, r: 0, scale: 1.1 });
      openers = [first];
    }
    const perPack = Math.ceil((tiles.length + Math.min(unseen, MAX_UNSEEN)) / openers.length);
    openers.forEach(p => {
      p.open = tearAt + (draft ? 0 : ROW_TEAR[ps.indexOf(p)]);
      tear({ pk: p.inner, top: p.top, zip: p.zip, mouth: p.mouth, seal: p.seal }, pw, p.open);
      animations.push(...p.pk.getAnimations({ subtree: true }));
      // The emptied wrapper fades once its cards are out
      const gone = p.open + FIRST_CARD + perPack * STAGGER + 120;
      anim(p.body, [{ opacity: 1 }, { opacity: 0 }], { duration: 520, delay: gone, easing: 'ease-in', fill: 'forwards' });
      anim(p.mouth, [{ opacity: 1 }, { opacity: 0 }], { duration: 520, delay: gone, easing: 'ease-in', fill: 'forwards' });
    });

    const sleeve = sleeveUrl(rememberedSleeve() ?? 0);
    const sample = tiles[0].getBoundingClientRect();
    const targets: { rect: DOMRect; src: string; sink: boolean }[] = tiles.map(tile => ({
      rect: tile.getBoundingClientRect(), src: tile.querySelector('img:not(.sym)')?.getAttribute('src') ?? '', sink: false }));
    const gridBox = grid.getBoundingClientRect();
    for (let i = 0; i < Math.min(unseen, MAX_UNSEEN); i++) {
      // A card below the visible ones runs on past the bottom of the grid and fades, so the pool reads as larger than the screen
      const col = i % 4;
      targets.push({ rect: new DOMRect(gridBox.left + col * (sample.width + 12), gridBox.bottom - sample.height * .3, sample.width, sample.height),
        src: targets[i % tiles.length].src, sink: true });
    }
    const starts: { turn: HTMLElement; start: number }[] = [];
    targets.forEach((target, i) => {
      const p = openers[i % openers.length], n = draft ? i : Math.floor(i / openers.length);
      const x = make('div', 'pi-card'), y = make('div', 'pi-card-y'), turn = make('div', 'pi-card-turn');
      const back = make('div', 'pi-back');
      back.style.backgroundImage = `url("${sleeve}")`;
      const face = make('img', 'pi-face');
      // A picture that has not been downloaded yet lands as the sleeve, as the real tile hides its broken picture
      face.onerror = () => { face.onerror = null; face.src = sleeve; };
      face.src = target.src;
      face.alt = '';
      turn.append(back, face);
      y.append(turn);
      x.append(y);
      Object.assign(x.style, { left: `${target.rect.left}px`, top: `${target.rect.top}px`, width: `${target.rect.width}px`, height: `${target.rect.height}px` });
      flyers.append(x);
      const mouthX = p.x + pw / 2, mouthY = p.y + p.ph * p.seal / 100 * p.scale;
      const dx = mouthX - (target.rect.left + target.rect.width / 2), dy = mouthY - (target.rect.top + target.rect.height / 2);
      const start = p.open + FIRST_CARD + n * STAGGER;
      // The card is scaled down while it climbs, so its rise is divided by that scale to start at the pack's mouth
      fly(x, y, dx, (dy + 96 * k) / .6, (dy - 118 * k) / .72, start, ((i % perPack) - (perPack - 1) / 2) * 3.2);
      animations.push(...x.getAnimations(), ...y.getAnimations());
      if (target.sink) anim(x, [{ opacity: 1 }, { opacity: 0 }], { duration: 300, delay: start + FLIGHT - 120, fill: 'forwards' });
      starts.push({ turn, start });
    });
    let flipAt = 0, landed = 0;
    starts.sort((a, b) => a.start - b.start).forEach((s, i) => {
      flipAt = Math.max(s.start + FLIGHT * .6, i ? flipAt + FLIP_GAP : 0);
      anim(s.turn, [{ transform: 'rotateY(180deg)' }, { transform: 'rotateY(0deg)' }], { duration: TURN, delay: flipAt, easing: 'cubic-bezier(.4,0,.2,1)' });
      landed = Math.max(landed, flipAt + TURN);
    });

    // The heading stays up while the packs wait. Once the cards are down the screen lifts and the real cards take their place
    const swap = landed + 150, T = swap + 1100, at = (ms: number) => Math.min(1, Math.max(0, ms / T));
    anim(scrim, [{ opacity: 0 }, { opacity: .9, offset: at(350) }, { opacity: .9, offset: at(landed - 500) }, { opacity: 0, offset: at(landed + 200) }, { opacity: 0 }], { duration: T, easing: 'linear' });
    anim(title, [{ opacity: 0, transform: 'translateY(14px)' }, { opacity: 0, offset: at(250) }, { opacity: 1, transform: 'none', offset: at(750) },
      { opacity: 1, offset: at(tearAt - 100) }, { opacity: 0, offset: at(tearAt + 250) }, { opacity: 0 }], { duration: T, easing: 'linear' });
    anim(skip, [{ opacity: 0 }, { opacity: 0, offset: at(500) }, { opacity: .7, offset: at(900) }, { opacity: .7, offset: at(landed - 500) }, { opacity: 0, offset: at(landed + 200) }, { opacity: 0 }],
      { duration: T, easing: 'linear' });
    window.setTimeout(() => { if (!ended) settle(); }, swap);

    const fadeIn = (el: Element) => anim(el, [{ opacity: 0 }, { opacity: 1 }], { duration: 1000, easing: 'cubic-bezier(.3,0,.3,1)', fill: 'backwards' });
    /** The landed cards are the same pictures in the same places as the real cards, so the real ones replace them at once. Only what sits on them fades in. */
    function settle(): void {
      flyers.remove();
      grid.style.opacity = '';
      const dealt = new Set<Element>(tiles);
      for (const tile of tiles) {
        const face = tile.querySelector('img:not(.sym)');
        for (const part of tile.children) if (!face || (part !== face && !part.contains(face))) fadeIn(part);
      }
      for (const other of grid.children) if (!dealt.has(other) && !tiles.some(tile => other.contains(tile))) fadeIn(other);
    }
    timer = window.setTimeout(end, T);
  }
  return end;
}
