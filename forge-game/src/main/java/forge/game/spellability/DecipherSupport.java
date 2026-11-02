package forge.game.spellability;

import forge.game.ability.AbilityFactory;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;

/** One cast trigger for the one chosen alternative, independent of the cost provider. */
public final class DecipherSupport {
    private DecipherSupport() { }

    public static void registerCastTrigger(final SpellAbility spell) {
        if (!spell.isSpell() || spell.isCopied() || !spell.isAlternativeCost(AlternativeCost.Decipher)
                || spell.hasParam("WithoutManaCost")) {
            return;
        }
        final var card = spell.getHostCard();
        final Trigger trigger = TriggerHandler.parseTrigger(
                "Mode$ SpellCast | ValidCard$ Card.StrictlySelf | AltCost$ Decipher"
                + " | TriggerDescription$ Decipher — Copy this spell for each instant or sorcery card in your graveyard.",
                card, false);
        final SpellAbility copies = AbilityFactory.getAbility(
                "DB$ CopySpellAbility | Defined$ TriggeredSpellAbility | Amount$ DecipherCount | MayChooseTarget$ True", card);
        copies.setSVar("DecipherCount", "Count$ValidGraveyard Instant.YouOwn,Sorcery.YouOwn");
        copies.setActivatingPlayer(spell.getActivatingPlayer());
        trigger.setOverridingAbility(copies);
        trigger.setSpawningAbility(spell);
        trigger.setActiveZone(null);
        // It matches the imminent cast event once, then follows ordinary trigger ordering/copying.
        card.getGame().getTriggerHandler().registerDelayedTrigger(trigger);
    }
}
