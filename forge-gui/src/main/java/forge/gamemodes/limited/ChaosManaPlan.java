package forge.gamemodes.limited;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.card.mana.ManaCostShard;
import forge.game.ability.AbilityFactory;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Conservative source estimates for a fixed 40-card plan, without simulating gameplay. */
final class ChaosManaPlan {
    private static final int COLORLESS = 32;
    private static final int SNOW = 64;
    private static final int ALL_COLORS = 31;

    record Source(int produces, int payment, int activation, int output, int turn, boolean land, boolean fetchesBasic) {}

    private final List<Source> sources;
    private final int[] landSources = new int[7];
    private final int landCount;
    private final int demand;
    private final int basicTargets;
    private final Map<PaperCard, Source> included = new java.util.IdentityHashMap<>();

    ChaosManaPlan(List<PaperCard> cards, Map<PaperCard, Source> cache) {
        List<Source> lands = new ArrayList<>();
        List<PaperCard> rocks = new ArrayList<>();
        int mask = 0;
        int count = 0;
        int basicMask = 0;
        for (PaperCard card : cards) {
            if (card.getRules().getType().isBasicLand()) {
                basicMask |= cache.computeIfAbsent(card, ChaosManaPlan::readSource).produces();
            }
        }
        basicTargets = basicMask;
        for (PaperCard card : cards) {
            boolean land = card.getRules().getType().isLand();
            if (land) {
                count++;
            } else {
                mask |= demand(card);
            }
            Source source = cache.computeIfAbsent(card, ChaosManaPlan::readSource);
            source = resolve(source);
            if (source.produces() == 0) {
                continue;
            }
            included.put(card, source);
            if (land) {
                lands.add(source);
                for (int i = 0; i < landSources.length; i++) {
                    if ((source.produces() & (1 << i)) != 0) {
                        landSources[i]++;
                    }
                }
            } else {
                rocks.add(card);
            }
        }
        demand = mask;
        landCount = count;
        sources = new ArrayList<>(lands);
        List<Source> payable = rocks.stream()
                .filter(card -> lands.size() >= included.get(card).payment() && castability(card) >= .5)
                .map(included::get).toList();
        sources.addAll(payable);
    }

    int lands() {
        return landCount;
    }

    List<Integer> landSources() {
        return java.util.Arrays.stream(landSources).boxed().toList();
    }

    double castability(PaperCard card) {
        double best = castability(card.getMainFace().getManaCost(), included.get(card));
        for (String keyword : card.getMainFace().getKeywords()) {
            String[] parts = keyword.split(":", 3);
            if (parts.length == 2 && (parts[0].equals("Evoke") || parts[0].equals("Dash"))
                    && parts[1].matches("[0-9WUBRGC ]+")) {
                ManaCost alternative = new ManaCost(new ManaCostParser(parts[1].trim()));
                best = Math.max(best, .65 * castability(alternative, included.get(card)));
            }
        }
        return best;
    }

    private double castability(ManaCost cost, Source ownSource) {
        if (cost.isNoCost()) {
            return 0;
        }
        if (cost.getCMC() == 0) {
            return 1;
        }
        int turn = Math.max(2, Math.min(8, cost.getCMC() + 1));
        int[] usable = new int[sources.size()];
        int[] drawAccess = new int[sources.size()];
        int available = 0;
        int netSources = 0;
        int accessible = 0;
        boolean skippedOwn = false;
        for (Source source : sources) {
            if (source == ownSource && !skippedOwn) {
                skippedOwn = true;
            } else if (source.turn() <= turn) {
                drawAccess[accessible++] = source.produces();
                // A fetch is another route to a basic, never an extra physical source.
                if (!source.fetchesBasic()) {
                    usable[available++] = source.produces();
                    if (source.output() > source.activation()) {
                        netSources++;
                    }
                }
            }
        }
        int minimumMana = cost.getGenericCost();
        for (ManaCostShard shard : cost) {
            minimumMana += shard == ManaCostShard.X || shard.isPhyrexian() ? 0 : 1;
        }
        if (netSources < minimumMana) {
            return 0;
        }
        double quality = 1;
        List<Integer> required = new ArrayList<>();
        int[] repeats = new int[128];
        for (ManaCostShard shard : cost) {
            int mask = requiredMask(shard);
            if (mask != 0) {
                required.add(mask);
                int count = 0;
                for (int source : drawAccess) {
                    if ((source & mask) != 0) {
                        count++;
                    }
                }
                int repeat = ++repeats[mask];
                quality = Math.min(quality, Math.min(1, count / (double) (repeat + (turn <= 3 ? 5 : 3))));
            }
        }
        required.sort(java.util.Comparator.comparingInt(Integer::bitCount));
        int[] assigned = new int[usable.length];
        java.util.Arrays.fill(assigned, -1);
        for (int i = 0; i < required.size(); i++) {
            if (!assign(i, required, usable, assigned, new boolean[usable.length])) {
                return 0;
            }
        }
        return quality;
    }

    // Each source can satisfy only one required symbol in this conservative matching.
    private static boolean assign(int shard, List<Integer> required, int[] usable, int[] assigned, boolean[] visited) {
        for (int i = 0; i < usable.length; i++) {
            if (!visited[i] && (usable[i] & required.get(shard)) != 0) {
                visited[i] = true;
                if (assigned[i] < 0 || assign(assigned[i], required, usable, assigned, visited)) {
                    assigned[i] = shard;
                    return true;
                }
            }
        }
        return false;
    }

    boolean contributes(Source source) {
        return (source.produces() & demand) != 0;
    }

    int usefulColors(Source source) {
        return Integer.bitCount(source.produces() & demand & (ALL_COLORS | COLORLESS));
    }

    Source resolve(Source source) {
        return source.fetchesBasic() ? new Source(basicTargets, 0, 0, 1, 2, true, true) : source;
    }

    static int demand(PaperCard card) {
        int mask = 0;
        for (ManaCostShard shard : card.getMainFace().getManaCost()) {
            mask |= requiredMask(shard);
        }
        return mask;
    }

    static boolean fitsColors(PaperCard card, ColorSet colors) {
        if (colors.isColorless() || card.getRules().getType().isLand()) {
            return true;
        }
        for (ManaCostShard shard : card.getMainFace().getManaCost()) {
            int mask = requiredMask(shard);
            if ((mask & ALL_COLORS) != 0 && (mask & colors.getColor()) == 0) {
                return false;
            }
        }
        return true;
    }

    private static int requiredMask(ManaCostShard shard) {
        if (shard.isPhyrexian() || shard.isOr2Generic()) {
            return 0;
        }
        if (shard.isColorless()) {
            return COLORLESS;
        }
        if (shard.isSnow()) {
            return SNOW;
        }
        int mask = 0;
        if (shard != ManaCostShard.X) {
            for (byte color : MagicColor.WUBRG) {
                if (shard.canBePaidWithManaOfColor(color)) {
                    mask |= color;
                }
            }
        }
        return mask;
    }

    static Source readSource(PaperCard card) {
        var face = card.getMainFace();
        boolean land = face.getType().isLand();
        int mask = 0;
        int payment = Integer.MAX_VALUE;
        int output = 0;
        int manaValue = face.getManaCost().getCMC();
        if (land) {
            for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                if (face.getType().hasSubtype(MagicColor.Constant.BASIC_LANDS.get(i))) {
                    mask |= MagicColor.WUBRG[i];
                    payment = 0;
                    output = 1;
                }
            }
        }
        for (String ability : face.getAbilities()) {
            Map<String, String> params = AbilityFactory.getMapParams(ability);
            if (land && basicFetch(params)) {
                return new Source(0, 0, 0, 1, 2, true, true);
            }
            if (!simpleMana(params)) {
                continue;
            }
            int multiplier = ChaosManaAmount.read(params, face).guaranteed(card);
            if (multiplier == 0) {
                continue;
            }
            String cost = params.getOrDefault("Cost", "");
            if (!cost.matches("(?:[0-9] )?T")) {
                continue;
            }
            String raw = params.getOrDefault("Produced", "");
            boolean choice = raw.startsWith("Combo ") || "Any".equalsIgnoreCase(raw);
            String produced = raw.replaceFirst("^Combo ", "");
            int producedMask = 0;
            int amount = 1;
            if ("Any".equalsIgnoreCase(produced)) {
                producedMask = ALL_COLORS;
            } else if (produced.matches("[1-9]")) {
                producedMask = COLORLESS;
                amount = Integer.parseInt(produced);
            } else if (produced.matches("[WUBRGC](?: [WUBRGC])*")) {
                for (String symbol : produced.split(" ")) {
                    producedMask |= "C".equals(symbol) ? COLORLESS : MagicColor.fromName(symbol);
                }
                amount = choice ? 1 : produced.split(" ").length;
            }
            if (producedMask != 0) {
                amount *= multiplier;
                int activation = cost.equals("T") ? 0 : Integer.parseInt(cost.substring(0, 1));
                int total = (land ? 0 : manaValue) + activation;
                if (total <= payment) {
                    mask = total == payment ? mask | producedMask : producedMask;
                    output = total == payment ? Math.min(output, amount) : amount;
                    payment = total;
                }
            }
        }
        if (mask == 0) {
            return new Source(0, 0, 0, 0, 0, land, false);
        }
        if (face.getType().isSnow()) {
            mask |= SNOW;
        }
        boolean tapped = false;
        for (String keyword : face.getKeywords()) {
            tapped |= keyword.startsWith("ETBReplacement:Other:") || "CARDNAME enters tapped.".equals(keyword);
        }
        for (String replacement : face.getReplacements()) {
            Map<String, String> params = AbilityFactory.getMapParams(replacement);
            tapped |= "Moved".equals(params.get("Event")) && "Battlefield".equals(params.get("Destination"))
                    && "Card.Self".equals(params.get("ValidCard"))
                    && params.getOrDefault("ReplaceWith", "").toLowerCase(java.util.Locale.ROOT).contains("tapped");
        }
        // A generic activation consumes a previously available source. Creatures wait a turn.
        int turn = land ? Math.max(1, payment + 1 + (tapped ? 1 : 0))
                : Math.max(manaValue + 1, payment);
        return new Source(mask, payment, payment - (land ? 0 : manaValue), output, turn, land, false);
    }

    private static boolean simpleMana(Map<String, String> params) {
        return "Mana".equals(params.get("AB")) && params.keySet().stream().allMatch(
                java.util.Set.of("AB", "Cost", "Produced", "Amount", "SpellDescription", "Description", "Secondary")::contains)
                && params.getOrDefault("Produced", "").matches("(?:Any|[1-9]|(?:Combo )?[WUBRGC](?: [WUBRGC])*)");
    }

    private static boolean basicFetch(Map<String, String> params) {
        return "ChangeZone".equals(params.get("AB")) && "Library".equals(params.get("Origin"))
                && "Battlefield".equals(params.get("Destination")) && "Land.Basic".equals(params.get("ChangeType"))
                && "T Sac<1/CARDNAME>".equals(params.get("Cost")) && "1".equals(params.getOrDefault("ChangeNum", "1"));
    }

    static boolean hasUnmodeledLandAbility(PaperCard card) {
        var face = card.getMainFace();
        if (!face.getType().isLand() || face.getType().isBasicLand()) {
            return false;
        }
        if (face.getTriggers().iterator().hasNext() || face.getStaticAbilities().iterator().hasNext()
                || face.getReplacements().iterator().hasNext() || face.getKeywords().iterator().hasNext()) {
            return true;
        }
        for (String ability : face.getAbilities()) {
            var params = AbilityFactory.getMapParams(ability);
            if (!basicFetch(params) && (!simpleMana(params) || ChaosManaAmount.read(params, face).variable()
                    || !params.getOrDefault("Cost", "").matches("(?:[0-9] )?T"))) {
                return true;
            }
        }
        return false;
    }
}
