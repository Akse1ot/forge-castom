package forge.game.ability.effects;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class ControlSpellEffectTest extends AITest {
    private SpellAbility stackSpell(Game game, Player controller, String name) {
        Card card = createCard(name, controller);
        card.setGameTimestamp(game.getNextTimestamp());
        game.getStackZone().add(card);
        SpellAbility spell = card.getFirstSpellAbility();
        spell.setActivatingPlayer(controller);
        game.getStack().add(spell);
        return game.getStack().peekAbility();
    }

    private SpellAbility exchange(Card source, SpellAbility target) {
        SpellAbility ability = AbilityFactory.getAbility(
                "DB$ ControlSpell | TargetType$ Spell | ValidTgts$ Card.OppCtrl"
                        + " | Mode$ Exchange | DefinedExchange$ SourceFirstSpell | Remember$ True", source);
        ability.setActivatingPlayer(source.getController());
        ability.getTargets().add(target);
        return ability;
    }

    private void assertController(Game game, SpellAbility spell, Player controller) {
        SpellAbilityStackInstance instance = game.getStack().getInstanceMatchingSpellAbilityID(spell);
        assertNotNull(instance);
        assertSame(instance.getActivatingPlayer(), controller);
        assertSame(instance.getSpellAbility().getActivatingPlayer(), controller);
        assertSame(spell.getHostCard().getController(), controller);
    }

    @Test
    public void exchangesBothSpellsAndTheirResultingPermanents() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0), opponent = game.getPlayers().get(1);
        SpellAbility target = stackSpell(game, opponent, "Ornithopter");
        // A plain enchantment stands in for Spell Theft; its cast trigger is resolved below.
        SpellAbility source = stackSpell(game, caster, "Glorious Anthem");
        Card sourceCard = source.getHostCard();

        AbilityUtils.resolve(exchange(sourceCard, target));

        assertController(game, source, opponent);
        assertController(game, target, caster);
        assertEquals(sourceCard.getRememberedCount(), 2);
        game.getStack().resolveStack();
        game.getStack().resolveStack();
        assertTrue(game.getCardState(sourceCard).isInPlay());
        assertSame(game.getCardState(sourceCard).getController(), opponent);
        assertTrue(game.getCardState(target.getHostCard()).isInPlay());
        assertSame(game.getCardState(target.getHostCard()).getController(), caster);
    }

    @DataProvider
    public Object[][] eitherSpell() {
        return new Object[][] {{true}, {false}};
    }

    @Test(dataProvider = "eitherSpell")
    public void counteringEitherSpellPreventsTheWholeExchange(boolean counterSource) {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0), opponent = game.getPlayers().get(1);
        SpellAbility target = stackSpell(game, opponent, "Ornithopter");
        SpellAbility source = stackSpell(game, caster, "Glorious Anthem");
        SpellAbility exchange = exchange(source.getHostCard(), target);
        SpellAbility countered = counterSource ? source : target;
        SpellAbility counter = AbilityFactory.getAbility(
                "DB$ Counter | TargetType$ Spell | ValidTgts$ Card", addCard("Island", opponent));
        counter.setActivatingPlayer(opponent);
        counter.getTargets().add(countered);
        AbilityUtils.resolve(counter);
        assertNull(game.getStack().getInstanceMatchingSpellAbilityID(countered));
        assertTrue(game.getCardState(countered.getHostCard()).isInZone(ZoneType.Graveyard));

        // The cast trigger remains independent of its countered source.
        AbilityUtils.resolve(exchange);

        assertController(game, counterSource ? target : source, counterSource ? opponent : caster);
        assertFalse(source.getHostCard().hasRemembered());
    }

    @Test(dataProvider = "eitherSpell")
    public void controlRestrictionOnEitherSpellPreventsTheWholeExchange(boolean restrictSource) {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0), opponent = game.getPlayers().get(1);
        SpellAbility target = stackSpell(game, opponent, "Ornithopter");
        SpellAbility source = stackSpell(game, caster, "Glorious Anthem");
        Card restricted = (restrictSource ? source : target).getHostCard();
        Card restriction = addCard("Island", caster);
        restriction.addStaticAbility("Mode$ CantGainControl | ValidCard$ Card.named" + restricted.getName());

        AbilityUtils.resolve(exchange(source.getHostCard(), target));

        assertController(game, source, caster);
        assertController(game, target, opponent);
        assertFalse(source.getHostCard().hasRemembered());
    }

    @Test
    public void spellsWithTheSameControllerCannotBeExchanged() {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0);
        SpellAbility target = stackSpell(game, caster, "Ornithopter");
        SpellAbility source = stackSpell(game, caster, "Glorious Anthem");

        new ControlSpellEffect().resolve(exchange(source.getHostCard(), target));

        assertController(game, source, caster);
        assertController(game, target, caster);
        assertFalse(source.getHostCard().hasRemembered());
    }

    @Test(dataProvider = "eitherSpell")
    public void permanentExchangeRetainsChimeraBehavior(boolean available) {
        Game game = initAndCreateGame();
        Player caster = game.getPlayers().get(0), opponent = game.getPlayers().get(1);
        Card chimera = addCardToZone("Perplexing Chimera", caster,
                available ? ZoneType.Battlefield : ZoneType.Graveyard);
        SpellAbility target = stackSpell(game, opponent, "Ornithopter");
        SpellAbility exchange = AbilityFactory.getAbility(
                "DB$ ControlSpell | Defined$ Remembered | Mode$ Exchange | Remember$ True", chimera);
        exchange.setActivatingPlayer(caster);
        chimera.addRemembered(target);

        AbilityUtils.resolve(exchange);

        assertSame(chimera.getController(), available ? opponent : caster);
        assertController(game, target, available ? caster : opponent);
        assertEquals(chimera.getRememberedCount(), available ? 3 : 1);
    }
}
