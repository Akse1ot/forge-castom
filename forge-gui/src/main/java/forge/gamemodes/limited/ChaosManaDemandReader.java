package forge.gamemodes.limited;

import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.ability.AbilityFactory;
import forge.item.PaperCard;

import java.util.*;

import static forge.gamemodes.limited.ChaosReportFile.fields;

/** Confirmed primary-face paths only. All omitted paths remain explicit evidence, not a card rating. */
final class ChaosManaDemandReader {
    record Profile(ChaosManaDemand.Card resource, ChaosManaDemand.Cost castingCost, boolean pureMana, boolean potentialMana,
                   List<String> unknown, Map<String, Object> facts) {}
    private final Map<PaperCard, Profile> profiles = new IdentityHashMap<>();

    Profile read(PaperCard card) { return profiles.computeIfAbsent(card, this::parse); }
    void clear() { profiles.clear(); }
    static Map<String, Object> cost(PaperCard c) {
        var mc=c.getMainFace().getManaCost();
        List<Integer> masks=new ArrayList<>();
        String reason=null;
        if(mc.isNoCost())reason="no_casting_cost";
        if(mc.countX()>0)reason="X_not_fixed";
        for(var s:mc) {
            if(s==ManaCostShard.X)continue;
            if(s.isPhyrexian()||s.isOr2Generic()||s.isSnow()) {
                reason="alternate_or_snow_payment";
                continue;
            }
            int mask=s.isColorless()?32:0;
            if(!s.isColorless())for(int i=0; i<5; i++)if(s.canBePaidWithManaOfColor((byte)(1<<i)))mask|=1<<i;
            if(mask!=0)masks.add(mask);
        }
        return fields("generic", Math.max(0, mc.getGenericCost()), "symbols", masks, "cmc", mc.getCMC(), "reason", reason, "text", mc.toString());
    }
    static ChaosManaDemand.Cost castingCost(PaperCard c) {
        var co=cost(c);
        List<Integer> pips=new ArrayList<>();
        for(Object x:(List<?>)co.get("symbols"))pips.add(((Number)x).intValue());
        return new ChaosManaDemand.Cost(((Number)co.get("generic")).intValue(), pips, ((Number)co.get("cmc")).intValue(), co.get("reason")==null, (String)co.get("text"));
    }
    private Profile parse(PaperCard c) {
        var f=c.getMainFace();
        boolean land=f.getType().isLand(), creature=f.getType().isCreature();
        List<String> uncertainty=new ArrayList<>(), records=new ArrayList<>();
        List<Map<String, Object>> paths=new ArrayList<>();
        Map<String, String> vars=new HashMap<>();
        f.getVariables().forEach(e->vars.put(e.getKey(), e.getValue()));
        boolean tapped=false, availabilityUnknown=false, indirectMana=false;
        int unknownPaths=0;
        for(var kw:f.getKeywords()) {
            if(kw.equals("CARDNAME enters tapped."))tapped=true;
            if(kw.startsWith("ETBReplacement:")) {
                String[] parts=kw.split(":");
                String child=vars.get(parts[parts.length-1]);
                if(child!=null&&child.contains("DB$ Tap")&&child.contains("Defined$ Self"))tapped=true;
                else if(child==null||!child.contains("DB$ ChooseColor")) {
                    availabilityUnknown=true;
                    uncertainty.add("keyword_ETB_obligation:"+kw);
                }
            }
            if(kw.contains("doesn't untap")||kw.startsWith("Sunburst")||kw.startsWith("Fading")||kw.startsWith("Vanishing")) {
                availabilityUnknown=true;
                uncertainty.add("resource_lifecycle:"+kw);
            }
        }
        for(var rep:f.getReplacements()) {
            records.add(rep);
            var p=AbilityFactory.getMapParams(rep);
            String child=vars.get(p.get("ReplaceWith"));
            var q=child==null?Map.<String, String>of():AbilityFactory.getMapParams(child);
            if("Battlefield".equals(p.get("Destination"))&&"Card.Self".equals(p.get("ValidCard"))) {
                if("Tap".equals(q.get("DB"))&&"Self".equals(q.get("Defined"))&&q.keySet().stream().noneMatch(k->k.startsWith("Condition")))tapped=true;
                else {
                    availabilityUnknown=true;
                    uncertainty.add("conditional_or_other_ETB_replacement");
                }
            }
        }
        for(var tr:f.getTriggers()) {
            records.add(tr);
            var p=AbilityFactory.getMapParams(tr);
            String child=vars.get(p.get("Execute"));
            String chain=child==null?"":child;
            if("Battlefield".equals(p.get("Destination"))&&"Card.Self".equals(p.get("ValidCard"))&& (chain.contains("DB$ Sacrifice")||chain.contains("Destination$ Hand")||chain.contains("DB$ Tap")||chain.contains("DB$ ChangeZoneAll"))) {
                availabilityUnknown=true;
                uncertainty.add("ETB_resource_obligation");
            }
        }
        int basic=0;
        for(int i=0; i<5; i++)if(f.getType().hasSubtype(MagicColor.Constant.BASIC_LANDS.get(i)))basic|=1<<i;
        if(land&&basic!=0)paths.add(fields("activation", 0, "units", List.of(basic), "origin", "basic_subtype"));
        for(var ab:f.getAbilities()) {
            records.add(ab);
            var p=AbilityFactory.getMapParams(ab);
            String api=p.getOrDefault("AB", "");
            if(api.equals("Untap")&&(p.getOrDefault("AILogic", "").equals("PoolExtraMana")||p.getOrDefault("ValidTgts", "").matches(".*(?:Land|Forest|Island|Plains|Mountain|Swamp).*"))) {
                indirectMana=true;
                uncertainty.add("indirect_untap_mana_unmodeled");
            }
            if(api.equals("ChangeZone")&&p.getOrDefault("Destination", "").equals("Battlefield")&&p.getOrDefault("ChangeType", "").contains("Land")) {
                indirectMana=true;
                uncertainty.add("search_land_setup_unmodeled");
            }
            if(api.equals("ManaReflected")) {
                indirectMana=true;
                uncertainty.add("reflected_mana_unmodeled");
            }
            if(!"Mana".equals(p.get("AB")))continue;
            String co=p.getOrDefault("Cost", "");
            String produced=p.getOrDefault("Produced", "");
            int amount=1;
            boolean safe=p.keySet().stream().allMatch(Set.of("AB", "Cost", "Produced", "Amount", "SpellDescription", "Description", "Secondary")::contains)&&co.matches("(?:[0-2] )?T");
            try {
                amount=Integer.parseInt(p.getOrDefault("Amount", "1"));
            }catch(NumberFormatException e) {
                safe=false;
            }
            List<Integer> units=new ArrayList<>();
            if(produced.equals("Any")) {
                if(amount==1)units.add(31);
                else safe=false;
            } else if(produced.startsWith("Combo ")) {
                int mask=0;
                for(String x:produced.substring(6).split(" ")) {
                    int i="WUBRGC".indexOf(x);
                    if(x.length()!=1||i<0) {
                        safe=false;
                        break;
                    }
                    mask|=1<<i;
                }
                if(amount==1)units.add(mask);
                else safe=false;
            }else if(produced.matches("[1-9]")) {
                for(int i=0; i<Integer.parseInt(produced)*amount; i++)units.add(32);
            } else if(produced.matches("[WUBRGC](?: [WUBRGC])*")) {
                for(int a=0; a<amount; a++)for(String x:produced.split(" "))units.add(1<<"WUBRGC".indexOf(x));
            } else safe=false;
            if(units.size()>9||units.isEmpty())safe=false;
            if(safe)paths.add(fields("activation", co.equals("T")?0:Integer.parseInt(co.substring(0, 1)), "units", units, "origin", ab));
            else {
                unknownPaths++;
                uncertainty.add("unrecognized_mana_path:"+ab);
            }
        }
        f.getStaticAbilities().forEach(records::add);
        boolean potential=indirectMana||land||!paths.isEmpty()||records.stream().anyMatch(s->s.contains("Mana")||s.contains("TokenScript"))||vars.values().stream().anyMatch(s->s.contains("$ Mana")||s.contains("treasure")||s.contains("ManaColor"));
        if(paths.size()>1)uncertainty.add("alternative_paths_not_combined");
        paths.sort(Comparator.comparingInt(p->((Number)p.get("activation")).intValue()));
        Map<String, Object> selected=paths.isEmpty()?null:paths.get(0);
        if(availabilityUnknown) {
            selected=null;
            uncertainty.add("confirmed_use_withheld_due_to_lifecycle");
        }
        if(potential&&selected==null)uncertainty.add("no_confirmed_path_not_zero_card_utility");
        if(cost(c).get("reason")!=null&&!land) {
            selected=null;
            uncertainty.add("source_setup_cost_unmodeled");
        }
        boolean pure=!land&&!creature&&!f.getStaticAbilities().iterator().hasNext()&&!f.getTriggers().iterator().hasNext() &&f.getAbilities().iterator().hasNext();
        for(var kw:f.getKeywords())pure &= kw.equals("CARDNAME enters tapped.");
        for(var ab:f.getAbilities())pure &= "Mana".equals(AbilityFactory.getMapParams(ab).get("AB"));
        if(!f.getType().isArtifact()&&!f.getType().isEnchantment())pure=false;
        var co=cost(c);
        List<Integer> pips=new ArrayList<>();
        for(Object x:(List<?>)co.get("symbols"))pips.add(((Number)x).intValue());
        var casting=new ChaosManaDemand.Cost(((Number)co.get("generic")).intValue(), pips, ((Number)co.get("cmc")).intValue(), co.get("reason")==null, (String)co.get("text"));
        ChaosManaDemand.Card resource=ChaosManaDemand.BLANK;
        if(selected!=null) {
            List<Integer> units=new ArrayList<>();
            for(Object x:(List<?>)selected.get("units"))units.add(((Number)x).intValue());
            resource=ChaosManaDemand.resource(casting, land, creature, tapped, new ChaosManaDemand.Source(((Number)selected.get("activation")).intValue(), units));
        }
        List<String> keywords=new ArrayList<>();
        f.getKeywords().forEach(keywords::add);
        var facts=fields("printing", ChaosDraftReport.printing(c), "type", f.getType().toString(), "power", f.getPower(), "toughness", f.getToughness(), "creature", creature, "land", land, "cost", casting, "pureMana", pure, "intrinsicValue", null, "keywords", List.copyOf(keywords), "abilities", List.copyOf(records), "variables", new TreeMap<>(vars));
        return new Profile(resource, casting, pure, potential, List.copyOf(uncertainty), Collections.unmodifiableMap(facts));
    }
}
