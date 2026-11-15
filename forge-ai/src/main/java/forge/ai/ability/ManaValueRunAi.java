package forge.ai.ability;

import java.util.NavigableSet;
import java.util.TreeSet;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public final class ManaValueRunAi {
    public static final String LOGIC = "ImproveManaValueRun";

    private ManaValueRunAi() {
    }

    public static int choose(final Player player, final SpellAbility ability, final int min, final int max) {
        final Card source = ability.getHostCard();
        final int current = source.getCMC();
        if (!source.isInPlay() || source.isPhasedOut() || source.isLand() || source.getController() != player) {
            return current;
        }
        final NavigableSet<Integer> values = new TreeSet<>();
        for (final Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card != source && !card.isLand()) {
                values.add(card.getCMC());
            }
        }

        int choice = current;
        int best = runWith(values, current);
        for (int candidate = Math.max(0, min); candidate <= Math.min(5, max); candidate++) {
            final int length = runWith(values, candidate);
            if (length > best) {
                best = length;
                choice = candidate;
            }
        }
        return choice;
    }

    private static int runWith(final NavigableSet<Integer> values, final int value) {
        final boolean added = values.add(value);
        int longest = 0;
        int run = 0;
        long previous = Long.MIN_VALUE;
        for (final int next : values) {
            run = next == previous + 1 ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = next;
        }
        if (added) {
            values.remove(value);
        }
        return longest;
    }
}
