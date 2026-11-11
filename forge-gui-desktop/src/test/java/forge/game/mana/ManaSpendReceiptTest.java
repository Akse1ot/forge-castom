package forge.game.mana;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPayManaOfCostPayment;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.player.PlayerControllerHuman;
import forge.util.collect.FCollectionView;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.testng.Assert.*;

public class ManaSpendReceiptTest extends AITest {
    @Test
    public void manualInputAndAutoConfirmExactlyOnce() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card land = addCard("Forest", player);
        SpellAbility spell = bearSpell(player);
        addGreen(player, land, 2);
        ManaCostBeingPaid cost = new ManaCostBeingPaid(spell.getPayCosts().getTotalMana());
        PaymentInput input = new PaymentInput(player, spell, cost);
        input.useManaFromPool(MagicColor.GREEN);
        assertEquals(player.getManaSpentThisTurn(), 0, "A partial payment is pending");
        assertTrue(ComputerUtilMana.payManaCost(cost, spell, player, false));
        input.finish();
        assertEquals(player.getManaSpentThisTurn(), 2);
        assertEquals(player.getExpentThisTurn(), 0);
        new ManaRefundService(spell).refundManaPaid();
        assertEquals(player.getManaSpentThisTurn(), 0);
        assertEquals(player.getManaPool().totalMana(), 2);
    }

    @Test
    public void unsuccessfulAutoAndProbesLeaveNoExpense() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card land = addCardToZone("Forest", player, ZoneType.Hand);
        SpellAbility spell = bearSpell(player);
        addGreen(player, land, 1);
        assertFalse(ComputerUtilMana.canPayManaCost(spell, player, 0, false));
        assertEquals(player.getManaSpentThisTurn(), 0);
        ManaCostBeingPaid cost = new ManaCostBeingPaid(spell.getPayCosts().getTotalMana());
        assertFalse(ComputerUtilMana.payManaCost(cost, spell, player, false));
        assertEquals(player.getManaSpentThisTurn(), 0);
        assertEquals(player.getManaPool().totalMana(), 1);
    }

    @Test
    public void assistAndMainPaymentAreAttributedToDifferentPlayers() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(1);
        Player assistant = game.getPlayers().get(0);
        SpellAbility spell = addCardToZone("Charging Binox", caster, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(caster);
        addGreen(assistant, addCard("Forest", assistant), 1);
        addGreen(caster, addCard("Forest", caster), 7);
        caster.dangerouslySetController(new PlayerControllerAi(game, caster, caster.getLobbyPlayer()) {
            @Override
            public Player choosePlayerToAssistPayment(FCollectionView<Player> candidates, SpellAbility sa,
                                                       String title, int max) {
                assertTrue(candidates.contains(assistant));
                return assistant;
            }
        });
        assistant.dangerouslySetController(new PlayerControllerAi(game, assistant, assistant.getLobbyPlayer()) {
            @Override
            public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
                var contribution = new ManaCostBeingPaid(ManaCost.get(1));
                assertTrue(ComputerUtilMana.payManaCost(contribution, sa, assistant, false));
                cost.decreaseGenericMana(1);
                return true;
            }
        });
        var mainCost = new ManaCostBeingPaid(spell.getPayCosts().getTotalMana());
        assertTrue(CostAdjustment.adjust(mainCost, spell, caster, null, false, false));
        assertTrue(ComputerUtilMana.payManaCost(mainCost, spell, caster, false));
        assertEquals(assistant.getManaSpentThisTurn(), 1);
        assertEquals(caster.getManaSpentThisTurn(), 7);
        new ManaRefundService(spell).refundManaPaid();
        assertEquals(assistant.getManaSpentThisTurn(), 0);
        assertEquals(caster.getManaSpentThisTurn(), 0);
        assertEquals(assistant.getManaPool().totalMana(), 1);
        assertEquals(caster.getManaPool().totalMana(), 7);
    }

    @Test
    public void countsIrreversibleNestedPaymentAfterOuterRefund() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCards("Swamp", 2, player);
        Card coffers = addCard("Cabal Coffers", player);
        SpellAbility mana = coffers.getManaAbilities().get(0);
        mana.setActivatingPlayer(player);
        assertTrue(ComputerUtil.playNoStack(player, mana, game, false));
        assertEquals(player.getManaSpentThisTurn(), 2);
        assertFalse(mana.isUndoable());
        SpellAbility spell = addCardToZone("Hymn to Tourach", player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        var cost = new ManaCostBeingPaid(spell.getPayCosts().getTotalMana());
        player.getManaPool().payManaFromAbility(spell, cost, mana);
        assertTrue(cost.isPaid());
        spell.confirmManaSpend(cost);
        assertEquals(player.getManaSpentThisTurn(), 4);
        new ManaRefundService(spell).refundManaPaid();
        assertEquals(player.getManaSpentThisTurn(), 2);
        assertTrue(coffers.isTapped());
    }

    @Test
    public void resolutionPaymentAndCopiesUseTheirOwnTurnTotals() {
        Game game = initAndCreateGame();
        Player payer = game.getPlayers().get(0);
        Player active = game.getPlayers().get(1);
        Card host = addCard("Forest", active);
        SpellAbility effect = AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1", host);
        effect.setActivatingPlayer(active);
        addGreen(payer, addCard("Forest", payer), 2);
        assertTrue(payer.getController().payCostToPreventEffect(new Cost("2", true), effect,
                false, new PlayerCollection(payer)));
        assertEquals(payer.getManaSpentThisTurn(), 2);
        assertEquals(payer.getExpentThisTurn(), 0);
        assertEquals(AbilityUtils.calculateAmount(host, "PlayerCountDefinedOpponent$ManaSpentThisTurn", effect), 2);
        for (Game copy : new Game[] {new GameSnapshot(game).makeCopy(null, false),
                new GameCopier(game).withStack(false).makeCopy()}) {
            Player copiedPayer = copy.getPlayer(payer.getId());
            assertEquals(copiedPayer.getManaSpentThisTurn(), 2);
            copy.getPhaseHandler().restart();
            assertEquals(copiedPayer.getManaSpentThisTurn(), 0);
            assertEquals(payer.getManaSpentThisTurn(), 2);
        }
        game.getPhaseHandler().devModeSet(PhaseType.CLEANUP, active, 1);
        game.getPhaseHandler().addExtraTurn(active);
        assertTrue(game.getPhaseHandler().devAdvanceThroughTurnEnd(PhaseType.UPKEEP, () -> {
            while (!game.getStack().isEmpty()) {
                game.getStack().resolveStack();
            }
        }));
        assertEquals(game.getPhaseHandler().getPlayerTurn(), active);
        assertEquals(payer.getManaSpentThisTurn(), 0);
    }

    @Test
    public void wealthyManChecksEightManaAtOpponentsEndStep() {
        for (int spent : new int[] {7, 8}) {
            Game game = initAndCreateGame();
            Player owner = game.getPlayers().get(1);
            Player active = game.getPlayers().get(0);
            Card land = addCard("Forest", owner);
            SpellAbility payment = bearSpell(owner);
            addGreen(owner, land, spent);
            assertTrue(ComputerUtilMana.payManaCost(new ManaCostBeingPaid(ManaCost.get(spent)), payment, owner, false));
            var rules = CardRules.fromScript(List.of("Name:Wealthy Man", "ManaCost:1 G", "Types:Creature Human Citizen",
                    "PT:2/2", "AlternateMode:DoubleFaced", "Oracle:",
                    "T:Mode$ Phase | Phase$ End of Turn | TriggerZones$ Battlefield | CheckSVar$ Spend | SVarCompare$ GE8 | Execute$ Turn",
                    "SVar:Spend:PlayerCountPropertyYou$ManaSpentThisTurn", "SVar:Turn:DB$ SetState | Mode$ Transform",
                    "ALTERNATE", "Name:Worldly Man", "ManaCost:no cost", "Colors:green",
                    "Types:Creature Human Warrior", "PT:2/2", "Oracle:"));
            Card card = Card.fromPaperCard(new PaperCard(rules, "TEST", CardRarity.Uncommon), owner);
            card.setGameTimestamp(game.getNextTimestamp());
            owner.getZone(ZoneType.Battlefield).add(card);
            game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, active);
            game.getAction().checkStaticAbilities();
            game.getTriggerHandler().resetActiveTriggers();
            game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(active), false);
            game.getStack().unfreezeStack();
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.getStack().isEmpty()) {
                game.getStack().resolveStack();
            }
            assertEquals(card.isBackSide(), spent == 8);
        }
    }

    private SpellAbility bearSpell(Player player) {
        SpellAbility spell = addCardToZone("Bear Cub", player, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        return spell;
    }

    private static void addGreen(Player player, Card source, int amount) {
        for (int i = 0; i < amount; i++) {
            player.getManaPool().addMana(new Mana(MagicColor.GREEN, source, null, player));
        }
    }

    private static class PaymentInput extends InputPayManaOfCostPayment {
        PaymentInput(Player player, SpellAbility spell, ManaCostBeingPaid cost) {
            super(humanController(player), cost, spell, player, null, false);
        }

        @Override
        public void showMessage() {
            // Drive the same input's completion directly so this test does not need the desktop event loop.
        }

        void finish() {
            done();
            onStop();
        }

        private static PlayerControllerHuman humanController(Player player) {
            var human = new PlayerControllerHuman(player.getGame(), player, player.getLobbyPlayer());
            human.setGui((IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(),
                    new Class<?>[] {IGuiGame.class}, (proxy, method, args) -> null));
            return human;
        }
    }
}
