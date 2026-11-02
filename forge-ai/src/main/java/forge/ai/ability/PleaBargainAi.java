package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.game.ability.effects.PleaBargainEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.List;
import java.util.Map;

/** A simple strategy using hand size, the defender's own cards and public revealed choices only. */
public final class PleaBargainAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        sa.resetTargets();
        Player target = null;
        for (Player opponent : ai.getOpponents()) {
            if (sa.canTarget(opponent) && !opponent.getCardsIn(ZoneType.Hand).isEmpty()
                    && (target == null || opponent.getCardsIn(ZoneType.Hand).size() > target.getCardsIn(ZoneType.Hand).size())) {
                target = opponent;
            }
        }
        if (target == null) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        sa.getTargets().add(target);
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected Card chooseSingleCard(Player ai, SpellAbility sa, Iterable<Card> options,
            boolean optional, Player targetedPlayer, Map<String, Object> params) {
        Card hidden = null;
        for (Card card : options) {
            if (hidden == null || card.getCMC() > hidden.getCMC()) {
                hidden = card;
            }
        }
        return hidden;
    }

    @Override
    public SpellAbility chooseSingleSpellAbility(Player ai, SpellAbility sa,
            List<SpellAbility> options, Map<String, Object> params) {
        Object choices = params.get(PleaBargainEffect.REVEALED_CHOICES);
        SpellAbility result = options.get(0);
        int best = -1;
        if (choices instanceof Map<?, ?> revealed) {
            for (SpellAbility option : options) {
                if (revealed.get(option) instanceof Card card && card.getCMC() > best) {
                    best = card.getCMC();
                    result = option;
                }
            }
        }
        return result;
    }
}
