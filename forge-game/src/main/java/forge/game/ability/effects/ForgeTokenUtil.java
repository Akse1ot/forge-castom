package forge.game.ability.effects;

import forge.game.CardTraitBase;
import forge.game.card.Card;
import forge.game.card.token.TokenInfo;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;

import java.util.List;

/**
 * Builds the dynamic Equipment prototype used by the Forge action.
 */
final class ForgeTokenUtil {
    private static final String TOKEN_SCRIPT = "forge_equipment";

    private ForgeTokenUtil() {
    }

    static Card createPrototype(final SpellAbility sa,
                                final Player owner,
                                final List<? extends SpellAbility> selectedChoices) {
        final Card prototype = TokenInfo.getProtoType(TOKEN_SCRIPT, sa, owner, false);

        if (prototype == null) {
            throw scriptError(sa, "token script '" + TOKEN_SCRIPT + "' was not found");
        }

        prototype.setTokenSpawningAbility(sa);

        int abilityCount = 0;

        if (sa.hasParam("ForgeAbilities")) {
            abilityCount += addStaticAbilities(
                    prototype,
                    sa,
                    sa.getParam("ForgeAbilities")
            );
        }

        if (selectedChoices != null) {
            for (final SpellAbility choice : selectedChoices) {
                if (!choice.hasParam("ForgeAbility")) {
                    throw scriptError(sa,
                            "choice '" + choice.getDescription()
                                    + "' has no ForgeAbility parameter");
                }

                abilityCount += addStaticAbilities(
                        prototype,
                        choice,
                        choice.getParam("ForgeAbility")
                );
            }
        }

        if (abilityCount == 0) {
            throw scriptError(sa,
                    "no ForgeAbilities or selected ForgeAbility was provided");
        }

        return prototype;
    }

    private static int addStaticAbilities(final Card prototype,
                                          final CardTraitBase holder,
                                          final String abilityNames) {
        int added = 0;

        for (final String rawName : abilityNames.split(" & ")) {
            final String abilityName = rawName.trim();

            if (abilityName.isEmpty()) {
                continue;
            }

            if (!holder.hasSVar(abilityName)) {
                throw scriptError(holder,
                        "static ability SVar '" + abilityName + "' was not found");
            }

            final String definition = holder.getSVar(abilityName);

            if (definition == null || definition.trim().isEmpty()) {
                throw scriptError(holder,
                        "static ability SVar '" + abilityName + "' is empty");
            }

            final StaticAbility forgedAbility =
                    prototype.addStaticAbility(definition);

            copyDependentSVars(forgedAbility, holder);

            /*
             * The base token is loaded without applying the source's text
             * changes. Apply them only to the dynamically granted property.
             */
            forgedAbility.changeTextIntrinsic(
                    holder.getChangedTextColors(),
                    holder.getChangedTextTypes()
            );

            added++;
        }

        return added;
    }

    private static void copyDependentSVars(final StaticAbility forgedAbility,
                                           final CardTraitBase holder) {
        if (!holder.hasParam("ForgeSVars")) {
            return;
        }

        for (final String rawName : holder.getParam("ForgeSVars").split(" & ")) {
            final String sVarName = rawName.trim();

            if (sVarName.isEmpty()) {
                continue;
            }

            if (!holder.hasSVar(sVarName)) {
                throw scriptError(holder,
                        "dependent SVar '" + sVarName + "' was not found");
            }

            forgedAbility.setSVar(sVarName, holder.getSVar(sVarName));
        }
    }

    private static IllegalStateException scriptError(
            final CardTraitBase holder,
            final String message) {
        return new IllegalStateException(
                "Invalid Forge definition for "
                        + holder.getHostCard().getName()
                        + ": "
                        + message
        );
    }
}