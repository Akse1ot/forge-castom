package forge.gamemodes.limited;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.*;

public class ChaosDraftEvaluatorTest extends AITest {
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
