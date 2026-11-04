package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.card.CardRules;
import forge.card.CardRarity;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

public class CnvlMartyrTest extends AITest {
    @DataProvider
    public Object[][] paidX() {
        return new Object[][] {{0}, {1}, {3}};
    }

    @Test(dataProvider = "paidX")
    public void paidXIsAvailableBeforeTargetsAndControlsThePump(int x) throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card commander = addCard("Blazing Commander", player);
        List<Card> targets = addCards("Grizzly Bears", 3, player);
        // The creature's old spell has a different X and must not supply this value.
        SpellAbility oldCast = commander.getFirstSpellAbility();
        oldCast.setXManaCostPaid(9);
        commander.setCastSA(oldCast);
        SpellAbility martyr = martyr(commander, player, x);
        prepare(game);
        AbilityUtils.resolve(martyr);

        SpellAbility reward = onlyReward(game);
        assertEquals(reward.getRootAbility().getXManaCostPaid(), Integer.valueOf(x));
        assertEquals(reward.getTriggeringObject(AbilityKey.Amount), x);
        assertEquals(reward.getMaxTargets(), x, "X must be set before choosing any targets");
        assertEquals(AbilityUtils.calculateAmount(reward.getHostCard(), "X", reward), x);
        for (int i = 0; i < x; i++) {
            assertTrue(reward.canTarget(targets.get(i)));
            reward.getTargets().add(targets.get(i));
        }
        assertTrue(reward.isTargetNumberValid());
        reward.getTargets().add(x < 3 ? targets.get(x) : addCard("Grizzly Bears", player));
        assertFalse(reward.isTargetNumberValid(), "An extra target must exceed the paid X");
        reward.getTargets().remove(reward.getTargets().size() - 1);
        AbilityUtils.resolve(reward);
        for (int i = 0; i < 3; i++) {
            assertEquals(targets.get(i).getNetPower(), 2 + (i < x ? x : 0));
            assertEquals(targets.get(i).hasKeyword(Keyword.TRAMPLE), i < x);
            assertEquals(targets.get(i).hasKeyword(Keyword.INDESTRUCTIBLE), i < x);
        }
        Card lki = (Card) reward.getTriggeringObject(AbilityKey.Card);
        assertTrue(lki.equalsWithGameTimestamp(commander));
        assertNotSame(lki, commander);
        assertTrue(game.findById(commander.getId()).isInZone(ZoneType.Graveyard));
    }

    @Test
    public void independentActivationsAndCopiedAbilityKeepTheirOwnX() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCard("Mirror Box", player);
        Card first = addCard("Blazing Commander", player);
        Card second = addCard("Blazing Commander", player);
        SpellAbility one = martyr(first, player, 1);
        SpellAbility three = martyr(second, player, 3);
        SpellAbility copied = CardFactory.copySpellAbilityAndPossiblyHost(three, three, player);
        prepare(game);
        AbilityUtils.resolve(one);
        AbilityUtils.resolve(copied);
        AbilityUtils.resolve(three); // The original cannot sacrifice the departed creature again.
        List<SpellAbility> rewards = rewards(game);
        assertEquals(rewards.size(), 2);
        assertEquals(rewards.get(0).getXManaCostPaid(), Integer.valueOf(1));
        assertEquals(rewards.get(1).getXManaCostPaid(), Integer.valueOf(3));
        assertEquals(rewards.get(0).getMaxTargets(), 1);
        assertEquals(rewards.get(1).getMaxTargets(), 3);
    }

    @Test
    public void frozenEventKeepsThePaidXWhenTheAbilityChangesLater() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card commander = addCard("Blazing Commander", player);
        SpellAbility martyr = martyr(commander, player, 3);
        prepare(game);
        game.getStack().setFrozen(true);
        AbilityUtils.resolve(martyr);
        martyr.setXManaCostPaid(1);
        game.getStack().setFrozen(false);
        assertEquals(onlyReward(game).getMaxTargets(), 3);
    }

    @Test
    public void forbiddenSacrificeDoesNotCreateAReward() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card commander = addCard("Blazing Commander", player);
        commander.addStaticAbility("Mode$ CantSacrifice | ValidCard$ Card.Self");
        SpellAbility martyr = martyr(commander, player, 3);
        prepare(game);
        assertFalse(commander.canBeSacrificedBy(martyr, true));
        AbilityUtils.resolve(martyr);
        assertTrue(commander.isInPlay());
        assertTrue(rewards(game).isEmpty());
    }

    @Test
    public void blinkDoesNotSacrificeTheNewIncarnation() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card commander = addCard("Blazing Commander", player);
        SpellAbility martyr = martyr(commander, player, 3);
        Card exiled = game.getAction().exile(commander, null, null);
        Card returned = game.getAction().moveToPlay(exiled, player, null, AbilityKey.newMap());
        assertEquals(returned.getId(), commander.getId());
        assertFalse(returned.equalsWithGameTimestamp(commander));
        prepare(game);
        AbilityUtils.resolve(martyr);
        assertTrue(returned.isInPlay());
        assertTrue(rewards(game).isEmpty());
    }

    @Test
    public void exileReplacementStillUsesTheSacrificedObjectsLkiAndController()
            throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card commander = addCard("Blazing Commander", player);
        addCard("Rest in Peace", player);
        SpellAbility martyr = martyr(commander, player, 3);
        prepare(game);
        AbilityUtils.resolve(martyr);
        SpellAbility reward = onlyReward(game);
        assertTrue(game.findById(commander.getId()).isInZone(ZoneType.Exile));
        assertSame(reward.getActivatingPlayer(), player);
        Card lki = (Card) reward.getTriggeringObject(AbilityKey.Card);
        assertSame(lki.getController(), player);
        assertSame(lki.getOwner(), player);
        assertTrue(lki.equalsWithGameTimestamp(commander));
        assertEquals(reward.getMaxTargets(), 3);
    }

    @Test
    public void unpaidMartyrNeverReachesSacrifice() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card commander = addCard("Blazing Commander", player);
        SpellAbility martyr = martyr(commander, player, 3);
        prepare(game);
        assertFalse(ComputerUtil.handlePlayingSpellAbility(player, martyr, null));
        assertTrue(commander.isInPlay());
        assertTrue(rewards(game).isEmpty());
        assertTrue(game.getStack().isEmpty());
    }

    @Test
    public void fixedCostMartyrAndOrdinarySacrificeKeepTheirBehavior() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card cadet = addCard("Selfless Cadet", player);
        SpellAbility fixed = martyr(cadet, player, null);
        prepare(game);
        AbilityUtils.resolve(fixed);
        SpellAbility reward = onlyReward(game);
        assertEquals(reward.getXManaCostPaid(), Integer.valueOf(0));
        assertEquals(AbilityUtils.calculateAmount(cadet, reward.getParam("NumAtt"), reward), 1);
        game.getStack().clearSimultaneousStack();

        Card commander = addCard("Blazing Commander", player);
        SpellAbility ordinary = AbilityFactory.getAbility("DB$ Sacrifice | SacValid$ Self", commander);
        ordinary.setActivatingPlayer(player);
        prepare(game);
        AbilityUtils.resolve(ordinary);
        assertTrue(game.findById(commander.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(rewards(game).isEmpty(), "An ordinary sacrifice must not become Martyr");
    }

    @Test
    public void selectedSacrificeAlsoPassesTheMartyrX() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card commander = addCard("Blazing Commander", player);
        SpellAbility sacrifice = AbilityFactory.getAbility(
                "DB$ Sacrifice | SacValid$ Creature.YouCtrl | Defined$ You | Random$ True", commander);
        sacrifice.setKeyword(Keyword.getInstance("Martyr:X R W"));
        sacrifice.setActivatingPlayer(player);
        sacrifice.setXManaCostPaid(3);
        prepare(game);
        AbilityUtils.resolve(sacrifice);
        assertEquals(onlyReward(game).getMaxTargets(), 3);
    }

    @DataProvider
    public Object[][] sacrificeKeywords() {
        return new Object[][] {{"Exploit"}, {"Devour:2"}};
    }

    @Test(dataProvider = "sacrificeKeywords")
    public void exploitAndDevourRetainTheirOwnBookkeeping(String keyword) throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card host = addCard("Forest", player);
        Card commander = addCard("Blazing Commander", player);
        SpellAbility sacrifice = AbilityFactory.getAbility(
                "DB$ Sacrifice | SacValid$ Creature.YouCtrl | Defined$ You | Random$ True", host);
        sacrifice.setKeyword(Keyword.getInstance(keyword));
        sacrifice.setActivatingPlayer(player);
        prepare(game);
        AbilityUtils.resolve(sacrifice);
        assertTrue(game.findById(commander.getId()).isInZone(ZoneType.Graveyard));
        assertEquals(keyword.equals("Exploit") ? host.getExploited().size() : host.getDevouredCards().size(), 1);
        assertTrue(rewards(game).isEmpty());
    }

    private SpellAbility martyr(Card card, Player player, Integer x) {
        Trigger trigger = card.getTriggers().stream()
                .filter(t -> t.getMode() == TriggerType.AttackerBlocked && t.isKeyword(Keyword.MARTYR))
                .findFirst().orElseThrow();
        SpellAbility ability = trigger.getOverridingAbility().copy(card, player, false);
        assertTrue(ability.isKeyword(Keyword.MARTYR));
        ability.setXManaCostPaid(x);
        return ability;
    }

    @DataProvider
    public Object[][] controllerCases() {
        return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}};
    }

    @Test(dataProvider = "controllerCases")
    public void stolenMartyrKeepsItsControllerWithoutChangingObserversOrExplicitOverride(
            boolean replacement, boolean explicitOverride) throws ReflectiveOperationException, IOException {
        Game game = initAndCreateGame();
        Player controller = game.getPlayers().get(1);
        Player owner = game.getPlayers().get(0);
        List<String> script = Files.readAllLines(Path.of(ForgeConstants.CARD_DATA_DIR,
                "custom cards", "CNVL", "b", "Blazing_Commander.txt"));
        if (explicitOverride) {
            script.replaceAll(line -> line.startsWith("T:Mode$ BecomesMartyr")
                    ? line + " | TriggerController$ CardOwner" : line);
        }
        Card commander = CardFactory.getCard(new PaperCard(CardRules.fromScript(script), "", CardRarity.Common), owner, game);
        commander.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(commander);
        commander.addTempController(controller, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(commander);
        if (replacement) {
            addCard("Rest in Peace", controller);
        }
        Card observer = addCard("Forest", owner);
        observer.setSVar("Reward", "DB$ GainLife | Defined$ You | LifeAmount$ 1");
        observer.addTrigger(TriggerHandler.parseTrigger(
                "Mode$ BecomesMartyr | ValidCard$ Creature | Execute$ Reward | TriggerZones$ Battlefield",
                observer, true));
        SpellAbility sacrifice = martyr(commander, controller, 3);
        prepare(game);
        AbilityUtils.resolve(sacrifice);
        List<SpellAbility> results = rewards(game);
        assertEquals(results.size(), 2);
        SpellAbility own = results.stream().filter(s -> s.getHostCard().getId() == commander.getId())
                .findFirst().orElseThrow();
        SpellAbility observed = results.stream().filter(s -> s.getHostCard().getId() == observer.getId())
                .findFirst().orElseThrow();
        assertSame(own.getActivatingPlayer(), explicitOverride ? owner : controller);
        assertSame(observed.getActivatingPlayer(), owner);
        assertSame(((Card) own.getTriggeringObject(AbilityKey.Card)).getController(), controller);
        assertEquals(own.getMaxTargets(), 3);
        assertTrue(game.findById(commander.getId()).isInZone(replacement ? ZoneType.Exile : ZoneType.Graveyard));
    }

    private void prepare(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private SpellAbility onlyReward(Game game) throws ReflectiveOperationException {
        List<SpellAbility> rewards = rewards(game);
        assertEquals(rewards.size(), 1);
        return rewards.get(0);
    }

    private List<SpellAbility> rewards(Game game) throws ReflectiveOperationException {
        game.getTriggerHandler().runWaitingTriggers();
        Field field = game.getStack().getClass().getDeclaredField("simultaneousStackEntryList");
        field.setAccessible(true);
        List<SpellAbility> result = new ArrayList<>();
        for (Object entry : (List<?>) field.get(game.getStack())) {
            if (entry instanceof WrappedAbility wrapper
                    && wrapper.getTrigger().getMode() == TriggerType.BecomesMartyr) {
                result.add(wrapper.getWrappedAbility());
            }
        }
        return result;
    }
}
