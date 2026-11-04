package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.SpellApiToAi;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

public class CnvlAetheriseTest extends AITest {
    @Test(dataProvider = "flags")
    public void decliningOptionalActionDoesNotMoveOrEmitAnEvent(boolean ordinary) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = watcher(player);
        Card target = addCard("Grizzly Bears", player);
        player.dangerouslySetController(new forge.player.PlayerControllerHuman(game, player, player.getLobbyPlayer()) {
            @Override
            public boolean confirmAction(SpellAbility sa, forge.game.player.PlayerActionConfirmMode mode,
                                         String message, List<String> options, Card cardToShow,
                                         java.util.Map<String, Object> params) {
                return false;
            }
        });
        SpellAbility action = AbilityFactory.getAbility("DB$ " + (ordinary ? "ChangeZone" : "Aetherise")
                + " | Origin$ Battlefield | Destination$ Library | ValidTgts$ Creature | Optional$ True", source);
        action.getTargets().add(target);
        resolve(game, action, player);
        assertTrue(target.isInZone(ZoneType.Battlefield));
        assertEquals(player.getLife(), 20);
    }

    @DataProvider
    public Object[][] librarySizes() {
        return new Object[][] {{0}, {1}, {3}};
    }

    @Test(dataProvider = "librarySizes")
    public void bottomToTopAndAlreadyOnTopCountOnceWithoutZoneChange(int size) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = watcher(player);
        List<Card> library = addLibrary(player, size);
        SpellAbility action = AbilityFactory.getAbility("DB$ Aetherise | Origin$ Library | DefinedPlayer$ You"
                + " | ChangeType$ Card | ChangeNum$ 1 | Mandatory$ True | OriginLibraryPosition$ Bottom", source);
        resolve(game, action, player);
        assertEquals(player.getLife(), size == 0 ? 20 : 21);
        if (size > 0) {
            assertEquals(player.getCardsIn(ZoneType.Library).getFirst().getId(), library.get(size - 1).getId());
        }
    }

    @Test
    public void ordinaryLibraryMoveDoesNotBecomeAetherise() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = watcher(player);
        Card card = addCardToZone("Island", player, ZoneType.Library);
        source.addRemembered(card);
        SpellAbility action = AbilityFactory.getAbility(
                "DB$ ChangeZone | Defined$ Remembered | Origin$ Library | Destination$ Library", source);
        resolve(game, action, player);
        assertEquals(player.getLife(), 20);
    }

    @Test
    public void replacementInExileStillCountsTheAction() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = watcher(player);
        Card bear = addCard("Grizzly Bears", player);
        bear.setSVar("Exile", "DB$ ChangeZone | Hidden$ True | Origin$ All | Destination$ Exile | Defined$ ReplacedCard");
        bear.addReplacementEffect(ReplacementHandler.parseReplacement(
                "Event$ Moved | ActiveZones$ Battlefield | Origin$ Battlefield | Destination$ Library"
                        + " | ValidCard$ Card.Self | ReplaceWith$ Exile", bear, true));
        SpellAbility action = AbilityFactory.getAbility("DB$ Aetherise | Origin$ Battlefield | ValidTgts$ Creature", source);
        action.getTargets().add(bear);
        resolve(game, action, player);
        assertTrue(game.findById(bear.getId()).isInZone(ZoneType.Exile));
        assertEquals(player.getLife(), 21);
    }

    @DataProvider
    public Object[][] flags() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "flags")
    public void onlyLegalTargetsPerformTheAction(boolean allIllegal) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        watcher(player);
        Card first = addCard("Grizzly Bears", player);
        Card second = addCard("Grizzly Bears", player);
        Card source = createCard("Unsummon", player);
        game.getStackZone().add(source);
        SpellAbility spell = AbilityFactory.getAbility(
                "SP$ Aetherise | Origin$ Battlefield | ValidTgts$ Creature | TargetMax$ 2", source);
        spell.setActivatingPlayer(player);
        spell.getTargets().add(first);
        spell.getTargets().add(second);
        prepare(game);
        game.getStack().add(spell);
        game.getAction().moveToHand(second, null);
        if (allIllegal) {
            game.getAction().moveToHand(first, null);
        }
        drain(game);
        assertEquals(player.getLife(), allIllegal ? 20 : 21);
    }

    @Test(dataProvider = "flags")
    public void scriptsIdentifyThePlayerDoingTheAction(boolean portal) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        watcher(player);
        watcher(opponent);
        Card card = addCardToZone("Island", opponent, ZoneType.Hand);
        Card source = addCard(portal ? "Portal Guard" : "Inquirer of Knowledge", player);
        SpellAbility action = AbilityFactory.getAbility(source, portal ? "TrigDiscard" : "TrigAetherise");
        if (portal) {
            action.setTriggeringObject(AbilityKey.DefendingPlayer, opponent);
        } else {
            action.getTargets().add(opponent);
        }
        resolve(game, action, player);
        assertEquals(player.getLife(), 20);
        assertEquals(opponent.getLife(), 21);
        assertEquals(opponent.getCardsIn(ZoneType.Library).getFirst().getId(), card.getId());
    }

    @Test(dataProvider = "flags")
    public void independentWorldCopiesCanPerformTheAction(boolean snapshot) {
        Game original = initAndCreateGame();
        Player player = original.getPlayers().get(1);
        Card source = watcher(player);
        addLibrary(player, 3);
        prepare(original);
        Game copy = snapshot ? new GameSnapshot(original).makeCopy(null, false)
                : new GameCopier(original).withStack(false).makeCopy();
        Player copied = copy.getPlayer(player.getId());
        SpellAbility action = AbilityFactory.getAbility("DB$ Aetherise | Origin$ Library | DefinedPlayer$ You"
                + " | ChangeType$ Card | ChangeNum$ 1 | Mandatory$ True | OriginLibraryPosition$ Bottom",
                copy.findById(source.getId()));
        resolve(copy, action, copied);
        assertEquals(copied.getLife(), 21);
        assertEquals(player.getLife(), 20);
    }

    @Test
    public void aiCanActivateAndPayForSecretObservatory() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        watcher(player);
        Card source = addCard("Secret Observatory", player);
        addLibrary(player, 4);
        assertTrue(SpellApiToAi.Converter.get(ApiType.Aetherise) instanceof ChangeZoneAi);
        SpellAbility activation = source.getSpellAbilities().stream().filter(s -> s.getApi() == ApiType.Aetherise)
                .findFirst().orElseThrow();
        activation.setActivatingPlayer(player);
        for (int i = 0; i < 3; i++) {
            player.getManaPool().addMana(new Mana((byte) 0, source, null, player));
        }
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN2, player);
        prepare(game);
        assertTrue(SpellApiToAi.Converter.get(ApiType.Aetherise)
                .canPlayWithSubs(player, activation).willingToPlay());
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, activation, null));
        drain(game);
        assertTrue(source.isTapped());
        assertEquals(player.getManaPool().totalMana(), 0);
        assertEquals(player.getLife(), 21);
    }

    private Card watcher(Player player) {
        CardRules rules = CardRules.fromScript(List.of("Name:Aetherise observer fixture", "ManaCost:no cost", "Types:Land",
                "T:Mode$ Aetherised | ValidPlayer$ You | TriggerZones$ Battlefield | Execute$ Reward",
                "T:Mode$ ChangesZone | Origin$ Library | Destination$ Library | TriggerZones$ Battlefield | Execute$ Wrong",
                "SVar:Reward:DB$ GainLife | Defined$ You | LifeAmount$ 1",
                "SVar:Wrong:DB$ GainLife | Defined$ You | LifeAmount$ 1000", "Oracle:"));
        Card card = CardFactory.getCard(new PaperCard(rules, "", CardRarity.Common), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    @Test
    public void aetherisingASpellUsesTheStackPathExactlyOnce() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = watcher(player);
        Card card = createCard("Shock", player);
        game.getStackZone().add(card);
        SpellAbility shock = card.getFirstSpellAbility();
        shock.setActivatingPlayer(player);
        shock.getTargets().add(game.getPlayers().get(0));
        prepare(game);
        game.getStack().add(shock);
        SpellAbility action = AbilityFactory.getAbility("DB$ Aetherise | Origin$ Stack | ValidTgts$ Spell", source);
        action.getTargets().add(shock);
        resolve(game, action, player);
        assertTrue(game.findById(card.getId()).isInZone(ZoneType.Library));
        assertEquals(player.getLife(), 21);
        assertEquals(game.getPlayers().get(0).getLife(), 20);
    }

    private List<Card> addLibrary(Player player, int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> addCardToZone("Island", player, ZoneType.Library)).toList();
    }

    private void prepare(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private void resolve(Game game, SpellAbility action, Player player) {
        action.setActivatingPlayer(player);
        prepare(game);
        AbilityUtils.resolve(action);
        drain(game);
    }

    private void drain(Game game) {
        for (int i = 0; i < 30; i++) {
            game.getTriggerHandler().runWaitingTriggers();
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (game.getStack().isEmpty()) {
                return;
            }
            game.getStack().resolveStack();
        }
        fail("Stack did not drain");
    }
}
