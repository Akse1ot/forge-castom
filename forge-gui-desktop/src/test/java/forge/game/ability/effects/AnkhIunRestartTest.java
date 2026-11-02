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
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public class AnkhIunRestartTest extends AITest {
    private Card fixture(Player p) {
        var rules=CardRules.fromScript(List.of("Name:Restart modifier fixture","Types:Creature Phoenix","ManaCost:2 R","PT:4/4","K:Flying","K:Haste",
                "S:Mode$ MustAttack | ValidCreature$ Card.Self | UnlessAttacked$ True",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self | IunRestartTrigger$ True | Execute$ Life",
                "T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self | Execute$ Life",
                "SVar:Life:DB$ GainLife | Defined$ You | LifeAmount$ 1","Oracle:"));
        Card c=CardFactory.getCard(new PaperCard(rules,"",CardRarity.Rare),p,p.getGame());p.getZone(ZoneType.Battlefield).add(c);return c;
    }
    private void remove(Card source,Card... returned) {
        source.clearRemembered();source.addRemembered(List.of(returned));var a=AbilityFactory.getAbility("DB$ IunPostRestart",source);a.setActivatingPlayer(source.getController());AbilityUtils.resolve(a);
    }
    private void used(Card card,boolean used) {
        Assert.assertEquals(card.getTriggers().stream().filter(t->t.hasParam("IunRestartTrigger")).count(),used?0L:1L);
        Assert.assertTrue(card.hasKeyword(Keyword.FLYING));Assert.assertTrue(card.hasKeyword(Keyword.HASTE));Assert.assertFalse(card.getStaticAbilities().isEmpty());
    }
    @Test public void removesOnlyTaggedIntrinsicTriggerFromActualReturnedSource() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card source=fixture(p),other=fixture(p);
        source.addTrigger(TriggerHandler.parseTrigger("Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | ValidCard$ Card.Self | IunRestartTrigger$ True | Execute$ Life",source,false));
        remove(source,source,other);Assert.assertEquals(source.getTriggers().size(),2,"unrelated and externally granted triggers remain");used(other,false);
        Assert.assertTrue(source.getTriggers().stream().anyMatch(t->t.hasParam("IunRestartTrigger")&&!t.isIntrinsic()));
    }
    @Test public void usedModifierSurvivesGameCopiesAndResetsWithZoneObject() {
        Game g=initAndCreateGame();Card source=fixture(g.getPlayers().get(1));remove(source,source);used(source,true);
        for(Game copy:List.of(new GameCopier(g).makeCopy(),new GameSnapshot(g).makeCopy()))used(copy.findById(source.getId()),true);
        Card moved=g.getAction().moveToGraveyard(source,null);used(moved,false);
    }
    @Test public void snapshotRestoresBothUsedAndUnusedModifierStates() {
        Game g=initAndCreateGame();Card source=fixture(g.getPlayers().get(1));var unused=new GameSnapshot(g);unused.makeCopy();remove(source,source);unused.restoreGameState(g);source=g.findById(source.getId());used(source,false);
        remove(source,source);var used=new GameSnapshot(g);used.makeCopy();g.getAction().moveToGraveyard(source,null);used.restoreGameState(g);used(g.findById(source.getId()),true);
    }
    @Test public void staleRememberedReturnDoesNotStripNewIncarnation() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card source=fixture(p);Card departed=g.getAction().moveToGraveyard(source,null);Card returned=g.getAction().moveToPlay(departed,p,null,null);remove(source,source);used(returned,false);
    }
    @Test public void cardsRememberedOutsideBattlefieldOrWithAnotherPhysicalIdAreUntouched() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card source=fixture(p),other=fixture(p);remove(source,other);used(source,false);used(other,false);
        Card departed=g.getAction().moveToGraveyard(source,null);remove(departed,departed);used(departed,false);
    }
}
