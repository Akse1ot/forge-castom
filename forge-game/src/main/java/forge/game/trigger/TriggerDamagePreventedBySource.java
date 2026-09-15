package forge.game.trigger;

import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.spellability.SpellAbility;
import forge.util.Expressions;
import forge.util.Localizer;

public class TriggerDamagePreventedBySource extends Trigger {

    public TriggerDamagePreventedBySource(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    @Override
    public final boolean performTest(final Map<AbilityKey, Object> runParams) {
        if (!matchesValidParam("ValidSource", runParams.get(AbilityKey.DamageSource))) {
            return false;
        }
        if (!matchesValidParam("ValidTarget", runParams.get(AbilityKey.DamageTarget))) {
            return false;
        }

        if (hasParam("CombatDamage")) {
            if (getParam("CombatDamage").equals("True") != ((Boolean) runParams.get(AbilityKey.IsCombatDamage))) {
                return false;
            }
        }

        if (hasParam("DamageAmount")) {
            final String fullParam = getParam("DamageAmount");
            final String operator = fullParam.substring(0, 2);
            final int operand = Integer.parseInt(fullParam.substring(2));
            final int actualAmount = (Integer) runParams.get(AbilityKey.DamageAmount);

            if (!Expressions.compare(actualAmount, operator, operand)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public final void setTriggeringObjects(final SpellAbility sa, final Map<AbilityKey, Object> runParams) {
        sa.setTriggeringObject(AbilityKey.Source,
                CardCopyService.getLKICopy((Card) runParams.get(AbilityKey.DamageSource)));
        sa.setTriggeringObject(AbilityKey.Target, runParams.get(AbilityKey.DamageTarget));
        sa.setTriggeringObjectsFrom(runParams, AbilityKey.DamageAmount);
    }

    @Override
    public String getImportantStackObjects(final SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        sb.append(Localizer.getInstance().getMessage("lblDamageSource")).append(": ")
                .append(sa.getTriggeringObject(AbilityKey.Source)).append(", ");
        sb.append(Localizer.getInstance().getMessage("lblDamageTarget")).append(": ")
                .append(sa.getTriggeringObject(AbilityKey.Target)).append(", ");
        sb.append(Localizer.getInstance().getMessage("lblAmount")).append(": ")
                .append(sa.getTriggeringObject(AbilityKey.DamageAmount));
        return sb.toString();
    }
}