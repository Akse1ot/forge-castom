package forge.game.card;

import forge.ai.AITest;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardRulesPredicates;
import forge.card.DeckHints;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.keyword.Keyword;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.testng.Assert.*;

/** Loads the actual DWAP scripts so tests do not accidentally select an upstream namesake. */
public class DWAPCardScriptTest extends AITest {
    private Path scripts() {
        return Path.of(ForgeConstants.CARD_DATA_DIR, "custom cards", "DWAP");
    }

    private Card load(Path path, Player player, ZoneType zone) throws IOException {
        // CardStorageReader removes blank lines before handing scripts to CardRules.Reader.
        CardRules rules = CardRules.fromScript(Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank()).collect(Collectors.toList()));
        Card card = CardFactory.getCard(new PaperCard(rules, "DWAP", CardRarity.Rare), player, player.getGame());
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        if (zone == ZoneType.Stack) {
            player.getGame().getStackZone().add(card);
        } else {
            player.getZone(zone).add(card);
        }
        return card;
    }

    private Card load(String filename, Player player, ZoneType zone) throws IOException {
        try (Stream<Path> paths = Files.walk(scripts())) {
            Path path = paths.filter(p -> p.getFileName().toString().equals(filename)).findFirst().orElseThrow();
            return load(path, player, zone);
        }
    }

    private SpellAbility namedAbility(Card card, String name, Player player) {
        SpellAbility ability = AbilityFactory.getAbility(card.getSVar(name), card);
        ability.setActivatingPlayer(player);
        return ability;
    }

    @Test
    public void allScriptsAndBothFacesInstantiate() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(scripts())) {
            paths = walk.filter(p -> p.toString().endsWith(".txt")).sorted().collect(Collectors.toList());
        }
        assertEquals(paths.size(), 252);
        SoftAssert checks = new SoftAssert();
        for (Path path : paths) {
            try {
                Card card = load(path, player, ZoneType.Hand);
                for (var stateName : card.getStates()) {
                    var state = card.getState(stateName);
                    for (var trigger : state.getTriggers()) {
                        trigger.ensureAbility();
                    }
                    for (var replacement : state.getReplacementEffects()) {
                        replacement.ensureAbility();
                    }
                }
            } catch (RuntimeException | AssertionError error) {
                checks.fail(path.getFileName() + ": " + error, error);
            }
        }
        checks.assertAll();
    }

    @Test
    public void sourceMetadataAndConfirmedPowerToughnessArePreserved() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card aptitude = load("Van_Helsas_Aptitude.txt", player, ZoneType.Hand);
        assertTrue(aptitude.isEnchantment());
        assertFalse(aptitude.isSorcery());
        assertEquals(aptitude.getManaCost().getCMC(), 6);
        assertTrue(load("Fjord_Raptor.txt", player, ZoneType.Hand).isCreature());
        assertFalse(load("Fjord_Raptor.txt", player, ZoneType.Hand).isArtifact());
        assertEquals(load("Fjord_Thrasher.txt", player, ZoneType.Hand).getManaCost().getCMC(), 6);
        assertEquals(load("Titanic_Odds.txt", player, ZoneType.Hand).getManaCost().getCMC(), 4);
        Card fae = load("Malleable_Fae.txt", player, ZoneType.Hand);
        assertEquals(fae.getNetPower(), 2);
        assertEquals(fae.getNetToughness(), 1);
        assertEquals(load("Foraging_Raccoon.txt", player, ZoneType.Hand).getNetPower(), 1);
        assertEquals(load("Yearling_Warrior_Inner_Anger.txt", player, ZoneType.Hand).getNetToughness(), 3);
        assertEquals(load("Spinner_of_Souls_i.txt", player, ZoneType.Hand).getName(), "Spinner of Souls I");
    }

    @DataProvider
    public Object[][] artifactCounts() {
        return new Object[][] {{0}, {6}};
    }

    @Test(dataProvider = "artifactCounts")
    public void demonicIonizationGrantsDynamicArtifactBonusAndConditionalDraw(int artifacts) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card spell = load("Ferros_Encased_Thunder_Demonic_Ionization.txt", player, ZoneType.Hand);
        spell.setState(spell.getAlternateState().getStateName(), true);
        Card creature = addCard("Runeclaw Bear", player);
        addCards("Sol Ring", artifacts, player);
        for (int i = 0; i < 8; i++) {
            addCardToZone("Island", player, ZoneType.Library);
        }
        SpellAbility ability = spell.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.Animate).findFirst().orElseThrow();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(creature);
        AbilityUtils.resolve(ability);
        game.getAction().checkStateEffects(true);
        assertTrue(creature.isArtifact());
        assertEquals(creature.getNetPower(), artifacts + 1);
        assertEquals(creature.getNetToughness(), artifacts + 1);
        assertEquals(player.getCardsIn(ZoneType.Hand).size(), artifacts == 6 ? 3 : 1);
        addCard("Sol Ring", player);
        game.getAction().checkStateEffects(true);
        assertEquals(creature.getNetPower(), artifacts + 2, "Granted bonus must continue to count artifacts");
    }

    @DataProvider
    public Object[][] goatCounts() {
        return new Object[][] {{0}, {3}};
    }

    @Test(dataProvider = "goatCounts")
    public void growingStarlightUsesOnePlusExistingGoats(int goats) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card starlight = load("Growing_Starlight.txt", player, ZoneType.Battlefield);
        for (int i = 0; i < goats; i++) {
            addToken("w_0_1_goat", player);
        }
        AbilityUtils.resolve(namedAbility(starlight, "TrigToken", player));
        Card token = player.getCreaturesInPlay().stream()
                .filter(c -> c.getType().hasSubtype("Spirit")).findFirst().orElseThrow();
        assertEquals(token.getNetPower(), goats + 1);
        assertEquals(token.getNetToughness(), goats + 1);
    }

    @Test
    public void takeUpBladesForgesThenAttachesEquipment() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card creature = addCard("Runeclaw Bear", player);
        Card spell = load("Take_up_Blades.txt", player, ZoneType.Hand);
        SpellAbility ability = spell.getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        ability.getSubAbility().getTargets().add(creature);
        AbilityUtils.resolve(ability);
        game.getAction().checkStateEffects(true);
        assertEquals(player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isEquipment).count(), 1L);
        assertTrue(creature.isEquipped());
        assertTrue(creature.hasKeyword(Keyword.FIRST_STRIKE));
    }

    @Test
    public void winterTombReturnsCreatureUnderYourControlAsAHorror() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card bear = addCard("Runeclaw Bear", opponent);
        Card tomb = load("Winters_Tomb.txt", player, ZoneType.Battlefield);
        AbilityUtils.resolve(namedAbility(tomb, "TrigExile", player));
        assertTrue(game.findById(bear.getId()).isInZone(ZoneType.Exile));
        AbilityUtils.resolve(namedAbility(tomb, "TrigReturn", player));
        game.getAction().checkStateEffects(true);
        Card returned = game.findById(bear.getId());
        assertTrue(returned.isInPlay());
        assertSame(returned.getController(), player);
        assertTrue(returned.getType().hasSubtype("Horror"));
        assertFalse(returned.getType().hasSubtype("Bear"));
    }

    @Test
    public void frostedGrottoGrantsTrampleToItsTarget() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card bear = addCard("Runeclaw Bear", player);
        Card grotto = load("Frosted_Grotto.txt", player, ZoneType.Battlefield);
        SpellAbility ability = grotto.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.PutCounter).findFirst().orElseThrow();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(bear);
        AbilityUtils.resolve(ability);
        assertEquals(bear.getCounters(CounterEnumType.P1P1), 3);
        assertTrue(bear.hasKeyword(Keyword.TRAMPLE));
        assertFalse(grotto.hasKeyword(Keyword.TRAMPLE));
    }

    @DataProvider
    public Object[][] bindingStillPresent() {
        return new Object[][] {{true}, {false}};
    }

    @Test(dataProvider = "bindingStillPresent")
    public void cageTheSoulUsesTheChosenEnchantmentsLifetime(boolean present) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card binding = addCard("Glorious Anthem", player);
        Card bear = addCard("Runeclaw Bear", game.getPlayers().get(0));
        Card spell = load("Cage_the_Soul.txt", player, ZoneType.Stack);
        SpellAbility ability = spell.getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(binding);
        ability.getSubAbility().getTargets().add(bear);
        if (!present) {
            game.getAction().moveToGraveyard(binding, null);
        }
        AbilityUtils.resolve(ability);
        game.getAction().moveToGraveyard(spell, null);
        assertEquals(game.findById(bear.getId()).isInZone(ZoneType.Exile), present);
        if (present) {
            game.getAction().moveToGraveyard(binding, null);
        }
        assertTrue(game.findById(bear.getId()).isInPlay());
    }

    @Test
    public void sivfridaUsesSecondAndFourthResolutionMilestones() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card sivfrida = load("Sivfrida_Life_Warden_Sivfridas_Curiosity.txt", player, ZoneType.Battlefield);
        SpellAbility gain = namedAbility(sivfrida, "TrigGainLife", player);
        SpellAbility draw = gain.getSubAbility();
        SpellAbility invoke = draw.getSubAbility();
        for (int i = 1; i <= 4; i++) {
            sivfrida.addAbilityResolved(gain);
            assertEquals(draw.metConditions(), i == 2, "Draw milestone: " + i);
            assertEquals(invoke.metConditions(), i == 4, "Invoke milestone: " + i);
        }
    }

    @Test
    public void epitomeBonusesAndAttackRestrictionFollowEnlightenment() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card reclaimer = load("Wildform_Reclaimer.txt", player, ZoneType.Battlefield);
        Card ascetic = load("Steadfast_Ascetic.txt", player, ZoneType.Battlefield);
        Card sentinel = load("Grove_Sentinel.txt", player, ZoneType.Battlefield);
        Card brewer = load("Battle_Brewer.txt", player, ZoneType.Battlefield);
        Card bloom = load("Dangerous_Bloom.txt", player, ZoneType.Battlefield);
        var restriction = bloom.getStaticAbilities().stream()
                .filter(st -> st.checkMode(StaticAbilityMode.CantAttack)).findFirst().orElseThrow();
        game.getAction().checkStateEffects(true);
        assertFalse(player.isEnlightened());
        assertEquals(reclaimer.getNetPower(), 1);
        assertEquals(ascetic.getNetPower(), 4);
        assertEquals(sentinel.getNetPower(), 2);
        assertFalse(brewer.hasKeyword(Keyword.INDESTRUCTIBLE));
        assertTrue(restriction.checkConditions());

        player.setEnlightened(true, "DWAP");
        game.getAction().checkStateEffects(true);
        assertEquals(reclaimer.getNetPower(), 5);
        assertEquals(ascetic.getNetPower(), 6);
        assertEquals(sentinel.getNetPower(), 6);
        assertTrue(brewer.hasKeyword(Keyword.INDESTRUCTIBLE));
        assertFalse(restriction.checkConditions());
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getAction().checkStateEffects(true);
        assertFalse(brewer.hasKeyword(Keyword.INDESTRUCTIBLE), "Brewer's bonus is limited to its controller's turn");
    }

    @Test
    public void awokenSaplingRequiresEnlightenmentToActivate() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card sapling = load("Awoken_Sapling.txt", player, ZoneType.Battlefield);
        Card target = addCardToZone("Runeclaw Bear", player, ZoneType.Graveyard);
        SpellAbility ability = sapling.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.ChangeZone).findFirst().orElseThrow();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(target);
        assertFalse(ability.canPlay());
        player.setEnlightened(true, "DWAP");
        assertTrue(ability.canPlay());
    }

    @Test
    public void skadisMusterRecognizesThePlayerWhoDiscarded() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card muster = load("Skadis_Muster.txt", player, ZoneType.Hand);
        SpellAbility draw = namedAbility(muster, "DBDraw", player);
        assertFalse(draw.metConditions());
        muster.addRemembered(player);
        assertTrue(draw.metConditions(), "Discarding must allow the draw even without an enchanted Shaman");
    }

    @Test
    public void graveReckoningAffectsEveryOpponentWithoutTargeting() throws IOException {
        Game game = initAndCreateThreePlayerGame();
        Player player = game.getPlayers().get(1);
        Card reckoning = load("Grave_Reckoning.txt", player, ZoneType.Battlefield);
        SpellAbility ability = namedAbility(reckoning, "TrigGenericChoice", player);
        assertFalse(ability.usesTargeting());
        int firstLife = game.getPlayers().get(0).getLife();
        int secondLife = game.getPlayers().get(2).getLife();
        AbilityUtils.resolve(ability);
        assertEquals(game.getPlayers().get(0).getLife(), firstLife - 3);
        assertEquals(game.getPlayers().get(2).getLife(), secondLife - 3);
    }

    @Test
    public void gunvaldDealsTwoDamageToOpponentsAndTheirPlaneswalkers() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card gunvald = load("Gunvald_Unsparing_Raider.txt", player, ZoneType.Battlefield);
        Card jace = addCard("Jace Beleren", opponent);
        jace.setCounters(CounterEnumType.LOYALTY, 5);
        int life = opponent.getLife();
        AbilityUtils.resolve(namedAbility(gunvald, "TrigDamageAll", player));
        assertEquals(opponent.getLife(), life - 2);
        assertEquals(jace.getCounters(CounterEnumType.LOYALTY), 3);
    }

    @Test
    public void snowslideTargetsOnlyEquipmentAttachedToItsCreatureTarget() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card creature = addCard("Runeclaw Bear", player);
        Card attached = addCard("Bonesplitter", player);
        Card unrelated = addCard("Bonesplitter", player);
        attached.attachToEntity(creature, null);
        Card spell = load("Snowslide.txt", player, ZoneType.Hand);
        SpellAbility ability = spell.getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(creature);
        SpellAbility destroy = ability.getSubAbility();
        assertTrue(destroy.usesTargeting());
        assertTrue(destroy.canTarget(attached));
        assertFalse(destroy.canTarget(unrelated));
    }

    @DataProvider
    public Object[][] optionalEquipment() {
        return new Object[][] {{false, false}, {true, false}, {true, true}};
    }

    @Test(dataProvider = "optionalEquipment")
    public void snowslideResolvesWithOptionalOrDetachedEquipment(boolean choose, boolean detach) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card creature = addCard("Giant Spider", player);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, player, false);
        game.getPhaseHandler().setCombat(new forge.game.combat.Combat(player));
        game.getCombat().addAttacker(creature, game.getPlayers().get(0));
        Card equipment = addCard("Bonesplitter", player);
        equipment.attachToEntity(creature, null);
        Card spell = load("Snowslide.txt", player, ZoneType.Stack);
        SpellAbility ability = spell.getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(creature);
        SpellAbility destroy = ability.getSubAbility();
        assertEquals(destroy.getMinTargets(), 0);
        if (choose) {
            destroy.getTargets().add(equipment);
        }
        assertTrue(destroy.isTargetNumberValid());
        if (detach) {
            equipment.attachToEntity(addCard("Runeclaw Bear", player), null);
            assertFalse(destroy.canTarget(equipment));
        }
        game.getStack().add(ability);
        game.getStack().resolveStack();
        assertEquals(creature.getDamage(), 3);
        assertEquals(game.findById(equipment.getId()).isInZone(ZoneType.Graveyard), choose && !detach);
    }

    @DataProvider
    public Object[][] rebukeTargets() {
        return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
    }

    @Test(dataProvider = "rebukeTargets")
    public void primalRebukeDamagesCreaturesAndPlaneswalkersWithOrWithoutShaman(boolean shaman,
            boolean planeswalker) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card source = shaman ? load("Skadi_Fierce_Protectress.txt", player, ZoneType.Battlefield)
                : addCard("Runeclaw Bear", player);
        int power = source.getNetPower();
        Card target = addCard(planeswalker ? "Jace Beleren" : "Runeclaw Bear", opponent);
        if (planeswalker) {
            target.setCounters(CounterEnumType.LOYALTY, 10);
        }
        SpellAbility ability = load("Primal_Rebuke.txt", player, ZoneType.Stack).getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(source);
        SpellAbility damage = ability.getSubAbility();
        assertEquals(damage.getMinTargets(), 0);
        assertTrue(damage.canTarget(target));
        assertFalse(damage.canTarget(source));
        assertFalse(damage.canTarget(opponent));
        assertEquals(damage.getParam("ValidTgtsDesc"), "creature or planeswalker you don't control");
        damage.getTargets().add(target);
        AbilityUtils.resolve(ability);
        assertEquals(source.getCounters(CounterEnumType.P1P1), shaman ? 1 : 0);
        int expected = power + (shaman ? 1 : 0);
        assertEquals(planeswalker ? 10 - target.getCounters(CounterEnumType.LOYALTY) : target.getDamage(), expected);
        assertEquals(source.getDamage(), 0, "Rebuke deals one-way damage; it does not fight");
    }

    @Test
    public void primalRebukeMayOmitDamageTarget() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card shaman = load("Skadi_Fierce_Protectress.txt", player, ZoneType.Battlefield);
        SpellAbility ability = load("Primal_Rebuke.txt", player, ZoneType.Stack).getFirstSpellAbility();
        ability.setActivatingPlayer(player);
        ability.getTargets().add(shaman);
        assertTrue(ability.getSubAbility().isTargetNumberValid());
        AbilityUtils.resolve(ability);
        assertEquals(shaman.getCounters(CounterEnumType.P1P1), 1);
        assertEquals(shaman.getDamage(), 0);
    }

    @DataProvider
    public Object[][] fightTarget() {
        return new Object[][] {{false}, {true}};
    }

    @Test(dataProvider = "fightTarget")
    public void skadiFightUsesOneHybridManaAndTheEnteringShaman(boolean chooseTarget) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card skadi = load("Skadi_Fierce_Protectress.txt", player, ZoneType.Battlefield);
        Card entering = load("Learned_Ascetic.txt", player, ZoneType.Battlefield);
        Card enemy = addCard("Runeclaw Bear", game.getPlayers().get(0));
        SpellAbility fight = namedAbility(skadi, "TrigFight", player);
        fight.setTriggeringObject(AbilityKey.Card, entering);
        assertEquals(fight.getPayCosts().getTotalMana().getCMC(), 1);
        assertEquals(fight.getPayCosts().getTotalMana().iterator().next(), ManaCostShard.RG);
        assertEquals(fight.getMinTargets(), 0);
        assertFalse(fight.canTarget(entering));
        assertTrue(fight.canTarget(enemy));
        if (chooseTarget) {
            fight.getTargets().add(enemy);
        }
        assertTrue(fight.isTargetNumberValid());
        // Resolve the effect after the trigger's optional payment, without simulating payment UI.
        AbilityUtils.resolve(fight);
        assertEquals(enemy.getDamage(), chooseTarget ? entering.getNetPower() : 0);
        assertEquals(entering.getDamage(), chooseTarget ? enemy.getNetPower() : 0);
        assertEquals(skadi.getDamage(), 0);
    }

    @Test
    public void starHerdInvokeLimitPersistsAcrossTurnsButResetsForNewObject() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card apprentice = load("Star_Herd_Apprentice_Star_Herd_Invocation.txt", player, ZoneType.Battlefield);
        SpellAbility invoke = apprentice.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.InvokeSkill).findFirst().orElseThrow();
        invoke.setActivatingPlayer(player);
        assertTrue(invoke.canPlay());
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, player);
        assertFalse(invoke.canPlay(), "Invoke is restricted to sorcery timing");
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        apprentice.addAbilityActivated(invoke);
        assertFalse(invoke.canPlay());
        apprentice.resetActivationsPerTurn();
        assertEquals(invoke.getActivationsThisTurn(), 0);
        assertFalse(invoke.canPlay(), "Once per game object, not once each turn");
        Card grave = game.getAction().moveToGraveyard(apprentice, null);
        Card returned = game.getAction().moveToPlay(grave, player, null, AbilityKey.newMap());
        SpellAbility newInvoke = returned.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.InvokeSkill).findFirst().orElseThrow();
        newInvoke.setActivatingPlayer(player);
        assertTrue(newInvoke.canPlay(), "A returned card is a new object");
    }

    @Test
    public void deckHintsDistinguishAlternativesIntersectionAndProviders() throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card forge = load("Eager_Geologist.txt", player, ZoneType.Hand);
        assertTrue(CardRulesPredicates.deckHasExactly(DeckHints.Type.TYPE,
                new String[] {"Equipment", "Artifact"}).test(forge.getRules()));
        Card charge = load("Avalanche_Charge.txt", player, ZoneType.Hand);
        assertTrue(CardRulesPredicates.deckHasExactly(DeckHints.Type.ABILITY,
                new String[] {"Token", "LifeGain"}).test(charge.getRules()));
        List<PaperCard> pool = List.of((PaperCard) charge.getPaperCard(), (PaperCard) forge.getPaperCard());
        List<PaperCard> alternatives = StreamSupport.stream(new DeckHints("Ability$Token|LifeGain")
                .filter(pool).spliterator(), false).collect(Collectors.toList());
        assertTrue(alternatives.contains(charge.getPaperCard()));
        assertTrue(alternatives.contains(forge.getPaperCard()));
        List<PaperCard> intersection = StreamSupport.stream(new DeckHints("Ability$Token & Ability$LifeGain")
                .filter(pool).spliterator(), false).collect(Collectors.toList());
        assertTrue(intersection.contains(charge.getPaperCard()));
        assertFalse(intersection.contains(forge.getPaperCard()));
        Card gunvald = load("Gunvald_Unsparing_Raider.txt", player, ZoneType.Hand);
        assertFalse(CardRulesPredicates.deckHas(DeckHints.Type.ABILITY, "Sacrifice").test(gunvald.getRules()));
        assertTrue(gunvald.getRules().getAiHints().getDeckHints().contains(DeckHints.Type.ABILITY, "Sacrifice"));
        Card rebuke = load("Primal_Rebuke.txt", player, ZoneType.Hand);
        assertNull(rebuke.getRules().getAiHints().getDeckNeeds());
    }

    @DataProvider
    public Object[][] strixSacrifices() {
        return new Object[][] {{"Runeclaw Bear"}, {"Sol Ring"}};
    }

    @Test(dataProvider = "strixSacrifices")
    public void auroraStrixRequiresExactlyOneArtifactOrCreatureSacrifice(String permanent) throws IOException {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card strix = load("Aurora_Strix.txt", player, ZoneType.Hand);
        strix.getFirstSpellAbility().setActivatingPlayer(player);
        assertTrue(GameActionUtil.getAdditionalCostSpell(strix.getFirstSpellAbility()).isEmpty(),
                "No cast option may omit the mandatory sacrifice");
        addCard(permanent, player);
        List<SpellAbility> variants = GameActionUtil.getAdditionalCostSpell(strix.getFirstSpellAbility());
        assertEquals(variants.size(), 1);
        List<CostSacrifice> sacrifices = variants.get(0).getPayCosts().getCostParts().stream()
                .filter(CostSacrifice.class::isInstance).map(CostSacrifice.class::cast).collect(Collectors.toList());
        assertEquals(sacrifices.size(), 1);
        assertEquals(sacrifices.get(0).getAmount(), "1");
        assertEquals(sacrifices.get(0).getType(), "Creature;Artifact");
        assertTrue(CardRulesPredicates.deckHas(DeckHints.Type.ABILITY, "Sacrifice").test(strix.getRules()));
        assertFalse(CardRulesPredicates.deckHas(DeckHints.Type.ABILITY, "Discard").test(strix.getRules()));
    }

    @Test
    public void tungskulSkillOffersNonlandTypesAndAffectsOnlyOpponents() throws IOException {
        Game game = initAndCreateThreePlayerGame();
        Player player = game.getPlayers().get(1);
        Card tungskul = load("Tungskul_Gelid_Moon_Tungskuls_Retribution.txt", player, ZoneType.Stack);
        assertFalse(CardRulesPredicates.deckHas(DeckHints.Type.ABILITY, "Sacrifice").test(tungskul.getRules()));
        assertTrue(tungskul.getRules().getAiHints().getDeckHints().contains(DeckHints.Type.ABILITY, "Sacrifice"));
        tungskul.setState(tungskul.getAlternateState().getStateName(), true);
        SpellAbility skill = tungskul.getFirstSpellAbility();
        assertEquals(skill.getParam("ValidTypes"), "Artifact,Battle,Creature,Enchantment,Planeswalker");
        Card own = addCard("Runeclaw Bear", player);
        Card first = addCard("Runeclaw Bear", game.getPlayers().get(0));
        Card second = addCard("Runeclaw Bear", game.getPlayers().get(2));
        // Fix the player's legal choice, then exercise the shared opponent iteration and payment.
        tungskul.setChosenType("Creature");
        skill.setActivatingPlayer(player);
        AbilityUtils.resolve(skill.getSubAbility());
        assertTrue(own.isInPlay());
        assertTrue(game.findById(first.getId()).isInZone(ZoneType.Graveyard));
        assertTrue(game.findById(second.getId()).isInZone(ZoneType.Graveyard));
    }
}
