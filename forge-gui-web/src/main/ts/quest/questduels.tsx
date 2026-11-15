import { useState } from 'preact/hooks';
import { DeckRow, SidePanel } from '../campaign/campaign';
import { artUrl } from '../sleeves';
import { Pips } from '../symbols';
import type { Actions } from '../actions';
import type { QuestDuelRow, QuestDuels } from '../protocol';
import { t, type TextKey } from '../text';
import { Select } from '../select';

/** A duel's difficulty by its number, 1 to 5. The random opponent has none. */
const DIFFICULTY: TextKey[] = ['lblEasy', 'lblMedium', 'lblHard', 'lblExpert', 'lblWebQuestWild'];

/** How hard a duel is, as filled bars with the word beside them. The word means it can be read without colour. */
export function Difficulty({ n }: { n: number }) {
  const word = t(DIFFICULTY[n - 1]);
  return (
    <span class="qu-diff" title={word}>
      {[1, 2, 3, 4].map(i => <i key={i} class={i <= n ? 'on' : undefined} />)}
      <span>{word}</span>
    </span>
  );
}

function Tile({ duel, chosen, choose }: { duel: QuestDuelRow; chosen: boolean; choose: () => void }) {
  return (
    <button class="qu-ev" aria-pressed={chosen} onClick={choose}>
      {duel.random
        ? <span class="a unknown"><b>?</b></span>
        : <span class="a" style={{ backgroundImage: duel.face ? `url("${artUrl(duel.face)}")` : undefined }} />}
      <span class="t">
        <b>{duel.title}</b>
        {duel.difficulty > 0 && <span class="l"><Difficulty n={duel.difficulty} /></span>}
        <p>{duel.description}</p>
        {duel.colors && <span class="f"><span class="pips"><Pips colors={duel.colors} /></span></span>}
      </span>
    </button>
  );
}

/** What the player brings to a duel or a challenge, at the foot of the panel: the deck, the pet, the plant, the match length and the start button. fixedDeck means the challenge supplies its own deck. */
export function Brings({ page, actions, zoom, start, label, fixedDeck }: {
  page: QuestDuels; actions: Actions; zoom: (image: string | null) => void; start: () => void; label: string; fixedDeck?: boolean;
}) {
  const plant = page.pets.find(p => p.slot === 0);
  const pet = page.pets.find(p => p.slot === 1);
  const summon = (name: string) => t('lblSummon').replace('%n', name);
  const problem = fixedDeck ? null : page.deckProblem;
  return <>
    {fixedDeck
      ? <DeckRow name={t('lblWebQuestChallengeDeck')} line={t('lblWebQuestChallengeDeckLine')} problem={false} zoom={zoom} />
      : <DeckRow name={page.deck || t('lblNone')} line={t('lblCurrentDeck2')} problem={!!page.deckProblem} zoom={zoom} />}
    {pet && (
      <div class="qu-row">
        <span>{t('lblWebQuestPet')}</span>
        <Select label={t('lblWebQuestPet')} value={pet.chosen ?? ''} change={v => actions.questPet(1, v || null)}
          choices={[{ value: '', label: t('lblDontSummonAPet') }, ...pet.pets.map(p => ({ value: p, label: summon(`"${p}"`) }))]} />
      </div>
    )}
    {plant && (
      <div class="qu-row">
        <span>{t('lblPlant')}</span>
        <label class="qu-check">
          <input type="checkbox" checked={plant.chosen != null} onChange={e => actions.questPet(0, e.currentTarget.checked ? plant.pets[0] : null)} />
          {summon(t('lblPlant'))}
        </label>
      </div>
    )}
    {page.matchLengths.length > 1 && (
      <div class="qu-row">
        <span>{t('lblMatch')}</span>
        <span class="seg" role="group">
          {page.matchLengths.map(n => (
            <button key={n} aria-pressed={n === page.matchLength} onClick={() => actions.questMatchLength(n)}>{t('lblWebLimitedBestOf', n)}</button>
          ))}
        </span>
      </div>
    )}
    {problem && <p class="cq-warn">{problem}</p>}
    <button class="primary cq-big" disabled={!!problem} onClick={start}>{label}</button>
  </>;
}

/** Travel, plus Unlock Sets when the quest's format offers an unlock. Desktop has the same buttons beside the duels and challenges. */
export function WorldButtons({ page, actions }: { page: QuestDuels; actions: Actions }) {
  return <>
    <button onClick={() => actions.questTravel()}>{t('btnTravel')}</button>
    {page.canUnlock && <button onClick={() => actions.questUnlock()}>{t('btnUnlockSets')}</button>}
  </>;
}

export function Duels({ page, actions }: { page: QuestDuels; actions: Actions }) {
  const [chosen, setChosen] = useState(0);
  const [zoom, setZoom] = useState<string | null>(null);
  // The list is new after every match, so a choice past its end falls back to the last duel
  const duel = page.duels[Math.min(chosen, page.duels.length - 1)];
  return (
    <div class="cq-map">
      <div class="qu-list">
        <div class="qu-list-head"><h2>{t('lblDuels')}</h2><p>{t('lblSelectNextDuel')}</p><span class="sp" /><WorldButtons page={page} actions={actions} /></div>
        <div class="qu-status">
          <span class="cq-chip"><b>{page.wins}</b> {t('lblWins')}</span>
          <span class="cq-chip"><b>{page.losses}</b> {t('lblLosses')}</span>
          <span class="cq-chip">{t('lblWinStreak')}: <b>{page.streak}</b> ({t('lblBest')}: {page.bestStreak})</span>
          {page.nextChallenge && <span>{page.nextChallenge}</span>}
        </div>
        <div class="qu-events">
          {page.duels.map(d => <Tile key={d.index} duel={d} chosen={d === duel} choose={() => setChosen(d.index)} />)}
        </div>
      </div>
      {duel && (
        <SidePanel kicker={t('lblWebQuestDuel')} title={duel.title} art={duel.face} zoom={zoom} setZoom={setZoom}
          foot={<Brings page={page} actions={actions} zoom={setZoom} start={() => actions.questDuel(duel.index)} label={t('lblWebQuestStartDuel')} />}>
          {duel.difficulty > 0 && <div class="cq-chips"><Difficulty n={duel.difficulty} /></div>}
          <p class="cq-desc">{duel.description}</p>
        </SidePanel>
      )}
    </div>
  );
}
