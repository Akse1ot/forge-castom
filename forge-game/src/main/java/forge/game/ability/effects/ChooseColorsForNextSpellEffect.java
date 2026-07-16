package forge.game.ability.effects;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.ability.SpellAbilityEffect;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public class ChooseColorsForNextSpellEffect extends SpellAbilityEffect {
    @Override
    public void resolve(final SpellAbility sa) {
        final Player player = sa.getActivatingPlayer();
        if (player == null) {
            return;
        }

        final ColorSet options = ColorSet.fromMask(
                MagicColor.WHITE
                        | MagicColor.BLUE
                        | MagicColor.BLACK
                        | MagicColor.RED
                        | MagicColor.GREEN
        );

        int min = getColorCountParam(sa, "MinColors", 1);
        int max = getColorCountParam(sa, "MaxColors", 5);

        if (sa.hasParam("NumColors")) {
            min = getColorCountParam(sa, "NumColors", 1);
            max = min;
        }

        min = Math.max(1, Math.min(min, 5));
        max = Math.max(min, Math.min(max, 5));

        final ColorSet chosen = player.getController().chooseColors(
                max == 1 ? "Choose a color" : "Choose one or more colors",
                sa,
                min,
                max,
                options
        );

        if (chosen == null || chosen.isColorless()) {
            return;
        }

        player.setNextSpellAddColors(chosen);
    }

    private static int getColorCountParam(final SpellAbility sa, final String param, final int defaultValue) {
        if (!sa.hasParam(param)) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(sa.getParam(param));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}