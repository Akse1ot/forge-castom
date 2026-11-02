package forge.game.ability.effects;

import forge.game.CardTraitBase;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardTraitChanges;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import java.util.ArrayList;
import java.util.function.Predicate;

/** Removes only the returned source's tagged intrinsic restart trigger. */
public final class IunPostRestartEffect extends SpellAbilityEffect {
    private enum RemoveRestartTrigger implements Predicate<CardTraitBase> {
        INSTANCE;
        @Override public boolean test(CardTraitBase trait) {
            return trait instanceof Trigger && trait.isIntrinsic() && !trait.isCopiedTrait()
                    && trait.hasParam("IunRestartTrigger");
        }
    }

    @Override public void resolve(SpellAbility sa) {
        Card host = sa.getHostCard();
        var game = host.getGame();
        // Remembered is the actual result of the immediately preceding return effect.
        for (Object remembered : host.getRemembered()) {
            if (!(remembered instanceof Card returned)) {
                continue;
            }
            Card current = game.getCardState(returned, null);
            if (returned.getId() == host.getId() && current != null && current.isInPlay()
                    && current.equalsWithGameTimestamp(returned)) {
                current.addChangedCardTraits(null, null, null, null, RemoveRestartTrigger.INSTANCE,
                        game.getNextTimestamp(), 0);
            }
        }
    }

    /** Existing AI copies copy trait changes; snapshots must retain this specific modifier too. */
    public static void copyState(Card from, Card to) {
        for (var entry : new ArrayList<>(to.getChangedCardTraits().cellSet())) {
            if (entry.getValue() instanceof CardTraitChanges change && change.remove() == RemoveRestartTrigger.INSTANCE) {
                to.removeChangedCardTraits(entry.getRowKey(), entry.getColumnKey());
            }
        }
        for (var entry : from.getChangedCardTraits().cellSet()) {
            if (entry.getValue() instanceof CardTraitChanges change && change.remove() == RemoveRestartTrigger.INSTANCE) {
                to.addChangedCardTraits(change.copy(to, true), entry.getRowKey(), entry.getColumnKey(), true);
            }
        }
    }
}
