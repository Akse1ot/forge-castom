package forge.game.ability.effects;

import forge.game.CustomReplayEtbTriggerSupport;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

public class CustomReplayEtbEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(final SpellAbility sa) {
        return sa.getParamOrDefault("SpellDescription", "");
    }

    @Override
    public void resolve(final SpellAbility sa) {
        for (final Card card : getDefinedCardsOrTargeted(sa)) {
            CustomReplayEtbTriggerSupport.replay(card);
        }
    }
}