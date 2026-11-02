package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.List;

/** Basic local choice; normal cost/timing checks still belong to the casting pipeline. */
public final class WorshipAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        if (sa.getHostCard().isDevout()
                || !ComputerUtilCost.checkLifeCost(ai, sa.getPayCosts(), sa.getHostCard(), 4, sa)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        sa.resetTargets();
        List<Card> friends = CardLists.getTargetableCards(ai.getCreaturesInPlay(), sa);
        if (friends.isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }
        sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(friends));
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }
}
