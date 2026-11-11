package forge.game.mana;

import forge.game.player.Player;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Tracks reversible mana spending for one ability's current payment. */
public final class ManaSpendReceipt {
    private record Payer(Player player, long turn) {
    }

    private final Map<ManaCostBeingPaid, Map<Payer, Integer>> pending = new IdentityHashMap<>();
    private final Map<Payer, Integer> confirmed = new HashMap<>();

    public void record(ManaCostBeingPaid cost, Player player) {
        Payer payer = new Payer(player, player.getManaSpendingTurn());
        pending.computeIfAbsent(cost, ignored -> new HashMap<>()).merge(payer, 1, Integer::sum);
    }

    public void confirm(ManaCostBeingPaid cost) {
        Map<Payer, Integer> paid = pending.remove(cost);
        if (paid == null) {
            return;
        }
        paid.forEach((payer, amount) -> {
            payer.player().adjustManaSpentThisTurn(payer.turn(), amount);
            confirmed.merge(payer, amount, Integer::sum);
        });
    }

    public void refund() {
        confirmed.forEach((payer, amount) -> payer.player().adjustManaSpentThisTurn(payer.turn(), -amount));
        confirmed.clear();
        pending.clear();
    }
}
