package forge.game.ability.effects;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.ability.SpellAbilityEffect;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class ChooseColorsForNextSpellEffect extends SpellAbilityEffect {

    @Override
    public void resolve(final SpellAbility sa) {
        final Player p = sa.getActivatingPlayer();
        if (p == null) return;

        final ColorSet options = ColorSet.fromMask(
                MagicColor.WHITE
                        | MagicColor.BLUE
                        | MagicColor.BLACK
                        | MagicColor.RED
                        | MagicColor.GREEN
        );

        final ColorSet chosen = p.getController().chooseColors(
                "Choose one or more colors",
                sa,
                1,
                5,
                options
        );

        if (chosen == null || chosen.isColorless()) return;
        p.setNextSpellAddColors(chosen);
    }
}
