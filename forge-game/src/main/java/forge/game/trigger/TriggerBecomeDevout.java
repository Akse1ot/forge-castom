package forge.game.trigger;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import java.util.Map;

public final class TriggerBecomeDevout extends Trigger {
    public TriggerBecomeDevout(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
    }
    @Override
    public boolean performTest(final Map<AbilityKey, Object> event) {
        return matchesValidParam("ValidCard", event.get(AbilityKey.Card));
    }
    @Override
    public void setTriggeringObjects(final SpellAbility sa, final Map<AbilityKey, Object> event) {
        sa.setTriggeringObjectsFrom(event, AbilityKey.Card);
    }
    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        return "Devout: " + sa.getTriggeringObject(AbilityKey.Card);
    }
}
