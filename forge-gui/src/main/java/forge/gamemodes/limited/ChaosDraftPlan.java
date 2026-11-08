package forge.gamemodes.limited;

import forge.card.ColorSet;
import forge.card.mana.ManaCostShard;
import forge.item.PaperCard;

import java.util.List;

/** Draft commitment from spell demand and independently usable sources in the visible pool. */
final class ChaosDraftPlan {
    private static final int COLORS = 31;
    private static final int COLORLESS = 32;
    private final ColorSet colors;
    private final double commitment;
    private final int[] demand = new int[5];
    private final int[] fixing = new int[5];
    private final int[] fetches = new int[5];
    private final boolean needsColorless;
    private final int landCount;
    private final int genericPayoffs;

    ChaosDraftPlan(List<PaperCard> picked, ColorSet colors) {
        this.colors = colors;
        int coloredSpells = 0;
        int supportedSpells = 0;
        int lands = 0;
        int payoffs = 0;
        int fetchCount = 0;
        boolean colorless = false;
        for (PaperCard card : picked) {
            if (card.getMainFace().getType().isLand()) {
                if (ChaosDraftEvaluator.ordinaryBasic(card)) {
                    continue;
                }
                lands++;
                var source = ChaosManaPlan.readSource(card);
                fetchCount += source.fetchesBasic() ? 1 : 0;
                if ((source.produces() & colors.getColor()) != 0) {
                    for (int i = 0; i < fixing.length; i++) {
                        fixing[i] += (source.produces() & (1 << i)) == 0 ? 0 : 1;
                    }
                }
                continue;
            }
            int mask = ChaosManaPlan.demand(card);
            if ((mask & COLORS) != 0) {
                coloredSpells++;
                supportedSpells += ChaosManaPlan.fitsColors(card, colors) ? 1 : 0;
            }
            colorless |= (mask & COLORLESS) != 0;
            payoffs += card.getMainFace().getManaCost().getGenericCost() >= 3 ? 1 : 0;
            for (int i = 0; i < demand.length; i++) {
                demand[i] += (mask & (1 << i)) == 0 ? 0 : 1;
            }
        }
        for (int i = 0; i < fixing.length; i++) {
            fetches[i] = fixing[i] == 0 ? 0 : fetchCount;
        }
        double progress = Math.min(1, picked.size() / 28.0);
        double consistency = coloredSpells == 0 || colors.isColorless() ? 0 : supportedSpells / (double) coloredSpells;
        commitment = colors.isColorless() || coloredSpells == 0 ? 0 : progress * (0.45 + 0.55 * consistency);
        landCount = lands;
        needsColorless = colorless;
        genericPayoffs = payoffs;
    }

    double commitment() {
        return commitment;
    }

    boolean splash(PaperCard card) {
        int outside = ChaosManaPlan.demand(card) & COLORS & ~colors.getColor();
        if (colors.isColorless() || Integer.bitCount(outside) != 1 || card.getMainFace().getManaCost().getCMC() < 3) {
            return false;
        }
        int index = Integer.numberOfTrailingZeros(outside);
        int pips = 0;
        for (ManaCostShard shard : card.getMainFace().getManaCost()) {
            if (!shard.isPhyrexian() && !shard.isOr2Generic() && (shard.getColorMask() & outside) != 0) {
                pips++;
            }
        }
        return pips == 1 && fixing[index] + fetches[index] >= 2;
    }

    double colorPenalty(PaperCard card) {
        if (card.getMainFace().getType().isLand() || ChaosManaPlan.fitsColors(card, colors) && !colors.isColorless()) {
            return 0;
        }
        int outside = ChaosManaPlan.demand(card) & COLORS & ~colors.getColor();
        double result = 0;
        for (int i = 0; i < demand.length; i++) {
            if ((outside & (1 << i)) != 0) {
                double alternative = Math.min(.55, demand[i] / 15.0);
                result += 4 + 57 * commitment * (1 - alternative);
            }
        }
        return splash(card) ? result * .35 : result;
    }

    int usefulColors(ChaosManaPlan.Source source) {
        return Integer.bitCount(source.produces() & (colors.getColor() | (needsColorless ? COLORLESS : 0)) & 63);
    }

    double landBonus(ChaosManaPlan.Source source) {
        if (!source.land()) {
            return 0;
        }
        if (source.fetchesBasic()) {
            return colors.countColors() >= 2 ? 3 : 0;
        }
        int useful = usefulColors(source);
        return useful == 0 ? 0 : Math.max(0, useful * 3 - (source.turn() - 1) - landCount / 3.0);
    }

    double landCeiling(ChaosManaPlan.Source source) {
        if (source.fetchesBasic() || source.produces() == 0 || usefulColors(source) > 1) {
            return 100;
        }
        if (usefulColors(source) == 1) {
            return (source.produces() & COLORS) == colors.getColor() && colors.countColors() == 1 ? 78 : 70;
        }
        if (colors.isColorless() && (source.produces() & COLORS) != 0) {
            return 64;
        }
        return 26 + Math.min(8, genericPayoffs) - Math.max(0, source.turn() - 1) * 3;
    }

    double rockCeiling(ChaosManaPlan.Source source) {
        if (source.land() || source.produces() == 0 || source.payment() == 0 && colors.isColorless()) {
            return 100;
        }
        int useful = usefulColors(source);
        if (useful == 0) {
            if (source.output() - source.activation() >= 2 || source.payment() <= 1 && (source.produces() & COLORLESS) != 0) {
                return 100;
            }
            if (colors.isColorless()) {
                return 61 - Math.max(0, source.turn() - 2) * 5;
            }
            return (source.payment() == 0 ? 50 : 32) + Math.min(8, genericPayoffs);
        }
        int produced = Integer.bitCount(source.produces() & 63);
        if (source.activation() > 0 && useful < produced) {
            return 54;
        }
        return source.activation() == 0 && source.payment() >= 2 && source.output() == 1 && useful == 1
                ? 66 + Math.min(6, genericPayoffs) : 100;
    }

    double rockBonus(ChaosManaPlan.Source source) {
        return !source.land() && source.output() > source.activation()
                && (source.payment() <= 1 || source.output() - source.activation() >= 2)
                && (usefulColors(source) > 0 || source.produces() == COLORLESS || colors.isColorless()) ? 3 : 0;
    }
}
