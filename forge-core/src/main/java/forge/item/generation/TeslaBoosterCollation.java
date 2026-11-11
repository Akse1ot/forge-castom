package forge.item.generation;

import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardSplitType;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import forge.util.MyRandom;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/** Reuben collation for the Tesla v1.05 edition, with a fixed DFC across retries. */
final class TeslaBoosterCollation {
    static final String PROFILE = "TESL_REUBEN_V1";
    private static final int MAX_ATTEMPTS = 512;
    private static CardDb cachedDb;
    private static CardEdition cachedEdition;
    private static Pools cachedPools;

    private TeslaBoosterCollation() {
    }

    static List<PaperCard> generate(SealedTemplate template, Collection<String> excludedNames) {
        if (!"TESL".equals(template.getEdition()) || !template.getSlots().equals(List.of(
                Pair.of("Common:!dfc", 9), Pair.of("Uncommon:!dfc", 3),
                Pair.of("RareMythic:!dfc", 1), Pair.of(BoosterSlots.DUAL_FACED_CARD, 1)))) {
            throw new IllegalArgumentException("Unsupported Tesla booster template: " + template);
        }
        StaticData data = StaticData.instance();
        return generate(pools(data.getCommonCards(), data.getEditions().get(template.getEdition())),
                MyRandom.getRandom(), excludedNames == null ? Set.of() : excludedNames);
    }

    private static synchronized Pools pools(CardDb db, CardEdition edition) {
        if (edition == null) {
            throw new IllegalArgumentException("Tesla collation requires a loaded TESL edition");
        }
        if (db != cachedDb || edition != cachedEdition) {
            List<PaperCard> cards = new ArrayList<>();
            for (CardEdition.EditionEntry entry : edition.getAllCardsInSet()) {
                PaperCard card = db.getCard(entry.name(), edition.getCode(), entry.collectorNumber());
                if (card == null || !edition.getCode().equals(card.getEdition())
                        || card.getRarity() != entry.rarity()) {
                    throw new IllegalStateException("Unloaded or mismatched TESL booster card: " + entry.name());
                }
                cards.add(card);
            }
            cachedPools = new Pools(cards);
            cachedDb = db;
            cachedEdition = edition;
        }
        return cachedPools;
    }

    static final class Pools {
        private final EnumMap<CardRarity, List<PaperCard>> ordinary = new EnumMap<>(CardRarity.class);
        private final EnumMap<CardRarity, List<PaperCard>> doubleFaced = new EnumMap<>(CardRarity.class);

        Pools(Collection<PaperCard> cards) {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (PaperCard card : cards) {
                if (card.getRules().getMainPart().getType().isBasicLand()) {
                    continue;
                }
                if (!names.add(card.getName())) {
                    throw new IllegalArgumentException("Duplicate TESL booster card: " + card.getName());
                }
                EnumMap<CardRarity, List<PaperCard>> pool = CardSplitType.DUAL_FACED_CARDS.contains(
                        card.getRules().getSplitType()) ? doubleFaced : ordinary;
                pool.computeIfAbsent(card.getRarity(), rarity -> new ArrayList<>()).add(card);
            }
            requireSize(ordinary, CardRarity.Common, 97);
            requireSize(ordinary, CardRarity.Uncommon, 65);
            requireSize(ordinary, CardRarity.Rare, 50);
            requireSize(ordinary, CardRarity.MythicRare, 15);
            requireSize(doubleFaced, CardRarity.Common, 4);
            requireSize(doubleFaced, CardRarity.Uncommon, 15);
            requireSize(doubleFaced, CardRarity.Rare, 3);
            if (names.size() != 249) {
                throw new IllegalStateException("Unexpected rarity in TESL booster pools");
            }
        }

        private static void requireSize(EnumMap<CardRarity, List<PaperCard>> pool, CardRarity rarity, int expected) {
            int count = pool.getOrDefault(rarity, List.of()).size();
            if (count != expected) {
                throw new IllegalStateException("Invalid TESL " + rarity + " pool: expected "
                        + expected + ", found " + count);
            }
        }
    }

    static List<PaperCard> generate(Pools pools, Random random, Collection<String> excludedNames) {
        List<PaperCard> commons = available(pools.ordinary.get(CardRarity.Common), excludedNames, 9);
        List<PaperCard> uncommons = available(pools.ordinary.get(CardRarity.Uncommon), excludedNames, 3);
        List<PaperCard> rares = available(pools.ordinary.get(CardRarity.Rare), excludedNames, 0);
        List<PaperCard> mythics = available(pools.ordinary.get(CardRarity.MythicRare), excludedNames, 0);
        int rareWeight = 2 * rares.size();
        int combinedWeight = rareWeight + mythics.size();
        if (combinedWeight == 0) {
            throw new IllegalStateException("No cards available for TESL Rare/Mythic slot");
        }

        List<PaperCard> commonDfc = available(pools.doubleFaced.get(CardRarity.Common), excludedNames, 1);
        List<PaperCard> uncommonDfc = available(pools.doubleFaced.get(CardRarity.Uncommon), excludedNames, 1);
        List<PaperCard> rareDfc = available(pools.doubleFaced.get(CardRarity.Rare), excludedNames, 1);
        int roll = random.nextInt(14);
        List<PaperCard> dfcs = roll < 10 ? commonDfc : roll < 13 ? uncommonDfc : rareDfc;
        PaperCard dfc = dfcs.get(random.nextInt(dfcs.size()));

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            sample(commons, 9, random);
            sample(uncommons, 3, random);
            List<PaperCard> commonSlot = commons.subList(0, 9);
            List<PaperCard> uncommonSlot = uncommons.subList(0, 3);
            if (validColors(commonSlot, uncommonSlot, dfc)) {
                List<PaperCard> pack = new ArrayList<>(14);
                pack.addAll(commonSlot);
                pack.addAll(uncommonSlot);
                int ticket = random.nextInt(combinedWeight);
                pack.add(ticket < rareWeight ? rares.get(ticket / 2) : mythics.get(ticket - rareWeight));
                pack.add(dfc);
                return pack;
            }
        }
        throw new IllegalStateException("No valid TESL pack found after " + MAX_ATTEMPTS
                + " attempts for " + dfc.getName() + " (available C/U: " + commons.size() + "/" + uncommons.size() + ")");
    }

    private static List<PaperCard> available(List<PaperCard> pool, Collection<String> excludedNames, int minimum) {
        List<PaperCard> cards = new ArrayList<>(pool);
        cards.removeIf(card -> excludedNames.contains(card.getName()));
        if (cards.size() < minimum) {
            throw new IllegalStateException("Insufficient TESL cards after exclusions for "
                    + pool.get(0).getRarity() + " pool: expected at least " + minimum + ", found " + cards.size());
        }
        return cards;
    }

    private static void sample(List<PaperCard> cards, int size, Random random) {
        for (int index = 0; index < size; index++) {
            Collections.swap(cards, index, index + random.nextInt(cards.size() - index));
        }
    }

    private static boolean validColors(List<PaperCard> commons, List<PaperCard> uncommons, PaperCard dfc) {
        int[] commonColors = new int[5];
        int[] uncommonColors = new int[5];
        boolean commonCreature = false;
        for (PaperCard card : commons) {
            countColors(card, commonColors);
            commonCreature |= card.getRules().getMainPart().getType().isCreature();
        }
        for (PaperCard card : uncommons) {
            countColors(card, uncommonColors);
        }
        if (dfc.getRarity() == CardRarity.Common) {
            countColors(dfc, commonColors);
            commonCreature |= dfc.getRules().getMainPart().getType().isCreature();
        } else if (dfc.getRarity() == CardRarity.Uncommon) {
            countColors(dfc, uncommonColors);
        }
        if (!commonCreature) {
            return false;
        }
        for (int i = 0; i < commonColors.length; i++) {
            if (commonColors[i] == 0 || commonColors[i] > 4 || uncommonColors[i] > 2) {
                return false;
            }
        }
        return true;
    }

    private static void countColors(PaperCard card, int[] counts) {
        byte colors = card.getRules().getMainPart().getColor().getColor();
        for (int i = 0; i < counts.length; i++) {
            counts[i] += (colors >> i) & 1;
        }
    }
}
