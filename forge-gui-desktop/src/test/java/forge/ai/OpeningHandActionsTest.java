package forge.ai;

import forge.game.Game;
import forge.game.GameAction;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.testng.Assert.*;

public class OpeningHandActionsTest extends AITest {
    @DataProvider
    public Object[][] origins() {
        return new Object[][] {
                {ZoneType.Hand, null, true}, {ZoneType.Command, null, false},
                {ZoneType.Hand, "Hand", true}, {ZoneType.Command, "Hand", false},
                {ZoneType.Hand, "Command", false}, {ZoneType.Command, "Command", true},
                {ZoneType.Hand, "Hand,Command", true}, {ZoneType.Command, "Hand,Command", true},
                {ZoneType.Hand, "All", true}, {ZoneType.Command, "All", false}
        };
    }

    @Test(dataProvider = "origins")
    public void offersOnlyActionsFromTheirSourceZone(ZoneType zone, String origin, boolean offered) throws Exception {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        RecordingController controller = record(player);
        addOpeningCard(player, zone, origin);
        runOpeningActions(game, player);
        assertEquals(controller.offered.size(), offered ? 1 : 0);
        assertEquals(controller.played.size(), offered ? 1 : 0);
    }

    @Test
    public void dualKeywordsResolveWithTheStockAiFromHandAndCommand() throws Exception {
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Command)) {
            Game game = initAndCreateGame();
            Player player = game.getPlayers().get(0);
            Card card = addOpeningCard(player, zone, "Hand");
            card.addIntrinsicKeyword("MayEffectFromOpeningHand:FromCommand");
            card.setSVar("FromCommand", "DB$ ChangeZone | Defined$ Self | Origin$ Command"
                    + " | Destination$ Battlefield | AILogic$ Always");
            runOpeningActions(game, player);
            assertTrue(game.getCardsIn(ZoneType.Battlefield).contains(card));
            assertTrue(game.getStack().isEmpty());
        }
    }

    @Test
    public void originalCardsKeepTheirEligibilityAndPlayFirstRestriction() throws Exception {
        Game game = initAndCreateGame();
        Player first = game.getPlayers().get(0);
        Player second = game.getPlayers().get(1);
        RecordingController firstController = record(first);
        RecordingController secondController = record(second);
        Card leyline = addCardToZone("Leyline of the Void", first, ZoneType.Hand);
        Card sphinx = addCardToZone("Sphinx of Foresight", first, ZoneType.Hand);
        addCardToZone("Gemstone Caverns", first, ZoneType.Hand);
        Card caverns = addCardToZone("Gemstone Caverns", second, ZoneType.Hand);
        addCardToZone("Leyline of the Void", second, ZoneType.Command);
        addCardToZone("Sphinx of Foresight", second, ZoneType.Command);
        runOpeningActions(game, first);
        assertEquals(hosts(firstController.offered), List.of(leyline, sphinx));
        assertEquals(hosts(secondController.offered), List.of(caverns));
    }

    @Test
    public void controllerCanDeclineAndReorderActions() throws Exception {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        RecordingController controller = record(player);
        Card hand = addOpeningCard(player, ZoneType.Hand, "Hand");
        Card command = addOpeningCard(player, ZoneType.Command, "Command");
        controller.onChoose = Collections::reverse;
        runOpeningActions(game, player);
        assertEquals(hosts(controller.offered), List.of(hand, command));
        assertEquals(controller.played, List.of(command, hand));
        controller.played.clear();
        controller.onChoose = List::clear;
        runOpeningActions(game, player);
        assertTrue(controller.played.isEmpty());
    }

    @Test
    public void selectedActionIsSkippedAfterItsCardChangesZone() throws Exception {
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Command)) {
            Game game = initAndCreateGame();
            Player player = game.getPlayers().get(0);
            RecordingController controller = record(player);
            Card mover = addOpeningCard(player, zone, "Hand,Command");
            Card moved = addOpeningCard(player, zone, "Hand,Command");
            ZoneType destination = zone == ZoneType.Hand ? ZoneType.Command : ZoneType.Hand;
            controller.onPlay = sa -> game.getAction().moveTo(destination, moved, sa, null);
            runOpeningActions(game, player);
            assertEquals(controller.offered.size(), 2);
            assertEquals(controller.played, List.of(mover));
            assertTrue(player.getZone(destination).contains(moved));
        }
    }

    private Card addOpeningCard(Player player, ZoneType zone, String origin) {
        Card card = addCardToZone("Runeclaw Bear", player, zone);
        card.addIntrinsicKeyword("MayEffectFromOpeningHand:FromHand");
        card.setSVar("FromHand", "DB$ ChangeZone | Defined$ Self | Destination$ Battlefield | AILogic$ Always"
                + (origin == null ? "" : " | Origin$ " + origin));
        return card;
    }

    private static List<Card> hosts(List<SpellAbility> abilities) {
        List<Card> cards = new ArrayList<>();
        for (SpellAbility sa : abilities) {
            cards.add(sa.getHostCard());
        }
        return cards;
    }

    private static void runOpeningActions(Game game, Player first) throws Exception {
        Method method = GameAction.class.getDeclaredMethod("runOpeningHandActions", Player.class);
        method.setAccessible(true);
        assertSame(method.invoke(game.getAction(), first), first);
    }

    private static RecordingController record(Player player) {
        RecordingController controller = new RecordingController(player);
        player.addController(player.getGame().getNextTimestamp(), player, controller, false);
        return controller;
    }

    // Exercises the existing controller contract without opening a human choice dialog.
    private static class RecordingController extends PlayerControllerAi {
        private List<SpellAbility> offered = new ArrayList<>();
        private final List<Card> played = new ArrayList<>();
        private Consumer<List<SpellAbility>> onChoose = actions -> {};
        private Consumer<SpellAbility> onPlay = sa -> {};

        private RecordingController(Player player) {
            super(player.getGame(), player, player.getLobbyPlayer());
        }

        @Override
        public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> actions) {
            offered = new ArrayList<>(actions);
            onChoose.accept(actions);
            return actions;
        }

        @Override
        public void playSpellAbilityNoStack(SpellAbility sa, boolean canSetupTargets) {
            played.add(sa.getHostCard());
            onPlay.accept(sa);
        }
    }
}
