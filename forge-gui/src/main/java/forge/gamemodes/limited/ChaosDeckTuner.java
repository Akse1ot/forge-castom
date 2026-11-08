package forge.gamemodes.limited;

import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Bounded deterministic swaps on top of the native Limited deck selection. */
final class ChaosDeckTuner {
    private static final int MAX_ROUNDS = 3;
    private static final int MAX_IN = 12;
    private static final int MAX_OUT = 12;
    private static final double MIN_GAIN = 2;

    record Swap(String out, String in, double gain) {}

    record Metrics(double score, double castability, int uncastable, int early, int creatures, int interaction,
                   int lands, List<Integer> landSources, int restricted) {}

    record Result(Deck deck, Metrics before, Metrics after, List<Swap> swaps,
                  Map<String, Integer> rejected, String invariant) {}

    private record Trial(int out, PaperCard in, Metrics metrics) {}

    private final ChaosRatings ratings;
    private final ColorSet colors;
    private final Map<PaperCard, ChaosCardProfile> profiles = new IdentityHashMap<>();
    private final Map<PaperCard, ChaosManaPlan.Source> sources = new IdentityHashMap<>();

    ChaosDeckTuner(ChaosRatings ratings, ColorSet colors) {
        this.ratings = ratings;
        this.colors = colors;
    }

    Result improve(Deck seed, List<PaperCard> stock) {
        List<PaperCard> main = seed.getMain().toFlatList();
        List<PaperCard> sideboard = seed.has(DeckSection.Sideboard)
                ? seed.get(DeckSection.Sideboard).toFlatList() : List.of();
        Metrics before = metrics(main, seed);
        Metrics current = before;
        Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
        List<Swap> swaps = new ArrayList<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            ChaosManaPlan mana = new ChaosManaPlan(main, sources);
            ChaosDraftEvaluator evaluator = evaluator(main, seed, mana);
            Map<PaperCard, Double> order = new IdentityHashMap<>();
            List<PaperCard> rankedIn = sideboard.stream().distinct()
                    .filter(card -> DeckSection.matchingSection(card) == DeckSection.Main && !freeBasic(card))
                    .sorted(Comparator.<PaperCard>comparingDouble(card ->
                            order.computeIfAbsent(card, item -> rank(item, evaluator, mana))).reversed())
                    .toList();
            List<Integer> rankedOut = java.util.stream.IntStream.range(0, main.size()).boxed()
                    .sorted(Comparator.comparingDouble(index ->
                            order.computeIfAbsent(main.get(index), item -> rank(item, evaluator, mana))))
                    .toList();
            List<PaperCard> in = new ArrayList<>();
            List<Integer> out = new ArrayList<>();
            for (boolean land : new boolean[]{false, true}) {
                var eligible = rankedIn.stream().filter(card -> card.getRules().getType().isLand() == land);
                if (land) {
                    eligible = java.util.stream.Stream.concat(eligible, main.stream()
                            .filter(ChaosDeckTuner::freeBasic).distinct());
                }
                eligible.limit(land ? 6 : MAX_IN).forEach(in::add);
                rankedOut.stream().filter(index -> main.get(index).getRules().getType().isLand() == land)
                        .limit(land ? 6 : MAX_OUT).forEach(out::add);
            }
            Trial best = null;
            List<PaperCard> trial = new ArrayList<>(main);
            for (PaperCard replacement : in) {
                for (int index : out) {
                    PaperCard original = main.get(index);
                    if (original.equals(replacement)
                            || original.getRules().getType().isLand() != replacement.getRules().getType().isLand()) {
                        continue;
                    }
                    trial.set(index, replacement);
                    Metrics value = metrics(trial, seed);
                    trial.set(index, original);
                    String rejection = reject(value, current, before);
                    if (rejection == null && ChaosManaPlan.hasUnmodeledLandAbility(original)
                            && (value.uncastable() >= current.uncastable() || value.castability() < current.castability() + 1)) {
                        rejection = "unmodeled_land_ability";
                    }
                    if (rejection != null) {
                        rejected.merge(rejection, 1, Integer::sum);
                    } else if (best == null || value.score() > best.metrics().score()) {
                        best = new Trial(index, replacement, value);
                    }
                }
            }
            if (best == null) {
                break;
            }
            PaperCard removed = main.set(best.out(), best.in());
            swaps.add(new Swap(removed.getName(), best.in().getName(), best.metrics().score() - current.score()));
            current = best.metrics();
            Deck board = (Deck) seed.copyTo(seed.getName());
            board.getMain().clear();
            board.getMain().add(main);
            reconcile(board, stock);
            sideboard = board.getOrCreate(DeckSection.Sideboard).toFlatList();
        }
        if (swaps.isEmpty()) {
            return new Result(seed, before, before, List.of(), rejected, "valid_seed_no_improvement");
        }
        Deck result = (Deck) seed.copyTo(seed.getName());
        result.getMain().clear();
        result.getMain().add(main);
        reconcile(result, stock);
        return new Result(result, before, current, List.copyOf(swaps), rejected, "valid");
    }

    private static String reject(Metrics next, Metrics current, Metrics baseline) {
        if (next.restricted() != 0) {
            return "ai_deck_restriction";
        }
        if (next.uncastable() > current.uncastable() || next.castability() + .45 < current.castability()) {
            return "castability";
        }
        if (next.early() < roleFloor(7, baseline.early()) || next.creatures() < roleFloor(12, baseline.creatures())
                || next.interaction() < roleFloor(4, baseline.interaction())) {
            return "role_floor";
        }
        return next.score() < current.score() + MIN_GAIN ? "no_deck_gain" : null;
    }

    private static int roleFloor(int target, int baseline) {
        return Math.max(Math.min(target, baseline), baseline - 1);
    }

    private ChaosDraftEvaluator evaluator(List<PaperCard> main, Deck deck, ChaosManaPlan mana) {
        return new ChaosDraftEvaluator(ratings, main, colors, deck, true, true, mana, profiles);
    }

    private Metrics metrics(List<PaperCard> main, Deck deck) {
        ChaosManaPlan mana = new ChaosManaPlan(main, sources);
        ChaosDraftEvaluator evaluator = evaluator(main, deck, mana);
        double score = 0;
        double casting = 0;
        int uncastable = 0;
        int early = 0;
        int creatures = 0;
        int interaction = 0;
        int restricted = 0;
        for (PaperCard card : main) {
            if (!ChaosAiEvidence.deckAllowed(card, main)) {
                restricted++;
            }
            if (card.getRules().getType().isLand()) {
                score += rank(card, evaluator, mana);
                continue;
            }
            double cast = mana.castability(card);
            casting += cast;
            uncastable += cast == 0 ? 1 : 0;
            var strength = evaluator.strength(card);
            boolean usable = cast >= .55 && strength.aiSupport() != ChaosAiEvidence.Status.UNSUPPORTED;
            var profile = evaluator.profile(card);
            double standalone = spellValue(card, strength, evaluator);
            early += usable && card.getMainFace().getManaCost().getCMC() <= 2
                    && (!profile.manaOnly() || standalone >= 45) ? 1 : 0;
            creatures += usable && card.getRules().getType().isCreature() ? 1 : 0;
            interaction += usable && profile.interaction() ? 1 : 0;
            score += standalone * (.25 + .75 * cast);
        }
        score += 5 * Math.min(7, early) + 4 * Math.min(12, creatures) + 4 * Math.min(4, interaction);
        score -= 30 * uncastable + 100 * restricted;
        return new Metrics(score, casting, uncastable, early, creatures, interaction, mana.lands(),
                mana.landSources(), restricted);
    }

    private double rank(PaperCard card, ChaosDraftEvaluator evaluator, ChaosManaPlan mana) {
        var source = mana.resolve(sources.computeIfAbsent(card, ChaosManaPlan::readSource));
        if (source.land()) {
            double value = source.produces() == 0 ? 0 : (mana.contributes(source) ? 5 : 2) - source.turn();
            if (!freeBasic(card)) {
                value += evaluator.landUtility(source);
                var strength = evaluator.strength(card);
                if (strength.value() != null) {
                    value += (evaluator.poolValue(card, strength, 35) - 35) * .1;
                }
            }
            return value;
        }
        return spellValue(card, evaluator.strength(card), evaluator) * (.25 + .75 * mana.castability(card));
    }

    private double spellValue(PaperCard card, ChaosCardProfile.Strength strength,
                              ChaosDraftEvaluator evaluator) {
        if (strength.aiSupport() == ChaosAiEvidence.Status.UNSUPPORTED) {
            return 0;
        }
        return evaluator.poolValue(card, strength, 30);
    }

    static void reconcile(Deck deck, List<PaperCard> stock) {
        if (deck.getMain().countAll() != 40) {
            throw new IllegalStateException("Chaos seed has " + deck.getMain().countAll() + " main cards");
        }
        DeckFormat limited = DeckFormat.Limited;
        for (String problem : new String[]{
                deck.has(DeckSection.Attractions) ? limited.getAttractionDeckConformanceProblem(deck) : null,
                deck.has(DeckSection.Contraptions) ? limited.getContraptionDeckConformanceProblem(deck) : null,
                deck.has(DeckSection.Stickers) ? limited.getStickerSheetConformanceProblem(deck) : null}) {
            if (problem != null) {
                throw new IllegalStateException(problem);
            }
        }
        Map<PaperCard, Integer> remaining = new HashMap<>();
        stock.forEach(card -> remaining.merge(card, 1, Integer::sum));
        for (DeckSection section : DeckSection.values()) {
            if (section == DeckSection.Sideboard || !deck.has(section)) {
                continue;
            }
            for (PaperCard card : deck.get(section).toFlatList()) {
                if (!section.validate(card) || (section == DeckSection.Main && DeckSection.matchingSection(card) != section)) {
                    throw new IllegalStateException("Card in wrong section: " + card.getName() + " in " + section);
                }
                int count = remaining.getOrDefault(card, 0);
                if (count > 0) {
                    remaining.put(card, count - 1);
                } else if (section != DeckSection.Main || !freeBasic(card)) {
                    throw new IllegalStateException("Card exceeds draft stock: " + card.getName());
                }
            }
        }
        CardPool board = deck.getOrCreate(DeckSection.Sideboard);
        board.clear();
        for (PaperCard card : stock) {
            int count = remaining.getOrDefault(card, 0);
            if (count > 0) {
                board.add(card);
                remaining.put(card, count - 1);
            }
        }
    }

    static void repairSeedRestrictions(Deck seed) {
        // Native size repair may bring an unsupported Random card back into main.
        List<PaperCard> main = seed.getMain().toFlatList();
        PaperCard basic = main.stream().filter(ChaosDeckTuner::freeBasic).findFirst().orElse(null);
        boolean changed;
        do {
            changed = false;
            List<PaperCard> allowed = main.stream().filter(card -> ChaosAiEvidence.deckAllowed(card, main)).toList();
            if (allowed.size() != main.size()) {
                if (basic == null) {
                    basic = FModel.getMagicDb().getCommonCards().getCard("Wastes");
                }
                if (basic == null) {
                    throw new IllegalStateException("No legal filler for native deck repair");
                }
                int missing = main.size() - allowed.size();
                main.clear();
                main.addAll(allowed);
                main.addAll(java.util.Collections.nCopies(missing, basic));
                changed = true;
            }
        } while (changed);
        seed.getMain().clear();
        seed.getMain().add(main);
    }

    private static boolean freeBasic(PaperCard card) {
        return card.getRules().getType().isBasicLand() && !card.getRules().getType().isSnow();
    }
}
