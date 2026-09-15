package forge.game.spellability;

import forge.game.cost.Cost;
import forge.game.cost.CostTraverse;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCostPartReplacement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CostPartVariantBuilder {
    public static final String MARK_PREPARED = "CostPartVariantsPrepared";

    private static final String PARAM_REPLACEMENT_PREFIX = "CostPartReplacement_";
    private static final String PARAM_REPLACEMENT_INTRINSIC_PREFIX = "CostPartReplacementIntrinsic_";

    private CostPartVariantBuilder() {
    }

    public static List<SpellAbility> expand(final List<SpellAbility> candidates,
                                            final Player activator) {
        final List<SpellAbility> result = new ArrayList<>();

        if (candidates == null || candidates.isEmpty()) {
            return result;
        }

        for (final SpellAbility candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            if (candidate.getActivatingPlayer() == null && activator != null) {
                candidate.setActivatingPlayer(activator);
            }

            if (!candidate.isSpell()
                    || candidate.getHostCard() == null
                    || !candidate.getHostCard().hasKeyword(Keyword.TRAVERSE)) {
                result.add(candidate);
                continue;
            }

            final Player player = candidate.getActivatingPlayer() != null
                    ? candidate.getActivatingPlayer()
                    : activator;

            if (player == null) {
                result.add(candidate);
                continue;
            }

            final List<StaticAbility> replacements =
                    StaticAbilityCostPartReplacement.getReplacements(
                            "Traverse",
                            candidate,
                            player
                    );

            // No choice exists, so ordinary Traverse can stay completely
            // transparent to the ability-list generation code.
            if (replacements.isEmpty()) {
                result.add(candidate);
                continue;
            }

            if (isPreparedForCurrentContext(candidate)) {
                result.add(candidate);
                continue;
            }

            final List<Variant> variants = new ArrayList<>();

            final SpellAbility base = candidate.copy(player);
            clearReplacement(base, "Traverse");
            markPrepared(base);
            appendDescription(base, "Traverse");

            if (base.canPlay()) {
                variants.add(new Variant(base, false, false));
            }

            final Set<String> seenCosts = new LinkedHashSet<>();

            for (final StaticAbility st : replacements) {
                final String replacement = st.getParam("Cost");

                if (replacement == null
                        || replacement.trim().isEmpty()
                        || !seenCosts.add(replacement)) {
                    continue;
                }

                final SpellAbility derived = candidate.copy(player);

                setReplacement(
                        derived,
                        "Traverse",
                        replacement,
                        derived.getHostCard().equals(st.getHostCard())
                );
                markPrepared(derived);

                final String replacementDescription =
                        st.getParamOrDefault(
                                "CostPartDescription",
                                new Cost(replacement, false).toSimpleString()
                        );

                appendDescription(
                        derived,
                        "Traverse: " + replacementDescription
                );

                if (!derived.canPlay()) {
                    continue;
                }

                final boolean aiPreferred =
                        "Alternative".equalsIgnoreCase(
                                st.getParamOrDefault(
                                        "CostPartAIPreference",
                                        ""
                                )
                        );

                variants.add(
                        new Variant(
                                derived,
                                true,
                                aiPreferred
                        )
                );
            }

            if (variants.isEmpty()) {
                continue;
            }

            if (player.getController().isAI()) {
                final SpellAbility preferred =
                        chooseAiVariant(variants);

                if (preferred != null) {
                    result.add(preferred);
                }
            } else {
                for (final Variant variant : variants) {
                    result.add(variant.ability);
                }
            }
        }

        return result;
    }

    public static boolean needsPreparation(final SpellAbility sa,
                                           final Player activator) {
        if (sa == null
                || !sa.isSpell()
                || sa.getHostCard() == null
                || !sa.getHostCard().hasKeyword(Keyword.TRAVERSE)
                || isPreparedForCurrentContext(sa)) {
            return false;
        }

        final Player player = sa.getActivatingPlayer() != null
                ? sa.getActivatingPlayer()
                : activator;

        return player != null
                && StaticAbilityCostPartReplacement.hasReplacement(
                "Traverse",
                sa,
                player
        );
    }

    public static boolean isPreparedForCurrentContext(final SpellAbility sa) {
        if (sa == null || !sa.hasParam(MARK_PREPARED)) {
            return false;
        }

        return getContext(sa).equals(
                sa.getParam(MARK_PREPARED)
        );
    }

    public static String getSelectedReplacement(final SpellAbility sa,
                                                final String costPart) {
        if (sa == null
                || costPart == null
                || !isPreparedForCurrentContext(sa)) {
            return null;
        }

        final String param =
                getReplacementParam(costPart);

        return sa.hasParam(param)
                ? sa.getParam(param)
                : null;
    }

    public static boolean isSelectedReplacementIntrinsic(final SpellAbility sa,
                                                         final String costPart) {
        if (sa == null || costPart == null) {
            return true;
        }

        return !"False".equalsIgnoreCase(
                sa.getParamOrDefault(
                        getReplacementIntrinsicParam(costPart),
                        "True"
                )
        );
    }

    private static void setReplacement(final SpellAbility sa,
                                       final String costPart,
                                       final String replacement,
                                       final boolean intrinsic) {
        AlternativeCostRuleUtil.putPersistentParam(
                sa,
                getReplacementParam(costPart),
                replacement
        );

        AlternativeCostRuleUtil.putPersistentParam(
                sa,
                getReplacementIntrinsicParam(costPart),
                Boolean.toString(intrinsic)
        );
    }

    private static void clearReplacement(final SpellAbility sa,
                                         final String costPart) {
        AlternativeCostRuleUtil.removePersistentParam(
                sa,
                getReplacementParam(costPart)
        );

        AlternativeCostRuleUtil.removePersistentParam(
                sa,
                getReplacementIntrinsicParam(costPart)
        );
    }

    private static void markPrepared(final SpellAbility sa) {
        AlternativeCostRuleUtil.putPersistentParam(
                sa,
                MARK_PREPARED,
                getContext(sa)
        );
    }

    private static String getContext(final SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();

        final AlternativeCost alternativeCost =
                sa.getAlternativeCost();

        sb.append(
                alternativeCost == null
                        ? "Basic"
                        : alternativeCost.name()
        );

        if (sa.getMayPlay() != null) {
            sb.append("|MayPlay=")
                    .append(sa.getMayPlay().getId());
        }

        sb.append("|State=")
                .append(sa.getCardStateName());

        if (sa.hasParam(AlternativeCostRuleUtil.MARK_DERIVED_VARIANT)) {
            sb.append("|Derived");
        }

        for (final OptionalCost optionalCost : sa.getOptionalCosts()) {
            sb.append("|Optional=")
                    .append(optionalCost.name());
        }

        return sb.toString();
    }

    private static String getReplacementParam(final String costPart) {
        return PARAM_REPLACEMENT_PREFIX + costPart;
    }

    private static String getReplacementIntrinsicParam(final String costPart) {
        return PARAM_REPLACEMENT_INTRINSIC_PREFIX + costPart;
    }

    private static void appendDescription(final SpellAbility sa,
                                          final String text) {
        final String description = sa.getDescription();

        sa.setDescription(
                (description == null || description.isEmpty())
                        ? "(" + text + ")"
                        : description + " (" + text + ")"
        );
    }

    private static SpellAbility chooseAiVariant(final List<Variant> variants) {
        for (final Variant variant : variants) {
            if (variant.alternative && variant.aiPreferred) {
                return variant.ability;
            }
        }

        for (final Variant variant : variants) {
            if (!variant.alternative) {
                return variant.ability;
            }
        }

        return variants.get(0).ability;
    }

    private static final class Variant {
        private final SpellAbility ability;
        private final boolean alternative;
        private final boolean aiPreferred;

        private Variant(final SpellAbility ability,
                        final boolean alternative,
                        final boolean aiPreferred) {
            this.ability = ability;
            this.alternative = alternative;
            this.aiPreferred = aiPreferred;
        }
    }
}