package forge.ai.simulation;

import forge.ai.AITest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import static org.testng.Assert.*;

public class FirstTimeHistoryTest extends AITest {
    @Test
    public void copiesBeforeAndAfterFirstDeathHaveIndependentHistory() {
        final Game game = initAndCreateGame();
        final Player player = active(game);
        final Card watcher = watcher(player);
        final Card first = addCard("Grizzly Bears", player);
        final Card second = addCard("Runeclaw Bear", player);
        final Card third = addCard("Hill Giant", player);

        final Game before = new GameCopier(game).makeCopy();
        final Game sibling = new GameCopier(game).makeCopy();
        destroy(before, watcher, first);
        assertEquals(active(before).getLife(), 21);
        assertEquals(active(sibling).getLife(), 20);
        assertEquals(player.getLife(), 20);
        assertTrue(current(game, first).isInPlay());
        destroy(sibling, watcher, second);
        assertEquals(active(sibling).getLife(), 21);

        destroy(game, watcher, first);
        assertEquals(player.getLife(), 21);

        final Game after = new GameCopier(game).makeCopy();
        destroy(after, watcher, second);
        assertEquals(active(after).getLife(), 21, "The second death must not become the first in a simulation");
        assertTrue(current(game, second).isInPlay());

        destroy(game, watcher, second);
        assertEquals(player.getLife(), 21);
        game.onCleanupPhase();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, 2);
        destroy(game, watcher, third);
        assertEquals(player.getLife(), 22);
        assertTrue(current(after, third).isInPlay());
    }

    @Test
    public void distinctFiltersAndOrdinaryTriggersSeeASimultaneousBatch() {
        final Game game = initAndCreateGame();
        final Player player = active(game);
        final Player other = game.getPlayers().get(0);
        final Card watcher = watcher(player,
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | TriggerZones$ Battlefield"
                        + " | ValidCard$ Creature.OppOwn | FirstTime$ True | FirstTimeValid$ Creature.OppOwn | Execute$ OpponentDeath",
                "SVar:OpponentDeath:DB$ GainLife | Defined$ You | LifeAmount$ 5",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | TriggerZones$ Battlefield"
                        + " | ValidCard$ Creature | Execute$ OrdinaryDeath",
                "SVar:OrdinaryDeath:DB$ GainLife | Defined$ You | LifeAmount$ 10");
        final Card rock = addCard("Sol Ring", player);
        final Card first = addCard("Grizzly Bears", player);
        final Card second = addCard("Runeclaw Bear", player);
        final Card late = addCardToZone("Hill Giant", other, ZoneType.Hand);
        destroy(game, watcher, rock);
        assertEquals(player.getLife(), 20);

        destroy(game, watcher, first, second);
        assertEquals(player.getLife(), 42, "Both creatures in the first batch get the first-death and ordinary triggers");

        final Game copy = new GameCopier(game).makeCopy();
        final Card future = copy.getAction().moveToPlay(current(copy, late),
                copy.getPlayer(other.getId()), null, null);
        destroy(copy, watcher, future);
        assertEquals(active(copy).getLife(), 57, "The first opposing death still triggers, as does the ordinary trigger");
        assertEquals(player.getLife(), 42);
    }

    @Test
    public void historicalTypeAndControllerAreUsedAfterTheCardChangesZones() {
        final Game game = initAndCreateGame();
        final Player player = active(game);
        final Player other = game.getPlayers().get(0);
        final Card watcher = watcherFor(player, "Creature.YouCtrl");
        final Card stolenLand = addCard("Forest", other);
        final SpellAbility animate = ability(watcher, "DB$ Animate | ValidTgts$ Land | Types$ Creature"
                + " | Power$ 2 | Toughness$ 2 | Duration$ UntilEndOfTurn");
        animate.getTargets().add(stolenLand);
        AbilityUtils.resolve(animate);
        stolenLand.addTempController(player, game.getNextTimestamp());
        assertTrue(stolenLand.isCreature());
        assertSame(stolenLand.getController(), player);

        final Card second = addCard("Grizzly Bears", player);
        destroy(game, watcher, stolenLand);
        assertEquals(player.getLife(), 21);
        final Card departed = current(game, stolenLand);
        assertTrue(departed.isInZone(ZoneType.Graveyard));
        assertFalse(departed.isCreature());
        assertNotEquals(departed.getGameTimestamp(), stolenLand.getGameTimestamp());

        final Game copy = new GameCopier(game).makeCopy();
        destroy(copy, watcher, second);
        assertEquals(active(copy).getLife(), 21, "The current noncreature incarnation must not replace the old LKI");
    }

    @Test
    public void replacedDeathDoesNotCountAsAGraveyardBatch() {
        final Game game = initAndCreateGame();
        final Player player = active(game);
        final Card watcher = watcher(player);
        final Card first = addCard("Grizzly Bears", player);
        final Card second = addCard("Runeclaw Bear", player);
        final Card third = addCard("Hill Giant", player);
        final Card replacement = addCard("Rest in Peace", player);
        destroy(game, watcher, first);
        assertTrue(current(game, first).isInZone(ZoneType.Exile));
        assertEquals(player.getLife(), 20);
        game.getAction().exile(replacement, null, null);

        final Game copy = new GameCopier(game).makeCopy();
        destroy(copy, watcher, second);
        assertTrue(current(copy, second).isInZone(ZoneType.Graveyard));
        assertEquals(active(copy).getLife(), 21);
        destroy(copy, watcher, third);
        assertEquals(active(copy).getLife(), 21);
    }

    @Test
    public void snapshotRestoresHistoryFromBeforeAndAfterFirstDeath() {
        final Game game = initAndCreateGame();
        final Player player = active(game);
        final Card watcher = watcher(player);
        final Card first = addCard("Grizzly Bears", player);
        final Card second = addCard("Runeclaw Bear", player);
        final GameSnapshot before = new GameSnapshot(game);
        before.makeCopy();

        destroy(game, watcher, first);
        assertEquals(player.getLife(), 21);
        final GameSnapshot after = new GameSnapshot(game);
        final Game simulation = after.makeCopy();
        destroy(simulation, watcher, second);
        assertEquals(active(simulation).getLife(), 21);

        game.onCleanupPhase();
        after.restoreGameState(game);
        destroy(game, watcher, second);
        assertEquals(player.getLife(), 21, "Restore must bring back the recorded first death");

        before.restoreGameState(game);
        assertEquals(player.getLife(), 20);
        destroy(game, watcher, second);
        assertEquals(player.getLife(), 21, "Restore must discard events recorded since the snapshot");
    }

    @Test
    public void historicalLkiDoesNotSharePlayersZonesOrMutableHistory() {
        final Game game = initAndCreateGame();
        final Player player = active(game);
        final Player opponent = game.getPlayers().get(0);
        final Card bear = addCard("Grizzly Bears", player);
        bear.getDamageHistory().setCreatureAttackedThisCombat(opponent, 0);
        final Card lki = CardCopyService.getLKICopy(bear);
        lki.addRemembered(opponent);
        lki.addHiddenExtrinsicKeywords(game.getNextTimestamp(), 0, List.of("Flying"));
        final Game copy = new GameCopier(game).makeCopy();
        final Card snapshot = CardCopyService.copyLkiForGame(lki, copy, new IdentityHashMap<>());
        assertNotSame(snapshot, current(copy, bear));
        assertTrue(snapshot.isLKI() && snapshot.equalsWithGameTimestamp(lki));
        assertSame(snapshot.getGame(), copy);
        assertSame(snapshot.getController(), active(copy));
        assertSame(snapshot.getOwner(), active(copy));
        assertSame(snapshot.getLastKnownZone(), active(copy).getZone(ZoneType.Battlefield));
        assertTrue(snapshot.getDamageHistory().hasAttackedThisTurn(copy.getPlayer(opponent.getId())));
        assertSame(snapshot.getRemembered().iterator().next(), copy.getPlayer(opponent.getId()));
        snapshot.removeHiddenExtrinsicKeyword("Flying");
        assertTrue(lki.hasKeyword("Flying"));
        snapshot.getDamageHistory().newTurn();
        assertEquals(lki.getDamageHistory().getCreatureAttacksThisTurn(), 1);
    }

    private Card watcher(final Player player, final String... extra) {
        return watcherFor(player, "Creature", extra);
    }

    private Card watcherFor(final Player player, final String filter, final String... extra) {
        final List<String> script = new ArrayList<>(List.of("Name:Death observer", "ManaCost:0", "Types:Artifact",
                "SVar:Reward:DB$ GainLife | Defined$ You | LifeAmount$ 1",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | TriggerZones$ Battlefield"
                        + " | ValidCard$ " + filter + " | FirstTime$ True | FirstTimeValid$ " + filter + " | Execute$ Reward"));
        script.addAll(List.of(extra));
        final Game game = player.getGame();
        final Card card = CardFactory.getCard(new PaperCard(CardRules.fromScript(script), "TST", CardRarity.Common), player, game);
        card.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static Player active(final Game game) {
        return game.getPlayers().get(1);
    }

    private static Card current(final Game game, final Card card) {
        final Card found = game.findById(card.getId());
        assertNotNull(found, "Expected a card with ID " + card.getId());
        return found;
    }

    private static SpellAbility ability(final Card host, final String script) {
        final SpellAbility result = AbilityFactory.getAbility(script, host);
        result.setActivatingPlayer(host.getController());
        return result;
    }

    private static void destroy(final Game game, final Card source, final Card... targets) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
        final SpellAbility destroy = ability(current(game, source),
                "DB$ Destroy | ValidTgts$ Permanent | TargetMin$ " + targets.length + " | TargetMax$ " + targets.length);
        for (final Card target : targets) {
            destroy.getTargets().add(current(game, target));
        }
        AbilityUtils.resolve(destroy);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        while (!game.getStack().isEmpty()) {
            game.getStack().resolveStack();
        }
    }
}

