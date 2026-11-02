package forge.game.ability.effects;

import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityKey;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;

import java.util.Map;

/** Worship's success is independent of whether its counters can be placed. */
public final class WorshipEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(final SpellAbility sa) {
        return sa.getHostCard() + " worships " + getTargetCards(sa) + ".";
    }

    private static Card currentPermanent(final Game game, final Card original) {
        Card current = game.getCardState(original, null);
        return current != null && current.isInPlay() && current.equalsWithGameTimestamp(original)
                ? current : null;
    }

    private static Card eventLki(final Game game, final Card original) {
        Card current = currentPermanent(game, original);
        return current == null ? game.getChangeZoneLKIInfo(original) : CardCopyService.getLKICopy(current);
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        final Game game = host.getGame();
        final Card source = currentPermanent(game, host);
        final Card sourceState = source == null ? game.getChangeZoneLKIInfo(host) : source;
        if (sourceState.isDevout() || getTargetCards(sa).size() != 1) {
            return;
        }

        final Card chosen = getTargetCards(sa).getFirst();
        final Card target = currentPermanent(game, chosen);
        if (target == null || !sa.canTarget(target)) {
            return;
        }

        GameEntityCounterTable counters = new GameEntityCounterTable();
        if (source != null) {
            source.addCounter(CounterEnumType.P1P1, 1, sa.getActivatingPlayer(), counters);
        }
        target.addCounter(CounterEnumType.P1P1, 1, sa.getActivatingPlayer(), counters);
        counters.replaceCounterEffect(game, sa);

        // Replacement effects may have moved the source. Never mark its new object.
        final Card remainingSource = currentPermanent(game, host);
        if (remainingSource != null) {
            remainingSource.setDevout(true);
            remainingSource.updateAbilityTextForView();
            game.updateLastStateForCard(remainingSource);
            game.fireEvent(new GameEventCardStatsChanged(remainingSource));
            game.getTriggerHandler().runTrigger(TriggerType.BecomeDevout,
                    AbilityKey.mapFromCard(remainingSource), false);
        }
        final Card remainingTarget = currentPermanent(game, chosen);
        if (remainingTarget != null) {
            game.updateLastStateForCard(remainingTarget);
        }

        // An eligible resolution succeeds even if the source left or no counter was placed.
        final Card worshipper = eventLki(game, host);
        final Map<AbilityKey, Object> event = AbilityKey.mapFromCard(worshipper);
        event.put(AbilityKey.Source, worshipper);
        event.put(AbilityKey.Target, eventLki(game, chosen));
        event.put(AbilityKey.Player, sa.getActivatingPlayer());
        game.getTriggerHandler().runTrigger(TriggerType.Worship, event, false);
    }
}
