package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.ArrayList;
import java.util.List;

public class AnkhModalGroupsTest extends AITest {
    private Card fixture(Player p,boolean grouped,boolean target,boolean mana) {
        var lines=List.of("Name:Modal groups fixture","ManaCost:2 W","Types:Sorcery",
                "A:SP$ Charm | Choices$ One,Two,Three,Four"+(grouped?" | ModalChoiceGroups$ 2,2":""),
                "SVar:One:"+(target?"DB$ Destroy | ValidTgts$ Artifact":mana?"DB$ Mana | Produced$ R | Amount$ 3":"DB$ GainLife | Defined$ You | LifeAmount$ 1")+" | SpellDescription$ First mode.",
                "SVar:Two:DB$ GainLife | Defined$ You | LifeAmount$ 2 | SpellDescription$ Second mode.",
                "SVar:Three:DB$ GainLife | Defined$ ChoosingPlayer | LifeAmount$ 4 | SpellDescription$ Third mode.",
                "SVar:Four:DB$ GainLife | Defined$ ChoosingPlayer | LifeAmount$ 5 | SpellDescription$ Fourth mode.","Oracle:");
        Card c=CardFactory.getCard(new PaperCard(CardRules.fromScript(lines),"TST",CardRarity.Common),p,p.getGame());
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(ZoneType.Hand).add(c);return c;
    }
    private void chooser(Player p,int index,List<Player> calls,boolean invalid) {
        p.dangerouslySetController(new PlayerControllerAi(p.getGame(),p,p.getLobbyPlayer()) {
            @Override public List<AbilitySub> chooseModeForAbility(SpellAbility a,List<AbilitySub> possible,int min,int max,boolean repeat) {
                calls.add(p);Assert.assertEquals(min,1);Assert.assertEquals(max,1);Assert.assertFalse(repeat);
                return invalid?new ArrayList<>(possible):new ArrayList<>(List.of(possible.get(Math.min(index,possible.size()-1))));
            }
        });
    }
    private int parts(SpellAbility a){int n=0;for(var s=a.getSubAbility();s!=null;s=s.getSubAbility())n++;return n;}
    private void cast(Game g,SpellAbility a){a.setHostCard(g.getAction().moveToStack(a.getHostCard(),a));g.getStack().add(a);}
    private void drain(Game g){for(int i=0;i<20;i++){g.getTriggerHandler().runWaitingTriggers();g.getStack().addAllTriggeredAbilitiesToStack();if(g.getStack().isEmpty())return;g.getStack().resolveStack();}Assert.fail("undrained stack");}
    @Test public void bothChoicesPrecedeEffectsAndSpellCopyKeepsThem() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);var calls=new ArrayList<Player>();chooser(p,1,calls,false);chooser(q,0,calls,false);
        Card c=fixture(p,true,false,false);var a=c.getFirstSpellAbility();a.setActivatingPlayer(p);int before=p.getLife(),other=q.getLife();
        Assert.assertTrue(CharmEffect.makeChoices(a));Assert.assertEquals(calls,List.of(p,q));Assert.assertEquals(parts(a),2);Assert.assertEquals(a.getChosenList().size(),2);Assert.assertSame(a.getChoosingPlayer(),q);
        Assert.assertTrue(c.isInZone(ZoneType.Hand));Assert.assertEquals(p.getLife(),before);Assert.assertEquals(q.getLife(),other);
        var copy=CardFactory.copySpellAbilityAndPossiblyHost(a,a,p);Assert.assertTrue(CharmEffect.makeChoices(copy));Assert.assertEquals(calls.size(),2);Assert.assertEquals(parts(copy),2);Assert.assertSame(copy.getChoosingPlayer(),q);
        g.getStack().add(copy);drain(g);Assert.assertEquals(p.getLife(),before+2);Assert.assertEquals(q.getLife(),other+4);
    }
    @Test public void rejectsMoreThanOneModeInAGroup() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);var calls=new ArrayList<Player>();chooser(p,0,calls,true);var a=fixture(p,true,false,false).getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertFalse(CharmEffect.makeChoices(a));Assert.assertEquals(parts(a),0);Assert.assertNull(a.getChosenList());
    }
    @Test public void requiredTargetsAreCheckedWithinTheirOriginalGroup() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);var calls=new ArrayList<Player>();chooser(p,0,calls,false);chooser(q,0,calls,false);var a=fixture(p,true,true,false).getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertTrue(CharmEffect.makeChoices(a));Assert.assertEquals(a.getChosenList().get(0).getParam("SpellDescription"),"Second mode.");Assert.assertEquals(parts(a),2);
    }
    @Test public void ordinaryCharmKeepsItsSingleChoice() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);var calls=new ArrayList<Player>();chooser(p,1,calls,false);var a=fixture(p,false,false,false).getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertTrue(CharmEffect.makeChoices(a));Assert.assertEquals(parts(a),1);Assert.assertEquals(calls.size(),1);Assert.assertNull(a.getChoosingPlayer());
    }
    @Test public void sorceryWithManaModeStillUsesTheStack() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);var calls=new ArrayList<Player>();chooser(p,0,calls,false);chooser(q,0,calls,false);var a=fixture(p,true,false,true).getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertTrue(CharmEffect.makeChoices(a));Assert.assertFalse(a.isManaAbility());cast(g,a);Assert.assertEquals(p.getManaPool().totalMana(),0);drain(g);Assert.assertEquals(p.getManaPool().getAmountOfColor(forge.card.MagicColor.RED),3);
    }
    @Test public void aiAndSnapshotCopiesMapOpponentAndKeepBothGroups() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);var calls=new ArrayList<Player>();chooser(p,1,calls,false);chooser(q,0,calls,false);var a=fixture(p,true,false,false).getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertTrue(CharmEffect.makeChoices(a));int life=p.getLife(),other=q.getLife();cast(g,a);
        Game copied=new forge.ai.simulation.GameCopier(g).withStack(true).makeCopy();var copiedAbility=copied.getStack().peekAbility();Assert.assertEquals(parts(copiedAbility),2);Assert.assertSame(copiedAbility.getChoosingPlayer().getGame(),copied);drain(copied);Assert.assertEquals(copied.getPlayer(q.getId()).getLife(),other+4);Assert.assertEquals(q.getLife(),other);
        var snapshot=new GameSnapshot(g);snapshot.makeCopy();drain(g);snapshot.restoreGameState(g);Assert.assertEquals(g.getPlayer(p.getId()).getLife(),life);Assert.assertSame(g.getStack().peekAbility().getChoosingPlayer().getGame(),g);drain(g);Assert.assertEquals(g.getPlayer(p.getId()).getLife(),life+2);Assert.assertEquals(g.getPlayer(q.getId()).getLife(),other+4);
    }
    @Test public void defaultAiProducesOneLegalChoicePerGroup() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);p.setLife(5,null);var a=fixture(p,true,false,false).getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertTrue(CharmEffect.makeChoices(a));Assert.assertEquals(parts(a),2);Assert.assertEquals(a.getChosenList().size(),2);
    }
    @Test public void multiplayerCasterSelectsTheOneOpponentWhoChooses() {
        Game g=initAndCreateThreePlayerGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0),r=g.getPlayers().get(2);
        Assert.assertEquals(p.getOpponents().size(),2);var calls=new ArrayList<Player>();chooser(r,1,calls,false);
        p.dangerouslySetController(new PlayerControllerAi(g,p,p.getLobbyPlayer()) {
            @Override public List<AbilitySub> chooseModeForAbility(SpellAbility a,List<AbilitySub> possible,int min,int max,boolean repeat) {
                calls.add(p);return List.of(possible.get(0));
            }
            @Override @SuppressWarnings("unchecked") public <T extends forge.game.GameEntity> T chooseSingleEntityForEffect(
                    forge.util.collect.FCollectionView<T> options,forge.game.player.DelayedReveal reveal,SpellAbility a,String title,boolean optional,Player related,java.util.Map<String,Object> params) {
                Assert.assertTrue(options.contains(r));return (T)r;
            }
        });
        var a=fixture(p,true,false,false).getFirstSpellAbility();a.setActivatingPlayer(p);int other=q.getLife(),selected=r.getLife();
        Assert.assertTrue(CharmEffect.makeChoices(a));Assert.assertSame(a.getChoosingPlayer(),r);Assert.assertEquals(calls,List.of(p,r));cast(g,a);drain(g);Assert.assertEquals(q.getLife(),other);Assert.assertEquals(r.getLife(),selected+5);
    }
}
