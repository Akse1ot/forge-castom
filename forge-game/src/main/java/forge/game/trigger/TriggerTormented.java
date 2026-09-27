package forge.game.trigger;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.util.Localizer;

import java.util.Map;

public class TriggerTormented extends Trigger {
    public TriggerTormented(final Map<String, String> params,
                            final Card host,
                            final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    @Override
    public final boolean performTest(final Map<AbilityKey, Object> runParams) {
        if (!matchesValidParam(
                "ValidPlayer",
                runParams.get(AbilityKey.Player))) {
            return false;
        }

        if (!matchesValidParam(
                "ValidTormentor",
                runParams.get(AbilityKey.Activator))) {
            return false;
        }

        return true;
    }

    @Override
    public final void setTriggeringObjects(
            final SpellAbility sa,
            final Map<AbilityKey, Object> runParams) {
        sa.setTriggeringObjectsFrom(
                runParams,
                AbilityKey.Player,
                AbilityKey.Activator,
                AbilityKey.Cause);
    }

    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();

        sb.append(Localizer.getInstance().getMessage("lblPlayer"))
                .append(": ")
                .append(sa.getTriggeringObject(AbilityKey.Player));

        sb.append(", Tormentor: ")
                .append(sa.getTriggeringObject(AbilityKey.Activator));

        return sb.toString();
    }
}