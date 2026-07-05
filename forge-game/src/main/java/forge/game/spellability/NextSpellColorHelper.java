package forge.game.spellability;

import forge.card.ColorSet;
import forge.game.card.Card;
import forge.game.player.Player;

public final class NextSpellColorHelper {
    private NextSpellColorHelper() {}

    private static final String SVAR_NEXT_COLOR_MASK = "_NextSpellAddColorMask";
    private static final String SVAR_NEXT_COLOR_TS = "_NextSpellAddColorTS";

    private static boolean hasValue(final SpellAbility sa, final String key) {
        final String value = sa == null ? null : sa.getSVar(key);
        return value != null && !value.isEmpty();
    }

    /**
     * Prepare the next spell color effect before target selection.
     *
     * This does NOT consume the player's pending effect. The pending effect is
     * consumed only after the spell was successfully cast.
     */
    public static void prepareSpellColor(final Player caster, final SpellAbility sa) {
        if (caster == null || sa == null || !sa.isSpell() || sa.isCopied()) {
            return;
        }
        if (!caster.hasNextSpellAddColors()) {
            return;
        }
        if (hasValue(sa, SVAR_NEXT_COLOR_TS)) {
            return;
        }

        final ColorSet colors = caster.getNextSpellAddColors();
        if (colors == null || colors.isColorless()) {
            return;
        }

        final Card host = sa.getHostCard();
        if (host == null || host.getGame() == null) {
            return;
        }

        final long timestamp = host.getGame().getNextTimestamp();
        host.addColor(colors, true, timestamp, null);

        sa.setSVar(SVAR_NEXT_COLOR_MASK, Integer.toString(colors.getColor()));
        sa.setSVar(SVAR_NEXT_COLOR_TS, Long.toString(timestamp));
    }

    /**
     * Commit the prepared next-spell color effect after successful casting.
     *
     * The color remains on the spell while it is on the stack. Only the pending
     * player effect is cleared here.
     */
    public static void commitPreparedSpellColor(final Player caster, final SpellAbility sa) {
        if (caster == null || sa == null) {
            return;
        }
        if (!hasValue(sa, SVAR_NEXT_COLOR_MASK)) {
            return;
        }

        caster.clearNextSpellAddColors();
    }

    /**
     * Roll back a prepared color effect when casting fails or is cancelled.
     *
     * This does NOT clear the player's pending effect, because the spell was not
     * successfully cast.
     */
    public static void rollbackPreparedSpellColor(final SpellAbility sa) {
        clearDirectColorOverrideForSpellCast(sa);
    }

    /**
     * Remove the temporary color override when the spell leaves the stack.
     */
    public static void clearDirectColorOverrideForSpellCast(final SpellAbility sa) {
        if (sa == null) {
            return;
        }

        final String tsStr = sa.getSVar(SVAR_NEXT_COLOR_TS);
        if (tsStr != null && !tsStr.isEmpty()) {
            final Card host = sa.getHostCard();
            if (host != null) {
                try {
                    host.removeColor(Long.parseLong(tsStr), 0L);
                } catch (NumberFormatException ignored) {
                    // Ignore malformed internal state and still clear the markers below.
                }
            }
        }

        sa.setSVar(SVAR_NEXT_COLOR_TS, null);
        sa.setSVar(SVAR_NEXT_COLOR_MASK, null);
    }
}