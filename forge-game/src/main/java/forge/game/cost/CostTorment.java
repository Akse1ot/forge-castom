package forge.game.cost;

import forge.game.ability.TormentUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class CostTorment extends CostPart {
    private static final long serialVersionUID = 1L;

    public CostTorment() {
        super("1", "Torment", null);
    }

    @Override
    public int paymentOrder() {
        return 100;
    }

    @Override
    public boolean canPay(final SpellAbility ability,
                          final Player payer,
                          final boolean effect) {
        return payer != null && payer.isInGame();
    }

    @Override
    public String toString() {
        return "Torment yourself";
    }

    @Override
    public boolean payAsDecided(final Player payer,
                                final PaymentDecision decision,
                                final SpellAbility ability,
                                final boolean effect) {
        if (decision == null) {
            return false;
        }

        return TormentUtil.torment(
                payer,
                payer,
                ability,
                effect,
                decision.type);
    }

    @Override
    public <T> T accept(final ICostVisitor<T> visitor) {
        return visitor.visit(this);
    }
}