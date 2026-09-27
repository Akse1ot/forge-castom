package forge.game.ability.effects;

import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.ability.TormentUtil;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.SpellAbility;
import forge.util.Lang;

public class TormentEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(final SpellAbility sa) {
        final PlayerCollection players = getDefinedPlayersOrTargeted(sa);

        int amount = 1;
        if (sa.hasParam("Amount")) {
            amount = AbilityUtils.calculateAmount(
                    sa.getHostCard(),
                    sa.getParam("Amount"),
                    sa);
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(Lang.joinHomogenous(players))
                .append(players.size() == 1 ? " is" : " are")
                .append(" tormented");

        if (amount > 1) {
            sb.append(" ").append(amount).append(" times");
        }

        sb.append(".");
        return sb.toString();
    }

    @Override
    public void resolve(final SpellAbility sa) {
        int amount = 1;
        if (sa.hasParam("Amount")) {
            amount = AbilityUtils.calculateAmount(
                    sa.getHostCard(),
                    sa.getParam("Amount"),
                    sa);
        }

        if (amount <= 0) {
            return;
        }

        Player tormentor = sa.getActivatingPlayer();
        if (tormentor == null) {
            tormentor = sa.getHostCard().getController();
        }

        final boolean effect = !(sa instanceof AbilityStatic);

        for (final Player player : getDefinedPlayersOrTargeted(sa)) {
            for (int i = 0; i < amount && player.isInGame(); i++) {
                TormentUtil.torment(
                        tormentor,
                        player,
                        sa,
                        effect);
            }
        }
    }
}