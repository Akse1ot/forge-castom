package forge.game;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardZoneTable;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.HashSet;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Only the missing current-turn predicates for opted-in Trap cost conditions. */
public final class TrapTurnHistory {
    private static final Set<PhaseType> COMBAT_STEPS = EnumSet.range(PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_END);
    private final Set<Player> ownedPermanentTaken = new HashSet<>();
    private final Set<Player> opponentCombatAction = new HashSet<>();
    private final Set<Player> opponentOffMainEntry = new HashSet<>();
    // Individual and simultaneous zone events can describe the same entry. No Card references are retained.
    private record Entry(int id, long timestamp) { }
    private final Set<Entry> seenEntries = new HashSet<>();

    public boolean ownedPermanentTaken(final Player player) { return ownedPermanentTaken.contains(player); }
    public boolean opponentCombatAction(final Player player) { return opponentCombatAction.contains(player); }
    public boolean opponentOffMainEntry(final Player player) { return opponentOffMainEntry.contains(player); }

    public void record(final Game game, final TriggerType type, final Map<AbilityKey, Object> event) {
        if (game.getAge() != GameStage.Play) {
            return;
        }
        if (type == TriggerType.ChangesController && event.get(AbilityKey.Card) instanceof Card card) {
            Player current = card.getController();
            // Some zone moves emit an informational event whose LKI still has the old controller.
            if (card.isInPlay() && current != null && event.get(AbilityKey.OriginalController) instanceof Player previous
                    && current != previous
                    && current.isOpponentOf(card.getOwner())) {
                ownedPermanentTaken.add(card.getOwner());
            }
        } else if ((type == TriggerType.SpellCast || type == TriggerType.AbilityCast)
                && COMBAT_STEPS.contains(game.getPhaseHandler().getPhase())
                && event.get(AbilityKey.SpellAbility) instanceof SpellAbility spell && !spell.isCopied()) {
            Player actor = event.get(AbilityKey.Activator) instanceof Player player ? player : spell.getActivatingPlayer();
            if (actor != null) {
                opponentCombatAction.addAll(actor.getOpponents());
            }
        } else if (type == TriggerType.ChangesZone && isBattlefield(event.get(AbilityKey.Destination))
                && event.get(AbilityKey.Card) instanceof Card card) {
            entered(game, card);
        } else if (type == TriggerType.ChangesZoneAll && event.get(AbilityKey.Cards) instanceof CardZoneTable table) {
            for (var cards : table.column(ZoneType.Battlefield).values()) {
                for (Card card : cards) {
                    entered(game, card);
                }
            }
        }
    }

    private static boolean isBattlefield(final Object zone) {
        return zone == ZoneType.Battlefield || ZoneType.Battlefield.name().equals(zone);
    }

    private void entered(final Game game, final Card card) {
        if (!seenEntries.add(new Entry(card.getId(), card.getGameTimestamp())) || !card.isCreature()) {
            return;
        }
        Player controller = card.getController();
        PhaseType phase = game.getPhaseHandler().getPhase();
        boolean ownMain = (phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2)
                && controller == game.getPhaseHandler().getPlayerTurn();
        if (!ownMain && controller != null) {
            opponentOffMainEntry.addAll(controller.getOpponents());
        }
    }

    public void clear() {
        ownedPermanentTaken.clear();opponentCombatAction.clear();opponentOffMainEntry.clear();seenEntries.clear();
    }

    public void copyFrom(final TrapTurnHistory source, final IEntityMap map) {
        if (source == this) {
            return;
        }
        clear();
        source.ownedPermanentTaken.forEach(player -> ownedPermanentTaken.add(map.map(player)));
        source.opponentCombatAction.forEach(player -> opponentCombatAction.add(map.map(player)));
        source.opponentOffMainEntry.forEach(player -> opponentOffMainEntry.add(map.map(player)));
        seenEntries.addAll(source.seenEntries);
    }
}
