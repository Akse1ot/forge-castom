package forge.gamemodes.limited;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evaluates the same card and pool for a drafter or an AI seat. */
public final class ChaosDraftEvaluator {
    public record PreviewScore(double value, String text, boolean recommended) {}

    public static final class PackEvaluation {
        private static final PackEvaluation EMPTY = new PackEvaluation(Map.of(), List.of(), List.of(), null, 0, 0);
        private final Map<PaperCard, PreviewScore> scores;
        private final List<Candidate> candidates;
        private final List<PaperCard> visible;
        private final PaperCard recommended;
        private final int colorMask;
        private final double commitment;

        private PackEvaluation(Map<PaperCard, Candidate> scored, List<Candidate> candidates, List<PaperCard> visible,
                               PaperCard best, int colorMask, double commitment) {
            Map<PaperCard, PreviewScore> result = new IdentityHashMap<>();
            scored.forEach((card, candidate) -> result.put(card, new PreviewScore(candidate.score(),
                    String.format(Locale.ROOT, "%.1f", candidate.score()), card == best)));
            scores = Collections.unmodifiableMap(result);
            this.candidates = List.copyOf(candidates);
            this.visible = visible.stream().filter(card -> card != PaperCard.FAKE_CARD).toList();
            recommended = best;
            this.colorMask = colorMask;
            this.commitment = commitment;
        }

        public static PackEvaluation empty() {
            return EMPTY;
        }

        public PreviewScore get(PaperCard card) {
            return scores.get(card);
        }

        List<Candidate> candidates() { return candidates; }
        List<PaperCard> visible() { return visible; }
        PaperCard recommended() { return recommended; }
        int colorMask() { return colorMask; }
        double commitment() { return commitment; }
    }

    record Candidate(PaperCard card, int nativeRank, Double rating, String ratingSource, String exclusion,
                     ChaosCardProfile.Support support, double colorPenalty, double curveBonus, double landBonus,
                     double nativeBonus, double score, ChaosCardProfile.Strength strength,
                     double poolBase, double manaBonus, String fitReason) {
        boolean selectable() {
            return exclusion == null;
        }
    }

    private final ChaosRatings ratings;
    private final List<PaperCard> picked;
    private final ColorSet chosen;
    private final ChaosCardProfile.Context support;
    private final Map<PaperCard, ChaosCardProfile> profiles;
    private final ChaosDraftPlan plan;

    ChaosDraftEvaluator(ChaosRatings ratings, List<PaperCard> picked, ColorSet chosen, Deck ownDeck, boolean forAi) {
        this(ratings, picked, chosen, ownDeck, forAi, false);
    }

    ChaosDraftEvaluator(ChaosRatings ratings, List<PaperCard> picked, ColorSet chosen, Deck ownDeck,
                        boolean forAi, boolean deckBuilding) {
        this(ratings, picked, chosen, ownDeck, forAi, deckBuilding, null, new IdentityHashMap<>());
    }

    ChaosDraftEvaluator(ChaosRatings ratings, List<PaperCard> picked, ColorSet chosen, Deck ownDeck,
                        boolean forAi, boolean deckBuilding, ChaosManaPlan mana, Map<PaperCard, ChaosCardProfile> profiles) {
        this.ratings = ratings;
        this.picked = picked;
        this.chosen = chosen;
        this.profiles = profiles;
        plan = new ChaosDraftPlan(picked, chosen);
        support = new ChaosCardProfile.Context(picked.stream()
                .filter(card -> (!forAi || ChaosAiEvidence.deckAllowed(card, picked))
                        && (mana == null ? ChaosManaPlan.fitsColors(card, chosen)
                        : card.getRules().getType().isLand() || mana.castability(card) >= .4))
                .map(this::profile).toList(), picked, ownDeck, deckBuilding, forAi);
    }

    ChaosCardProfile profile(PaperCard card) {
        return profiles.computeIfAbsent(card, ChaosCardProfile::read);
    }

    ChaosCardProfile.Strength strength(PaperCard card) {
        return profile(card).strength(ratings.lookup(card), support);
    }

    double poolValue(PaperCard card, ChaosCardProfile.Strength strength, double unrated) {
        double base = strength.value() == null ? unrated : strength.value();
        if (card.getMainFace().getType().isLand()) {
            return strength.enhancedLand() ? base : Math.min(base, plan.landCeiling(ChaosManaPlan.readSource(card)));
        }
        return profile(card).manaOnly() ? Math.min(base, plan.rockCeiling(ChaosManaPlan.readSource(card))) : base;
    }

    double landUtility(ChaosManaPlan.Source source) {
        return plan.landBonus(source);
    }

    List<PaperCard> pickedCards() {
        return picked;
    }

    List<Candidate> evaluatePack(List<PaperCard> ranked, int maxColors, boolean forAi) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            PaperCard card = ranked.get(i);
            if (card == PaperCard.FAKE_CARD || card.getRules().isUnsupported()) {
                continue;
            }
            ChaosCardProfile.Strength strength = strength(card);
            String exclusion = null;
            if (ordinaryBasic(card)) {
                exclusion = "free_basic_land";
            } else if (forAi && !ChaosAiEvidence.deckAllowed(card, picked)) {
                exclusion = "native_ai_deck_restriction";
            } else if (forAi && picked.size() >= 12 && !ChaosManaPlan.fitsColors(card, chosen)
                    && Integer.bitCount(chosen.getColor() | (ChaosManaPlan.demand(card) & 31)) > maxColors
                    && !plan.splash(card)) {
                exclusion = "color_cap";
            } else if (strength.value() == null) {
                exclusion = "unrated";
            }
            // A weak or early color plan can still change when a strong new spell appears.
            double colorPenalty = plan.colorPenalty(card);
            double curve = curveBonus(card, picked, chosen);
            boolean isLand = card.getMainFace().getType().isLand();
            ChaosManaPlan.Source mana = isLand || profile(card).manaOnly() ? ChaosManaPlan.readSource(card) : null;
            double land = mana == null ? 0 : plan.landBonus(mana);
            double manaBonus = mana == null ? 0 : plan.rockBonus(mana);
            double nativeBonus = Math.max(0, 4 - i * 0.5);
            double poolBase = poolValue(card, strength, 0);
            double score = strength.value() == null ? Double.NEGATIVE_INFINITY
                    : poolBase - colorPenalty + curve + land + nativeBonus + manaBonus;
            String reason = isLand ? (land > 0 ? "mana_supports_plan" : "no_fixing_bonus")
                    : plan.splash(card) ? "supported_splash" : colorPenalty > 0 ? "outside_color_plan" : "fits_color_plan";
            if (strength.value() != null && poolBase < strength.value()) {
                reason += isLand ? ",land_source_ceiling" : ",mana_source_ceiling";
            }
            candidates.add(new Candidate(card, i, strength.external(), strength.source(), exclusion,
                    strength.support(), colorPenalty, curve, land, nativeBonus, score, strength, poolBase, manaBonus, reason));
        }
        return candidates;
    }

    PackEvaluation preview(List<PaperCard> ranked) {
        return preview(ranked, ranked);
    }

    PackEvaluation preview(List<PaperCard> ranked, List<PaperCard> shown) {
        Map<PaperCard, Candidate> visible = new IdentityHashMap<>();
        Candidate best = null;
        List<Candidate> candidates = evaluatePack(ranked, 5, false);
        for (Candidate candidate : candidates) {
            if (candidate.selectable()) {
                // The same printing may occur twice in one pack. Keep its highest native-order bonus.
                visible.putIfAbsent(candidate.card(), candidate);
                if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
            }
        }
        return new PackEvaluation(visible, candidates, shown, best == null ? null : best.card(),
                chosen.getColor(), plan.commitment());
    }

    static ColorSet humanColors(List<PaperCard> picked) {
        // Human choices have no DeckColors. Only infer a plan after seeing a few real picks.
        return picked.size() < 5 ? ColorSet.C : inferColors(picked, ColorSet.C, 3);
    }

    static ColorSet inferColors(List<PaperCard> picked, ColorSet previous, int limit) {
        final int[] counts = new int[5];
        for (final PaperCard card : picked) {
            if (card.getRules().getType().isLand() || blockedByHints(card)) {
                continue;
            }
            final int mask = ChaosManaPlan.demand(card);
            for (int i = 0; i < 5; i++) {
                if ((mask & (1 << i)) != 0) {
                    counts[i] += card.getMainFace().getType().isCreature() ? 2 : 1;
                }
            }
        }
        int mask = 0;
        int secondary = 0;
        for (int j = 0; j < Math.min(5, limit); j++) {
            int best = -1;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0 && (mask & (1 << i)) == 0 && (best < 0 || counts[i] > counts[best]
                        || counts[i] == counts[best] && (previous.getColor() & (1 << i)) != 0
                        && (previous.getColor() & (1 << best)) == 0)) {
                    best = i;
                }
            }
            if (best < 0 || j >= 2 && (counts[best] < 4 || counts[best] * 2 < secondary)) {
                break;
            }
            mask |= 1 << best;
            if (j == 1) {
                secondary = counts[best];
            }
        }
        final ColorSet chosen = ColorSet.fromMask(mask);
        return chosen;
    }

    static boolean ordinaryBasic(final PaperCard card) {
        return MagicColor.Constant.BASIC_LANDS.contains(card.getName());
    }

    static boolean blockedByHints(final PaperCard card) {
        // Match both the initial LimitedDeckBuilder filter and its later
        // checkRemRandomDeckCards phase. A Random hint can be permitted by
        // DeckNeeds/DeckHints, but do not guess that support in the MVP.
        return card.getRules().getAiHints().getRemAIDecks()
                || card.getRules().getAiHints().getRemNonCommanderDecks()
                || card.getRules().getAiHints().getRemRandomDecks();
    }

    static boolean eligible(final PaperCard card, final ColorSet chosen, final int maxColors) {
        if (ordinaryBasic(card) || blockedByHints(card)) {
            return false;
        }
        final ColorSet offColors = chosen.getOffColors(card.getRules().getDeckbuildingColors());
        return chosen.countColors() + offColors.countColors() <= maxColors;
    }

    private static double curveBonus(final PaperCard card, final List<PaperCard> picked, final ColorSet chosen) {
        if (!card.getRules().getType().isCreature() || picked.size() < 4) {
            return 0;
        }
        int playableSpells = 0;
        int creatures = 0;
        int cheap = 0;
        int expensive = 0;
        for (final PaperCard previous : picked) {
            if (previous.getRules().getType().isLand() || blockedByHints(previous)
                    || (chosen.countColors() > 0 && !previous.getRules().getDeckbuildingColors().hasNoColorsExcept(chosen))) {
                continue;
            }
            playableSpells++;
            if (previous.getRules().getType().isCreature()) {
                creatures++;
                final int cost = previous.getRules().getManaCost().getCMC();
                if (cost >= 1 && cost <= 2) {
                    cheap++;
                }
                if (cost >= 6) {
                    expensive++;
                }
            }
        }
        final int cost = card.getRules().getManaCost().getCMC();
        double bonus = creatures * 2 < playableSpells ? 2.5 : 0;
        if (cost >= 1 && cost <= 2 && cheap < Math.max(2, playableSpells / 6)) {
            bonus += 2.5;
        }
        if (cost >= 6 && expensive >= 2) {
            bonus -= 4;
        }
        return bonus;
    }

}
