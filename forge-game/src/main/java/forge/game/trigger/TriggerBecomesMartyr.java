package forge.game.trigger;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

public class TriggerBecomesMartyr extends Trigger {

    public TriggerBecomesMartyr(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    @Override
    public final boolean performTest(final Map<AbilityKey, Object> runParams) {
        return matchesValidParam("ValidCard", runParams.get(AbilityKey.Card));
    }

    @Override
    public final void setTriggeringObjects(final Map<AbilityKey, Object> objects, Map<AbilityKey, Object> runParams) {
        AbilityKey.copyKeys(runParams, objects, AbilityKey.Card, AbilityKey.Amount);
    }

    void prepareTriggeredAbility(final SpellAbility sa) {
        // Set the executable root before the trigger's targets are chosen.
        final Integer amount = (Integer) sa.getTriggeringObject(AbilityKey.Amount);
        sa.getRootAbility().setXManaCostPaid(amount == null ? 0 : amount);
        final Card martyr = (Card) sa.getTriggeringObject(AbilityKey.Card);
        if (martyr != null && getHostCard().getId() == martyr.getId()) {
            // The battlefield controller still controls the creature's own trigger.
            // Its registered host may already be the new object after the sacrifice.
            // Observer triggers keep their controller; TriggerController is applied later.
            sa.setActivatingPlayer(martyr.getController());
        }
    }

    @Override
    public String getImportantStackObjects(SpellAbility sa) {
        return "Martyr: " + sa.getTriggeringObject(AbilityKey.Card);
    }
}
