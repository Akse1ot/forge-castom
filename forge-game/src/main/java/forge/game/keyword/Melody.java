package forge.game.keyword;

import forge.card.mana.ManaCost;
import forge.game.cost.Cost;
import java.util.ArrayList;
import java.util.List;

public class Melody extends KeywordInstance<Melody> {
    public enum PaymentType {
        ManaCost,
        Choice,
        AnyOneColor,
        AnyColor
    }

    private PaymentType paymentType = PaymentType.ManaCost;
    private ManaCost manaCost = ManaCost.ZERO;
    private List<ManaCost> manaCosts = List.of();
    private int paymentAmount;
    private String paymentText = "";

    public PaymentType getPaymentType() {
        return paymentType;
    }

    public ManaCost getManaCost() {
        return manaCost;
    }

    public List<ManaCost> getManaCosts() {
        return manaCosts;
    }

    public int getPaymentAmount() {
        return paymentAmount;
    }

    @Override
    public String getTitle() {
        if (paymentType == PaymentType.ManaCost) {
            return getKeyword() + " " + paymentText;
        }
        if (paymentType == PaymentType.Choice) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < manaCosts.size(); i++) {
                if (i == 0) {
                    sb.append(getKeyword()).append(" ");
                } else {
                    sb.append(", melody ");
                }
                sb.append(manaCosts.get(i));
            }
            return sb.toString();
        }
        return getKeyword() + "—" + paymentText;
    }

    @Override
    protected void parse(String details) {
        String value = details.split("\\|", 2)[0].trim();

        if (value.startsWith("Choice:")) {
            parseChoice(value.substring("Choice:".length()));
            return;
        }
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

    private void parseChoice(String choicesText) {
        final String[] choices = choicesText.split(":");
        if (choices.length < 2) {
            throw new IllegalArgumentException("Melody Choice requires at least two payments: " + choicesText);
        }

        final List<ManaCost> payments = new ArrayList<>();
        final StringBuilder text = new StringBuilder();

        for (final String choice : choices) {
            final String value = choice.trim();
            final Cost cost = new Cost(value, true);

            if (!cost.hasManaCost() || !cost.isOnlyManaCost()) {
                throw new IllegalArgumentException("Melody Choice requires mana-only payments: " + choice);
            }

            final ManaCost payment = cost.getCostMana().getMana();
            if (payment == null || payment.isNoCost() || payment.isZero() || payment.countX() > 0) {
                throw new IllegalArgumentException("Unsupported Melody Choice payment: " + choice);
            }

            payments.add(payment);

            if (text.length() > 0) {
                text.append(" or ");
            }
            text.append(cost.toSimpleString());
        }

        paymentType = PaymentType.Choice;
        manaCosts = List.copyOf(payments);
        paymentText = text.toString();
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