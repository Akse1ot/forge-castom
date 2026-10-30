package forge.game.spellability;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

public class ModifyModesControllerTest extends AITest {
    @Test
    public void castersTomeAppliesWhenOpponentChoosesFatalLoreModes() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player chooser = game.getPlayers().get(1);
        addCard("Tome of Infinity", caster);
        SpellAbility spell = spell("Fatal Lore", caster);

        ModeController controller = choose(spell, chooser);

        assertEquals(controller.max, 2);
        assertSame(spell.getChoosingPlayer(), chooser);
        assertSame(spell.getActivatingPlayer(), caster);
    }

    @Test
    public void choosersTomeDoesNotApplyToOpponentsFatalLore() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        Player chooser = game.getPlayers().get(1);
        addCard("Tome of Infinity", chooser);
        SpellAbility spell = spell("Fatal Lore", caster);

        ModeController controller = choose(spell, chooser);

        assertEquals(controller.max, 1);
        assertSame(spell.getChoosingPlayer(), chooser);
        assertSame(spell.getActivatingPlayer(), caster);
    }

    @Test
    public void castersTomeStillAppliesWhenCasterChoosesModes() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        addCard("Tome of Infinity", caster);
        addCard("Runeclaw Bear", caster); // Keep Boros Charm's creature mode legal.
        SpellAbility spell = spell("Boros Charm", caster);

        ModeController controller = choose(spell, caster);

        assertEquals(controller.max, 3);
        assertSame(spell.getActivatingPlayer(), caster);
    }

    @DataProvider
    public Object[][] abilityKinds() {
        return new Object[][] {{"AB"}, {"DB"}};
    }

    @Test(dataProvider = "abilityKinds")
    public void abilityControllerCanDifferFromSourceController(String kind) {
        Game game = initAndCreateGame();
        Player activator = game.getPlayers().get(0);
        Player sourceController = game.getPlayers().get(1);
        addCard("Tome of Infinity", activator);
        Card source = addCardToZone("Fatal Lore", sourceController, ZoneType.Hand);
        SpellAbility ability = AbilityFactory.getAbility(kind + "$ Charm"
                + (kind.equals("AB") ? " | Cost$ 0 | ActivationZone$ Hand" : "")
                + " | Chooser$ Opponent | Choices$ DrawThree,DestroyAndDraw", source);
        ability.setActivatingPlayer(activator);
        if (kind.equals("DB")) {
            ability.setTrigger(TriggerHandler.parseTrigger("Mode$ Phase | Phase$ Upkeep", source, true));
            assertTrue(ability.isTrigger());
        } else {
            assertTrue(ability.isActivatedAbility());
        }

        ModeController controller = choose(ability, sourceController);

        assertEquals(controller.max, 2);
        assertSame(source.getController(), sourceController);
        assertSame(ability.getActivatingPlayer(), activator);
    }

    private SpellAbility spell(String name, Player caster) {
        Card card = addCardToZone(name, caster, ZoneType.Hand);
        SpellAbility spell = card.getSpellAbilities().get(0);
        spell.setActivatingPlayer(caster);
        return spell;
    }

    private ModeController choose(SpellAbility ability, Player chooser) {
        ModeController controller = new ModeController(chooser);
        chooser.runWithController(() -> assertTrue(CharmEffect.makeChoices(ability)), controller);
        assertEquals(controller.calls, 1, "The expected chooser must receive the modal choice");
        assertSame(controller.ability, ability);
        assertEquals(controller.min, 1);
        assertFalse(controller.allowRepeat);
        return controller;
    }

    private static class ModeController extends PlayerControllerAi {
        private SpellAbility ability;
        private int calls;
        private int min;
        private int max;
        private boolean allowRepeat;

        ModeController(Player player) {
            super(player.getGame(), player, player.getLobbyPlayer());
        }

        @Override
        public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                    int minimum, int maximum, boolean repeat) {
            ability = sa;
            calls++;
            min = minimum;
            max = maximum;
            allowRepeat = repeat;
            return new ArrayList<>(possible.subList(0, minimum));
        }
    }
}
