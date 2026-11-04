package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Predicate;

import static org.testng.Assert.*;

public class CnvlCopyTargetsTest extends AITest {
    @Test
    public void realHumanTargetSelectionFiltersTheForbiddenCardBeforeAutoSelection() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCardToZone("Frostscale Monster", player, ZoneType.Graveyard);
        Card alternative = addCardToZone("Grizzly Bears", player, ZoneType.Graveyard);
        PlayerControllerHuman human = new PlayerControllerHuman(game, player, player.getLobbyPlayer());
        player.dangerouslySetController(human);
        human.setGui((forge.gui.interfaces.IGuiGame) java.lang.reflect.Proxy.newProxyInstance(
                forge.gui.interfaces.IGuiGame.class.getClassLoader(),
                new Class<?>[] {forge.gui.interfaces.IGuiGame.class}, (proxy, method, args) -> {
                    throw new AssertionError("Unexpected GUI call: " + method.getName());
                }));
        SpellAbility original = damage(game, monster, "DB", "1");
        original = AbilityFactory.getAbility("DB$ DealDamage | Cost$ 0 | ValidTgts$ Creature"
                + " | TgtZone$ Graveyard | NumDmg$ 1", original.getHostCard());
        original.setActivatingPlayer(game.getPlayers().get(0));
        original.setTrigger(TriggerHandler.parseTrigger("Mode$ Phase | Phase$ Upkeep", original.getHostCard(), true));
        original.getTargets().add(monster);
        assertEquals(copy(monster, original).getTargets().getFirstTargetedCard(), alternative);
    }

    @Test
    public void replacementAddedCopiesAlsoCarryTheNativeForbiddenTargetFilter() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        addCard("Twinning Staff", player);
        Card enemy = addCard("Grizzly Bears", game.getPlayers().get(0));
        SpellAbility original = damage(game, monster, "SP", "2");
        original.getTargets().add(monster);
        game.getAction().checkStaticAbilities();
        game.getTriggerHandler().resetActiveTriggers();
        copy(monster, original);
        assertEquals(game.getStack().size(), 2);
        for (forge.game.spellability.SpellAbilityStackInstance entry : game.getStack()) {
            SpellAbility copied = entry.getSpellAbility();
            assertNotNull(copied.getForbiddenNewTargetsFilter());
            assertFalse(copied.getForbiddenNewTargetsFilter().test(monster));
            assertEquals(copied.getTargets().getFirstTargetedCard(), enemy);
            assertNull(copied.copy(player).getForbiddenNewTargetsFilter(), "Copy restrictions must not leak into later copies");
        }
    }

    @DataProvider
    public Object[][] sourceKinds() {
        return new Object[][] {{"SP"}, {"AB"}, {"DB"}};
    }

    @Test(dataProvider = "sourceKinds")
    public void humanCopyRetargetsSpellsActivatedAndTriggeredAbilities(String kind) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        Card target = addCard("Grizzly Bears", game.getPlayers().get(0));
        SpellAbility original = damage(game, monster, kind, "1");
        original.getTargets().add(monster);
        if (kind.equals("DB")) {
            original.setTrigger(TriggerHandler.parseTrigger("Mode$ Phase | Phase$ Upkeep", original.getHostCard(), true));
            original = new forge.game.trigger.WrappedAbility(original.getTrigger(), original, original.getActivatingPlayer());
        }
        scriptedHuman(player, monster, List.of(List.of(target)), false);
        SpellAbility copied = copy(monster, original);
        assertTrue(copied.isCopied());
        assertEquals(copied.isSpell(), kind.equals("SP"));
        assertEquals(copied.getTargets().getFirstTargetedCard(), target);
        assertEquals(original.getTargets().getFirstTargetedCard(), monster);
        game.getStack().resolveStack();
        assertEquals(target.getDamage(), 1);
        assertEquals(monster.getDamage(), 0);
    }

    @Test
    public void humanCanChangeBothTargetsIndependentlyKeepingXAndDivision() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        Card old = addCard("Grizzly Bears", player);
        Card first = addCard("Grizzly Bears", game.getPlayers().get(0));
        Card second = addCard("Grizzly Bears", game.getPlayers().get(0));
        SpellAbility original = damage(game, monster, "SP", "X");
        original.getHostCard().setSVar("X", "Count$xPaid");
        original = AbilityFactory.getAbility("SP$ DealDamage | Cost$ 0 | ValidTgts$ Creature | TargetMax$ 2 | NumDmg$ X | DividedAsYouChoose$ X", original.getHostCard());
        original.setActivatingPlayer(game.getPlayers().get(0));
        original.setXManaCostPaid(7);
        original.getTargets().add(monster);
        original.getTargets().add(old);
        original.addDividedAllocation(monster, 2);
        original.addDividedAllocation(old, 5);
        scriptedHuman(player, monster, List.of(List.of(first, second)), false);
        SpellAbility copied = copy(monster, original);
        assertEquals(copied.getXManaCostPaid(), Integer.valueOf(7));
        assertEquals(new ArrayList<>(copied.getTargets()), List.of(first, second));
        assertEquals(copied.getTargets().getDividedValue(first), Integer.valueOf(2));
        assertEquals(copied.getTargets().getDividedValue(second), Integer.valueOf(5));
        assertEquals(original.getTargets().getTotalDividedValue(), 7);
    }

    @Test
    public void chosenModesAndTheirSeparateTargetClausesAreCopied() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        Card a = addCard("Grizzly Bears", player);
        Card b = addCard("Grizzly Bears", game.getPlayers().get(0));
        Card host = createCard("Prismari Command", game.getPlayers().get(0));
        game.getStackZone().add(host);
        SpellAbility original = host.getFirstSpellAbility();
        original.setActivatingPlayer(host.getOwner());
        List<AbilitySub> modes = original.getAdditionalAbilityList("Choices");
        AbilitySub damage = (AbilitySub) modes.get(0).copy(player);
        AbilitySub draw = (AbilitySub) modes.get(1).copy(player);
        original.setChosenList(List.of(damage, draw));
        original.setSubAbility(damage);
        damage.setSubAbility(draw);
        damage.getTargets().add(monster);
        draw.getTargets().add(player);
        scriptedHuman(player, monster, List.of(List.of(b), List.of(a.getController())), false);
        SpellAbility copied = copy(monster, original);
        assertEquals(copied.getChosenList().size(), 2);
        assertEquals(copied.getSubAbility().getApi(), damage.getApi());
        assertEquals(copied.getSubAbility().getSubAbility().getApi(), draw.getApi());
        assertEquals(copied.getSubAbility().getTargets().getFirstTargetedCard(), b);
        assertEquals(copied.getSubAbility().getSubAbility().getTargets().getFirstTargetedPlayer(), player);
    }

    @Test
    public void decliningRetargetKeepsTheOriginalAsAnAcceptedNativeLimitation() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        SpellAbility original = damage(game, monster, "SP", "1");
        original.getTargets().add(monster);
        scriptedHuman(player, monster, List.of(), true);
        assertEquals(copy(monster, original).getTargets().getFirstTargetedCard(), monster);
    }

    @Test(dataProvider = "sourceKinds")
    public void aiAcceptsUsefulDefinedCopyAndChoosesAnOpposingTarget(String kind) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        Card enemy = addCard("Grizzly Bears", game.getPlayers().get(0));
        SpellAbility original = damage(game, monster, kind, "2");
        if (kind.equals("DB")) {
            original = new forge.game.trigger.WrappedAbility(
                    TriggerHandler.parseTrigger("Mode$ Phase | Phase$ Upkeep", original.getHostCard(), true), original, null);
        }
        original.getTargets().add(monster);
        SpellAbility action = copyAction(monster, original);
        assertTrue(((PlayerControllerAi) player.getController()).getAi().doTrigger(action, false));
        AbilityUtils.resolve(action);
        SpellAbility copied = game.getStack().peekAbility();
        assertEquals(copied.getTargets().getFirstTargetedCard(), enemy);
        assertEquals(original.getTargets().getFirstTargetedCard(), monster);
    }

    @Test
    public void aiDeclinesAnOptionalCopyWithOnlyItsOwnCreatureToDamage() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        SpellAbility original = damage(game, monster, "SP", "2");
        original.getTargets().add(monster);
        assertFalse(((PlayerControllerAi) player.getController()).getAi().doTrigger(copyAction(monster, original), false));
    }

    @Test
    public void aiDoesNotInstallAForbiddenNewTargetWhenItFallsBack() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        Card old = addCard("Grizzly Bears", game.getPlayers().get(0));
        SpellAbility original = AbilityFactory.getAbility("AB$ Pump | Cost$ 0 | ValidTgts$ Creature | NumAtt$ 3 | NumDef$ 3", old);
        original.setActivatingPlayer(old.getController());
        original.getTargets().add(old);
        SpellAbility copied = copy(monster, original);
        assertEquals(copied.getTargets().getFirstTargetedCard(), old,
                "AI may restore the old target, but may not install Monster as a new target");
        SpellAbility unrestricted = copyAction(monster, original);
        unrestricted.removeParam("NewTargetCantBe");
        AbilityUtils.resolve(unrestricted);
        SpellAbility ordinaryCopy = game.getStack().peekAbility();
        assertNull(ordinaryCopy.getForbiddenNewTargetsFilter());
        assertEquals(ordinaryCopy.getTargets().getFirstTargetedCard(), monster,
                "An ordinary copy without NewTargetCantBe must retain native targeting behavior");
    }

    @DataProvider
    public Object[][] permanents() {
        return new Object[][] {{"Rancor", true}, {"Grizzly Bears", false}};
    }

    @Test(dataProvider = "permanents")
    public void permanentSpellCopyResolvesAsTokenIncludingAura(String name, boolean aura) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card monster = addCard("Frostscale Monster", player);
        Card target = addCard("Grizzly Bears", player);
        Card host = createCard(name, game.getPlayers().get(0));
        game.getStackZone().add(host);
        SpellAbility original = host.getFirstSpellAbility();
        original.setActivatingPlayer(host.getOwner());
        if (aura) {
            original.getTargets().add(monster);
        }
        scriptedHuman(player, monster, aura ? List.of(List.of(target)) : List.of(), false);
        SpellAbility copied = copy(monster, original);
        int id = copied.getHostCard().getId();
        game.getStack().resolveStack();
        Card token = game.findById(id);
        assertNotNull(token);
        assertTrue(token.isInZone(ZoneType.Battlefield));
        assertTrue(token.isToken());
        if (aura) {
            assertEquals(token.getEntityAttachedTo(), target);
        }
        assertTrue(host.isInZone(ZoneType.Stack));
    }

    private SpellAbility damage(Game game, Card monster, String kind, String amount) {
        Card host = createCard("Shock", game.getPlayers().get(0));
        if (kind.equals("SP")) {
            game.getStackZone().add(host);
        } else {
            host.getOwner().getZone(ZoneType.Battlefield).add(host);
        }
        SpellAbility original = AbilityFactory.getAbility(kind + "$ DealDamage | Cost$ 0 | ValidTgts$ Creature | NumDmg$ " + amount, host);
        original.setActivatingPlayer(host.getOwner());
        return original;
    }

    private SpellAbility copyAction(Card monster, SpellAbility original) {
        SpellAbility action = AbilityFactory.getAbility(monster.getSVar("TrigCopy"), monster);
        action.setActivatingPlayer(monster.getController());
        action.setTriggeringObject(AbilityKey.SourceSA, original);
        return action;
    }

    private SpellAbility copy(Card monster, SpellAbility original) {
        AbilityUtils.resolve(copyAction(monster, original));
        return monster.getGame().getStack().peekAbility();
    }

    // Exercise the real human ordering/setupNewTargets path with deterministic choices at its UI boundary.
    private void scriptedHuman(Player player, Card monster, List<List<? extends GameObject>> choices, boolean decline) {
        Queue<List<? extends GameObject>> queue = new ArrayDeque<>(choices);
        player.dangerouslySetController(new PlayerControllerHuman(player.getGame(), player, player.getLobbyPlayer()) {
            @Override
            public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
                assertNotNull(filter);
                assertFalse(filter.test(monster));
                if (decline) {
                    return null;
                }
                List<? extends GameObject> targets = queue.remove();
                List<Integer> allocations = new ArrayList<>(ability.getTargets().getDividedValues());
                ability.clearTargets();
                for (int i = 0; i < targets.size(); i++) {
                    GameObject target = targets.get(i);
                    assertTrue(filter.test(target));
                    assertTrue(ability.canTarget(target));
                    ability.getTargets().add(target);
                    if (!allocations.isEmpty()) {
                        ability.addDividedAllocation(target, allocations.get(i));
                    }
                }
                return ability.getTargets();
            }
        });
    }
}
