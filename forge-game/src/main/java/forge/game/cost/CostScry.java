package forge.game.cost;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

public class CostScry extends CostPart {
    private static final long serialVersionUID = 1L;

    public CostScry(final String amount) {
        super(amount, "Scry", null);
    }

    @Override
    public int paymentOrder() {
        return 20;
    }

    @Override
    public boolean canPay(final SpellAbility ability, final Player payer, final boolean effect) {
        return payer != null && getAbilityAmount(ability) >= 0;
    }

    @Override
    public String toString() {
        return "Scry " + getAmount();
    }

    @Override
    public boolean payAsDecided(final Player payer, final PaymentDecision decision,
                                final SpellAbility ability, final boolean effect) {
        if (decision == null) {
            return false;
        }

        payer.getGame().getAction().scry(
                List.of(payer),
                decision.c,
                ability
        );

        return true;
    }

    @Override
    public <T> T accept(final ICostVisitor<T> visitor) {
        return visitor.visit(this);
    }
}