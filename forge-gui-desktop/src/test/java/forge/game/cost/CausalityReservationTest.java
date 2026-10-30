package forge.game.cost;

import forge.ai.AITest;
import forge.ai.AiCostDecision;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

/** Reserved materials cannot pay another zone-change cost, even if a chooser ignores the flag. */
public class CausalityReservationTest extends AITest {
    private record Fixture(Game game, Player player, SpellAbility spell, Card material, Card spare) { }

    private Card artifact(Player player, ZoneType zone, String name) {
        Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(List.of(
                "Name:" + name, "ManaCost:1", "Types:Artifact", "Oracle:Test artifact.")),
                "TEST", CardRarity.Common), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }

    private Fixture fixture(boolean withSpare) {
        Game game = initAndCreateGame();
        game.getPlayers().forEach(player -> fillLibrary(player, 12));
        Player player = game.getPlayers().get(1);
        Card host = Card.fromPaperCard(new PaperCard(CardRules.fromScript(List.of(
                "Name:Reservation test", "ManaCost:4 W", "Types:Instant",
                "A:SP$ ChangeZone | Cost$ 4 W ExileFromGrave<1/Permanent/ExileTag=Causality>"
                + " ExileFromGrave<1/Artifact/ExileTag=Other> | CausalityCost$ True"
                + " | ValidTgts$ Permanent.nonLand+sharesCausalityCostType"
                + " | Origin$ Battlefield | Destination$ Exile | SpellDescription$ Exile target permanent.",
                "Oracle:Exile target permanent.")), "TEST", CardRarity.Common), player);
        host.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Hand).add(host);
        SpellAbility spell = host.getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        Card material = artifact(player, ZoneType.Graveyard, "Reserved");
        Card spare = withSpare ? artifact(player, ZoneType.Graveyard, "Spare") : null;
        Card target = artifact(game.getPlayers().get(0), ZoneType.Battlefield, "Target");
        assertTrue(CausalityCostHelper.select(spell, List.of(material)));
        spell.getTargets().add(target);
        for (byte color : new byte[] {MagicColor.COLORLESS, MagicColor.COLORLESS,
                MagicColor.COLORLESS, MagicColor.WHITE}) {
            player.getManaPool().addMana(new Mana(color, host, null, player));
        }
        return new Fixture(game, player, spell, material, spare);
    }

    private boolean pay(CostPayment payment, AiCostDecision choices, boolean computer) {
        return computer ? payment.payComputerCosts(choices) : payment.payCost(choices);
    }

    private void assertReleased(Fixture f) {
        assertFalse(f.material().isReservedForZoneChangePayment());
        assertFalse(f.game().findById(f.material().getId()).isReservedForZoneChangePayment());
    }

    @DataProvider
    public Object[][] paths() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "paths")
    public void otherExileChoosesTheSpareInsteadOfTheReservedMaterial(boolean computer) {
        Fixture f = fixture(true);
        CostPayment payment = new CostPayment(f.spell().getPayCosts(), f.spell());
        assertTrue(pay(payment, new AiCostDecision(f.player(), f.spell(), false) {
            @Override
            public PaymentDecision visit(CostExile cost) {
                assertEquals(cost.getExileTag(), "Other");
                assertTrue(f.material().isReservedForZoneChangePayment());
                assertFalse(f.material().canExiledBy(f.spell(), false));
                PaymentDecision result = super.visit(cost);
                assertNotNull(result);
                assertEquals(result.cards.size(), 1);
                assertEquals(result.cards.getFirst().getId(), f.spare().getId());
                return result;
            }
        }, computer));
        assertEquals(f.spell().getPaidList("ExiledCost_Causality", true).getFirst().getId(), f.material().getId());
        assertEquals(f.spell().getPaidList("ExiledCost_Other", true).getFirst().getId(), f.spare().getId());
        assertTrue(f.spell().getCausalitySelection().paid());
        assertReleased(f);
    }

    @Test(dataProvider = "paths")
    public void insufficientUnreservedCardsFailBeforeAnyPayment(boolean computer) {
        Fixture f = fixture(false);
        CostPayment payment = new CostPayment(f.spell().getPayCosts(), f.spell());
        assertFalse(pay(payment, new AiCostDecision(f.player(), f.spell(), false), computer));
        assertTrue(f.material().isInZone(ZoneType.Graveyard));
        assertEquals(f.player().getManaPool().totalMana(), 4);
        assertNull(f.spell().getCausalitySelection());
        assertReleased(f);
    }

    @Test(dataProvider = "paths")
    public void anOverlappingDecisionIsRejectedBeforeTheOtherExileExecutes(boolean computer) {
        Fixture f = fixture(true);
        CostPayment payment = new CostPayment(f.spell().getPayCosts(), f.spell());
        assertFalse(pay(payment, new AiCostDecision(f.player(), f.spell(), false) {
            @Override
            public PaymentDecision visit(CostExile cost) {
                return PaymentDecision.card(f.material());
            }
        }, computer));
        payment.refundPayment();
        assertTrue(f.material().isInZone(ZoneType.Graveyard));
        assertTrue(f.spare().isInZone(ZoneType.Graveyard));
        assertTrue(f.spell().getPaidHash().row("ExiledCost_Other").isEmpty());
        assertNull(f.spell().getCausalitySelection());
        assertReleased(f);
    }

    @Test(dataProvider = "paths")
    public void changingTheSelectionInsideAnotherChoiceCannotChangeThePaidCards(boolean computer) {
        Fixture f = fixture(true);
        CostPayment payment = new CostPayment(f.spell().getPayCosts(), f.spell());
        assertFalse(pay(payment, new AiCostDecision(f.player(), f.spell(), false) {
            @Override
            public PaymentDecision visit(CostExile cost) {
                assertTrue(CausalityCostHelper.select(f.spell(), List.of(f.spare())));
                return PaymentDecision.card(f.spare());
            }
        }, computer));
        payment.refundPayment();
        assertTrue(f.material().isInZone(ZoneType.Graveyard));
        assertTrue(f.spare().isInZone(ZoneType.Graveyard));
        assertNull(f.spell().getCausalitySelection());
        assertReleased(f);
    }

    @Test(dataProvider = "paths")
    public void chooserExceptionsReleaseOnlyThisPaymentReservation(boolean computer) {
        Fixture f = fixture(true);
        CostPayment payment = new CostPayment(f.spell().getPayCosts(), f.spell());
        try {
            pay(payment, new AiCostDecision(f.player(), f.spell(), false) {
                @Override
                public PaymentDecision visit(CostExile cost) {
                    throw new IllegalStateException("Injected choice failure");
                }
            }, computer);
            fail("The injected exception must propagate");
        } catch (IllegalStateException expected) {
            assertEquals(expected.getMessage(), "Injected choice failure");
        }
        payment.refundPayment();
        assertTrue(f.material().isInZone(ZoneType.Graveyard));
        assertTrue(f.spare().isInZone(ZoneType.Graveyard));
        assertNull(f.spell().getCausalitySelection());
        assertReleased(f);
    }
}
