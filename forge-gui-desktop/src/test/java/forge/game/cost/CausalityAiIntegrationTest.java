package forge.game.cost;

import forge.ai.AITest;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

/** Exercises real AI evaluation, final targeting, CostPayment and stack resolution. */
public class CausalityAiIntegrationTest extends AITest {
    private record Fixture(Game game, Player ai, Player opponent, SpellAbility spell) { }

    private Card card(Player player, ZoneType zone, String name, String types, String mana) {
        List<String> lines = new ArrayList<>(List.of("Name:" + name, "ManaCost:" + mana, "Types:" + types));
        if (types.contains("Creature")) lines.add("PT:8/8");
        Card result = Card.fromPaperCard(new PaperCard(CardRules.fromScript(lines),
                "TEST", CardRarity.Common), player);
        result.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(result);
        return result;
    }

    private Fixture fixture() {
        Game game = initAndCreateGame();
        game.getPlayers().forEach(player -> fillLibrary(player, 12));
        Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        Card host = card(ai, ZoneType.Hand, "AI Causality", "Instant", "4 W");
        SpellAbility spell = AbilityFactory.getAbility(
                "SP$ ChangeZone | Cost$ 4 W ExileFromGrave<1/Permanent/permanent cards/ExileTag=Causality>"
                + " | CausalityCost$ True | AILogic$ Causality"
                + " | ValidTgts$ Permanent.nonLand+sharesCausalityCostType"
                + " | Origin$ Battlefield | Destination$ Exile | SpellDescription$ Exile target nonland permanent.",
                host);
        spell.setActivatingPlayer(ai);
        host.addSpellAbility(spell);
        return new Fixture(game, ai, opponent, spell);
    }

    private List<Card> artifacts(Fixture f, int count) {
        List<Card> materials = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            materials.add(card(f.ai(), ZoneType.Graveyard, "Material " + i, "Artifact", "1"));
        }
        return materials;
    }

    private void mana(Fixture f, int generic, boolean white) {
        for (int i = 0; i < generic; i++) {
            f.ai().getManaPool().addMana(new Mana(MagicColor.COLORLESS, f.spell().getHostCard(), null, f.ai()));
        }
        if (white) f.ai().getManaPool().addMana(new Mana(MagicColor.WHITE,
                f.spell().getHostCard(), null, f.ai()));
    }

    private AiPlayDecision evaluate(Fixture f) {
        f.game().getAction().checkStaticAbilities();
        f.game().copyLastState();
        f.game().getTriggerHandler().resetActiveTriggers();
        return ((PlayerControllerAi) f.ai().getController()).getAi().canPlaySa(f.spell());
    }

    private void castAndResolve(Fixture f, Card target, int count) {
        assertEquals(evaluate(f), AiPlayDecision.WillPlay, "The real AI must accept and target the spell");
        assertTrue(f.spell().getTargets().getTargetCards().contains(target));
        assertTrue(ComputerUtil.handlePlayingSpellAbility(f.ai(), f.spell(), null),
                "No target is injected by this test; casting uses the AI's chosen target");
        SpellAbility onStack = f.game().getStack().peekAbility();
        assertNotNull(onStack);
        assertTrue(onStack.getTargets().getTargetCards().contains(target));
        assertTrue(onStack.getCausalitySelection().paid());
        assertEquals(onStack.getCausalitySelection().materials().size(), count);
        f.game().getStack().resolveStack();
        assertTrue(f.game().findById(target.getId()).isInZone(ZoneType.Exile));
    }

    @DataProvider
    public Object[][] manaCases() {
        return new Object[][] {{0, 4}, {1, 3}, {3, 1}};
    }

    @Test(dataProvider = "manaCases")
    public void actualAiUsesMinimumAffordableMaterialCount(int generic, int count) {
        Fixture f = fixture();
        List<Card> materials = artifacts(f, 6);
        Card target = addCard("Akroma's Memorial", f.opponent());
        mana(f, generic, true);
        castAndResolve(f, target, count);
        assertEquals(materials.stream().filter(c ->
                f.game().findById(c.getId()).isInZone(ZoneType.Exile)).count(), (long) count);
        assertEquals(f.ai().getManaPool().totalMana(), 0);
    }

    @Test
    public void actualAiCannotReplaceTheWhiteRequirementWithMoreMaterials() {
        Fixture f = fixture();
        List<Card> materials = artifacts(f, 6);
        addCard("Akroma's Memorial", f.opponent());
        mana(f, 8, false);
        assertNotEquals(evaluate(f), AiPlayDecision.WillPlay);
        assertFalse(ComputerUtil.handlePlayingSpellAbility(f.ai(), f.spell(), null));
        assertTrue(materials.stream().allMatch(c -> c.isInZone(ZoneType.Graveyard)));
        assertTrue(f.spell().getHostCard().isInZone(ZoneType.Hand));
        assertEquals(f.ai().getManaPool().totalMana(), 8);
    }

    @Test
    public void actualAiPaysAdditionalLifeAndIndependentExileCost() {
        Fixture f = fixture();
        List<Card> materials = artifacts(f, 5);
        Card other = card(f.ai(), ZoneType.Graveyard, "Other material", "Creature Bear", "1");
        f.spell().getPayCosts().add(new Cost("PayLife<2> ExileFromGrave<1/Creature/ExileTag=Other>", false));
        Card target = addCard("Akroma's Memorial", f.opponent());
        mana(f, 0, true);
        int life = f.ai().getLife();
        castAndResolve(f, target, 4);
        assertEquals(f.ai().getLife(), life - 2);
        assertTrue(f.game().findById(other.getId()).isInZone(ZoneType.Exile));
        assertEquals(f.spell().getPaidList("ExiledCost_Other", true).size(), 1);
        assertEquals(materials.stream().filter(c ->
                f.game().findById(c.getId()).isInZone(ZoneType.Exile)).count(), 4L);
    }

    @Test
    public void actualAiAccountsForARealStaticManaTax() {
        Fixture f = fixture();
        artifacts(f, 7);
        Card tax = card(f.opponent(), ZoneType.Battlefield, "Tax", "Artifact", "2");
        tax.addStaticAbility("Mode$ RaiseCost | ValidCard$ Card | Type$ Spell | Amount$ 2");
        Card target = addCard("Akroma's Memorial", f.opponent());
        mana(f, 0, true);
        castAndResolve(f, target, 6);
    }

    @Test
    public void actualAiKeepsTheFinalTargetCompatibleWithAffordableMaterials() {
        Fixture f = fixture();
        artifacts(f, 4);
        card(f.ai(), ZoneType.Graveyard, "Only creature material", "Creature Bear", "1");
        card(f.opponent(), ZoneType.Battlefield, "Unaffordable high-value creature", "Creature Dragon", "12");
        Card target = addCard("Akroma's Memorial", f.opponent());
        mana(f, 0, true);
        castAndResolve(f, target, 4);
    }

    @Test
    public void incompatibleFinalRetargetCancelsBeforePayment() {
        Fixture f = fixture();
        List<Card> materials = artifacts(f, 4);
        addCard("Akroma's Memorial", f.opponent());
        Card wrong = card(f.opponent(), ZoneType.Battlefield, "Wrong type", "Creature Dragon", "12");
        mana(f, 0, true);
        assertEquals(evaluate(f), AiPlayDecision.WillPlay);
        assertFalse(ComputerUtil.handlePlayingSpellAbility(f.ai(), f.spell(), sa -> {
            sa.resetTargets();
            sa.getTargets().add(wrong);
        }));
        assertTrue(materials.stream().allMatch(c -> c.isInZone(ZoneType.Graveyard)));
        assertNull(f.spell().getCausalitySelection());
        assertTrue(f.spell().getHostCard().isInZone(ZoneType.Hand));
        assertEquals(f.ai().getManaPool().totalMana(), 1);
    }

    @DataProvider
    public Object[][] overlappingCosts() {
        return new Object[][] {{5, 0, 4}, {4, 1, 3}, {4, 0, 0}};
    }

    @Test(dataProvider = "overlappingCosts")
    public void actualAiReservesMaterialsForAnotherArtifactExileCost(int count, int generic, int expected) {
        Fixture f = fixture();
        List<Card> materials = artifacts(f, count);
        f.spell().getPayCosts().add(new Cost("ExileFromGrave<1/Artifact/ExileTag=Other>", false));
        Card target = addCard("Akroma's Memorial", f.opponent());
        mana(f, generic, true);
        if (expected == 0) {
            assertNotEquals(evaluate(f), AiPlayDecision.WillPlay);
            assertFalse(ComputerUtil.handlePlayingSpellAbility(f.ai(), f.spell(), null));
            assertTrue(materials.stream().allMatch(card -> card.isInZone(ZoneType.Graveyard)));
            assertEquals(f.ai().getManaPool().totalMana(), generic + 1);
        } else {
            castAndResolve(f, target, expected);
            assertEquals(f.spell().getPaidList("ExiledCost_Other", true).size(), 1);
            int otherId = f.spell().getPaidList("ExiledCost_Other", true).getFirst().getId();
            assertTrue(f.spell().getCausalitySelection().materials().stream()
                    .noneMatch(material -> material.id() == otherId));
        }
        assertTrue(materials.stream().noneMatch(Card::isReservedForZoneChangePayment));
    }

    @Test
    public void actualAiCastsThePrintedModalBackFace() throws Exception {
        Game game = initAndCreateGame();
        game.getPlayers().forEach(player -> fillLibrary(player, 12));
        Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        Path path = Path.of(System.getProperty("causality.card.script",
                Path.of(ForgeConstants.CARD_DATA_DIR, "custom cards", "ERLB", "l",
                        "Law_of_Regression_Law_of_Causality.txt").toString()));
        CardRules rules = CardRules.fromScript(Files.readAllLines(path, StandardCharsets.UTF_8)
                .stream().filter(line -> !line.isBlank()).toList());
        Card host = Card.fromPaperCard(new PaperCard(rules, "ERLB", CardRarity.Rare), ai);
        host.setGameTimestamp(game.getNextTimestamp());
        ai.getZone(ZoneType.Hand).add(host);
        host.setState(CardStateName.Backside, true);
        SpellAbility spell = host.getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        Fixture f = new Fixture(game, ai, opponent, spell);
        artifacts(f, 5);
        Card target = addCard("Akroma's Memorial", opponent);
        mana(f, 0, true);
        castAndResolve(f, target, 4);
    }

}
