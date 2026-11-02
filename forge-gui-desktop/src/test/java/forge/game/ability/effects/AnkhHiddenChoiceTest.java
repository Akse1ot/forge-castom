package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.ai.ability.PleaBargainAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;
import java.util.Map;

public class AnkhHiddenChoiceTest extends AITest {
    private final class ChoiceController extends PlayerControllerAi {
        Card hidden, publicSelection;
        boolean blind, mutatePrivate, mutatePublic, ai;
        int privateCalls, publicCalls, revealCalls;
        ChoiceController(Player p) { super(p.getGame(),p,p.getLobbyPlayer()); }
        @Override @SuppressWarnings("unchecked")
        public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> cards, DelayedReveal delayed,
                SpellAbility sa,String title,boolean optional,Player related,Map<String,Object> params) {
            privateCalls++;
            Assert.assertTrue(cards.contains((T)hidden));
            if(mutatePrivate) blink(hidden);
            return (T)hidden;
        }
        private void blink(Card c) {
            Game game=c.getGame();Card moved=game.getAction().moveToGraveyard(c,null);
            game.getAction().moveToHand(moved,null);
        }
        @Override public void reveal(CardCollectionView cards,ZoneType zone,Player owner,String prefix,boolean suffix) {
            revealCalls++;Assert.assertFalse(cards.contains(hidden),"hidden Card must never reach public reveal callback");
        }
        @Override public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> options,SpellAbility sa,String title,Map<String,Object> params) {
            publicCalls++;
            Assert.assertFalse(title.contains(hidden.getName()));
            Assert.assertFalse(sa.getHostCard().hasChosenCard());Assert.assertFalse(sa.getHostCard().hasRemembered());
            var revealed=(Map<?,?>)params.get(PleaBargainEffect.REVEALED_CHOICES);
            Assert.assertFalse(revealed.containsValue(hidden));
            Assert.assertEquals(revealed.size(),options.size()-1);
            for(SpellAbility option:options) {
                Assert.assertSame(option.getHostCard(),sa.getHostCard());
                Assert.assertFalse(option.toString().contains(hidden.getName()));
                Assert.assertFalse(option.usesTargeting());
                if(revealed.get(option) instanceof Card c)Assert.assertFalse(c.isLand());
            }
            SpellAbility chosen=options.get(0);
            if(ai)chosen=new PleaBargainAi().chooseSingleSpellAbility(sa.getActivatingPlayer(),sa,options,params);
            else if(!blind)chosen=options.stream().filter(o->revealed.get(o)==publicSelection).findFirst().orElseThrow();
            if(mutatePublic)blink(blind?hidden:publicSelection);
            return chosen;
        }
    }
    private SpellAbility ability(Player caster,Player opponent) {
        var sa=AbilityFactory.getAbility("AB$ PleaBargain | Cost$ B | ValidTgts$ Opponent",addCard("Swamp",caster));
        sa.setActivatingPlayer(caster);sa.getTargets().add(opponent);return sa;
    }
    private ChoiceController controller(Player p,Card hidden) {
        var controller=new ChoiceController(p);controller.hidden=hidden;p.dangerouslySetController(controller);return controller;
    }
    @Test public void emptyHandMakesNoChoice() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);
        var c=controller(p,null);var d=controller(q,null);AbilityUtils.resolve(ability(p,q));Assert.assertEquals(c.publicCalls+d.privateCalls,0);
    }
    @Test public void singletonHiddenLandCanBeDiscardedWithoutRevealingItForTheChoice() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card hidden=addCardToZone("Forest",q,ZoneType.Hand);
        var c=controller(p,hidden);c.blind=true;controller(q,hidden);AbilityUtils.resolve(ability(p,q));
        Assert.assertTrue(g.getCardState(hidden).isInZone(ZoneType.Graveyard));Assert.assertEquals(c.publicCalls,1);Assert.assertEquals(q.getDiscardedThisTurn().size(),1);
    }
    @Test public void revealsAllOthersIncludingLandsButOnlyOffersRevealedNonlands() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card hidden=addCardToZone("Forest",q,ZoneType.Hand),spell=addCardToZone("Dark Ritual",q,ZoneType.Hand);addCardToZone("Island",q,ZoneType.Hand);
        var c=controller(p,hidden);c.publicSelection=spell;controller(q,hidden);AbilityUtils.resolve(ability(p,q));
        Assert.assertTrue(g.getCardState(spell).isInZone(ZoneType.Graveyard));Assert.assertEquals(q.getCardsIn(ZoneType.Hand).size(),2);Assert.assertEquals(c.revealCalls,1);
    }
    @Test public void allRevealedLandsLeaveTheBlindOptionRegardlessOfHiddenType() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card hidden=addCardToZone("Dark Ritual",q,ZoneType.Hand);addCardToZone("Island",q,ZoneType.Hand);
        var c=controller(p,hidden);c.blind=true;controller(q,hidden);AbilityUtils.resolve(ability(p,q));Assert.assertTrue(g.getCardState(hidden).isInZone(ZoneType.Graveyard));
    }
    @Test public void stalePrivateChoiceCannotDiscardNewHandObject() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card hidden=addCardToZone("Forest",q,ZoneType.Hand);
        var c=controller(p,hidden);var d=controller(q,hidden);d.mutatePrivate=true;AbilityUtils.resolve(ability(p,q));
        Assert.assertEquals(c.publicCalls,0);Assert.assertEquals(q.getCardsIn(ZoneType.Hand).size(),1);Assert.assertTrue(q.getDiscardedThisTurn().isEmpty());
    }
    @Test public void stalePublicAndBlindChoicesCannotDiscardNewHandObjects() {
        for(boolean blind:List.of(false,true)) {
            Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card hidden=addCardToZone("Forest",q,ZoneType.Hand),spell=addCardToZone("Dark Ritual",q,ZoneType.Hand);
            var c=controller(p,hidden);c.blind=blind;c.publicSelection=spell;c.mutatePublic=true;controller(q,hidden);AbilityUtils.resolve(ability(p,q));
            Assert.assertEquals(q.getCardsIn(ZoneType.Hand).size(),2);Assert.assertTrue(q.getDiscardedThisTurn().isEmpty());
        }
    }
    @Test public void aiChoosesUsingOnlyThePublicMap() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card hidden=addCardToZone("Autochthon Wurm",q,ZoneType.Hand),spell=addCardToZone("Dark Ritual",q,ZoneType.Hand);
        var c=controller(p,hidden);c.ai=true;controller(q,hidden);AbilityUtils.resolve(ability(p,q));
        Assert.assertTrue(g.getCardState(spell).isInZone(ZoneType.Graveyard));Assert.assertTrue(hidden.isInZone(ZoneType.Hand));
    }
}
