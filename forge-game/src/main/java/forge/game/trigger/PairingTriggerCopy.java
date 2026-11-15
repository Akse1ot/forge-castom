package forge.game.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Copies the non-targeted attack and pair triggers used by temporary creature pairs. */
public final class PairingTriggerCopy {
    private PairingTriggerCopy() {
    }

    public static boolean supports(final SpellAbility ability) {
        if (!(ability instanceof WrappedAbility wrapped)) {
            return false;
        }
        final TriggerType mode = wrapped.getTrigger().getMode();
        if (mode != TriggerType.BecomesPaired && !(mode == TriggerType.Attacks
                && ability.getApi() == ApiType.Bond
                && "True".equalsIgnoreCase(ability.getParam("PairUntilEndOfTurn")))) {
            return false;
        }
        for (SpellAbility part = wrapped.getWrappedAbility(); part != null; part = part.getSubAbility()) {
            if (part.usesTargeting() || !part.getAdditionalAbilities().isEmpty()
                    || !part.getAdditionalAbilityLists().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static SpellAbility copy(final SpellAbility original, final Game destination) {
        final WrappedAbility wrapped = (WrappedAbility) original;
        final Card host = mapCard(original.getHostCard(), destination, false);
        final Player player = destination.getPlayer(original.getActivatingPlayer().getId());
        final SpellAbility effect = wrapped.getWrappedAbility().copy(host, player, false, true);
        final Map<AbilityKey, Object> payload = AbilityKey.newMap();
        for (final Map.Entry<AbilityKey, Object> entry : wrapped.getTriggeringObjects().entrySet()) {
            payload.put(entry.getKey(), mapPayload(entry.getValue(), destination));
        }
        effect.setTriggeringObjects(payload);
        effect.setLastStateBattlefield(destination.copyLastStateBattlefield());
        effect.setLastStateGraveyard(destination.copyLastStateGraveyard());
        final Player decider = wrapped.getDecider();
        return new WrappedAbility(wrapped.getTrigger().copy(host, true, true, effect), effect,
                decider == null ? null : destination.getPlayer(decider.getId()));
    }

    private static Object mapPayload(final Object value, final Game destination) {
        if (value instanceof Card card) {
            return mapCard(card, destination, card.isLKI());
        }
        if (value instanceof Player player) {
            return destination.getPlayer(player.getId());
        }
        if (value instanceof Iterable<?> entries) {
            final List<GameEntity> mapped = new ArrayList<>();
            for (final Object entry : entries) {
                if (mapPayload(entry, destination) instanceof GameEntity entity) {
                    mapped.add(entity);
                }
            }
            return mapped;
        }
        return null;
    }

    private static Card mapCard(final Card original, final Game destination, final boolean preserveLki) {
        final Card current = destination.findById(original.getId());
        if (original.equalsWithGameTimestamp(current)) {
            return preserveLki ? CardCopyService.getLKICopy(current) : current;
        }
        final Card previous = new CardCopyService(original, destination).copyCard(false,
                destination.getPlayer(original.getOwner().getId()));
        previous.setGameTimestamp(original.getGameTimestamp());
        previous.setController(destination.getPlayer(original.getController().getId()), original.getGameTimestamp());
        if (original.getZone() != null) {
            previous.setZone(original.isInZone(ZoneType.Stack) ? destination.getStackZone()
                    : destination.getPlayer(original.getController().getId()).getZone(original.getZone().getZoneType()));
        }
        previous.setLKICMC(original.getCMC());
        return previous;
    }
}
