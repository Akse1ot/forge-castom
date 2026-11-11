package forge.gamemodes.limited;

import forge.card.ColorSet;
import forge.card.mana.ManaCostShard;
import forge.item.PaperCard;

import java.util.List;
import java.util.function.Function;

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
    private final int rampPayoffs;
    private final int artifacts;

    ChaosDraftPlan(List<PaperCard> picked, ColorSet colors, Function<PaperCard, ChaosManaPlan.Source> sourceOf) {
        this.colors = colors;
        int coloredSpells = 0;
        int supportedSpells = 0;
        int lands = 0;
        int payoffs = 0;
        int fetchCount = 0;
        int expensive = 0;
        int material = 0;
        boolean colorless = false;
        for (PaperCard card : picked) {
            if (card.getMainFace().getType().isArtifact() && ChaosManaPlan.fitsColors(card, colors)) {
                material++;
            }
            if (card.getMainFace().getType().isLand()) {
                if (ChaosDraftEvaluator.ordinaryBasic(card)) {
                    continue;
                }
                var source = sourceOf.apply(card);
                if (source.fetchesBasic() || source.produces() != 0 && source.turn() < 5) {
                    lands++;
                }
                fetchCount += source.fetchesBasic() ? 1 : 0;
                if (source.traits().kind() != ChaosManaTraits.Kind.CHOSEN && (source.produces() & colors.getColor()) != 0) {
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
            if (ChaosManaPlan.fitsColors(card, colors)) {
                payoffs += card.getMainFace().getManaCost().getGenericCost() >= 3 ? 1 : 0;
                expensive += card.getMainFace().getManaCost().getCMC() >= 4 ? 1 : 0;
            }
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
        rampPayoffs = expensive;
        artifacts = material;
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
            return colors.countColors() >= 2 ? Math.max(0, 3 - landCount / 3.0) : 0;
        }
        int useful = usefulColors(source);
        if (source.traits().kind() == ChaosManaTraits.Kind.CHOSEN) {
            useful = Math.min(1, useful);
        }
        double existing = 0;
        for (int i = 0; i < fixing.length; i++) {
            if ((source.produces() & colors.getColor() & (1 << i)) != 0) {
                existing += Math.min(3, fixing[i]) * .35;
            }
        }
        return useful == 0 ? 0 : Math.max(0, useful * 3 - (source.turn() - 1) - existing - landCount / 3.0);
    }

    double landCeiling(ChaosManaPlan.Source source) {
        if (source.fetchesBasic()) {
            return (colors.countColors() >= 2 ? 62 : 49) - Math.min(7, landCount);
        }
        if (source.traits().kind() == ChaosManaTraits.Kind.CHOSEN) {
            return (colors.countColors() >= 2 ? 71 : 61) - Math.min(7, landCount);
        }
        if (source.traits().kind() == ChaosManaTraits.Kind.LAND_THRESHOLD && !source.traits().independentEffect()) {
            return 24 + Math.min(22, rampPayoffs * 3) + (needsColorless ? 5 : 0);
        }
        if (source.produces() == 0) {
            return 100;
        }
        if (usefulColors(source) > 1) {
            return 91 - Math.min(8, landCount);
        }
        if (usefulColors(source) == 1) {
            return ((source.produces() & COLORS) == colors.getColor() && colors.countColors() == 1 ? 69 : 72)
                    - Math.min(8, landCount);
        }
        if (colors.isColorless() && (source.produces() & COLORS) != 0) {
            return 64;
        }
        return 30 - 10 * commitment * commitment + Math.min(8, genericPayoffs) - Math.max(0, source.turn() - 1) * 3;
    }

    double sourceCeiling(PaperCard card, ChaosManaPlan.Source source, Double standalone) {
        var traits = source.traits();
        if (traits.kind() == ChaosManaTraits.Kind.FORMAT_UNAVAILABLE && source.produces() == 0 && !traits.independentEffect()) {
            return 1;
        }
        if (traits.kind() == ChaosManaTraits.Kind.UNKNOWN && source.produces() == 0 && !traits.independentEffect()) {
            return 54;
        }
        if (source.land()) {
            return landCeiling(source);
        }
        int body = standalone == null ? 0 : standalone.intValue();
        return switch (traits.kind()) {
            case STORAGE -> 22;
            case FODDER -> 18 + Math.min(30, Math.max(0, artifacts - 1) * 4);
            case ONE_SHOT -> 35 + Math.min(14, rampPayoffs * 2) + 7 * Math.max(0, traits.threshold() - 1);
            case AURA -> (ChaosManaPlan.fitsColors(card, colors) ? 55 : 42) + Math.min(15, rampPayoffs * 2);
            case REFLECTED -> Math.max(body, 47 + Math.min(16, rampPayoffs * 2));
            default -> card.getMainFace().getType().isCreature() && source.output() > source.activation()
                    ? Math.max(body, 60 + Math.min(24, rampPayoffs * 3) + (source.payment() <= 1 ? 9 : 0)
                            - (ChaosManaPlan.fitsColors(card, colors) ? 0 : 12))
                    : rockCeiling(source);
        };
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
            return (source.payment() == 0 ? 50 : 38) - 6 * commitment * commitment + Math.min(8, genericPayoffs);
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
