package forge.gamemodes.limited;

import forge.card.ColorSet;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.gamemodes.limited.ChaosDraftEvaluator.Candidate;
import forge.gamemodes.limited.ChaosDraftEvaluator.PackEvaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import static forge.gamemodes.limited.ChaosDraftEvaluator.blockedByHints;
import static forge.gamemodes.limited.ChaosDraftEvaluator.eligible;
import static forge.gamemodes.limited.ChaosDraftEvaluator.ordinaryBasic;

/**
 * Optional Full-pool/Chaos Draft picker. Native draft rules and the native deck builder remain authoritative.
 */
public final class FullChaosDraftPicker {
    private static final String ENABLED_FILE = "enabled.flag";
    private static final Path DIRECTORY = findDirectory();
    private static final ChaosRatings RATINGS = new ChaosRatings(DIRECTORY);
    private static final boolean ENABLED = Files.isRegularFile(DIRECTORY.resolve(ENABLED_FILE));
    private static final boolean TRACE = Boolean.getBoolean("forge.chaosDraft.trace");
    private static final boolean REPORT = ENABLED && Boolean.getBoolean("forge.chaosDraft.report");

    static {
        if (TRACE) {
            System.out.println("FullChaosDraftPicker: " + (ENABLED ? "enabled" : "disabled")
                    + ", rated names: " + RATINGS.standardSize() + ", directory: " + DIRECTORY
                    + (REPORT ? ", reports: " + DIRECTORY.resolve("reports") : ""));
        }
    }

    private FullChaosDraftPicker() {
    }

    private static Path findDirectory() {
        final String override = System.getProperty("forge.chaosDraft.dir");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override);
        }
        final String appData = System.getenv("APPDATA");
        if (appData != null && !appData.trim().isEmpty()) {
            return Paths.get(appData, "Forge", "custom", "chaos-draft");
        }
        return Paths.get(System.getProperty("user.home"), ".forge", "chaos-draft");
    }

    public static Double getCardRating(final PaperCard card) {
        final ChaosRatings.Rating rating = RATINGS.lookup(card);
        return rating == null ? null : rating.value();
    }

    public static boolean isEnabledForDraft(final IBoosterDraft draft) {
        return usesRatings(ENABLED, RATINGS.isAvailable(), draft);
    }

    static boolean usesRatings(boolean enabled, boolean hasRatings, IBoosterDraft draft) {
        return enabled && hasRatings && chaosDraft(draft);
    }

    public static boolean showHumanHints(final IBoosterDraft draft) {
        return isEnabledForDraft(draft)
                && Boolean.parseBoolean(System.getProperty("forge.chaosDraft.humanHints", "true"));
    }

    static boolean diagnosticsEnabled() {
        return ENABLED && (REPORT || Boolean.getBoolean("forge.chaosDraft.matchReport"));
    }

    static boolean draftReportEnabled() {
        return REPORT;
    }

    static Path reportDirectory() {
        return DIRECTORY.resolve("reports");
    }

    public static void identifyDraftDecks(IBoosterDraft draft, Deck human, Deck[] opponents) {
        if (diagnosticsEnabled() && chaosDraft(draft)) {
            ((BoosterDraft) draft).chaosReport().identify(human, opponents);
        }
    }

    public static void reportDraftDecks(IBoosterDraft draft, Deck human, Deck[] opponents) {
        if (diagnosticsEnabled() && chaosDraft(draft)) {
            ((BoosterDraft) draft).chaosReport().completed(human, opponents);
        }
    }

    private static boolean chaosDraft(final IBoosterDraft draft) {
        return draft instanceof BoosterDraft booster && !(booster instanceof WinstonDraft)
                && !booster.isCommanderDraft()
                && (booster.draftFormat == LimitedPoolType.Full || booster.draftFormat == LimitedPoolType.Chaos);
    }

    private static PaperCard safeNativeFallback(final List<PaperCard> ranked, final ColorSet chosen, final int maxColors) {
        // An unranked on-color card is preferable to an off-color fallback that the
        // native LimitedDeckBuilder is unlikely to put in the final deck.
        for (final PaperCard card : ranked) {
            if (eligible(card, chosen, maxColors)) {
                return card;
            }
        }
        // Drafting cannot reject a pack; if none fits the colors, take a usable
        // off-color card for the sideboard before an unusable card or free basic.
        for (final PaperCard card : ranked) {
            if (!ordinaryBasic(card) && !blockedByHints(card)) {
                return card;
            }
        }
        for (final PaperCard card : ranked) {
            if (!ordinaryBasic(card)) {
                return card;
            }
        }
        return ranked.get(0);
    }

    public static PaperCard choose(final List<PaperCard> ranked, final List<PaperCard> picked,
                                   final DeckColors deckColors, final int seat, final IBoosterDraft draft) {
        return choose(ranked, picked, deckColors, seat, draft, null);
    }

    static PaperCard choose(final List<PaperCard> ranked, final List<PaperCard> picked, final DeckColors deckColors,
                            final int seat, final IBoosterDraft draft, final Deck ownDeck) {
        if (ranked.isEmpty()) {
            return null;
        }
        final PaperCard nativePick = ranked.get(0);
        if (!isEnabledForDraft(draft)) {
            return nativePick;
        }
        final ColorSet chosen = deckColors.getChosenColors();
        final ChaosDraftEvaluator evaluator = new ChaosDraftEvaluator(RATINGS, picked, chosen, ownDeck, true);
        final List<Candidate> candidates = evaluator.evaluatePack(ranked, deckColors.MAX_COLORS, true);
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate.selectable() && (best == null || candidate.score() > best.score())) {
                best = candidate;
            }
        }

        final PaperCard fallback = safeNativeFallback(ranked, chosen, deckColors.MAX_COLORS);
        final PaperCard pick;
        final String reason;
        if (best == null) {
            pick = fallback;
            if (ranked.size() == 1) {
                reason = "forced_last_card";
            } else if (eligible(fallback, chosen, deckColors.MAX_COLORS)) {
                reason = "unrated_eligible_native_fallback";
            } else {
                reason = "no_eligible_native_fallback";
            }
        } else if (fallback == nativePick && evaluator.strength(nativePick).value() == null
                && eligible(nativePick, chosen, deckColors.MAX_COLORS)) {
            // Unknown native top pick remains authoritative, but unknown lower-ranked cards do not veto the pack.
            pick = nativePick;
            reason = "native_top_unrated";
        } else {
            pick = best.card();
            reason = "rated_score";
        }
        if (TRACE) {
            System.out.println("FullChaosDraftPicker: " + nativePick.getName() + " -> " + pick.getName()
                    + " (" + candidates.stream().filter(Candidate::selectable).count()
                    + " rated eligible, " + picked.size() + " picked, " + reason + ")");
        }
        recordPick(draft, seat, picked.size(), chosen, candidates, nativePick, pick, reason);
        return pick;
    }

    public static void updateColors(final DeckColors deckColors, final PaperCard pick, final IBoosterDraft draft) {
        updateColors(deckColors, pick, isEnabledForDraft(draft));
    }

    static void updateColors(DeckColors deckColors, PaperCard pick, IBoosterDraft draft, List<PaperCard> picked) {
        boolean active = isEnabledForDraft(draft);
        if (active && picked.size() < 12 && !pick.getMainFace().getType().isLand()) {
            List<PaperCard> pool = new ArrayList<>(picked);
            pool.add(pick);
            var inferred = ChaosDraftEvaluator.inferColors(pool, deckColors.getChosenColors(), deckColors.MAX_COLORS);
            deckColors.setColorsByList(List.of(inferred.getColor()));
        } else {
            updateColors(deckColors, pick, active);
        }
    }

    static void updateColors(DeckColors deckColors, PaperCard pick, boolean useRatings) {
        // A fixing land must not permanently claim both native deck colors during the first pick.
        if (useRatings && pick.getRules().getType().isLand()) {
            return;
        }
        deckColors.getChosenColors(); // addColorsOf expects the cached ColorSet to be populated.
        deckColors.addColorsOf(pick);
    }

    public static Deck buildDeck(final List<PaperCard> pool, final DeckColors deckColors,
                                 final String landSetCode, final int seat, final IBoosterDraft draft) {
        return buildDeck(pool, deckColors, landSetCode, seat, draft, null);
    }

    static Deck buildDeck(List<PaperCard> pool, DeckColors deckColors, String landSetCode, int seat,
                          IBoosterDraft draft, Deck ownDeck) {
        boolean useChaosBuilder = useDeckBuilder(isEnabledForDraft(draft));
        List<PaperCard> stock = new ArrayList<>(pool);
        if (useChaosBuilder && ownDeck != null) {
            for (DeckSection section : DeckSection.NONTRADITIONAL_SECTIONS) {
                if (ownDeck.has(section)) {
                    stock.addAll(ownDeck.get(section).toFlatList());
                }
            }
        }
        BiFunction<List<PaperCard>, DeckColors, Deck> improved = useChaosBuilder ? (cards, colors) -> {
            cards.removeIf(card -> {
                DeckSection section = DeckSection.matchingSection(card);
                return section != DeckSection.Main && section != DeckSection.Conspiracy;
            });
            Deck seed = new FullChaosBoosterDeckBuilder(cards, colors, deckEvaluator(List.copyOf(cards), colors, ownDeck))
                    .buildDeck(landSetCode);
            copySpecialSections(ownDeck, seed);
            ChaosDeckTuner.repairSeedRestrictions(seed);
            ChaosDeckTuner.reconcile(seed, stock);
            return seed;
        } : null;
        BuildResult result = buildWithFallback(pool, deckColors, landSetCode, improved);
        Deck deck = result.deck();
        ChaosDeckTuner.Result tuning = null;
        String tuningStatus = "disabled";
        if (result.chaosBuilder()) {
            try {
                tuning = new ChaosDeckTuner(RATINGS, deckColors.getChosenColors()).improve(deck, stock);
                deck = tuning.deck();
                tuningStatus = tuning.invariant();
            } catch (RuntimeException failure) {
                tuningStatus = "optimizer_failed_seed_kept:" + failure.getClass().getSimpleName();
                System.err.println("FullChaosDraftPicker: keeping the Chaos seed after tuning failed: " + failure);
            }
        } else if (useChaosBuilder) {
            copySpecialSections(ownDeck, deck);
            tuningStatus = "native_fallback";
            try {
                ChaosDeckTuner.reconcile(deck, stock);
            } catch (IllegalStateException invalidNativeDeck) {
                tuningStatus = "native_fallback_invariant_failed";
                System.err.println("FullChaosDraftPicker: native result failed Chaos validation: " + invalidNativeDeck);
            }
        }
        try {
            return onDeckBuilt(deck, seat, draft, result.chaosBuilder(), tuning, tuningStatus);
        } catch (RuntimeException reportingFailure) {
            System.err.println("FullChaosDraftPicker: deck diagnostics failed: " + reportingFailure);
            return deck;
        }
    }

    static boolean useDeckBuilder(boolean helperActive) {
        return helperActive && Boolean.parseBoolean(System.getProperty("forge.chaosDraft.deckBuilder", "true"));
    }

    private static void copySpecialSections(Deck from, Deck to) {
        if (from == null) {
            return;
        }
        for (DeckSection section : DeckSection.NONTRADITIONAL_SECTIONS) {
            if (from.has(section)) {
                to.getOrCreate(section).addAll(from.get(section));
            }
        }
    }

    static ChaosDraftEvaluator deckEvaluator(List<PaperCard> picked, DeckColors colors, Deck ownDeck) {
        return new ChaosDraftEvaluator(RATINGS, picked, colors.getChosenColors(), ownDeck, true, true);
    }

    record BuildResult(Deck deck, boolean chaosBuilder) {}

    static BuildResult buildWithFallback(List<PaperCard> pool, DeckColors deckColors, String landSetCode,
                                         BiFunction<List<PaperCard>, DeckColors, Deck> improved) {
        List<PaperCard> originalPool = List.copyOf(pool);
        DeckColors originalColors = copyColors(deckColors);
        if (improved != null) {
            try {
                Deck deck = improved.apply(new ArrayList<>(originalPool), copyColors(originalColors));
                return new BuildResult(Objects.requireNonNull(deck, "No Chaos deck was built"), true);
            } catch (RuntimeException failure) {
                System.err.println("FullChaosDraftPicker: falling back to the native deck builder: " + failure);
            }
        }
        return new BuildResult(new BoosterDeckBuilder(new ArrayList<>(originalPool), originalColors)
                .buildDeck(landSetCode), false);
    }

    private static DeckColors copyColors(DeckColors original) {
        DeckColors copy = new DeckColors(original.MAX_COLORS);
        copy.chosen = original.getChosenColors();
        copy.colorMask = copy.chosen.getColor();
        return copy;
    }

    public static PackEvaluation evaluateVisiblePack(IBoosterDraft draft, List<PaperCard> visible, Deck ownDeck) {
        return evaluateVisiblePack(draft, visible, ownDeck, RATINGS, needsHumanEvaluation(draft));
    }

    public static boolean needsHumanEvaluation(IBoosterDraft draft) {
        return showHumanHints(draft) || REPORT && isEnabledForDraft(draft);
    }

    static void recordHumanPick(BoosterDraft draft, PackEvaluation snapshot, PaperCard card, int pack, int pick) {
        if (REPORT && chaosDraft(draft) && snapshot != null) {
            draft.chaosReport().humanPick(snapshot, card, pack, pick);
        }
    }

    static PackEvaluation evaluateVisiblePack(IBoosterDraft draft, List<PaperCard> visible, Deck ownDeck,
                                               ChaosRatings ratings, boolean showHints) {
        if (!showHints || draft.getHumanPlayer().hasArchdemonCurse() || visible.isEmpty()) {
            return PackEvaluation.empty();
        }
        List<PaperCard> picked = ownDeck.getMain().toFlatList();
        if (ownDeck.has(DeckSection.Sideboard)) {
            picked.addAll(ownDeck.get(DeckSection.Sideboard).toFlatList());
        }
        ColorSet chosen = ChaosDraftEvaluator.humanColors(picked);
        List<PaperCard> cards = visible.stream()
                .filter(card -> card != PaperCard.FAKE_CARD && !card.getRules().isUnsupported()).toList();
        List<PaperCard> ranked = CardRanker.rankCardsInPack(cards, picked, chosen, true);
        return new ChaosDraftEvaluator(ratings, picked, chosen, ownDeck, false).preview(ranked, visible);
    }

    public static Deck onDeckBuilt(final Deck result, final int seat, final IBoosterDraft draft) {
        return onDeckBuilt(result, seat, draft, false, null, "disabled");
    }

    private static Deck onDeckBuilt(final Deck result, final int seat, final IBoosterDraft draft,
                                    final boolean chaosBuilder, ChaosDeckTuner.Result tuning, String tuningStatus) {
        if (!REPORT || !chaosDraft(draft)) {
            return result;
        }
        final List<PaperCard> main = result.getMain().toFlatList();
        final List<PaperCard> sideboard = result.getOrCreate(DeckSection.Sideboard).toFlatList();
        int lands = 0;
        for (final PaperCard card : main) {
            if (card.getRules().getType().isLand()) {
                lands++;
            }
        }
        final StringBuilder json = new StringBuilder("{\"event\":\"deck\",\"seat\":").append(seat)
                .append(",\"builder\":\"").append(chaosBuilder ? "chaos_rated" : "native").append('"')
                .append(",\"mainCount\":").append(main.size()).append(",\"landCount\":").append(lands)
                .append(",\"sideboardCount\":").append(sideboard.size()).append(",\"main\":");
        appendCards(json, main);
        json.append(",\"sideboard\":");
        appendCards(json, sideboard);
        json.append(",\"mainDetails\":");
        ChaosManaPlan mana = new ChaosManaPlan(main, new java.util.IdentityHashMap<>());
        ChaosDraftEvaluator evaluator = new ChaosDraftEvaluator(RATINGS, main, ColorSet.fromMask(0), result,
                true, true, mana, new java.util.IdentityHashMap<>());
        appendCardProfiles(json, main, evaluator, mana, true);
        json.append(",\"sideboardDetails\":");
        appendCardProfiles(json, sideboard, evaluator, mana, false);
        json.append(",\"nativeFallback\":").append(tuningStatus.startsWith("native_fallback"));
        json.append(",\"tuningStatus\":");
        appendString(json, tuningStatus);
        if (tuning != null) {
            json.append(",\"qualityBefore\":");
            appendMetrics(json, tuning.before());
            json.append(",\"qualityAfter\":");
            appendMetrics(json, tuning.after());
            json.append(",\"swaps\":[");
            for (int i = 0; i < tuning.swaps().size(); i++) {
                if (i != 0) {
                    json.append(',');
                }
                ChaosDeckTuner.Swap swap = tuning.swaps().get(i);
                json.append("{\"out\":");
                appendString(json, swap.out());
                json.append(",\"in\":");
                appendString(json, swap.in());
                json.append(",\"gain\":").append(swap.gain()).append('}');
            }
            json.append("],\"rejections\":{");
            boolean separator = false;
            for (var rejected : tuning.rejected().entrySet()) {
                if (separator) {
                    json.append(',');
                }
                appendString(json, rejected.getKey());
                json.append(':').append(rejected.getValue());
                separator = true;
            }
            json.append('}');
        }
        writeReport(draft, json.append('}').toString());
        return result;
    }

    private static void appendCards(final StringBuilder out, final List<PaperCard> cards) {
        out.append('[');
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendString(out, cards.get(i).getName());
        }
        out.append(']');
    }

    private static void appendMetrics(StringBuilder out, ChaosDeckTuner.Metrics metrics) {
        out.append("{\"score\":").append(metrics.score()).append(",\"castability\":").append(metrics.castability())
                .append(",\"uncastable\":").append(metrics.uncastable()).append(",\"early\":").append(metrics.early())
                .append(",\"creatures\":").append(metrics.creatures()).append(",\"interaction\":").append(metrics.interaction())
                .append(",\"lands\":").append(metrics.lands()).append(",\"landSourcesWUBRGCS\":").append(metrics.landSources())
                .append(",\"restricted\":").append(metrics.restricted()).append('}');
    }

    private static void appendCardProfiles(final StringBuilder out, final List<PaperCard> cards,
                                           ChaosDraftEvaluator evaluator, ChaosManaPlan mana, boolean main) {
        out.append('[');
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            final PaperCard card = cards.get(i);
            out.append("{\"name\":");
            appendString(out, card.getName());
            out.append(",\"edition\":");
            appendString(out, card.getEdition());
            out.append(",\"collector\":");
            appendString(out, card.getCollectorNumber());
            out.append(",\"rating\":");
            final ChaosRatings.Rating rating = RATINGS.lookup(card);
            final Double value = rating == null ? null : rating.value();
            out.append(value == null ? "null" : value);
            out.append(",\"ratingSource\":");
            appendString(out, rating == null ? "unrated" : rating.source());
            out.append(",\"manaValue\":").append(card.getRules().getManaCost().getCMC());
            out.append(",\"colors\":").append(card.getRules().getDeckbuildingColors().getColor());
            out.append(",\"land\":").append(card.getRules().getType().isLand());
            out.append(",\"creature\":").append(card.getRules().getType().isCreature());
            out.append(",\"remRandomDecks\":").append(card.getRules().getAiHints().getRemRandomDecks());
            var strength = evaluator.strength(card);
            appendStrength(out, strength);
            boolean land = card.getRules().getType().isLand();
            double castability = land ? 1 : mana.castability(card);
            out.append(",\"castability\":").append(castability).append(",\"disposition\":");
            appendString(out, main ? "selected_main" : !ChaosAiEvidence.deckAllowed(card, evaluator.pickedCards())
                    ? "ai_deck_restriction" : strength.aiSupport() == ChaosAiEvidence.Status.UNSUPPORTED
                    ? "ai_effect_unavailable" : castability == 0 ? "cannot_cast" : "sideboard_or_no_accepted_gain");
            out.append('}');
        }
        out.append(']');
    }

    private static void appendStrength(StringBuilder out, ChaosCardProfile.Strength strength) {
        out.append(",\"standalone\":").append(strength.standalone()).append(",\"contextualBase\":").append(strength.value())
                .append(",\"availableContribution\":").append(strength.availableContribution())
                .append(",\"unavailableContribution\":").append(strength.unavailableContribution())
                .append(",\"dependencyReason\":");
        appendString(out, strength.dependencyReason());
        out.append(",\"aiSupport\":");
        appendString(out, strength.aiSupport().name());
        out.append(",\"aiEvidence\":");
        appendString(out, strength.aiEvidence());
    }

    private static void appendString(final StringBuilder out, final String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static void recordPick(final IBoosterDraft draft, final int seat, final int pickedCount, final ColorSet chosen,
                                   final List<Candidate> candidates, final PaperCard nativePick,
                                   final PaperCard pick, final String reason) {
        if (!REPORT) {
            return;
        }
        final StringBuilder json = new StringBuilder("{\"event\":\"pick\",\"seat\":").append(seat)
                .append(",\"pickedBefore\":").append(pickedCount)
                .append(",\"chosenColorMask\":").append(chosen.getColor())
                .append(",\"native\":");
        appendString(json, nativePick.getName());
        json.append(",\"selected\":");
        appendString(json, pick.getName());
        json.append(",\"reason\":");
        appendString(json, reason);
        json.append(",\"candidates\":[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            final Candidate c = candidates.get(i);
            json.append("{\"name\":");
            appendString(json, c.card().getName());
            json.append(",\"edition\":");
            appendString(json, c.card().getEdition());
            json.append(",\"collector\":");
            appendString(json, c.card().getCollectorNumber());
            json.append(",\"nativeRank\":").append(c.nativeRank()).append(",\"rating\":");
            json.append(c.rating() == null ? "null" : c.rating());
            json.append(",\"ratingSource\":");
            appendString(json, c.ratingSource());
            json.append(",\"support\":");
            appendString(json, c.support().name());
            appendStrength(json, c.strength());
            json.append(",\"excluded\":");
            if (c.exclusion() == null) {
                json.append("null");
            } else {
                appendString(json, c.exclusion());
            }
            json.append(",\"score\":").append(c.selectable() ? Double.toString(c.score()) : "null")
                    .append(",\"poolBase\":").append(c.poolBase())
                    .append(",\"colorPenalty\":").append(c.colorPenalty())
                    .append(",\"curveBonus\":").append(c.curveBonus())
                    .append(",\"landBonus\":").append(c.landBonus())
                    .append(",\"manaBonus\":").append(c.manaBonus())
                    .append(",\"nativeBonus\":").append(c.nativeBonus())
                    .append(",\"manaValue\":").append(c.card().getRules().getManaCost().getCMC())
                    .append(",\"colors\":").append(c.card().getRules().getDeckbuildingColors().getColor())
                    .append(",\"land\":").append(c.card().getRules().getType().isLand())
                    .append(",\"creature\":").append(c.card().getRules().getType().isCreature())
                    .append(",\"remRandomDecks\":").append(c.card().getRules().getAiHints().getRemRandomDecks())
                    .append(",\"fitReason\":");
            appendString(json, c.fitReason());
            json.append('}');
        }
        writeReport(draft, json.append("]}").toString());
    }

    private static void writeReport(final IBoosterDraft draft, final String json) {
        if (REPORT && chaosDraft(draft)) {
            ((BoosterDraft) draft).chaosReport().legacy(json);
        }
    }
}
