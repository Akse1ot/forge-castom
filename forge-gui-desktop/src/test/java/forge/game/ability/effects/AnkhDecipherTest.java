package forge.game.ability.effects;

import forge.ai.AITest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.ArrayList;
import java.util.List;

/** Generic fixtures exercise the real casting trigger and existing copy API. */
public class AnkhDecipherTest extends AITest {
    private Card card(Player p, ZoneType zone, String name, String types, String... script) {
        var lines=new ArrayList<>(List.of("Name:"+name,"ManaCost:1 U","Types:"+types));
        lines.addAll(List.of(script));lines.add("Oracle:");
        Card c=CardFactory.getCard(new PaperCard(CardRules.fromScript(lines),"TST",CardRarity.Common),p,p.getGame());
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(c);return c;
    }
    private Card nativeSpell(Player p) {
        return card(p,ZoneType.Hand,"Decipher fixture","Instant",
                "A:SP$ GainLife | LifeAmount$ 1 | Defined$ You | SpellDescription$ Gain 1 life.","K:Decipher:3 U");
    }
    private Card stone(Player p) {
        return card(p,ZoneType.Battlefield,"Decipher provider","Artifact",
                "S:Mode$ AlternativeCost | ValidSA$ Spell | ValidCard$ Instant,Sorcery | ValidPlayer$ You | Cost$ W U B R G | AlternativeCost$ Decipher | Description$ Decipher {W}{U}{B}{R}{G}");
    }
    private SpellAbility nativeOption(Card c,Player p) {
        SpellAbility a=c.getSpellAbilities().stream().filter(x->x.isAlternativeCost(AlternativeCost.Decipher)).findFirst().orElseThrow();
        a.setActivatingPlayer(p);return a;
    }
    private void cast(Game g,SpellAbility a) {
        a.setHostCard(g.getAction().moveToStack(a.getHostCard(),a));g.getStack().add(a);
        g.getTriggerHandler().runWaitingTriggers();g.getStack().addAllTriggeredAbilitiesToStack();
    }
    private void drain(Game g) {
        for(int i=0;i<40;i++){g.getTriggerHandler().runWaitingTriggers();g.getStack().addAllTriggeredAbilitiesToStack();if(g.getStack().isEmpty())return;g.getStack().resolveStack();}
        Assert.fail("recursive or undrained copy stack");
    }
    @Test
    public void countAtResolutionIsInstantSorceryUnion() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);Card c=nativeSpell(p);
        card(p,ZoneType.Graveyard,"Earlier instant","Instant");card(q,ZoneType.Graveyard,"Other graveyard","Sorcery");
        int life=p.getLife();cast(g,nativeOption(c,p));Assert.assertEquals(g.getStack().size(),2);
        card(p,ZoneType.Graveyard,"Both types","Instant Sorcery"); // entered after cast, counted only once
        drain(g);Assert.assertEquals(p.getLife(),life+3); // original plus two copies, no recursion
    }
    @Test
    public void ordinaryAndFreeCastsDoNotDecipher() {
        for(boolean free:new boolean[]{false,true}) {
            Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card c=nativeSpell(p);
            card(p,ZoneType.Graveyard,"Instant in yard","Instant");
            SpellAbility a=free?nativeOption(c,p).copyWithNoManaCost():c.getFirstSpellAbility();a.setActivatingPlayer(p);
            int life=p.getLife();cast(g,a);Assert.assertEquals(g.getStack().size(),1);drain(g);Assert.assertEquals(p.getLife(),life+1);
        }
    }
    @Test
    public void nativeAndMultipleProvidersGiveOneBatch() {
        for(boolean useExternal:new boolean[]{false,true}) {
            Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card c=nativeSpell(p);Card one=stone(p),two=stone(p);
            card(p,ZoneType.Graveyard,"Sorcery in yard","Sorcery");SpellAbility basic=c.getFirstSpellAbility();basic.setActivatingPlayer(p);
            var alternatives=GameActionUtil.getAlternativeCosts(basic,p,true).stream().filter(x->x.isAlternativeCost(AlternativeCost.Decipher)).toList();
            Assert.assertTrue(alternatives.size()>=2,"each provider offers a concrete alternative");
            SpellAbility a=useExternal?alternatives.get(0):nativeOption(c,p);a.setActivatingPlayer(p);int life=p.getLife();cast(g,a);
            // Providers no longer matter once the chosen spell has been cast.
            g.getAction().moveToGraveyard(one,null);g.getAction().moveToGraveyard(two,null);
            drain(g);Assert.assertEquals(p.getLife(),life+2);
        }
    }
    @Test
    public void nativeCostPreservesAdditionalCostAndX() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);
        Card c=card(p,ZoneType.Hand,"X decipher","Sorcery",
                "A:SP$ GainLife | Cost$ X U Discard<1/Card> | LifeAmount$ X | Defined$ You","K:Decipher:X X X R R R","SVar:X:Count$xPaid");
        SpellAbility a=nativeOption(c,p);a.setXManaCostPaid(2);
        Assert.assertTrue(a.getPayCosts().getCostParts().stream().anyMatch(x->x instanceof forge.game.cost.CostDiscard));
        Assert.assertEquals(a.getPayCosts().getTotalMana().countX(),3);
        card(p,ZoneType.Graveyard,"Instant in yard","Instant");int life=p.getLife();cast(g,a);drain(g);Assert.assertEquals(p.getLife(),life+4);
    }
    @Test
    public void copyControllerCanRetargetAfterOriginalTargetLeft() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);
        Card c=card(p,ZoneType.Hand,"Targeted decipher","Instant",
                "A:SP$ DealDamage | ValidTgts$ Creature | NumDmg$ 2","K:Decipher:3 U");
        Card oldTarget=addCard("Grizzly Bears",q),newTarget=addCard("Grizzly Bears",q);
        // Make an explicit legal player choice; the default AI may keep an illegal old target.
        p.dangerouslySetController(new forge.ai.PlayerControllerAi(g,p,p.getLobbyPlayer()) {
            @Override
            public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
                for(SpellAbility copy:abilities)if(copy.isCopied()) {
                    Assert.assertTrue(copy.isMayChooseNewTargets());
                    copy.resetTargets();Assert.assertTrue(copy.canTarget(newTarget));copy.getTargets().add(newTarget);
                    copy.setMayChooseNewTargets(false);
                }
                super.orderAndPlaySimultaneousSa(abilities);
            }
        });
        card(p,ZoneType.Graveyard,"Instant in yard","Instant");SpellAbility a=nativeOption(c,p);a.getTargets().add(oldTarget);cast(g,a);
        g.getAction().moveToGraveyard(oldTarget,null);drain(g);Assert.assertEquals(newTarget.getDamage(),2);
    }
    @Test
    public void triggerAndCopiesSurviveSnapshotAndAiCopy() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card c=nativeSpell(p);
        card(p,ZoneType.Graveyard,"Instant in yard","Instant");int life=p.getLife();cast(g,nativeOption(c,p));
        var snapshot=new forge.game.GameSnapshot(g);
        snapshot.makeCopy();
        Game copied=new forge.ai.simulation.GameCopier(g).withStack(true).makeCopy();
        drain(copied);Assert.assertEquals(copied.getPlayer(p.getId()).getLife(),life+2);
        drain(g);Assert.assertEquals(p.getLife(),life+2);
        snapshot.restoreGameState(g);Player restored=g.getPlayer(p.getId());Assert.assertEquals(restored.getLife(),life);
        drain(g);Assert.assertEquals(restored.getLife(),life+2);
    }
    @Test
    public void externalProviderAcceptsSpellsOwnedByAnotherPlayer() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);stone(p);Card c=nativeSpell(q);
        SpellAbility basic=c.getFirstSpellAbility();basic.setActivatingPlayer(p);
        Assert.assertFalse(GameActionUtil.getAlternativeCosts(basic,p,true).stream().noneMatch(x->x.isAlternativeCost(AlternativeCost.Decipher)));
    }
}
