package forge.game.card;

import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class CardAbilityLabelUtil {
    private CardAbilityLabelUtil() {
    }

    /**
     * Returns whether the card's current SpellAbility graph contains an ability
     * with the specified PrecostDesc label.
     */
    public static boolean hasAbilityLabel(final Card card, final String label) {
        if (card == null) {
            return false;
        }

        final String normalized = normalizeLabel(label);
        if (normalized.isEmpty()) {
            return false;
        }

        final Set<SpellAbility> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (final SpellAbility sa : card.getSpellAbilities()) {
            if (hasAbilityLabel(sa, normalized, visited)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasAbilityLabel(final SpellAbility sa, final String label,
                                           final Set<SpellAbility> visited) {
        if (sa == null || !visited.add(sa)) {
            return false;
        }

        if (sa.hasParam("PrecostDesc")
                && normalizeLabel(sa.getParam("PrecostDesc")).equals(label)) {
            return true;
        }

        if (hasAbilityLabel(sa.getSubAbility(), label, visited)) {
            return true;
        }

        for (final SpellAbility additional : sa.getAdditionalAbilities().values()) {
            if (hasAbilityLabel(additional, label, visited)) {
                return true;
            }
        }

        for (final List<AbilitySub> list : sa.getAdditionalAbilityLists().values()) {
            for (final AbilitySub additional : list) {
                if (hasAbilityLabel(additional, label, visited)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String normalizeLabel(final String label) {
        if (StringUtils.isBlank(label)) {
            return "";
        }

        String result = label.trim();

        if (result.endsWith("—")) {
            result = result.substring(0, result.length() - 1).trim();
        }

        return result;
    }
}