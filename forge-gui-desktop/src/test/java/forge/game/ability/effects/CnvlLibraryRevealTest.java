package forge.game.ability.effects;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.simulation.GameCopier;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.LibraryRevealHelper;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.testng.Assert.*;

public class CnvlLibraryRevealTest extends AITest {
    @Test(dataProvider = "flags")
    public void trackedIdentityRestorationKeepsOtherEffectsLiveAndStaleReferencesDistinct(boolean snapshot) {
        Frame f = frame(3);
        Card old = forge.game.card.CardCopyService.getLKICopy(f.cards().get(0));
        Card live = f.game().getAction().moveToHand(f.cards().get(0), null);
        live = f.game().getAction().moveToLibrary(live, 0, null);
        assertFalse(old.equalsWithGameTimestamp(live));
        LibraryRevealHelper.track(f.source(), f.player(), List.of(live));
        Card host = addCard("Forest", f.player());
        for (Card remembered : List.of(live, old)) {
            host.clearRemembered();
            host.addRemembered(remembered);
            SpellAbility effect = AbilityFactory.getAbility("DB$ Effect | RememberObjects$ DirectRemembered"
                    + " | Duration$ EndOfTurn | CopyForSimulation$ True | Name$ "
                    + (remembered == live ? "Live identity" : "Stale identity"), host);
            effect.setActivatingPlayer(f.player());
            AbilityUtils.resolve(effect);
        }
        Game copy = snapshot ? new GameSnapshot(f.game()).makeCopy()
                : new GameCopier(f.game()).withStack(true).makeCopy();
        Card current = copy.findById(live.getId());
        for (Card effect : copy.getCardsIn(ZoneType.Command)) {
            if (!effect.getName().endsWith("identity")) {
                continue;
            }
            Card reference = null;
            for (Object remembered : effect.getRemembered()) {
                if (remembered instanceof Card c) {
                    reference = c;
                    break;
                }
            }
            assertNotNull(reference);
            assertSame(reference.getGame(), copy);
            assertEquals(reference.equalsWithGameTimestamp(current), effect.getName().startsWith("Live"));
        }
        assertEquals(LibraryRevealHelper.getTracked(copy.findById(f.source().getId())).size(), 1);
        assertTrue(live.equalsWithGameTimestamp(current));
        assertFalse(old.equalsWithGameTimestamp(current));
    }

    private record Frame(Game game, Player player, Card source, List<Card> cards) {
    }

    @DataProvider
    public Object[][] operations() {
        return new Object[][] {{1, 0}, {2, 3}, {3, 1}, {4, 3}, {5, 2}, {6, 2}, {7, 0}};
    }

    @Test(dataProvider = "operations")
    public void onlyObjectsAffectedByTheOperationLoseTheirRevealIdentity(int operation, int expected) {
        Frame f = frame(operation == 7 ? 1 : 5);
        List<Card> cards = f.cards();
        LibraryRevealHelper.track(f.source(), f.player(), cards.subList(0, Math.min(3, cards.size())));
        switch (operation) {
            case 1, 7 -> f.player().shuffle(null);
            case 2 -> {
                f.game().getAction().moveToLibrary(cards.get(3), 4, null);
                f.game().getAction().moveToLibrary(cards.get(4), 3, null);
            }
            case 3 -> {
                f.game().getAction().moveToLibrary(cards.get(0), 1, null);
                f.game().getAction().moveToLibrary(cards.get(1), 0, null);
            }
            case 4 -> {
                Card added = addCardToZone("Forest", f.player(), ZoneType.Hand);
                f.game().getAction().moveToLibrary(added, 0, null);
            }
            case 5 -> f.game().getAction().moveToHand(cards.get(0), null);
            case 6 -> {
                Card departed = f.game().getAction().moveToHand(cards.get(0), null);
                Card returned = f.game().getAction().moveToLibrary(departed, 0, null);
                assertEquals(returned.getId(), cards.get(0).getId());
                assertFalse(returned.equalsWithGameTimestamp(cards.get(0)));
            }
        }
        assertEquals(LibraryRevealHelper.getTracked(f.source()).size(), expected);
        assertEquals(AbilityUtils.getDefinedCards(f.source(), "TrackedLibraryReveal",
                f.game().getStack().peekAbility()).size(), expected);
    }

    @Test
    public void directPartialReorderAndWholeReorderHaveDifferentAffectedSets() {
        Frame f = frame(5);
        LibraryRevealHelper.track(f.source(), f.player(), f.cards().subList(0, 3));
        f.player().getZone(ZoneType.Library).reorder(f.cards().get(3), 4);
        assertEquals(LibraryRevealHelper.getTracked(f.source()).size(), 3);
        f.player().getZone(ZoneType.Library).reorder(f.cards().get(0), 4);
        assertEquals(LibraryRevealHelper.getTracked(f.source()).size(), 2);
        SpellAbility reorder = AbilityFactory.getAbility(
                "DB$ ReorderZone | Zone$ Library | Defined$ You | Random$ True", f.source());
        reorder.setActivatingPlayer(f.player());
        AbilityUtils.resolve(reorder);
        assertTrue(LibraryRevealHelper.getTracked(f.source()).isEmpty());
    }

    @DataProvider
    public Object[][] sizes() {
        return new Object[][] {{0}, {1}, {3}};
    }

    @Test(dataProvider = "sizes")
    public void optInPeekRevealsWithoutMovingAndClearsAnOldGroup(int size) {
        Frame f = frame(size);
        List<Integer> before = f.player().getCardsIn(ZoneType.Library).stream().map(Card::getId).toList();
        SpellAbility peek = f.game().getStack().peekAbility().copy(f.source(), f.player(), false);
        peek.setSubAbility(null);
        AbilityUtils.resolve(peek);
        assertEquals(f.player().getCardsIn(ZoneType.Library).stream().map(Card::getId).toList(), before);
        assertEquals(LibraryRevealHelper.getTracked(f.source()).size(), size);
        peek.putParam("NoReveal", "True");
        AbilityUtils.resolve(peek);
        assertTrue(LibraryRevealHelper.getTracked(f.source()).isEmpty());
    }

    @DataProvider
    public Object[][] flags() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "flags")
    public void activeValuesAndIdentitiesAreIndependentAcrossWorldCopies(boolean snapshot) {
        Frame f = frame(5);
        LibraryRevealHelper.track(f.source(), f.player(), f.cards().subList(0, 3));
        Game copy = snapshot ? new GameSnapshot(f.game()).makeCopy()
                : new GameCopier(f.game()).withStack(true).makeCopy();
        Card source = copy.findById(f.source().getId());
        assertEquals(LibraryRevealHelper.getTracked(source).size(), 3);
        assertTrue(LibraryRevealHelper.getTracked(source).stream().allMatch(c -> c.getGame() == copy));
        assertTrue(copy.getTimestamp() >= f.game().getTimestamp(), "A copied move must not reuse an old identity");
        copy.getAction().moveToLibrary(LibraryRevealHelper.getTracked(source).getFirst(), -1, null);
        assertEquals(LibraryRevealHelper.getTracked(source).size(), 2);
        copy.getPlayer(f.player().getId()).shuffle(null);
        assertTrue(LibraryRevealHelper.getTracked(source).isEmpty());
        assertEquals(LibraryRevealHelper.getTracked(f.source()).size(), 3);
    }

    @Test(dataProvider = "flags")
    public void readTheFuturePaysCostsBeforeMovingOnlyTheOtherSurvivingCards(boolean payable) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card future = addCardToZone("Read the Future", player, ZoneType.Hand);
        String cost = payable ? "0 Mill<1>" : "R R";
        CardRules rules = CardRules.fromScript(List.of("Name:Reveal payment fixture", "Types:Instant",
                "ManaCost:" + (payable ? "0" : "R R"),
                "A:SP$ Draw | Cost$ " + cost + " | Defined$ You | NumCards$ 2", "Oracle:"));
        Card selected = CardFactory.getCard(new PaperCard(rules, "", CardRarity.Common), player, game);
        selected.setGameTimestamp(game.getNextTimestamp());
        player.getZone(ZoneType.Library).add(selected);
        Card second = addCardToZone("Island", player, ZoneType.Library);
        Card third = addCardToZone("Mountain", player, ZoneType.Library);
        Card fourth = addCardToZone("Forest", player, ZoneType.Library);
        Card fifth = addCardToZone("Plains", player, ZoneType.Library);
        Card sixth = addCardToZone("Swamp", player, ZoneType.Library);
        player.getManaPool().addMana(new Mana(MagicColor.BLUE, future, null, player),
                new Mana(MagicColor.RED, future, null, player));
        SpellAbility cast = future.getFirstSpellAbility();
        cast.setActivatingPlayer(player);
        game.copyLastState();
        assertTrue(ComputerUtil.handlePlayingSpellAbility(player, cast, null));
        drain(game);
        assertTrue(game.findById(second.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(game.findById(third.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(game.findById(selected.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(game.findById(fourth.getId()).isInZone(payable ? ZoneType.Hand : ZoneType.Library));
        assertTrue(game.findById(fifth.getId()).isInZone(payable ? ZoneType.Hand : ZoneType.Library));
        assertTrue(game.findById(sixth.getId()).isInZone(ZoneType.Library));
        assertTrue(LibraryRevealHelper.getTracked(game.findById(future.getId())).isEmpty());
        assertEquals(player.getCardsIn(ZoneType.Hand).size(), payable ? 2 : 0);
    }

    @Test(dataProvider = "flags")
    public void aRebuiltCardCannotResurrectAStaleZeroTimestampIdentity(boolean snapshot) {
        Frame f = frame(5);
        Card old = f.cards().get(0);
        old.setGameTimestamp(0);
        LibraryRevealHelper.track(f.source(), f.player(), f.cards().subList(0, 3));
        Card moved = f.game().getAction().moveToHand(old, null);
        f.game().getAction().moveToLibrary(moved, 0, null);
        assertEquals(LibraryRevealHelper.getTracked(f.source()).size(), 2);
        Game first = snapshot ? new GameSnapshot(f.game()).makeCopy()
                : new GameCopier(f.game()).withStack(true).makeCopy();
        Game second = snapshot ? new GameSnapshot(first).makeCopy()
                : new GameCopier(first).withStack(true).makeCopy();
        assertEquals(LibraryRevealHelper.getTracked(first.findById(f.source().getId())).size(), 2);
        assertEquals(LibraryRevealHelper.getTracked(second.findById(f.source().getId())).size(), 2);
    }

    private Frame frame(int size) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        List<Card> cards = IntStream.range(0, size)
                .mapToObj(i -> addCardToZone("Island", player, ZoneType.Library)).toList();
        Card source = createCard("Read the Future", player);
        game.getStackZone().add(source);
        SpellAbility spell = source.getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        game.copyLastState();
        game.getStack().add(spell);
        return new Frame(game, player, source, cards);
    }

    @Test
    public void ordinaryPeekKeepsItsExistingRememberedCardsAndDoesNotOptIn() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card source = addCardToZone("Fact or Fiction", player, ZoneType.Hand);
        List<Card> cards = IntStream.range(0, 3)
                .mapToObj(i -> addCardToZone("Island", player, ZoneType.Library)).toList();
        SpellAbility peek = AbilityFactory.getAbility("DB$ PeekAndReveal | Defined$ You | PeekAmount$ 3"
                + " | NoPeek$ True | RememberRevealed$ True", source);
        peek.setActivatingPlayer(player);
        AbilityUtils.resolve(peek);
        assertEquals(source.getRememberedCount(), 3);
        assertFalse(source.hasSVar(LibraryRevealHelper.STATE));
        assertEquals(player.getCardsIn(ZoneType.Library).stream().map(Card::getId).toList(),
                cards.stream().map(Card::getId).toList());
    }

    private void drain(Game game) {
        for (int i = 0; i < 20; i++) {
            game.getTriggerHandler().runWaitingTriggers();
            game.getStack().addAllTriggeredAbilitiesToStack();
            if (game.getStack().isEmpty()) {
                return;
            }
            game.getStack().resolveStack();
        }
        fail("Read the Future stack did not drain");
    }
}
