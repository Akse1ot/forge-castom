package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AnkhScriptSupportTest extends AITest {
    @Test
    public void landPlayFlagIsEventOnlyAndDiffersFromReanimation() {
        for(int mode=0;mode<3;mode++) {
            Game g=initAndCreateGame();Player p=g.getPlayers().get(1);
            Card c=fixture(p,"Land play observer","Land",
                    "R:Event$ Moved | ValidCard$ Card.Self | Origin$ Graveyard | Destination$ Battlefield | LandPlayed$ True | ReplaceWith$ Mark | ReplacementResult$ Updated",
                    "SVar:Mark:DB$ PutCounter | Defined$ Self | CounterType$ VOYAGE | CounterNum$ 1 | ETB$ True");
            c=g.getAction().moveToGraveyard(c,null);
            if(mode==0)c=g.getAction().moveToPlay(c,p,null,null);
            else {
                var cause=mode==1?c.getSpellAbilities().stream().filter(forge.game.spellability.SpellAbility::isLandAbility).findFirst().orElseThrow()
                        :forge.game.ability.AbilityFactory.getAbility("DB$ Play | Defined$ Self",c);
                cause.setActivatingPlayer(p);c=p.playLand(c,cause);
            }
            Assert.assertEquals(c.getCounters(forge.game.card.CounterType.getType("VOYAGE")),mode==0?0:1);
            Assert.assertEquals(p.getLandsPlayedThisTurn(),mode==0?0:1);
            c=g.getAction().moveToGraveyard(c,null);c=g.getAction().moveToPlay(c,p,null,null);
            Assert.assertEquals(c.getCounters(forge.game.card.CounterType.getType("VOYAGE")),0,"play flag cannot leak into a later entry");
        }
    }
    @Test
    public void payerAmountUsesManaRecordsAndIsFrozenOnTrigger() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);
        Card dragon=fixture(p,"Payer observer","Creature Dragon","PT:5/5",
                "T:Mode$ SpellCast | ValidSA$ Spell.IsTargeting Self | ManaSpentByYou$ True | Execute$ Gain",
                "SVar:Gain:DB$ GainLife | Defined$ You | LifeAmount$ Spent","SVar:Spent:TriggerCount$Amount");
        Card source=addCardToZone("Giant Growth",q,forge.game.zone.ZoneType.Hand),mine=addCard("Island",p),theirs=addCard("Forest",q);
        var cast=source.getFirstSpellAbility();cast.setActivatingPlayer(q);cast.getTargets().add(dragon);
        for(int i=0;i<2;i++)cast.getPayingMana().add(new forge.game.mana.Mana(forge.card.MagicColor.BLUE,mine,mine.getManaAbilities().get(0).getManaPart(),p));
        for(int i=0;i<3;i++)cast.getPayingMana().add(new forge.game.mana.Mana(forge.card.MagicColor.GREEN,theirs,theirs.getManaAbilities().get(0).getManaPart(),q));
        var event=forge.game.ability.AbilityKey.mapFromPlayer(q);event.put(forge.game.ability.AbilityKey.SpellAbility,cast);event.put(forge.game.ability.AbilityKey.Activator,q);
        var trigger=dragon.getTriggers().get(0);Assert.assertTrue(trigger.performTest(event),"another player may cast using your mana");
        var ability=trigger.ensureAbility();ability.setActivatingPlayer(p);trigger.setTriggeringObjects(ability,event);
        Assert.assertEquals(ability.getTriggeringObject(forge.game.ability.AbilityKey.Amount),2);
        cast.clearManaPaid();Assert.assertFalse(trigger.performTest(event));
        dragon.setController(q,g.getNextTimestamp());
        Assert.assertEquals(ability.getTriggeringObject(forge.game.ability.AbilityKey.Amount),2,"later payment/controller state cannot change the amount");
    }
    @Test
    public void selectedAlternativeDistinguishesFreeExternalAndReducedCosts() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);Card c=addCardToZone("Archive Trap",p,forge.game.zone.ZoneType.Hand);
        var basic=c.getFirstSpellAbility();basic.setActivatingPlayer(p);
        Assert.assertFalse(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(basic));
        var reduced=basic.copy();reduced.setPayCosts(new forge.game.cost.Cost("0",false));
        Assert.assertFalse(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(reduced));
        Assert.assertTrue(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(basic.copyWithNoManaCost()));
        var nativeCost=basic.copy();nativeCost.setAlternativeCost(forge.game.spellability.AlternativeCost.Decipher);
        Assert.assertTrue(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(nativeCost));
        var optional=basic.copy();optional.addOptionalCost(forge.game.spellability.OptionalCost.AltCost);
        Assert.assertTrue(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(optional));
        fixture(p,"External alternative","Artifact","S:Mode$ AlternativeCost | ValidSA$ Spell | ValidPlayer$ You | Cost$ W U B R G");
        var external=forge.game.staticability.StaticAbilityAlternativeCost.alternativeCosts(basic,c,p).get(0);
        Assert.assertNull(external.getAlternativeCost());
        Assert.assertTrue(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(external));
        Assert.assertTrue(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(external.copy()));
        Assert.assertFalse(forge.game.spellability.AlternativeCostRuleUtil.usesAlternativeOrFreeCost(basic),"variant does not mark the original");
    }
    private Card fixture(Player p,String name,String types,String... extra) {
        var lines=new java.util.ArrayList<>(java.util.List.of("Name:"+name,"ManaCost:0","Types:"+types));
        lines.addAll(java.util.List.of(extra));lines.add("Oracle:");
        Card c=forge.game.card.CardFactory.getCard(new forge.item.PaperCard(forge.card.CardRules.fromScript(lines),"TST",forge.card.CardRarity.Common),p,p.getGame());
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(forge.game.zone.ZoneType.Battlefield).add(c);return c;
    }
    @Test
    public void multiplyOnlyBlueAndKeepLegacyAmountReplacement() {
        for(boolean selective:new boolean[]{false,true}) {
            Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);
            Card modifier=fixture(p,"Mana modifier","Artifact",
                    "R:Event$ ProduceMana | ActiveZones$ Battlefield | ValidCard$ Land.YouCtrl | ValidSA$ Activated.ManaAbility | ReplaceWith$ Replace"+(selective?" | ManaContains$ U":""),
                    "SVar:Replace:DB$ ReplaceMana | "+(selective?"MultiplyColor$ U | Factor$ 2":"ReplaceAmount$ 2"));
            Card land=fixture(p,"Mixed land","Land","A:AB$ Mana | Cost$ 0 | Produced$ U R C");
            var a=land.getManaAbilities().get(0);a.setActivatingPlayer(p);AbilityUtils.resolve(a);
            var event=forge.game.ability.AbilityKey.mapFromAffected(land);
            event.put(forge.game.ability.AbilityKey.Player,p);event.put(forge.game.ability.AbilityKey.Activator,p);
            event.put(forge.game.ability.AbilityKey.AbilityMana,a);event.put(forge.game.ability.AbilityKey.Mana,"R");
            Assert.assertEquals(modifier.getReplacementEffects().get(0).canReplace(event),!selective);
            Assert.assertEquals(p.getManaPool().getAmountOfColor(forge.card.MagicColor.BLUE),2);
            Assert.assertEquals(p.getManaPool().getAmountOfColor(forge.card.MagicColor.RED),selective?1:2);
            Assert.assertEquals(p.getManaPool().getAmountOfColor((byte)forge.card.mana.ManaAtom.COLORLESS),selective?1:2);
            Card foreign=fixture(q,"Other land","Land","A:AB$ Mana | Cost$ 0 | Produced$ U");
            a=foreign.getManaAbilities().get(0);a.setActivatingPlayer(q);AbilityUtils.resolve(a);
            Assert.assertEquals(q.getManaPool().getAmountOfColor(forge.card.MagicColor.BLUE),1);
        }
    }
    @Test
    public void opponentScryCountUsesExistingHistoryAndSurvivesCopies() {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1),q=g.getPlayers().get(0);
        Card c=addCard("Grizzly Bears",p);var a=c.getFirstSpellAbility();a.setActivatingPlayer(p);
        Assert.assertEquals(AbilityUtils.calculateAmount(c,"PlayerCountOpponents$YouScryThisTurn",a),0);
        q.incScryThisTurn();q.incScryThisTurn();p.incScryThisTurn();
        Assert.assertEquals(AbilityUtils.calculateAmount(c,"PlayerCountOpponents$YouScryThisTurn",a),2);
        Game copied=new GameCopier(g).makeCopy();Assert.assertEquals(copied.getPlayer(q.getId()).getScryThisTurn(),2);
        var snapshot=new GameSnapshot(g);snapshot.makeCopy();q.setScryThisTurn(0);snapshot.restoreGameState(g);
        Assert.assertEquals(g.getPlayer(q.getId()).getScryThisTurn(),2);
        g.getPlayer(q.getId()).onCleanupPhase();
        Assert.assertEquals(g.getPlayer(q.getId()).getScryThisTurn(),0);
    }
}
