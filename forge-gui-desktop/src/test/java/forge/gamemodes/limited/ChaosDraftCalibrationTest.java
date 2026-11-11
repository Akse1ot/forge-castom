package forge.gamemodes.limited;

import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static forge.gamemodes.limited.ChaosDeckTunerTest.card;
import static org.testng.Assert.*;

public class ChaosDraftCalibrationTest extends AITest {
    private static final int RWG = MagicColor.RED | MagicColor.WHITE | MagicColor.GREEN;
    private static final int UR = MagicColor.BLUE | MagicColor.RED;
    private static final int UG = MagicColor.BLUE | MagicColor.GREEN;

    @Test
    public void recognizesManaPathsAndSeparatesUtilityFromExternalRating() throws IOException {
        ChaosRatings ratings = ratings(directory());
        List<PaperCard> candidates = List.of(card("Command Tower"), card("Path of Ancestry"),
                card("Temple of the False God"), card("Jeweled Amulet"), card("Krark-Clan Ironworks"),
                card("Forsaken Crossroads"), card("Harvester Druid"), card("Fertile Ground"),
                card("Terramorphic Expanse"), card("Llanowar Elves"), card("Sol Ring"), card("Grizzly Bears"));
        var late = new ArrayList<>(Collections.nCopies(10, card("Grizzly Bears")));
        late.addAll(Collections.nCopies(6, card("Stone Giant")));
        var early = evaluate(ratings, List.of(), 0, candidates, false);
        var developed = evaluate(ratings, late, RWG, candidates, false);
        var evaluator = new ChaosDraftEvaluator(ratings, List.of(), ColorSet.C, new Deck(), false);
        var unusable = evaluator.preview(candidates.subList(0, 2));
        for (PaperCard land : candidates.subList(0, 2)) {
            assertEquals(unusable.get(land).value(), 1.0, land.getName());
            assertFalse(unusable.get(land).recommended(), land.getName());
            assertFalse(ChaosAiEvidence.deckAllowed(land, late));
        }
        assertEquals(ChaosManaPlan.readSource(candidates.get(2)).turn(), 5);
        var temple = ChaosManaPlan.readSource(candidates.get(2));
        assertEquals(new ChaosManaPlan(List.of(card("Island")), new java.util.IdentityHashMap<>()).resolve(temple).output(), 0);
        assertEquals(new ChaosManaPlan(Collections.nCopies(4, card("Island")), new java.util.IdentityHashMap<>())
                .resolve(temple).output(), 2);
        var reflected = ChaosManaPlan.readSource(candidates.get(6));
        assertEquals(new ChaosManaPlan(List.of(card("Forest")), new java.util.IdentityHashMap<>())
                .resolve(reflected).produces(), (int) MagicColor.GREEN);
        assertTrue(developed.get(2).poolBase() > early.get(2).poolBase());
        assertEquals(early.get(3).poolBase(), 22.0);
        assertEquals(early.get(4).poolBase(), 18.0);
        var fodder = evaluate(ratings, Collections.nCopies(5, card("Sol Ring")), 0, candidates, false);
        assertTrue(fodder.get(4).poolBase() > early.get(4).poolBase());
        assertTrue(developed.get(5).poolBase() >= 65 && developed.get(5).poolBase() < 90);
        assertTrue(developed.get(6).poolBase() > early.get(6).poolBase());
        assertTrue(developed.get(7).poolBase() > early.get(7).poolBase());
        assertTrue(developed.get(8).poolBase() >= 55 && developed.get(8).poolBase() < 80);
        assertTrue(developed.get(9).poolBase() >= 85 && developed.get(9).poolBase() > early.get(9).poolBase());
        assertEquals(developed.get(10).poolBase(), 98.0);
        assertEquals(developed.get(11).poolBase(), 65.0);
        var uncertain = evaluate(ratings, late, RWG, List.of(card("Reflecting Pool"), card("Maze of Ith")), false);
        assertEquals(uncertain.get(0).support(), ChaosCardProfile.Support.UNKNOWN);
        assertTrue(uncertain.get(0).poolBase() > 0 && uncertain.get(0).poolBase() < 90);
        assertEquals(uncertain.get(1).poolBase(), 91.0);
        for (int i = 0; i < candidates.size(); i++) {
            System.out.printf(java.util.Locale.ROOT, "MANA %s %.2f %.2f %.2f%n", candidates.get(i).getName(),
                    early.get(i).rating(), early.get(i).score(), developed.get(i).score());
        }
    }

    @Test
    public void trimsOnlyAnOverlandedDeckWithPayableAcceleration() throws IOException {
        var ratings = ratings(directory());
        for (int lands : List.of(18, 19, 20)) {
            var deck = new Deck();
            deck.getMain().add(card("Island"), lands / 2);
            deck.getMain().add(card("Mountain"), lands - lands / 2);
            deck.getMain().add(card("Izzet Signet"));
            deck.getMain().add(card("Talisman of Indulgence"));
            deck.getMain().add(card("Stone Giant"), 40 - lands - 2);
            deck.getOrCreate(DeckSection.Sideboard).add(card("Incinerate"));
            var stock = deck.getMain().toFlatList();
            stock.add(card("Incinerate"));
            var result = new ChaosDeckTuner(ratings, ColorSet.fromMask(UR)).improve(deck, stock);
            assertEquals(result.deck().getMain().countAll(), 40);
            assertEquals(result.after().lands(), Math.min(lands, 19), result.toString());
        }
    }

    @Test
    public void conditionalManaRequiresTheActualProvidersAndKeepsItsGuaranteedOutput() throws IOException {
        Path root = directory();
        ChaosRatings ratings = ratings(root);
        PaperCard mine = card("Urza's Mine");
        PaperCard tower = card("Urza's Tower");
        PaperCard plant = card("Urza's Power Plant");
        PaperCard artifact = card("Sol Ring");
        PaperCard bears = card("Grizzly Bears");
        List<PaperCard> pool = new ArrayList<>(Collections.nCopies(16, bears));
        pool.add(artifact);
        var unsupported = evaluate(ratings, pool, MagicColor.GREEN, List.of(mine, bears), false).get(0);
        assertEquals(unsupported.strength().value(), 26.0);
        assertEquals(unsupported.strength().standalone(), 26.0);
        assertEquals(unsupported.support(), ChaosCardProfile.Support.UNAVAILABLE);
        assertEquals(unsupported.landBonus(), 0.0);
        assertFalse(ChaosAiEvidence.deckAllowed(mine, pool));
        assertEquals(ChaosManaPlan.readSource(mine).output(), 1);
        pool.add(tower);
        assertFalse(ChaosAiEvidence.deckAllowed(mine, pool));
        pool.add(plant);
        assertTrue(ChaosAiEvidence.deckAllowed(mine, pool));
        var supported = evaluate(ratings, pool, MagicColor.GREEN, List.of(mine, bears), true).get(0);
        assertTrue(supported.selectable());
        assertEquals(supported.poolBase(), 78.8);
        assertTrue(supported.score() > unsupported.score() + 45);
        PaperCard coffers = card("Cabal Coffers");
        assertEquals(evaluate(ratings, List.of(), MagicColor.BLACK, List.of(coffers), false).get(0).poolBase(), 0.0);
        assertTrue(evaluate(ratings, Collections.nCopies(5, card("Swamp")), MagicColor.BLACK,
                List.of(coffers), false).get(0).poolBase() >= 40);
        PaperCard priest = card("Priest of Titania");
        assertEquals(ChaosManaPlan.readSource(priest).output(), 1);
        var soloPriest = evaluate(ratings, List.of(), MagicColor.GREEN, List.of(priest), false).get(0);
        assertTrue(soloPriest.poolBase() >= 35);
        assertTrue(evaluate(ratings, Collections.nCopies(5, card("Llanowar Elves")), MagicColor.GREEN,
                List.of(priest), false).get(0).poolBase() > soloPriest.poolBase());
        assertEquals(ChaosManaPlan.readSource(artifact).output(), 2);
        assertEquals(evaluate(ratings, pool, MagicColor.GREEN, List.of(artifact), false).get(0).poolBase(), 98.0);
        System.out.printf("CALIBRATION Mine %.1f / full %.1f%n", unsupported.score(), supported.score());
    }

    @Test
    public void checksLateColorsAndValuesNarrowEffectsByTheirPlan() throws IOException {
        ChaosRatings ratings = ratings(directory());
        List<PaperCard> pool = new ArrayList<>(Collections.nCopies(12, card("Grizzly Bears")));
        pool.addAll(Collections.nCopies(10, card("Incinerate")));
        pool.addAll(Collections.nCopies(10, card("Savannah Lions")));
        List<PaperCard> pack = List.of(card("Sunken Hollow"), card("Rites of Spring"), card("Markov Waltzer"),
                card("Vanquish the Weak"), card("Charcoal Diamond"), card("Grizzly Bears"));
        var late = evaluate(ratings, pool, RWG, pack, false);
        var early = evaluate(ratings, pool.subList(0, 2), RWG, pack, false);
        assertEquals(late.get(0).landBonus(), 0.0);
        assertTrue(late.get(0).score() < 35);
        assertTrue(late.get(1).score() < late.get(2).score());
        assertTrue(late.get(3).colorPenalty() > 45);
        assertTrue(early.get(3).score() - late.get(3).score() > 35);
        assertEquals(late.get(5).poolBase(), 65.0);
        assertEquals(ChaosDraftEvaluator.humanColors(pool).countColors(), 3);
        assertEquals(ChaosDraftEvaluator.inferColors(pool.subList(0, 8), ColorSet.fromMask(UR), 2).getColor(), MagicColor.GREEN);

        List<PaperCard> splashPool = new ArrayList<>(pool);
        splashPool.add(card("Temple of Silence"));
        splashPool.add(card("Godless Shrine"));
        var splash = evaluate(ratings, splashPool, RWG, pack, false).get(3);
        assertEquals(splash.fitReason(), "supported_splash");
        assertTrue(splash.score() - late.get(3).score() > 25);

        PaperCard freeze = card("Brain Freeze");
        PaperCard ritual = card("Dark Ritual");
        PaperCard diamond = card("Charcoal Diamond");
        var narrow = evaluate(ratings, pool, UG, List.of(freeze), false).get(0);
        var mill = evaluate(ratings, List.of(card("Mind Sculpt"), card("Tome Scour"), card("Dream Twist")),
                UG, List.of(freeze), false).get(0);
        var offColor = evaluate(ratings, pool, UR, List.of(diamond), false).get(0);
        var temporary = evaluate(ratings, pool, MagicColor.BLACK | MagicColor.GREEN, List.of(ritual), false).get(0);
        assertTrue(narrow.poolBase() < 30);
        assertTrue(mill.poolBase() - narrow.poolBase() > 20);
        assertTrue(offColor.poolBase() <= 40);
        assertTrue(temporary.poolBase() < 45);
        var ritualPayoffs = evaluate(ratings, Collections.nCopies(6, card("Blightwidow")),
                MagicColor.BLACK | MagicColor.GREEN, List.of(ritual), false).get(0);
        assertTrue(ritualPayoffs.poolBase() > temporary.poolBase() + 5);
        late.forEach(c -> System.out.printf("CALIBRATION %s raw=%s adjusted=%.1f score=%.1f%n",
                c.card().getName(), c.rating(), c.poolBase(), c.score()));
        System.out.printf("CALIBRATION early Vanquish %.1f / splash %.1f / mill %.1f with plan %.1f / diamond %.1f / ritual %.1f with payoffs %.1f%n",
                early.get(3).score(), splash.score(), narrow.score(), mill.score(), offColor.score(),
                temporary.score(), ritualPayoffs.score());
    }

    @Test
    public void writesOnlyTheVisibleSnapshotIncludingTheNonRecommendedPick() throws IOException {
        Path root = directory();
        PaperCard bears = card("Grizzly Bears");
        PaperCard ring = card("Sol Ring");
        var evaluator = new ChaosDraftEvaluator(ratings(root), List.of(bears), ColorSet.fromMask(MagicColor.GREEN),
                new Deck(), false);
        var snapshot = evaluator.preview(List.of(bears, ring), List.of(bears, ring, bears, PaperCard.FAKE_CARD));
        var report = new ChaosDraftReport(root);
        report.humanPick(ChaosDraftEvaluator.PackEvaluation.empty(), ring, 2, 1);
        report.humanPick(snapshot, card("Island"), 2, 1);
        report.humanPick(snapshot, bears, 2, 2);
        try (var files = Files.list(root)) {
            var journal = files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow();
            var lines = Files.readAllLines(journal);
            assertEquals(lines.size(), 1);
            var row = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            assertEquals(row.get("event").getAsString(), "human_pick");
            assertEquals(row.get("pack").getAsInt(), 2);
            assertEquals(row.get("pick").getAsInt(), 2);
            assertEquals(row.getAsJsonObject("selected").get("name").getAsString(), "Grizzly Bears");
            assertEquals(row.getAsJsonObject("recommended").get("name").getAsString(), "Sol Ring");
            assertEquals(row.getAsJsonArray("candidates").size(), 3);
            assertEquals(row.getAsJsonArray("candidates").get(0).getAsJsonObject().get("score").getAsDouble(),
                    snapshot.get(bears).value());
            System.out.println("CALIBRATION sample " + journal.toAbsolutePath());
        }
    }

    private static List<ChaosDraftEvaluator.Candidate> evaluate(ChaosRatings ratings, List<PaperCard> pool,
                                                                int colors, List<PaperCard> pack, boolean forAi) {
        return new ChaosDraftEvaluator(ratings, pool, ColorSet.fromMask(colors), new Deck(), forAi)
                .evaluatePack(pack, 2, forAi);
    }

    private static Path directory() throws IOException {
        return Files.createTempDirectory(Path.of("target"), "chaos-calibration-");
    }

    private static ChaosRatings ratings(Path root) throws IOException {
        Files.writeString(root.resolve("ratings.tsv"), "Urza's Mine\t78.8\nCabal Coffers\t90\n"
                + "Sunken Hollow\t92.46\nRites of Spring\t57.47\nMarkov Waltzer\t34.8\nVanquish the Weak\t82.99\n"
                + "Charcoal Diamond\t89.17\nBrain Freeze\t99.66\nDark Ritual\t99.24\nGrizzly Bears\t65\nSol Ring\t98\n"
                + "Priest of Titania\t93\nCommand Tower\t99\nPath of Ancestry\t99\nTemple of the False God\t72\n"
                + "Jeweled Amulet\t99\nKrark-Clan Ironworks\t99\nForsaken Crossroads\t98\nHarvester Druid\t93\n"
                + "Fertile Ground\t95\nTerramorphic Expanse\t98\nLlanowar Elves\t98\nIncinerate\t93\n"
                + "Stone Giant\t50\nIzzet Signet\t82\nTalisman of Indulgence\t80\n"
                + "Reflecting Pool\t98\nMaze of Ith\t91\n");
        return new ChaosRatings(root);
    }
}
