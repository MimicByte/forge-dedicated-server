// A campaign's card lists and the trade between them. Every price shown comes from the server

import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl, skinIconUrl, hideBroken } from '../images';
import { ColourToggles, toggled } from '../symbols';
import { useDebounced } from '../hooks';
import { showNotice } from '../notices';
import type { Actions } from '../actions';
import { useCataloguePages } from '../deck/catalogue';
import type { Model } from '../model';
import type { CatalogueRow } from '../protocol';
import { t, formatNumber, type TextKey } from '../text';
import { Backdrop } from '../dialog';
import { reducedMotion } from '../dom';
import { balance, capBalance } from './campaign';
import { flyShards } from './reward';
import { Select } from '../select';

/** A mode's name for one of its lists, and the text it shows for trading from that list. */
export interface TradeWords {
  label: string;
  /** The title of the selection bar and of the confirm dialog, for n cards picked. */
  verb: (n: number) => string;
  /** The text under a card: what it costs or pays in this list. The token marks where the currency symbol goes. */
  row: (price: string) => string;
  /** The confirm dialog's sentence for n cards at this total. The token marks where the symbol goes. */
  confirm: (n: number, total: string) => string;
  /** The badge on a card with the number of copies its list holds, for a list that counts copies. */
  count?: (n: number) => string;
  /** Whether a trade from this list pays the player. Its cards then fade out and their price flies to the balance. */
  pays?: boolean;
}

/** A picked card printing or product, with the key a trade uses for it and the number picked. */
export interface Pick { key: string; name: string; value: number; count: number; most: number }

/** Makes a pick from a row, of one copy by default. most is the number its list holds. */
export const pickOf = (row: CatalogueRow, count = 1): Pick => ({ key: keyOf(row), name: row.name, value: row.value ?? 0, count, most: row.count ?? 1 });

/** The key for a row in a trade. It is the server's key when the image key is not enough, as for a foil. Otherwise it is the image key. */
const keyOf = (row: CatalogueRow): string => row.key ?? row.image;

export function PricedButton({ label, icon, iconLabel, size, cost, have, disabled, cls, onClick }: {
  label: string; icon: string; iconLabel: string; size: number; cost: number; have: number; disabled?: boolean; cls?: string; onClick: () => void;
}) {
  return (
    <button class={cls ? `primary ${cls}` : 'primary'} disabled={disabled || cost > have} onClick={onClick}>
      {label}<span class="cq-sep">·</span><img class="cq-shard" alt={iconLabel} style={{ width: `${size}px`, height: `${size}px` }} src={skinIconUrl(icon)} />{formatNumber(cost)}
    </button>
  );
}

function Owned({ n }: { n: number }) {
  return (
    <span class="cq-owned" title={t('lblWebTradeOwned', n)}>
      {[1, 2, 3, 4].map(i => <i key={i} class={i <= n ? 'on' : undefined} />)}
      <span>{t('lblWebTradeOwned', n)}</span>
    </span>
  );
}

/** The keys of words are the lists, in that order. Each key is also the source used to request that list's cards. */
export function TradePage({ model, actions, icon, iconLabel, token, words, types, groupLabel, blocked, top }: {
  model: Model; actions: Actions;
  /** The currency's skin icon, its name as alt text, and the token that marks where a sentence names it. */
  icon: string; iconLabel: string; token: string;
  words: Record<string, TradeWords>; types: [string, TextKey][];
  /** The label of the group filter and of its option that filters by no group. Only modes with groups set it. */
  groupLabel?: string;
  /** The badge on a card that cannot be picked. */
  blocked: string;
  /** What a list shows above its cards. It gets the picks, a function to pick or unpick one, and a function to confirm a set of picks at once. */
  top?: (source: string, picked: Map<string, Pick>, toggle: (pick: Pick) => void, askAbout: (picks: Pick[]) => void) => ComponentChildren;
}) {
  const info = model.trading;
  const [source, setSource] = useState(Object.keys(words)[0]);
  const [typed, setTyped] = useState('');
  const text = useDebounced(typed);
  const [colours, setColours] = useState<Set<string>>(() => new Set());
  const [type, setType] = useState('any');
  const [group, setGroup] = useState('');
  /** The picks, by the key from keyOf. For most cards this is the image key, which names the printing. */
  const [picked, setPicked] = useState<Map<string, Pick>>(() => new Map());
  const [asking, setAsking] = useState(false);
  const query = { text, colours: [...colours].join(''), type, filters: '', sort: 'name', showAll: true, source, group: group || undefined };
  const sizes = info?.lists.map(l => l.count).join(',');
  const priced = (sentence: string) => {
    const [before, after] = sentence.split(token);
    return <>{before}<img class="cq-shard" alt={iconLabel} src={skinIconUrl(icon)} />{after}</>;
  };

  useEffect(() => { actions.trading(); }, []);
  // The catalogue is shared with the deck editor and the other lists. The page it holds may still belong to one of them
  const page = model.catalogue?.source === source ? model.catalogue : null;
  const rows = page?.rows ?? [];
  // A trade changes the lists. Their sizes show the change, so the sizes are a dependency
  const more = useCataloguePages(actions, query, page, [text, query.colours, type, group, source, sizes]);
  useEffect(() => { setPicked(new Map()); setAsking(false); }, [source, sizes]);
  /** The fades of the cards a trade took. The next page of cards ends them, because a card with copies left stays in it. */
  const leaving = useRef<Animation[]>([]);
  useEffect(() => {
    leaving.current.forEach(a => a.cancel());
    leaving.current = [];
  }, [page]);
  const toggle = (pick: Pick) => {
    const next = new Map(picked);
    if (!next.delete(pick.key)) next.set(pick.key, pick);
    setPicked(next);
  };
  const recount = (pick: Pick, by: number) => {
    const count = Math.min(Math.max(1, pick.count + by), pick.most);
    setPicked(new Map(picked).set(pick.key, { ...pick, count }));
  };
  const askAbout = (picks: Pick[]) => {
    setPicked(new Map(picks.map(p => [p.key, p])));
    if (picks.length) setAsking(true);
  };
  const chosen = [...picked.values()];
  const n = chosen.reduce((sum, c) => sum + c.count, 0);
  const total = chosen.reduce((sum, c) => sum + c.value * c.count, 0);
  const said = words[source];
  const title = said.verb(n);
  // The bar keeps the old balance and counts each card's price as it arrives
  const payOut = () => {
    let shown = balance(model.campaignBar, icon);
    let flying = 0;
    capBalance(icon, shown);
    const land = (amount: number) => {
      shown += amount;
      capBalance(icon, --flying ? shown : null);
    };
    chosen.forEach((c, i) => {
      const el = document.querySelector<HTMLElement>(`.cq-coll-grid .cq-cc[data-pick="${CSS.escape(c.key)}"]`);
      const amount = c.value * c.count;
      flying++;
      if (!el || reducedMotion()) {
        land(amount);
        return;
      }
      const rect = el.getBoundingClientRect();
      leaving.current.push(el.animate([{ opacity: 1, scale: 1 }, { opacity: 0, scale: .85 }], { duration: 350, delay: i * 70, easing: 'ease-in', fill: 'forwards' }));
      setTimeout(() => flyShards(rect, () => land(amount), icon), 150 + i * 70);
    });
  };
  return (
    <div class="cq-coll">
      <div class="cq-coll-bar">
        <span class="seg" role="group">
          {Object.entries(words).map(([id, w]) => {
            const size = info?.lists.find(l => l.source === id)?.count;
            return <button key={id} aria-pressed={id === source} onClick={() => setSource(id)}>{w.label}{size !== undefined && ` (${formatNumber(size)})`}</button>;
          })}
        </span>
        <input class="find" type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
        <ColourToggles label={t('lblColors')} colourless pressed={c => colours.has(c)} toggle={c => setColours(toggled(colours, c))} />
        <Select label={t('lblCardType')} value={type} change={setType} choices={types.map(([id, name]) => ({ value: id, label: t(name) }))} />
        {groupLabel && (
          <Select label={groupLabel} value={group} change={setGroup} choices={[{ value: '', label: groupLabel }, ...(info?.groups ?? []).map(g => ({ value: g, label: g }))]} />
        )}
        {info && <span class="cq-coll-note">{info.note}</span>}
      </div>
      {top?.(source, picked, toggle, askAbout)}
      <div class="cq-coll-grid" onScroll={more}>
        {page && page.total === 0 && <p class="none-found">{t('lblWebCatalogueNoMatchHint')}</p>}
        {rows.map(row => {
          const pick = picked.get(keyOf(row));
          const card = (
              <button key={keyOf(row)} class={`cq-cc${pick ? ' sel' : ''}${row.problem ? ' used' : ''}`} data-pick={keyOf(row)} title={row.problem} data-image={row.image} data-act={t('lblChoose')}
                aria-pressed={!!pick} aria-disabled={!!row.problem}
                onClick={() => (row.problem ? showNotice({ t: 'notice', title: row.problem, error: false }) : toggle(pickOf(row)))}>
                <span class="cq-pic"><span class="nm">{row.name}</span><img loading="lazy" alt="" src={imageUrl(row.image)} onError={hideBroken} /></span>
                {row.isNew && <span class="new-card">{t('lblNew')}</span>}
                {row.problem && <span class="inuse">{blocked}</span>}
                {said.count && row.count != null && <span class="cq-stock">{said.count(row.count)}</span>}
                <span class="val">{priced(said.row(formatNumber(row.value ?? 0)))}{row.owned != null && <Owned n={row.owned} />}{row.note && <span class="cq-cc-note">{row.note}</span>}</span>
              </button>
          );
          // In a list that counts copies, a pick gets a count stepper under the card. Other lists show the card alone
          if (row.count == null) return card;
          return (
            <div key={keyOf(row)} class="cq-cc-slot">
              {card}
              {pick && pick.most > 1 && (
                <span class="cq-step">
                  <button aria-label={t('lblWebEditorOneFewer', row.name)} disabled={pick.count <= 1} onClick={() => recount(pick, -1)}>&minus;</button>
                  <b>{t('lblWebNOfM', pick.count, pick.most)}</b>
                  <button aria-label={t('lblWebEditorOneMore', row.name)} disabled={pick.count >= pick.most} onClick={() => recount(pick, 1)}>+</button>
                </span>
              )}
            </div>
          );
        })}
      </div>
      {chosen.length > 0 && (
        <div class="cq-sel-bar">
          <button onClick={() => setPicked(new Map())}>{t('lblCancel')}</button>
          <button class="primary" onClick={() => setAsking(true)}>
            {title}<img class="cq-shard" alt={iconLabel} src={skinIconUrl(icon)} />{formatNumber(total)}
          </button>
        </div>
      )}
      {asking && (
        <Backdrop dismiss={() => setAsking(false)}>
          <div class="dialog cq-exile">
            <h3>{title}</h3>
            <p class="cq-exile-total">{priced(said.confirm(n, formatNumber(total)))}</p>
            <ul class="cq-cards-list">
              {chosen.map(c => <li key={c.key}>{c.count > 1 ? `${c.name} ×${c.count}` : c.name}<span>{priced(`${token} ${formatNumber(c.value * c.count)}`)}</span></li>)}
            </ul>
            <div class="actions">
              <button onClick={() => setAsking(false)}>{t('lblCancel')}</button>
              <button class="primary" onClick={() => { setAsking(false); if (said.pays) payOut(); actions.trade(source, chosen.map(c => ({ key: c.key, count: c.count }))); }}>{t('lblOK')}</button>
            </div>
          </div>
        </Backdrop>
      )}
    </div>
  );
}
