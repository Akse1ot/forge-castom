package forge.ai.ability;

import java.util.Map;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

public class TriggerSagaChaptersAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision canPlay(final Player ai, final SpellAbility sa) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, final SpellAbility sa, final boolean mandatory) {
        return canPlay(ai, sa);
    }

    @Override
    protected Card chooseSingleCard(final Player ai, final SpellAbility sa, final Iterable<Card> options,
            final boolean isOptional, final Player targetedPlayer, final Map<String, Object> params) {
        Card best = null;
        int bestCount = -1;
        for (final Card card : options) {
            int count = 0;
            for (final Trigger trigger : card.getTriggers()) {
                if (trigger.isChapter() && !trigger.isLastChapter() && !trigger.isSuppressed()) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = card;
                bestCount = count;
            }
        }
        return best;
    }
}
