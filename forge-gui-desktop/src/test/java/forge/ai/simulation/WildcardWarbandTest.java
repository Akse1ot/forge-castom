package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CardFactory;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import static org.testng.Assert.*;

public class WildcardWarbandTest extends AITest {
    private static final String ATTACK = "T:Mode$ Attacks | ValidCard$ Card.Self | Execute$ Pair | TriggerZones$ Battlefield";
    private static final String PAIR = "SVar:Pair:DB$ Bond | Defined$ TriggeredAttackerLKICopy"
            + " | ValidCards$ Creature.Other+YouCtrl+attacking+!Paired | PairUntilEndOfTurn$ True";

    @DataProvider
    public Object[][] backends() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "backends")
    public void wildcardPreservesCostAndChangesStraightInBothGames(boolean snapshot) {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card wild = wildcard(player);
        fixture(player, "One", "1", "Artifact");
        fixture(player, "Three", "3", "Artifact");
        SpellAbility ability = wild.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.ChooseNumber).findFirst().orElseThrow();
        ability.setActivatingPlayer(player);
        setValue(wild, ability, 0);
        assertEquals(wild.getCMC(), 0);
        assertEquals(wild.getManaCost().getShortString(), "1 {B}");
        assertTrue(wild.isBlack());
        assertEquals(AbilityUtils.calculateAmount(wild, "Count$Devotion.Black", ability), 1);
        assertEquals(AbilityUtils.calculateAmount(wild, "Count$LongestManaValueRun", ability), 2);
        assertTrue(wild.isValid("Creature.cmcEQ0", player, wild, ability));
        assertEquals(CardCopyService.getLKICopy(wild).getCMC(), 0);
        assertEquals(new CardCopyService(wild).copyCard(false).getCMC(), 2);

        Game copy = copy(game, snapshot);
        Card copied = copy.findById(wild.getId());
        assertEquals(copied.getCMC(), 0);
        cleanup(copy);
        assertEquals(copied.getCMC(), 2);
        assertEquals(wild.getCMC(), 0);
        var ai = ((PlayerControllerAi) player.getController()).getAi();
        assertEquals(ai.canPlaySa(ability), AiPlayDecision.WillPlay);
        assertEquals(ai.chooseNumber(ability, "", 0, 5), 2);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, ability, null));
        drain(game);
        assertEquals(AbilityUtils.calculateAmount(wild, "Count$LongestManaValueRun", ability), 3);
        assertEquals(ai.canPlaySa(ability), AiPlayDecision.CantPlayAi);

        for (int value = 0; value <= 5; value++) {
            setValue(wild, ability, value);
            assertEquals(wild.getCMC(), value);
        }
        Card exile = game.getAction().exile(wild, null, null);
        Card returned = game.getAction().moveToPlay(exile, null, null);
        game.getAction().checkStaticAbilities();
        assertEquals(returned.getCMC(), 2);
        cleanup(game);
        assertEquals(returned.getCMC(), 2);
    }

    @Test(dataProvider = "backends")
    public void pendingWarbandAndPairTriggersResolveInTheCopy(boolean snapshot) {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card brave = fighter(player, "Brave", "First Strike");
        Card chief = fighter(player, "Chief", "Trample");
        declare(game, brave, chief);
        assertEquals(game.getStack().size(), 2);
        Game pending = copy(game, snapshot);
        assertEquals(pending.getStack().size(), 2);
        pending.getStack().resolveStack();
        enqueue(pending);
        Card a = pending.findById(brave.getId());
        Card b = pending.findById(chief.getId());
        assertSame(a.getPairedWith(), b);
        Game bonuses = copy(pending, snapshot);
        drain(bonuses);
        assertBuffs(bonuses.findById(brave.getId()), bonuses.findById(chief.getId()));
        drain(pending);
        assertBuffs(a, b);
        Game resolved = copy(pending, snapshot);
        assertBuffs(resolved.findById(brave.getId()), resolved.findById(chief.getId()));
        cleanup(resolved);
        assertFalse(resolved.findById(brave.getId()).isPaired());
        assertFalse(resolved.findById(brave.getId()).hasKeyword("First Strike"));
        assertTrue(a.isPaired());
        assertFalse(brave.isPaired());
        drain(game);
        assertBuffs(brave, chief);
    }

    @Test
    public void karakwaAndJointAssaultSeeSoulbondAndWarband() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        fixture(player, "Karakwa observer", "3 R G", "Enchantment",
                "T:Mode$ BecomesPaired | ValidPlayer$ You | TriggerZones$ Battlefield | Execute$ Counters",
                "SVar:Counters:DB$ PutCounter | Defined$ TriggeredCardsLKICopy | CounterType$ P1P1 | CounterNum$ 1");
        Card paladin = addCard("Silverblade Paladin", player);
        refresh(game);
        Card wing = game.getAction().moveToPlay(addCardToZone("Wingcrafter", player, ZoneType.Hand), null, null);
        drain(game);
        assertSame(paladin.getPairedWith(), wing);
        assertEquals(wing.getCounters(CounterEnumType.P1P1), 1);
        Card brave = fighter(player, "Brave", "First Strike");
        Card partner = addCard("Grizzly Bears", player);
        declare(game, brave, partner);
        drain(game);
        assertSame(brave.getPairedWith(), partner);
        assertEquals(brave.getCounters(CounterEnumType.P1P1), 1);

        Card assault = addCardToZone("Joint Assault", player, ZoneType.Hand);
        SpellAbility sa = assault.getFirstSpellAbility();
        sa.setActivatingPlayer(player);
        sa.getTargets().add(brave);
        final int power = partner.getNetPower();
        AbilityUtils.resolve(sa);
        assertEquals(partner.getNetPower(), power + 2);

        var phases = game.getPhaseHandler();
        int turn = phases.getTurn();
        phases.devModeSet(PhaseType.COMBAT_END, player, true, turn);
        phases.addExtraPhase(PhaseType.COMBAT_END, PhaseType.PHASE_GROUPS.get(2), PhaseType.MAIN2);
        assertTrue(phases.devAdvanceToPhase(PhaseType.MAIN2, () -> drain(game)));
        assertTrue(brave.isPaired());
        cleanup(game);
        assertFalse(brave.isPaired());
        assertSame(paladin.getPairedWith(), wing);
    }

    @Test
    public void pairingAiDeclaresTwoAttackersWhenFirstStrikeMakesTheirAttackSafe() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card brave = fighter(player, "Brave", "First Strike");
        Card partner = addCard("Grizzly Bears", player);
        addCard("Grizzly Bears", player.getOpponents().getFirst());
        brave.setSickness(false);
        partner.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, player);
        Combat combat = new Combat(player);
        game.getPhaseHandler().setCombat(combat);
        refresh(game);
        player.getController().declareAttackers(player, combat);
        assertTrue(combat.isAttacking(brave) && combat.isAttacking(partner));
        assertTrue(CombatUtil.validateAttackers(combat));
    }

    @Test
    public void pairBreaksOnControlChangePhasingAndSimultaneousTypeLoss() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card brave = fighter(player, "Brave", "First Strike");
        Card partner = addCard("Grizzly Bears", player);
        declare(game, brave, partner);
        drain(game);
        partner.setController(player.getOpponents().getFirst(), game.getNextTimestamp());
        refresh(game);
        assertFalse(brave.isPaired());
        assertTrue(partner.hasKeyword("First Strike"));
        for (boolean snapshot : List.of(false, true)) {
            Game copied = copy(game, snapshot);
            assertTrue(copied.findById(partner.getId()).hasKeyword("First Strike"));
            assertFalse(copied.findById(partner.getId()).isPaired());
            cleanup(copied);
            assertFalse(copied.findById(partner.getId()).hasKeyword("First Strike"));
        }

        partner.setController(player, game.getNextTimestamp());
        refresh(game);
        declare(game, brave, partner);
        drain(game);
        partner.phase(false);
        assertFalse(brave.isPaired());

        partner.phase(false);
        declare(game, brave, partner);
        drain(game);
        Card transmuter = fixture(player, "Transmuter", "1", "Enchantment",
                "S:Mode$ Continuous | Affected$ Creature | RemoveType$ Creature | AddType$ Artifact");
        refresh(game);
        assertFalse(brave.isCreature());
        assertFalse(partner.isCreature());
        assertFalse(brave.isPaired());
        assertFalse(partner.isPaired());
        game.getAction().moveToGraveyard(transmuter, null);
        refresh(game);
    }

    @Test
    public void blinkInvalidatesAttackTriggerAndRepeatedCleanupClearsNewPair() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card brave = fighter(player, "Brave", "First Strike");
        Card partner = addCard("Grizzly Bears", player);
        declare(game, brave, partner);
        Card returned = game.getAction().moveToPlay(game.getAction().exile(brave, null, null), null, null);
        for (boolean snapshot : List.of(false, true)) {
            Game copied = copy(game, snapshot);
            drain(copied);
            assertFalse(copied.findById(brave.getId()).isPaired());
        }
        drain(game);
        assertFalse(returned.isPaired());

        declare(game, returned, partner);
        drain(game);
        assertTrue(returned.isPaired());
        AtomicInteger passes = new AtomicInteger();
        game.getEndOfTurn().addUntil(() -> {
            assertFalse(returned.isPaired());
            passes.incrementAndGet();
            game.getEndOfTurn().addUntil(passes::incrementAndGet);
        });
        fixture(player, "Cleanup fixture", "1", "Artifact",
                "T:Mode$ Phase | Phase$ Cleanup | TriggerZones$ Battlefield | ActivationLimit$ 1 | Execute$ Gain",
                "SVar:Gain:DB$ GainLife | Defined$ You | LifeAmount$ 1");
        refresh(game);
        var phases = game.getPhaseHandler();
        phases.devModeSet(PhaseType.END_OF_TURN, player, true, phases.getTurn());
        assertTrue(phases.devAdvanceThroughTurnEnd(PhaseType.UPKEEP, () -> drain(game)));
        assertEquals(passes.get(), 2);
        assertFalse(partner.isPaired());
    }

    @Test
    public void snapshotRestoreRebuildsEffectAndRestoresPendingPairTrigger() {
        Game game = initAndCreateGame();
        Player player = game.getPhaseHandler().getPlayerTurn();
        Card wild = wildcard(player);
        SpellAbility ability = wild.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.ChooseNumber).findFirst().orElseThrow();
        ability.setActivatingPlayer(player);
        setValue(wild, ability, 0);
        Card brave = fighter(player, "Brave", "First Strike");
        Card partner = addCard("Grizzly Bears", player);
        declare(game, brave, partner);
        GameSnapshot snapshot = new GameSnapshot(game);
        Game stored = snapshot.makeCopy();
        assertEquals(stored.findById(wild.getId()).getCMC(), 0);
        drain(game);
        assertTrue(brave.isPaired());
        cleanup(game);
        assertEquals(wild.getCMC(), 2);

        snapshot.restoreGameState(game);
        assertEquals(game.findById(wild.getId()).getCMC(), 0);
        assertEquals(game.getStack().size(), 1);
        drain(game);
        assertTrue(game.findById(brave.getId()).isPaired());
        cleanup(game);
        assertEquals(game.findById(wild.getId()).getCMC(), 2);
    }

    private Card wildcard(Player player) {
        return fixture(player, "Wildcard fixture", "1 B", "Creature Shapeshifter", "PT:2/2",
                "A:AB$ ChooseNumber | Cost$ 0 | Defined$ You | Min$ 0 | Max$ 5 | AILogic$ ImproveManaValueRun | SubAbility$ Change",
                "SVar:Change:DB$ Effect | RememberLKI$ Self | ExileOnMoved$ Battlefield | Duration$ EndOfTurn"
                        + " | CopyForSimulation$ True | StaticAbilities$ Value",
                "SVar:Value:Mode$ Continuous | EffectZone$ Command | AffectedZone$ Battlefield"
                        + " | Affected$ Card.IsRememberedIncarnation | SetManaValue$ Count$ChosenNumber");
    }

    private Card fighter(Player player, String name, String keyword) {
        return fixture(player, name, "2 R", "Creature Warrior", "PT:2/2", ATTACK, PAIR,
                "T:Mode$ BecomesPaired | ValidCard$ Card.StrictlySelf | Execute$ Buff | PairAttackBonus$ " + keyword,
                "SVar:Buff:DB$ Effect | Name$ " + name + " buff | RememberLKI$ TriggeredCardsLKICopy"
                        + " | Duration$ EndOfTurn | CopyForSimulation$ True | StaticAbilities$ Grant",
                "SVar:Grant:Mode$ Continuous | EffectZone$ Command | AffectedZone$ Battlefield"
                        + " | Affected$ Card.IsRememberedIncarnation | AddKeyword$ " + keyword);
    }

    private Card fixture(Player player, String name, String cost, String type, String... script) {
        List<String> lines = new ArrayList<>(List.of("Name:" + name, "ManaCost:" + cost, "Types:" + type));
        lines.addAll(List.of(script));
        lines.add("Oracle:");
        Game game = player.getGame();
        Card card = CardFactory.getCard(new PaperCard(CardRules.fromScript(lines), "M21", CardRarity.Common), player, game);
        card.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static void setValue(Card wild, SpellAbility ability, int value) {
        wild.setChosenNumber(value);
        ability.getSubAbility().setActivatingPlayer(ability.getActivatingPlayer());
        AbilityUtils.resolve(ability.getSubAbility());
        refresh(wild.getGame());
    }

    private static void declare(Game game, Card... attackers) {
        Player player = game.getPhaseHandler().getPlayerTurn();
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, player);
        Combat combat = new Combat(player);
        game.getPhaseHandler().setCombat(combat);
        for (Card card : attackers) {
            combat.addAttacker(card, player.getOpponents().getFirst());
        }
        refresh(game);
        for (Card card : attackers) {
            CombatUtil.checkDeclaredAttacker(game, card, combat, true);
        }
        enqueue(game);
    }

    private static Game copy(Game game, boolean snapshot) {
        Game copy = snapshot ? new GameSnapshot(game).makeCopy() : new GameCopier(game).withStack(true).makeCopy();
        refresh(copy);
        return copy;
    }

    private static void assertBuffs(Card first, Card second) {
        for (Card card : List.of(first, second)) {
            assertTrue(card.hasKeyword("First Strike"));
            assertTrue(card.hasKeyword("Trample"));
        }
    }

    private static void refresh(Game game) {
        game.getAction().checkStaticAbilities();
        game.getTriggerHandler().resetActiveTriggers();
        game.copyLastState();
    }

    private static void enqueue(Game game) {
        game.getStack().unfreezeStack();
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
    }

    private static void drain(Game game) {
        for (int i = 0; i < 30; i++) {
            enqueue(game);
            if (game.getStack().isEmpty()) {
                return;
            }
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(false);
        }
        fail("Stack did not drain");
    }

    private static void cleanup(Game game) {
        var phases = game.getPhaseHandler();
        phases.devModeSet(PhaseType.END_OF_TURN, phases.getPlayerTurn(), true, phases.getTurn());
        assertTrue(phases.devAdvanceToPhase(PhaseType.CLEANUP, () -> drain(game)));
        refresh(game);
    }
}
