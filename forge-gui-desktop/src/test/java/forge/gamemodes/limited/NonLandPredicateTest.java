package forge.gamemodes.limited;

import forge.ai.AITest;
import forge.card.CardFacePredicates;
import forge.card.CardRulesPredicates;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.model.FModel;
import forge.util.MyRandom;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.*;

public class NonLandPredicateTest extends AITest {
    @DataProvider(name = "cardTypes")
    public Object[][] cardTypes() {
        return new Object[][]{
                {"Forest", false}, {"Evolving Wilds", false}, {"Grizzly Bears", true},
                {"Giant Growth", true}, {"Divination", true}, {"Great Furnace", false},
                {"Dryad Arbor", false}, {"Sol Ring", true},
                {"Bala Ged Recovery", true}, {"Kazandu Mammoth", true},
                {"Branchloft Pathway", false}, {"Westvale Abbey", false}
        };
    }

    @Test(dataProvider = "cardTypes")
    public void selectsNonlandsUsingTheAggregatedRulesType(String name, boolean nonland) {
        PaperCard card = card(name);
        assertEquals(CardRulesPredicates.IS_NON_LAND.test(card.getRules()), nonland, name);
        assertEquals(PaperCardPredicates.IS_NON_LAND.test(card), nonland, name);
        assertEquals(CardRulesPredicates.IS_NON_LAND.test(card.getRules()),
                !CardRulesPredicates.IS_LAND.test(card.getRules()));
    }

    @Test
    public void modalLandBackDoesNotReplaceThePrimaryRulesType() {
        PaperCard recovery = card("Bala Ged Recovery");
        assertTrue(CardFacePredicates.IS_NON_LAND.test(recovery.getMainFace()));
        assertFalse(CardFacePredicates.IS_NON_LAND.test(recovery.getRules().getOtherPart()));
        assertTrue(CardRulesPredicates.IS_NON_LAND.test(recovery.getRules()));
        PaperCard abbey = card("Westvale Abbey");
        assertFalse(CardRulesPredicates.IS_NON_LAND.test(abbey.getRules()));
        assertTrue(CardFacePredicates.IS_NON_LAND.test(abbey.getRules().getOtherPart()));
    }

    @Test
    public void ordinaryDraftAndSealedUseTheExtraSpellSlot() {
        Random original = MyRandom.getRandom();
        try {
            List<PaperCard> pool = printings("Giant Growth", 23);
            pool.add(card("Forest"));
            DeckColors colors = new DeckColors();
            colors.setColorsByList(List.of(MagicColor.GREEN));
            MyRandom.setRandom(new Random(1511));
            verifyLimited(new BoosterDeckBuilder(new ArrayList<>(pool), colors).buildDeck("M10"));
            MyRandom.setRandom(new Random(1511));
            verifyLimited(new SealedDeckBuilder(new ArrayList<>(pool)).buildDeck("M10"));
        } finally {
            MyRandom.setRandom(original);
        }
    }

    @Test
    public void limitedThirdColorAndRandomFillDoNotSelectLands() throws Exception {
        LimitedProbe third = new LimitedProbe(List.of(card("Forest"), card("Lightning Bolt")));
        third.addThirdColorCards(1);
        assertEquals(third.deckList, List.of(card("Lightning Bolt")));
        LimitedProbe random = new LimitedProbe(List.of(card("Mountain"), card("Giant Growth")));
        var fill = LimitedDeckBuilder.class.getDeclaredMethod("addRandomCards", int.class);
        fill.setAccessible(true);
        fill.invoke(random, 1);
        assertEquals(random.deckList, List.of(card("Giant Growth")));
    }

    @Test
    public void themedLowCurveThirdColorAndSideboardExcludeLands() {
        ThemedProbe low = new ThemedProbe(List.of(card("Forest"), card("Grizzly Bears")));
        low.addLowCMCCard();
        assertEquals(low.deckList, List.of(card("Grizzly Bears")));
        ThemedProbe third = new ThemedProbe(List.of(card("Forest"), card("Lightning Bolt")));
        third.addThirdColorCards(1);
        assertEquals(third.deckList, List.of(card("Lightning Bolt")));
        assertFalse(third.hasColor.test(card("Forest").getRules()));
        assertEquals(List.of(card("Forest"), card("Grizzly Bears")).stream()
                .filter(PaperCardPredicates.fromRules(CardRulesPredicates.IS_KEPT_IN_AI_DECKS
                        .and(CardRulesPredicates.IS_NON_LAND))).toList(), List.of(card("Grizzly Bears")));
    }

    @Test
    public void commanderDraftExtraSlotAlsoUsesASpell() {
        Random original = MyRandom.getRandom();
        try {
            List<PaperCard> pool = printings("Giant Growth", 36);
            PaperCard commander = card("Trostani, Selesnya's Voice");
            pool.add(commander);
            pool.add(card("Forest"));
            DeckColors colors = new DeckColors();
            colors.setColorsByList(List.of(MagicColor.GREEN));
            MyRandom.setRandom(new Random(199));
            Deck deck = new CommanderDraftDeckBuilder(pool, colors, commander, null).buildDeck("M10");
            assertEquals(deck.getOrCreate(DeckSection.Commander).countAll(), 1);
            assertEquals(deck.getMain().countAll(), 59);
            assertEquals(deck.getMain().toFlatList().stream().filter(PaperCardPredicates.IS_LAND).count(), 23L);
        } finally {
            MyRandom.setRandom(original);
        }
    }

    private static void verifyLimited(Deck deck) {
        assertEquals(deck.getMain().countAll(), 40);
        assertNull(DeckFormat.Limited.getDeckConformanceProblem(deck));
        assertEquals(deck.getMain().toFlatList().stream().filter(c -> c.getName().equals("Giant Growth")).count(), 23L);
        assertEquals(deck.getMain().toFlatList().stream().filter(PaperCardPredicates.IS_LAND).count(), 17L);
    }

    private static PaperCard card(String name) {
        PaperCard result = FModel.getMagicDb().getCommonCards().getCard(name);
        assertNotNull(result, name);
        return result;
    }

    private static List<PaperCard> printings(String name, int count) {
        // Distinct real printings avoid removeAll treating repeated identical objects as a playset.
        List<PaperCard> cards = FModel.getMagicDb().getCommonCards().getAllCards(name).stream()
                .distinct().limit(count).toList();
        assertEquals(cards.size(), count);
        return new ArrayList<>(cards);
    }

    private static class LimitedProbe extends LimitedDeckBuilder {
        LimitedProbe(List<PaperCard> cards) {
            super(new ArrayList<>(cards), new DeckColors());
            colors = ColorSet.fromMask(MagicColor.GREEN);
            rankedColorList = new ArrayList<>();
        }
    }

    private static class ThemedProbe extends CardThemedDeckBuilder {
        ThemedProbe(List<PaperCard> cards) {
            super(FModel.getMagicDb().getCommonCards(), DeckFormat.Constructed);
            aiPlayables = new ArrayList<>(cards);
            rankedColorList = new ArrayList<>(cards);
            colors = ColorSet.fromMask(MagicColor.GREEN);
        }
    }
}
