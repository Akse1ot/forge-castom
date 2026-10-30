package forge.game.cost;

import forge.ai.AITest;
import forge.ai.AiCostDecision;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardDamageTable;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

/** Exercises tagged payments through CostPayment and the printed Godrick script. */
public class TaggedExileCostTest extends AITest {
    private static final String DONOR_LIST = "ExiledCost_GodrickDonor";

    private Game game() {
        Game game = initAndCreateGame();
        game.getPlayers().forEach(player -> fillLibrary(player, 12));
        return game;
    }

    private Card godrick(Player player, ZoneType zone) throws IOException {
        Path path = Path.of(ForgeConstants.CARD_DATA_DIR, "custom cards", "ERLB", "g",
                "Godrick_the_Grafted_Godrick_Borne_Witness.txt");
        CardRules rules = CardRules.fromScript(Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank()).collect(Collectors.toList()));
        Card card = Card.fromPaperCard(new PaperCard(rules, "ERLB", CardRarity.Rare), player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
        return card;
    }

    private void refresh(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private void mana(Player player, Card source, byte... colors) {
        for (byte color : colors) player.getManaPool().addMana(new Mana(color, source, null, player));
    }

    private void pay(SpellAbility ability, Function<CostExile, List<Card>> choices) {
        Player player = ability.getActivatingPlayer();
        CostPayment payment = new CostPayment(ability.getPayCosts(), ability);
        assertTrue(payment.payCost(new AiCostDecision(player, ability, false) {
            @Override
            public PaymentDecision visit(CostExile cost) {
                List<Card> cards = choices.apply(cost);
                assertEquals(cards.size(), cost.getAbilityAmount(ability));
                assertTrue(cost.canPay(ability, player, false));
                return PaymentDecision.card(cards);
            }
        }), "All costs, including mana, must actually be paid");
        assertTrue(payment.isFullyPaid());
        assertEquals(player.getManaPool().totalMana(), 0);
    }

    private PaidGodrick cast(Card card, String donorName, boolean escape) {
        Game game = card.getGame();
        Player player = card.getOwner();
        card.setState(CardStateName.Backside, true);
        Card donor = addCardToZone(donorName, player, ZoneType.Graveyard);
        List<Card> extra = new ArrayList<>();
        if (escape) {
            addCard("Underworld Breach", player);
            extra.add(addCardToZone("Prodigal Pyromancer", player, ZoneType.Graveyard));
            extra.add(addCardToZone("Forest", player, ZoneType.Graveyard));
            extra.add(addCardToZone("Forest", player, ZoneType.Graveyard));
        }
        refresh(game);
        SpellAbility normal = card.getFirstSpellAbility();
        normal.setActivatingPlayer(player);
        SpellAbility ability = escape ? GameActionUtil.getAlternativeCosts(normal, player, true).stream()
                .filter(SpellAbility::isEscape).findFirst().orElseThrow() : normal;
        ability.resetPaidHash(); // The normal casting procedure resets these before paying too.
        Card stackCard = game.getAction().moveToStack(card, ability);
        ability.setHostCard(stackCard);
        mana(player, stackCard, MagicColor.COLORLESS, MagicColor.COLORLESS, MagicColor.COLORLESS,
                MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN);
        pay(ability, cost -> "GodrickDonor".equals(cost.getExileTag()) ? List.of(donor) : extra);
        assertEquals(ability.getPaidList("ExiledCards", true).size(), escape ? 4 : 1);
        assertEquals(ability.getPaidList("Exiled", true).size(), escape ? 4 : 1);
        assertEquals(ability.getPaidList(DONOR_LIST, true).size(), 1);
        game.getStack().add(ability);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().resolveStack();
        Card permanent = ability.getHostCard();
        assertTrue(permanent.isInPlay());
        assertEquals(ability.getTotalManaSpent(), 6);
        refresh(game);
        return new PaidGodrick(permanent, ability, game.findById(donor.getId()), extra);
    }

    private record PaidGodrick(Card card, SpellAbility cast, Card donor, List<Card> escapeCards) { }

    private void assertDonor(PaidGodrick paid, boolean triggered) {
        SpellAbility storedCast = paid.card().getCastSA();
        assertNotNull(storedCast);
        assertNotNull(storedCast.getPaidList(DONOR_LIST, true));
        Card recorded = storedCast.getPaidList(DONOR_LIST, true).getFirst();
        assertTrue(recorded.equalsWithGameTimestamp(paid.donor()), "Payment must retain the donor's exile timestamp");
        assertTrue(paid.donor().getExiledWith().equalsWithGameTimestamp(storedCast.getHostCard()));
        assertTrue(storedCast.getHostCard().hasExiledCard(paid.donor()));
        assertEquals(paid.card().getTriggers().size(), triggered ? 1 : 0);
        assertEquals(paid.card().getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).count(), triggered ? 1L : 2L);
        assertFalse(paid.card().hasKeyword("Flying"));
        assertFalse(paid.card().getSpellAbilities().stream().anyMatch(sa -> sa.getApi() == ApiType.DealDamage),
                "An unrelated Pyromancer must not be inherited");
        assertEquals(AbilityUtils.getDefinedCards(paid.card(), "CastSA>" + DONOR_LIST,
                paid.card().getStaticAbilities().get(0)), List.of(paid.donor()));
    }

    @DataProvider
    public Object[][] donors() { return new Object[][] {{"Balefire Dragon", true}, {"Shivan Dragon", false}}; }

    @DataProvider
    public Object[][] donorDepartures() {
        return new Object[][] {{"Balefire Dragon", true, ZoneType.Hand}, {"Shivan Dragon", false, ZoneType.Hand},
                {"Balefire Dragon", true, ZoneType.Battlefield}, {"Shivan Dragon", false, ZoneType.Battlefield}};
    }

    @Test(dataProvider = "donors")
    public void ordinaryCastInheritsOnlyThePaidDonor(String name, boolean triggered) throws IOException {
        Game game = game();
        Player player = game.getPlayers().get(1);
        addCardToZone("Prodigal Pyromancer", player, ZoneType.Exile);
        PaidGodrick paid = cast(godrick(player, ZoneType.Hand), name, false);
        assertDonor(paid, triggered);
    }

    @Test
    public void escapePaysBothCostsAndExcludesPyromancer() throws IOException {
        Game game = game();
        PaidGodrick paid = cast(godrick(game.getPlayers().get(1), ZoneType.Graveyard), "Balefire Dragon", true);
        assertDonor(paid, true);
        assertTrue(paid.donor().isInZone(ZoneType.Exile));
        paid.escapeCards().forEach(card -> assertTrue(game.findById(card.getId()).isInZone(ZoneType.Exile)));
        assertEquals(paid.cast().getPayCosts().getCostParts().stream().filter(CostExile.class::isInstance).count(), 2L);
    }

    @Test
    public void inheritedCombatTriggerActuallyDamagesOpponentsCreatures() throws IOException {
        Game game = game();
        Player player = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        PaidGodrick paid = cast(godrick(player, ZoneType.Hand), "Balefire Dragon", false);
        Card victim = addCard("Shivan Dragon", opponent);
        refresh(game);
        CardDamageTable damage = new CardDamageTable();
        damage.put(paid.card(), opponent, 3);
        game.getAction().dealDamage(true, damage, new CardDamageTable(), new GameEntityCounterTable(), null);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().isEmpty());
        game.getStack().resolveStack();
        assertEquals(victim.getDamage(), 3);
    }

    @Test
    public void inheritedActivationPaysManaAndPumpsGodrick() throws IOException {
        Game game = game();
        Player player = game.getPlayers().get(1);
        PaidGodrick paid = cast(godrick(player, ZoneType.Hand), "Shivan Dragon", false);
        SpellAbility pump = paid.card().getSpellAbilities().stream()
                .filter(sa -> sa.isActivatedAbility() && sa.isCopiedTrait()).findFirst().orElseThrow();
        pump.setActivatingPlayer(player);
        int power = paid.card().getNetPower();
        mana(player, paid.card(), MagicColor.RED);
        pay(pump, cost -> { throw new AssertionError("No exile cost on the inherited activation"); });
        game.getStack().add(pump);
        game.getStack().resolveStack();
        assertEquals(paid.card().getNetPower(), power + 1);
    }

    @Test(dataProvider = "donorDepartures")
    public void leavingExileRemovesAbilitiesAndReexileDoesNotRestoreThem(String name, boolean triggered, ZoneType destination) throws IOException {
        Game game = game();
        Player player = game.getPlayers().get(1);
        PaidGodrick paid = cast(godrick(player, ZoneType.Hand), name, false);
        assertDonor(paid, triggered);
        long paidTimestamp = paid.cast().getPaidList(DONOR_LIST, true).getFirst().getGameTimestamp();
        Card moved = destination == ZoneType.Hand ? game.getAction().moveToHand(paid.donor(), null)
                : game.getAction().moveToPlay(paid.donor(), player, null, forge.game.ability.AbilityKey.newMap());
        assertEquals(paid.cast().getPaidList(DONOR_LIST, true).getFirst().getGameTimestamp(), paidTimestamp,
                "The recorded exile incarnation must not mutate with the live card");
        refresh(game);
        assertEquals(paid.card().getTriggers().size(), 0);
        assertEquals(paid.card().getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).count(), 1L);
        assertTrue(AbilityUtils.getDefinedCards(paid.card(), "CastSA>" + DONOR_LIST,
                paid.card().getStaticAbilities().get(0)).isEmpty());
        paid.card().addRemembered(moved);
        SpellAbility exileAgain = AbilityFactory.getAbility(
                "DB$ ChangeZone | Defined$ Remembered | Origin$ " + destination + " | Destination$ Exile", paid.card());
        exileAgain.setActivatingPlayer(player);
        AbilityUtils.resolve(exileAgain);
        paid.card().clearRemembered();
        assertTrue(paidTimestamp != game.findById(moved.getId()).getGameTimestamp());
        refresh(game);
        assertEquals(paid.card().getTriggers().size(), 0);
        assertEquals(paid.card().getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).count(), 1L);
        assertEquals(paid.cast().getPaidList(DONOR_LIST, true).size(), 1, "History remains, but is no longer a live link");
    }

    @Test
    public void twoGodricksKeepTheirDonorsSeparate() throws IOException {
        Game game = game();
        Player player = game.getPlayers().get(1);
        PaidGodrick first = cast(godrick(player, ZoneType.Hand), "Shivan Dragon", false);
        PaidGodrick second = cast(godrick(player, ZoneType.Graveyard), "Balefire Dragon", true);
        assertDonor(first, false);
        assertDonor(second, true);
        game.getAction().moveToHand(first.donor(), null);
        refresh(game);
        assertEquals(first.card().getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).count(), 1L);
        assertDonor(second, true);
    }

    @Test
    public void recastingGodrickUsesOnlyTheNewPayment() throws IOException {
        Game game = game();
        Player player = game.getPlayers().get(1);
        PaidGodrick first = cast(godrick(player, ZoneType.Hand), "Shivan Dragon", false);
        Card hand = game.getAction().moveToHand(first.card(), null);
        PaidGodrick second = cast(hand, "Balefire Dragon", false);
        assertEquals(first.card().getId(), second.card().getId());
        assertFalse(first.card().equalsWithGameTimestamp(second.card()));
        assertTrue(first.donor().isInZone(ZoneType.Exile));
        assertDonor(second, true);
    }

    @Test
    public void enteringWithoutCastingDoesNotReuseThePreviousLink() throws IOException {
        Game game = game();
        Player player = game.getPlayers().get(1);
        PaidGodrick paid = cast(godrick(player, ZoneType.Hand), "Shivan Dragon", false);
        Card hand = game.getAction().moveToHand(paid.card(), null);
        hand.setState(CardStateName.Backside, true);
        Card returned = game.getAction().moveToPlay(hand, player, null, forge.game.ability.AbilityKey.newMap());
        refresh(game);
        assertEquals(returned.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).count(), 1L);
        assertEquals(returned.getTriggers().size(), 0);
    }

    @DataProvider
    public Object[][] tagOrders() { return new Object[][] {{true}, {false}}; }

    @Test(dataProvider = "tagOrders")
    public void tagsSurviveCopiesAndNeverMergeWithOtherExileParts(boolean taggedFirst) {
        Game game = game();
        Player player = game.getPlayers().get(1);
        Card source = addCard("Forest", player);
        Cost cost = new Cost("0", true);
        if (!taggedFirst) cost.add(new Cost("ExileFromGrave<2/Card>", true));
        cost.add(new Cost("ExileFromGrave<1/Card/ExileTag=First>", true));
        cost.add(new Cost("ExileFromGrave<1/Card/ExileTag=Second>", true));
        if (taggedFirst) {
            cost.add(new Cost("ExileFromGrave<1/Card>", true));
            cost.add(new Cost("ExileFromGrave<1/Card>", true));
        }
        List<CostExile> parts = cost.copyWithNoMana().getCostParts().stream().filter(CostExile.class::isInstance)
                .map(CostExile.class::cast).collect(Collectors.toList());
        assertEquals(parts.size(), 3);
        assertEquals(parts.stream().filter(c -> c.getExileTag() == null).findFirst().orElseThrow().getAmount(), "2");
        SpellAbility ability = AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | NumCards$ 1", source);
        ability.setActivatingPlayer(player);
        ability.setPayCosts(cost.copy());
        Card first = addCardToZone("Shivan Dragon", player, ZoneType.Graveyard);
        Card second = addCardToZone("Balefire Dragon", player, ZoneType.Graveyard);
        List<Card> ordinary = List.of(addCardToZone("Forest", player, ZoneType.Graveyard),
                addCardToZone("Forest", player, ZoneType.Graveyard));
        // Payment order may change; choices and their links are keyed by tag, never by index.
        Collections.reverse(ability.getPayCosts().getCostParts());
        refresh(game);
        pay(ability, part -> "First".equals(part.getExileTag()) ? List.of(first)
                : "Second".equals(part.getExileTag()) ? List.of(second) : ordinary);
        assertEquals(ability.getPaidList("ExiledCards", true).size(), 4);
        assertEquals(ability.getPaidList("Exiled", true).size(), 4);
        assertEquals(ability.getPaidList("ExiledCost_First", true).getFirst().getId(), first.getId());
        assertEquals(ability.getPaidList("ExiledCost_Second", true).getFirst().getId(), second.getId());
        SpellAbility copy = ability.copy(source, player, true);
        assertEquals(copy.getPaidList("ExiledCost_First", true), ability.getPaidList("ExiledCost_First", true));
        assertEquals(copy.getPayCosts().getCostParts().stream().filter(CostExile.class::isInstance)
                .map(CostExile.class::cast).filter(c -> c.getExileTag() != null).count(), 2L);
    }

    @Test
    public void taggedListsPreserveIntrinsicExtrinsicPartition() {
        Game game = game();
        Player player = game.getPlayers().get(1);
        Card source = addCard("Forest", player);
        SpellAbility ability = AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | NumCards$ 1", source);
        ability.setActivatingPlayer(player);
        Cost cost = new Cost("ExileFromGrave<1/Card/printed/ExileTag=Shared>", true, true);
        cost.add(new Cost("ExileFromGrave<1/Card/granted/ExileTag=Shared>", true, false));
        ability.setPayCosts(cost);
        Card intrinsic = addCardToZone("Shivan Dragon", player, ZoneType.Graveyard);
        Card extrinsic = addCardToZone("Balefire Dragon", player, ZoneType.Graveyard);
        refresh(game);
        pay(ability, part -> List.of("printed".equals(part.getTypeDescription()) ? intrinsic : extrinsic));
        assertEquals(ability.getPaidList("ExiledCost_Shared", true).size(), 1);
        assertEquals(ability.getPaidList("ExiledCost_Shared", false).size(), 1);
        assertEquals(ability.getPaidList("ExiledCards", true).getFirst().getId(), intrinsic.getId());
        assertEquals(ability.getPaidList("ExiledCards", false).getFirst().getId(), extrinsic.getId());
        assertEquals(AbilityUtils.getDefinedCards(source, "ExiledCost_Shared", ability).getFirst().getId(), intrinsic.getId());
    }

    @Test
    public void triggerDefinedAloneRegistersTheAbilityLayerAndCanRemoveItsGrant() {
        Game game = game();
        Player player = game.getPlayers().get(1);
        Card source = addCard("Forest", player);
        Card donor = addCardToZone("Balefire Dragon", player, ZoneType.Exile);
        source.setChosenCards(List.of(donor));
        source.addStaticAbility("Mode$ Continuous | Affected$ Card.Self | GainsTriggerAbsOfDefined$ ChosenCard");
        refresh(game);
        assertEquals(source.getTriggers().size(), 1);
        source.setChosenCards(List.of());
        refresh(game);
        assertEquals(source.getTriggers().size(), 0);
    }

    @Test
    public void ordinaryCardsKeepUntypedInheritanceAndExileCosts() {
        Game game = game();
        Player player = game.getPlayers().get(1);
        Card ooze = addCard("Necrotic Ooze", player);
        addCardToZone("Shivan Dragon", player, ZoneType.Graveyard);
        refresh(game);
        assertTrue(ooze.getSpellAbilities().stream().anyMatch(sa -> sa.isActivatedAbility() && sa.getApi() == ApiType.Pump));
        assertFalse(ooze.hasKeyword("Flying"));
        Card idris = addCard("Idris, Soul of the TARDIS", player);
        Card donor = addCardToZone("Balefire Dragon", player, ZoneType.Graveyard);
        SpellAbility exile = AbilityFactory.getAbility("AB$ Draw | Cost$ ExileFromGrave<1/Card> | NumCards$ 1", idris);
        exile.setActivatingPlayer(player);
        refresh(game);
        pay(exile, part -> List.of(donor));
        refresh(game);
        assertTrue(idris.getTriggers().size() > 1, "Existing GainsTriggerAbsOf must still inherit Balefire's trigger");
        assertNull(exile.getPaidList("ExiledCost_GodrickDonor", true));
        assertEquals(exile.getPaidList("ExiledCards", true).size(), 1);
        assertEquals(exile.getPaidList("Exiled", true).size(), 1);
    }

    @Test
    public void ordinarySkaabRuinatorPaysItsUntypedCreatureExileCost() {
        Game game = game();
        Player player = game.getPlayers().get(1);
        Card ruinator = addCardToZone("Skaab Ruinator", player, ZoneType.Hand);
        List<Card> creatures = List.of(addCardToZone("Runeclaw Bear", player, ZoneType.Graveyard),
                addCardToZone("Runeclaw Bear", player, ZoneType.Graveyard),
                addCardToZone("Runeclaw Bear", player, ZoneType.Graveyard));
        SpellAbility spell = ruinator.getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        Card stackCard = game.getAction().moveToStack(ruinator, spell);
        spell.setHostCard(stackCard);
        mana(player, stackCard, MagicColor.COLORLESS, MagicColor.BLUE, MagicColor.BLUE);
        pay(spell, part -> creatures);
        game.getStack().add(spell);
        game.getStack().resolveStack();
        assertTrue(spell.getHostCard().isInPlay());
        assertEquals(spell.getPaidList("ExiledCards", true).size(), 3);
        assertEquals(spell.getPaidList("Exiled", true).size(), 3);
        assertTrue(spell.getPaidHash().rowKeySet().stream().noneMatch(key -> key.startsWith("ExiledCost_")));
    }
}
