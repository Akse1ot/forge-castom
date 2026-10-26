package forge.game.spellability;

import com.google.common.collect.Maps;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.ability.ApiType;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.zone.ZoneType;

import java.util.*;
import java.util.function.Predicate;

public final class ResonanceHelper {

    private ResonanceHelper() {}

    // =====================================================================
    // SVars
    // =====================================================================
    private static final String SVAR_RESONANCE_COLORS = "ResonanceColors";
    private static final String SVAR_RESONANCE_COLOR_TS = "ResonanceColorTS";
    private static final String SVAR_RESONANCE_COMMIT_IDS = "ResonanceCommitIds";
    private static final String SVAR_RESONANCE_PREPARED = "ResonancePrepared";
    private static final String SVAR_RESONANCE_EFFECT = "ResonanceEffect";

    /** Owns only Resonance changes; the caller keeps the normal payment and stack lifecycle. */
    public static final class CastAttempt implements AutoCloseable {
        private final Game game;
        private final Player player;
        private final SpellAbility original;
        private boolean finished;

        private CastAttempt(Game game, Player player, SpellAbility original) {
            this.game = game;
            this.player = player;
            this.original = original;
        }

        public boolean isPrepared() {
            return !getCommitIds(original).isEmpty();
        }

        public void commit(SpellAbility current) {
            if (!finished) {
                commitOnCastSuccess(game, player, current);
                if (current != original) {
                    original.setSVar(SVAR_RESONANCE_COMMIT_IDS, "");
                }
                finished = true;
            }
        }

        public void rollback(SpellAbility current) {
            if (!finished) {
                clearMerge(current);
                if (current != original) {
                    clearMerge(original);
                }
                finished = true;
            }
        }

        @Override
        public void close() {
            rollback(original);
        }
    }

    public static CastAttempt prepareCast(Game game, Player player, SpellAbility root) {
        return prepareCast(game, player, root, null);
    }

    /** Remove a previous cast's additions before Splice or modes inspect a reused ability. */
    public static void resetForCast(SpellAbility root) {
        if (root != null && root.isSpell() && !root.isCopied()) {
            clearMerge(root);
        }
    }

    public static CastAttempt prepareCast(Game game, Player player, SpellAbility root,
            Predicate<SpellAbility> chooseEffects) {
        CastAttempt attempt = new CastAttempt(game, player, root);
        if (root == null || !root.isSpell() || root.isCopied()) {
            attempt.finished = true;
            return attempt;
        }
        // A new cast may reuse an SA whose previous cast already committed its effects.
        resetForCast(root);
        try {
            mergeOnCast(game, player, root, chooseEffects);
        } catch (RuntimeException | Error ex) {
            attempt.close();
            throw ex;
        }
        return attempt;
    }

    private static void clearMerge(SpellAbility root) {
        if (root == null || root.getSVar(SVAR_RESONANCE_PREPARED).isEmpty()) {
            return;
        }
        clearDirectColorOverrideForSpellCast(root);
        SpellAbility previous = root;
        for (AbilitySub sub = root.getSubAbility(); sub != null;) {
            AbilitySub next = sub.getSubAbility();
            if ("True".equals(sub.getSVar(SVAR_RESONANCE_EFFECT))) {
                previous.setSubAbility(next);
            } else {
                previous = sub;
            }
            sub = next;
        }
        root.setSVar(SVAR_RESONANCE_COMMIT_IDS, "");
        root.setSVar(SVAR_RESONANCE_COLORS, "");
        root.setSVar(SVAR_RESONANCE_COLOR_TS, "");
        root.setSVar(SVAR_RESONANCE_PREPARED, "");
    }

    private static boolean chooseEffects(SpellAbility root, SpellAbility first,
            Predicate<SpellAbility> chooser) {
        Map<SpellAbility, TargetChoices> targets = new IdentityHashMap<>();
        for (SpellAbility part = root; part != first; part = part.getSubAbility()) {
            targets.put(part, part.getTargets().clone());
        }
        Integer x = root.getXManaCostPaid();
        List<AbilitySub> modes = root.getChosenList() == null ? null : new ArrayList<>(root.getChosenList());
        try {
            return chooser.test(first) && root.getHostCard().getGame().getStack().hasLegalTargeting(first);
        } finally {
            targets.forEach(SpellAbility::setTargets);
            root.setXManaCostPaid(x);
            root.setChosenList(modes);
        }
    }

    private static void appendTargetPromptContext(final SpellAbility sa, final String context) {
        if (sa == null) {
            return;
        }

        final String suffix = " for " + context;

        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            final TargetRestrictions tgt = cur.getTargetRestrictions();
            if (tgt == null) {
                continue;
            }

            final String current = tgt.getVTSelection();
            if (current == null || current.isEmpty()) {
                tgt.setVTSelection("Select target" + suffix);
            } else if (!current.contains(suffix)) {
                tgt.setVTSelection(current + suffix);
            }
        }
    }

    private static void markForCommit(final SpellAbility root, final Card resCard) {
        if (root == null || resCard == null) {
            return;
        }

        final String id = Integer.toString(resCard.getId());
        final String existing = root.getSVar(SVAR_RESONANCE_COMMIT_IDS);

        if (existing == null || existing.isEmpty()) {
            root.setSVar(SVAR_RESONANCE_COMMIT_IDS, id);
            return;
        }

        for (final String part : existing.split(",")) {
            if (id.equals(part)) {
                return;
            }
        }

        root.setSVar(SVAR_RESONANCE_COMMIT_IDS, existing + "," + id);
    }

    private static Set<Integer> getCommitIds(final SpellAbility root) {
        final Set<Integer> result = new HashSet<>();
        if (root == null) {
            return result;
        }

        final String raw = root.getSVar(SVAR_RESONANCE_COMMIT_IDS);
        if (raw == null || raw.isEmpty()) {
            return result;
        }

        for (final String part : raw.split(",")) {
            try {
                result.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }

        return result;
    }

    public static void commitOnCastSuccess(final Game game, final Player activator, final SpellAbility root) {
        if (game == null || activator == null || root == null) {
            return;
        }

        final Set<Integer> ids = getCommitIds(root);
        if (ids.isEmpty()) {
            return;
        }

        for (final Card resCard : new ArrayList<>(activator.getPendingResonance())) {
            if (!ids.contains(resCard.getId())) {
                continue;
            }

            activator.removePendingResonance(resCard);

            if (resCard.isInZone(ZoneType.Exile)) {
                game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);
            }
        }

        root.setSVar(SVAR_RESONANCE_COMMIT_IDS, "");
    }

    public static void rollbackUncommittedMerge(final SpellAbility root, final SpellAbility originalTail) {
        if (root == null) {
            return;
        }

        if (getCommitIds(root).isEmpty()) {
            return;
        }

        clearMerge(root);
        if (originalTail != null && originalTail.getRootAbility() != root) {
            clearMerge(originalTail.getRootAbility());
        }
    }

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
        return mergeOnCast(game, activator, root, null);
    }

    private static SpellAbility mergeOnCast(Game game, Player activator, SpellAbility root,
            Predicate<SpellAbility> chooseEffects) {

        if (root == null || !root.isSpell() || root.isCopied()) return root;
        if (!root.getSVar(SVAR_RESONANCE_PREPARED).isEmpty()) return root;

        final Card host = root.getHostCard();
        if (host == null) return root;
        if (!host.isInstant() && !host.isSorcery()) return root;
        if (activator == null) return root;

        List<Card> pending = new ArrayList<>(activator.getPendingResonance());
        if (pending.isEmpty()) return root;
        root.setSVar(SVAR_RESONANCE_PREPARED, "True");

        boolean originalTargetPromptAdjusted = false;

        System.out.println("[Resonance] mergeOnCast for " + host.getName());

        for (Card resCard : pending) {

            if (!resCard.isInZone(ZoneType.Exile)) {
                activator.removePendingResonance(resCard);
                continue;
            }

            List<SpellAbility> allSA = AbilityUtils.getBasicSpellsFromPlayEffect(resCard, activator);

            if (allSA.isEmpty()) {
                markForCommit(root, resCard);
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
                markForCommit(root, resCard);
                continue;
            }

            if (!originalTargetPromptAdjusted) {
                appendTargetPromptContext(root, host.getName() + " (original spell)");
                originalTargetPromptAdjusted = true;
            }

            final CardState hostState = host.getCurrentState();
            final SpellAbility originalTail = root.getTailAbility();

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

                if (!(subSa instanceof AbilitySub)) {
                    System.err.println("[Resonance] Built ability is not a sub-ability for " + resCard.getName());
                    continue;
                }

                AbilitySub sub = (AbilitySub) subSa;
                sub.setActivatingPlayer(activator);
                for (SpellAbility part = sub; part != null; part = part.getSubAbility()) {
                    part.setSVar(SVAR_RESONANCE_EFFECT, "True");
                }
                appendTargetPromptContext(sub, "Resonance from " + resCard.getName());
                root.appendSubAbility(sub);
            }

            // Declining an optional effect still consumes its source on a successful cast.
            if (chooseEffects != null && originalTail.getSubAbility() != null
                    && !chooseEffects(root, originalTail.getSubAbility(), chooseEffects)) {
                originalTail.setSubAbility(null);
                markForCommit(root, resCard);
                continue;
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

            markForCommit(root, resCard);

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
