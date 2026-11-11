package forge.item.generation;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.testng.Assert.*;

public class TeslaBoosterCollationTest {
    private static final List<String> COLORS = List.of("W", "U", "B", "R", "G");

    @Test
    public void testPacksKeepTheSelectedDoubleFacedCardAcrossRetries() {
        var pools = new TeslaBoosterCollation.Pools(cards());
        var random = new Random(105);
        var hits = new EnumMap<CardRarity, Integer>(CardRarity.class);
        int packs = 5_000;
        for (int i = 0; i < packs; i++) {
            List<PaperCard> pack = TeslaBoosterCollation.generate(pools, random, Set.of("Common 0"));
            assertEquals(pack.size(), 14);
            assertEquals(new HashSet<>(pack.stream().map(PaperCard::getName).toList()).size(), 14);
            assertTrue(pack.stream().noneMatch(card -> card.getName().equals("Common 0")));
            assertTrue(pack.stream().noneMatch(card -> card.getRules().getType().isBasicLand()));
            assertTrue(pack.subList(0, 9).stream().allMatch(card -> card.getRarity() == CardRarity.Common));
            assertTrue(pack.subList(9, 12).stream().allMatch(card -> card.getRarity() == CardRarity.Uncommon));
            assertTrue(Set.of(CardRarity.Rare, CardRarity.MythicRare).contains(pack.get(12).getRarity()));
            assertTrue(pack.subList(0, 13).stream()
                    .noneMatch(card -> CardSplitType.DUAL_FACED_CARDS.contains(card.getRules().getSplitType())));
            PaperCard dfc = pack.get(13);
            assertEquals(dfc.getRules().getSplitType(), CardSplitType.Transform);
            hits.merge(dfc.getRarity(), 1, Integer::sum);
            assertTrue(pack.stream().anyMatch(card -> card.getRarity() == CardRarity.Common
                    && card.getRules().getMainPart().getType().isCreature()));
            for (int color = 0; color < COLORS.size(); color++) {
                int mask = 1 << color;
                long commons = countColor(pack, CardRarity.Common, mask);
                assertTrue(commons >= 1 && commons <= 4);
                assertTrue(countColor(pack, CardRarity.Uncommon, mask) <= 2);
            }
        }
        assertEquals((double) hits.get(CardRarity.Common), packs * 10.0 / 14, 160);
        assertEquals((double) hits.get(CardRarity.Uncommon), packs * 3.0 / 14, 160);
        assertEquals((double) hits.get(CardRarity.Rare), packs / 14.0, 120);
    }

    @Test
    public void rejectsIncompletePoolsAndImpossibleExclusions() {
        var cards = cards();
        assertThrows(IllegalStateException.class,
                () -> new TeslaBoosterCollation.Pools(cards.subList(1, cards.size())));
        var pools = new TeslaBoosterCollation.Pools(cards);
        List<String> whiteCommons = cards.stream().filter(card -> card.getRarity() == CardRarity.Common)
                .filter(card -> card.getRules().getSplitType() == CardSplitType.None)
                .filter(card -> (card.getRules().getMainPart().getColor().getColor() & 1) != 0)
                .map(PaperCard::getName).toList();
        assertThrows(IllegalStateException.class,
                () -> TeslaBoosterCollation.generate(pools, new Random(105), whiteCommons));
        assertEquals(TeslaBoosterCollation.generate(pools, new Random(105), Set.of()).size(), 14);
    }

    @Test
    public void dispatcherKeepsTheLegacyHandlerAndRoutesTeslaSeparately() {
        var unknown = new SealedTemplate("TEST", List.of(), "unknown");
        var legacy = expectThrows(IllegalArgumentException.class, () -> AnkheretBoosterCollation.generate(unknown));
        var routed = expectThrows(IllegalArgumentException.class, () -> BoosterGenerator.getBoosterPack(unknown));
        assertEquals(routed.getMessage(), legacy.getMessage());

        var tesla = new SealedTemplate("TESL", List.of(), TeslaBoosterCollation.PROFILE);
        var invalidTesla = expectThrows(IllegalArgumentException.class, () -> BoosterGenerator.getBoosterPack(tesla));
        assertTrue(invalidTesla.getMessage().startsWith("Unsupported Tesla booster template"));
    }

    private static long countColor(List<PaperCard> pack, CardRarity rarity, int mask) {
        return pack.stream().filter(card -> card.getRarity() == rarity)
                .filter(card -> (card.getRules().getMainPart().getColor().getColor() & mask) != 0).count();
    }

    private static List<PaperCard> cards() {
        List<PaperCard> result = new ArrayList<>();
        addCards(result, CardRarity.Common, 97, false);
        addCards(result, CardRarity.Uncommon, 65, false);
        addCards(result, CardRarity.Rare, 50, false);
        addCards(result, CardRarity.MythicRare, 15, false);
        addCards(result, CardRarity.Common, 4, true);
        addCards(result, CardRarity.Uncommon, 15, true);
        addCards(result, CardRarity.Rare, 3, true);
        result.add(new PaperCard(CardRules.fromScript(List.of("Name:Fixture Plains",
                "Types:Basic Land Plains", "ManaCost:no cost", "Oracle:")), "TEST", CardRarity.BasicLand));
        return result;
    }

    private static void addCards(List<PaperCard> cards, CardRarity rarity, int count, boolean dfc) {
        for (int i = 0; i < count; i++) {
            String name = (dfc ? "DFC " : "") + rarity.name() + " " + i;
            // Blue DFC commons make the exclusions test independent of the chosen DFC.
            String color = dfc ? "U" : COLORS.get(i % COLORS.size());
            List<String> script = new ArrayList<>(List.of("Name:" + name,
                    "Types:Creature Human", "PT:1/1", "ManaCost:" + color, "Oracle:"));
            if (dfc) {
                script.addAll(List.of("AlternateMode:DoubleFaced", "ALTERNATE", "Name:" + name + " Back",
                        "Types:Creature Human", "PT:2/2", "ManaCost:no cost", "Colors:red", "Oracle:"));
            }
            cards.add(new PaperCard(CardRules.fromScript(script), "TEST", rarity));
        }
    }
}
