// The reward is already in the save and the server sets its steps. This file only shows the steps and tells the bar what is not shown yet

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { skinIconUrl, imageUrl } from '../images';
import { mountPack, type Pack } from '../conquest/conquestpack';
import { mountWheel, spotFor, type Wheel, type WheelOutcome } from '../conquest/conquestwheel';
import { pendingAmounts } from '../conquest/conquestpending';
import type { Reward, RewardStep } from '../protocol';
import { t, formatNumber } from '../text';
import { byId, make, reducedMotion } from '../dom';
import { Backdrop } from '../dialog';
import { boundKeys } from '../settings';
import { keyName } from '../keys';

/** The amounts the reveal has not shown yet, by the icon of the balance they were paid into. */
export type Owed = Record<string, number>;

export const wheelLabels = (): Record<WheelOutcome, string> => ({
  CHAOS: t('lblChaos'), BOOSTER: t('lblWebConquestWheelBooster'), DOUBLE_BOOSTER: t('lblWebConquestWheelBoosters'),
  SHARDS: t('lblWebConquestWheelShards'), DOUBLE_SHARDS: t('lblWebConquestWheelDoubleShards'), PLANESWALK: t('lblWebConquestWheelEmblems'),
});

/** A step with nothing of its own to show. The packs before it have already shown the shards their duplicates became. */
const silent = (step: RewardStep): boolean => step.kind === 'DUPLICATE_SHARDS';

export function Reveal({ reward, onOwed, done }: { reward: Reward; onOwed: (owed: Owed) => void; done: () => void }) {
  const steps = reward.steps;
  // A first conquest's emblems are shown above the wheel that follows, so the two steps are shown as one
  const first = steps[0]?.kind === 'CONQUER_EMBLEMS' && steps[1]?.kind === 'WHEEL' ? 1 : 0;
  const [at, setAt] = useState(first);
  /** The shards from the current pack's duplicates that have reached the bar. */
  const [released, setReleased] = useState(0);
  const [zoom, setZoom] = useState<string | null>(null);
  const finished = useRef(false);
  const step = steps[at];

  const next = () => {
    let i = at + 1;
    while (i < steps.length && silent(steps[i])) i++;
    setReleased(0);
    setAt(i);
    // Tell the server at once, not after the next paint. A reload in between would be sent the same reward again
    if (i >= steps.length) finish();
  };
  const finish = () => {
    if (!finished.current) {
      finished.current = true;
      done();
    }
  };
  // A reward with nothing to show ends as soon as it arrives
  useEffect(() => { if (at >= steps.length) finish(); }, []);
  // Every card the reward shows is loaded now, so each card has its picture by the time a pack lets it out
  useEffect(() => { for (const s of steps) for (const c of s.cards ?? []) new Image().src = imageUrl(c.image); }, []);
  // The dock is outside the campaign page, so the reveal makes the dock inert itself while the reveal is open
  useEffect(() => {
    const dock = byId('dock');
    dock.inert = true;
    return () => { dock.inert = false; };
  }, []);

  // The current step's amount counts as shown. A pack is the exception, because its shards are added card by card
  const packShards = step?.kind === 'BOOSTER' ? (step.cards ?? []).reduce((sum, c) => sum + c.shards, 0) : 0;
  const owed = pendingAmounts(steps, at + 1);
  if (step?.kind === 'BOOSTER' && step.icon) owed[step.icon] = (owed[step.icon] ?? 0) + packShards - released;
  // Runs before the first paint. Otherwise the bar would show, for one frame, everything the reveal is about to give
  useLayoutEffect(() => onOwed(owed), [JSON.stringify(owed)]);

  if (!step) return null;
  return (
    <Backdrop cls="cq-reveal">
      {step.kind === 'WHEEL' && <WheelStop key={at} step={step} emblem={first === 1 && at === 1 ? steps[0].amount : 0} next={next} />}
      {step.kind === 'BOOSTER' && <PackStop key={at} step={step} release={n => setReleased(r => r + n)} next={next} />}
      {step.kind === 'CONQUER_EMBLEMS' && <Gift icon="IMG_PW_BADGE_COMMON" amount={step.amount} title={t('lblWebConquestFirstConquest')} button={t('lblGreat')} next={next} />}
      {step.kind === 'SHARDS' && <Gift icon="IMG_AETHER_SHARD" amount={step.amount} title={t('lblReceivedAetherShards')} button={t('lblGreat')} next={next} />}
      {step.kind === 'EMBLEMS' && <Gift icon="IMG_PW_BADGE_COMMON" amount={step.amount} title={t('lblReceivedBonusPlaneswalkEmblems')} button={t('lblGreat')} next={next} />}
      {step.kind === 'ALL_PLANES_UNLOCKED' && <Gift icon="IMG_PW_BADGE_COMMON" title={t('lblAllPlanesUnlocked')} text={t('lblAllPlanesUnlockedNotify')} button={t('lblOK')} next={next} />}
      {step.kind === 'CHAOS_BATTLE' && <Gift icon="IMG_MULTIVERSE" title={t('lblChaosApproaching')} text={t('lblWebConquestNoRefusal')} button={t('lblOK')} next={next} />}
      {step.kind === 'MESSAGE' && <Gift icon={step.icon} title={step.title ?? ''} text={step.text} lines={step.lines} button={t('lblOK')} next={next} />}
      {(step.kind === 'CARDS' || step.kind === 'REGION_COMMANDER') && <CardsStop key={at} step={step} zoom={setZoom} next={next} />}
      {zoom && <div class="backdrop cq-zoom-layer" onClick={e => { e.stopPropagation(); setZoom(null); }}><img class="cq-zoom" alt="" src={imageUrl(zoom)} /></div>}
    </Backdrop>
  );
}

/** Runs the step's default button when the OK key is pressed, as the wheel's spin does. null turns the key off. */
function useOkKey(run: (() => void) | null): string {
  const key = boundKeys().ok;
  useEffect(() => {
    if (!run) return;
    const onKey = (e: KeyboardEvent) => {
      // A held key would run through every step. A zoomed card takes the key first
      if (e.key !== key || e.repeat || e.ctrlKey || e.altKey || e.metaKey || e.target instanceof HTMLInputElement || document.querySelector('.cq-zoom-layer')) return;
      // Space would also press a focused button, so the button is not pressed twice
      e.preventDefault();
      run();
    };
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [run, key]);
  return keyName(key);
}

/** A message line that ends in an amount of credits, as Quest's results write them. It captures the words and the amount separately. */
const CREDIT_LINE = /^(.*?):?\s+(-?\d+) credits?\.?$/;

/** One of the wheel's simple outcomes, or a notice, shown as a small dialog with one layout. lines are a breakdown, each ending in its amount. */
function Gift({ icon, amount, title, text, lines, button, next }: {
  icon?: string; amount?: number; title: string; text?: string; lines?: string[]; button: string; next: () => void;
}) {
  return (
    <div class="dialog cq-gift">
      <h3>{title}</h3>
      {icon && <div class="cq-gift-what"><img alt="" src={skinIconUrl(icon)} />{amount !== undefined && <b>{formatNumber(amount)}</b>}</div>}
      {text && <p>{text}</p>}
      {lines && (
        <ul class="cq-gift-lines">
          {lines.map((line, i) => {
            const credit = CREDIT_LINE.exec(line);
            return <li key={i}>{credit ? <><span>{credit[1]}</span><b>{credit[2]}</b></> : <span>{line}</span>}</li>;
          })}
        </ul>
      )}
      <button class="primary" onClick={next}>{button} <kbd>{useOkKey(next)}</kbd></button>
    </div>
  );
}

function CardsStop({ step, zoom, next }: { step: RewardStep; zoom: (image: string) => void; next: () => void }) {
  const lost = step.outcome === 'lost';
  const cards = step.cards ?? [];
  const duplicates = cards.reduce((sum, c) => sum + c.shards, 0);
  return (
    <div class="cq-pack-stop">
      <h3>{step.title}</h3>
      <div class={lost ? 'cq-cards lost' : 'cq-cards'}>
        {cards.map((c, i) => (
          <button key={i} class="cq-cards-card" aria-label={c.name} onClick={() => zoom(c.image)}><img alt={c.name} src={imageUrl(c.image)} /></button>
        ))}
      </div>
      <div class="cq-reveal-foot">
        {duplicates > 0 && (
          <span class="cq-dup-line">{t('lblReceivedAetherShardsForDuplicateCards')}<img alt="" src={skinIconUrl('IMG_AETHER_SHARD')} /><b>{formatNumber(duplicates)}</b></span>
        )}
        <button class="primary cq-big" onClick={next}>{t('lblOK')} <kbd>{useOkKey(next)}</kbd></button>
      </div>
    </div>
  );
}

function WheelStop({ step, emblem, next }: { step: RewardStep; emblem: number; next: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const wheel = useRef<Wheel | null>(null);
  const spot = useRef(spotFor(step.outcome ?? ''));
  const [phase, setPhase] = useState<'ready' | 'spinning' | 'stopped'>('ready');
  useEffect(() => { wheel.current = mountWheel(host.current!, wheelLabels()); }, []);
  const spin = () => {
    setPhase('spinning');
    void wheel.current!.spin(spot.current).then(() => setPhase('stopped'));
  };
  const key = useOkKey(phase === 'ready' ? spin : phase === 'stopped' ? next : null);
  return (
    <div class="cq-wheel-stop">
      {emblem > 0 && <p class="cq-wheel-first"><img alt="" src={skinIconUrl('IMG_PW_BADGE_COMMON')} />{t('lblWebConquestFirstConquest')}</p>}
      <div class="cq-wheel-host" ref={host} />
      {/* The name fades in under the dial once it stops (conquest.css) */}
      <p class="cq-wheel-name">{phase === 'stopped' ? wheelLabels()[step.outcome as WheelOutcome] : ''}</p>
      <div class="cq-reveal-foot">
        {phase === 'stopped'
          ? <button class="primary cq-big" onClick={next}>{t('lblGreat')} <kbd>{key}</kbd></button>
          : <button class="primary cq-big" disabled={phase === 'spinning'} onClick={spin}>{t('lblWebConquestSpin')} <kbd>{key}</kbd></button>}
      </div>
    </div>
  );
}

/** Animates an amount from a card to the bar's balance with that icon. The bar counts the amount when it arrives. */
export function flyShards(from: DOMRect, arrived: () => void, icon = 'IMG_AETHER_SHARD'): void {
  const to = document.querySelector(`.cq-purse .cq-coin[data-icon="${icon}"]`)?.getBoundingClientRect();
  if (!to || reducedMotion()) {
    arrived();
    return;
  }
  const mote = make('img', 'cq-mote');
  mote.alt = '';
  mote.src = skinIconUrl(icon);
  mote.dataset.icon = icon;
  document.body.append(mote);
  const x0 = from.left + from.width / 2, y0 = from.top + from.height * .4;
  mote.animate([{ transform: `translate(${x0}px, ${y0}px) scale(1.4)`, opacity: 1 },
    { transform: `translate(${to.left + 14}px, ${to.top + to.height / 2}px) scale(.8)`, opacity: .9 }],
    { duration: 600, easing: 'cubic-bezier(.5,0,.3,1)' }).finished.then(() => { mote.remove(); arrived(); }, () => mote.remove());
}

function PackStop({ step, release, next }: { step: RewardStep; release: (shards: number) => void; next: () => void }) {
  const host = useRef<HTMLDivElement>(null);
  const pack = useRef<Pack | null>(null);
  const [phase, setPhase] = useState<'sealed' | 'opening' | 'shown'>('sealed');
  // Shards still in flight when the pack closes are already counted. They must not count toward the next pack
  const here = useRef(true);
  useEffect(() => () => { here.current = false; }, []);
  const cards = step.cards ?? [];
  const duplicates = cards.reduce((sum, c) => sum + c.shards, 0);
  useEffect(() => {
    pack.current = mountPack(host.current!, {
      name: step.chaos ? t('lblChaos') : step.pack ?? '', sub: step.chaos ? step.pack ?? '' : t('lblBoosterPack'),
      art: step.chaos ? null : step.art ?? null, chaos: step.chaos, booster: step.packImage ?? null, cards, hint: t('lblWebConquestClickToOpen'),
    }, {
      flipped: (card, rect) => { if (card.shards) flyShards(rect, () => { if (here.current) release(card.shards); }); },
      opening: () => setPhase('opening'),
      shown: () => setPhase('shown'),
    });
  }, []);
  const key = useOkKey(phase === 'shown' ? next : null);
  const title = step.total > 1 ? t('lblReceivedBoosterPackNOfTotal', step.number, step.total).replace('\n', ' ') : t('lblReceivedBoosterPack');
  return (
    // A click anywhere while the cards are moving or flipping shows them all at once
    <div class="cq-pack-stop" onClick={() => { if (phase === 'opening') pack.current?.finish(); }}>
      <h3>{title}</h3>
      <div class="cq-pack-host" ref={host} />
      <div class="cq-reveal-foot">
        {phase === 'shown' && duplicates > 0 && (
          <span class="cq-dup-line">{t('lblReceivedAetherShardsForDuplicateCards')}<img alt="" src={skinIconUrl('IMG_AETHER_SHARD')} /><b>{formatNumber(duplicates)}</b></span>
        )}
        {phase === 'shown'
          ? <button class="primary cq-big" onClick={e => { e.stopPropagation(); next(); }}>{t('lblGreat')} <kbd>{key}</kbd></button>
          : <button onClick={e => { e.stopPropagation(); pack.current?.finish(); }}>{t('lblSkip')}</button>}
      </div>
    </div>
  );
}
