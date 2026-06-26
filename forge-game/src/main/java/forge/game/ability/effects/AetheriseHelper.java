package forge.game.ability.effects;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

import java.util.Map;

public final class AetheriseHelper {
    private AetheriseHelper() {
    }

    public static void fireAetherisedTrigger(final SpellAbility sa, final Card movedCard,
                                             final Zone originZone, final ZoneType destination) {
        if (sa == null || movedCard == null || movedCard.getGame() == null) {
            return;
        }

        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(movedCard);
        runParams.put(AbilityKey.Player, sa.getActivatingPlayer());
        runParams.put(AbilityKey.Source, sa.getHostCard());
        runParams.put(AbilityKey.SourceSA, sa);
        runParams.put(AbilityKey.SpellAbility, sa);

        if (originZone != null) {
            runParams.put(AbilityKey.Origin, originZone.getZoneType().toString());
        }
        if (destination != null) {
            runParams.put(AbilityKey.Destination, destination.toString());
        }

        movedCard.getGame().getTriggerHandler().runTrigger(TriggerType.Aetherised, runParams, false);
    }
}