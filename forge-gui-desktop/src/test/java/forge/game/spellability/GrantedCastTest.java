package forge.game.spellability;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPayEnergy;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.assertTrue;

/**
 * A grant clears the restriction zone, so it is the grant that has to reach another player's
 * zone. MayPlayDontGrantZonePermissions marks a cost reduction rather than permission, so on its
 * own it is not enough - but it must not stand in the way when a second grant does allow it.
 */
public class GrantedCastTest extends AITest {

    private Game game;
    private Player me;
    private Player opp;

    @Test
    public void aCostReductionAloneDoesNotReachAnotherHand() {
        newGame();
        Card af = addCard("As Foretold", me);
        af.addCounterInternal(CounterEnumType.TIME, 5, me, false, new forge.game.GameEntityCounterTable(), null);
        addCards("Island", 8, me);
        settle();

        Card mine = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        Card theirs = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        settle();

        assertEquals("As Foretold still discounts my own card", 2, playable(mine));
        assertEquals("but it must not reach an opponent's hand", 0, playable(theirs));
    }

    @Test
    public void aGrantThatReachesTheZoneStillWorks() {
        newGame();
        Card prize = addCardToZone("Lightning Bolt", opp, ZoneType.Graveyard);
        addCards("Island", 10, me);
        settle();

        resolve(addCardToZone("Mnemonic Betrayal", me, ZoneType.Hand).getFirstSpellAbility(), null);
        assertEquals("Mnemonic Betrayal still casts from their graveyard", 1,
                playable(game.getCardState(prize, prize)));
    }

    @Test
    public void aCostReductionRidesOnTopOfAGrant() {
        newGame();
        Card grenzo = addCard("Grenzo, Crooked Jailer", me);
        addCards("Mountain", 8, me);
        for (int i = 0; i < 6; i++) {
            addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        }
        settle();

        // Heist is what grants the zone; Grenzo's {0} only discounts what it finds there
        resolve(AbilityFactory.getAbility("DB$ Heist | ValidTgts$ Opponent", grenzo), opp);
        Card heisted = opp.getCardsIn(ZoneType.Exile).getFirst();
        assertEquals("Grenzo's discount survives alongside Heist's grant", 2, playable(heisted));
    }

    @Test
    public void aCostReductionGrantedToTheActivePlayerReachesOnlyTheirOwnCards() {
        newGame();
        // the enchantment is theirs, but it grants to whoever is active - that is me
        addCard("Weftwalking", opp);
        addCards("Island", 8, me);
        settle();

        Card mine = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        Card theirs = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        settle();

        assertEquals("Weftwalking still offers me the free cast of my own card", 2, playable(mine));
        assertEquals("but not of a card I do not own", 0, playable(theirs));
    }

    @Test
    public void selectedAlternativeKeepsAdditionalCostsAndRejectsAnotherManaReplacement() {
        newGame();
        Card foretold = addCard("As Foretold", me);
        foretold.addCounterInternal(CounterEnumType.TIME, 5, me, false, new forge.game.GameEntityCounterTable(), null);
        addCard("Primal Prayers", me);
        Card spell = scriptedCard(me, ZoneType.Hand, "Reflex fixture", "Creature",
                "PT:2/2", reflexCost("Hand"), reflexTiming("Hand"));
        Card ordinary = addCardToZone("Grizzly Bears", me, ZoneType.Hand);
        settle();

        List<SpellAbility> options = alternatives(spell);
        List<SpellAbility> reflex = options.stream().filter(sa -> "Reflex".equals(sa.getName())).toList();
        assertEquals(1, reflex.size());
        assertEquals("{R} {R}", reflex.get(0).getPayCosts().getTotalMana().getShortString());
        assertTrue(reflex.get(0).getPayCosts().getCostParts().stream().anyMatch(CostDiscard.class::isInstance));
        assertTrue(reflex.get(0).getPayCosts().getCostParts().stream().noneMatch(CostPayEnergy.class::isInstance));
        assertTrue(options.stream().anyMatch(sa -> sa.getMayPlay() != null && sa.getMayPlay().getHostCard().equals(foretold)));
        assertTrue(alternatives(ordinary).stream().anyMatch(sa ->
                sa.getPayCosts().getCostParts().stream().anyMatch(CostPayEnergy.class::isInstance)));
    }

    @Test
    public void alternativeFromForeignExileNeedsAnIndependentPermission() {
        newGame();
        addCardToZone("Grizzly Bears", me, ZoneType.Hand);
        Card spell = scriptedCard(opp, ZoneType.Exile, "Reflex in exile", "Sorcery",
                "A:SP$ Draw | Defined$ You | NumCards$ 1", reflexCost("Exile"), reflexTiming("Exile"));
        Card free = scriptedCard(me, ZoneType.Battlefield, "Free exile cast", "Artifact",
                "S:Mode$ Continuous | AffectedZone$ Exile | Affected$ Sorcery.OppOwn | MayPlay$ True"
                        + " | MayPlayWithoutManaCost$ True");
        settle();
        List<SpellAbility> options = alternatives(spell);
        assertTrue(options.stream().anyMatch(sa -> sa.hasParam("WithoutManaCost") && sa.canPlay()
                && sa.getMayPlay().getHostCard().equals(free)));
        assertFalse(options.stream().anyMatch(sa -> "Reflex".equals(sa.getName()) && sa.canPlay()));

        Card permission = scriptedCard(me, ZoneType.Battlefield, "Independent exile cast", "Artifact",
                "S:Mode$ Continuous | AffectedZone$ Exile | Affected$ Sorcery.OppOwn | MayPlay$ True | MayPlayLimit$ 1");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        game.getPhaseHandler().onStackResolved();
        game.getPhaseHandler().setPriority(me);
        settle();
        SpellAbility reflex = alternatives(spell).stream()
                .filter(sa -> "Reflex".equals(sa.getName()) && sa.canPlay()).findFirst().orElseThrow();
        assertSame(permission.getStaticAbilities().get(0), reflex.getMayPlay());
        assertEquals("{R} {R}", reflex.getPayCosts().getTotalMana().getShortString());
        assertTrue(reflex.copy(reflex.getHostCard(), me, false, false)
                .hasParam(AlternativeCostRuleUtil.PARAM_PREVENT_COST_REPLACEMENT));
    }

    private List<SpellAbility> alternatives(Card card) {
        SpellAbility base = card.getFirstSpellAbility();
        base.setActivatingPlayer(me);
        return GameActionUtil.getAlternativeCosts(base, me, false);
    }

    private static String reflexCost(String zone) {
        return "S:Mode$ AlternativeCost | EffectZone$ " + zone + " | ValidCard$ Card.Self | ValidSA$ Spell"
                + " | Cost$ R R Discard<1/Card> | Named$ Reflex | PreventAlternativeCostReplacement$ True | Description$ Reflex";
    }

    private static String reflexTiming(String zone) {
        return "S:Mode$ CastWithFlash | EffectZone$ " + zone + " | ValidCard$ Card.Self | ValidSA$ Spell.NamedAbilityReflex";
    }

    private Card scriptedCard(Player owner, ZoneType zone, String name, String type, String... script) {
        List<String> lines = new ArrayList<>(List.of("Name:" + name, "ManaCost:2 R", "Types:" + type));
        lines.addAll(List.of(script));
        lines.add("Oracle:");
        Card card = CardFactory.getCard(new PaperCard(CardRules.fromScript(lines), "M21", CardRarity.Common), owner, game);
        card.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(zone).add(card);
        return card;
    }

    private void newGame() {
        game = initAndCreateGame();
        me = game.getPlayers().get(0);
        opp = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
    }

    private void settle() {
        game.getAction().checkStateEffects(true);
    }

    private int playable(Card c) {
        return c.getAllPossibleAbilities(me, true).size();
    }

    private void resolve(SpellAbility sa, Player target) {
        sa.setActivatingPlayer(me);
        sa.resetTargets();
        if (target != null) {
            sa.getTargets().add(target);
        }
        AbilityUtils.resolve(sa);
        settle();
    }
}
