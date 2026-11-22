package forge.game.cost;

import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardFactory;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.*;

/** Actual zone changes, payment bookkeeping and the printed Enigma Construct ability. */
public class CoalesceTest extends AITest {
    private Card enigma(Player player) throws IOException {
        List<String> script = Files.readAllLines(Path.of(ForgeConstants.CARD_DATA_DIR,
                "custom cards", "TWDS", "e", "enigma_construct.txt"));
        return scripted(player, ZoneType.Hand, script);
    }

    private Card scripted(Player player, ZoneType zone, List<String> script) {
        Card card = Card.fromPaperCard(new PaperCard(CardRules.fromScript(
                script.stream().filter(line -> !line.isBlank()).toList()), "TWDS", CardRarity.Rare), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }

    private SpellAbility spell(Card card) {
        SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(card.getOwner());
        return sa;
    }

    private void select(SpellAbility sa, int amount) {
        for (int i = 0; i < amount; i++) {
            Card card = addCardToZone("Forest", sa.getActivatingPlayer(), ZoneType.Exile);
            card.setUsedToPay(true);
            sa.addCostToHashList(card, CostPayment.COALESCE_PAYMENT, true);
        }
    }

    private void pay(SpellAbility sa, boolean success) {
        CostPayment.handleCoalesce(sa, success, AbilityKey.newMap());
    }

    private int count(SpellAbility sa) {
        return AbilityUtils.calculateAmount(sa.getHostCard(), "CastSA>CoalescedCards$Amount", sa);
    }

    private void refresh(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private void putOnStack(SpellAbility sa) {
        Game game = sa.getHostCard().getGame();
        sa.setHostCard(game.getAction().moveToStack(sa.getHostCard(), sa));
        game.getStack().add(sa);
    }

    @DataProvider
    public Object[][] amounts() {
        return new Object[][] {{0, 6}, {2, 6}, {4, 6}, {4, 1}, {2, 0}};
    }

    @Test(dataProvider = "amounts")
    public void enigmaResolvesPrintedTrigger(int amount, int hand) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        fillLibrary(player, 20);
        Card card = enigma(player);
        SpellAbility sa = spell(card);
        select(sa, amount);
        pay(sa, true);
        assertNullOrEmptySelection(sa);
        for (int i = 0; i < hand; i++) addCardToZone("Island", player, ZoneType.Hand);
        refresh(game);
        putOnStack(sa);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().resolveStack();
        Card permanent = sa.getHostCard();
        assertTrue(permanent.isInPlay());
        assertEquals(count(permanent.getCastSA()), amount);
        assertEquals(AbilityUtils.calculateAmount(permanent, "CastSA>CoalescedCards$Amount/Times.2",
                permanent.getCastSA()), 2 * amount);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().isEmpty(), "The printed enters trigger must be put on the stack");
        game.getStack().resolveStack();
        assertEquals(player.getCardsIn(ZoneType.Library).size(), 20 - 2 * amount);
        assertEquals(player.getCardsIn(ZoneType.Hand).size(), hand - Math.min(hand, amount) + amount);
        assertEquals(player.getCardsIn(ZoneType.Exile).size(), amount);
        assertEquals(player.getCardsIn(ZoneType.Graveyard).size(), amount + Math.min(hand, amount));
    }

    private void assertNullOrEmptySelection(SpellAbility sa) {
        assertTrue(sa.getPaidList(CostPayment.COALESCE_PAYMENT, true) == null
                || sa.getPaidList(CostPayment.COALESCE_PAYMENT, true).isEmpty());
    }

    @Test
    public void cancelledPaymentRetryAndEmptyPaymentReplacePreviousResult() throws IOException {
        Game game = initAndCreateGame();
        SpellAbility sa = spell(enigma(game.getPlayers().get(1)));
        sa.getHostCard().setCastSA(sa);
        select(sa, 2);
        CardCollection selected = new CardCollection(sa.getPaidList(CostPayment.COALESCE_PAYMENT, true));
        pay(sa, false);
        assertEquals(count(sa), 0);
        selected.forEach(card -> {
            assertTrue(card.isInZone(ZoneType.Exile));
            assertFalse(card.isUsedToPay());
            card.setUsedToPay(true);
            sa.addCostToHashList(card, CostPayment.COALESCE_PAYMENT, true);
        });
        pay(sa, true);
        assertEquals(count(sa), 2);
        Card paid = sa.getPaidList("CoalescedCards", true).getFirst();
        game.getAction().moveToHand(paid, null);
        assertEquals(count(sa), 2, "Later zone changes do not undo the historical payment");
        SpellAbility copy = sa.copy(sa.getHostCard(), sa.getActivatingPlayer(), true);
        pay(sa, false);
        assertEquals(count(sa), 0);
        assertEquals(copy.getPaidList("CoalescedCards", true).size(), 2,
                "Replacing the original record must not clear its copy");
        select(sa, 4);
        pay(sa, true);
        assertEquals(count(sa), 4);
        pay(sa, true);
        assertEquals(count(sa), 0, "A fresh payment with no coalesce cannot reuse the previous record");
    }

    @Test
    public void staleSelectionsAndOtherOwnersAreNotRecorded() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        SpellAbility sa = spell(enigma(player));
        Card old = addCardToZone("Forest", player, ZoneType.Exile);
        Card hand = game.getAction().moveToHand(old, null);
        game.getAction().exile(hand, null, AbilityKey.newMap());
        sa.addCostToHashList(old, CostPayment.COALESCE_PAYMENT, true);
        Card other = addCardToZone("Island", game.getPlayers().get(0), ZoneType.Exile);
        sa.addCostToHashList(other, CostPayment.COALESCE_PAYMENT, true);
        pay(sa, true);
        assertNull(sa.getPaidList("CoalescedCards", true));
        assertTrue(game.findById(old.getId()).isInZone(ZoneType.Exile));
        assertTrue(other.isInZone(ZoneType.Exile));
    }

    @DataProvider
    public Object[][] replacements() {
        return new Object[][] {{"Prevent$ True", "", ZoneType.Exile},
                {"ReplaceWith$ Redirect", "SVar:Redirect:DB$ ChangeZone | Defined$ ReplacedCard | Origin$ All | Destination$ Hand", ZoneType.Hand},
                {"ReplaceWith$ Redirect", "SVar:Redirect:DB$ ChangeZone | Defined$ ReplacedCard | Origin$ All | Destination$ Exile", ZoneType.Exile}};
    }

    @Test(dataProvider = "replacements")
    public void recordsOnlyActualGraveyardMoves(String replacement, String effect, ZoneType destination) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        SpellAbility sa = spell(enigma(player));
        Card replaced = addCardToZone("Island", player, ZoneType.Exile);
        sa.addCostToHashList(replaced, CostPayment.COALESCE_PAYMENT, true);
        select(sa, 3);
        scripted(player, ZoneType.Battlefield, List.of("Name:Coalesce Replacement", "ManaCost:0", "Types:Enchantment",
                "R:Event$ Moved | ActiveZones$ Battlefield | Origin$ Exile | Destination$ Graveyard | ValidCard$ Card.namedIsland | " + replacement,
                effect, "Oracle:Test replacement."));
        refresh(game);
        pay(sa, true);
        assertEquals(sa.getPaidList("CoalescedCards", true).size(), 3);
        assertTrue(game.findById(replaced.getId()).isInZone(destination));
        assertNullOrEmptySelection(sa);
    }

    @Test
    public void copiedSpellKeepsPayment() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        fillLibrary(player, 20);
        SpellAbility sa = spell(enigma(player));
        select(sa, 2);
        pay(sa, true);
        refresh(game);
        putOnStack(sa);
        SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(sa, sa, player);
        assertEquals(copy.getPaidList("CoalescedCards", true).size(), 2);
        game.getStack().add(copy);
        game.getStack().resolveStack();
        Card permanent = copy.getHostCard();
        assertTrue(permanent.isInPlay());
        assertEquals(count(permanent.getCastSA()), 2);
    }

    @Test
    public void blinkLosesPaymentHistory() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        fillLibrary(player, 20);
        SpellAbility sa = spell(enigma(player));
        select(sa, 2);
        pay(sa, true);
        refresh(game);
        putOnStack(sa);
        game.getStack().resolveStack();
        Card exiled = game.getAction().exile(sa.getHostCard(), null, AbilityKey.newMap());
        Card returned = game.getAction().moveToPlay(exiled, player, null, AbilityKey.newMap());
        assertNull(returned.getCastSA());
        SpellAbility trigger = AbilityFactory.getAbility(returned.getSVar("TrigDiscard"), returned);
        trigger.setActivatingPlayer(player);
        assertEquals(AbilityUtils.calculateAmount(returned, "X", trigger), 0);
    }

    @Test
    public void exileWithTooFewCardsUsesAvailableHand() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card card = enigma(player);
        SpellAbility sa = spell(card);
        select(sa, 4);
        pay(sa, true);
        Card permanent = game.getAction().moveToPlay(card, player, null, AbilityKey.newMap());
        permanent.setCastSA(sa);
        addCardToZone("Island", player, ZoneType.Hand);
        SpellAbility exile = AbilityFactory.getAbility(permanent.getSVar("DBExile"), permanent);
        exile.setActivatingPlayer(player);
        AbilityUtils.resolve(exile);
        assertTrue(player.getCardsIn(ZoneType.Hand).isEmpty());
        assertEquals(player.getCardsIn(ZoneType.Exile).size(), 1);
    }

    @Test
    public void aiAdjustmentStillPaysPairsAndTestModeDoesNotReserve() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        SpellAbility sa = spell(enigma(player));
        for (int i = 0; i < 6; i++) addCardToZone("Forest", player, ZoneType.Exile);
        refresh(game);
        ManaCostBeingPaid preview = new ManaCostBeingPaid(sa.getHostCard().getManaCost());
        CostAdjustment.adjust(preview, sa, player, new CardCollection(), true, false);
        assertEquals(preview.getGenericManaAmount(), 0);
        assertNull(sa.getPaidList(CostPayment.COALESCE_PAYMENT, true));
        ManaCostBeingPaid actual = new ManaCostBeingPaid(sa.getHostCard().getManaCost());
        CostAdjustment.adjust(actual, sa, player, new CardCollection(), false, false);
        assertEquals(actual.getGenericManaAmount(), 0);
        assertEquals(sa.getPaidList(CostPayment.COALESCE_PAYMENT, true).size(), 4);
        assertEquals(player.getCardsIn(ZoneType.Exile).size(), 6);
        pay(sa, true);
        assertEquals(sa.getPaidList("CoalescedCards", true).size(), 4);
        assertEquals(player.getCardsIn(ZoneType.Exile).size(), 2);
    }

    @DataProvider
    public Object[][] aiCards() { return new Object[][] {{true}, {false}}; }

    @Test(dataProvider = "aiCards")
    public void aiPaysColoredManaAndCommitsCoalesce(boolean construct) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card card = construct ? enigma(player) : scripted(player, ZoneType.Hand,
                List.of("Name:Coalesce Test Spell", "ManaCost:2 U", "Types:Sorcery", "K:Coalesce",
                        "A:SP$ Draw | NumCards$ 1", "Oracle:Coalesce. Draw a card."));
        SpellAbility sa = spell(card);
        for (int i = 0; i < 4; i++) addCardToZone("Forest", player, ZoneType.Exile);
        player.getManaPool().addMana(new Mana(MagicColor.BLUE, card, null, player));
        if (construct) player.getManaPool().addMana(new Mana(MagicColor.RED, card, null, player));
        refresh(game);
        assertTrue(ComputerUtilMana.payManaCost(sa.getPayCosts(), player, sa, false));
        assertEquals(sa.getPaidList("CoalescedCards", true).size(), 4);
        assertTrue(player.getCardsIn(ZoneType.Exile).isEmpty());
        assertEquals(player.getCardsIn(ZoneType.Graveyard).size(), 4);
        assertNullOrEmptySelection(sa);
    }

    @Test
    public void aiFailedManaPaymentCanRetryWithoutStaleResult() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card card = enigma(player);
        SpellAbility sa = spell(card);
        for (int i = 0; i < 4; i++) addCardToZone("Forest", player, ZoneType.Exile);
        refresh(game);
        assertFalse(ComputerUtilMana.payManaCost(sa.getPayCosts(), player, sa, false));
        assertNull(sa.getPaidList("CoalescedCards", true));
        assertEquals(player.getCardsIn(ZoneType.Exile).size(), 4);
        player.getCardsIn(ZoneType.Exile).forEach(exiled -> assertFalse(exiled.isUsedToPay()));
        assertNullOrEmptySelection(sa);
        player.getManaPool().addMana(new Mana(MagicColor.BLUE, card, null, player));
        player.getManaPool().addMana(new Mana(MagicColor.RED, card, null, player));
        assertTrue(ComputerUtilMana.payManaCost(sa.getPayCosts(), player, sa, false));
        assertEquals(sa.getPaidList("CoalescedCards", true).size(), 4);
    }
}
