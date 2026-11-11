package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellAbilityAi;
import forge.game.ability.effects.SacrificeInAPNAPEffect;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public final class SacrificeInAPNAPAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        int advantage = 0;
        for (Player player : SacrificeInAPNAPEffect.players(sa)) {
            final CardCollectionView candidates = SacrificeInAPNAPEffect.candidates(sa, player);
            final int count = Math.min(SacrificeInAPNAPEffect.amount(sa), candidates.size());
            if (count == 0) {
                continue;
            }

            int value = 0;
            for (Card choice : ComputerUtil.choosePermanentsToSacrifice(player, candidates, count, sa, false, false)) {
                value += choice.isCreature() ? ComputerUtilCard.evaluateCreature(choice) : 20 * (choice.getCMC() + 1);
                if (sa.hasParam("SacrificerLosesToughness") && player.canLoseLife()) {
                    value += 10 * Math.max(0, choice.getNetToughness());
                }
            }
            advantage += player.isOpponentOf(ai) ? value : -value;
        }
        return advantage > 0 ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, final SpellAbility sa, final boolean mandatory) {
        return mandatory ? new AiAbilityDecision(100, AiPlayDecision.MandatoryPlay) : checkApiLogic(ai, sa);
    }
}
