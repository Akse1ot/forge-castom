package forge.ai;

import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardUtil;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class CannotProduceManaAi {
    private CannotProduceManaAi() {
    }

    static boolean hasTrigger(Player player) {
        return player.getCardsIn(ZoneType.Battlefield).stream().flatMap(card -> card.getTriggers().stream())
                .anyMatch(trigger -> trigger.getMode() == TriggerType.TapsForMana && supports(trigger));
    }

    static boolean canTriggerFrom(SpellAbility source) {
        return source.getHostCard().isLand() && source.getPayCosts() != null && source.getPayCosts().hasTapCost();
    }

    private static boolean supports(Trigger trigger) {
        if (!trigger.isStatic()) {
            return false;
        }
        SpellAbility effect = trigger.ensureAbility();
        return effect != null && effect.getApi() == ApiType.ManaReflected
                && "CannotProduce".equals(effect.getParam("ReflectProperty"))
                && "Defined.TriggeredCard".equals(effect.getParam("Valid"))
                && "You".equals(effect.getParam("Defined"));
    }

    static Map<Trigger, Set<String>> options(SpellAbility source, Player player, String produced) {
        Card land = source.getHostCard();
        if (!canTriggerFrom(source) || !producesMana(produced)
                || land.getGame().getTriggerHandler().isTriggerSuppressed(TriggerType.TapsForMana)) {
            return Collections.emptyMap();
        }
        Map<AbilityKey, Object> event = AbilityKey.mapFromCard(land);
        event.put(AbilityKey.Activator, player);
        event.put(AbilityKey.AbilityMana, source);
        event.put(AbilityKey.Produced, produced);
        Map<Trigger, Set<String>> options = new LinkedHashMap<>();
        for (Trigger trigger : land.getGame().getTriggerHandler().getActiveTrigger(TriggerType.TapsForMana, event)) {
            if (!trigger.getHostCard().getController().equals(player) || !supports(trigger)) {
                continue;
            }
            SpellAbility effect = ability(trigger, source, player, produced);
            Set<String> colors = CardUtil.getReflectableManaColors(effect);
            if (!colors.isEmpty()) {
                options.put(trigger, colors);
            }
        }
        return options;
    }

    static SpellAbility ability(Trigger trigger, SpellAbility source, Player player, String produced) {
        Map<AbilityKey, Object> event = AbilityKey.mapFromCard(source.getHostCard());
        event.put(AbilityKey.Activator, player);
        event.put(AbilityKey.AbilityMana, source);
        event.put(AbilityKey.Produced, produced);
        SpellAbility effect = trigger.ensureAbility().copy(player);
        effect.setTrigger(trigger);
        effect.setTriggeringObjects(trigger.buildTriggeringObjects(event));
        return effect;
    }

    static String mana(SpellAbility effect, String color) {
        int amount = AbilityUtils.calculateAmount(effect.getHostCard(), effect.getParamOrDefault("Amount", "1"), effect);
        return StringUtils.repeat(color, " ", amount);
    }

    private static boolean producesMana(String produced) {
        for (String token : StringUtils.split(produced)) {
            if (!StringUtils.isNumeric(token) || Integer.parseInt(token) > 0) {
                return true;
            }
        }
        return false;
    }

    static boolean canPay(Player player, SpellAbility spell, ManaCostShard shard, Set<String> colors, Card source,
            ManaCostBeingPaid cost) {
        return colors.stream().anyMatch(color -> canPay(player, spell, shard, ManaAtom.fromName(color), source, cost));
    }

    private static boolean canPay(Player player, SpellAbility spell, ManaCostShard shard, byte color, Card source,
            ManaCostBeingPaid cost) {
        return (!shard.isSnow() || source.isSnow())
                && (spell == null || spell.allowsPayingWithShard(source, color))
                && player.getManaPool().canPayForShardWithColor(shard, color)
                && (shard != ManaCostShard.COLORED_X || cost == null
                    || ManaCostBeingPaid.canColoredXShardBePaidByColor(MagicColor.toShortString(color), cost.getXManaCostPaidByColor()));
    }

    static String choose(Player player, SpellAbility spell, ManaCostBeingPaid cost, ManaCostShard preferred,
            Trigger trigger, Set<String> colors, Function<String, String> produced) {
        List<ManaCostShard> shards = cost == null ? new ArrayList<>(List.of(preferred)) : cost.getUnpaidShards();
        Collections.sort(shards);
        if (!preferred.isGeneric() && shards.remove(preferred)) {
            shards.add(0, preferred);
        }
        for (ManaCostShard shard : shards) {
            for (byte color : MagicColor.WUBRG) {
                if (colors.contains(MagicColor.toLongString(color))
                        && java.util.Arrays.stream(StringUtils.split(produced.apply(MagicColor.toShortString(color))))
                            .anyMatch(mana -> canPay(player, spell, shard, ManaAtom.fromName(mana), trigger.getHostCard(), cost))) {
                    return MagicColor.toShortString(color);
                }
            }
        }
        // With an actual payment, let the controller choose surplus mana normally.
        return cost == null ? MagicColor.toShortString(colors.iterator().next()) : null;
    }

    record Payment(Card land, Map<Trigger, String> choices) {
        void apply(WrappedAbility ability) {
            if (ability.getTriggeringObject(AbilityKey.Card) != land) {
                return;
            }
            String color = choices.get(ability.getTrigger());
            if (color != null) {
                ability.getWrappedAbility().getManaPart().setExpressChoice(color);
            }
        }
    }
}
