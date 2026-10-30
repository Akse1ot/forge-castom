package forge.game.cost;

import forge.ai.AITest;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardFactory;
import forge.game.mana.Mana;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

/** Exercises stock payment, cancellation, copying and non-failing payment completion. */
public class CausalityCostTest extends AITest {
    private record Fixture(Game game, Player player, Player opponent, SpellAbility spell) { }

    private Card card(Player player, ZoneType zone, String name, String types) {
        List<String> script = new ArrayList<>(List.of("Name:" + name, "ManaCost:1", "Types:" + types));
        if (types.contains("Creature")) {
            script.add("PT:2/2");
        }
        Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(script),
                "TEST", CardRarity.Common), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }

    private Fixture fixture() {
        Game game = initAndCreateGame();
        game.getPlayers().forEach(player -> fillLibrary(player, 12));
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        CardRules rules = CardRules.fromScript(List.of(
                "Name:Causality test", "ManaCost:4 W", "Types:Instant",
                "A:SP$ ChangeZone | Cost$ 4 W ExileFromGrave<1/Permanent/permanent cards/ExileTag=Causality>"
                + " | CausalityCost$ True | AILogic$ Causality"
                + " | ValidTgts$ Permanent.nonLand+sharesCausalityCostType"
                + " | Origin$ Battlefield | Destination$ Exile"
                + " | SpellDescription$ Exile target nonland permanent.",
                "Oracle:Exile target nonland permanent."));
        Card host = Card.fromPaperCard(new PaperCard(rules, "TEST", CardRarity.Common), player);
        host.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Hand).add(host);
        SpellAbility sa = host.getFirstSpellAbility();
        sa.setActivatingPlayer(player);
        return new Fixture(game, player, opponent, sa);
    }

    private void mana(Fixture f, int generic, boolean white) {
        for (int i = 0; i < generic; i++) {
            f.player().getManaPool().addMana(new Mana(MagicColor.COLORLESS,
                    f.spell().getHostCard(), null, f.player()));
        }
        if (white) {
            f.player().getManaPool().addMana(new Mana(MagicColor.WHITE,
                    f.spell().getHostCard(), null, f.player()));
        }
    }

    @Test
    public void intersectionIsNotPairwiseSharingOrUnion() {
        Fixture f = fixture();
        Card ac = card(f.player(), ZoneType.Graveyard, "AC", "Artifact Creature Golem");
        Card ae = card(f.player(), ZoneType.Graveyard, "AE", "Artifact Enchantment");
        Card ec = card(f.player(), ZoneType.Graveyard, "EC", "Enchantment Creature Spirit");
        Card creature = card(f.opponent(), ZoneType.Battlefield, "Creature", "Creature Bear");
        Card enchantment = card(f.opponent(), ZoneType.Battlefield, "Enchantment", "Enchantment");
        Card artifact = card(f.opponent(), ZoneType.Battlefield, "Artifact", "Artifact");
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(ac, ae)));
        assertTrue(f.spell().canTarget(artifact));
        assertFalse(f.spell().canTarget(creature));
        assertFalse(f.spell().canTarget(enchantment));
        f.spell().setCausalitySelection(null);
        assertFalse(CausalityCostHelper.select(f.spell(), List.of(ac, ae, ec)));
        assertNull(f.spell().getCausalitySelection());
        assertTrue(ac.isInZone(ZoneType.Graveyard));
        assertTrue(ae.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void supertypesDoNotMatchButKindredDoes() {
        Fixture f = fixture();
        Card a = card(f.player(), ZoneType.Graveyard, "Legend A", "Legendary Artifact");
        Card c = card(f.player(), ZoneType.Graveyard, "Legend C", "Legendary Creature Bear");
        assertFalse(CausalityCostHelper.select(f.spell(), List.of(a, c)));
        Card kc = card(f.player(), ZoneType.Graveyard, "Kindred C", "Kindred Creature Elf");
        Card ke = card(f.player(), ZoneType.Graveyard, "Kindred E", "Kindred Enchantment Elf");
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(kc, ke)));
        Card target = card(f.opponent(), ZoneType.Battlefield, "Kindred target",
                "Kindred Enchantment Goblin");
        assertTrue(f.spell().canTarget(target));
    }

    @DataProvider
    public Object[][] counts() {
        return new Object[][] {{1}, {2}, {4}, {6}};
    }

    @Test(dataProvider = "counts")
    public void stockPaymentUsesExactSelectionAndOnlyGenericReduction(int count) {
        Fixture f = fixture();
        List<Card> selected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            selected.add(card(f.player(), ZoneType.Graveyard, "Material " + i, "Artifact"));
        }
        Card unselected = card(f.player(), ZoneType.Graveyard, "Keep", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        assertTrue(CausalityCostHelper.select(f.spell(), selected));
        f.spell().getTargets().add(target);
        assertTrue(selected.stream().allMatch(c -> c.isInZone(ZoneType.Graveyard)));
        mana(f, Math.max(0, 4 - count), true);
        CostPayment payment = new CostPayment(f.spell().getPayCosts(), f.spell());
        assertTrue(payment.payCost(new AiCostDecision(f.player(), f.spell(), false) {
            @Override
            public PaymentDecision visit(CostExile cost) {
                fail("The preselected tagged part must not request another choice");
                return null;
            }
        }));
        assertTrue(payment.isFullyPaid());
        assertEquals(f.player().getManaPool().totalMana(), 0);
        assertTrue(selected.stream().allMatch(c ->
                f.game().findById(c.getId()).isInZone(ZoneType.Exile)));
        assertTrue(unselected.isInZone(ZoneType.Graveyard));
        assertTrue(f.spell().getCausalitySelection().paid());
        assertEquals(f.spell().getCausalitySelection().materials().size(), count);
    }

    @Test
    public void missingTargetAndEmptyChoiceDoNotMoveMaterials() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        assertFalse(CausalityCostHelper.select(f.spell(), List.of()));
        assertFalse(CausalityCostHelper.prepare(f.spell()));
        assertNull(f.spell().getCausalitySelection());
        assertTrue(material.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void cancelManaThenRetrySharedCastingPath() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.player(), f.player().getLobbyPlayer()) {
            @Override
            public CardCollectionView chooseCardsForEffect(CardCollectionView available,
                    SpellAbility sa, String title, int min, int max, boolean optional,
                    Map<String, Object> params) {
                return new CardCollection(List.of(material));
            }

            @Override
            public boolean chooseTargetsFor(SpellAbility sa) {
                sa.resetTargets();
                sa.getTargets().add(target);
                return true;
            }

            @Override
            public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility sa,
                    boolean effect, String prompt) {
                return new AiCostDecision(player, sa, effect) {
                    @Override
                    public PaymentDecision visit(CostPartMana cost) {
                        return null; // deterministic cancellation at the mana input boundary
                    }
                };
            }
        };
        f.player().addController(f.game().getNextTimestamp(), f.player(), controller, false);
        assertFalse(new PlaySpellAbility(controller, f.spell()).playAbility(true, false, false));
        assertNull(f.spell().getCausalitySelection());
        assertTrue(f.game().findById(material.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(f.spell().getHostCard().isInZone(ZoneType.Hand));
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().resetTargets();
        f.spell().getTargets().add(target);
        mana(f, 3, true);
        assertTrue(new CostPayment(f.spell().getPayCosts(), f.spell())
                .payCost(new AiCostDecision(f.player(), f.spell(), false)));
    }

    @Test
    public void changedTimestampInvalidatesSelectionWithoutTouchingZones() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().getTargets().add(target);
        material.setGameTimestamp(f.game().getNextTimestamp());
        assertFalse(CausalityCostHelper.hasValidSelection(f.spell()));
        assertFalse(new CostPayment(f.spell().getPayCosts(), f.spell())
                .payCost(new AiCostDecision(f.player(), f.spell(), false)));
        CausalityCostHelper.Attempt attempt = new CausalityCostHelper.Attempt(f.spell());
        attempt.finish(false);
        assertNull(f.spell().getCausalitySelection());
        assertTrue(material.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void committedCopyRetainsTypesAfterMaterialLeavesExile() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().getTargets().add(target);
        mana(f, 3, true);
        assertTrue(new CostPayment(f.spell().getPayCosts(), f.spell())
                .payCost(new AiCostDecision(f.player(), f.spell(), false)));
        SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(
                f.spell(), f.spell(), f.player());
        assertTrue(copy.isCopied());
        assertNull(copy.getPayCosts().getCostPartByType(CostExile.class));
        f.game().getAction().moveToHand(f.game().findById(material.getId()), null);
        assertEquals(copy.getCausalitySelection(), f.spell().getCausalitySelection());
        copy.resetTargets();
        assertTrue(copy.canTarget(target));
        assertTrue(CausalityCostHelper.prepare(copy));
        assertTrue(new CostPayment(copy.getPayCosts(), copy)
                .payCost(new AiCostDecision(f.player(), copy, false)));
        copy.setCausalitySelection(null);
        assertNotNull(f.spell().getCausalitySelection());
    }

    @Test
    public void aiCastingUsesSharedPreselectionAndStockPayment() {
        Fixture f = fixture();
        List<Card> materials = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            materials.add(card(f.player(), ZoneType.Graveyard, "AI material " + i, "Artifact"));
        }
        Card target = card(f.opponent(), ZoneType.Battlefield, "AI target", "Artifact");
        mana(f, 0, true);
        assertTrue(ComputerUtil.handlePlayingSpellAbility(f.player(), f.spell(), sa -> {
            sa.resetTargets();
            sa.getTargets().add(target);
        }));
        assertTrue(materials.stream().allMatch(c ->
                f.game().findById(c.getId()).isInZone(ZoneType.Exile)));
        assertEquals(f.spell().getCausalitySelection().materials().size(), 4);
        assertTrue(f.spell().getCausalitySelection().paid());
    }

    @Test
    public void cancellationClearsOneAttemptWithoutTouchingAnotherAbility() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "One material", "Artifact");
        SpellAbility other = f.spell().copy();
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        assertTrue(CausalityCostHelper.select(other, List.of(material)));
        CausalityCostHelper.Attempt attempt = new CausalityCostHelper.Attempt(f.spell());
        attempt.finish(false);
        assertNull(f.spell().getCausalitySelection());
        assertNotNull(other.getCausalitySelection());
        assertTrue(material.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void unmarkedCostAndOrderingAreUnchanged() {
        Fixture f = fixture();
        SpellAbility ordinary = AbilityFactory.getAbility(
                "SP$ Draw | Cost$ 2 W | NumCards$ 1", f.spell().getHostCard());
        ordinary.setActivatingPlayer(f.player());
        Cost cost = new Cost("2 W ExileFromGrave<1/Permanent>", false);
        List<CostPart> parts = cost.getCostPartsWithZeroMana();
        assertSame(CausalityCostHelper.exileLast(parts, ordinary), parts);
        assertEquals(CausalityCostHelper.reduction(ordinary), 0);
        assertTrue(CausalityCostHelper.prepare(ordinary));
        assertNull(ordinary.getCausalitySelection());
    }

    @Test
    public void targetChoiceCancellationNeverPaysExileCost() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.player(), f.player().getLobbyPlayer()) {
            @Override
            public CardCollectionView chooseCardsForEffect(CardCollectionView available,
                    SpellAbility sa, String title, int min, int max, boolean optional,
                    Map<String, Object> params) {
                return new CardCollection(List.of(material));
            }

            @Override
            public boolean chooseTargetsFor(SpellAbility sa) {
                return false;
            }
        };
        f.player().addController(f.game().getNextTimestamp(), f.player(), controller, false);
        assertFalse(new PlaySpellAbility(controller, f.spell()).playAbility(true, false, false));
        assertNull(f.spell().getCausalitySelection());
        assertTrue(f.game().findById(material.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(f.spell().getHostCard().isInZone(ZoneType.Hand));
    }

    @Test
    public void materialTypeChangeDuringManaPaymentRejectsTheOldSelection() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().getTargets().add(target);
        mana(f, 3, true);
        assertFalse(new CostPayment(f.spell().getPayCosts(), f.spell())
                .payCost(new AiCostDecision(f.player(), f.spell(), false) {
                    @Override
                    public PaymentDecision visit(CostPartMana cost) {
                        material.setType(CardType.parse("Enchantment", false));
                        return PaymentDecision.number(0);
                    }
                }));
        assertNull(f.spell().getCausalitySelection());
        assertTrue(material.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void anotherExileCostDoesNotInflateTheDiscountOrPaidIntersection() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        Card other = card(f.player(), ZoneType.Graveyard, "Other cost", "Creature Bear");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        f.spell().getPayCosts().add(new Cost("ExileFromGrave<1/Creature/ExileTag=Other>", false));
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().getTargets().add(target);
        mana(f, 3, true);
        assertTrue(new CostPayment(f.spell().getPayCosts(), f.spell())
                .payCost(new AiCostDecision(f.player(), f.spell(), false) {
                    @Override
                    public PaymentDecision visit(CostExile cost) {
                        assertEquals(cost.getExileTag(), "Other");
                        return PaymentDecision.card(other);
                    }
                }));
        assertEquals(f.spell().getCausalitySelection().materials().size(), 1);
        assertEquals(f.spell().getPaidList("ExiledCards", true).size(), 2);
        assertEquals(f.spell().getPaidList("ExiledCost_Causality", true).size(), 1);
        assertEquals(f.spell().getPaidList("ExiledCost_Other", true).size(), 1);
    }

    @Test
    public void pendingSelectionSurvivesCopyOfGameWithoutOriginalGameReferences() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().getTargets().add(target);
        f.spell().setHostCard(f.game().getAction().moveToStack(f.spell().getHostCard(), f.spell()));
        f.game().getStack().add(f.spell());
        Game copy = new GameSnapshot(f.game()).makeCopy();
        SpellAbility copied = copy.getStack().peekAbility();
        assertNotNull(copied);
        assertEquals(copied.getCausalitySelection(), f.spell().getCausalitySelection());
        assertTrue(CausalityCostHelper.hasValidSelection(copied));
        CausalityCostHelper.Attempt attempt = new CausalityCostHelper.Attempt(copied);
        attempt.finish(false);
        assertNull(copied.getCausalitySelection());
        assertNotNull(f.spell().getCausalitySelection());
        assertTrue(material.isInZone(ZoneType.Graveyard));
    }

    @Test
    public void freeManaCastStillPaysTheAdditionalExileCost() {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Free cast material", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Free cast target", "Artifact");
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().getTargets().add(target);
        assertTrue(new PlaySpellAbility(f.player().getController(), f.spell())
                .playAbility(false, true, false));
        assertTrue(f.game().findById(material.getId()).isInZone(ZoneType.Exile));
        assertTrue(f.spell().getCausalitySelection().paid());
        assertEquals(f.spell().getTotalManaSpent(), 0);
    }

    @DataProvider
    public Object[][] paymentPaths() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "paymentPaths")
    public void completionUsesStockExileResultsInsteadOfFabricatingMissingTaggedEvidence(boolean computer) {
        Fixture f = fixture();
        Card material = card(f.player(), ZoneType.Graveyard, "Material", "Artifact");
        Card target = card(f.opponent(), ZoneType.Battlefield, "Target", "Artifact");
        CostExile instrumented = new CostExile("1", "Permanent", "permanent cards", ZoneType.Graveyard) {
            @Override
            public boolean payAsDecided(Player payer, PaymentDecision decision, SpellAbility ability, boolean effect) {
                boolean paid = super.payAsDecided(payer, decision, ability, effect);
                // The actual CostExile result must remain usable without this additional hash row.
                ability.getPaidHash().row(TAGGED_PAID_LIST_PREFIX + "Causality").clear();
                return paid;
            }
        };
        instrumented.setExileTag("Causality");
        List<CostPart> parts = f.spell().getPayCosts().getCostParts();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof CostExile) {
                parts.set(i, instrumented);
                break;
            }
        }
        assertTrue(CausalityCostHelper.select(f.spell(), List.of(material)));
        f.spell().getTargets().add(target);
        mana(f, 3, true);
        CostPayment payment = new CostPayment(f.spell().getPayCosts(), f.spell());
        AiCostDecision choices = new AiCostDecision(f.player(), f.spell(), false);
        assertTrue(computer ? payment.payComputerCosts(choices) : payment.payCost(choices));
        assertEquals(f.player().getManaPool().totalMana(), 0);
        assertTrue(f.game().findById(material.getId()).isInZone(ZoneType.Exile));
        assertTrue(f.spell().getCausalitySelection().paid());
        assertEquals(f.spell().getCausalitySelection().materials().get(0).timestamp(),
                f.game().findById(material.getId()).getGameTimestamp());
        SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(f.spell(), f.spell(), f.player());
        copy.resetTargets();
        assertTrue(copy.canTarget(target));
        assertTrue(CausalityCostHelper.prepare(copy));
    }

    @Test
    public void oldOnePlusSyntaxIsParsedButDoesNotDeclareVariableCardinality() {
        Cost cost = new Cost("ExileFromGrave<1+/Permanent+withSharedCardType/permanent cards>", false);
        assertEquals(cost.getCostPartByType(CostExile.class).getAmount(), "1+");
        // The proposal intentionally leaves global parsing/evaluation of this syntax unchanged.
    }
}
