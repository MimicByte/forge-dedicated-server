import { expect, test, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { buildLimitedDeck, chooseDeck, enterName, gameStarted, inviteLink } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

/** The host opens a draft or sealed table from Play with Friends. */
async function hostEvent(page: Page, kind: 'draft' | 'sealed'): Promise<void> {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=multiplayer]');
  await page.click(`.chooser [data-kind=${kind}]`);
  await expect(page.locator('.event-setup .wiz')).toBeVisible({ timeout: 30_000 });
}

async function ready(page: Page): Promise<void> {
  await page.click('.plate.mine .ready-toggle');
  await expect(page.locator('.plate.mine .ready-toggle')).toContainText('Ready');
}

/** Builds a forty-card deck from the pool in the limited editor, then chooses the event deck for the seat. */
async function buildAndSit(page: Page): Promise<void> {
  await expect(page.locator('#editor')).toBeVisible({ timeout: 90_000 });
  await buildLimitedDeck(page);
  await page.click('.editor-head button.primary');
  await expect(page.locator('#lobby')).toBeVisible();
  await chooseDeck(page, page.locator('.plate.mine'));
}

// Fails if any step of an online sealed event is unreachable, from the event form to a guest's pool and the match
test('online sealed with a guest', async ({ page, browser }) => {
  test.setTimeout(480_000);
  await hostEvent(page, 'sealed');
  await page.click('.tile-choice:has-text("Full Cardpool")');
  await page.click('.stp-open button:has-text("Continue")');
  await page.click('.wfoot button:has-text("Save")');
  await expect(page.locator('.event-head .event-product')).toContainText('Full');

  const guest = await (await browser.newContext()).newPage();
  await guest.goto(await inviteLink(page, server.url));
  await enterName(guest, 'Bea');
  await expect(page.locator('#seats')).toContainText('Bea');
  await ready(guest);
  await ready(page);

  await page.click('.event-head button:has-text("Open packs")');
  await buildAndSit(page);
  await buildAndSit(guest);
  await expect(page.locator('#play')).toBeEnabled({ timeout: 30_000 });
  await page.click('#play');
  for (const p of [page, guest]) {
    await expect(p.locator('#match')).toBeVisible({ timeout: 60_000 });
    await gameStarted(p);
  }
});
