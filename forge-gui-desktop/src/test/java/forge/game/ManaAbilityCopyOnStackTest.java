package forge.game;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

public class ManaAbilityCopyOnStackTest extends AITest {
    @Test
    public void markedManaCopyCanResolveOrBeCounteredWithoutNewActivation() {
        for (boolean counter : new boolean[] {false, true}) {
            Game game = initAndCreateGame();
            Player player = game.getPlayers().get(1);
            Player opponent = game.getPlayers().get(0);
            Card resonator = addResonator(player);
            addCard("Rings of Brighthearth", player);
            addCard("Ashnod the Uncaring", player);
            Card altar = addCard("Ashnod's Altar", player);
            Card fodder = addCard("Grizzly Bears", player);
            prepare(game);
            activate(player, altar.getManaAbilities().get(0));
            assertFalse(fodder.isInPlay());
            assertEquals(player.getManaPool().totalMana(), 2);

            collect(game);
            assertEquals(game.getStack().size(), 1, "Only Resonator triggers on the original mana ability");
            WrappedAbility triggered = (WrappedAbility) game.getStack().peekAbility();
            assertSame(triggered.getHostCard(), resonator);
            assertFalse(((SpellAbility) triggered.getTriggeringObject(AbilityKey.SpellAbility)).isCopied());
            game.getStack().resolveStack();
            collect(game);
            assertEquals(game.getStack().size(), 1);
            SpellAbility copy = game.getStack().peekAbility();
            assertTrue(copy.isCopied());
            assertTrue(copy.getPaidList("Sacrificed").iterator().hasNext());
            assertEquals(player.getManaPool().totalMana(), 2, "The copy is still waiting for priority");

            if (counter) {
                SpellAbility stifle = addCardToZone("Stifle", opponent, ZoneType.Hand).getFirstSpellAbility();
                addCard("Island", opponent);
                stifle.setActivatingPlayer(opponent);
                assertTrue(stifle.canTarget(copy));
                stifle.getTargets().add(copy);
                assertTrue(ComputerUtil.handlePlayingSpellAbility(opponent, stifle, null));
            }
            game.getStack().resolveStack();
            collect(game);
            assertTrue(game.getStack().isEmpty(), "The copy must not cause another Resonator trigger");
            assertEquals(player.getManaPool().totalMana(), counter ? 2 : 4);
            assertEquals(player.getSacrificedThisTurn().size(), 1);
        }
    }

    @Test
    public void spellWithSacrificeCostIsCopiedOnce() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addResonator(player);
        addCard("Swamp", player);
        addCard("Grizzly Bears", player);
        for (int i = 0; i < 4; i++) {
            addCardToZone("Forest", player, ZoneType.Library);
        }
        SpellAbility rites = addCardToZone("Village Rites", player, ZoneType.Hand).getFirstSpellAbility();
        prepare(game);
        activate(player, rites);
        collect(game);
        assertEquals(game.getStack().size(), 2);
        game.getStack().resolveStack();
        collect(game);
        assertTrue(game.getStack().peekAbility().isCopied());
        game.getStack().resolveStack();
        game.getStack().resolveStack();
        collect(game);
        assertTrue(game.getStack().isEmpty());
        assertEquals(player.getCardsIn(ZoneType.Hand).size(), 4);
        assertEquals(player.getSacrificedThisTurn().size(), 1);
    }

    @Test
    public void ringsAndAshnodStillCopyOrdinarySacrificeActivation() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card rings = addCard("Rings of Brighthearth", player);
        Card ashnod = addCard("Ashnod the Uncaring", player);
        Card seer = addCard("Viscera Seer", player);
        addCard("Grizzly Bears", player);
        addCards("Forest", 2, player);
        addCardToZone("Island", player, ZoneType.Library);
        player.dangerouslySetController(new PlayerControllerAi(game, player, player.getLobbyPlayer()) {
            @Override
            public boolean confirmTrigger(WrappedAbility trigger) {
                return true;
            }
        });
        prepare(game);
        SpellAbility scry = seer.getNonManaAbilities().stream().filter(SpellAbility::isActivatedAbility)
                .findFirst().orElseThrow();
        activate(player, scry);
        collect(game);
        int copies = 0;
        int triggers = 0;
        while (!game.getStack().isEmpty()) {
            SpellAbility top = game.getStack().peekAbility();
            if (top.getHostCard().equals(rings) || top.getHostCard().equals(ashnod)) {
                triggers++;
            }
            if (top.isCopied()) {
                copies++;
                assertFalse(top.hasParam("PutManaCopiesOnStack"));
            }
            assertTrue(triggers <= 2 && copies <= 2);
            game.getStack().resolveStack();
            collect(game);
        }
        assertEquals(triggers, 2);
        assertEquals(copies, 2);
        assertEquals(player.getSacrificedThisTurn().size(), 1);
    }

    private static Card addResonator(final Player player) {
        var paper = new PaperCard(CardRules.fromScript(List.of(
                "Name:Resonator Test", "ManaCost:4", "Types:Artifact",
                "T:Mode$ SpellCast | ValidCard$ Instant,Sorcery | ValidSA$ Spell.SacrificedToPay | ValidActivatingPlayer$ You | TriggerZones$ Battlefield | Execute$ Copy",
                "T:Mode$ AbilityCast | ValidSA$ SpellAbility.SacrificedToPay | ValidActivatingPlayer$ You | TriggerZones$ Battlefield | Execute$ Copy",
                "SVar:Copy:DB$ CopySpellAbility | Defined$ TriggeredSpellAbility | MayChooseTarget$ True | PutManaCopiesOnStack$ True",
                "Oracle:Copy an ability or spell that sacrificed a permanent to pay.")), "TEST", CardRarity.Rare);
        Card card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static void activate(Player player, SpellAbility ability) {
        ability.setActivatingPlayer(player);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, ability, null));
    }

    private static void prepare(Game game) {
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private static void collect(Game game) {
        game.getStack().unfreezeStack();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }
}
