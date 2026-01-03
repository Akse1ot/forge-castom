package forge.game.spellability;

import com.google.common.collect.Maps;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

import java.util.*;

public final class ResonanceHelper {

    private ResonanceHelper() {}

    // =====================================================================
    // SVars
    // =====================================================================
    private static final String SVAR_RESONANCE_COLORS = "ResonanceColors";
    private static final String SVAR_RESONANCE_COLOR_TS = "ResonanceColorTS";

    // =====================================================================
    // COMPAT HELPER
    // =====================================================================
    public static void maybeMerge(Game game, Player activator, SpellAbility root) {
        mergeOnCast(game, activator, root);
    }

    // =====================================================================
    // MAIN MERGE FUNCTION
    // =====================================================================
    public static SpellAbility mergeOnCast(Game game, Player activator, SpellAbility root) {

        if (root == null || !root.isSpell()) return root;

        final Card host = root.getHostCard();
        if (host == null) return root;
        if (!host.isInstant() && !host.isSorcery()) return root;
        if (activator == null) return root;

        List<Card> pending = new ArrayList<>(activator.getPendingResonance());
        if (pending.isEmpty()) return root;

        System.out.println("[Resonance] mergeOnCast for " + host.getName());

        for (Card resCard : pending) {

            if (!resCard.isInZone(ZoneType.Exile)) {
                activator.removePendingResonance(resCard);
                continue;
            }

            FCollectionView<SpellAbility> allSA_view = resCard.getSpellAbilities();
            if (allSA_view.isEmpty()) {
                activator.removePendingResonance(resCard);
                game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);
                continue;
            }

            List<SpellAbility> allSA = new ArrayList<>();
            for (SpellAbility sa : allSA_view) {
                if ("True".equals(sa.getSVar("ResonanceCast"))) continue;
                allSA.add(sa);
            }

            if (allSA.isEmpty()) {
                activator.removePendingResonance(resCard);
                game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);
                continue;
            }

            boolean apply = activator.getController().confirmAction(
                    root,
                    PlayerActionConfirmMode.OptionalChoose,
                    "Apply Resonance from " + resCard.getName() + "?",
                    Collections.emptyList(),
                    resCard,
                    null
            );

            if (!apply) {
                activator.removePendingResonance(resCard);
                game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);
                continue;
            }

            final CardState hostState = host.getCurrentState();

            // === MERGE EFFECTS ===
            for (SpellAbility saA : allSA) {

                Map<String, String> paramsA = Maps.newHashMap();
                if (saA.getMapParams() != null) {
                    paramsA.putAll(saA.getMapParams());
                }

                AbilityRecordType recType = AbilityRecordType.getRecordType(paramsA);
                if (recType == null) {
                    System.err.println("[Resonance] Unknown ability type in " + resCard.getName());
                    continue;
                }

                ApiType apiA = recType.getApiTypeOf(paramsA);
                if (apiA == null) {
                    System.err.println("[Resonance] Cannot determine ApiType for " + resCard.getName());
                    continue;
                }

                SpellAbility subSa;
                try {
                    subSa = AbilityFactory.getAbility(
                            AbilityRecordType.SubAbility,
                            apiA,
                            paramsA,
                            null,
                            hostState,
                            hostState
                    );
                } catch (Exception e) {
                    System.err.println("[Resonance] Failed building sub-ability: " + e);
                    continue;
                }

                AbilitySub sub = (AbilitySub) subSa;
                sub.setActivatingPlayer(activator);
                root.appendSubAbility(sub);
            }

            // === REMEMBER COLORS (MASK-BASED) ===
            ColorSet colorsA = resCard.getColor();
            if (!colorsA.isColorless()) {

                final int maskA = colorsA.getColor();
                final String existing = root.getSVar(SVAR_RESONANCE_COLORS);

                if (existing == null || existing.isEmpty()) {
                    root.setSVar(SVAR_RESONANCE_COLORS, Integer.toString(maskA));
                } else {
                    try {
                        int maskExisting = Integer.parseInt(existing);
                        int merged = maskExisting | maskA;
                        root.setSVar(SVAR_RESONANCE_COLORS, Integer.toString(merged));
                    } catch (NumberFormatException e) {
                        // fallback: reset to current mask
                        root.setSVar(SVAR_RESONANCE_COLORS, Integer.toString(maskA));
                    }
                }
            }

            activator.removePendingResonance(resCard);
            game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);

            System.out.println("[Resonance] Applied " + resCard.getName()
                    + " to " + host.getName());
        }

        return root;
    }

    // =====================================================================
    // DIRECT COLOR OVERRIDE
    // =====================================================================
    public static void applyDirectColorOverrideForSpellCast(final SpellAbility sp) {

        if (sp == null || !sp.isSpell()) return;

        final String colors = sp.getSVar(SVAR_RESONANCE_COLORS);
        if (colors == null || colors.isEmpty()) return;

        if (sp.getSVar(SVAR_RESONANCE_COLOR_TS) != null
                && !sp.getSVar(SVAR_RESONANCE_COLOR_TS).isEmpty()) {
            return;
        }

        final Card host = sp.getHostCard();
        if (host == null || host.getGame() == null) return;

        final long ts = host.getGame().getNextTimestamp();
        int mask;
        try {
            mask = Integer.parseInt(colors);
        } catch (NumberFormatException e) {
            return;
        }

        host.addColor(ColorSet.fromMask(mask), true, ts, null);
        sp.setSVar(SVAR_RESONANCE_COLOR_TS, Long.toString(ts));
    }

    public static void clearDirectColorOverrideForSpellCast(final SpellAbility sp) {

        if (sp == null) return;

        final String tsStr = sp.getSVar(SVAR_RESONANCE_COLOR_TS);
        if (tsStr == null || tsStr.isEmpty()) return;

        final Card host = sp.getHostCard();
        if (host == null) {
            sp.setSVar(SVAR_RESONANCE_COLOR_TS, "");
            return;
        }

        try {
            long ts = Long.parseLong(tsStr);
            host.removeColor(ts, 0L);
        } catch (NumberFormatException ignored) {}

        sp.setSVar(SVAR_RESONANCE_COLOR_TS, "");
    }
}
