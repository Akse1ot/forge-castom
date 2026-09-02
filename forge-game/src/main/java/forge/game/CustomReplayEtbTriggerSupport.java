package forge.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.staticability.StaticAbilityPanharmonicon;
import forge.game.trigger.CustomReplayEtbWrappedAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerChangesZone;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.Expressions;

public final class CustomReplayEtbTriggerSupport {
    private enum ReplayAmountKind {
        CURRENT,
        ENTRY_HISTORY,
        EVENT_DEPENDENT
    }

    private CustomReplayEtbTriggerSupport() {
    }

    public static Map<AbilityKey, Object> getReplayRunParams(final Card card) {
        final Card current = getCurrentPermanent(card);
        if (current == null) {
            return null;
        }

        final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.Card, current);
        runParams.put(AbilityKey.CardLKI, CardCopyService.getLKICopy(current));
        return runParams;
    }

    public static List<TriggerChangesZone> getReplayableEtbTriggers(final Card card,
                                                                    final Map<AbilityKey, Object> runParams) {
        final List<TriggerChangesZone> result = new ArrayList<>();

        if (runParams == null) {
            return result;
        }

        final Object object = runParams.get(AbilityKey.Card);
        if (!(object instanceof Card)) {
            return result;
        }

        final Card current = (Card) object;
        for (final Trigger trigger : current.getTriggers()) {
            if (!(trigger instanceof TriggerChangesZone)) {
                continue;
            }

            final TriggerChangesZone etb = (TriggerChangesZone) trigger;
            if (canReplayTrigger(current.getGame(), etb, runParams)) {
                result.add(etb);
            }
        }

        return result;
    }

    public static int replay(final Card card) {
        final Map<AbilityKey, Object> runParams = getReplayRunParams(card);
        if (runParams == null) {
            return 0;
        }

        final Card current = (Card) runParams.get(AbilityKey.Card);
        final Game game = current.getGame();
        int count = 0;

        for (final TriggerChangesZone trigger : getReplayableEtbTriggers(current, runParams)) {
            final int triggerAmount =
                    1 + StaticAbilityPanharmonicon.handlePanharmonicon(game, trigger, runParams);

            for (int i = 0; i < triggerAmount; i++) {
                game.getTriggerHandler().runSingleTriggerWithWrapper(
                        trigger,
                        runParams,
                        (ability, decider) ->
                                new CustomReplayEtbWrappedAbility(trigger, ability, decider));
                count++;
            }
        }

        return count;
    }

    public static boolean canReplayTrigger(final Game game,
                                           final TriggerChangesZone trigger,
                                           final Map<AbilityKey, Object> runParams) {
        if (game == null || trigger == null
                || trigger.getMode() != TriggerType.ChangesZone
                || trigger.isStatic()
                || game.getTriggerHandler().isTriggerSuppressed(TriggerType.ChangesZone)
                || trigger.isSuppressed()
                || !trigger.phasesCheck(game)
                || !trigger.checkActivationLimit()) {
            return false;
        }

        final Object object = runParams.get(AbilityKey.Card);
        if (!(object instanceof Card)) {
            return false;
        }

        final Card current = getCurrentPermanent((Card) object);
        if (current == null
                || !trigger.getHostCard().equalsWithGameTimestamp(current)) {
            return false;
        }

        if (trigger.getSpawningAbility() == null
                && !trigger.zonesCheck(game.getZoneOf(current))) {
            return false;
        }

        if (!isBattlefieldDestination(trigger)) {
            return false;
        }

        if (!matchesReplayValidCard(trigger, current)) {
            return false;
        }

        if (!checkOnTriggeredCard(trigger, current)) {
            return false;
        }

        if (!meetsReplayRequirements(game, trigger)) {
            return false;
        }

        // Keep generic trigger-disabling effects, but do not fabricate
        // Origin/Destination/Cause for ETB-specific effects such as Torpor Orb.
        return !StaticAbilityDisableTriggers.disabled(game, trigger, runParams);
    }

    public static boolean meetsReplayRequirements(final Game game, final Trigger trigger) {
        if (game == null || trigger == null) {
            return false;
        }

        final Card host = trigger.getHostCard();
        if (host == null || host.getController() == null
                || (game.getAge() != GameStage.Play
                && game.getAge() != GameStage.RestartedByKarn)) {
            return false;
        }

        if (!meetsReplayTriggeredObjectRequirements(trigger)) {
            return false;
        }

        final Map<String, String> params = getReplayRequirementParams(trigger);
        if (params == null) {
            return false;
        }

        if (!meetsReplayGlobalRequirements(game, params)) {
            return false;
        }

        if (!trigger.meetsCommonRequirements(params)) {
            return false;
        }

        return trigger.checkResolvedLimit(host.getController());
    }

    public static SpellAbility buildPreviewAbility(final TriggerChangesZone trigger,
                                                   Player controller, final Map<AbilityKey, Object> runParams) {
        if (trigger == null || controller == null || runParams == null) {
            return null;
        }

        final Card host = trigger.getHostCard();
        SpellAbility sa = trigger.getOverridingAbility();

        if (sa == null) {
            if (!trigger.hasParam("Execute")) {
                sa = new SpellAbility.EmptySa(host);
            } else {
                final String name = trigger.getParam("Execute");
                if (!trigger.hasSVar(name)) {
                    return null;
                }
                sa = AbilityFactory.getAbility(host, name, trigger);
            }

            sa.setActivatingPlayer(controller);

            if (trigger.isIntrinsic()) {
                sa.setIntrinsic(true);
                sa.changeText();
            }
        } else {
            if (trigger.getSpawningAbility() != null) {
                controller = trigger.getSpawningAbility().getActivatingPlayer();
            }

            sa = sa.copy(host, controller, false, true);

            if (sa instanceof AbilitySub) {
                ((AbilitySub) sa).setParent(null);
            }
        }

        sa.setTrigger(trigger);
        trigger.setTriggeringObjects(sa, runParams);

        if (trigger.hasParam("TriggerController")) {
            final List<Player> players = AbilityUtils.getDefinedPlayers(
                    host, trigger.getParam("TriggerController"), sa);
            if (players.isEmpty()) {
                return null;
            }
            sa.setActivatingPlayer(players.get(0));
        }

        return sa;
    }

    private static Card getCurrentPermanent(final Card card) {
        if (card == null || card.getGame() == null) {
            return null;
        }

        final Card current = card.getGame().getCardState(card);
        if (current == null
                || !current.equalsWithGameTimestamp(card)
                || !current.isInPlay()
                || current.isPhasedOut()) {
            return null;
        }

        return current;
    }

    private static boolean isBattlefieldDestination(final Trigger trigger) {
        if (!trigger.hasParam("Destination")) {
            return false;
        }

        for (final String destination : trigger.getParam("Destination").split(",")) {
            if (ZoneType.Battlefield.toString().equals(destination.trim())) {
                return true;
            }
        }

        return false;
    }

    private static boolean matchesReplayValidCard(final Trigger trigger, final Card card) {
        if (!trigger.hasParam("ValidCard")) {
            return false;
        }

        return trigger.matchesValid(card, getReplayValidCards(trigger));
    }

    private static String[] getReplayValidCards(final Trigger trigger) {
        final String[] valids = trigger.getParam("ValidCard").split(",");

        for (int i = 0; i < valids.length; i++) {
            valids[i] = filterReplayValid(valids[i]);
        }

        return valids;
    }

    private static String filterReplayValid(final String value) {
        final String valid = value.trim();
        final String[] parts = valid.split("\\.", 2);

        if (parts.length < 2) {
            return valid;
        }

        final List<String> properties = new ArrayList<>();
        for (final String propertyValue : parts[1].split("\\+")) {
            final String property = propertyValue.trim();
            if (!isReplayEntryHistoryProperty(property)) {
                properties.add(property);
            }
        }

        if (properties.isEmpty()) {
            return parts[0];
        }

        return parts[0] + "." + String.join("+", properties);
    }

    private static boolean isReplayEntryHistoryProperty(final String value) {
        String property = value;
        if (property.startsWith("!")) {
            property = property.substring(1);
        }

        final String lower = property.toLowerCase(Locale.ROOT);

        return lower.startsWith("wascast")
                || lower.startsWith("castsa")
                || lower.startsWith("kicked")
                || lower.startsWith("linkedcastsa")
                || lower.startsWith("bargained")
                || lower.startsWith("surged")
                || lower.startsWith("blitzed")
                || lower.startsWith("dashed")
                || lower.startsWith("escaped")
                || lower.startsWith("evoked")
                || lower.startsWith("promisedgift")
                || lower.startsWith("teamwork")
                || lower.startsWith("impended")
                || lower.startsWith("prowled")
                || lower.startsWith("spectacle")
                || lower.startsWith("sneaked")
                || lower.startsWith("taxed")
                || lower.startsWith("castkeyword")
                || lower.startsWith("nottributed");
    }

    private static boolean checkOnTriggeredCard(final Trigger trigger, final Card card) {
        if (!trigger.hasParam("CheckOnTriggeredCard")) {
            return true;
        }

        final String[] condition =
                trigger.getParam("CheckOnTriggeredCard").split(" ", 2);
        final String comparator = condition.length < 2 ? "GE1" : condition[1];

        if (comparator.length() < 2) {
            return false;
        }

        final ReplayAmountKind actualKind =
                classifyReplayAmount(trigger, condition[0]);
        final ReplayAmountKind referenceKind =
                classifyReplayAmount(trigger, comparator.substring(2));

        if (actualKind == ReplayAmountKind.EVENT_DEPENDENT
                || referenceKind == ReplayAmountKind.EVENT_DEPENDENT) {
            return false;
        }

        // This requirement described the original entry/cast. It is not
        // a condition of the synthetic replay itself.
        if (actualKind == ReplayAmountKind.ENTRY_HISTORY
                || referenceKind == ReplayAmountKind.ENTRY_HISTORY) {
            return true;
        }

        final int referenceValue = AbilityUtils.calculateAmount(
                trigger.getHostCard(), comparator.substring(2), trigger);
        final int actualValue =
                AbilityUtils.calculateAmount(card, condition[0], trigger);

        return Expressions.compare(
                actualValue, comparator.substring(0, 2), referenceValue);
    }

    private static Map<String, String> getReplayRequirementParams(final Trigger trigger) {
        final Map<String, String> params =
                new HashMap<>(trigger.getMapParams());

        // These explicitly describe the original casting of the object.
        params.remove("Adamant");
        params.remove("ManaSpent");
        params.remove("ManaNotSpent");

        if (!sanitizeCheckSVarRequirements(trigger, params)) {
            return null;
        }

        if (!sanitizeComparatorRequirement(
                trigger, params, "LifeTotal", "LifeAmount", "GE1")) {
            return null;
        }
        if (!sanitizeComparatorRequirement(
                trigger, params, "IsPresent", "PresentCompare", "GE1")) {
            return null;
        }
        if (!sanitizeComparatorRequirement(
                trigger, params, "IsPresent2", "PresentCompare2", "GE1")) {
            return null;
        }
        if (!sanitizeComparatorRequirement(
                trigger, params, "CheckDefinedPlayer",
                "DefinedPlayerCompare", "GE1")) {
            return null;
        }

        if (params.containsKey("IsPresent")) {
            params.put("IsPresent", filterReplayValid(params.get("IsPresent")));
        }
        if (params.containsKey("IsPresent2")) {
            params.put("IsPresent2", filterReplayValid(params.get("IsPresent2")));
        }

        if (isEventDependentDefinition(params.get("PresentDefined"))
                || isEventDependentDefinition(params.get("CheckDefinedPlayer"))) {
            return null;
        }

        return params;
    }

    private static boolean sanitizeCheckSVarRequirements(final Trigger trigger,
                                                         final Map<String, String> params) {
        if (!params.containsKey("CheckSVar")) {
            return true;
        }

        ReplayAmountKind kind =
                classifyReplayAmount(trigger, params.get("CheckSVar"));

        final String comparator = params.getOrDefault("SVarCompare", "GE1");
        if (comparator.length() < 2) {
            return false;
        }

        kind = combineReplayAmountKinds(
                kind, classifyReplayAmount(trigger, comparator.substring(2)));

        if (params.containsKey("CheckSecondSVar")) {
            kind = combineReplayAmountKinds(
                    kind,
                    classifyReplayAmount(
                            trigger, params.get("CheckSecondSVar")));

            final String comparator2 =
                    params.getOrDefault("SecondSVarCompare", "GE1");
            if (comparator2.length() < 2) {
                return false;
            }

            kind = combineReplayAmountKinds(
                    kind,
                    classifyReplayAmount(
                            trigger, comparator2.substring(2)));
        }

        if (kind == ReplayAmountKind.EVENT_DEPENDENT) {
            return false;
        }

        if (kind == ReplayAmountKind.ENTRY_HISTORY) {
            params.remove("CheckSVar");
            params.remove("SVarCompare");
            params.remove("CheckSecondSVar");
            params.remove("SecondSVarCompare");
        }

        return true;
    }

    private static boolean sanitizeComparatorRequirement(final Trigger trigger,
                                                         final Map<String, String> params, final String requirement,
                                                         final String comparatorParam, final String defaultComparator) {
        if (!params.containsKey(requirement)) {
            return true;
        }

        final String comparator =
                params.getOrDefault(comparatorParam, defaultComparator);
        if (comparator.length() < 2) {
            return false;
        }

        final ReplayAmountKind kind =
                classifyReplayAmount(trigger, comparator.substring(2));

        if (kind == ReplayAmountKind.EVENT_DEPENDENT) {
            return false;
        }

        if (kind == ReplayAmountKind.ENTRY_HISTORY) {
            params.remove(requirement);
            params.remove(comparatorParam);
        }

        return true;
    }

    private static ReplayAmountKind classifyReplayAmount(
            final Trigger trigger, final String value) {
        return classifyReplayAmount(trigger, value, new HashSet<>());
    }

    private static ReplayAmountKind classifyReplayAmount(
            final Trigger trigger, final String value,
            final Set<String> visited) {
        if (value == null || value.isEmpty()) {
            return ReplayAmountKind.CURRENT;
        }

        final String expression = value.trim();

        if (trigger.hasSVar(expression) && visited.add(expression)) {
            return classifyReplayAmount(
                    trigger, trigger.getSVar(expression), visited);
        }

        final String lower = expression.toLowerCase(Locale.ROOT);

        if (lower.startsWith("svar$")) {
            String svar = expression.substring(5);
            final int operator = svar.indexOf('/');
            if (operator >= 0) {
                svar = svar.substring(0, operator);
            }

            if (visited.add(svar) && trigger.hasSVar(svar)) {
                return classifyReplayAmount(
                        trigger, trigger.getSVar(svar), visited);
            }
        }

        if (isReplayEventDependentExpression(lower)) {
            return ReplayAmountKind.EVENT_DEPENDENT;
        }

        if (isReplayEntryHistoryExpression(lower)) {
            return ReplayAmountKind.ENTRY_HISTORY;
        }

        return ReplayAmountKind.CURRENT;
    }

    private static ReplayAmountKind combineReplayAmountKinds(
            final ReplayAmountKind first, final ReplayAmountKind second) {
        if (first == ReplayAmountKind.EVENT_DEPENDENT
                || second == ReplayAmountKind.EVENT_DEPENDENT) {
            return ReplayAmountKind.EVENT_DEPENDENT;
        }

        if (first == ReplayAmountKind.ENTRY_HISTORY
                || second == ReplayAmountKind.ENTRY_HISTORY) {
            return ReplayAmountKind.ENTRY_HISTORY;
        }

        return ReplayAmountKind.CURRENT;
    }

    private static boolean isReplayEntryHistoryExpression(final String value) {
        return value.startsWith("castsa>")
                || value.contains("count$xpaid")
                || value.contains("count$xcolorpaid")
                || value.contains("count$kicked")
                || value.contains("count$teamwork")
                || value.contains("count$optional")
                || value.contains("count$hasoptional")
                || value.contains("count$bargain")
                || value.contains("count$freerunning")
                || value.contains("count$madness")
                || value.contains("count$manacolorspaid")
                || value.contains("count$adamant")
                || value.contains("count$totalmanaspent")
                || value.contains("count$fromnamedability")
                || value.contains("count$casttotalmanaspent")
                || value.contains("count$promisedgift")
                || value.contains("count$escaped")
                || value.contains("count$emerged")
                || value.contains("count$altcost")
                || value.contains("count$timeskicked")
                || value.contains("count$converge")
                || value.contains("count$eachphyrexianpaidwithlife")
                || value.contains("count$eachspenttocast")
                || value.contains("count$wascastfrom")
                || value.contains("count$presence")
                || value.contains("count$ifcastinownmainphase");
    }

    private static boolean isReplayEventDependentExpression(final String value) {
        return value.startsWith("spawner>")
                || value.startsWith("triggeredspellability>")
                || value.startsWith("triggered")
                || value.startsWith("replaced")
                || value.contains("count$triggered")
                || value.contains("count$triggercount")
                || value.contains("count$replacecount")
                || value.contains("count$laststatebattlefield")
                || value.contains("count$laststategraveyard")
                || value.contains("count$firstbatchthisturnentered")
                || value.contains("triggerrememberamount");
    }

    private static boolean isEventDependentDefinition(final String value) {
        if (value == null) {
            return false;
        }

        final String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("triggered")
                || lower.startsWith("replaced")
                || lower.startsWith("spawner>")
                || lower.startsWith("triggeredspellability>");
    }

    private static boolean meetsReplayTriggeredObjectRequirements(
            final Trigger trigger) {
        if (trigger.isKeyword(Keyword.EVOLVE)
                || trigger.isKeyword(Keyword.INCREMENT)) {
            return false;
        }

        // Trigger.meetsRequirementsOnTriggeredObjects() uses Condition for
        // event-specific data such as LifePaid, Sacrificed or attacked player.
        // Without the original event, unknown Condition values are rejected
        // rather than guessed.
        return !trigger.hasParam("Condition");
    }

    private static boolean meetsReplayGlobalRequirements(final Game game,
                                                         final Map<String, String> params) {
        if (params.containsKey("APlayerHasMoreLifeThanEachOther")) {
            int highestLife = Integer.MIN_VALUE;
            final List<Player> healthiest = new ArrayList<>();

            for (final Player player : game.getPlayers()) {
                if (player.getLife() > highestLife) {
                    healthiest.clear();
                    highestLife = player.getLife();
                    healthiest.add(player);
                } else if (player.getLife() == highestLife) {
                    healthiest.add(player);
                }
            }

            if (healthiest.size() != 1) {
                return false;
            }
        }

        if (params.containsKey("APlayerHasMostCardsInHand")) {
            int largestHand = 0;
            final List<Player> withLargestHand = new ArrayList<>();

            for (final Player player : game.getPlayers()) {
                if (player.getCardsIn(ZoneType.Hand).size() > largestHand) {
                    withLargestHand.clear();
                    largestHand = player.getCardsIn(ZoneType.Hand).size();
                    withLargestHand.add(player);
                } else if (player.getCardsIn(ZoneType.Hand).size() == largestHand) {
                    withLargestHand.add(player);
                }
            }

            if (withLargestHand.size() != 1) {
                return false;
            }
        }

        return true;
    }
}