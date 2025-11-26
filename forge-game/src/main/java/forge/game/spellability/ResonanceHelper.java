package forge.game.spellability;

import com.google.common.collect.Maps;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;
import forge.card.ColorSet;
import forge.card.MagicColor.Color;
import forge.util.collect.FCollectionView;
import java.util.*;

/**
 * Helper for Resonance mechanic.
 *
 * Called BEFORE ability.setupTargets(), similar to Splice.
 * This ensures all added subAbilities participate in targeting
 * together with the original spell.
 */
public final class ResonanceHelper {

    private ResonanceHelper() {}

    // =====================================================================
    // MAIN MERGE FUNCTION
    // =====================================================================
    public static SpellAbility mergeOnCast(Game game, Player activator, SpellAbility root) {

        if (root == null || !root.isSpell())
            return root;

        final Card host = root.getHostCard();
        if (host == null)
            return root;

        if (!host.isInstant() && !host.isSorcery())
            return root;

        if (activator == null)
            return root;

        // pending resonance
        List<Card> pending = new ArrayList<>(activator.getPendingResonance());
        if (pending.isEmpty())
            return root;

        System.out.println("[Resonance] mergeOnCast for " + host.getName());

        // For each exiled card with Resonance
        for (Card resCard : pending) {

            if (!resCard.isInZone(ZoneType.Exile)) {
                activator.removePendingResonance(resCard);
                continue;
            }

            // get ALL SpellAbilities from card A
            FCollectionView<SpellAbility> allSA_view = resCard.getSpellAbilities();
            if (allSA_view == null || allSA_view.isEmpty()) {
                activator.removePendingResonance(resCard);
                game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);
                continue;
            }

            // convert FCollectionView → List
            List<SpellAbility> allSA = new ArrayList<>();
            for (SpellAbility sa : allSA_view) {
                allSA.add(sa);
            }

            // ask player to apply A to B
            boolean apply = activator.getController().confirmAction(
                    root,
                    PlayerActionConfirmMode.OptionalChoose,
                    "Apply Resonance from " + resCard.getName() + "?",
                    Collections.emptyList(),
                    resCard,
                    null
            );

            if (!apply) {
                activator.removePendingResonance(resCard);
                game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);
                continue;
            }

            final CardState hostState = host.getCurrentState();

            // =====================================================================
            // Convert EACH SpellAbility A into a SubAbility and attach to root
            // =====================================================================
            for (SpellAbility saA : allSA) {

                // Copy params
                Map<String, String> paramsA = Maps.newHashMap();
                if (saA.getMapParams() != null)
                    paramsA.putAll(saA.getMapParams());

                // Determine recordType & API
                AbilityRecordType recType = AbilityRecordType.getRecordType(paramsA);
                ApiType apiA = recType.getApiTypeOf(paramsA);

                // Create SubAbility using Forge factory (same approach as Fuse)
                SpellAbility subSa;
                try {
                    subSa = AbilityFactory.getAbility(
                            AbilityRecordType.SubAbility,
                            apiA,
                            paramsA,
                            null,          // SubAbility has no cost
                            hostState,
                            hostState
                    );
                }
                catch (Exception e) {
                    System.err.println("[Resonance] Failed to build sub-ability of "
                            + resCard.getName() + " : " + e);
                    continue;
                }

                AbilitySub sub = (AbilitySub) subSa;
                sub.setActivatingPlayer(activator);

                // attach to root chain
                root.appendSubAbility(sub);
            }

            // =====================================================================
            // Ask player to add COLORS of A to spell B
            // =====================================================================
            boolean addColors = activator.getController().confirmAction(
                    root,
                    PlayerActionConfirmMode.OptionalChoose,
                    "Add colors of " + resCard.getName() + " to this spell?",
                    Collections.emptyList(),
                    resCard,
                    null
            );

            if (addColors) {
                ColorSet colorsA = resCard.getColor(); // includes continuous effects
                if (!colorsA.isColorless()) {
                    applyColorAddEffect(host, colorsA);
                }
            }

            // remove pending and move A → graveyard
            activator.removePendingResonance(resCard);
            game.getAction().moveTo(ZoneType.Graveyard, resCard, -1, root, null);

            System.out.println("[Resonance] Applied " + resCard.getName()
                    + " to " + host.getName());
        }

        return root;
    }

    // =====================================================================
    // COLOR SUPPORT
    // =====================================================================

    /** Convert ColorSet to "Red Blue ..." string for AddColor$. */
    private static String colorsToParam(ColorSet colors) {
        List<String> out = new ArrayList<>();
        for (Color c : colors)
            out.add(c.toString());
        return String.join(" ", out);
    }

    /**
     * Temporary continuous color effect for spell on stack:
     * Mode$ AddColor;
     * AddColor$ <colors>;
     * Affected$ Card.Self;
     * EffectZone$ Stack;
     */
    private static void applyColorAddEffect(Card host, ColorSet addColors) {

        Map<String, String> p = new HashMap<>();

        // Continuous effect
        p.put("Mode", "Continuous");

        // AddColor$ Red Blue ...
        p.put("AddColor", colorsToParam(addColors));

        // Restrict to stack only
        p.put("EffectZone", "Stack");

        // Affected$ Card.Self
        p.put("Affected", "Card.Self");

        CardState state = host.getCurrentState();

        // Direct constructor: NO STRING PARSING
        StaticAbility st = new StaticAbility(p, host, state);

        state.addStaticAbility(st);

        System.out.println("[Resonance] Added colors " +
                colorsToParam(addColors) + " to spell " + host.getName());
    }

}
