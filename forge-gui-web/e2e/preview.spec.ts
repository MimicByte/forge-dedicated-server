import { expect, type Page } from '@playwright/test';
import { drafting, finderWithOwnDeck, lobby, probe, settled, PHONE, WIDE_TOUCH, type Probe } from './probe';
import { hold } from './steps';

// Cards on every page use the match's preview. A mouse hover opens it, and a held finger opens it as a sheet

async function newDeck(p: Probe): Promise<void> {
  const page = p.page;
  await lobby(p);
  await page.locator('.plate.mine .sleeve').click();
  await page.getByRole('button', { name: '+ New deck' }).click();
  await page.locator('.dialog.new-deck').getByText('Build a deck').click();
  await page.locator('#editor').waitFor();
  await page.locator('.catalogue .tile').nth(2).waitFor();
}

async function swipe(page: Page, x: number, y: number, dx: number): Promise<void> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  for (let i = 1; i <= 6; i++) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + (dx * i) / 6, y }] });
    await page.waitForTimeout(16);
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}

type Box = { x: number; y: number; width: number; height: number };
const overlaps = (a: Box, b: Box) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;

probe('a deck line\'s preview opens beside it with its picture, and goes when the pointer leaves', async p => {
  const page = p.page;
  await finderWithOwnDeck(p);
  const line = page.locator('.dk-chosen [data-image]').last();
  await line.hover();
  const zoom = page.locator('#zoom');
  await expect(zoom).toBeVisible();
  expect(decodeURIComponent((await zoom.locator('img').getAttribute('src')) ?? '')).toContain((await line.getAttribute('data-image'))!);
  expect(overlaps((await zoom.boundingBox())!, (await line.boundingBox())!)).toBe(false);
  await page.mouse.move(5, 5);
  await expect(zoom).toBeHidden();
});

probe('on a phone a tap on a catalogue card opens no preview, and a held one opens the sheet', async p => {
  const page = p.page;
  await newDeck(p);
  const zoom = page.locator('#zoom');
  await page.locator('.catalogue .tile').nth(0).tap();
  await page.waitForTimeout(600);
  await expect(zoom).toBeHidden();
  await hold(page, page.locator('.catalogue .tile').nth(1));
  await expect(zoom).toHaveClass(/sheet/);
  await expect(zoom.locator('.sheet-act')).toHaveText('Add');
  await expect(zoom.locator('.sheet-at')).toHaveText(/^2 of /);
}, PHONE);

probe('a swipe across the sheet shows the next card, and More opens the card\'s menu', async p => {
  const page = p.page;
  await newDeck(p);
  const zoom = page.locator('#zoom');
  await hold(page, page.locator('.catalogue .tile').nth(1));
  await expect(zoom.locator('.sheet-at')).toHaveText(/^2 of /);
  // A fresh server downloads each picture on first use, and the sheet shows the card's text until it arrives
  await expect(zoom.locator('.shot')).toBeVisible();
  const shot = (await zoom.locator('.shot').boundingBox())!;
  await swipe(page, shot.x + shot.width / 2, shot.y + shot.height / 2, -140);
  await expect(zoom.locator('.sheet-at')).toHaveText(/^3 of /);
  await zoom.locator('.sheet-abilities').tap();
  await expect(zoom).toBeHidden();
  await expect(page.locator('.card-menu')).toBeVisible();
}, PHONE);

probe('on a phone a tap on a draft card opens its sheet, whose Pick picks it', async p => {
  const page = p.page;
  await drafting(p);
  const slot = page.locator('.draft-pack .draft-slot').first();
  await slot.waitFor();
  // The pack slides in first, and a tap on a moving card opens no preview
  await settled(page);
  await slot.tap();
  const zoom = page.locator('#zoom');
  await expect(zoom).toHaveClass(/sheet/);
  await expect(zoom.locator('.sheet-act')).toHaveText('Pick');
  await zoom.locator('.sheet-act').tap();
  await expect.poll(() => page.evaluate(() => window.forge.model.draft?.picks.length ?? 0)).toBe(1);
  await expect(zoom).toBeHidden();
}, PHONE);

probe('on a landscape touch screen a held draft card opens its sheet, whose Pick picks it', async p => {
  const page = p.page;
  await drafting(p);
  const slot = page.locator('.draft-pack .draft-slot').first();
  await slot.waitFor();
  await settled(page);
  await hold(page, slot);
  const zoom = page.locator('#zoom');
  await expect(zoom.locator('.sheet-act')).toHaveText('Pick');
  await zoom.locator('.sheet-act').tap();
  await expect.poll(() => page.evaluate(() => window.forge.model.draft?.picks.length ?? 0)).toBe(1);
}, WIDE_TOUCH);
