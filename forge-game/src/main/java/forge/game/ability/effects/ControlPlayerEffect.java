package forge.game.ability.effects;

import java.util.List;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.Lang;
import forge.util.TextUtil;

/**
 * TODO: Write javadoc for this type.
 *
 */
public class ControlPlayerEffect extends SpellAbilityEffect {

    @Override
    protected String getStackDescription(SpellAbility sa) {
        List<Player> tgtPlayers = getTargetPlayers(sa);

        if ("UntilEndOfResolution".equals(sa.getParam("Duration"))) {
            return TextUtil.concatWithSpace(
                    sa.getActivatingPlayer().toString(),
                    "controls",
                    Lang.joinHomogenous(tgtPlayers),
                    "until this spell or ability finishes resolving"
            );
        }

        return TextUtil.concatWithSpace(
                sa.getActivatingPlayer().toString(),
                "controls",
                Lang.joinHomogenous(tgtPlayers),
                "during their next turn"
        );
    }

    @SuppressWarnings("serial")
    @Override
    public void resolve(SpellAbility sa) {
        final Player controller = AbilityUtils.getDefinedPlayers(
                sa.getHostCard(), sa.getParam("Controller"), sa).get(0);
        final Game game = controller.getGame();
        final boolean combat = sa.hasParam("Combat");
        final boolean untilEndOfResolution =
                "UntilEndOfResolution".equals(sa.getParam("Duration"));

        for (final Player pTarget : getTargetPlayers(sa)) {
            if (untilEndOfResolution) {
                // CR 800.4b
                if (!controller.isInGame()) {
                    continue;
                }

                // Controlling yourself has no additional game effect and
                // should not create an unnecessary MindSlaveController layer.
                if (pTarget == controller) {
                    continue;
                }

                final long ts = game.getNextTimestamp();
                pTarget.addController(ts, controller);

                addUntilCommand(sa, () -> pTarget.removeController(ts));
                continue;
            }

            // before next untap gain control
            (combat ? game.getBeginOfCombat() : game.getCleanup()).addUntil(pTarget, () -> {
                // CR 800.4b
                if (!controller.isInGame()) {
                    return;
                }

                long ts = game.getNextTimestamp();
                pTarget.addController(ts, controller);

                // after following cleanup release control
                (combat ? game.getEndOfCombat() : game.getCleanup())
                        .addUntil(() -> pTarget.removeController(ts));
            });
        }
    }
}
