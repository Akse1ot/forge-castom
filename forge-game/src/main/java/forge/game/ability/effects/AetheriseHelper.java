package forge.game.ability.effects;

import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.PlayerCollection;
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
        fireAetherisedTrigger(sa, movedCard == null ? null : CardCopyService.getLKICopy(movedCard),
                movedCard, originZone, destination);
    }

    public static void fireAetherisedTrigger(final SpellAbility sa, final Card lki, final Card result,
                                             final Zone originZone, final ZoneType destination) {
        final Card card = result == null ? lki : result;
        if (sa == null || card == null || card.getGame() == null) {
            return;
        }

        final PlayerCollection actors = AbilityUtils.getDefinedPlayers(sa.getHostCard(),
                sa.getParamOrDefault("AetherisingPlayer", "You"), sa);
        if (actors.size() != 1) {
            throw new IllegalArgumentException("AetherisingPlayer must define exactly one player");
        }
        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(card);
        runParams.put(AbilityKey.CardLKI, lki);
        runParams.put(AbilityKey.Player, actors.getFirst());
        runParams.put(AbilityKey.Source, sa.getHostCard());
        runParams.put(AbilityKey.SourceSA, sa);
        runParams.put(AbilityKey.SpellAbility, sa);

        if (originZone != null) {
            runParams.put(AbilityKey.Origin, originZone.getZoneType().toString());
        }
        final ZoneType actualDestination = result != null && result.getZone() != null
                ? result.getZone().getZoneType() : destination;
        if (actualDestination != null) {
            runParams.put(AbilityKey.Destination, actualDestination.toString());
        }

        card.getGame().getTriggerHandler().runTrigger(TriggerType.Aetherised, runParams, false);
    }
}
