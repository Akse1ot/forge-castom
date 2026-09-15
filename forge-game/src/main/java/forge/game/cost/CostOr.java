package forge.game.cost;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.List;

public class CostOr extends CostPart {
    private static final long serialVersionUID = 1L;

    private final Cost leftCost;
    private final Cost rightCost;

    private transient List<CostPart> paidParts;

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
    public int paymentOrder() {
        return Math.max(
                getPaymentOrder(leftCost),
                getPaymentOrder(rightCost)
        );
    }

    private static int getPaymentOrder(final Cost cost) {
        int result = 0;

        for (final CostPart part : cost.getCostParts()) {
            result = Math.max(
                    result,
                    part.paymentOrder()
            );
        }

        return result;
    }

    @Override
    public CostPart copy() {
        return new CostOr(
                leftCost.copy(),
                rightCost.copy()
        );
    }

    @Override
    public boolean canPay(final SpellAbility ability, final Player payer, final boolean effect) {
        return leftCost.canPay(ability, payer, effect)
                || rightCost.canPay(ability, payer, effect);
    }

    @Override
    public boolean payAsDecided(final Player payer, final PaymentDecision decision,
                                final SpellAbility sa, final boolean effect) {
        clearPaidParts();

        if (decision == null
                || decision.type == null
                || decision.nested == null) {
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

        final List<CostPart> parts =
                chosen.getCostParts();

        if (parts.size() != decision.nested.size()) {
            return false;
        }

        for (int i = 0; i < parts.size(); i++) {
            final CostPart part = parts.get(i);
            final PaymentDecision nestedDecision =
                    decision.nested.get(i);

            if (nestedDecision == null) {
                refundPaidParts(sa.getHostCard());
                return false;
            }

            nestedDecision.matrix = decision.matrix;

            if (!part.payAsDecided(
                    payer,
                    nestedDecision,
                    sa,
                    effect)) {
                refundPaidParts(sa.getHostCard());
                return false;
            }

            getPaidParts().add(part);
        }

        return true;
    }

    @Override
    public void refund(final Card source) {
        refundPaidParts(source);
    }

    private List<CostPart> getPaidParts() {
        if (paidParts == null) {
            paidParts = new ArrayList<>();
        }
        return paidParts;
    }

    private void clearPaidParts() {
        if (paidParts != null) {
            paidParts.clear();
        }
    }

    private void refundPaidParts(final Card source) {
        if (paidParts == null || paidParts.isEmpty()) {
            return;
        }

        for (int i = paidParts.size() - 1; i >= 0; i--) {
            final CostPart part = paidParts.get(i);

            part.refund(source);
            resetPartLists(part);
        }

        paidParts.clear();
    }

    private static void resetPartLists(final CostPart part) {
        if (part instanceof CostOr or) {
            or.resetNestedLists();
        } else if (part instanceof CostPartWithList withList) {
            withList.resetLists();
        }
    }

    public void resetNestedLists() {
        resetNestedLists(leftCost);
        resetNestedLists(rightCost);
        clearPaidParts();
    }

    private static void resetNestedLists(final Cost cost) {
        for (final CostPart part : cost.getCostParts()) {
            resetPartLists(part);
        }
    }

    @Override
    public String toString() {
        return leftCost.toSimpleString()
                + " or "
                + rightCost.toSimpleString();
    }

    @Override
    public <T> T accept(final ICostVisitor<T> visitor) {
        return visitor.visit(this);
    }
}