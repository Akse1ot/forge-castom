package forge.game.ability.effects;

import forge.game.CustomReplayEtbTriggerSupport;
import forge.game.ability.AbilityKey;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;

import java.util.Map;

public class CustomReplayEtbEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(final SpellAbility sa) {
        return sa.getParamOrDefault("SpellDescription", "");
    }

    @Override
    public void resolve(final SpellAbility sa) {
        boolean replayed = false;

        for (final Card card : getDefinedCardsOrTargeted(sa)) {
            if (CustomReplayEtbTriggerSupport.getReplayRunParams(card) == null) {
                continue;
            }

            CustomReplayEtbTriggerSupport.replay(card);
            replayed = true;
        }

        if (replayed) {
            final Player player = sa.getActivatingPlayer();
            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(player);
            player.getGame().getTriggerHandler().runTrigger(
                    TriggerType.CustomReplayEtb,
                    runParams,
                    false
            );
        }
    }
}