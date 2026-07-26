package forge.game.ability.effects;

import java.util.List;

import forge.game.ability.SpellAbilityEffect;
import forge.game.keyword.EpitomeHelper;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.Lang;

public class EpitomeEffect extends SpellAbilityEffect {

    @Override
    protected String getStackDescription(final SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        final List<Player> players = getTargetPlayers(sa);

        sb.append(Lang.joinHomogenous(players));
        sb.append(players.size() > 1 ? " become" : " becomes");
        sb.append(" enlightened.");

        return sb.toString();
    }

    @Override
    public void resolve(final SpellAbility sa) {
        for (final Player player : getTargetPlayers(sa)) {
            if (!player.isInGame() || player.isEnlightened()) {
                continue;
            }

            if (EpitomeHelper.isThresholdMet(player)) {
                player.setEnlightened(true, sa.getOriginalHost().getSetCode());
            }
        }
    }
}
