package forge.gamemodes.limited;

import forge.ai.AITest;
import forge.item.PaperCard;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.*;

import static forge.gamemodes.limited.ChaosDeckTunerTest.card;
import static org.testng.Assert.*;

/** Real PaperCard regression tests; no engine Game or synthetic card ratings are needed. */
public class ChaosManaDemandTest extends AITest {
    private static ChaosManaDemand.Config config(int samples, int horizon, int entries, int nodes) {
        return new ChaosManaDemand.Config(samples, horizon, 817263L, false, false, entries, 65536, nodes);
    }
    private static List<PaperCard> mono() {
        List<PaperCard> cards = new ArrayList<>(Collections.nCopies(17, card("Forest")));
        cards.addAll(Collections.nCopies(23, card("Grizzly Bears")));
        return cards;
    }
    private static ChaosManaDemand.Cost cost(String name) { return ChaosManaDemandReader.castingCost(card(name)); }
    private static ChaosManaDemand.Development developed(int turn, String... names) {
        var reader = new ChaosManaDemandReader();
        var cards = Arrays.stream(names).map(ChaosDeckTunerTest::card).map(c -> reader.read(c).resource()).toList();
        var board = new ChaosManaDemand.Development(cards, 200000);
        board.turn = 1;
        for (int i = 0; i < cards.size(); i++) { board.enter(i); }
        board.turn = turn;
        return board;
    }
    private static boolean payable(ChaosManaDemand.Development board, ChaosManaDemand.Cost cost) {
        return ChaosManaDemand.fund(board, cost, List.of()) != null;
    }

    @Test public void genericColoursAndMandatoryColourlessUseDistinctUnits() {
        assertTrue(payable(developed(1, "Sol Ring"), cost("Warping Wail")));
        assertFalse(payable(developed(1, "Sol Ring"), cost("Divination")));
        assertTrue(payable(developed(1, "Wastes"), new ChaosManaDemand.Cost(0, List.of(32), 1, true, "C")));
        assertFalse(payable(developed(1, "Forest", "Rakdos Signet"), new ChaosManaDemand.Cost(0, List.of(2), 1, true, "U")));
        assertTrue(payable(developed(1, "Forest", "Forest", "Forest", "Forest", "Rakdos Signet"), ChaosManaDemand.generic(5)));
        assertFalse(payable(developed(2, "Dimir Guildgate"), new ChaosManaDemand.Cost(0, List.of(2, 4), 2, true, "UB")));
        assertFalse(payable(developed(1, "Tropical Island"), new ChaosManaDemand.Cost(0, List.of(2, 16), 2, true, "UG")));
    }

    @Test public void setupAndActivationCannotUseFutureManaOrDoubleSpend() {
        assertFalse(payable(developed(1), cost("Sol Ring")));
        assertTrue(payable(developed(1, "Forest"), cost("Sol Ring")));
        assertFalse(payable(developed(1, "Rakdos Signet"), ChaosManaDemand.generic(1)));
        assertTrue(payable(developed(1, "Forest", "Rakdos Signet"), ChaosManaDemand.generic(2)));
        var board = developed(1, "Forest", "Forest", "Rakdos Signet");
        board.on[2] = false;
        var payment = ChaosManaDemand.fund(board, cost("Rakdos Signet"), List.of());
        assertNotNull(payment);
        ChaosManaDemand.commit(board, payment);
        board.enter(2);
        assertFalse(payable(board, ChaosManaDemand.generic(1)));
    }

    @Test public void sicknessAndTappedEntriesDelayConfirmedProduction() {
        assertFalse(payable(developed(1, "Llanowar Elves"), cost("Giant Growth")));
        assertTrue(payable(developed(2, "Llanowar Elves"), cost("Giant Growth")));
        assertFalse(payable(developed(1, "Dimir Guildgate"), ChaosManaDemand.generic(1)));
        assertTrue(payable(developed(2, "Dimir Guildgate"), ChaosManaDemand.generic(1)));
        assertFalse(payable(developed(1, "Worn Powerstone"), ChaosManaDemand.generic(2)));
        assertTrue(payable(developed(2, "Worn Powerstone"), ChaosManaDemand.generic(2)));
    }

    @Test public void unknownPathsDoNotEraseConfirmedPathsOrInventIntrinsicRatings() {
        var reader = new ChaosManaDemandReader();
        assertEquals(reader.read(card("Opal Palace")).resource().source().units(), List.of(32));
        assertEquals(reader.read(card("Quirion Elves")).resource().source().units(), List.of(16));
        assertFalse(reader.read(card("Quirion Elves")).unknown().isEmpty());
        for (String name : List.of("Command Tower", "Arcane Signet", "Commander's Sphere", "Orzhov Basilica", "Fellwar Stone", "Wayfarer's Bauble", "Arbor Elf")) {
            var profile = reader.read(card(name));
            assertNull(profile.resource().source(), name);
            assertTrue(profile.potentialMana(), name);
            assertFalse(profile.unknown().isEmpty(), name);
            assertNull(profile.facts().get("intrinsicValue"), name);
        }
        assertFalse(reader.read(card("Llanowar Elves")).pureMana());
        assertEquals(reader.read(card("Llanowar Elves")).facts().get("power"), "1");
    }

    @Test public void enumerationAndNonmanaNamesDoNotChangeScenariosOrCacheKeys() {
        var seed = mono();
        try (var model = new ChaosManaDemand(seed, seed, config(32, 8, 16, 200000))) {
            var first = model.evaluate(model.slots(seed), true);
            var reordered = new ArrayList<>(seed);
            Collections.shuffle(reordered, new Random(17));
            var second = model.evaluate(model.slots(reordered), true);
            assertEquals(second.id(), first.id());
            assertEquals(second.hitWords(), first.hitWords());
            reordered.set(reordered.indexOf(card("Grizzly Bears")), card("Woolly Loxodon"));
            assertEquals(model.evaluate(model.slots(reordered), true).hitWords(), first.hitWords());
            assertEquals(model.stats().misses(), 1L);
            assertEquals(model.stats().hits(), 2L);
        }
    }

    @Test public void acceptedSourcesKeepTheirSlotsWhenTheNextRoundAddsAnotherSource() {
        var seed = mono();
        var first = new ArrayList<>(seed);
        first.set(39, card("Skyshroud Troopers"));
        var second = new ArrayList<>(first);
        second.set(38, card("Llanowar Elves"));
        var reader = new ChaosManaDemandReader();
        var source = reader.read(card("Skyshroud Troopers")).resource();
        try (var model = new ChaosManaDemand(seed, seed, config(16, 8, 8, 200000))) {
            var before = model.slots(first);
            var cached = model.evaluate(before, true);
            model.anchor(first);
            assertEquals(model.evaluate(model.slots(first), true).id(), cached.id());
            assertEquals(model.slots(second).indexOf(source), before.indexOf(source));
            Collections.reverse(second);
            assertEquals(model.slots(second).indexOf(source), before.indexOf(source));
        }
    }

    @Test public void duplicateLandsUseTheSamePhysicalSlotForTheGenericBridge() {
        var seed = mono();
        var trial = new ArrayList<>(seed);
        trial.set(0, card("Island"));
        try (var model = new ChaosManaDemand(seed, seed, config(32, 8, 8, 200000))) {
            model.anchor(seed);
            var pair = model.genericSwap(model.slots(seed), model.slots(trial));
            assertEquals(pair.before(), pair.after());
            var before = model.evaluate(pair.before(), false);
            var after = model.evaluate(pair.after(), false);
            assertEquals(model.difference(before, after).mean(), 0.);
            assertEquals(model.difference(before, after).se(), 0.);
        }
    }

    @Test public void workloadAndHorizonBelongToAnImmutableDiagnosticContext() {
        var seed = mono();
        var heavier = new ArrayList<>(seed);
        heavier.set(39, card("Woolly Loxodon"));
        try (var a = new ChaosManaDemand(seed, seed, config(16, 8, 4, 200000));
             var b = new ChaosManaDemand(heavier, heavier, config(16, 8, 4, 200000));
             var c = new ChaosManaDemand(seed, seed, config(16, 10, 4, 200000))) {
            var av = a.evaluate(a.slots(seed), true);
            assertNotEquals(b.evaluate(b.slots(seed), true).id(), av.id());
            assertNotEquals(c.evaluate(c.slots(seed), true).id(), av.id());
            assertEquals(b.stats().hits(), 0L);
        }
    }

    @Test public void cacheEvictsWithinItsBudgetAndCloseReleasesIt() {
        var seed = mono();
        var model = new ChaosManaDemand(seed, seed, config(16, 8, 1, 200000));
        model.evaluate(model.slots(seed), true);
        var changed = new ArrayList<>(seed);
        changed.set(0, card("Wastes"));
        model.evaluate(model.slots(changed), true);
        assertEquals(model.stats().entries(), 1);
        assertEquals(model.stats().evictions(), 1L);
        assertTrue(model.stats().peakRetainedBytesEstimate() <= 65536);
        model.close();
        assertEquals(model.stats().entries(), 0);
        assertEquals(model.stats().retainedBytesEstimate(), 0L);
    }

    @Test public void pairedGenericAndFixingDifferencesSumToTheWholeManaDifference() {
        var seed = mono();
        var trial = new ArrayList<>(seed);
        trial.set(39, card("Llanowar Elves"));
        try (var model = new ChaosManaDemand(seed, seed, config(64, 8, 16, 200000))) {
            var beforeSlots = model.slots(seed);
            var afterSlots = model.slots(trial);
            var before = model.evaluate(beforeSlots, true);
            var after = model.evaluate(afterSlots, true);
            var gb = model.evaluate(model.genericPath(beforeSlots, card("Grizzly Bears")), false);
            var ga = model.evaluate(model.genericPath(afterSlots, card("Llanowar Elves")), false);
            var total = model.difference(before, after);
            var generic = model.difference(gb, ga);
            var fixing = model.fixingDifference(before, after, gb, ga);
            assertEquals(generic.mean() + fixing.mean(), total.mean(), 1e-12);
            assertTrue(total.se() >= 0);
            assertTrue(total.low() <= total.high());
        }
    }

    @Test public void expandedQueriesCannotInventFixingForTheFixedMainWorkload() {
        List<PaperCard> seed = new ArrayList<>(Collections.nCopies(9, card("Plains")));
        seed.addAll(Collections.nCopies(9, card("Island")));
        seed.addAll(Collections.nCopies(11, card("Divination")));
        seed.addAll(Collections.nCopies(11, card("Serra Angel")));
        List<PaperCard> stock = new ArrayList<>(seed);
        stock.addAll(List.of(card("Shock"), card("Murder"), card("Rakdos Signet")));
        List<PaperCard> trial = new ArrayList<>(seed);
        trial.set(39, card("Rakdos Signet"));
        var options = new ChaosManaDemand.Config(64, 8, 817263L, false, true, 16, 65536, 200000);
        try (var model = new ChaosManaDemand(seed, stock, options)) {
            var a = model.slots(seed);
            var b = model.slots(trial);
            var before = model.evaluate(a, true);
            var after = model.evaluate(b, true);
            var genericBefore = model.evaluate(model.genericPath(a, card("Serra Angel")), false);
            var genericAfter = model.evaluate(model.genericPath(b, card("Rakdos Signet")), false);
            assertEquals(model.fixingDifference(before, after, genericBefore, genericAfter).mean(), 0., 1e-12);
        }
    }

    @Test public void samplingIsRepeatableMatchesPassiveReferenceAndLeavesForgeRngUntouched() throws Exception {
        var seed = mono();
        byte[] before = rng();
        List<List<String>> hits;
        try (var model = new ChaosManaDemand(seed, seed, config(2048, 8, 4, 200000))) {
            var value = model.evaluate(model.slots(seed), true);
            double expected = 1 - choose(23, 8) / choose(40, 8) - 17 * choose(23, 7) / choose(40, 8);
            assertEquals(value.probabilities()[0], expected, .03);
            hits = value.hitWords();
        }
        try (var model = new ChaosManaDemand(seed, seed, config(2048, 8, 4, 200000))) {
            assertEquals(model.evaluate(model.slots(seed), true).hitWords(), hits);
        }
        assertEquals(rng(), before);
    }

    @Test public void exhaustedDiagnosticBudgetProducesUnavailableRatherThanZeroUtility() {
        var seed = mono();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var shadow = new ChaosManaDemandShadow(seed, seed, config(4, 8, 1, 1), rows::add)) {
            shadow.trial(seed, 39, card("Llanowar Elves"), 4, null, 0);
        }
        assertTrue(rows.stream().anyMatch(r -> "mana_demand_unavailable".equals(r.get("event"))));
        var summary = rows.get(rows.size() - 1);
        assertEquals(summary.get("failed"), true);
        assertFalse(rows.stream().anyMatch(r -> "mana_demand_trial".equals(r.get("event"))));
    }

    private static double choose(int n, int k) { double result = 1; for (int i = 1; i <= k; i++) { result *= (n - i + 1.) / i; } return result; }
    private static byte[] rng() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) { out.writeObject(MyRandom.getRandom()); }
        return bytes.toByteArray();
    }
}
