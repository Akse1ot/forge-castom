package forge.game.replacement;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.zone.Zone;

import java.util.HashMap;
import java.util.Map;

/**
 * Applies Resonance effects to the next instant / sorcery cast.
 */
public class ReplacementEffectResonanceApply extends ReplacementEffect {

    public ReplacementEffectResonanceApply(final Card exiled) {
        super(new HashMap<>(), exiled, true);
    }

    @Override
    public boolean canReplace(Map<AbilityKey, Object> params) {

        System.out.println("[Resonance-Apply] canReplace() called.");

        Object o = params.get(AbilityKey.SpellAbility);
        if (!(o instanceof SpellAbility)) {
            System.out.println("[Resonance-Apply] FAIL: no SpellAbility in params");
            return false;
        }
        SpellAbility sa = (SpellAbility) o;

        Card exiled = getHostCard();
        System.out.println("  - exiled = " + exiled);

        if (exiled != null) {
            System.out.println("  - name = " + exiled.getName());
            System.out.println("  - zone = " + exiled.getZone());
            System.out.println("  - has ResonancePending = " + exiled.hasKeyword("ResonancePending"));
        }

        if (exiled == null) return false;
        if (!exiled.hasKeyword("ResonancePending")) {
            System.out.println("[Resonance-Apply] FAIL: no ResonancePending");
            return false;
        }
        if (!exiled.isInZone(ZoneType.Exile)) {
            System.out.println("[Resonance-Apply] FAIL: card not in exile");
            return false;
        }

        boolean ok =
                (sa.getHostCard().isInstant() || sa.getHostCard().isSorcery()) &&
                        sa.getActivatingPlayer() == exiled.getController();

        System.out.println("[Resonance-Apply] canReplace returns: " + ok);
        return ok;
    }

    public void replace(Map<AbilityKey, Object> params) {

        System.out.println("[Resonance-Apply] replace() CALLED");

        SpellAbility sa = (SpellAbility) params.get(AbilityKey.SpellAbility);
        Card exiled = getHostCard();

        System.out.println("  - exiled = " + exiled);
        System.out.println("  - target SA = " + sa);

        if (sa == null || exiled == null) {
            System.out.println("[Resonance-Apply] ERROR: null SA/exiled");
            return;
        }

        Card targetSpellCard = sa.getHostCard();

        System.out.println("[Resonance-Apply] Copying abilities...");
        for (SpellAbility subSA : exiled.getSpellAbilities()) {
            if (!subSA.isSpell()) continue;

            SpellAbility copy = subSA.copy(targetSpellCard, false);
            targetSpellCard.addSpellAbility(copy);

            System.out.println("  - copied from " + exiled.getName());
        }

        System.out.println("[Resonance-Apply] Adding colors...");
        targetSpellCard.addColor(
                exiled.getColor(),
                true,
                targetSpellCard.getGame().getNextTimestamp(),
                null
        );

        System.out.println("[Resonance-Apply] Moving exiled card to graveyard...");
        Zone grave = exiled.getOwner().getZone(ZoneType.Graveyard);
        exiled.getGame().getAction().changeZone(
                exiled.getZone(),
                grave,
                exiled,
                null,
                sa
        );

        exiled.removeIntrinsicKeyword("ResonancePending");

        System.out.println("[Resonance-Apply] DONE.");
    }
}
