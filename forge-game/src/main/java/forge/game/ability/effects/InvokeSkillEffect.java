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
        final Player controller = sa.getActivatingPlayer();

        if (host == null || controller == null) {
            return;
        }

        if (!host.isModal() || !host.hasState(SKILL_STATE)) {
            System.err.println("InvokeSkill failed: '" + host + "' has no modal backside.");
            return;
        }

        final CardState skillState = host.getState(SKILL_STATE);
        if (!isSkillSpellState(skillState)) {
            System.err.println("InvokeSkill failed: backside of '" + host + "' is not an instant/sorcery Skill.");
            return;
        }

        final Card skillCopy = Card.fromPaperCard(host.getPaperCard(), controller);

        // Same broad model as DB$ Play + CopyCard$ True, but without putting the copy
        // into the source permanent's real zone. The source permanent must stay untouched.
        skillCopy.setGamePieceType(GamePieceType.TOKEN);
        skillCopy.setCopiedPermanent(host);
        skillCopy.setZone(controller.getZone(ZoneType.None));
        skillCopy.setBackSide(true);

        final Predicate<SpellAbility> validSkillSpell = sp -> {
            if (sp == null || !sp.isSpell() || sp.getCardStateName() != SKILL_STATE) {
                return false;
            }
            return isSkillSpellState(sp.getCardState());
        };

        // Keep the copy in Original state while collecting candidates.
        // AbilityUtils will add modal Backside spells itself, and this avoids duplicate Backside choices.
        final List<SpellAbility> candidates = AbilityUtils.getSpellsFromPlayEffect(
                skillCopy,
                controller,
                CardStateName.Original,
                false,
                validSkillSpell
        );

        if (candidates.isEmpty()) {
            System.err.println("InvokeSkill failed: no castable Skill spell found on backside of '" + host + "'.");
            return;
        }

        // Now switch the temporary copy to the Skill side for display/state consistency
        // before handing it to the normal play/cast flow.
        if (!skillCopy.setState(SKILL_STATE, true, true)) {
            System.err.println("InvokeSkill failed: could not apply Skill state to copied card '" + skillCopy + "'.");
            return;
        }

        SpellAbility skillSA = controller.getController().getAbilityToPlay(skillCopy, candidates);
        if (skillSA == null) {
            return;
        }

        skillSA = skillSA.copyWithNoManaCost(controller);
        if (skillSA == null) {
            return;
        }

        skillSA.setInvoked(true);
        skillSA.setActivatingPlayer(controller);

        // Invoke is not "you may cast" after the invoke ability has resolved.
        // This also keeps AI from declining a mandatory invoke cast.
        skillSA.getPayCosts().setMandatory(true);

        controller.getController().playSaFromPlayEffect(skillSA);
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