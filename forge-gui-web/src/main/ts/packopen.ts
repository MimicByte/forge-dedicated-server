// The tear and the card flight of an opening booster pack. Planar Conquest's pack and a limited event's opening both use them

export const SLICES = 16;
/** How long a card takes from the pack's mouth to its place. */
export const FLIGHT = 860;
/** The time between one card leaving the pack and the next. */
export const STAGGER = 78;
/** When the first card leaves, counted from the start of the tear. */
export const FIRST_CARD = 700;
const TEAR = 640;

export interface Seal {
  /** The pack. It wobbles as it tears, and the flecks of foil are added to it. */
  pk: HTMLElement;
  /** The seal, cut into SLICES strips so that it can roll up behind the tear. */
  top: HTMLElement;
  /** The point of light at the front of the tear. */
  zip: HTMLElement;
  /** The light from the opening, as far as the tear has gone. */
  mouth: HTMLElement;
  /** The seal's height, as a percentage of the pack's height. */
  seal: number;
}

/** Tears the seal from one corner to the other. The freed seal is pulled up and back in an arc behind the tear, then flung away. */
export function tear({ pk, top, zip, mouth, seal }: Seal, width: number, delay = 0): void {
  const W = width, R = 84, S = 30, w = W / SLICES;
  const prog = (u: number) => { const v = Math.max(0, (u - .14) / .86); return v < .5 ? 2 * v * v : 1 - (-2 * v + 2) ** 2 / 2; };
  const sample = <T>(fn: (xk: number) => T): T[] => Array.from({ length: S + 1 }, (_, j) => fn(prog(j / S) * (W + 8)));
  [...top.children].forEach((slice, i) => {
    const x = (i + .5) * w;
    slice.animate(sample(xk => {
      const s = Math.max(0, xk - x), a = s / R;
      return { transform: `translate(${s ? xk - R * Math.sin(a) - x : 0}px, ${-R * (1 - Math.cos(a))}px) rotate(${a}rad)` };
    }), { duration: TEAR, delay, fill: 'forwards' });
  });
  top.animate([{ transform: 'none', opacity: 1 }, { transform: 'translate(60px, -90px) rotate(70deg)', opacity: 1, offset: .5 }, { transform: 'translate(150px, 40px) rotate(200deg)', opacity: 0 }],
    { duration: 520, delay: delay + TEAR + 30, easing: 'cubic-bezier(.3,.6,.6,1)', fill: 'forwards' });
  zip.animate(sample(xk => ({ left: `${xk}px`, opacity: 1 })), { duration: TEAR, delay, fill: 'forwards' });
  zip.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 140, delay: delay + TEAR, fill: 'forwards' });
  mouth.animate(sample(xk => ({ opacity: 1, clipPath: `inset(-70px ${Math.max(0, 100 - 100 * xk / W)}% -70px -70px)` })), { duration: TEAR, delay, fill: 'forwards' });
  pk.animate([{ transform: 'none' }, { transform: 'rotate(-3deg) translate(-5px, 2px)', offset: .13 }, { transform: 'rotate(2.5deg) translate(4px, -2px)', offset: .55 },
    { transform: 'rotate(-1.2deg) translate(-1px, 3px)', offset: .82 }, { transform: 'none' }], { duration: TEAR + 260, delay, easing: 'ease-in-out' });
  for (let k = 0; k < 14; k++) {
    const u = .2 + k * .055, side = (k * 37) % 17 - 8, rise = 26 + (k * 53) % 34;
    const fleck = document.createElement('i');
    fleck.className = 'pk-fleck';
    Object.assign(fleck.style, { left: `${prog(u) * W}px`, top: `${seal}%` });
    pk.append(fleck);
    // A fleck waits hidden until the tear reaches it, so it fills forwards only
    fleck.animate([{ transform: 'translate(0, 0) rotate(0deg)', opacity: 1 }, { transform: `translate(${side * 2.4}px, ${-rise}px) rotate(200deg)`, opacity: 1, offset: .4, easing: 'ease-in' },
      { transform: `translate(${side * 4.4}px, ${50 + rise}px) rotate(520deg)`, opacity: 0 }], { duration: 620, delay: delay + u * TEAR, easing: 'ease-out', fill: 'forwards' });
  }
}

/**
 * Flies a card from a pack's mouth to its place. card moves sideways and scales, y moves up and down, on separate curves, so the path bends.
 * dx is the mouth's distance from the card's place. rise and peak are y's offsets as the card leaves and at the top of its arc.
 */
export function fly(card: HTMLElement, y: HTMLElement, dx: number, rise: number, peak: number, start: number, lean: number): void {
  // The flight ends where the card rests, so it does not fill forwards. A card with an animation left on it gets no hover preview
  card.animate([{ transform: `translateX(${dx}px) rotate(0deg) scale(.6)`, opacity: 0 }, { transform: `translateX(${dx}px) rotate(0deg) scale(.6)`, opacity: 1, offset: .06, easing: 'cubic-bezier(.3,.6,.5,1)' },
    { transform: `translateX(${dx * .86}px) rotate(${lean}deg) scale(.72)`, opacity: 1, offset: .34, easing: 'cubic-bezier(.45,0,.3,1)' }, { transform: 'translateX(0px) rotate(0deg) scale(1)', opacity: 1 }],
  { duration: FLIGHT, delay: start, fill: 'backwards' });
  y.animate([{ transform: `translateY(${rise}px)`, easing: 'cubic-bezier(.3,.7,.4,1)' }, { transform: `translateY(${peak}px)`, offset: .34, easing: 'cubic-bezier(.45,0,.3,1)' }, { transform: 'translateY(0px)' }],
    { duration: FLIGHT, delay: start, fill: 'both' });
}
