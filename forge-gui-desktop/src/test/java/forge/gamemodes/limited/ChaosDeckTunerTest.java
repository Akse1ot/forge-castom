package forge.gamemodes.limited;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

import static org.testng.Assert.*;

public class ChaosDeckTunerTest extends AITest {
    @Test
    public void signetImprovesWhiteBlackButOffColorMoxDoesNotDisplaceGreenBlackSpells() throws IOException {
        try (Ratings files = new Ratings()) {
            Deck wb = seed("Plains", "Swamp", "Carrier Thrall", "Murder", "Herbal Poultice");
            PaperCard signet = card("Orzhov Signet");
            var improved = tune(wb, List.of(signet), MagicColor.WHITE | MagicColor.BLACK, files);
            assertTrue(improved.deck().getMain().contains(signet));
            assertTrue(improved.after().score() > improved.before().score());
            assertEquals(improved.deck().getMain().countAll(), 40);
            assertEquals(wb.getMain().countAll(), 40);
            assertFalse(wb.getMain().contains(signet));
            assertTrue(improved.deck().get(DeckSection.Sideboard).contains(card("Herbal Poultice")));
            assertEquals(improved.swaps(), tune(wb, List.of(signet), 5, files).swaps());

            Deck bg = seed("Forest", "Swamp", "Thornweald Archer", "Murder", "Night's Whisper");
            PaperCard mox = card("Mox Sapphire");
            var result = tune(bg, List.of(mox), MagicColor.BLACK | MagicColor.GREEN, files);
            assertFalse(result.deck().getMain().contains(mox));
            assertTrue(result.deck().get(DeckSection.Sideboard).contains(mox));
        }
    }

    @Test
    public void improvesTheRedBlueCurveAndRetainsTheBodyCount() throws IOException {
        try (Ratings files = new Ratings()) {
            Deck ur = seed("Island", "Mountain", "Stone Giant", "Incinerate", "Memory Lapse");
            var result = tune(ur, List.of(card("Jeskai Devotee"), card("Lantern Bearer"),
                    card("Midnight Mangler")), MagicColor.BLUE | MagicColor.RED, files);
            assertTrue(result.deck().getMain().contains(card("Jeskai Devotee")));
            assertTrue(result.deck().getMain().contains(card("Lantern Bearer")));
            assertTrue(result.after().early() > result.before().early());
            assertEquals(result.after().creatures(), result.before().creatures());
        }
    }

    @Test
    public void understandsActualManaWithoutRequiringTheKickerOrIdentity() {
        List<PaperCard> blueCards = new ArrayList<>(Collections.nCopies(9, card("Island")));
        blueCards.add(card("Jilt"));
        var blue = new ChaosManaPlan(blueCards, new IdentityHashMap<>());
        assertTrue(blue.castability(card("Jilt")) > .9);
        assertTrue(blue.castability(card("Talisman of Indulgence")) > .9);
        assertEquals(blue.castability(card("Oubliette")), 0.0);
        assertFalse(blue.contributes(ChaosManaPlan.readSource(card("Talisman of Indulgence"))));
        var wb = new ChaosManaPlan(seed("Plains", "Swamp", "Carrier Thrall", "Murder", "Herbal Poultice")
                .getMain().toFlatList(), new IdentityHashMap<>());
        assertEquals(ChaosManaPlan.readSource(card("Orzhov Signet")).turn(), 3);
        assertTrue(wb.contributes(ChaosManaPlan.readSource(card("Orzhov Signet"))));
        var wastes = new ChaosManaPlan(List.of(card("Wastes"), card("Wastes")), new IdentityHashMap<>());
        assertEquals(wastes.landSources().get(5).intValue(), 2);
        var noTarget = new ChaosManaPlan(List.of(card("Evolving Wilds")), new IdentityHashMap<>());
        assertEquals(noTarget.landSources().get(1).intValue(), 0);
        var oneTarget = new ChaosManaPlan(List.of(card("Evolving Wilds"), card("Swamp"), card("Island"),
                card("Island")), new IdentityHashMap<>());
        assertEquals(oneTarget.castability(card("Oubliette")), 0.0);
    }

    @Test
    public void prefersFullSignetCoverageAndKeepsLandsWithUnmodeledValue() throws IOException {
        try (Ratings files = new Ratings()) {
            Deck ur = seed("Island", "Mountain", "Goblin Piker", "Incinerate", "Memory Lapse");
            ur.getMain().remove(card("Memory Lapse"));
            ur.getMain().add(card("Herbal Poultice"));
            PaperCard gruul = card("Gruul Signet");
            PaperCard izzet = card("Izzet Signet");
            var mana = new ChaosManaPlan(ur.getMain().toFlatList(), new IdentityHashMap<>());
            var gruulSource = ChaosManaPlan.readSource(gruul);
            assertEquals(gruulSource.produces(), MagicColor.RED | MagicColor.GREEN);
            assertEquals(gruulSource.activation(), 1);
            assertEquals(gruulSource.output(), 2);
            assertEquals(gruulSource.turn(), 3);
            assertEquals(mana.usefulColors(gruulSource), 1);
            assertEquals(mana.usefulColors(ChaosManaPlan.readSource(izzet)), 2);
            var result = tune(ur, List.of(gruul, izzet), MagicColor.BLUE | MagicColor.RED, files);
            assertTrue(result.deck().getMain().contains(izzet), result.toString());
            assertTrue(result.swaps().stream().filter(swap -> swap.in().endsWith("Signet"))
                    .findFirst().orElseThrow().in().equals("Izzet Signet"), result.swaps().toString());

            for (String name : List.of("Myriad Landscape", "Dunes of the Dead")) {
                PaperCard land = card(name);
                assertTrue(ChaosManaPlan.hasUnmodeledLandAbility(land));
                Deck seed = seed("Island", "Mountain", "Stone Giant", "Incinerate", "Memory Lapse");
                seed.getMain().remove(card("Mountain"));
                seed.getMain().add(land);
                var preserved = tune(seed, List.of(), MagicColor.BLUE | MagicColor.RED, files);
                assertTrue(preserved.deck().getMain().contains(land), preserved.swaps().toString());
            }
        }
    }

    @Test
    public void forgeAiCanPayGruulSignetActivationWithGenericMana() {
        var game = initAndCreateGame();
        var player = game.getPlayers().get(1);
        var signet = addCard("Gruul Signet", player);
        var shock = addCardToZone("Shock", player, ZoneType.Hand).getFirstSpellAbility();
        shock.setActivatingPlayer(player);
        assertFalse(ComputerUtilMana.canPayManaCost(shock, player, 0, false));
        var island = addCard("Island", player);
        assertTrue(ComputerUtilMana.canPayManaCost(shock, player, 0, false));
        assertTrue(ComputerUtilMana.payManaCost(shock.getPayCosts(), player, shock, false));
        assertTrue(signet.isTapped());
        assertTrue(island.isTapped());
        assertEquals(player.getManaPool().getAmountOfColor(MagicColor.GREEN), 1);
    }

    @Test
    public void preservesCopiesAndSpecialSectionsAndRepairsRestrictedCards() throws IOException {
        try (Ratings files = new Ratings()) {
            Deck seed = seed("Plains", "Swamp", "Carrier Thrall", "Murder", "Herbal Poultice");
            PaperCard attraction = ChaosDraftEvaluatorTest.script("b/balloon_stand.txt", "UNF");
            seed.getOrCreate(DeckSection.Attractions).add(attraction, 3);
            seed.getMain().remove(card("Herbal Poultice"));
            seed.getMain().add(card("Oubliette"));
            List<PaperCard> stock = new ArrayList<>(seed.getMain().toFlatList());
            stock.addAll(Collections.nCopies(3, attraction));
            stock.addAll(Collections.nCopies(2, card("Orzhov Signet")));
            ChaosDeckTuner.repairSeedRestrictions(seed);
            ChaosDeckTuner.reconcile(seed, stock);
            var result = new ChaosDeckTuner(files.load(), ColorSet.fromMask(5)).improve(seed, stock).deck();
            assertEquals(result.getMain().countAll(), 40);
            assertEquals(result.get(DeckSection.Attractions).count(attraction), 3);
            assertFalse(result.getMain().contains(card("Oubliette")));
            assertTrue(result.get(DeckSection.Sideboard).contains(card("Oubliette")));
            assertEquals(result.getMain().count(card("Orzhov Signet"))
                    + result.get(DeckSection.Sideboard).count(card("Orzhov Signet")), 2);
        }
    }

    @Test(timeOut = 10000)
    public void seedHandlesEmptyAndColorlessPools() {
        for (List<PaperCard> cards : List.of(List.<PaperCard>of(), List.of(card("Mox Sapphire"), card("Black Vise")))) {
            Deck deck = new FullChaosBoosterDeckBuilder(new ArrayList<>(cards), new DeckColors()).buildDeck("M10");
            ChaosDeckTuner.reconcile(deck, cards);
            assertEquals(deck.getMain().countAll(), 40);
            assertTrue(deck.getMain().toFlatList().stream().anyMatch(card -> card.getName().equals("Wastes")));
        }
    }

    @Test
    public void reproducesMoxVersusBlackViseWithTheLoggedNativeOrder() throws IOException {
        try (Ratings files = new Ratings()) {
            PaperCard vise = card("Black Vise");
            PaperCard mox = card("Mox Sapphire");
            List<PaperCard> pack = new ArrayList<>();
            pack.add(vise);
            pack.addAll(Collections.nCopies(8, card("Stone Giant")));
            pack.add(mox);
            var evaluator = new ChaosDraftEvaluator(files.load(), Collections.nCopies(31, card("Murder")),
                    ColorSet.fromMask(MagicColor.BLUE | MagicColor.RED), new Deck(), true);
            var scores = evaluator.evaluatePack(pack, 2, true);
            assertTrue(scores.get(9).score() > scores.get(0).score());
            assertTrue(scores.get(0).strength().aiEvidence().contains("ChoosePlayerAi:MostCardsInHand"));
        }
    }

    private static ChaosDeckTuner.Result tune(Deck seed, List<PaperCard> side, int colors, Ratings ratings) {
        List<PaperCard> stock = new ArrayList<>(seed.getMain().toFlatList());
        stock.addAll(side);
        ChaosDeckTuner.reconcile(seed, stock);
        return new ChaosDeckTuner(ratings.load(), ColorSet.fromMask(colors)).improve(seed, stock);
    }

    private static Deck seed(String firstLand, String secondLand, String body, String spell, String other) {
        Deck seed = new Deck();
        seed.getMain().add(card(firstLand), 9);
        seed.getMain().add(card(secondLand), 9);
        seed.getMain().add(card(body), 12);
        seed.getMain().add(card(spell), 5);
        seed.getMain().add(card(other), 5);
        return seed;
    }

    static PaperCard card(String name) {
        PaperCard card = FModel.getMagicDb().getCommonCards().getCard(name);
        assertNotNull(card, name);
        return card;
    }

    private static final class Ratings implements AutoCloseable {
        private final Path folder = Files.createTempDirectory("forge-chaos-tuner");
        private final Path file = folder.resolve("ratings.tsv");

        Ratings() throws IOException {
            Files.writeString(file, "Carrier Thrall\t77.12\nMurder\t85\nHerbal Poultice\t15\n"
                    + "Orzhov Signet\t77.32\nThornweald Archer\t58.76\nNight's Whisper\t90\nMox Sapphire\t99.99\n"
                    + "Stone Giant\t5.49\nIncinerate\t90\nMemory Lapse\t75\nJeskai Devotee\t60.23\n"
                    + "Lantern Bearer\t63.76\nMidnight Mangler\t32.15\nBlack Vise\t96.47\n"
                    + "Gruul Signet\t85\nIzzet Signet\t85\nGoblin Piker\t65\n");
        }

        ChaosRatings load() {
            return new ChaosRatings(folder);
        }

        @Override
        public void close() throws IOException {
            Files.delete(file);
            Files.delete(folder);
        }
    }
}
