package forge.gamemodes.limited;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.limited.ChaosCardProfile.Support;
import forge.gamemodes.limited.ChaosDraftEvaluator.PackEvaluation;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.*;

public class ChaosDraftEvaluatorTest extends AITest {
    @Test
    public void replaysEightArchivedUnratedPicksWithoutChangingTheirScores() throws IOException {
        JsonObject fixtures = unratedFixtures();
        try (RatingFiles files = new RatingFiles()) {
            ChaosRatings ratings = fixtureRatings(files, fixtures);
            assertEquals(fixtures.getAsJsonArray("fixtures").size(), 8);
            for (var item : fixtures.getAsJsonArray("fixtures")) {
                JsonObject fixture = item.getAsJsonObject();
                List<PaperCard> pack = fixtureCards(fixture.getAsJsonArray("ranked"));
                List<PaperCard> pool = fixtureCards(fixture.getAsJsonArray("pool"));
                DeckColors colors = colors(fixture.get("colors").getAsInt());
                var evaluator = new ChaosDraftEvaluator(ratings, pool, colors.getChosenColors(), new Deck(), true);
                var before = evaluator.evaluatePack(pack, 2, true);
                assertEquals(before.get(0).exclusion(), "unrated");
                assertNull(before.get(0).strength().value());
                assertEquals(before.get(0).support(), Support.UNKNOWN);
                assertEquals(before.get(0).score(), Double.NEGATIVE_INFINITY);
                PaperCard expected = pack.stream().filter(card -> card.getName().equals(
                        fixture.get("expected").getAsString())).findFirst().orElseThrow();
                for (int repeat = 0; repeat < 3; repeat++) {
                    assertSame(choose(pack, pool, colors, ratings), expected, "case " + fixture.get("id"));
                }
                assertEquals(evaluator.evaluatePack(pack, 2, true), before);
                assertEquals(colors.getChosenColors().getColor(), fixture.get("colors").getAsInt());
            }
        }
    }

    @Test
    public void usesTheCurrentPropheticPrismRestrictionWithoutRestoringTheArchive() throws IOException {
        JsonObject fixtures = unratedFixtures();
        try (RatingFiles files = new RatingFiles()) {
            ChaosRatings ratings = fixtureRatings(files, fixtures);
            JsonObject fixture = fixtures.getAsJsonObject("prismCase");
            List<PaperCard> pack = fixtureCards(fixture.getAsJsonArray("ranked"));
            List<PaperCard> pool = fixtureCards(fixture.getAsJsonArray("pool"));
            var colors = colors(fixture.get("colors").getAsInt());
            var candidates = new ChaosDraftEvaluator(ratings, pool, colors.getChosenColors(), new Deck(), true)
                    .evaluatePack(pack, 2, true);
            var prism = candidates.stream().filter(c -> c.card().getName().equals("Prophetic Prism"))
                    .findFirst().orElseThrow();
            assertTrue(prism.selectable());
            assertEquals(prism.score(), 89.81, .000001);
            assertSame(choose(pack, pool, colors, ratings), prism.card());
        }
    }

    @Test
    public void keepsNativeOnTiesAndUsesAnExactRatedPrintingWithoutNativeEvidence() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            Files.writeString(files.directory.resolve("ratings.tsv"), "Shredding Winds\t39.5\nGrizzly Bears\t65\n");
            var ratings = files.load();
            PaperCard orb = FModel.getMagicDb().getCommonCards().getCard("Sunlight Orb", "CNVL");
            PaperCard winds = FModel.getMagicDb().getCommonCards().getCard("Shredding Winds", "THS");
            assertEquals(CardRanker.getRawScore(orb), 39.0);
            var tie = new ChaosDraftEvaluator(ratings, List.of(), ColorSet.fromMask(MagicColor.WHITE | MagicColor.GREEN),
                    new Deck(), true).evaluatePack(List.of(orb, winds), 2, true);
            assertEquals(tie.get(0).exclusion(), "unrated");
            assertEquals(tie.get(1).score(), 43.0);
            assertEquals(CardRanker.getRawScore(orb) + tie.get(0).nativeBonus(), tie.get(1).score());
            assertSame(choose(List.of(orb, winds), List.of(), colors(MagicColor.WHITE | MagicColor.GREEN), ratings), orb);

            PaperCard unknown = custom("Name:Unrated Priority Probe", "ManaCost:G", "Types:Instant",
                    "A:SP$ Pump | ValidTgts$ Creature | NumAtt$ 2 | NumDef$ 2");
            PaperCard bears = FModel.getMagicDb().getCommonCards().getCard("Grizzly Bears", "M10");
            PaperCard otherPrinting = FModel.getMagicDb().getCommonCards().getCard("Grizzly Bears", "10E");
            assertNotSame(bears, otherPrinting);
            assertTrue(CardRanker.getRawScore(unknown) < 0);
            for (int repeat = 0; repeat < 3; repeat++) {
                assertSame(choose(List.of(unknown, bears, otherPrinting), List.of(), colors(MagicColor.GREEN), ratings), bears);
            }
        }
    }

    @Test
    public void preservesFallbackWithNoRatedCandidateAndKeepsCustomStandaloneSelectable() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard first = custom("Name:First Unknown Trick", "ManaCost:G", "Types:Instant",
                    "A:SP$ Pump | ValidTgts$ Creature | NumAtt$ 1 | NumDef$ 1");
            PaperCard second = custom("Name:Second Unknown Trick", "ManaCost:G", "Types:Instant",
                    "A:SP$ Pump | ValidTgts$ Creature | NumAtt$ 2 | NumDef$ 2");
            var ratings = files.load();
            assertSame(choose(List.of(first, second), List.of(), colors(MagicColor.GREEN), ratings), first);
            assertSame(choose(List.of(first), List.of(), colors(MagicColor.GREEN), ratings), first);
            assertNull(choose(List.of(), List.of(), colors(MagicColor.GREEN), ratings));
            PaperCard offColor = custom("Name:Unknown White Trick", "ManaCost:W", "Types:Instant",
                    "A:SP$ Pump | ValidTgts$ Creature | NumAtt$ 2 | NumDef$ 2");
            PaperCard forest = FModel.getMagicDb().getCommonCards().getCard("Forest", "M10");
            var pool = Collections.nCopies(12, forest);
            assertSame(choose(List.of(offColor, forest), pool, colors(MagicColor.BLUE | MagicColor.GREEN), ratings), offColor);

            Files.writeString(files.directory.resolve("ratings.tsv"), "Weldfast Wingsmith\t1.69\n");
            ratings = files.load();
            PaperCard harbinger = FModel.getMagicDb().getCommonCards().getCard("Abyssal Harbinger", "IAMR");
            PaperCard wingsmith = FModel.getMagicDb().getCommonCards().getCard("Weldfast Wingsmith", "KLD");
            var strength = new ChaosDraftEvaluator(ratings, List.of(), ColorSet.C, new Deck(), true).strength(harbinger);
            assertNull(strength.external());
            assertEquals(strength.standalone(), 30.0);
            assertSame(choose(List.of(harbinger, wingsmith), List.of(), colors(MagicColor.BLUE | MagicColor.BLACK), ratings), harbinger);
        }
    }

    @Test
    public void doesNotLetUnratedPriorityBypassCandidateExclusions() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard bears = FModel.getMagicDb().getCommonCards().getCard("Grizzly Bears", "M10");
            PaperCard offColor = custom("Name:Excluded White Trick", "ManaCost:W", "Types:Instant",
                    "A:SP$ Pump | ValidTgts$ Creature | NumAtt$ 2 | NumDef$ 2");
            PaperCard blocked = custom("Name:Excluded Ai Trick", "ManaCost:G", "Types:Instant",
                    "A:SP$ Pump | ValidTgts$ Creature | NumAtt$ 2 | NumDef$ 2", "AI:RemoveDeck:All");
            PaperCard unsupported = new PaperCard(CardRules.getUnsupportedCardNamed("Unsupported Priority Probe"), "M10", CardRarity.Common);
            PaperCard forest = FModel.getMagicDb().getCommonCards().getCard("Forest", "M10");
            var excludedCandidates = new ChaosDraftEvaluator(files.load(), Collections.nCopies(12, bears),
                    ColorSet.fromMask(MagicColor.BLUE | MagicColor.GREEN), new Deck(), true)
                    .evaluatePack(List.of(offColor, blocked, unsupported, forest, bears), 2, true);
            assertEquals(excludedCandidates.get(0).exclusion(), "color_cap");
            assertEquals(excludedCandidates.get(1).exclusion(), "native_ai_deck_restriction");
            assertTrue(excludedCandidates.stream().noneMatch(candidate -> candidate.card() == unsupported));
            assertEquals(excludedCandidates.get(2).exclusion(), "free_basic_land");
            for (PaperCard excluded : List.of(offColor, blocked, unsupported, forest)) {
                assertSame(choose(List.of(excluded, bears), Collections.nCopies(12, bears),
                        colors(MagicColor.BLUE | MagicColor.GREEN), files.load()), bears);
            }
        }
    }

    @Test
    public void preservesDisabledAndOtherLimitedEntryPoints() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard unknown = custom("Name:Disabled Unrated Probe", "ManaCost:G", "Types:Instant",
                    "A:SP$ Pump | ValidTgts$ Creature | NumAtt$ 2 | NumDef$ 2");
            PaperCard bears = FModel.getMagicDb().getCommonCards().getCard("Grizzly Bears", "M10");
            var pack = List.of(unknown, bears);
            var colors = colors(MagicColor.GREEN);
            assertSame(FullChaosDraftPicker.choose(pack, List.of(), colors, 1,
                    new BoosterDraft(LimitedPoolType.Full), new Deck(), files.load(), false), unknown);
            for (LimitedPoolType type : List.of(LimitedPoolType.Block, LimitedPoolType.FantasyBlock, LimitedPoolType.Custom)) {
                assertSame(FullChaosDraftPicker.choose(pack, List.of(), colors, 1,
                        new BoosterDraft(type), new Deck(), files.load(), true), unknown);
            }
            var sealed = new SealedDeckBuilder(new ArrayList<>(Collections.nCopies(23, bears))).buildDeck("M10");
            assertEquals(sealed.getMain().countAll(), 40);
            assertNull(forge.game.GameType.Sealed.getDeckFormat().getDeckConformanceProblem(sealed));
        }
    }

    private static PaperCard choose(List<PaperCard> pack, List<PaperCard> pool, DeckColors colors, ChaosRatings ratings) {
        return FullChaosDraftPicker.choose(pack, pool, colors, 1, new BoosterDraft(LimitedPoolType.Full), new Deck(), ratings, true);
    }

    private static DeckColors colors(int mask) {
        DeckColors colors = new DeckColors();
        colors.setColorsByList(List.of((byte) mask));
        return colors;
    }

    private static JsonObject unratedFixtures() throws IOException {
        try (var stream = ChaosDraftEvaluatorTest.class.getResourceAsStream("unrated-picks.json")) {
            if (stream == null) {
                throw new IOException("Missing unrated-picks.json");
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static ChaosRatings fixtureRatings(RatingFiles files, JsonObject fixtures) throws IOException {
        StringBuilder table = new StringBuilder();
        fixtures.getAsJsonObject("ratings").entrySet().forEach(entry ->
                table.append(entry.getKey()).append('\t').append(entry.getValue().getAsDouble()).append('\n'));
        Files.writeString(files.directory.resolve("ratings.tsv"), table);
        return files.load();
    }

    private static List<PaperCard> fixtureCards(JsonArray rows) {
        List<PaperCard> cards = new ArrayList<>();
        for (var row : rows) {
            var fields = row.getAsJsonArray();
            PaperCard card = FModel.getMagicDb().getCommonCards().getCard(fields.get(0).getAsString(),
                    fields.get(1).getAsString(), fields.get(2).getAsString());
            assertNotNull(card, row.toString());
            assertEquals(card.getName(), fields.get(0).getAsString());
            assertEquals(card.getEdition(), fields.get(1).getAsString());
            assertEquals(card.getCollectorNumber(), fields.get(2).getAsString());
            cards.add(card);
        }
        return cards;
    }

    @Test
    public void evaluatesOpeningAndVisitingSeparately() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard cutter = script("l/line_cutter.txt", "UNF");
            PaperCard coming = script("c/coming_attraction.txt", "UNF");
            PaperCard balloon = script("b/balloon_stand.txt", "UNF");
            Deck deck = new Deck();
            deck.getOrCreate(DeckSection.Sideboard).add(balloon, 3);
            var empty = evaluator(files, deck, List.of(coming));
            assertEquals(empty.strength(cutter).support(), Support.UNAVAILABLE);
            assertEquals(empty.strength(cutter).value(), 38.0);
            assertEquals(empty.strength(coming).value(), 48.0);

            deck.getOrCreate(DeckSection.Attractions).add(balloon, 2);
            assertEquals(evaluator(files, deck, List.of(coming)).strength(cutter).support(), Support.UNAVAILABLE);
            deck.getOrCreate(DeckSection.Attractions).add(balloon);
            var noOpener = evaluator(files, deck, List.of());
            assertEquals(noOpener.strength(cutter).support(), Support.UNAVAILABLE);
            assertEquals(noOpener.strength(coming).support(), Support.AVAILABLE);
            var supported = evaluator(files, deck, List.of(coming));
            assertEquals(supported.strength(cutter).support(), Support.AVAILABLE);
            assertEquals(supported.strength(cutter).value(), 70.71);
        }
    }

    @Test
    public void checksContraptionsAndSelfFundedStickers() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard rigger = script("w/wrench_rigger.txt", "UST");
            PaperCard work = script("w/work_a_double.txt", "UST");
            PaperCard stiltstrider = script("s/stiltstrider.txt", "UNF");
            Deck deck = new Deck();
            var empty = evaluator(files, deck, List.of());
            assertEquals(empty.strength(rigger).support(), Support.UNAVAILABLE);
            assertEquals(empty.strength(work).value(), 0.0);
            assertEquals(empty.strength(stiltstrider).support(), Support.UNAVAILABLE);
            assertTrue(empty.strength(stiltstrider).value() > 0);
            deck.getOrCreate(DeckSection.Contraptions).add(script("w/widget_contraption.txt", "UST"));
            deck.getOrCreate(DeckSection.Stickers).add(script("e/eldrazi_guacamole_tightrope.txt", "UNF"));
            var supported = evaluator(files, deck, List.of());
            assertEquals(supported.strength(rigger).support(), Support.AVAILABLE);
            assertEquals(supported.strength(work).value(), 66.0);
            assertEquals(supported.strength(stiltstrider).support(), Support.AVAILABLE);
            assertTrue(supported.strength(stiltstrider).value() > empty.strength(stiltstrider).value());
        }
    }

    @Test
    public void followsActualProvidersForStaticAbilitiesAndReplacementEffects() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard winner = script("b/big_winner.txt", "UNF");
            PaperCard boss = script("s/steamflogger_boss.txt", "FUT");
            PaperCard stiltstrider = script("s/stiltstrider.txt", "UNF");
            PaperCard rigger = script("w/wrench_rigger.txt", "UST");
            Deck deck = new Deck();
            var empty = evaluator(files, deck, List.of());
            assertEquals(empty.strength(winner).support(), Support.UNAVAILABLE);
            assertEquals(empty.strength(boss).support(), Support.UNAVAILABLE);
            deck.getOrCreate(DeckSection.Stickers).add(script("e/eldrazi_guacamole_tightrope.txt", "UNF"));
            deck.getOrCreate(DeckSection.Contraptions).add(script("w/widget_contraption.txt", "UST"), 2);
            assertEquals(evaluator(files, deck, List.of()).strength(winner).support(), Support.UNAVAILABLE);
            assertTrue(evaluator(files, deck, List.of(rigger)).strength(boss).availableContribution() > 5);
            var supported = evaluator(files, deck, List.of(stiltstrider, rigger));
            assertEquals(supported.strength(winner).support(), Support.AVAILABLE);
            assertEquals(supported.strength(boss).support(), Support.AVAILABLE);
            assertTrue(supported.strength(winner).value() > empty.strength(winner).value());
            assertTrue(supported.strength(boss).value() > empty.strength(boss).value());
        }
    }

    @Test
    public void recognizesGenericArtifactPayoffsAndPotentialTokenSupply() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard artifact = script("m/mox_sapphire.txt", "LEA");
            PaperCard treasure = script("s/strike_it_rich.txt", "MH2");
            for (String path : List.of("f/foundry_screecher.txt", "a/aeronaut_tinkerer.txt")) {
                PaperCard payoff = script(path, "KLD");
                var empty = evaluator(files, new Deck(), List.of()).strength(payoff);
                var supported = evaluator(files, new Deck(), List.of(artifact)).strength(payoff);
                assertEquals(empty.support(), Support.UNAVAILABLE);
                assertEquals(supported.support(), Support.AVAILABLE);
                assertTrue(supported.value() > empty.value());
                assertEquals(evaluator(files, new Deck(), List.of(treasure)).strength(payoff).support(),
                        Support.POTENTIAL);
            }
            PaperCard disentomb = script("d/disentomb.txt", "M10");
            assertEquals(evaluator(files, new Deck(), List.of(treasure)).strength(disentomb).support(),
                    Support.UNAVAILABLE);
            assertEquals(evaluator(files, new Deck(), List.of(script("g/grizzly_bears.txt", "M10")))
                    .strength(disentomb).support(), Support.POTENTIAL);
        }
    }

    @Test
    public void separatesAiVetoFromMechanicalAvailabilityAndDiceUtility() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard worst = script("w/worst_fears.txt", "JOU");
            PaperCard coming = script("c/coming_attraction.txt", "UNF");
            PaperCard cutter = script("l/line_cutter.txt", "UNF");
            PaperCard dice = script("s/steel_squirrel.txt", "UST");
            Deck deck = new Deck();
            var human = evaluator(files, deck, List.of());
            var ai = new ChaosDraftEvaluator(files.load(), List.of(), ColorSet.fromMask(0), deck, true);
            assertEquals(human.strength(worst).value(), 85.0);
            assertEquals(ai.strength(worst).value(), 0.0);
            assertTrue(ai.strength(worst).aiEvidence().contains("CannotPlayAi:ControlPlayer"));
            assertEquals(human.strength(coming).value(), 48.0);
            assertEquals(ai.strength(coming).value(), 0.0);
            assertTrue(ai.strength(coming).aiEvidence().contains("chkDrawback"));
            var dieSupport = evaluator(files, deck, List.of(dice)).strength(cutter);
            assertEquals(dieSupport.support(), Support.UNAVAILABLE);
            assertTrue(dieSupport.value() > human.strength(cutter).value());
        }
    }

    @Test
    public void estimatesRealRulesWithoutGuessingUnknownEffectsOrReusingNames() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            Files.writeString(files.directory.resolve("custom_ratings.tsv"), "Grizzly Bears\tM10\t87\n");
            PaperCard official = script("g/grizzly_bears.txt", "M10");
            PaperCard collision = custom("Name:Grizzly Bears", "ManaCost:1 G", "Types:Creature Bear", "PT:4/3",
                    "A:AB$ UnrecognizedForgeEffect", "SVar:Unused:DB$ OpenAttraction", "Oracle:Open an Attraction.");
            PaperCard noEstimate = custom("Name:Unrated Puzzle", "ManaCost:1 G", "Types:Sorcery",
                    "A:SP$ UnrecognizedForgeEffect", "Oracle:Unrecognized effect.");
            var evaluator = evaluator(files, new Deck(), List.of());
            assertEquals(evaluator.strength(official).value(), 87.0);
            assertNull(evaluator.strength(collision).external());
            assertEquals(evaluator.strength(collision).source(), "estimated");
            assertEquals(evaluator.strength(collision).support(), Support.UNKNOWN);
            assertTrue(evaluator.strength(collision).value() > 0);
            assertNull(evaluator.strength(noEstimate).value());
            assertEquals(evaluator.strength(noEstimate).support(), Support.UNKNOWN);

            PaperCard unreachable = custom("Name:Unreachable", "ManaCost:1 G", "Types:Creature Bear", "PT:2/2",
                    "SVar:Unused:DB$ OpenAttraction", "Oracle:Open an Attraction.");
            assertEquals(evaluator.strength(unreachable).support(), Support.INDEPENDENT);
            PaperCard draw = custom("Name:New Research", "ManaCost:2 U", "Types:Sorcery",
                    "A:SP$ Draw | NumCards$ 2", "Oracle:Draw two cards.");
            assertEquals(evaluator.strength(draw).support(), Support.INDEPENDENT);
            assertTrue(evaluator.strength(draw).value() > 0);
        }
    }

    @Test
    public void appliesLegacyManualRatingsToAnIdentifiedCustomScript() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard card = FModel.getMagicDb().getCommonCards().getAllCards().stream()
                    .filter(print -> print.getName().startsWith("Aspects of the Crucible Horn")
                            && "ERLB".equals(print.getEdition()))
                    .findFirst().orElseThrow();
            assertTrue(ChaosRatings.isCustom(card));
            Files.writeString(files.directory.resolve("custom_ratings.tsv"),
                    card.getName() + "\t68\tmanual\n" + card.getName() + "\tERLB\t73\n");
            assertEquals(files.load().lookup(card).value(), 73.0);
        }
    }

    @Test
    public void preservesOrdinaryRatingsAndNativeColorsWhenInactive() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            PaperCard bears = script("g/grizzly_bears.txt", "M10");
            var evaluator = evaluator(files, new Deck(), List.of());
            assertEquals(evaluator.evaluatePack(List.of(bears), 2, true).get(0).score(), 65.0);
            assertEquals(evaluator.preview(List.of(bears)).get(bears).text(), "65.0");
            assertEquals(evaluator.preview(List.of(bears, bears)).get(bears).text(), "65.0");
            assertTrue(evaluator.preview(List.of(bears, bears)).get(bears).recommended());
            BoosterDraft full = new BoosterDraft(LimitedPoolType.Full, 1);
            BoosterDraft block = new BoosterDraft(LimitedPoolType.Block, 1);
            assertTrue(FullChaosDraftPicker.usesRatings(true, true, full));
            assertFalse(FullChaosDraftPicker.usesRatings(false, true, full));
            assertFalse(FullChaosDraftPicker.usesRatings(true, false, full));
            assertFalse(FullChaosDraftPicker.usesRatings(true, true, block));
            assertSame(FullChaosDraftPicker.choose(List.of(bears, PaperCard.FAKE_CARD), List.of(),
                    new DeckColors(), 1, block), bears);

            PaperCard land = script("t/temple_garden.txt", "GRN");
            for (boolean enabled : List.of(false, true)) {
                DeckColors inactive = new DeckColors();
                FullChaosDraftPicker.updateColors(inactive, land, FullChaosDraftPicker.usesRatings(enabled, false, full));
                DeckColors nativeColors = new DeckColors();
                nativeColors.getChosenColors();
                nativeColors.addColorsOf(land);
                assertFalse(nativeColors.getChosenColors().isColorless());
                assertEquals(inactive.getChosenColors(), nativeColors.getChosenColors());
            }
        }
    }

    @Test
    public void respectsTheIndependentDeckBuilderSwitch() {
        String key = "forge.chaosDraft.deckBuilder";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "false");
            assertFalse(FullChaosDraftPicker.useDeckBuilder(true));
            System.setProperty(key, "true");
            assertTrue(FullChaosDraftPicker.useDeckBuilder(true));
            assertFalse(FullChaosDraftPicker.useDeckBuilder(false));
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    public void keepsRandomConditionalWithoutConfusingItWithAiAbilitySupport() throws IOException {
        PaperCard card = custom("Name:Test Random Draw", "ManaCost:2 U", "Types:Sorcery",
                "A:SP$ Draw | NumCards$ 2", "AI:RemoveDeck:Random", "DeckNeeds:Type$Creature",
                "Oracle:Draw two cards.");
        assertFalse(ChaosAiEvidence.deckAllowed(card, List.of()));
        assertTrue(ChaosAiEvidence.deckAllowed(card, List.of(script("g/grizzly_bears.txt", "M10"))));
        try (RatingFiles files = new RatingFiles()) {
            var human = evaluator(files, new Deck(), List.of());
            assertTrue(human.strength(card).value() > 0);
            assertEquals(human.strength(card).aiSupport(), ChaosAiEvidence.Status.UNKNOWN);
        }
    }

    @Test
    public void retriesWithOriginalPoolAndColorsAndLetsErrorsEscape() throws IOException {
        PaperCard bears = script("g/grizzly_bears.txt", "M10");
        List<PaperCard> pool = new ArrayList<>(Collections.nCopies(22, bears));
        DeckColors colors = new DeckColors();
        colors.setColorsByList(List.of(MagicColor.GREEN));
        Deck nativeDeck = new BoosterDeckBuilder(new ArrayList<>(pool), colors).buildDeck("M10");
        var result = FullChaosDraftPicker.buildWithFallback(pool, colors, "M10", (cards, attemptColors) -> {
            cards.clear();
            attemptColors.setColorsByList(List.of(MagicColor.RED));
            throw new IllegalStateException("deliberate failure");
        });
        assertFalse(result.chaosBuilder());
        assertEquals(result.deck().getMain().countAll(), 40);
        assertEquals(result.deck().getMain().toFlatList(), nativeDeck.getMain().toFlatList());
        assertEquals(pool.size(), 22);
        assertEquals(colors.getChosenColors(), ColorSet.fromMask(MagicColor.GREEN));
        expectThrows(OutOfMemoryError.class, () -> FullChaosDraftPicker.buildWithFallback(pool, colors, "M10",
                (cards, attemptColors) -> { throw new OutOfMemoryError("test sentinel"); }));
    }

    @Test
    public void archdemonPreventsEvenReadingHiddenCandidates() throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            BoosterDraft draft = new BoosterDraft(LimitedPoolType.Full, 1);
            LimitedPlayer human = draft.getHumanPlayer();
            PaperCard archdemon = script("a/archdemon_of_paliano.txt", "CN2");
            human.receiveOpenedPack(new DraftPack(new ArrayList<>(List.of(archdemon)), 0));
            human.draftCard(archdemon);
            assertTrue(human.hasArchdemonCurse());
            List<PaperCard> unreadable = new AbstractList<>() {
                @Override public PaperCard get(int index) { throw new AssertionError("read hidden card"); }
                @Override public int size() { throw new AssertionError("read hidden pack"); }
            };
            assertSame(FullChaosDraftPicker.evaluateVisiblePack(draft, unreadable, human.getDeck(), files.load(), true),
                    PackEvaluation.empty());
        }
    }

    public static PackEvaluation preview(List<PaperCard> pack) throws IOException {
        try (RatingFiles files = new RatingFiles()) {
            return evaluator(files, new Deck(), List.of()).preview(pack);
        }
    }

    private static ChaosDraftEvaluator evaluator(RatingFiles files, Deck deck, List<PaperCard> picked) {
        return new ChaosDraftEvaluator(files.load(), picked, ColorSet.fromMask(0), deck, false);
    }

    public static PaperCard script(String relativePath, String edition) throws IOException {
        CardRules rules = new CardRules.Reader().readCard(Files.readAllLines(
                Path.of(ForgeConstants.CARD_DATA_DIR, relativePath)));
        return new PaperCard(rules, edition, CardRarity.Common);
    }

    private static PaperCard custom(String... lines) {
        CardRules rules = CardRules.fromScript(List.of(lines));
        rules.setCustom();
        return new PaperCard(rules, "M10", CardRarity.Common);
    }

    private static final class RatingFiles implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("forge-chaos-ratings-test");

        RatingFiles() throws IOException {
            Files.writeString(directory.resolve("ratings.tsv"), "Line Cutter\t70.71\nComing Attraction\t80\n"
                    + "Wrench Rigger\t65\nWork a Double\t66\nStiltstrider\t67\nGrizzly Bears\t65\n"
                    + "Big Winner\t62\nSteamflogger Boss\t70\nWorst Fears\t85\n"
                    + "Serra Angel\t80\nSoulcipher Board\t74\n");
        }

        ChaosRatings load() {
            return new ChaosRatings(directory);
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(directory.resolve("custom_ratings.tsv"));
            Files.delete(directory.resolve("ratings.tsv"));
            Files.delete(directory);
        }
    }
}
