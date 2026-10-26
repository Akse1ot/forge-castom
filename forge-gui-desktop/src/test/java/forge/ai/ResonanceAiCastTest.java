package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import static org.testng.Assert.*;

/** Checks Resonance through the AI cast flow, including its original alternative-cost cast. */
public class ResonanceAiCastTest extends AITest {
    @Test
    public void pendingResonanceAppliesToTheNextAiSpellOnly() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        assertTrue(ai.getController().isAI());
        assertEquals(game.getPlayers().size(), 2);
        fillLibrary(ai, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        addCards("Island", 3, ai);
        Card chord = addCardToZone("Ethereal Chord", ai, ZoneType.Hand);
        Card next = addCardToZone("Nourish", ai, ZoneType.Hand);
        Card later = addCardToZone("Nourish", ai, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        SpellAbility resonance = chord.getSpellAbilities().stream()
                .filter(sa -> sa.getAlternativeCost() == AlternativeCost.Resonance)
                .findFirst().orElseThrow(() -> new AssertionError("Missing printed Resonance cast option"));
        assertEquals(resonance.getSVar("ResonanceCast"), "True");
        cast(game, ai, resonance);
        assertEquals(resonance.getTotalManaSpent(), 3, "The actual Resonance cost must be paid");
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getCardsIn(ZoneType.Library).size(), 7, "The original draw effect must resolve");
        assertEquals(ai.getPendingResonance().size(), 1, "Resolving the Resonance cast must arm it");
        Card pending = ai.getPendingResonance().get(0);
        assertEquals(pending.getId(), chord.getId());
        assertTrue(pending.isInZone(ZoneType.Exile));

        // Reserve green mana for both Nourishes instead of letting the first cast spend it on {2}.
        addCards("Forest", 4, ai);
        game.getAction().checkStateEffects(true);
        int life = ai.getLife();
        int library = ai.getCardsIn(ZoneType.Library).size();
        SpellAbility spell = next.getFirstSpellAbility();
        cast(game, ai, spell);
        assertEquals(spell.getTotalManaSpent(), 2, "The next spell must also pay its printed cost");

        // Keep running after the missing integration so one failure reports both merge and commit.
        SoftAssert checks = new SoftAssert();
        checks.assertNotNull(game.getStack().peekAbility().getSubAbility(),
                "AI cast must append the pending Resonance draw effect before resolution");
        checks.assertTrue(ai.getPendingResonance().isEmpty(),
                "A successful AI cast must consume pendingResonance");
        checks.assertTrue(game.getCardState(pending).isInZone(ZoneType.Graveyard),
                "The consumed Resonance source must leave Exile for the graveyard");
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getLife(), life + 6, "Positive control: the next spell's own effect must resolve");
        assertTrue(game.getCardState(next).isInZone(ZoneType.Graveyard));
        checks.assertEquals(ai.getCardsIn(ZoneType.Library).size(), library - 1,
                "The appended Resonance effect must draw exactly one additional card");
        checks.assertTrue(ai.getPendingResonance().isEmpty(), "Resolution must not rearm the consumed source");
        checks.assertTrue(game.getCardState(pending).isInZone(ZoneType.Graveyard),
                "The consumed source must remain in the graveyard after resolution");

        int libraryAfterNext = ai.getCardsIn(ZoneType.Library).size();
        cast(game, ai, later.getFirstSpellAbility());
        checks.assertNull(game.getStack().peekAbility().getSubAbility(),
                "A later spell must not receive the already consumed Resonance effect");
        game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getLife(), life + 12, "Positive control: the later spell must resolve normally");
        checks.assertEquals(ai.getCardsIn(ZoneType.Library).size(), libraryAfterNext,
                "The Resonance draw must not repeat on a later spell");
        checks.assertTrue(ai.getPendingResonance().isEmpty(), "A later cast must leave no pending source");
        checks.assertAll();
    }

    private void cast(Game game, Player ai, SpellAbility spell) {
        spell.setActivatingPlayer(ai);
        game.copyLastState();
        assertTrue(ComputerUtil.handlePlayingSpellAbility(ai, spell, null), "The AI cast must succeed");
        assertEquals(game.getStack().size(), 1, "A successful cast must actually reach the stack");
        assertEquals(game.getStack().peekAbility().getHostCard().getId(), spell.getHostCard().getId());
    }
}
