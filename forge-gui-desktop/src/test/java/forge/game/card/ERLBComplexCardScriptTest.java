package forge.game.card;

import forge.ai.AITest;
import forge.ai.ComputerUtilCost;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostExile;
import forge.game.cost.CostSacrifice;
import forge.game.cost.PaymentDecision;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.PlayerControllerForTests;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.util.collect.FCollectionView;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

/** Loads the exact reviewed scripts; exercises effects against real zones, costs and stack entries. */
public class ERLBComplexCardScriptTest extends AITest {
    private static final String GATE = "Gate_of_Divinity.txt";
    private static final String SERPENT = "God_Devouring_Serpent_Rykard_Lord_of_Blasphemy.txt";
    private static final String GODRICK = "Godrick_the_Grafted_Godrick_Borne_Witness.txt";
    private static final String PARRY = "Golden_Parry_Carian Retaliation.txt";
    private static final String GUARD = "Guard_Counter_Backstab.txt";
    private static final String WYRM = "Great_Wyrm_Theodorix_Theodorixs_Magma.txt";

    private Card load(String file, Player player, ZoneType zone) throws IOException {
        Path path = Path.of(ForgeConstants.CARD_DATA_DIR, "custom cards", "ERLB", "g", file);
        CardRules rules = CardRules.fromScript(Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(s -> !s.isBlank()).collect(Collectors.toList()));
        Card card = CardFactory.getCard(new PaperCard(rules, "ERLB", CardRarity.Rare), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        if (zone == ZoneType.Stack) player.getGame().getStackZone().add(card);
        else player.getZone(zone).add(card);
        return card;
    }

    private SpellAbility named(Card card, String name, Player player) {
        SpellAbility sa = AbilityFactory.getAbility(card.getSVar(name), card);
        sa.setActivatingPlayer(player);
        return sa;
    }

    private Card ordinary(String name, Player player, ZoneType zone) {
        if (zone != ZoneType.Stack) return addCardToZone(name, player, zone);
        Card card = createCard(name, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getGame().getStackZone().add(card);
        return card;
    }

    private void back(Card card) {
        card.setState(card.getAlternateState().getStateName(), true);
    }

    private void prepare(Game game) {
        game.getAction().checkStaticAbilities();
        game.copyLastState();
        game.getTriggerHandler().resetActiveTriggers();
    }

    private void decisions(Player player, boolean pay) {
        player.dangerouslySetController(new PlayerControllerForTests(player.getGame(), player, player.getLobbyPlayer()) {
            @Override
            public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean paid, FCollectionView<Player> payers) {
                // Deterministic resolution branch; mana payment UI itself is outside these effect tests.
                return pay;
            }
        });
    }

    @DataProvider
    public Object[][] gatePaths() { return new Object[][] {{false, 1}, {false, 2}, {true, 1}}; }

    @Test(dataProvider = "gatePaths")
    public void gateAffectsOnlyPaidCreatureAndCleansItsEffects(boolean countered, int gates) throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card creature = ordinary("Runeclaw Bear", p, ZoneType.Stack);
        Card other = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        for (int i = 0; i < gates; i++) {
            Card gate = load(GATE, p, ZoneType.Graveyard);
            SpellAbility effect = named(gate, "TrigEffect", p);
            effect.setTriggeringObject(AbilityKey.Card, creature);
            AbilityUtils.resolve(effect);
        }
        assertEquals(p.getCardsIn(ZoneType.Command).size(), gates);
        prepare(game);
        Card moved = countered ? game.getAction().moveToGraveyard(creature, null)
                : game.getAction().moveToPlay(creature, p, null, AbilityKey.newMap());
        game.getTriggerHandler().runWaitingTriggers();
        if (!countered) {
            assertTrue(moved.getType().isLegendary());
            assertTrue(moved.getType().hasSubtype("God"));
            assertTrue(moved.getKeywords().toString().contains("Protection"));
        }
        assertTrue(p.getCardsIn(ZoneType.Command).isEmpty(), "No shield should survive the stack departure");
        Card unrelated = game.getAction().moveToPlay(other, p, null, AbilityKey.newMap());
        assertFalse(unrelated.getType().isLegendary());
        assertFalse(unrelated.getType().hasSubtype("God"));
        if (!countered) moved = game.getAction().moveToGraveyard(moved, null);
        moved = game.getAction().moveToPlay(moved, p, null, AbilityKey.newMap());
        assertFalse(moved.getType().isLegendary(), "Returning creates a new object");
        assertFalse(moved.getType().hasSubtype("God"));
    }

    @Test
    public void gateSpentManaTriggerIsBoundToExactlyThePaidSpell() throws IOException, ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card gate = load(GATE, p, ZoneType.Graveyard);
        Card paid = ordinary("Runeclaw Bear", p, ZoneType.Stack);
        Card unrelated = ordinary("Runeclaw Bear", p, ZoneType.Stack);
        SpellAbility cast = paid.getFirstSpellAbility();
        cast.setActivatingPlayer(p);
        SpellAbility mana = gate.getManaAbilities().stream().filter(sa -> sa.hasParam("TriggersWhenSpent")).findFirst().orElseThrow();
        mana.setActivatingPlayer(p);
        mana.getManaPart().addTriggersWhenSpent(cast);
        Field field = forge.game.trigger.TriggerHandler.class.getDeclaredField("delayedTriggers");
        field.setAccessible(true);
        List<?> delayed = (List<?>) field.get(game.getTriggerHandler());
        Trigger trigger = (Trigger) delayed.get(0);
        java.util.Map<AbilityKey, Object> event = AbilityKey.newMap();
        event.put(AbilityKey.SpellAbility, cast);
        event.put(AbilityKey.Activator, p);
        assertTrue(trigger.performTest(event));
        SpellAbility otherCast = unrelated.getFirstSpellAbility();
        otherCast.setActivatingPlayer(p);
        event.put(AbilityKey.SpellAbility, otherCast);
        assertFalse(trigger.performTest(event));
    }

    @DataProvider
    public Object[][] sacrifices() { return new Object[][] {{false, 0}, {false, 2}, {false, 5}, {true, 0}, {true, 2}}; }

    @Test(dataProvider = "sacrifices")
    public void serpentReducesOnlyOptionalSacrifices(boolean graveyard, int x) throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card serpent = load(SERPENT, p, graveyard ? ZoneType.Graveyard : ZoneType.Hand);
        SpellAbility cast = serpent.getSpellAbilities().stream().filter(SpellAbility::isSpell)
                .filter(sa -> graveyard == "Graveyard".equals(sa.getParam("ActivationZone"))).findFirst().orElseThrow();
        cast.setActivatingPlayer(p);
        cast.setXManaCostPaid(x);
        List<Card> fodder = addCards("Runeclaw Bear", x + (graveyard ? 3 : 0), p);
        prepare(game);
        List<CostSacrifice> costs = cast.getPayCosts().getCostParts().stream().filter(CostSacrifice.class::isInstance)
                .map(CostSacrifice.class::cast).collect(Collectors.toList());
        assertEquals(costs.size(), graveyard ? 2 : 1);
        for (CostSacrifice cost : costs) {
            int amount = cost.getAbilityAmount(cast);
            assertTrue(cost.canPay(cast, p, false));
            List<Card> chosen = List.copyOf(p.getCardsIn(ZoneType.Battlefield)).subList(0, amount);
            cost.payAsDecided(p, PaymentDecision.card(chosen), cast, false);
        }
        assertEquals(p.getCardsIn(ZoneType.Graveyard).size(), fodder.size() + (graveyard ? 1 : 0));
        ManaCostBeingPaid mana = new ManaCostBeingPaid(cast.getPayCosts().getCostMana().getMana());
        assertTrue(CostAdjustment.adjust(mana, cast, p, null, true, false));
        assertEquals(mana.getConvertedManaCost(), Math.max(0, 8 - 2 * x) + 2);
        if (graveyard) assertFalse(costs.get(1).canPay(cast, p, false));
    }

    @Test
    public void serpentAiReservesThreeMandatorySacrifices() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card serpent = load(SERPENT, p, ZoneType.Graveyard);
        SpellAbility cast = serpent.getSpellAbilities().stream().filter(sa -> "Graveyard".equals(sa.getParam("ActivationZone")))
                .findFirst().orElseThrow();
        cast.setActivatingPlayer(p);
        addCards("Runeclaw Bear", 3, p);
        assertEquals(ComputerUtilCost.setMaxXValue(cast, p, false), 0);
        addCards("Runeclaw Bear", 2, p);
        assertEquals(ComputerUtilCost.setMaxXValue(cast, p, false), 2);
    }

    @Test
    public void graftingUsesSacrificedCounterLki() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card godrick = load(GODRICK, p, ZoneType.Battlefield);
        Card fodder = addCard("Runeclaw Bear", p);
        fodder.setCounters(CounterEnumType.P1P1, 5);
        SpellAbility graft = godrick.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.PutCounter)
                .findFirst().orElseThrow();
        graft.setActivatingPlayer(p);
        prepare(game);
        CostSacrifice sac = graft.getPayCosts().getCostParts().stream().filter(CostSacrifice.class::isInstance)
                .map(CostSacrifice.class::cast).findFirst().orElseThrow();
        sac.payAsDecided(p, PaymentDecision.card(fodder), graft, false);
        assertEquals(game.findById(fodder.getId()).getCounters(CounterEnumType.P1P1), 0);
        AbilityUtils.resolve(graft);
        assertEquals(godrick.getCounters(CounterEnumType.P1P1), 5);
    }

    @DataProvider
    public Object[][] donors() { return new Object[][] {{"Shivan Dragon"}, {"Balefire Dragon"}}; }

    @Test(dataProvider = "donors")
    public void borneWitnessGainsOnlyLinkedActivatedAndTriggeredAbilities(String name) throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card godrick = load(GODRICK, p, ZoneType.Stack);
        back(godrick);
        Card donor = addCardToZone(name, p, ZoneType.Graveyard);
        long activated = donor.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).count();
        int triggered = donor.getTriggers().size();
        Card unrelated = addCardToZone("Prodigal Pyromancer", p, ZoneType.Exile);
        SpellAbility cast = godrick.getFirstSpellAbility();
        cast.setActivatingPlayer(p);
        godrick.setCastSA(cast);
        CostExile exile = cast.getPayCosts().getCostParts().stream().filter(CostExile.class::isInstance)
                .map(CostExile.class::cast).findFirst().orElseThrow();
        exile.payAsDecided(p, PaymentDecision.card(donor), cast, false);
        AbilityUtils.resolve(cast);
        Card permanent = cast.getHostCard();
        prepare(game);
        assertEquals(permanent.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).count(), 1L + activated);
        assertEquals(permanent.getTriggers().size(), triggered);
        assertFalse(permanent.getSpellAbilities().stream().anyMatch(sa -> sa.getApi() == ApiType.DealDamage));
        assertFalse(permanent.hasKeyword("Flying"), "Static keyword abilities must not be inherited");
        assertTrue(permanent.getExiledCards().stream().noneMatch(c -> c.getId() == unrelated.getId()));
    }

    @DataProvider
    public Object[][] counterCases() { return new Object[][] {{false, false, 0}, {true, false, 0},
            {false, true, 0}, {false, false, 1}, {false, false, 2}}; }

    @Test(dataProvider = "counterCases")
    public void retaliationCreatesBlueTokensOnlyForActualCounter(boolean pay, boolean uncounterable, int kind) throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        decisions(opponent, pay);
        Card victim = addCard("Runeclaw Bear", p);
        Card source = ordinary(kind == 2 ? "Flametongue Kavu" : kind == 1 ? "Prodigal Pyromancer" : "Lightning Bolt", opponent,
                kind != 0 ? ZoneType.Battlefield : ZoneType.Stack);
        if (uncounterable) source.addReplacementEffect(forge.game.replacement.ReplacementHandler.parseReplacement(
                "Event$ Counter | ValidCard$ Card.Self | ValidSA$ Spell | Layer$ CantHappen", source, true));
        SpellAbility target;
        if (kind == 2) {
            Trigger trigger = source.getTriggers().get(0);
            target = trigger.ensureAbility().copy(source, opponent, false);
            target.setTrigger(trigger);
        } else {
            target = source.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.DealDamage)
                    .findFirst().orElseThrow();
        }
        target.setActivatingPlayer(opponent);
        target.getTargets().add(victim);
        game.getStack().add(target);
        target = game.getStack().peekAbility();
        Card parry = load(PARRY, p, ZoneType.Stack);
        back(parry);
        SpellAbility counter = parry.getFirstSpellAbility();
        counter.setActivatingPlayer(p);
        assertTrue(counter.canTargetSpellAbility(target), counter.getDescription() + "; target=" + target
                + "; choices=" + target.getTargets() + "; victim=" + victim.getZone());
        counter.getTargets().add(target);
        AbilityUtils.resolve(counter);
        boolean success = !pay && !uncounterable;
        List<Card> tokens = p.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).collect(Collectors.toList());
        assertEquals(tokens.size(), success ? 3 : 0);
        tokens.forEach(token -> {
            assertTrue(token.getColor().hasBlue());
            assertEquals(token.getName(), "Glintblade");
        });
        assertEquals(game.getStack().isEmpty(), success);
        assertFalse(parry.hasRemembered());
    }

    @Test
    public void retaliationWithMissingStackTargetCreatesNoTokens() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card victim = addCard("Runeclaw Bear", p);
        Card bolt = ordinary("Lightning Bolt", opponent, ZoneType.Stack);
        SpellAbility target = bolt.getFirstSpellAbility();
        target.setActivatingPlayer(opponent);
        target.getTargets().add(victim);
        game.getStack().add(target);
        Card parry = load(PARRY, p, ZoneType.Stack);
        back(parry);
        SpellAbility spell = parry.getFirstSpellAbility();
        spell.setActivatingPlayer(p);
        spell.getTargets().add(game.getStack().peekAbility());
        game.getStack().add(spell);
        SpellAbility otherCounter = AbilityFactory.getAbility("DB$ Counter | TargetType$ Spell | ValidTgts$ Card", victim);
        otherCounter.setActivatingPlayer(p);
        otherCounter.getTargets().add(target);
        AbilityUtils.resolve(otherCounter);
        game.getStack().resolveStack();
        assertTrue(p.getCardsIn(ZoneType.Battlefield).stream().noneMatch(Card::isToken));
    }

    @DataProvider
    public Object[][] guardDamage() { return new Object[][] {{true, false}, {true, true}, {false, false}}; }

    @Test(dataProvider = "guardDamage")
    public void guardPreventsAndReturnsOnlyPreventableCombatDamage(boolean combat, boolean unpreventable) throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card blocker = addCard("Giant Spider", p), recipient = addCard("Shivan Dragon", opponent);
        Card attacker = addCard("Runeclaw Bear", opponent), attacker2 = addCard("Runeclaw Bear", opponent);
        if (unpreventable) {
            attacker.addStaticAbility("Mode$ CantPreventDamage | ValidSource$ Card.Self");
            attacker2.addStaticAbility("Mode$ CantPreventDamage | ValidSource$ Card.Self");
        }
        Card guard = load(GUARD, p, ZoneType.Stack);
        SpellAbility spell = guard.getFirstSpellAbility();
        spell.setActivatingPlayer(p);
        spell.getTargets().add(blocker);
        spell.getSubAbility().getTargets().add(recipient);
        AbilityUtils.resolve(spell);
        prepare(game);
        CardDamageTable damage = new CardDamageTable(), prevented = new CardDamageTable();
        damage.put(attacker, blocker, 2);
        damage.put(attacker2, blocker, 1);
        game.getAction().dealDamage(combat, damage, prevented, new GameEntityCounterTable(), null);
        assertEquals(blocker.getDamage(), combat && !unpreventable ? 0 : 3);
        assertEquals(recipient.getDamage(), combat && !unpreventable ? 3 : 0);
        if (combat && !unpreventable) {
            // A second damage step must use the same shield rather than consume it after first strike.
            CardDamageTable secondStep = new CardDamageTable();
            secondStep.put(attacker, blocker, 2);
            game.getAction().dealDamage(true, secondStep, new CardDamageTable(), new GameEntityCounterTable(), null);
            assertEquals(blocker.getDamage(), 0);
            assertEquals(recipient.getDamage(), 5);
        }
    }

    @Test
    public void backstabGrantsTemporaryTriggerRestrictedToDamagedPlayersPermanents() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card assassin = addCard("Runeclaw Bear", p), enemy = addCard("Shivan Dragon", opponent);
        Card card = load(GUARD, p, ZoneType.Stack);
        back(card);
        SpellAbility spell = card.getFirstSpellAbility();
        spell.setActivatingPlayer(p);
        spell.getTargets().add(assassin);
        AbilityUtils.resolve(spell);
        Trigger trigger = assassin.getTriggers().stream().filter(t -> t.getMode() == TriggerType.DamageDone).findFirst().orElseThrow();
        SpellAbility destroy = trigger.ensureAbility();
        destroy.setActivatingPlayer(p);
        destroy.setTriggeringObject(AbilityKey.Target, opponent);
        assertTrue(destroy.canTarget(enemy));
        assertFalse(destroy.canTarget(assassin));
        destroy.getTargets().add(enemy);
        AbilityUtils.resolve(destroy);
        assertTrue(game.findById(enemy.getId()).isInZone(ZoneType.Graveyard));
        game.getEndOfTurn().executeUntil();
        assertTrue(assassin.getTriggers().stream().noneMatch(t -> t.getMode() == TriggerType.DamageDone));
    }

    @Test
    public void godwynReturnsForeignCreatureWithFinalityAndAdditionalTypes() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card godwyn = load("Godwyn_Prince_of_Death.txt", p, ZoneType.Battlefield);
        Card dead = addCardToZone("Runeclaw Bear", opponent, ZoneType.Graveyard);
        SpellAbility returnSA = named(godwyn, "TrigReturn", p);
        returnSA.setTriggeringObject(AbilityKey.Card, dead);
        AbilityUtils.resolve(returnSA);
        Card returned = game.findById(dead.getId());
        assertSame(returned.getController(), p);
        assertSame(returned.getOwner(), opponent);
        assertTrue(returned.isTapped());
        assertEquals(returned.getCounters(CounterEnumType.FINALITY), 1);
        for (String type : List.of("Bear", "Skeleton", "Spirit")) assertTrue(returned.getType().hasSubtype(type));
        prepare(game);
        Card removed = game.getAction().moveToGraveyard(returned, null);
        assertTrue(removed.isInZone(ZoneType.Exile));
        assertFalse(godwyn.hasRemembered());
    }

    @Test
    public void godwynCountsOtherUndeadOnceEach() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card godwyn = load("Godwyn_Prince_of_Death.txt", p, ZoneType.Battlefield);
        godwyn.setSickness(false);
        addCard("Reassembling Skeleton", p);
        Card multi = addCard("Walking Corpse", p);
        multi.addType("Spirit");
        prepare(game);
        assertFalse(forge.game.combat.CombatUtil.canAttack(godwyn, opponent));
        assertFalse(forge.game.combat.CombatUtil.canBlock(godwyn));
        addCard("Spectral Sailor", p);
        prepare(game);
        assertTrue(forge.game.combat.CombatUtil.canAttack(godwyn, opponent));
        assertTrue(forge.game.combat.CombatUtil.canBlock(godwyn));
    }

    @Test
    public void godwynSeesSimultaneousDeathsIncludingTokensWhileDyingItself() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        fillLibrary(p, 8);
        fillLibrary(opponent, 8);
        Card godwyn = load("Godwyn_Prince_of_Death.txt", p, ZoneType.Battlefield);
        Card first = addCard("Runeclaw Bear", opponent), second = addCard("Giant Spider", p);
        Card token = addCard("Runeclaw Bear", opponent);
        token.setGamePieceType(forge.card.GamePieceType.TOKEN);
        Card cause = addCard("Forest", p);
        prepare(game);
        SpellAbility sacrifice = AbilityFactory.getAbility("DB$ SacrificeAll | ValidCards$ Creature", cause);
        sacrifice.setActivatingPlayer(p);
        AbilityUtils.resolve(sacrifice);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        int remaining = 8;
        while (!game.getStack().isEmpty() && remaining-- > 0) game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty(), "Finality must not produce a resurrection loop");
        assertTrue(game.findById(godwyn.getId()).isInZone(ZoneType.Graveyard));
        assertSame(game.findById(first.getId()).getController(), p);
        assertSame(game.findById(second.getId()).getController(), p);
        assertTrue(game.findById(first.getId()).isInPlay());
        assertTrue(game.findById(second.getId()).isInPlay());
        assertTrue(game.getCardsIn(ZoneType.Battlefield).stream().noneMatch(c -> c.getId() == token.getId()));
    }

    @Test
    public void duelistAttachesOnlyLegalAttachmentsWithoutTargetingItself() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        decisions(p, false);
        Card duelist = load("Grave_Warden_Duelist.txt", p, ZoneType.Battlefield);
        duelist.addIntrinsicKeyword("Shroud");
        Card equipment = addCard("Bonesplitter", p), aura = addCard("Wild Growth", p);
        SpellAbility attach = named(duelist, "TrigTargetAuraEquip", p);
        assertFalse(attach.getSubAbility().usesTargeting());
        attach.getTargets().add(equipment);
        AbilityUtils.resolve(attach);
        assertSame(equipment.getAttachedTo(), duelist);
        attach = named(duelist, "TrigTargetAuraEquip", p);
        attach.getTargets().add(aura);
        AbilityUtils.resolve(attach);
        assertNull(aura.getAttachedTo(), "Enchant land cannot attach to this creature");
    }

    @DataProvider
    public Object[][] payments() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "payments")
    public void sorcererReturnsOnlyIfPaymentAccepted(boolean paid) throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        decisions(p, paid);
        Card sorcerer = load("Glintstone_Sorcerer.txt", p, ZoneType.Graveyard);
        Card instant = addCardToZone("Lightning Bolt", p, ZoneType.Graveyard);
        SpellAbility recover = named(sorcerer, "TrigReturn", p);
        assertFalse(recover.canTarget(addCardToZone("Lightning Bolt", game.getPlayers().get(0), ZoneType.Graveyard)));
        recover.getTargets().add(instant);
        AbilityUtils.resolve(recover);
        assertTrue(game.findById(instant.getId()).isInZone(paid ? ZoneType.Hand : ZoneType.Graveyard));
        AbilityUtils.resolve(named(sorcerer, "TrigReturn", p)); // Up to zero cards is valid.
    }

    @Test(dataProvider = "payments")
    public void gowryCreatesRotForEachPlayerAndDrawsOnlyWhenPaid(boolean paid) throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        decisions(p, paid);
        fillLibrary(p, 3);
        Card gowry = load("Gowry_Sage_of_Rot.txt", p, ZoneType.Battlefield);
        AbilityUtils.resolve(named(gowry, "TrigToken", p));
        for (Player player : game.getPlayers()) {
            Card rot = player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).findFirst().orElseThrow();
            assertTrue(rot.isLand());
            assertTrue(rot.getColor().isColorless());
            assertEquals(rot.getName(), "Rot");
        }
        AbilityUtils.resolve(named(gowry, "TrigMayDraw", p));
        assertEquals(p.getCardsIn(ZoneType.Hand).size(), paid ? 1 : 0);
    }

    @Test
    public void theodorixCopiesCreatureOnStackAndSacrificesOnlyOnOwnEndStep() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        fillLibrary(p, 8);
        fillLibrary(opponent, 8);
        Card wyrm = load(WYRM, p, ZoneType.Battlefield);
        Card original = ordinary("Verix Bladewing", p, ZoneType.Stack);
        SpellAbility originalSpell = original.getFirstSpellAbility();
        originalSpell.setActivatingPlayer(p);
        originalSpell.addOptionalCost(forge.game.spellability.OptionalCost.Kicker1);
        original.setCastSA(originalSpell);
        prepare(game);
        game.getStack().add(originalSpell);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertNotSame(game.getStack().peekAbility(), originalSpell, "The real kicked-cast trigger must be on stack");
        game.getStack().resolveStack();
        SpellAbility copiedSpell = game.getStack().peekAbility();
        assertNotSame(copiedSpell, originalSpell);
        assertTrue(copiedSpell.isCopied());
        assertTrue(copiedSpell.isKicked());
        assertFalse(copiedSpell.getHostCard().isInPlay(), "Copy must wait for spell resolution");
        assertFalse(copiedSpell.getHostCard().getType().isLegendary());
        assertTrue(copiedSpell.getHostCard().hasKeyword("Haste"));
        int id = copiedSpell.getHostCard().getId();
        game.getStack().resolveStack();
        Card token = game.findById(id);
        assertTrue(token.isInPlay());
        assertTrue(token.isToken());
        // Resolve the original and any resulting ETB triggers before testing the delayed sacrifice.
        int remaining = 12;
        while (!game.getStack().isEmpty() && remaining-- > 0) game.getStack().resolveStack();
        assertTrue(game.getStack().isEmpty());
        prepare(game);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opponent);
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(opponent), false);
        assertTrue(game.getStack().isEmpty(), "Opponent's end step must not consume the delayed trigger");
        assertTrue(token.isInPlay());
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, p);
        game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(p), false);
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertFalse(game.getStack().isEmpty());
        remaining = 8;
        while (!game.getStack().isEmpty() && remaining-- > 0) game.getStack().resolveStack();
        assertTrue(game.findById(id) == null || !game.findById(id).isInPlay());
        assertTrue(game.findById(original.getId()).isInPlay());
        assertFalse(wyrm.hasRemembered());
    }

    @Test
    public void theodorixNoncreatureCopyRetainsKickerAndOriginalTargets() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card wyrm = load(WYRM, p, ZoneType.Battlefield);
        Card original = ordinary("Burst Lightning", p, ZoneType.Stack);
        Card victim = addCard("Shivan Dragon", game.getPlayers().get(0));
        SpellAbility originalSpell = original.getFirstSpellAbility();
        originalSpell.setActivatingPlayer(p);
        originalSpell.addOptionalCost(forge.game.spellability.OptionalCost.Kicker1);
        original.setCastSA(originalSpell);
        originalSpell.getTargets().add(victim);
        SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(named(wyrm, "TrigCopy", p), originalSpell, p);
        assertTrue(copy.isKicked());
        assertSame(copy.getTargetCard(), victim);
        assertEquals(copy.getHostCard().getKickerMagnitude(), 1);
        assertSame(originalSpell.getTargetCard(), victim);
    }

    @Test
    public void borneWitnessEscapeInheritsOnlyItsTaggedDonor() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card godrick = load(GODRICK, p, ZoneType.Graveyard);
        back(godrick);
        addCard("Underworld Breach", p);
        Card donor = addCardToZone("Balefire Dragon", p, ZoneType.Graveyard);
        Card extra = addCardToZone("Prodigal Pyromancer", p, ZoneType.Graveyard);
        Card filler1 = addCardToZone("Forest", p, ZoneType.Graveyard);
        Card filler2 = addCardToZone("Forest", p, ZoneType.Graveyard);
        prepare(game);
        SpellAbility normal = godrick.getFirstSpellAbility();
        normal.setActivatingPlayer(p);
        SpellAbility escape = forge.game.GameActionUtil.getAlternativeCosts(normal, p, true).stream()
                .filter(SpellAbility::isEscape).findFirst().orElseThrow();
        List<CostExile> costs = escape.getPayCosts().getCostParts().stream().filter(CostExile.class::isInstance)
                .map(CostExile.class::cast).collect(Collectors.toList());
        assertEquals(costs.size(), 2);
        // Put the card on stack as normal casting does before any costs are paid.
        Card stacked = game.getAction().moveToStack(godrick, escape);
        escape.setHostCard(stacked);
        for (CostExile cost : costs) {
            cost.payAsDecided(p, cost.getAbilityAmount(escape) == 1 ? PaymentDecision.card(donor)
                    : PaymentDecision.card(List.of(extra, filler1, filler2)), escape, false);
        }
        assertEquals(escape.getPaidList("ExiledCards", true).size(), 4,
                "Standard payment lists must still contain both payments");
        assertEquals(escape.getPaidList("ExiledCost_GodrickDonor", true).size(), 1);
        AbilityUtils.resolve(escape);
        Card permanent = escape.getHostCard();
        prepare(game);
        assertEquals(permanent.getTriggers().size(), 1, "Inherit Balefire Dragon's combat-damage trigger");
        assertFalse(permanent.getSpellAbilities().stream().anyMatch(sa -> sa.getApi() == ApiType.DealDamage),
                "The escape payment's Pyromancer must not be inherited");
    }

    @Test
    public void gowryTriggersForPositiveLifePaymentButNotLossOrZero() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        load("Gowry_Sage_of_Rot.txt", p, ZoneType.Battlefield);
        Card source = addCard("Forest", opponent);
        SpellAbility cause = AbilityFactory.getAbility("DB$ GainLife | LifeAmount$ 0", source);
        cause.setActivatingPlayer(opponent);
        prepare(game);
        assertTrue(opponent.payLife(0, cause, false));
        opponent.loseLife(2, false, false, cause);
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertTrue(game.getStack().isEmpty());
        assertTrue(opponent.payLife(2, cause, false));
        game.getTriggerHandler().runWaitingTriggers();
        game.getStack().addAllTriggeredAbilitiesToStack();
        assertEquals(game.getStack().size(), 1, "One payment of two life must trigger once");
    }

    @Test
    public void goldenParryGrantsIndestructibleOnlyUntilEndOfTurn() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card creature = addCard("Runeclaw Bear", p);
        Card card = load(PARRY, p, ZoneType.Stack);
        SpellAbility spell = card.getFirstSpellAbility();
        spell.setActivatingPlayer(p);
        assertFalse(spell.canTarget(addCard("Runeclaw Bear", game.getPlayers().get(0))));
        spell.getTargets().add(creature);
        AbilityUtils.resolve(spell);
        assertTrue(creature.hasKeyword("Indestructible"));
        game.getEndOfTurn().executeUntil();
        assertFalse(creature.hasKeyword("Indestructible"));
    }

    @Test
    public void magmaGrantsLoyaltyAbilitiesOnlyToEnchantedPlaneswalker() throws IOException {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card aura = load(WYRM, p, ZoneType.Battlefield);
        back(aura);
        Card creature = addCard("Runeclaw Bear", p), enemy = addCard("Shivan Dragon", opponent);
        aura.attachToEntity(creature, null);
        prepare(game);
        assertTrue(creature.hasKeyword("Haste"));
        assertTrue(creature.getType().hasSubtype("Dragon"));
        assertTrue(creature.getSpellAbilities().stream().noneMatch(SpellAbility::isPwAbility));
        Card walker = addCard("Jace Beleren", p);
        walker.setCounters(CounterEnumType.LOYALTY, 5);
        aura.attachToEntity(walker, null);
        prepare(game);
        SpellAbility plus = walker.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.DealDamage)
                .findFirst().orElseThrow();
        plus.setActivatingPlayer(p);
        assertTrue(plus.isPwAbility());
        plus.getTargets().add(enemy);
        AbilityUtils.resolve(plus);
        assertEquals(enemy.getDamage(), 1);
        SpellAbility minus = walker.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.DamageAll)
                .findFirst().orElseThrow();
        minus.setActivatingPlayer(p);
        AbilityUtils.resolve(minus);
        assertEquals(enemy.getDamage(), 3);
        assertEquals(creature.getDamage(), 0);
    }
}
