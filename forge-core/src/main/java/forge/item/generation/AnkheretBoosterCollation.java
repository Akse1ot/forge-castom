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
        if (!PROFILE.equals(template.getCollation()) || !template.getSlots().equals(List.of(
                Pair.of(BoosterSlots.COMMON,10), Pair.of(BoosterSlots.UNCOMMON,3),
                Pair.of(BoosterSlots.RARE_MYTHIC,1), Pair.of(BoosterSlots.LAND,1)))) {
            throw new IllegalArgumentException("Unsupported custom booster collation/template: " + template);
        }
        var data = StaticData.instance();
        return generate(pools(data.getCommonCards(), data.getEditions().get(template.getEdition())), MyRandom.getRandom());
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
        List<PaperCard> result = new ArrayList<>(15);
        List<List<PaperCard>> available = new ArrayList<>();
        for(int i=0;i<6;i++) {
            List<PaperCard> group = new ArrayList<>(pools.commons.get(i));available.add(group);
            if(i<5)result.add(take(group,random));
        }
        // Fixed group weights compensate the five locked cards; select within each remaining group.
        // Each of 95 commons has marginal probability 2/19, without duplicates, including the locks.
        for(int i=0;i<5;i++) {
            int ticket=random.nextInt(95);
            result.add(take(available.get(ticket<85?ticket/17:5),random));
        }
        List<PaperCard> uncommon = new ArrayList<>(pools.uncommons);
        for(int i=0;i<3;i++)result.add(take(uncommon,random));
        int rareTicket=random.nextInt(2*pools.rares.size()+pools.mythics.size());
        result.add(rareTicket<2*pools.rares.size()?pools.rares.get(rareTicket/2):pools.mythics.get(rareTicket-2*pools.rares.size()));
        int landTicket=random.nextInt(18816);
        for(int i=0;i<pools.lands.size();i++) {
            landTicket-=pools.landWeights.get(i);
            if(landTicket<0){result.add(pools.lands.get(i));break;}
        }
        return result;
    }
}
