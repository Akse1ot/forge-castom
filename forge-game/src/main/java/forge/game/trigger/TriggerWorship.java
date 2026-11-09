package forge.game.trigger;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import java.util.Map;

/** One successful worship action, carrying both objects' last known states. */
public final class TriggerWorship extends Trigger {
    public TriggerWorship(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
    }
    @Override
    public boolean performTest(final Map<AbilityKey, Object> event) {
        return matchesValidParam("ValidCard", event.get(AbilityKey.Card))
                && matchesValidParam("ValidSource", event.get(AbilityKey.Source))
                && matchesValidParam("ValidTarget", event.get(AbilityKey.Target))
                && matchesValidParam("ValidPlayer", event.get(AbilityKey.Player));
    }
    @Override
    public void setTriggeringObjects(final Map<AbilityKey, Object> objects, final Map<AbilityKey, Object> event) {
        AbilityKey.copyKeys(event, objects, AbilityKey.Card, AbilityKey.Source, AbilityKey.Target, AbilityKey.Player);
    }
    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        return "Worship: " + sa.getTriggeringObject(AbilityKey.Source) + " -> " + sa.getTriggeringObject(AbilityKey.Target);
    }
}
