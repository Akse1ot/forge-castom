package forge.game.ability.effects;

import forge.card.CardStateName;
import forge.card.CardTypeView;
import forge.card.GamePieceType;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.function.Predicate;

public class InvokeSkillEffect extends SpellAbilityEffect {
    private static final CardStateName SKILL_STATE = CardStateName.Backside;
    private static final String SKILL_SUBTYPE = "Skill";

    @Override
    protected String getStackDescription(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        return host == null ? "Invoke." : host + " invokes.";
    }

    @Override
    public void resolve(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (host == null) {
            return;
        }

        final Player controller = sa.getActivatingPlayer() != null
                ? sa.getActivatingPlayer()
                : host.getController();

        if (controller == null) {
            return;
        }

        if (!host.isModal() || !host.hasState(SKILL_STATE)) {
            System.err.println("InvokeSkill failed: '" + host + "' has no modal backside.");
            return;
        }

        final CardState skillState = host.getState(SKILL_STATE);
        if (!isSkillSpellState(skillState)) {
            System.err.println(
                    "InvokeSkill failed: backside of '" + host
                            + "' is not an instant/sorcery Skill."
            );
            return;
        }

        final int amount = Math.max(
                0,
                AbilityUtils.calculateAmount(
                        host,
                        sa.getParamOrDefault("Amount", "1"),
                        sa
                )
        );

        if (amount == 0) {
            return;
        }

        final boolean differentTargets = sa.hasParam("DifferentTargets")
                && !"False".equalsIgnoreCase(sa.getParam("DifferentTargets"));

        // Every copy created by this resolution receives the same group ID.
        // canTarget() uses it to see targets chosen by earlier copies.
        final long targetGroupId = differentTargets
                ? host.getGame().getNextTimestamp()
                : -1L;

        for (int i = 0; i < amount; i++) {
            final SpellAbility skillSA = createSkillSpell(
                    host,
                    controller,
                    differentTargets ? targetGroupId : -1L
            );

            if (skillSA == null) {
                break;
            }

            // playSaFromPlayEffect performs the ordinary cast flow:
            // modes, targets, additional costs, legality and stack placement.
            if (!controller.getController().playSaFromPlayEffect(skillSA)) {
                // If the current copy cannot legally be cast, later copies
                // cannot improve the situation when distinct targets are required.
                break;
            }
        }
    }

    private SpellAbility createSkillSpell(final Card host,
                                          final Player controller,
                                          final long targetGroupId) {
        if (host.getPaperCard() == null) {
            System.err.println(
                    "InvokeSkill failed: '" + host + "' has no paper card."
            );
            return null;
        }

        final Card skillCopy = Card.fromPaperCard(host.getPaperCard(), controller);

        // The copy is a temporary castable card object. It must not be placed
        // in the battlefield zone occupied by the source permanent.
        skillCopy.setGamePieceType(GamePieceType.TOKEN);
        skillCopy.setCopiedPermanent(host);
        skillCopy.setZone(controller.getZone(ZoneType.None));

        final Predicate<SpellAbility> validSkillSpell = spell -> {
            if (spell == null
                    || !spell.isSpell()
                    || spell.getCardStateName() != SKILL_STATE) {
                return false;
            }

            return isSkillSpellState(spell.getCardState());
        };

        /*
         * Keep the temporary card in Original state while collecting spells.
         * getSpellsFromPlayEffect() adds the modal Backside itself.
         *
         * Switching to Backside before this call would cause the Skill spell
         * to be collected once as the current state and again as modal Backside.
         */
        final List<SpellAbility> candidates = AbilityUtils.getSpellsFromPlayEffect(
                skillCopy,
                controller,
                CardStateName.Original,
                false,
                validSkillSpell
        );

        if (candidates.isEmpty()) {
            System.err.println(
                    "InvokeSkill failed: no castable Skill spell found on backside of '"
                            + host + "'."
            );
            return null;
        }

        // Apply the Skill face before displaying and casting the copy.
        skillCopy.setBackSide(true);
        if (!skillCopy.setState(SKILL_STATE, true, true)) {
            System.err.println(
                    "InvokeSkill failed: could not apply Skill state to copied card '"
                            + skillCopy + "'."
            );
            return null;
        }

        SpellAbility skillSA = controller.getController().getAbilityToPlay(
                skillCopy,
                candidates
        );

        if (skillSA == null) {
            return null;
        }

        // Current Forge API uses the no-argument version.
        skillSA = skillSA.copyWithNoManaCost();
        if (skillSA == null) {
            return null;
        }

        skillSA.setInvoked(true);
        skillSA.setActivatingPlayer(controller);

        // Once an invoke instruction resolves, casting the Skill is mandatory.
        skillSA.getPayCosts().setMandatory(true);

        if (targetGroupId >= 0L) {
            skillSA.setInvokeTargetGroupId(targetGroupId);
        }

        return skillSA;
    }

    private static boolean isSkillSpellState(final CardState state) {
        if (state == null) {
            return false;
        }

        final CardTypeView type = state.getTypeWithChanges();
        return type != null
                && (type.isInstant() || type.isSorcery())
                && type.hasSubtype(SKILL_SUBTYPE);
    }
}