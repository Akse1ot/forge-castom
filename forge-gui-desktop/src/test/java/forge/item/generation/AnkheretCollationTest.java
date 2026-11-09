package forge.item.generation;

import forge.ai.AITest;
import forge.StaticData;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class AnkheretCollationTest extends AITest {
    private PaperCard card(String name,String type,String cost,CardRarity rarity) {
        return new PaperCard(CardRules.fromScript(List.of("Name:"+name,"Types:"+type,"ManaCost:"+cost,"Oracle:")),"TEST",rarity);
    }
    private List<PaperCard> cards() {
        List<PaperCard> result=new ArrayList<>();
        for(String color:List.of("W","U","B","R","G"))for(int i=0;i<18;i++)result.add(card("Common "+color+" "+i,"Artifact",color,CardRarity.Common));
        for(int i=0;i<5;i++)result.add(card("Common colorless "+i,"Artifact","1",CardRarity.Common));
        for(int i=0;i<73;i++)result.add(card("Uncommon "+i,"Artifact","1",CardRarity.Uncommon));
        for(int i=0;i<45;i++)result.add(card("Rare "+i,"Artifact","1",CardRarity.Rare));
        for(int i=0;i<14;i++)result.add(card("Mythic "+i,"Artifact","1",CardRarity.MythicRare));
        var rarities=List.of(CardRarity.Common,CardRarity.Uncommon,CardRarity.Rare,CardRarity.MythicRare);int[] counts={6,7,8,1};
        for(int r=0;r<4;r++)for(int i=0;i<counts[r];i++)result.add(card("Land "+r+" "+i,"Land","no cost",rarities.get(r)));
        result.add(card("Basic fixture","Basic Land Plains","no cost",CardRarity.Common));return result;
    }
    @Test public void exactProfileGeneratesUniqueBalancedPacksWithExpectedMarginalFrequencies() {
        var cards=cards();var pools=new AnkheretBoosterCollation.Pools(cards);var random=new Random(97021);
        Map<String,Integer> commonHits=new HashMap<>();int mythics=0;int[] landHits=new int[4];int samples=100000;
        for(int i=0;i<samples;i++) {
            var pack=AnkheretBoosterCollation.generate(pools,random);Assert.assertEquals(pack.size(),15);Assert.assertEquals(new HashSet<>(pack).size(),15);
            int mask=0;
            for(int j=0;j<10;j++) {var c=pack.get(j);Assert.assertEquals(c.getRarity(),CardRarity.Common);Assert.assertFalse(c.getRules().getType().isLand());mask|=c.getRules().getColor().getColor();commonHits.merge(c.getName(),1,Integer::sum);}
            Assert.assertEquals(mask,31);for(int j=10;j<13;j++)Assert.assertEquals(pack.get(j).getRarity(),CardRarity.Uncommon);
            if(pack.get(13).getRarity()==CardRarity.MythicRare)mythics++;else Assert.assertEquals(pack.get(13).getRarity(),CardRarity.Rare);
            var land=pack.get(14);Assert.assertTrue(land.getRules().getType().isLand());Assert.assertFalse(land.getRules().getType().isBasicLand());
            landHits[switch(land.getRarity()){case Common->0;case Uncommon->1;case Rare->2;case MythicRare->3;default->throw new AssertionError();}]++;
        }
        double commonExpected=samples*2.0/19;
        Assert.assertEquals(commonHits.size(),95);
        for(int hits:commonHits.values())Assert.assertEquals((double)hits,commonExpected,6*Math.sqrt(samples*(2.0/19)*(17.0/19)));
        double mythicRate = 14.0 / 104;
        Assert.assertEquals((double)mythics, samples * mythicRate, 6 * Math.sqrt(samples * mythicRate * (1 - mythicRate)));
        int[] weights={80,24,7,1};for(int i=0;i<4;i++){double p=weights[i]/112.0;Assert.assertEquals((double)landHits[i],samples*p,6*Math.sqrt(samples*p*(1-p)));}
    }
    @Test public void incompleteOrDuplicatePoolsFailInsteadOfApproximating() {
        var cards=cards();cards.remove(0);Assert.expectThrows(IllegalStateException.class,()->new AnkheretBoosterCollation.Pools(cards));
        var duplicate=cards();duplicate.add(duplicate.get(0));Assert.expectThrows(IllegalArgumentException.class,()->new AnkheretBoosterCollation.Pools(duplicate));
    }
    @Test public void profileIsExplicitAndUnknownProfileFails() {
        var slots=SealedTemplate.Reader.parseSlots("10 Common, 3 Uncommon, 1 RareMythic, 1 Land");
        var ordinary=new SealedTemplate("TEST",slots);var exact=new SealedTemplate("TEST",slots,AnkheretBoosterCollation.PROFILE);
        Assert.assertNull(ordinary.getCollation());Assert.assertNotEquals(ordinary,exact);Assert.assertEquals(exact.getNumberOfCardsExpected(),15);
        Assert.expectThrows(IllegalArgumentException.class,()->BoosterGenerator.getBoosterPack(new SealedTemplate("TEST",slots,"unknown")));
        Assert.expectThrows(IllegalArgumentException.class,()->BoosterGenerator.getBoosterPack(new SealedTemplate("TEST",List.of(),AnkheretBoosterCollation.PROFILE)));
    }
    @Test public void ordinaryOfficialBoosterStillUsesStandardGeneration() {
        var template=StaticData.instance().getEditions().get("M21").getBoosterTemplate();Assert.assertNull(template.getCollation());var pack=BoosterGenerator.getBoosterPack(template);Assert.assertEquals(pack.size(),template.getNumberOfCardsExpected());Assert.assertTrue(pack.stream().allMatch(c->c.getEdition().equals("M21")));
    }

    @Test
    public void restrictedNamesDoNotMutateTheExactPools() {
        var pools = new AnkheretBoosterCollation.Pools(cards());
        var excluded = List.of("Common W 0", "Uncommon 0", "Rare 0", "Mythic 0", "Land 0 0");
        var random = new Random(8128);
        for (int i = 0; i < 200; i++) {
            var pack = AnkheretBoosterCollation.generate(pools, random, excluded);
            Assert.assertEquals(pack.size(), 15);
            Assert.assertTrue(pack.stream().noneMatch(card -> excluded.contains(card.getName())));
        }
        Assert.assertTrue(pools.commons.get(0).stream().anyMatch(card -> card.getName().equals("Common W 0")));
    }
}
