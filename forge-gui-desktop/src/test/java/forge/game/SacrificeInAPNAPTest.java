package forge.game;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

public class SacrificeInAPNAPTest extends AITest {
    @Test
    public void allChoicesPrecedeMovesAndUseEffectRestrictions() {
        Game game = initAndCreateThreePlayerGame();
        Player active = game.getPlayers().get(1);
        Player protectedPlayer = game.getPlayers().get(2);
        Player last = game.getPlayers().get(0);
        Card first = addCard("Zulaport Cutthroat", active);
        Card second = addCard("Craw Wurm", last);
        addCard("Sigarda, Host of Herons", protectedPlayer);
        Card protectedCreature = addCard("Grizzly Bears", protectedPlayer);
        List<Player> order = new ArrayList<>();
        for (Player chooser : List.of(active, protectedPlayer, last)) {
            chooser.dangerouslySetController(new PlayerControllerAi(game, chooser, chooser.getLobbyPlayer()) {
                @Override
                public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                                     CardCollectionView legal, String message) {
                    order.add(chooser);
                    assertTrue(first.isInPlay() && second.isInPlay(), "Every choice precedes the batch");
                    assertEquals(min, 1);
                    assertEquals(max, 1);
                    assertFalse(legal.contains(protectedCreature));
                    if (chooser.equals(last)) {
                        assertTrue(message.contains(first.getName()), "Prior choice is visible");
                    }
                    return super.choosePermanentsToSacrifice(sa, min, max, legal, message);
                }
            });
        }
        Card source = fixture(active, "SacrificeInAPNAP", "Defined$ Player | SacValid$ Creature | RememberSacrificed$ True");
        resolve(game, activated(source, active));
        assertEquals(order, List.of(active, last));
        assertTrue(protectedCreature.isInPlay());
        assertFalse(first.isInPlay() || second.isInPlay());
        assertEquals(com.google.common.collect.Iterables.size(source.getRemembered()), 2);
        assertEquals(active.getLife(), 21, "Cutthroat sees its own death");
        assertEquals(protectedPlayer.getLife(), 19);
        assertEquals(last.getLife(), 19);
    }

    @Test
    public void departingKalitasRedirectsOtherSacrificeAndLkiIsRetained() {
        Game game = initAndCreateGame();
        Player active = game.getPlayers().get(1);
        Player other = game.getPlayers().get(0);
        Card kalitas = addCard("Kalitas, Traitor of Ghet", active);
        Card victim = addCard("Craw Wurm", other);
        Card source = fixture(active, "SacrificeInAPNAP", "Defined$ Player | SacValid$ Creature | RememberSacrificed$ True");
        resolve(game, activated(source, active));
        assertTrue(game.getCardState(kalitas).isInZone(ZoneType.Graveyard));
        assertTrue(game.getCardState(victim).isInZone(ZoneType.Exile));
        assertEquals(active.getSacrificedThisTurn().size(), 1);
        assertEquals(other.getSacrificedThisTurn().size(), 1);
        Card lki = other.getSacrificedThisTurn().get(0);
        assertSame(lki.getController(), other);
        assertEquals(lki.getNetToughness(), 4);
    }

    @Test
    public void departingMayhemDevilSeesBothSacrifices() {
        Game game = initAndCreateGame();
        Player active = game.getPlayers().get(1);
        Player other = game.getPlayers().get(0);
        Card devil = addCard("Mayhem Devil", active);
        addCard("Grizzly Bears", other);
        Card source = fixture(active, "SacrificeInAPNAP", "Defined$ Player | SacValid$ Creature");
        prepare(game);
        game.getStack().add(activated(source, active));
        game.getStack().resolveStack();
        game.getStack().unfreezeStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(devil.isInPlay());
        assertEquals(game.getStack().size(), 2);
        for (var instance : game.getStack()) {
            assertEquals(instance.getSpellAbility().getHostCard(), devil);
        }
    }

    @Test
    public void removingHumilityDoesNotCreateAJustAcquiredSacrificeTrigger() {
        Game game = initAndCreateGame();
        Player active = game.getPlayers().get(1);
        Player other = game.getPlayers().get(0);
        Card humility = addCard("Humility", active);
        Card devil = addCard("Mayhem Devil", active);
        addCard("Grizzly Bears", other);
        active.dangerouslySetController(new PlayerControllerAi(game, active, active.getLobbyPlayer()) {
            @Override
            public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                                 CardCollectionView legal, String message) {
                return new forge.game.card.CardCollection(humility);
            }
        });
        Card source = fixture(active, "SacrificeInAPNAP", "Defined$ Player | SacValid$ Permanent.nonLand");
        game.getAction().checkStaticAbilities();
        assertTrue(devil.getTriggers().isEmpty());
        prepare(game);
        game.getStack().add(activated(source, active));
        game.getStack().resolveStack();
        game.getStack().unfreezeStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertTrue(devil.isInPlay());
        assertFalse(devil.getTriggers().isEmpty());
        assertTrue(game.getStack().isEmpty(), "Mayhem Devil had no trigger in the pre-batch state");
    }

    @Test
    public void preventedMoveDoesNotRecordSacrifice() {
        Game game = initAndCreateGame();
        Player active = game.getPlayers().get(1);
        Player other = game.getPlayers().get(0);
        Card protectedCreature = addScript(active, List.of(
                "Name:Prevented Fixture", "ManaCost:1", "Types:Creature Bear", "PT:2/2",
                "R:Event$ Moved | Layer$ CantHappen | ActiveZones$ Battlefield | ValidCard$ Card.Self | Origin$ Battlefield | Destination$ Graveyard | Description$ This creature can't enter the graveyard from the battlefield.",
                "Oracle:Prevent moving to graveyard."));
        protectedCreature.setController(other, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(protectedCreature);
        Card equipment = addCard("Bonesplitter", other);
        equipment.attachToEntity(protectedCreature, null, true);
        Card source = fixture(active, "SacrificeInAPNAP", "Defined$ Player.Opponent | SacValid$ Creature | RememberSacrificed$ True");
        resolve(game, activated(source, active));
        assertTrue(protectedCreature.isInPlay());
        assertSame(protectedCreature.getController(), other);
        assertSame(equipment.getEntityAttachedTo(), protectedCreature);
        assertTrue(other.getSacrificedThisTurn().isEmpty());
        assertFalse(source.hasRemembered());
    }

    @Test
    public void existingMarauderAndGixCommandModesKeepTheirBehavior() {
        Game game = initAndCreateThreePlayerGame();
        Player active = game.getPlayers().get(1);
        Player first = game.getPlayers().get(2);
        Player second = game.getPlayers().get(0);
        Card left = addCard("Grizzly Bears", first);
        Card right = addCard("Grizzly Bears", second);
        prepare(game);
        game.getAction().moveToPlay(addCardToZone("Accursed Marauder", active, ZoneType.Hand), null, null);
        drain(game);
        assertFalse(left.isInPlay() || right.isInPlay());

        Card lesser = addCard("Grizzly Bears", first);
        Card greater = addCard("Craw Wurm", first);
        Card greaterOther = addCard("Craw Wurm", second);
        Card command = addCardToZone("Gix's Command", active, ZoneType.Hand);
        SpellAbility mode = command.getFirstSpellAbility().getAdditionalAbilityList("Choices").stream()
                .filter(sa -> sa.getApi() == ApiType.RepeatEach).findFirst().orElseThrow();
        resolve(game, mode.copy(command, active, false));
        assertTrue(lesser.isInPlay());
        assertFalse(greater.isInPlay() || greaterOther.isInPlay());
    }

    @Test
    public void existingMassSacrificeKeepsDeathTriggersAndLki() {
        Game game = initAndCreateThreePlayerGame();
        Player active = game.getPlayers().get(1);
        Player other = game.getPlayers().get(0);
        addCard("Zulaport Cutthroat", active);
        addCard("Grizzly Bears", active);
        addCard("Grizzly Bears", other);
        Card source = fixture(active, "SacrificeAll", "ValidCards$ Creature | RememberSacrificed$ True");
        resolve(game, activated(source, active));
        assertEquals(active.getLife(), 22);
        assertEquals(other.getLife(), 18);
        assertEquals(active.getSacrificedThisTurn().size(), 2);
        assertEquals(other.getSacrificedThisTurn().size(), 1);
    }

    private static Card fixture(Player player, String operator, String params) {
        return addScript(player, List.of("Name:Sacrifice Fixture", "ManaCost:0", "Types:Artifact",
                "A:AB$ " + operator + " | Cost$ 0 | " + params, "Oracle:Sacrifice."));
    }

    private static Card addScript(Player player, List<String> lines) {
        Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(lines), "TEST", CardRarity.Common), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static SpellAbility activated(Card card, Player player) {
        SpellAbility ability = card.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility)
                .findFirst().orElseThrow();
        ability.setActivatingPlayer(player);
        return ability;
    }

    private static void prepare(Game game) {
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private static void resolve(Game game, SpellAbility ability) {
        prepare(game);
        game.getStack().add(ability);
        drain(game);
    }

    private static void drain(Game game) {
        while (true) {
            game.getStack().unfreezeStack();
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (game.getStack().isEmpty()) {
                return;
            }
            game.getStack().resolveStack();
        }
    }
}
