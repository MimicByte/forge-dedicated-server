// A Forge web server for one test. It has its own home folder, so tests never change the preferences of the user running them

import { execFileSync, spawn, type ChildProcess } from 'node:child_process';
import { mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const module = resolve(here, '..');
const repo = resolve(module, '..');

export interface Server {
  /** The page's address, with the token a browser needs to connect. */
  url: string;
  port: number;
  /** Drops every seat and match and restarts in the same process with only the seed's preferences. Works only on a server started as reusable. */
  reset(seed: Seed): Promise<void>;
  stop(): Promise<void>;
}

/** The files written to the server's home folder before it starts. */
export interface Seed {
  /** Forge preferences, keyed as in forge.preferences. */
  prefs?: Record<string, string>;
  /** Constructed decks saved as the player's own, by name, in .dck text. */
  decks?: Record<string, string>;
  /** Other files, keyed by path under Forge's user folder. The value is the text, or the bytes for a binary file. */
  files?: Record<string, string | Buffer>;
}

/** The player's deck that every probe server starts with. It has sixty basic lands and is legal in Constructed. */
export const PROBE_DECK = 'Probe deck';

/** Card-based deck generation is off. Its data takes over half the start time to load, and a probe plays real decks. */
export const PROBE_SEED: Seed = {
  prefs: { DEV_MODE_ENABLED: 'true', DECKGEN_CARDBASED: 'false' },
  decks: { [PROBE_DECK]: `[metadata]\nName=${PROBE_DECK}\n[Main]\n30 Mountain\n30 Forest\n` },
};

/** Asks the system for a free port, so parallel runs from any session never get the same port. */
function freePort(): Promise<number> {
  return new Promise((done, fail) => {
    const probe = createServer();
    probe.once('error', fail);
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address() as AddressInfo;
      probe.close(() => done(port));
    });
  });
}

/** Ends the process. On Windows it also ends the child JVM of the `java` launcher, which would otherwise keep the port. */
function killTree(pid: number): void {
  try {
    if (process.platform === 'win32') execFileSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
    else process.kill(pid);
  } catch {
    // Already gone
  }
}

/** Starts a server. Give a port to simulate a restart of the same server, because a browser treats the same port as the same site. */
export async function startServer(onPort?: number, seed: Seed = {}, reusable = false): Promise<Server> {
  const home = mkdtempSync(join(tmpdir(), 'forge-e2e-'));
  // Forge's user folder is under APPDATA on Windows (set to home below). Elsewhere it is a dot folder in the home folder
  const user = process.platform === 'win32' ? join(home, 'Forge') : join(home, '.forge');
  if (seed.prefs) {
    mkdirSync(join(user, 'preferences'), { recursive: true });
    writeFileSync(join(user, 'preferences', 'forge.preferences'),
      Object.entries(seed.prefs).map(([k, v]) => `${k}=${v}\n`).join(''));
  }
  for (const [name, text] of Object.entries(seed.decks ?? {})) {
    mkdirSync(join(user, 'decks', 'constructed'), { recursive: true });
    writeFileSync(join(user, 'decks', 'constructed', `${name}.dck`), text);
  }
  for (const [path, text] of Object.entries(seed.files ?? {})) {
    mkdirSync(dirname(join(user, path)), { recursive: true });
    writeFileSync(join(user, path), text);
  }
  const port = onPort ?? await freePort();
  const java: ChildProcess = spawn('java', [
    // FORGE_E2E_JVM adds JVM options, such as a flight recording to profile the server during a test
    ...(process.env.FORGE_E2E_JVM ?? '').split(' ').filter(Boolean),
    '-Djava.awt.headless=true',
    '-Dforge.web.noBrowser=true',
    `-Dforge.web.port=${port}`,
    // FORGE_PAGE_DIR serves another build of the page, so two page builds can be compared on the same server
    `-Dforge.web.pageDir=${process.env.FORGE_PAGE_DIR ?? join(module, 'src/main/resources/web')}`,
    `-Duser.home=${home}`,
    ...(reusable ? ['-Dforge.web.resetOnInput=true'] : []),
    '-jar', join(module, 'target', readdirSync(join(module, 'target')).find(f => /^forge-gui-web-.*-jar-with-dependencies\.jar$/.test(f))!),
  // On Windows Forge keeps its preferences under APPDATA rather than the home folder
  ], { cwd: repo, stdio: [reusable ? 'pipe' : 'ignore', 'pipe', 'pipe'], env: { ...process.env, APPDATA: home, LOCALAPPDATA: home } });
  const running = () => java.exitCode === null && java.signalCode === null;
  // A run stopped early (a timeout, Ctrl+C) never calls stop(). Without this the server would keep its port
  const orphaned = () => { if (running()) killTree(java.pid!); };
  process.once('exit', orphaned);
  let log = '';
  java.stdout?.on('data', (chunk: Buffer) => { log += chunk; });
  java.stderr?.on('data', (chunk: Buffer) => { log += chunk; });
  // The server prints its address each time it starts. A reset starts it again
  const started = (after: number) => new Promise<string>((resolveUrl, reject) => {
    const timer = setTimeout(() => reject(new Error(`Forge did not start in time:\n${log.slice(-4000)}`)), 180_000);
    const exited = (code: number | null) => reject(new Error(`Forge exited with ${code} before it started:\n${log.slice(-4000)}`));
    const read = () => {
      const found = /Forge web UI: (\S+)/.exec(log.slice(after));
      if (!found) return;
      clearTimeout(timer);
      java.stdout?.off('data', read);
      java.stderr?.off('data', read);
      java.off('exit', exited);
      resolveUrl(found[1]);
    };
    java.stdout?.on('data', read);
    java.stderr?.on('data', read);
    java.once('exit', exited);
    read();
  });
  const url = await started(0);
  return {
    url,
    port,
    async reset(again: Seed) {
      const after = log.length;
      java.stdin!.write(`reset ${Object.entries(again.prefs ?? {}).map(([k, v]) => `${k}=${v}`).join(' ')}\n`);
      await started(after);
    },
    async stop() {
      process.off('exit', orphaned);
      if (running()) {
        const exited = new Promise(done => java.once('exit', done));
        killTree(java.pid!);
        await exited;
      }
      // Windows can keep a file locked for a short time after the JVM exits
      rmSync(home, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
    },
  };
}
