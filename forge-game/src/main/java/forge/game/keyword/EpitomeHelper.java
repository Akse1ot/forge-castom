package forge.game.keyword;

import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

public final class EpitomeHelper {
    public static final int MANA_ABILITY_THRESHOLD = 8;

    private EpitomeHelper() {
    }

    public static int countManaAbilities(final Player player) {
        int count = 0;

        for (final Card permanent : player.getCardsIn(ZoneType.Battlefield)) {
            // CR 702.26b: a phased-out permanent is treated as though it does not exist.
            if (permanent.isPhasedOut()) {
                continue;
            }

            final CardState state = permanent.getCurrentState();
            count += state.getManaAbilities().size();
            count += (int) state.getTriggers().stream()
                    .filter(Trigger::isManaAbility)
                    .count();
        }

        return count;
    }

    public static boolean isThresholdMet(final Player player) {
        return countManaAbilities(player) >= MANA_ABILITY_THRESHOLD;
    }
}
