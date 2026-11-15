package forge.game.trigger;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

public class TriggerBecomesPaired extends Trigger {
    public TriggerBecomesPaired(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    @Override
    public boolean performTest(final Map<AbilityKey, Object> runParams) {
        if (!matchesValidParam("ValidPlayer", runParams.get(AbilityKey.Player))) {
            return false;
        }
        if (!hasParam("ValidCard")) {
            return true;
        }
        if (runParams.get(AbilityKey.Cards) instanceof Iterable<?> cards) {
            for (final Object card : cards) {
                if (matchesValidParam("ValidCard", card)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void setTriggeringObjects(final Map<AbilityKey, Object> objects, final Map<AbilityKey, Object> runParams) {
        AbilityKey.copyKeys(runParams, objects, AbilityKey.Cards, AbilityKey.Player);
    }

    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        return String.valueOf(sa.getTriggeringObject(AbilityKey.Cards));
    }
}
