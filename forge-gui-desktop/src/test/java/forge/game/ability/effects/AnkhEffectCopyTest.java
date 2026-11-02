package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public class AnkhEffectCopyTest extends AITest {
    private Card effect(Game g, Player p) {
        var rules = CardRules.fromScript(List.of("Name:Copy effect fixture", "Types:Instant", "ManaCost:W",
                "A:SP$ Effect | CopyForSimulation$ True | RememberObjects$ Targeted & You | ValidTgts$ Creature | ImprintCards$ Self | ReplacementEffects$ Prevent | Triggers$ Seen",
                "SVar:Prevent:Event$ DamageDone | ValidTarget$ You | PreventionEffect$ True",
                "SVar:Seen:Mode$ Worship | Execute$ Life | TriggerZones$ Command",
                "SVar:Life:DB$ GainLife | Defined$ You | LifeAmount$ 1", "Oracle:"));
        Card source=CardFactory.getCard(new PaperCard(rules,"",CardRarity.Common),p,g);
        p.getZone(ZoneType.Graveyard).add(source);
        var a=source.getFirstSpellAbility();a.setActivatingPlayer(p);a.getTargets().add(addCard("Grizzly Bears",p));AbilityUtils.resolve(a);
        return p.getCardsIn(ZoneType.Command).stream().filter(c->c.getEffectSource()==source).findFirst().orElseThrow();
    }
    private void check(Game copy,Card original) {
        Card c=copy.findById(original.getId());
        Assert.assertEquals(c.getReplacementEffects().size(),1);Assert.assertEquals(c.getTriggers().size(),1);
        Assert.assertEquals(c.getRememberedCount(),2);Assert.assertEquals(c.getSVar("Life"),"DB$ GainLife | Defined$ You | LifeAmount$ 1");
        Assert.assertSame(c.getEffectSource().getGame(),copy);Assert.assertSame(c.getImprintedCards().getFirst().getGame(),copy);
        for(Object o:c.getRemembered()) {
            if(o instanceof Card card)Assert.assertSame(card.getGame(),copy);
            if(o instanceof Player player)Assert.assertSame(player.getGame(),copy);
        }
        copy.getEndOfTurn().executeUntil();Assert.assertFalse(copy.getCardsIn(ZoneType.Command).contains(c));
    }
    @Test public void dynamicEffectTraitsAndRelationshipsSurviveBothCopyPathsAndExpire() {
        Game g=initAndCreateGame();Card c=effect(g,g.getPlayers().get(1));
        check(new GameCopier(g).makeCopy(),c);check(new GameSnapshot(g).makeCopy(),c);
        Assert.assertTrue(c.isInZone(ZoneType.Command),"copied expiry must not expire original");
    }
    @Test public void snapshotRestoresEffectAfterItsOriginalExpired() {
        Game g=initAndCreateGame();Card c=effect(g,g.getPlayers().get(1));var snapshot=new GameSnapshot(g);snapshot.makeCopy();
        g.getEndOfTurn().executeUntil();snapshot.restoreGameState(g);check(g,c);
    }
    @Test public void grantedWorshipSurvivesSnapshotAndRollbackWithoutBecomingPrinted() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card c=addCard("Grizzly Bears",p);
        var grant=AbilityFactory.getAbility("AB$ Pump | Cost$ 0 | Defined$ Self | KW$ Worship:2 | Duration$ Permanent",c);grant.setActivatingPlayer(p);AbilityUtils.resolve(grant);
        var snapshot=new GameSnapshot(g);Game copy=snapshot.makeCopy();Assert.assertTrue(copy.findById(c.getId()).hasKeyword(Keyword.WORSHIP));
        var moved=g.getAction().moveToGraveyard(c,null);Assert.assertFalse(moved.hasKeyword(Keyword.WORSHIP));
        snapshot.restoreGameState(g);Assert.assertTrue(g.findById(c.getId()).hasKeyword(Keyword.WORSHIP));
    }
}
