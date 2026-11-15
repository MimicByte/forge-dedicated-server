// Starting points for a probe. A probe is a spec that checks one thing in the real page, as the phone and preview
// specs do. It can also be a throwaway spec written to check something during work. A probe gets a server with
// dev mode on and a saved deck of the player's own. Each starting point returns with the page ready for the check,
// so a probe contains only the check itself:
//
//   import { board, probe } from './probe';
//   probe('haste', async p => {
//     await board(p, 'humanbattlefield=Raging Goblin|SummonSick;Mountain\naibattlefield=Grizzly Bears');
//     await p.snap('board');
//   });
//
// In a match a probe calls the page's own actions and reads its model (act, asking, untilPriority, passUntil,
// p.until). It never presses keys or buttons, because they answer whatever prompt is open at that moment. A wait that
// times out reports what the page was asking and takes a screenshot.
//
// Name the file zz-<anything>.spec.ts (ignored by git). Run it with `FORGE_E2E_PROBES=1 npx playwright test zz-<anything>`.
// Screenshots go to forge-gui-web/target/probe/<title>/. The page's errors are printed as they happen.
//
// The server serves the page from the source folder, so a TS or CSS change needs only `npm run build` before a probe.
// A Java change or a new language key needs the jar rebuilt. The jar cannot be rebuilt while a server runs from it.

import { expect, test, type Browser, type BrowserContextOptions, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { PROBE_DECK, PROBE_SEED, startServer, type Server } from './server';
import { enterName, gameStarted, hostTable } from './steps';
import type { Actions } from '../src/main/ts/actions';
import type { Model } from '../src/main/ts/model';

// main.ts stores the page's actions and model here. A probe drives the game through them
declare global {
  interface Window { forge: { actions: Actions; model: Model } }
}

export { PROBE_DECK };

export interface Probe {
  page: Page;
  browser: Browser;
  server: Server;
  /** Records that a setup stage is done. When the probe ends it prints how long each stage took. */
  mark(stage: string): void;
  /** Saves a screenshot of the page, or of another page such as a guest's, and prints its path. */
  snap(name: string, on?: Page): Promise<void>;
  /** Waits until a test run in the page is true. On timeout it takes a screenshot and fails with what the page was asking. */
  until<A>(what: string, test: (arg: A) => unknown, arg?: A, on?: Page, timeout?: number): Promise<void>;
  /** Takes screenshots in sequence and saves them as one image, to show an animation. */
  frames(name: string, count?: number, everyMs?: number, clip?: { x: number; y: number; width: number; height: number }, on?: Page): Promise<void>;
}

const VIEWPORT = { width: 1440, height: 900 };
/** A phone browser with its toolbars shown. A portrait layout must fit this height. */
export const PHONE: BrowserContextOptions = { viewport: { width: 390, height: 664 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 };
export const SMALL_PHONE: BrowserContextOptions = { viewport: { width: 360, height: 640 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 };
/** A tablet in landscape. It has a touch screen but gets the desktop layout. */
export const WIDE_TOUCH: BrowserContextOptions = { viewport: { width: 1280, height: 800 }, hasTouch: true };
const SHOTS = join(import.meta.dirname, '..', 'target', 'probe');
function listen(page: Page, who: string): void {
  page.on('pageerror', e => console.log(`[${who} page error] ${e.message}`));
  // Card images the test machine has not downloaded always return 404. These errors are not page faults
  page.on('console', m => {
    if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) console.log(`[${who} console] ${m.text()}`);
  });
}

/** Waits from the next frame until all running animations finish. Endless animations, such as a pulse, are ignored. */
export async function settled(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
    const moving = () => document.getAnimations().filter(a => a.playState === 'running' && a.effect?.getComputedTiming().iterations !== Infinity);
    for (let running = moving(); running.length; running = moving()) await Promise.all(running.map(a => a.finished.catch(() => {})));
  });
}

/** A one-line summary of what the page shows and asks, for the error of a wait that timed out. */
export function explain(page: Page): Promise<string> {
  return page.evaluate(() => {
    const m = window.forge?.model;
    if (!m) return 'The page never loaded the game client.';
    const prompt = m.prompt;
    const text = (sel: string) => [...document.querySelectorAll<HTMLElement>(sel)].map(e => e.innerText.replace(/\s+/g, ' ').trim()).filter(Boolean);
    return JSON.stringify({
      where: m.screen === 'playing' ? 'match' : m.screen === 'setup' ? 'lobby' : 'elsewhere',
      prompt: prompt && { message: prompt.message, priority: prompt.priority, ok: prompt.ok, cancel: prompt.cancel,
        selectable: prompt.selectable.length, players: prompt.selectablePlayers.length },
      problems: m.lobby?.problems,
      notices: text('.notice'),
      dialogs: text('#dialog-layer .dialog').map(t => t.slice(0, 160)),
    });
  });
}

// Loading the cards takes most of a server's start time. So each worker starts one server and resets it in the same
// process before each probe. FORGE_E2E_REUSE=0 starts a new server for each probe instead.
const reuse = process.env.FORGE_E2E_REUSE !== '0';
const probeTest = test.extend<object, { shared: Server | null }>({
  shared: [async ({}, use) => {
    if (!reuse) {
      await use(null);
      return;
    }
    const server = await startServer(undefined, PROBE_SEED, true);
    await use(server);
    await server.stop();
  }, { scope: 'worker' }],
});

export function probe(title: string, body: (p: Probe) => Promise<void>, device: BrowserContextOptions = { viewport: VIEWPORT }): void {
  probeTest(title, async ({ browser, shared }) => {
    test.setTimeout(600_000);
    const began = Date.now();
    const stages: string[] = [];
    let last = began;
    const mark = (stage: string) => {
      const now = Date.now();
      stages.push(`${stage} ${((now - last) / 1000).toFixed(1)}`);
      last = now;
    };
    if (shared) await shared.reset(PROBE_SEED);
    const server = shared ?? await startServer(undefined, PROBE_SEED);
    mark('server');
    const context = await browser.newContext(device);
    // A test does not need time to read, so the pause after an opponent's play is off unless a test sets it
    await context.addInitScript(() => {
      try {
        if (!localStorage.getItem('forge.settings')) localStorage.setItem('forge.settings', '{"pace":0}');
      } catch { /* a blank page has no storage */ }
    });
    const page = await context.newPage();
    listen(page, 'host');
    const dir = join(SHOTS, title.replace(/[^\w-]+/g, '-'));
    mkdirSync(dir, { recursive: true });
    const snap = async (name: string, on: Page = page) => {
      await on.screenshot({ path: join(dir, `${name}.png`) });
      console.log(`[snap] forge-gui-web/target/probe/${title.replace(/[^\w-]+/g, '-')}/${name}.png`);
    };
    const p: Probe = {
      page, browser, server, mark, snap,
      async until(what, test, arg, on = page, timeout = 30_000) {
        try {
          await on.waitForFunction(test as (arg: unknown) => unknown, arg, { timeout });
        } catch {
          await snap(`timeout-${what.replace(/[^\w-]+/g, '-')}`, on).catch(() => {});
          throw new Error(`Waited ${timeout / 1000} s for ${what}. The page: ${await explain(on)}`);
        }
      },
      async frames(name, count = 8, everyMs = 100, clip, on = page) {
        const shots: { at: number; png: string }[] = [];
        const start = Date.now();
        for (let i = 0; i < count; i++) {
          shots.push({ at: Date.now() - start, png: (await on.screenshot({ clip })).toString('base64') });
          await on.waitForTimeout(everyMs);
        }
        const columns = Math.max(1, Math.min(count, Math.floor(1800 / (clip?.width ?? VIEWPORT.width)))) || 1;
        const sheet = await context.newPage();
        await sheet.setContent(`<body style="margin:0;background:#000;display:grid;gap:4px;align-items:start;align-content:start;grid-template-columns:repeat(${columns},auto)">${
          shots.map(s => `<div style="position:relative"><img style="display:block" src="data:image/png;base64,${s.png}"><b style="position:absolute;left:4px;top:2px;padding:0 4px;background:#000b;color:#fff;font:12px sans-serif">${s.at} ms</b></div>`).join('')}</body>`);
        await sheet.screenshot({ path: join(dir, `${name}.png`), fullPage: true });
        await sheet.close();
        console.log(`[frames] forge-gui-web/target/probe/${title.replace(/[^\w-]+/g, '-')}/${name}.png`);
      },
    };
    try {
      await body(p);
      mark('probe');
      console.log(`[time] ${((Date.now() - began) / 1000).toFixed(1)} s: ${stages.join(', ')}`);
    } finally {
      await context.close();
      if (!shared) await server.stop();
    }
  });
}

/** Opens the start page and enters the probe player's name, Alice. */
async function home(p: Probe): Promise<void> {
  await p.page.goto(p.server.url);
  await enterName(p.page, 'Alice');
}

/** Opens match setup against the computer, with no decks chosen. */
export async function lobby(p: Probe): Promise<void> {
  await home(p);
  await hostTable(p.page, false);
  p.mark('lobby');
}

/** Opens the deck finder for your seat, shows illegal decks too, and chooses the probe's own deck. */
export async function finderWithOwnDeck(p: Probe): Promise<void> {
  await lobby(p);
  await p.page.locator('.plate.mine .sleeve').click();
  await p.page.locator('.fbar .add').click();
  await p.page.locator('.filter-menu .mi', { hasText: 'Illegal decks' }).click();
  await p.page.locator('.fpanel .fopt', { hasText: 'shown' }).click();
  await p.page.locator('.dk-hit', { hasText: PROBE_DECK }).click();
  await expect(p.page.locator('.dk-chosen-head')).toContainText(PROBE_DECK);
}

/** The table setup for a board: the game from the Game menu (Constructed by default), the seats and starting life. */
export interface Table {
  game?: string;
  players?: number;
  /** Each player's life if the state does not set it. The default is 20, or 40 for Commander. */
  life?: number;
}

/**
 * Starts a match against the computer and sets up the board from a dev-mode game state. The state uses the format of
 * the dev menu's "Set up a game state" (lines of key=value). Players are human and ai in a two-player game, and p0
 * (you) to p3 with more. activeplayer=human, activephase=MAIN1 and each player's life are added if not given, because
 * a state without a life total sets it to -1. It answers the coin toss and mulligan, and returns once the first card
 * the state names is on the table.
 */
export async function board(p: Probe, state: string, table: Table = {}): Promise<void> {
  const page = p.page;
  await lobby(p);
  const want = { game: table.game ?? null, players: table.players ?? 2 };
  // Set the game, the seats and each seat's deck through the lobby's actions, as the menus do
  await page.evaluate(({ game, players }) => {
    const { actions, model } = window.forge;
    const format = game && model.formats.find(f => f.name === game);
    if (format) actions.setFormat(format.id);
    if (players !== model.lobby?.seats.length) actions.setPlayerCount(players);
  }, want);
  await p.until(`${want.players} seats${want.game ? ` at ${want.game}` : ''}`, ({ game, players }) => {
    const lobby = window.forge.model.lobby;
    return lobby?.seats.length === players && (!game || window.forge.model.formats.find(f => f.id === lobby.format)?.name === game);
  }, want);
  p.mark('table');
  // The deck list for a new game arrives after the game change. So legal decks are set again until the table can start
  for (let tries = 0; ; tries++) {
    const ready = await page.evaluate(() => {
      const { actions, model } = window.forge;
      const lobby = model.lobby!;
      if (lobby.canStart) return true;
      const legal = model.decks.filter(d => !d.generated && !d.problem).sort((a, b) => a.name.localeCompare(b.name));
      lobby.seats.forEach((seat, i) => {
        const deck = legal.find(d => d.key !== seat.deck);
        if ((!seat.deckName || seat.problem) && deck) actions.setSeat(i, { deck: deck.key });
      });
      return false;
    });
    if (ready) {
      p.mark('decks');
      break;
    }
    if (tries > 30) throw new Error(`The table never became ready. The page: ${await explain(page)}`);
    await page.waitForTimeout(700);
  }
  await act(page, 'startMatch', false);
  await gameStarted(page);
  p.mark('match start');
  await setState(p, state, table);
}

/**
 * Sets up a game state in the current match, with the same defaults as board. Pass the table the match started with,
 * minus any player who has lost, and the pages of the other people at the table. It waits for priority first, and
 * then for the first card the state names.
 */
export async function setState(p: Probe, state: string, table: Table = {}, others: Page[] = []): Promise<void> {
  const page = p.page;
  const players = table.players ?? 2;
  const lines = state.trim().split('\n').map(l => l.trim()).filter(Boolean);
  const life = String(table.life ?? (table.game === 'Commander' ? 40 : 20));
  const defaults = [['activeplayer', 'human'], ['activephase', 'MAIN1'],
    ...Array.from({ length: players }, (_, i) => [players === 2 ? ['humanlife', 'ailife'][i] : `p${i}life`, life])];
  for (const [key, value] of defaults) {
    if (!lines.some(l => l.startsWith(`${key}=`))) lines.unshift(`${key}=${value}`);
  }
  const named = /^(?:human|ai|p\d)(?:battlefield|hand)=([^;|]+)/;
  const first = lines.map(l => named.exec(l)?.[1]).find(Boolean);
  await untilPriority(page, others);
  p.mark('priority');
  await act(page, 'dev', 'setupGameState', lines.join('\n'));
  if (!first) return;
  // The board is sent after the cards are placed. A prompt answered before it arrives is the old prompt
  await p.until(`${first} on the table`, name => [...document.querySelectorAll<HTMLElement>('#match .card')]
    .some(c => c.offsetParent !== null && c.textContent?.includes(name)), first, page, 10_000);
  p.mark('placed');
}

/** Calls one of the page's actions directly, as its buttons and keys do. */
export async function act<K extends keyof Actions>(page: Page, name: K, ...args: Parameters<Actions[K]>): Promise<void> {
  await page.evaluate(([n, a]) => (window.forge.actions[n] as (...x: unknown[]) => void)(...a), [name, args] as const);
}

/** What the prompt asks you now. A probe can wait on it or print it when a wait times out. */
export function asking(page: Page): Promise<{ message: string; priority: boolean; stack: boolean } | null> {
  return page.evaluate(() => {
    const prompt = window.forge.model.prompt;
    return prompt && { message: prompt.message, priority: prompt.priority, stack: !document.getElementById('stack')?.hidden };
  });
}

/**
 * Waits until you have priority with an empty stack. Only then does the game accept a state. While it waits it gives
 * the first choice to each question before the first turn (play first, keep the hand). It also passes anything on the
 * stack. It does this for you and for the other pages given.
 */
export async function untilPriority(page: Page, others: Page[] = [], timeout = 60_000): Promise<void> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const last = await asking(page);
    if (last?.priority && !last.stack) return;
    // Answer OK for the other pages too. Otherwise a page with priority or a pre-game question blocks the game forever
    for (const other of others) {
      if (await other.evaluate(() => !!window.forge.model.prompt?.ok?.enabled)) await act(other, 'ok');
    }
    // With more than two players the toss winner chooses who goes first. OK does not answer that prompt
    const starter = await page.evaluate(() => {
      const m = window.forge.model;
      return m.prompt?.starterChoice && m.prompt.selectablePlayers.length ? m.localPlayers[0] : null;
    });
    if (starter !== null) {
      await act(page, 'selectPlayer', starter);
      await page.waitForFunction(() => !window.forge.model.prompt?.starterChoice, undefined, { timeout: 5000 }).catch(() => {});
    } else if (last && (!last.priority || last.stack)) {
      await act(page, 'ok');
      // Wait for the prompt to change so the same prompt is not answered twice
      await page.waitForFunction(m => window.forge.model.prompt?.message !== m, last.message, { timeout: 5000 }).catch(() => {});
    } else {
      await page.waitForTimeout(250);
    }
  }
  throw new Error(`No priority within ${timeout / 1000} s. The page: ${await explain(page)}`);
}

/**
 * Presses OK on every page given, yours first, until the test is true in yours. OK passes priority, keeps hands and
 * lets spells resolve. It also declares no attackers and no blockers, so stop before a declaration you want to make.
 */
export async function passUntil<A>(p: Probe, what: string, test: (arg: A) => unknown, arg?: A, others: Page[] = [], timeout = 60_000): Promise<void> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (await p.page.evaluate(test, arg as A)) return;
    for (const page of [p.page, ...others]) {
      if (await page.evaluate(() => !!window.forge.model.prompt?.ok?.enabled)) await act(page, 'ok');
    }
    await p.page.waitForTimeout(400);
  }
  await p.snap(`timeout-${what.replace(/[^\w-]+/g, '-')}`).catch(() => {});
  const pages = await Promise.all([p.page, ...others].map(async (page, i) => `${i ? `other ${i}` : 'yours'}: ${await explain(page)}`));
  throw new Error(`Passed for ${timeout / 1000} s without ${what}. ${pages.join(' ')}`);
}

/** An offline draft at its first pick. */
export async function drafting(p: Probe): Promise<void> {
  const page = p.page;
  await home(p);
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=draft]');
  await page.click('.ev.new');
  await page.locator('.tile-choice', { hasText: /full card ?pool/i }).click();
  await page.locator('.stp-open .tile-choice', { hasText: /^One card/ }).click();
  await page.click('.ticket button:has-text("Start drafting")');
  await expect(page.locator('#drafting')).toBeVisible({ timeout: 60_000 });
}
