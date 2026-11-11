package forge.ai.ability;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.cost.CostTapType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

final class TeslaRevolutionAi {
    private TeslaRevolutionAi() {
    }

    static boolean shouldActivate(Player ai, SpellAbility ability) {
        int gain = 0;
        for (Card card : AbilityUtils.getDefinedCards(ability.getHostCard(), ability.getParam("Defined"), ability)) {
            if (!card.canTransform(ability)) {
                continue;
            }
            if (!card.isCreature() || !card.hasAlternateState() || !card.getAlternateState().getType().isCreature()
                    || !SetStateAi.isSafeToTransformIntoLegendary(ai, card)) {
                return false;
            }
            Card transformed = CardCopyService.getLKICopy(card);
            transformed.getCurrentState().copyFrom(card.getAlternateState(), true);
            if (transformed.getNetToughness() <= 0) {
                return false;
            }
            gain += ComputerUtilCard.evaluateCreature(transformed) - ComputerUtilCard.evaluateCreature(card);
        }

        boolean beforeCombat = ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);
        Card source = ability.getHostCard();
        int tapPenalty = tapPenalty(source, beforeCombat);
        CostTapType extraTap = ability.getPayCosts().getCostPartByType(CostTapType.class);
        if (extraTap != null) {
            CardCollection payment = ComputerUtil.chooseTapType(ai, extraTap.getType(), source,
                    ability.getPayCosts().hasTapCost(), extraTap.getAbilityAmount(ability), CardCollection.EMPTY, ability);
            if (payment == null) {
                return false;
            }
            for (Card card : payment) {
                tapPenalty += tapPenalty(card, beforeCombat);
            }
        }
        return gain > tapPenalty;
    }

    private static int tapPenalty(Card card, boolean beforeCombat) {
        // The source and helpers can lose a turn of attacking or blocking.
        int power = Math.max(0, card.getNetPower());
        return 4 + (beforeCombat ? 5 : 3) * power;
    }
}
