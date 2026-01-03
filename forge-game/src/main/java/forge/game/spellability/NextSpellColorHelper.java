package forge.game.spellability;

import forge.card.ColorSet;
import forge.game.card.Card;
import forge.game.player.Player;

public final class NextSpellColorHelper {

    private NextSpellColorHelper() {}

    // Аналогично Resonance
    private static final String SVAR_NEXT_COLORS = "NextSpellAddColors";
    private static final String SVAR_NEXT_COLOR_TS = "NextSpellAddColorTS";

    // =====================================================
    // ONLY tag SpellAbility with colors (NO addColor here)
    // =====================================================
    public static void tagSpellOnCast(final Player caster, final SpellAbility sp) {
        if (caster == null || sp == null || !sp.isSpell()) return;
        if (!caster.hasNextSpellAddColors()) return;

        final ColorSet colors = caster.consumeNextSpellAddColors();
        if (colors == null || colors.isColorless()) return;

        sp.setSVar(SVAR_NEXT_COLORS, Integer.toString(colors.getColor()));

    }

    // =====================================================
    // APPLY color — ONLY from MagicStack.add()
    // =====================================================
    public static void applyDirectColorOverrideForSpellCast(final SpellAbility sp) {
        if (sp == null || !sp.isSpell()) return;

        final String colors = sp.getSVar(SVAR_NEXT_COLORS);
        if (colors == null || colors.isEmpty()) return;

        final String tsExisting = sp.getSVar(SVAR_NEXT_COLOR_TS);
        if (tsExisting != null && !tsExisting.isEmpty()) return;

        int mask;
        try {
            mask = Integer.parseInt(colors);
        } catch (NumberFormatException e) {
            return;
        }

        final Card host = sp.getHostCard();
        if (host == null || host.getGame() == null) return;

        final long ts = host.getGame().getNextTimestamp();
        host.addColor(ColorSet.fromMask(mask), true, ts, null);

        sp.setSVar(SVAR_NEXT_COLOR_TS, Long.toString(ts));
    }

    // =====================================================
    // Cleanup when leaving stack
    // =====================================================
    public static void clearDirectColorOverrideForSpellCast(final SpellAbility sp) {
        if (sp == null) return;

        final String tsStr = sp.getSVar(SVAR_NEXT_COLOR_TS);
        if (tsStr == null || tsStr.isEmpty()) return;

        final Card host = sp.getHostCard();
        if (host != null) {
            try {
                host.removeColor(Long.parseLong(tsStr), 0L);
            } catch (Exception ignored) {}
        }

        sp.setSVar(SVAR_NEXT_COLOR_TS, null);
        sp.setSVar(SVAR_NEXT_COLORS, null);
    }
}
