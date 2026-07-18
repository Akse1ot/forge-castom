package forge.game.cost;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

public class CostOr extends CostPart {
    private static final long serialVersionUID = 1L;

    private final Cost leftCost;
    private final Cost rightCost;

    public CostOr(final Cost leftCost, final Cost rightCost) {
        super("1", "Or", null);
        this.leftCost = leftCost;
        this.rightCost = rightCost;
    }

    public Cost getLeftCost() {
        return leftCost;
    }

    public Cost getRightCost() {
        return rightCost;
    }

    @Override
    public boolean canPay(final SpellAbility ability, final Player payer, final boolean effect) {
        return leftCost.canPay(ability, payer, effect) || rightCost.canPay(ability, payer, effect);
    }

    @Override
    public boolean payAsDecided(final Player payer, final PaymentDecision decision, final SpellAbility sa, final boolean effect) {
        if (decision == null || decision.type == null || decision.nested == null) {
            return false;
        }

        final Cost chosen;
        if ("Left".equals(decision.type)) {
            chosen = leftCost;
        } else if ("Right".equals(decision.type)) {
            chosen = rightCost;
        } else {
            return false;
        }

        final List<CostPart> parts = chosen.getCostPartsWithZeroMana();
        if (parts.size() != decision.nested.size()) {
            return false;
        }

        for (int i = 0; i < parts.size(); i++) {
            final CostPart part = parts.get(i);
            final PaymentDecision nestedDecision = decision.nested.get(i);
            if (nestedDecision == null || !part.payAsDecided(payer, nestedDecision, sa, effect)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void refund(final Card source) {
        refundNested(leftCost, source);
        refundNested(rightCost, source);
    }

    private static void refundNested(final Cost cost, final Card source) {
        for (final CostPart cp : cost.getCostParts()) {
            cp.refund(source);
            if (cp instanceof CostOr or) {
                or.resetNestedLists();
            } else if (cp instanceof CostPartWithList withList) {
                withList.resetLists();
            }
        }
    }

    public void resetNestedLists() {
        resetNestedLists(leftCost);
        resetNestedLists(rightCost);
    }

    private static void resetNestedLists(final Cost cost) {
        for (final CostPart cp : cost.getCostParts()) {
            if (cp instanceof CostOr or) {
                or.resetNestedLists();
            } else if (cp instanceof CostPartWithList withList) {
                withList.resetLists();
            }
        }
    }

    @Override
    public String toString() {
        return leftCost.toSimpleString() + " or " + rightCost.toSimpleString();
    }

    @Override
    public <T> T accept(final ICostVisitor<T> visitor) {
        return visitor.visit(this);
    }
}