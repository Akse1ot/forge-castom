package forge.gamemodes.limited;

import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses the optional Chaos ratings for the selection order of an AI Limited deck.
 * All construction, land allocation and AI restriction checks stay in LimitedDeckBuilder.
 */
public final class FullChaosBoosterDeckBuilder extends BoosterDeckBuilder {
    private final ChaosDraftEvaluator evaluator;

    public FullChaosBoosterDeckBuilder(final List<PaperCard> cards, final DeckColors deckColors) {
        this(cards, deckColors, FullChaosDraftPicker.deckEvaluator(List.copyOf(cards), deckColors, null));
    }

    FullChaosBoosterDeckBuilder(List<PaperCard> cards, DeckColors deckColors, ChaosDraftEvaluator evaluator) {
        super(cards, deckColors);
        this.evaluator = evaluator;
    }

    private List<PaperCard> rank(final Iterable<PaperCard> cards) {
        final java.util.Map<PaperCard, Double> scores = new java.util.IdentityHashMap<>();
        final List<PaperCard> result = new ArrayList<>();
        for (final PaperCard card : cards) {
            result.add(card);
            scores.computeIfAbsent(card, this::score);
        }
        // Java's stable sort preserves Forge's original order on ties.
        result.sort((left, right) -> Double.compare(scores.get(right), scores.get(left)));
        return result;
    }

    private double score(final PaperCard card) {
        final var strength = evaluator.strength(card);
        return evaluator.poolValue(card, strength, strength.value() == null ? CardRanker.getRawScore(card) : 0);
    }

    @Override
    protected void addManaCurveCreatures(final Iterable<PaperCard> creatures, final int num) {
        // Avoid the native 16-creature opening quota monopolizing the 22 spell slots
        // when strong noncreature spells are available; the native fallback can still
        // add additional creatures if the pool lacks enough good noncreature spells.
        super.addManaCurveCreatures(rank(creatures), Math.min(num, 12));
    }

    @Override
    protected void addNonCreatures(final Iterable<PaperCard> nonCreatures, final int num) {
        super.addNonCreatures(rank(nonCreatures), num);
    }

    @Override
    protected void addCreatures(final Iterable<PaperCard> creatures, final int num) {
        super.addCreatures(rank(creatures), num);
    }

    @Override
    protected void addNonBasicLands() {
        super.addNonBasicLands();
        // Keep the native size repair from entering its zero-color, empty-pool loop.
        if (landsNeeded <= 0 && deckList.size() < getDeckSizeTarget()
                && java.util.Arrays.stream(calculateLandNeeds()).sum() == 0) {
            landsNeeded = 1;
        }
    }

    @Override
    protected void addLands(final int[] colorNeeds, final String landSetCode) {
        if (java.util.Arrays.stream(colorNeeds).sum() > 0) {
            super.addLands(colorNeeds, landSetCode);
            return;
        }
        PaperCard wastes = FModel.getMagicDb().getCommonCards().getCard("Wastes");
        PaperCard basic = wastes == null ? getBasicLand(0, landSetCode) : wastes;
        if (basic == null) {
            throw new IllegalStateException("No basic land for a colorless Chaos deck");
        }
        while (deckList.size() < getDeckSizeTarget()) {
            deckList.add(basic);
        }
    }
}
