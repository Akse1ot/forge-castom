package forge.game.combat;

import forge.game.card.Card;

import java.util.HashSet;
import java.util.Set;

public final class CombatDeclarationHistory {
    private record Incarnation(int id, long timestamp) {
        private Incarnation(Card card) {
            this(card.getId(), card.getGameTimestamp());
        }
    }

    private final Set<Incarnation> attackers = new HashSet<>();
    private final Set<Incarnation> blockers = new HashSet<>();

    public void recordAttacker(Card attacker) {
        attackers.add(new Incarnation(attacker));
    }

    public void recordBlocker(Card blocker) {
        blockers.add(new Incarnation(blocker));
    }

    public boolean allowsAttackers(Iterable<Card> proposed, int max) {
        return allows(attackers, proposed, max);
    }

    public boolean allowsBlockers(Iterable<Card> proposed, int max) {
        return allows(blockers, proposed, max);
    }

    private static boolean allows(Set<Incarnation> history, Iterable<Card> proposed, int max) {
        if (max == Integer.MAX_VALUE) {
            return true;
        }
        int count = history.size();
        Set<Incarnation> additions = new HashSet<>();
        for (Card card : proposed) {
            Incarnation key = new Incarnation(card);
            if (!history.contains(key) && additions.add(key)) {
                count++;
            }
            if (count > max) {
                return false;
            }
        }
        return true;
    }

    public void clear() {
        attackers.clear();
        blockers.clear();
    }

    public void copyFrom(CombatDeclarationHistory source) {
        if (this != source) {
            clear();
            attackers.addAll(source.attackers);
            blockers.addAll(source.blockers);
        }
    }
}
