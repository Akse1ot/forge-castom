package forge.game.trigger;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.util.Localizer;

import java.util.Map;

public class TriggerSubjugated extends Trigger {

    public TriggerSubjugated(final Map<String, String> params,
                             final Card host,
                             final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    @Override
    public boolean performTest(final Map<AbilityKey, Object> runParams) {
        if (!matchesValidParam("ValidPlayer", runParams.get(AbilityKey.Player))) {
            return false;
        }

        if (hasParam("FirstTime")
                && !Boolean.TRUE.equals(runParams.get(AbilityKey.FirstTime))) {
            return false;
        }

        return true;
    }

    @Override
    public void setTriggeringObjects(final SpellAbility sa,
                                     final Map<AbilityKey, Object> runParams) {
        sa.setTriggeringObjectsFrom(
                runParams,
                AbilityKey.Player,
                AbilityKey.Amount,
                AbilityKey.Cards,
                AbilityKey.Cause,
                AbilityKey.FirstTime
        );
    }

    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        return Localizer.getInstance().getMessage("lblPlayer")
                + ": " + sa.getTriggeringObject(AbilityKey.Player);
    }
}