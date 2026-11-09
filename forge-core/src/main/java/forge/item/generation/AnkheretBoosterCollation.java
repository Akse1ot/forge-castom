package forge.item.generation;

import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import forge.util.MyRandom;
import org.apache.commons.lang3.tuple.Pair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Exact, explicitly selected Ankheret profile; no foil or independent-sheet replacements. */
public final class AnkheretBoosterCollation {
    public static final String PROFILE = "ANKH_EXACT_V1";
    private static CardDb cachedDb;
    private static CardEdition cachedEdition;
    private static Pools cachedPools;

    private AnkheretBoosterCollation() { }

    public static List<PaperCard> generate(SealedTemplate template) {
        return generate(template, Set.of());
    }

    static List<PaperCard> generate(SealedTemplate template, Collection<String> excludedNames) {
        if (!PROFILE.equals(template.getCollation()) || !template.getSlots().equals(List.of(
                Pair.of(BoosterSlots.COMMON,10), Pair.of(BoosterSlots.UNCOMMON,3),
                Pair.of(BoosterSlots.RARE_MYTHIC,1), Pair.of(BoosterSlots.LAND,1)))) {
            throw new IllegalArgumentException("Unsupported custom booster collation/template: " + template);
        }
        var data = StaticData.instance();
        return generate(pools(data.getCommonCards(), data.getEditions().get(template.getEdition())),
                MyRandom.getRandom(), excludedNames == null ? Set.of() : excludedNames);
    }

    private static synchronized Pools pools(CardDb db, CardEdition edition) {
        if (edition == null) {
            throw new IllegalArgumentException("Ankheret collation requires a loaded edition");
        }
        if (db != cachedDb || edition != cachedEdition) {
            List<PaperCard> cards = new ArrayList<>();
            for (var entry : edition.getAllCardsInSet()) {
                PaperCard card = db.getCard(entry.name(), edition.getCode());
                if (card == null || !edition.getCode().equals(card.getEdition()) || card.getRarity() != entry.rarity()) {
                    throw new IllegalStateException("Unloaded/mismatched booster card: " + entry.name());
                }
                cards.add(card);
            }
            Pools loaded = new Pools(cards);
            cachedDb = db;
            cachedEdition = edition;
            cachedPools = loaded;
        }
        return cachedPools;
    }

    static final class Pools {
        final List<List<PaperCard>> commons = new ArrayList<>();
        final List<PaperCard> uncommons = new ArrayList<>(), rares = new ArrayList<>(), mythics = new ArrayList<>();
        final List<PaperCard> lands = new ArrayList<>();
        final List<Integer> landWeights = new ArrayList<>();
        Pools(Collection<PaperCard> cards) {
            for (int i=0;i<6;i++) commons.add(new ArrayList<>());
            Set<String> seen = new HashSet<>();
            int[] landCounts = new int[4];
            for (PaperCard card : cards) {
                var type = card.getRules().getType();
                if (type.isBasicLand()) continue;
                if (!seen.add(card.getName())) throw new IllegalArgumentException("Duplicate nonbasic identity: " + card.getName());
                if (type.isLand()) {
                    int group = switch (card.getRarity()) {case Common -> 0;case Uncommon -> 1;case Rare -> 2;case MythicRare -> 3;default -> throw new IllegalArgumentException("Unexpected land rarity");};
                    lands.add(card);landCounts[group]++;
                    landWeights.add(new int[]{2240,576,147,168}[group]);
                } else {
                    switch (card.getRarity()) {
                        case Common -> {
                            int group = switch(card.getRules().getColor().getColor()) {case 1 -> 0;case 2 -> 1;case 4 -> 2;case 8 -> 3;case 16 -> 4;case 0 -> 5;default -> throw new IllegalArgumentException("Unexpected common color");};
                            commons.get(group).add(card);
                        }
                        case Uncommon -> uncommons.add(card);
                        case Rare -> rares.add(card);
                        case MythicRare -> mythics.add(card);
                        default -> throw new IllegalArgumentException("Unexpected nonland rarity");
                    }
                }
            }
            for(int i=0;i<6;i++) require(commons.get(i).size()==(i<5?18:5),"common color pool");
            require(uncommons.size()==73 && rares.size()==45 && mythics.size()==14,
                    "nonland rarity pools: U=" + uncommons.size()
                            + ", R=" + rares.size()
                            + ", M=" + mythics.size());
            require(java.util.Arrays.equals(landCounts,new int[]{6,7,8,1}),"nonbasic land pool");
        }
        private static void require(boolean valid,String pool) {
            if(!valid)throw new IllegalStateException("Incomplete Ankheret exact collation: " + pool);
        }
    }

    private static PaperCard take(List<PaperCard> cards, Random random) {
        return cards.remove(random.nextInt(cards.size()));
    }

    static List<PaperCard> generate(Pools pools, Random random) {
        return generate(pools, random, Set.of());
    }

    static List<PaperCard> generate(Pools pools, Random random, Collection<String> excludedNames) {
        List<PaperCard> result = new ArrayList<>(15);
        List<List<PaperCard>> available = new ArrayList<>(6);
        int[] groupWeights = {17, 17, 17, 17, 17, 10};
        for (int i = 0; i < groupWeights.length; i++) {
            List<PaperCard> group = excluding(pools.commons.get(i), excludedNames);
            available.add(group);
            if (i < 5 && !group.isEmpty()) {
                result.add(take(group, random));
            }
        }
        // Fixed group weights compensate the five locked cards; select within each remaining group.
        // Each of 95 commons has marginal probability 2/19, without duplicates, including the locks.
        while (result.size() < 10) {
            int weight = 0;
            for (int i = 0; i < groupWeights.length; i++) {
                if (available.get(i).isEmpty()) {
                    groupWeights[i] = 0;
                }
                weight += groupWeights[i];
            }
            if (weight == 0) {
                break;
            }
            int ticket = random.nextInt(weight);
            int group = 0;
            while (ticket >= groupWeights[group]) {
                ticket -= groupWeights[group++];
            }
            result.add(take(available.get(group), random));
        }
        List<PaperCard> uncommon = excluding(pools.uncommons, excludedNames);
        for (int i = 0; i < 3 && !uncommon.isEmpty(); i++) {
            result.add(take(uncommon, random));
        }
        List<PaperCard> rares = excluding(pools.rares, excludedNames);
        List<PaperCard> mythics = excluding(pools.mythics, excludedNames);
        int rareWeight = 2 * rares.size();
        if (rareWeight + mythics.size() > 0) {
            int ticket = random.nextInt(rareWeight + mythics.size());
            result.add(ticket < rareWeight ? rares.get(ticket / 2) : mythics.get(ticket - rareWeight));
        }
        int landWeight = 0;
        for (int i = 0; i < pools.lands.size(); i++) {
            if (!excludedNames.contains(pools.lands.get(i).getName())) {
                landWeight += pools.landWeights.get(i);
            }
        }
        if (landWeight > 0) {
            int ticket = random.nextInt(landWeight);
            for (int i = 0; i < pools.lands.size(); i++) {
                PaperCard land = pools.lands.get(i);
                if (!excludedNames.contains(land.getName())) {
                    ticket -= pools.landWeights.get(i);
                    if (ticket < 0) {
                        result.add(land);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private static List<PaperCard> excluding(List<PaperCard> cards, Collection<String> excludedNames) {
        List<PaperCard> available = new ArrayList<>(cards);
        available.removeIf(card -> excludedNames.contains(card.getName()));
        return available;
    }
}
