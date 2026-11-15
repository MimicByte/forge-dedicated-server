import { Shelf as Cards } from '../shelf';
import { changeUi, ui } from '../ui';
import { isPortrait } from '../form';
import { ICONS, Icon } from '../icons';
import type { ConquestTab } from '../campaign/register';
import { CampaignPages, CampaignShelf, CampaignShell, Purse, Stats, slideTab } from '../campaign/campaign';
import { ConquestMap } from './conquestmap';
import { Party } from './conquestparty';
import { Collection } from './conquestcollection';
import { NewConquest } from './conquestnew';
import { Aether } from './conquestaether';
import { Planes } from './conquestplanes';
import { artUrl } from '../sleeves';
import { wheelLabels } from '../campaign/reward';
import { setting } from '../settings';
import { Dropdown } from '../dialog';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { ConquestSave } from '../protocol';
import { t, shortDay, type TextKey } from '../text';
import { hideBroken } from '../images';
import { Select } from '../select';

export function Conquest({ model, actions }: { model: Model; actions: Actions }) {
  const saves = model.conquestSaves;
  return (
    <CampaignShell model={model} actions={actions} icons={['IMG_AETHER_SHARD', 'IMG_PW_BADGE_COMMON']}
      shelf={(creating, setCreating) => (
        <CampaignShelf model={model} actions={actions} creating={creating} setCreating={setCreating}
          words={{ mode: t('lblPlanarConquest'), new: t('lblWebConquestNew'), yours: t('lblWebConquestYours'), reading: t('lblWebConquestReading') }}
          form={<NewConquest model={model} actions={actions} />}
          saves={saves && <Saves saves={saves.saves} current={saves.current ?? null} actions={actions} create={() => setCreating(true)} />} />
      )}
      open={() => <Campaign model={model} actions={actions} />} />
  );
}

function Saves({ saves, current, actions, create }: { saves: ConquestSave[]; current: string | null; actions: Actions; create: () => void }) {
  return (
    <Cards rows={saves} createLast cls={s => (s.name === current ? 'ev cq-save current' : 'ev cq-save')}
      create={{ title: t('lblWebConquestNew'), go: create }}
      art={s => (
        <div class="cq-save-art" style={{ backgroundImage: `url("${artUrl(s.art)}")` }}>
          <img class="cq-medal" alt="" src={artUrl(s.walkerImage)} onError={hideBroken} />
        </div>
      )}
      sub={s => <span class="sub">{s.planeswalker} - {s.plane}</span>}
      mid={s => <>
        <div class="cq-pbar"><i style={{ width: `${s.total ? 100 * s.conquered / s.total : 0}%` }} /></div>
        <span class="ev-line dim">
          <b class="pct">{s.conquered} / {s.total}</b>
          <span class="sp" />
          {t('lblWebNCards', s.cards)}
          <Purse icon="IMG_AETHER_SHARD" n={s.shards} label={t('lblAetherShards')} />
          <Purse icon="IMG_PW_BADGE_COMMON" n={s.emblems} label={t('lblPlaneswalkEmblems')} />
        </span>
      </>}
      foot={s => <>
        <button class="primary" onClick={() => actions.campaignLoad(s.name)}>{t('lblPlay')}</button>
        {s.saved && <span class="ev-ask">{t('lblWebLimitedSaved', shortDay(s.saved))}</span>}
      </>}
      rename={{ field: t('lblConquestName'), go: (s, to) => actions.campaignRename(s.name, to) }}
      remove={{ item: t('lblDelete'), ask: s => t('lblAreYouSuerDeleteConquest', s.name), keep: t('lblCancel'), go: s => actions.campaignDelete(s.name) }} />
  );
}

const DEV_WHEEL = ['BOOSTER', 'DOUBLE_BOOSTER', 'SHARDS', 'DOUBLE_SHARDS', 'PLANESWALK', 'CHAOS'] as const;
const TABS: [ConquestTab, TextKey][] = [['map', 'lblTheMultiverse'], ['aether', 'lblTheAether'], ['party', 'lblCommanders'],
  ['collection', 'lblCollection'], ['planes', 'lblPlaneswalk'], ['stats', 'lblStatistics']];

/**
 * Dev mode: sets where the next Chaos Wheel stops, so each reward can be tested. It is marked as a dev tool, like the match's dev menu, so it is not taken for a reward.
 * On the desktop it is an icon that names the queued result. On a phone it is a row of the header's menu, under a Dev Mode heading.
 */
function DevWheel({ actions }: { actions: Actions }) {
  const labels: Record<string, string> = wheelLabels();
  const name = (o: string) => (o ? labels[o] : t('lblRandom'));
  const choose = (o: string) => {
    changeUi(u => { u.conquestWheel = o; });
    actions.devConquestWheel(o);
  };
  const tool = `${t('lblDevMode')} · ${t('lblWebConquestDevWheel')}`;
  if (isPortrait()) {
    return <>
      <hr class="ph-menu-sep" />
      <small class="ph-menu-head">{t('lblDevMode')}</small>
      <label class="ph-menu-row"><Icon d={ICONS.dev} />{t('lblWebConquestDevWheel')}
        <Select label={t('lblWebConquestDevWheel')} value={ui.conquestWheel} change={choose} choices={['', ...DEV_WHEEL].map(o => ({ value: o, label: name(o) }))} />
      </label>
    </>;
  }
  const queued = ui.conquestWheel ? labels[ui.conquestWheel] : '';
  return (
    <Dropdown cls="menu-anchor" block button={(open, toggle) => (
      <button class={queued ? 'cq-dev set' : 'cq-dev'} aria-expanded={open} title={tool} aria-label={queued ? `${tool}: ${queued}` : tool} onClick={toggle}>
        <Icon d={ICONS.dev} />{queued && <span>{queued}</span>}
      </button>
    )}>
      {close => (
        <div class="deck-menu" role="menu" aria-label={tool}>
          <small class="cq-dev-head">{tool}</small>
          {['', ...DEV_WHEEL].map(o => (
            <button key={o} role="menuitemradio" aria-checked={o === ui.conquestWheel} onClick={() => {
              close();
              choose(o);
            }}>{name(o)}</button>
          ))}
        </div>
      )}
    </Dropdown>
  );
}

function Page({ tab, model, actions }: { tab: ConquestTab; model: Model; actions: Actions }) {
  switch (tab) {
    case 'aether': return <Aether model={model} actions={actions} />;
    case 'party': return <Party model={model} actions={actions} />;
    case 'collection': return <Collection model={model} actions={actions} />;
    case 'planes': return <Planes model={model} actions={actions} />;
    case 'stats': return <Stats model={model} actions={actions} first={model.conquestState?.plane} />;
    default: return <ConquestMap actions={actions} state={model.conquestState!} held={!!model.reward} />;
  }
}

function Campaign({ model, actions }: { model: Model; actions: Actions }) {
  if (!model.campaignBar || !model.conquestState) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  const plane = model.conquestState.plane;
  return (
    <CampaignPages model={model} actions={actions} tabs={TABS} tab={ui.conquestTab}
      setTab={tab => slideTab(TABS, ui.conquestTab, tab, u => { u.conquestTab = tab; })}
      extra={setting('devMode') && <DevWheel actions={actions} />}
      page={key => <Page key={`${key} ${plane}`} tab={ui.conquestTab} model={model} actions={actions} />} />
  );
}
