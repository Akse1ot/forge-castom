package forge.game.staticability;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class GainsAllAbilitiesTriggerTest extends AITest {
    @Test
    public void neutralRecalculationKeepsLimitAndNextTurnAllowsTrigger() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        Card hammer = addCard("Hammer of Ancestry", player);
        Card source = addCard("Inchblade Companion", player);
        Card first = addCard("Runeclaw Bear", player);
        Card second = addCard("Runeclaw Bear", player);
        refresh(game);
        Trigger before = attached(hammer);
        int spells = hammer.getSpellAbilities().size();
        int statics = hammer.getStaticAbilities().size();

        attach(hammer, first);
        assertEquals(attached(hammer).getActivationsThisTurn(), 1);
        for (int i = 0; i < 4; i++) {
            refresh(game);
            assertIdentity(before, attached(hammer));
            assertEquals(hammer.getSpellAbilities().size(), spells);
            assertEquals(hammer.getStaticAbilities().size(), statics);
        }
        attach(hammer, second);
        assertEquals(attached(hammer).getActivationsThisTurn(), 1);
        assertEquals(attached(source).getActivationsThisTurn(), 0);

        // Use the same per-turn reset called by the turn lifecycle.
        hammer.resetActivationsPerTurn();
        refresh(game);
        attach(hammer, first);
        assertEquals(attached(hammer).getActivationsThisTurn(), 1);
        assertEquals(attached(hammer).getActivationsThisGame(), 2);
    }

    @Test
    public void actualGrantLossRemovesTriggerAndRegainingCreatesNewIdentity() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        Card hammer = addCard("Hammer of Ancestry", player);
        addCard("Inchblade Companion", player);
        refresh(game);
        Trigger before = attached(hammer);
        before.triggerRun();
        StaticAbility grant = hammer.getStaticAbilities().stream()
                .filter(st -> st.hasParam("GainsAllAbilities")).findFirst().orElseThrow();

        grant.setSuppressed(true);
        refresh(game);
        assertEquals(attachedCount(hammer), 0);
        grant.setSuppressed(false);
        refresh(game);

        Trigger regained = attached(hammer);
        assertNotEquals(regained.getId(), before.getId());
        assertNotEquals(regained.getOverridingAbility().getId(), before.getOverridingAbility().getId());
        assertEquals(regained.getActivationsThisTurn(), 0);
        regained.triggerRun();
        refresh(game);
        assertEquals(attached(hammer).getActivationsThisTurn(), 1);
    }

    @Test
    public void sourceAndTwoRecipientsHaveIndependentLimits() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        Card source = addCard("Inchblade Companion", player);
        Card hammer = addCard("Hammer of Ancestry", player);
        Card other = addCard("Bonesplitter", player);
        other.addRemembered(source);
        other.addStaticAbility("Mode$ Continuous | Affected$ Card.Self"
                + " | GainsAbilitiesOfDefined$ Remembered | GainsAllAbilities$ True");
        Card first = addCard("Runeclaw Bear", player);
        Card second = addCard("Runeclaw Bear", player);
        refresh(game);

        attach(source, first);
        attach(hammer, first);
        assertEquals(attached(source).getActivationsThisTurn(), 1);
        assertEquals(attached(hammer).getActivationsThisTurn(), 1);
        assertEquals(attached(other).getActivationsThisTurn(), 0);
        attach(other, first);
        refresh(game);
        attach(source, second);
        attach(hammer, second);
        attach(other, second);
        assertEquals(attached(source).getActivationsThisTurn(), 1);
        assertEquals(attached(hammer).getActivationsThisTurn(), 1);
        assertEquals(attached(other).getActivationsThisTurn(), 1);
    }

    @Test
    public void newSourceAndRecipientObjectsDoNotReuseOldActivationIdentity() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        Card hammer = addCard("Hammer of Ancestry", player);
        Card source = addCard("Inchblade Companion", player);
        refresh(game);
        Trigger before = attached(hammer);
        before.triggerRun();
        SpellAbility cause = AbilityFactory.getAbility("DB$ ChangeZone | Defined$ Self", hammer);
        cause.setActivatingPlayer(player);

        source = game.getAction().moveTo(ZoneType.Graveyard, source, cause, AbilityKey.newMap());
        refresh(game);
        assertEquals(attachedCount(hammer), 0);
        game.getAction().moveTo(ZoneType.Battlefield, source, cause, AbilityKey.newMap());
        refresh(game);
        Trigger newSource = attached(hammer);
        assertNotEquals(newSource.getId(), before.getId());
        assertEquals(newSource.getActivationsThisTurn(), 0);
        newSource.triggerRun();

        hammer = game.getAction().moveTo(ZoneType.Graveyard, hammer, cause, AbilityKey.newMap());
        hammer = game.getAction().moveTo(ZoneType.Battlefield, hammer, cause, AbilityKey.newMap());
        refresh(game);
        assertNotEquals(attached(hammer).getId(), newSource.getId());
        assertEquals(attached(hammer).getActivationsThisTurn(), 0);
    }

    @Test
    public void stockGainsTriggerAbsOfKeepsItsExistingCache() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        addCard("Inchblade Companion", player);
        Card receiver = addCard("Bonesplitter", player);
        receiver.addStaticAbility("Mode$ Continuous | Affected$ Card.Self"
                + " | GainsTriggerAbsOf$ Equipment.Other+YouCtrl");
        Card first = addCard("Runeclaw Bear", player);
        Card second = addCard("Runeclaw Bear", player);
        refresh(game);
        Trigger before = attached(receiver);

        attach(receiver, first);
        refresh(game);
        assertSame(attached(receiver), before);
        attach(receiver, second);
        assertEquals(before.getActivationsThisTurn(), 1);
    }

    @Test
    public void gameActivationLimitSurvivesRecalculationAndTurnReset() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        Card hammer = addCard("Hammer of Ancestry", player);
        Card source = addCard("Inchblade Companion", player);
        Trigger intrinsic = attached(source);
        intrinsic.removeParam("ActivationLimit");
        intrinsic.getOriginalMapParams().remove("ActivationLimit");
        intrinsic.putParam("GameActivationLimit", "1");
        intrinsic.getOriginalMapParams().put("GameActivationLimit", "1");
        Card first = addCard("Runeclaw Bear", player);
        Card second = addCard("Runeclaw Bear", player);
        refresh(game);
        Trigger before = attached(hammer);

        attach(hammer, first);
        hammer.resetActivationsPerTurn();
        refresh(game);
        assertIdentity(before, attached(hammer));
        attach(hammer, second);
        assertEquals(attached(hammer).getActivationsThisTurn(), 0);
        assertEquals(attached(hammer).getActivationsThisGame(), 1);
    }

    @Test
    public void textAndSuppressionRefreshWithoutLosingActivationIdentity() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(0);
        Card hammer = addCard("Hammer of Ancestry", player);
        Card source = addCard("Inchblade Companion", player);
        Trigger intrinsic = attached(source);
        intrinsic.putParam("ValidTarget", "Creature.Green");
        intrinsic.getOriginalMapParams().put("ValidTarget", "Creature.Green");
        SpellAbility effect = intrinsic.ensureAbility();
        effect.putParam("SpellDescription", "Create a green token.");
        effect.getOriginalMapParams().put("SpellDescription", "Create a green token.");
        refresh(game);
        Trigger before = attached(hammer);
        before.triggerRun();
        long timestamp = game.getNextTimestamp();

        source.addChangedTextColorWord("Green", "Blue", timestamp, 0);
        refresh(game);
        Trigger changed = attached(hammer);
        assertIdentity(before, changed);
        assertEquals(changed.getParam("ValidTarget"), "Creature.Blue");
        assertTrue(changed.getOverridingAbility().getParam("SpellDescription").contains("blue"));
        assertEquals(changed.getActivationsThisTurn(), 1);
        assertSame(changed.getHostCard(), hammer);
        assertSame(changed.getOverridingAbility().getHostCard(), hammer);
        assertFalse(changed.isIntrinsic());
        assertFalse(changed.getOverridingAbility().isIntrinsic());
        assertTrue(intrinsic.isIntrinsic());
        assertTrue(effect.isIntrinsic());

        intrinsic.setSuppressed(true);
        refresh(game);
        assertTrue(attached(hammer).isSuppressed());
        intrinsic.setSuppressed(false);
        source.removeChangedTextColorWord(timestamp, 0);
        refresh(game);
        assertIdentity(before, attached(hammer));
        assertEquals(attached(hammer).getParam("ValidTarget"), "Creature.Green");
        assertFalse(attached(hammer).isSuppressed());
        assertEquals(attached(hammer).getActivationsThisTurn(), 1);
    }

    @DataProvider
    public Object[][] copyBackends() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "copyBackends")
    public void copiedGameKeepsGrantedIdentityDuringItsOwnRecalculations(boolean snapshot) {
        Game original = initAndCreateGame();
        Player player = original.getPlayers().get(0);
        Card hammer = addCard("Hammer of Ancestry", player);
        addCard("Inchblade Companion", player);
        Card first = addCard("Runeclaw Bear", player);
        Card second = addCard("Runeclaw Bear", player);
        refresh(original);
        original.EXPERIMENTAL_RESTORE_SNAPSHOT = snapshot;
        GameCopier copier = new GameCopier(original);
        Game copy = copier.makeCopy();
        Card receiver = copier.find(hammer);
        refresh(copy);
        Trigger before = attached(receiver);

        attach(receiver, copier.find(first));
        refresh(copy);
        assertIdentity(before, attached(receiver));
        attach(receiver, copier.find(second));
        assertEquals(attached(receiver).getActivationsThisTurn(), 1);
        assertEquals(attached(hammer).getActivationsThisTurn(), 0);
    }

    private long attachedCount(Card card) {
        return card.getTriggers().stream().filter(t -> t.getMode() == TriggerType.Attached).count();
    }

    private Trigger attached(Card card) {
        assertEquals(attachedCount(card), 1L, "Exactly one Attached trigger on " + card.getName());
        Trigger result = card.getTriggers().stream()
                .filter(t -> t.getMode() == TriggerType.Attached).findFirst().orElseThrow();
        result.ensureAbility();
        return result;
    }

    private void assertIdentity(Trigger before, Trigger after) {
        assertEquals(after.getId(), before.getId());
        assertEquals(after.getOverridingAbility().getId(), before.getOverridingAbility().getId());
        assertEquals(after.getOverridingAbility().getView().getId(), after.getOverridingAbility().getId());
    }

    private void refresh(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private void attach(Card equipment, Card creature) {
        SpellAbility cause = AbilityFactory.getAbility("DB$ Attach | Defined$ Self", equipment);
        cause.setActivatingPlayer(equipment.getController());
        equipment.attachToEntity(creature, cause);
        assertSame(equipment.getEntityAttachedTo(), creature);
        equipment.getGame().getTriggerHandler().runWaitingTriggers();
    }
}
