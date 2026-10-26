package forge.game.spellability;

import forge.game.IEntityMap;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;

import java.util.HashMap;

/** Focus-specific state transfer; this is not a general delayed-trigger copier. */
public final class FocusHelper {
    private FocusHelper() {
    }

    public static Trigger copyPendingTrigger(Trigger source, IEntityMap map) {
        if (!source.hasParam("FocusDelayed")) {
            return null;
        }
        Card original = source.getHostCard();
        // An old exile object must not grant a new object of the same ID a cast.
        if (!original.equalsWithGameTimestamp(original.getGame().getCardState(original, null))) {
            return null;
        }
        Card host = map.map(original);
        Player activator = map.map(source.getSpawningAbility().getActivatingPlayer());
        if (host == null || activator == null) {
            return null;
        }

        // Rebuild the small, known Focus graph. Trigger.copy shares its remembered
        // list and spawning ability, so it cannot be used to cross game boundaries.
        SpellAbility spawning = rebuild(source.getSpawningAbility(), host, activator);
        AbilitySub execute = (AbilitySub) rebuild(source.getOverridingAbility(), host, activator);
        spawning.setAdditionalAbility("Execute", execute);
        Trigger copied = TriggerHandler.parseTrigger(new HashMap<>(source.getMapParams()),
                host, source.isIntrinsic(), null);
        copied.setSpawningAbility(spawning);
        copied.setActiveZone(null);
        copied.setSuppressed(source.isSuppressed());
        copied.addRemembered(host);
        // The trigger's executable ability has no parent, as in DelayedTriggerEffect.
        AbilitySub overriding = (AbilitySub) execute.copy(host, activator, false);
        overriding.setParent(null);
        copied.setOverridingAbility(overriding);
        return copied;
    }

    private static SpellAbility rebuild(SpellAbility source, Card host, Player activator) {
        SpellAbility result = AbilityFactory.getAbility(new HashMap<>(source.getMapParams()),
                AbilityRecordType.SubAbility, host.getCurrentState(), host);
        result.setActivatingPlayer(activator);
        return result;
    }

    /** A Focus free cast has a description suffix absent from its printed ability. */
    public static SpellAbility findFocusedSpell(SpellAbility source, Card host) {
        if (source.isFocused()) {
            for (SpellAbility spell : host.getSpells()) {
                if (source.getOriginalMapParams().equals(spell.getOriginalMapParams())) {
                    return spell;
                }
            }
        }
        return null;
    }

    /** Only the custom Focus variants affect the AI's otherwise unchanged first-choice policy. */
    public static boolean matchesCastCondition(SpellAbility ability) {
        Card host = ability.getHostCard();
        if (!host.hasKeyword(Keyword.FOCUS)) {
            return true;
        }
        boolean focused = ability.isFocused();
        String condition = ability.getParam("Condition");
        return (!"Focused".equals(condition) || focused)
                && (!"NotFocused".equals(condition) || !focused);
    }
}
