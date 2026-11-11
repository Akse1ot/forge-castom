package forge.game;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

public class CrownAttachmentTest extends AITest {
    private static final List<String> CROWN_SCRIPT = List.of(
            "Name:Crown of Ideals", "ManaCost:4", "Types:Artifact Equipment",
            "T:Mode$ Attached | ValidSource$ Card.Self | ValidTarget$ Creature | TriggerZones$ Battlefield | Execute$ TrigCopyOthers",
            "SVar:TrigCopyOthers:DB$ Clone | Defined$ Equipped | CloneTarget$ Valid Creature.YouCtrl | ExcludeChosen$ True | Duration$ UntilAttachmentEnds | AttachmentFromTrigger$ True",
            "T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Creature.YouCtrl | TriggerZones$ Battlefield | Execute$ TrigCopyEntering",
            "SVar:TrigCopyEntering:DB$ Clone | Defined$ Equipped | CloneTarget$ TriggeredCardLKICopy | Duration$ UntilAttachmentEnds",
            "K:Equip:4", "Oracle:Copy creatures until the attachment ends.");
    private static final PaperCard CROWN = new PaperCard(CardRules.fromScript(CROWN_SCRIPT), "TEST", CardRarity.MythicRare);
    private static final PaperCard STOCK_COPY = new PaperCard(CardRules.fromScript(CROWN_SCRIPT.stream()
            .map(line -> line.replace("UntilAttachmentEnds", "UntilUnattached").replace(" | AttachmentFromTrigger$ True", ""))
            .toList()), "TEST", CardRarity.MythicRare);

    @Test
    public void firstAttachmentCopiesOnlyOtherCreaturesYouControl() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card crown = addCrown(player);
        Card wearer = addCard("Craw Wurm", player);
        Card other = addCard("Grizzly Bears", player);
        Card enemy = addCard("Llanowar Elves", game.getPlayers().get(0));
        prepare(game);

        crown.attachToEntity(wearer, null, true);
        long id = crown.getAttachmentId();
        assertTrue(id > 0);
        collect(game);
        var trigger = (WrappedAbility) game.getStack().peekAbility();
        assertEquals(trigger.getTriggeringObject(AbilityKey.AttachmentId), Long.valueOf(id));
        drain(game);
        assertEquals(other.getName(), "Craw Wurm");
        assertEquals(other.getCloneStates().size(), 1);
        assertTrue(wearer.getCloneStates().isEmpty());
        assertEquals(enemy.getName(), "Llanowar Elves");

        crown.attachToEntity(wearer, null, true);
        drain(game);
        assertEquals(crown.getAttachmentId(), id);
        assertEquals(other.getCloneStates().size(), 1);
    }

    @Test
    public void detachInResponseAndReattachToSameObjectMakesOldTriggerStale() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card crown = addCrown(player);
        Card wearer = addCard("Craw Wurm", player);
        Card other = addCard("Grizzly Bears", player);
        prepare(game);

        crown.attachToEntity(wearer, null, true);
        collect(game);
        long oldId = crown.getAttachmentId();
        crown.unattachFromEntity(wearer);
        assertEquals(crown.getAttachmentId(), 0L);
        crown.attachToEntity(wearer, null, true);
        assertNotEquals(crown.getAttachmentId(), oldId);
        drain(game);
        assertEquals(other.getName(), "Craw Wurm");
        assertEquals(other.getCloneStates().size(), 1, "Only the newer attachment resolves");
        crown.unattachFromEntity(wearer);
        assertEquals(other.getName(), "Grizzly Bears");
    }

    @Test
    public void changingWearerInResponseDoesNotReuseTheFirstTrigger() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card crown = addCrown(player);
        Card oldWearer = addCard("Craw Wurm", player);
        Card newWearer = addCard("Llanowar Elves", player);
        Card other = addCard("Grizzly Bears", player);
        prepare(game);
        crown.attachToEntity(oldWearer, null, true);
        collect(game);
        crown.attachToEntity(newWearer, null, true);
        drain(game);
        assertEquals(other.getName(), "Llanowar Elves");
        assertEquals(other.getCloneStates().size(), 1);
        assertEquals(oldWearer.getName(), "Llanowar Elves");
        assertTrue(newWearer.getCloneStates().isEmpty());
    }

    @Test
    public void enteringCreatureUsesTheWearerAtResolution() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card crown = addCrown(player);
        Card first = addCard("Craw Wurm", player);
        prepare(game);
        crown.attachToEntity(first, null, true);
        drain(game);

        Card second = addCard("Llanowar Elves", player);
        Card entering = addCardToZone("Grizzly Bears", player, ZoneType.Hand);
        prepare(game);
        Card entered = game.getAction().moveToPlay(entering, null, null);
        Memory original = markers(player, true, true);
        setMemory(entered, original);
        collect(game);
        assertEquals(game.getStack().size(), 1);
        crown.attachToEntity(second, null, true);
        drain(game);
        assertEquals(entered.getName(), "Llanowar Elves");
        assertEquals(entered.getCloneStates().size(), 2, "The ETB adds its own layer after the newer attachment");
        setMemory(entered, markers(player, false, true));
        crown.unattachFromEntity(second);
        assertEquals(entered.getName(), "Grizzly Bears");
        assertMemory(entered, original);
    }

    @Test
    public void phasedAttachmentsKeepTheirLayersUntilCrownLeaves() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card crown = addCrown(player);
        Card wearer = addCard("Craw Wurm", player);
        Card other = addCard("Grizzly Bears", player);
        Memory original = markers(player, true, true);
        Memory whileCloned = markers(player, true, true);
        setMemory(other, original);
        prepare(game);
        crown.attachToEntity(wearer, null, true);
        drain(game);
        setMemory(other, whileCloned);
        long id = crown.getAttachmentId();
        long timestamp = other.getCloneStates().keySet().iterator().next();

        crown.phase(false);
        assertTrue(crown.isPhasedOut());
        assertFalse(wearer.isPhasedOut());
        assertEquals(crown.getAttachmentId(), id);
        assertTrue(other.getCloneStates().containsKey(timestamp));
        assertMemory(other, whileCloned);
        crown.phase(false);
        assertSame(crown.getEntityAttachedTo(), wearer);

        wearer.phase(false);
        assertTrue(crown.isPhasedOut());
        assertEquals(crown.getAttachmentId(), id);
        assertTrue(other.getCloneStates().containsKey(timestamp));
        wearer.phase(false);
        assertFalse(crown.isPhasedOut());
        assertEquals(other.getName(), "Craw Wurm");
        assertMemory(other, whileCloned);

        prepare(game);
        game.getAction().moveToGraveyard(crown, null);
        drain(game);
        assertEquals(other.getName(), "Grizzly Bears");
        assertTrue(other.getCloneStates().isEmpty());
        assertMemory(other, original);
    }

    @DataProvider
    public Object[][] layerRemovalOrders() {
        return new Object[][] {
                {List.of(0, 1)}, {List.of(1, 0)},
                {List.of(0, 1, 2)}, {List.of(0, 2, 1)},
                {List.of(1, 0, 2)}, {List.of(1, 2, 0)},
                {List.of(2, 0, 1)}, {List.of(2, 1, 0)}
        };
    }

    @Test(dataProvider = "layerRemovalOrders")
    public void attachmentLayersRestoreMemoryInAnyOrder(List<Integer> order) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card target = addCard("Grizzly Bears", player);
        Memory base = markers(player, true, true);
        setMemory(target, base);
        List<String> names = List.of("Craw Wurm", "Llanowar Elves", "Hill Giant");
        List<Layer> layers = new ArrayList<>();
        var activeTimestamps = new HashSet<Long>();
        for (int i = 0; i < order.size(); i++) {
            // Exercise each field independently and a layer with both fields populated.
            Memory memory = markers(player, i != 1, i != 0);
            Layer layer = applyLayer(target, CROWN, names.get(i), memory);
            layers.add(layer);
            activeTimestamps.add(layer.timestamp());
        }

        for (int removed : order) {
            Layer layer = layers.get(removed);
            layer.end();
            activeTimestamps.remove(layer.timestamp());
            assertEquals(target.getCloneStates().keySet(), activeTimestamps);
            long visible = activeTimestamps.stream().max(Long::compareTo).orElse(0L);
            Layer top = layers.stream().filter(candidate -> candidate.timestamp() == visible).findFirst().orElse(null);
            assertEquals(target.getName(), top == null ? "Grizzly Bears" : top.wearer().getName());
            assertMemory(target, top == null ? base : top.memory());
        }
    }

    @DataProvider
    public Object[][] mixedRemovalOrders() {
        return new Object[][] {{true, true}, {true, false}, {false, true}, {false, false}};
    }

    @Test(dataProvider = "mixedRemovalOrders")
    public void mixedDurationsKeepTheirPriorMemoryBehavior(boolean stockBelow, boolean topFirst) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card target = addCard("Grizzly Bears", player);
        Memory base = markers(player, true, true);
        Memory lowerMemory = markers(player, true, false);
        Memory upperMemory = markers(player, false, true);
        setMemory(target, base);
        Layer lower = applyLayer(target, stockBelow ? STOCK_COPY : CROWN, "Craw Wurm", lowerMemory);
        Layer upper = applyLayer(target, stockBelow ? CROWN : STOCK_COPY, "Llanowar Elves", upperMemory);
        if (topFirst) {
            upper.end();
            assertEquals(target.getName(), "Craw Wurm");
            assertMemory(target, lowerMemory);
            lower.end();
            assertMemory(target, base);
        } else {
            lower.end();
            assertEquals(target.getName(), "Llanowar Elves");
            assertMemory(target, stockBelow ? base : upperMemory);
            upper.end();
            // Existing CloneEffect behavior across durations: the surviving layer saved the expired layer's memory.
            assertMemory(target, lowerMemory);
        }
        assertTrue(target.getCloneStates().isEmpty());
        assertEquals(target.getName(), "Grizzly Bears");
    }

    @Test
    public void doesNotTransferAcrossAnExpiredStockLayer() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card target = addCard("Grizzly Bears", player);
        Memory base = markers(player, true, true);
        Memory lowerMemory = markers(player, true, false);
        Memory middleMemory = markers(player, false, true);
        setMemory(target, base);
        Layer lower = applyLayer(target, CROWN, "Craw Wurm", lowerMemory);
        Layer middle = applyLayer(target, STOCK_COPY, "Llanowar Elves", middleMemory);
        Layer upper = applyLayer(target, CROWN, "Hill Giant", markers(player, true, true));
        middle.end();
        assertMemory(target, lowerMemory);
        lower.end();
        assertMemory(target, lowerMemory);
        upper.end();
        assertMemory(target, middleMemory);
        assertTrue(target.getCloneStates().isEmpty());
    }

    @Test
    public void reattachmentStartsWithRestoredMemory() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card target = addCard("Grizzly Bears", player);
        Memory base = markers(player, true, true);
        setMemory(target, base);
        Layer layer = applyLayer(target, CROWN, "Craw Wurm", markers(player, true, false));
        long oldId = layer.host().getAttachmentId();
        layer.end();
        assertMemory(target, base);

        prepare(game);
        layer.host().attachToEntity(layer.wearer(), null, true);
        drain(game);
        assertNotEquals(layer.host().getAttachmentId(), oldId);
        assertFalse(target.getCloneStates().containsKey(layer.timestamp()));
        setMemory(target, markers(player, false, true));
        layer.end();
        assertMemory(target, base);
        assertTrue(target.getCloneStates().isEmpty());
    }

    @Test
    public void expiredAttachmentDoesNotRestoreMemoryToReturnedObject() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card target = addCard("Grizzly Bears", player);
        setMemory(target, markers(player, true, true));
        Layer lower = applyLayer(target, CROWN, "Craw Wurm", markers(player, true, false));
        Layer upper = applyLayer(target, CROWN, "Llanowar Elves", markers(player, false, true));
        lower.host().phase(false);
        upper.host().phase(false);
        prepare(game);
        Card graveyard = game.getAction().moveToGraveyard(target, null);
        Card returned = game.getAction().moveToPlay(graveyard, null, null);
        drain(game);
        assertNotSame(returned, target);
        assertTrue(returned.getCloneStates().isEmpty());
        Memory returnedMemory = markers(player, true, true);
        setMemory(returned, returnedMemory);
        lower.host().phase(false);
        upper.host().phase(false);
        lower.end();
        upper.end();
        assertTrue(returned.getCloneStates().isEmpty());
        assertMemory(returned, returnedMemory);
    }

    @Test
    public void removingEitherOfTwoLayersOnlyEndsTheMatchingLayer() {
        for (boolean removeTopFirst : new boolean[] {true, false}) {
            Game game = initAndCreateGame();
            Player player = game.getPlayers().get(1);
            Card lower = addCrown(player);
            Card first = addCard("Craw Wurm", player);
            Card other = addCard("Grizzly Bears", player);
            prepare(game);
            lower.attachToEntity(first, null, true);
            drain(game);
            long lowerTimestamp = other.getCloneStates().keySet().iterator().next();

            Card upper = addCrown(player);
            Card second = addCard("Llanowar Elves", player);
            prepare(game);
            upper.attachToEntity(second, null, true);
            drain(game);
            assertEquals(other.getName(), "Llanowar Elves");
            assertEquals(other.getCloneStates().size(), 2);
            long upperTimestamp = other.getCloneStates().keySet().stream().max(Long::compareTo).orElseThrow();
            assertTrue(upperTimestamp > lowerTimestamp);

            if (removeTopFirst) {
                upper.unattachFromEntity(second);
                assertEquals(other.getName(), "Craw Wurm");
                assertTrue(other.getCloneStates().containsKey(lowerTimestamp));
                lower.unattachFromEntity(first);
            } else {
                lower.unattachFromEntity(first);
                assertEquals(other.getName(), "Llanowar Elves");
                assertTrue(other.getCloneStates().containsKey(upperTimestamp));
                upper.unattachFromEntity(second);
            }
            assertEquals(other.getName(), "Grizzly Bears");
            assertTrue(other.getCloneStates().isEmpty());
        }
    }

    @Test
    public void standardBladeAndCloneKeepTheirOwnDurations() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card blade = addCard("Blade of Shared Souls", player);
        Card wearer = addCard("Grizzly Bears", player);
        addCard("Craw Wurm", player);
        player.dangerouslySetController(new PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override
            public boolean confirmTrigger(WrappedAbility trigger) {
                return true;
            }

            @Override
            public boolean confirmAction(SpellAbility ability, PlayerActionConfirmMode mode,
                                         String message, List<String> options, Card card, Map<String, Object> params) {
                return true;
            }
        });
        prepare(game);
        blade.attachToEntity(wearer, null, true);
        drain(game);
        assertEquals(wearer.getName(), "Craw Wurm");
        wearer.phase(false);
        wearer.phase(false);
        assertSame(blade.getEntityAttachedTo(), wearer);
        assertEquals(wearer.getName(), "Grizzly Bears", "Blade keeps its existing phase-out behavior");

        Card clone = addCardToZone("Clone", player, ZoneType.Hand);
        prepare(game);
        Card entered = game.getAction().moveToPlay(clone, null, null);
        drain(game);
        assertEquals(entered.getName(), "Craw Wurm");
        assertFalse(entered.getCloneStates().isEmpty());
        blade.unattachFromEntity(wearer);
        assertEquals(entered.getName(), "Craw Wurm");
    }

    private static Card addCrown(Player player) {
        return addEquipment(CROWN, player);
    }

    private static Card addEquipment(PaperCard paper, Player player) {
        Card card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private record Layer(Card host, Card wearer, long timestamp, Memory memory) {
        void end() {
            host.unattachFromEntity(wearer);
        }
    }

    private record Memory(Card remembered, Card imprinted) {}

    private Layer applyLayer(Card target, PaperCard paper, String wearerName, Memory memory) {
        Player player = target.getController();
        Game game = player.getGame();
        Card equipment = addEquipment(paper, player);
        Card wearer = addCard(wearerName, player);
        prepare(game);
        equipment.attachToEntity(wearer, null, true);
        drain(game);
        assertEquals(target.getName(), wearerName);
        long timestamp = target.getCloneStates().keySet().stream().max(Long::compareTo).orElseThrow();
        setMemory(target, memory);
        return new Layer(equipment, wearer, timestamp, memory);
    }

    private Memory markers(Player player, boolean remember, boolean imprint) {
        return new Memory(remember ? addCardToZone("Forest", player, ZoneType.Exile) : null,
                imprint ? addCardToZone("Island", player, ZoneType.Exile) : null);
    }

    private static void setMemory(Card target, Memory memory) {
        target.clearRemembered();
        target.clearImprintedCards();
        if (memory.remembered() != null) {
            target.addRemembered(memory.remembered());
        }
        if (memory.imprinted() != null) {
            target.addImprintedCard(memory.imprinted());
        }
    }

    private static void assertMemory(Card target, Memory expected) {
        assertEquals(target.getRememberedCount(), expected.remembered() == null ? 0 : 1, "Remembered count");
        if (expected.remembered() != null) {
            assertSame(target.getRemembered().iterator().next(), expected.remembered(), "Remembered object");
        }
        assertEquals(target.getImprintedCards().size(), expected.imprinted() == null ? 0 : 1, "Imprinted count");
        if (expected.imprinted() != null) {
            assertSame(target.getImprintedCards().get(0), expected.imprinted(), "Imprinted object");
        }
    }

    private static void prepare(Game game) {
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private static void collect(Game game) {
        game.getStack().unfreezeStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    private static void drain(Game game) {
        while (true) {
            collect(game);
            if (game.getStack().isEmpty()) {
                return;
            }
            game.getStack().resolveStack();
        }
    }
}
