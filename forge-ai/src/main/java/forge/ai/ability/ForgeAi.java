package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.SpellAbilityAi;
import forge.game.ability.AbilityUtils;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;
import java.util.Map;

public class ForgeAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision checkApiLogic(
            final Player ai,
            final SpellAbility sa) {
        return new AiAbilityDecision(
                100,
                AiPlayDecision.WillPlay
        );
    }

    @Override
    public SpellAbility chooseSingleSpellAbility(
            final Player player,
            final SpellAbility sa,
            final List<SpellAbility> choices,
            final Map<String, Object> params) {
        if (choices.isEmpty()) {
            return null;
        }

        SpellAbility best = choices.get(0);
        int bestWeight = getChoiceWeight(best);

        for (int i = 1; i < choices.size(); i++) {
            final SpellAbility current = choices.get(i);
            final int currentWeight = getChoiceWeight(current);

            if (currentWeight > bestWeight) {
                best = current;
                bestWeight = currentWeight;
            }
        }

        return best;
    }

    private static int getChoiceWeight(final SpellAbility choice) {
        if (!choice.hasParam("ForgeAIWeight")) {
            return 0;
        }

        return AbilityUtils.calculateAmount(
                choice.getHostCard(),
                choice.getParam("ForgeAIWeight"),
                choice
        );
    }
}