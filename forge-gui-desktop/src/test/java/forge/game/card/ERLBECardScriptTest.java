package forge.game.card;

import forge.ai.AITest;
import forge.ai.AiCostDecision;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.PlayerControllerForTests;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

/** Reads the ERLB/e scripts directly, including modal faces, without namesake lookup. */
public class ERLBECardScriptTest extends AITest {
    private Path scripts() {
        return Path.of(ForgeConstants.CARD_DATA_DIR, "custom cards", "ERLB", "e");
    }

    private Card load(String filename, Player player, ZoneType zone) throws IOException {
        CardRules rules = CardRules.fromScript(Files.readAllLines(scripts().resolve(filename), StandardCharsets.UTF_8)
                .stream().filter(line -> !line.isBlank()).collect(Collectors.toList()));
        Card card = CardFactory.getCard(new PaperCard(rules, "ERLB", CardRarity.Rare), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        if (zone == ZoneType.Stack) {
            player.getGame().getStackZone().add(card);
        } else {
            player.getZone(zone).add(card);
        }
        return card;
    }

    private SpellAbility named(Card card, String name, Player player) {
        SpellAbility ability = AbilityFactory.getAbility(card.getSVar(name), card);
        ability.setActivatingPlayer(player);
        return ability;
    }

    @Test
    public void allNineScriptsAndTheirFacesInstantiate() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        try (var files = Files.list(scripts())) {
            var paths = files.filter(p -> p.toString().endsWith(".txt")).collect(Collectors.toList());
            assertEquals(paths.size(), 9);
            for (Path path : paths) {
                // Reject the broken physical Oracle continuation that Reader would silently ignore.
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    assertTrue(line.isBlank() || line.equals("ALTERNATE") || line.startsWith("#")
                            || line.contains(":"), path + ": " + line);
                }
                Card card = load(path.getFileName().toString(), player, ZoneType.Hand);
                for (var stateName : card.getStates()) {
                    var state = card.getState(stateName);
                    for (var trigger : state.getTriggers()) {
                        trigger.ensureAbility();
                    }
                    for (var replacement : state.getReplacementEffects()) {
                        replacement.ensureAbility();
                    }
                }
            }
        }
    }

    @DataProvider
    public Object[][] otherDragons() {
        return new Object[][] {{0}, {1}, {2}};
    }

    @Test(dataProvider = "otherDragons")
    public void greyollCountsOnlyOtherDragons(int others) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card greyoll = load("Elder_Dragon_Greyoll_Greyolls_Roar.txt", player, ZoneType.Battlefield);
        addCards("Shivan Dragon", others, player);
        assertEquals(AbilityUtils.calculateAmount(greyoll, "Y", greyoll.getFirstSpellAbility()), others * 2);
        if (others > 0) {
            game.getAction().checkStateEffects(true);
            assertEquals(greyoll.getNetPower(), others * 2);
            assertEquals(greyoll.getNetToughness(), others * 2);
        }
    }

    @DataProvider
    public Object[][] starsEvents() {
        return new Object[][] {{true, 0}, {true, 2}, {false, 0}, {false, 2}};
    }

    @Test(dataProvider = "starsEvents")
    public void eldenStarsDamagesTheAttackerOrBlockerAndGainsLife(boolean attacking, int counters) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        player.dangerouslySetController(new PlayerControllerForTests(game, player, player.getLobbyPlayer()) {
            @Override
            public int chooseNumber(SpellAbility sa, String title, int min, int max) {
                return max;
            }
        });
        Card stars = load("Elden_Stars_Marikas_Minor_Erdtree.txt", player, ZoneType.Battlefield);
        Card first = addCard("Runeclaw Bear", player);
        Card second = addCard("Runeclaw Bear", player);
        first.setCounters(CounterEnumType.P1P1, counters);
        second.setCounters(CounterEnumType.P1P1, counters);
        Card enemy = addCard("Giant Spider", game.getPlayers().get(0));
        Trigger trigger = stars.getTriggers().stream().filter(t -> t.getMode()
                == (attacking ? TriggerType.Attacks : TriggerType.Blocks)).findFirst().orElseThrow();
        SpellAbility ability = named(stars, trigger.getParam("Execute"), player);
        Map<AbilityKey, Object> event = AbilityKey.newMap();
        event.put(attacking ? AbilityKey.Attacker : AbilityKey.Blocker, enemy);
        trigger.setTriggeringObjects(ability, event);
        int life = player.getLife();
        AbilityUtils.resolve(ability);
        assertEquals(enemy.getDamage(), 2 * counters);
        assertEquals(player.getLife(), life + 2 * counters);
        assertEquals(first.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(second.getCounters(CounterEnumType.P1P1), 0);
        assertFalse(stars.getRemembered().iterator().hasNext());
    }

    @Test
    public void watchdogUsesTheActivatedAbilityEventRatherThanTargetEvent() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card watchdog = load("Erdtree_Burial_Watchdog.txt", player, ZoneType.Battlefield);
        Card skeleton = addCardToZone("Reassembling Skeleton", opponent, ZoneType.Graveyard);
        SpellAbility activation = skeleton.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.ChangeZone).findFirst().orElseThrow();
        activation.setActivatingPlayer(opponent);
        Trigger trigger = watchdog.getTriggers().stream().filter(t -> t.getMode() == TriggerType.AbilityCast)
                .findFirst().orElseThrow();
        Map<AbilityKey, Object> event = AbilityKey.newMap();
        event.put(AbilityKey.SpellAbility, activation);
        event.put(AbilityKey.Activator, opponent);
        assertTrue(trigger.performTest(event));
        SpellAbility counter = named(watchdog, trigger.getParam("Execute"), player);
        trigger.setTriggeringObjects(counter, event);
        assertEquals(AbilityUtils.getDefinedSpellAbilities(watchdog, counter.getParam("Defined"), counter),
                java.util.List.of(activation));
        assertEquals(AbilityUtils.getDefinedPlayers(watchdog, counter.getParam("UnlessPayer"), counter),
                java.util.List.of(opponent));
        game.getAction().moveToPlay(skeleton, opponent, null, AbilityKey.newMap());
        assertFalse(trigger.performTest(event));
    }

    @DataProvider
    public Object[][] favorTokens() {
        return new Object[][] {{true, "c_a_blood_draw"}, {false, "c_a_blood_draw"}, {true, "c_a_Rune_sac"}};
    }

    @Test(dataProvider = "favorTokens")
    public void erdtreesFavorAddsOneRuneOnlyToItsControllersTokenEvent(boolean own, String script) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card favor = load("Erdtrees_Favor.txt", player, ZoneType.Battlefield);
        Player creator = own ? player : game.getPlayers().get(0);
        SpellAbility create = AbilityFactory.getAbility("DB$ Token | TokenScript$ " + script + " | TokenAmount$ 2", favor);
        create.setActivatingPlayer(creator);
        AbilityUtils.resolve(create);
        long runes = creator.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getType().hasSubtype("Rune")).count();
        assertEquals(runes, (script.equals("c_a_Rune_sac") ? 2L : 0L) + (own ? 1 : 0));
        assertEquals(creator.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count(), own ? 3L : 2L);
    }

    @Test
    public void eternalDarknessAllowsBothModesAndReturnsAnOwnedGraveyardCard() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card darkness = load("Eternal_Darkness.txt", player, ZoneType.Hand);
        assertEquals(darkness.getFirstSpellAbility().getParam("MinCharmNum"), "1");
        assertEquals(darkness.getFirstSpellAbility().getParam("CharmNum"), "2");
        Card own = addCardToZone("Lightning Bolt", player, ZoneType.Graveyard);
        Card foreign = addCardToZone("Lightning Bolt", game.getPlayers().get(0), ZoneType.Graveyard);
        SpellAbility recover = named(darkness, "DBReturn", player);
        assertTrue(recover.canTarget(own));
        assertFalse(recover.canTarget(foreign));
        recover.getTargets().add(own);
        AbilityUtils.resolve(recover);
        assertTrue(game.findById(own.getId()).isInZone(ZoneType.Hand));
    }

    @Test
    public void euporiaPaysAllEquippedCountersAndUsesTheCreatureAsDamageSource() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card euporia = load("Euporia.txt", player, ZoneType.Battlefield);
        Card equipped = addCard("Vampire Nighthawk", player);
        euporia.attachToEntity(equipped, null);
        equipped.setCounters(CounterEnumType.P1P1, 4);
        euporia.setCounters(CounterEnumType.P1P1, 7);
        SpellAbility damage = euporia.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.DealDamage)
                .findFirst().orElseThrow();
        damage.setActivatingPlayer(player);
        CostRemoveCounter cost = damage.getPayCosts().getCostParts().stream().filter(CostRemoveCounter.class::isInstance)
                .map(CostRemoveCounter.class::cast).findFirst().orElseThrow();
        assertEquals(cost.getAbilityAmount(damage), 4);
        PaymentDecision decision = new AiCostDecision(player, damage, false).visit(cost);
        assertNotNull(decision);
        assertTrue(cost.payAsDecided(player, decision, damage, false));
        assertEquals(equipped.getCounters(CounterEnumType.P1P1), 0);
        assertEquals(euporia.getCounters(CounterEnumType.P1P1), 7);
        Card enemy = addCard("Giant Spider", game.getPlayers().get(0));
        damage.getTargets().add(enemy);
        int life = player.getLife();
        AbilityUtils.resolve(damage);
        assertEquals(enemy.getDamage(), 4);
        assertEquals(player.getLife(), life + 4, "The equipped creature's lifelink must apply");
    }

    @Test
    public void runeProducersCreateTheExpectedTokensAndMarikaAddsManaAndLife() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card avatar = load("Erdtree_Avatar.txt", player, ZoneType.Battlefield);
        AbilityUtils.resolve(named(avatar, "TrigToken", player));
        assertEquals(player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count(), 2L);
        Card stars = load("Elden_Stars_Marikas_Minor_Erdtree.txt", player, ZoneType.Hand);
        stars.setState(stars.getAlternateState().getStateName(), true);
        int life = player.getLife();
        int mana = player.getManaPool().totalMana();
        AbilityUtils.resolve(named(stars, "TrigGainLife", player));
        assertEquals(player.getLife(), life + 1);
        assertEquals(player.getManaPool().totalMana(), mana + 1);
        assertEquals(player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count(), 3L);
    }

    @Test
    public void evergaolReturnsTheExiledCreatureWhenItLeaves() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card gaol = load("Evergaol.txt", player, ZoneType.Battlefield);
        Card creature = addCard("Runeclaw Bear", player);
        SpellAbility exile = named(gaol, "TrigExile", player);
        assertTrue(exile.canTarget(creature), "Oracle permits either player's creature");
        exile.getTargets().add(creature);
        AbilityUtils.resolve(exile);
        assertTrue(game.findById(creature.getId()).isInZone(ZoneType.Exile));
        game.getAction().moveToGraveyard(gaol, null);
        assertTrue(game.findById(creature.getId()).isInPlay());
    }
}
