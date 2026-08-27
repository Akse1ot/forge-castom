package forge.game.keyword;

import forge.card.mana.ManaCost;
import forge.game.cost.Cost;

public class Melody extends KeywordInstance<Melody> {
    public enum PaymentType {
        ManaCost,
        AnyOneColor,
        AnyColor
    }

    private PaymentType paymentType = PaymentType.ManaCost;
    private ManaCost manaCost = ManaCost.ZERO;
    private int paymentAmount;
    private String paymentText = "";

    public PaymentType getPaymentType() {
        return paymentType;
    }

    public ManaCost getManaCost() {
        return manaCost;
    }

    public int getPaymentAmount() {
        return paymentAmount;
    }

    @Override
    public String getTitle() {
        if (paymentType == PaymentType.ManaCost) {
            return getKeyword() + " " + paymentText;
        }
        return getKeyword() + "—" + paymentText;
    }

    @Override
    protected void parse(String details) {
        String value = details.split("\\|", 2)[0].trim();

        if (value.startsWith("AnyOneColor:")) {
            parseFlexiblePayment(PaymentType.AnyOneColor, value.substring("AnyOneColor:".length()));
            return;
        }
        if (value.startsWith("AnyColor:")) {
            parseFlexiblePayment(PaymentType.AnyColor, value.substring("AnyColor:".length()));
            return;
        }

        Cost cost = new Cost(value, true);
        if (!cost.hasManaCost() || !cost.isOnlyManaCost()) {
            throw new IllegalArgumentException("Melody requires a mana-only payment: " + details);
        }

        manaCost = cost.getCostMana().getMana();
        if (manaCost == null || manaCost.isNoCost() || manaCost.isZero() || manaCost.countX() > 0) {
            throw new IllegalArgumentException("Unsupported Melody payment: " + details);
        }

        paymentText = cost.toSimpleString();
    }

    private void parseFlexiblePayment(PaymentType type, String amountText) {
        int amount;
        try {
            amount = Integer.parseInt(amountText.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Melody payment amount: " + amountText, e);
        }

        if (amount <= 0) {
            throw new IllegalArgumentException("Melody payment amount must be greater than zero: " + amount);
        }

        paymentType = type;
        paymentAmount = amount;

        if (type == PaymentType.AnyOneColor) {
            paymentText = amount + " mana of any one color";
        } else {
            paymentText = amount + " mana of any color";
        }
    }

    @Override
    protected String formatReminderText(String reminderText) {
        return String.format(reminderText, paymentText);
    }
}