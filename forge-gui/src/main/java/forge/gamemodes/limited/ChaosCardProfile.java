package forge.gamemodes.limited;

import forge.StaticData;
import forge.card.CardType;
import forge.card.DeckHints;
import forge.card.ICardFace;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.sticker.StickerKind;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Limited heuristics over reachable script records, with no live Game or hidden draft data. */
final class ChaosCardProfile {
    private static final Set<String> GRANTED_RECORDS = Set.of("AddAbility", "AddTrigger", "AddStaticAbility",
            "AddReplacementEffect");
    enum Support { INDEPENDENT, AVAILABLE, POTENTIAL, UNAVAILABLE, UNKNOWN }

    record Strength(Double value, Double external, String source, Support support, Double standalone,
                    double availableContribution, double unavailableContribution, String dependencyReason,
                    ChaosAiEvidence.Status aiSupport, String aiEvidence, boolean enhancedLand) {}

    private record Effect(ApiType api, Map<String, String> params, boolean conditional,
                          boolean underSpell, ChaosAiEvidence.Assessment ai, ChaosManaAmount manaAmount) {}
    private record StickerOption(StickerKind kind, int tickets) {}
    private record Strategy(String reason, Support support, double standalone, double ceiling) {}

    static final class Context {
        private final List<ChaosCardProfile> picked;
        private final List<PaperCard> rawPicked;
        private final int attractions;
        private final int contraptions;
        private final List<StickerOption> stickers;
        private final boolean deckBuilding;
        private final boolean forAi;

        Context(List<ChaosCardProfile> picked, List<PaperCard> rawPicked, Deck deck, boolean deckBuilding, boolean forAi) {
            this.picked = picked;
            this.rawPicked = rawPicked;
            this.deckBuilding = deckBuilding;
            this.forAi = forAi;
            attractions = section(deck, DeckSection.Attractions).size();
            contraptions = section(deck, DeckSection.Contraptions).size();
            stickers = section(deck, DeckSection.Stickers).stream().limit(3)
                    .flatMap(sheet -> stickerOptions(sheet.getMainFace()).stream()).toList();
        }

        private static List<PaperCard> section(Deck deck, DeckSection section) {
            return deck == null || !deck.has(section) ? List.of()
                    : deck.get(section).toFlatList().stream().filter(section::validate).toList();
        }
    }

    private final PaperCard card;
    private final List<Effect> effects = new ArrayList<>();
    private final List<ChaosRequirement> requirements = new ArrayList<>();
    private final List<CardType> tokens = new ArrayList<>();
    private boolean unresolvedToken;
    private int primarySpellRoots;
    private boolean rollsMatter;
    private boolean unknown;

    private ChaosCardProfile(PaperCard card) {
        this.card = card;
        unknown = card.getRules().isVariant();
    }

    static ChaosCardProfile read(PaperCard card) {
        ChaosCardProfile profile = new ChaosCardProfile(card);
        for (ICardFace face : card.getAllFaces()) {
            boolean alternative = face != card.getMainFace();
            Map<String, String> variables = variables(face);
            for (String root : face.getAbilities()) {
                Map<String, String> params = AbilityFactory.getMapParams(root);
                if (!alternative && params.containsKey("SP")) {
                    profile.primarySpellRoots++;
                }
                ChaosRequirement requirement = alternative ? null : ChaosRequirement.fromSpell(params);
                if (requirement != null) {
                    profile.requirements.add(requirement);
                }
                profile.walk(root, variables, alternative, true, false, new HashSet<>(), 0);
            }
            for (String root : face.getTriggers()) {
                Map<String, String> trigger = AbilityFactory.getMapParams(root);
                String mode = trigger.get("Mode");
                profile.rollsMatter |= ("RolledDie".equals(mode) || "RolledDieOnce".equals(mode))
                        && !"Opponent".equals(trigger.get("ValidPlayer"));
                boolean selfEtb = "ChangesZone".equals(mode) && "Card.Self".equals(trigger.get("ValidCard"))
                        && "Battlefield".equals(trigger.get("Destination"));
                profile.walk(root, variables, alternative || !selfEtb, trigger.containsKey("OptionalDecider"),
                        false, new HashSet<>(), 0);
            }
            for (String root : face.getReplacements()) {
                ChaosRequirement requirement = ChaosRequirement.fromReplacement(AbilityFactory.getMapParams(root), variables);
                if (requirement != null) {
                    profile.requirements.add(requirement);
                }
                profile.walk(root, variables, true, false, false, new HashSet<>(), 0);
            }
            for (String root : face.getStaticAbilities()) {
                ChaosRequirement requirement = ChaosRequirement.fromStatic(AbilityFactory.getMapParams(root));
                if (requirement != null) {
                    profile.requirements.add(requirement);
                }
                profile.walk(root, variables, true, false, false, new HashSet<>(), 0);
            }
            for (String keyword : face.getKeywords()) {
                if (keyword.startsWith("ETBReplacement:")) {
                    String[] parts = keyword.split(":", 4);
                    if (parts.length >= 3 && variables.containsKey(parts[2])) {
                        profile.walk(variables.get(parts[2]), variables, false, true, false, new HashSet<>(), 0);
                    }
                }
            }
        }
        profile.readTokens();
        return profile;
    }

    private void walk(String record, Map<String, String> variables, boolean conditional, boolean voluntary,
                      boolean underSpell, Set<String> chain, int depth) {
        if (depth >= 12 || effects.size() >= 64 || !chain.add(record)) {
            unknown = true;
            return;
        }
        Map<String, String> params = AbilityFactory.getMapParams(record);
        boolean spell = underSpell || params.containsKey("SP");
        boolean gated = conditional || params.keySet().stream().anyMatch(key -> key.startsWith("Condition"))
                || params.containsKey("CheckSVar") || params.containsKey("UnlessCost");
        for (String key : List.of("SP", "AB", "DB", "ST")) {
            String name = params.get(key);
            if (name != null) {
                ApiType api;
                try {
                    api = ApiType.smartValueOf(name);
                } catch (RuntimeException invalidApi) {
                    api = null;
                }
                if (api == null) {
                    unknown = true;
                } else {
                    boolean choice = voluntary || params.containsKey("AB") || params.containsKey("SP")
                            || params.containsKey("OptionalDecider");
                    effects.add(new Effect(api, params, gated, spell, ChaosAiEvidence.assess(api, params, choice),
                            api == ApiType.Mana ? ChaosManaAmount.read(params, variables::get) : null));
                }
                break;
            }
        }
        for (String key : params.keySet()) {
            if (key.equals("SubAbility") || key.equals("ReplaceWith") || GRANTED_RECORDS.contains(key)
                    || AbilityFactory.additionalAbilityKeys.contains(key)
                    || (key.equals("Choices") && "Charm".equals(params.get("SP")))) {
                for (String name : params.get(key).split(GRANTED_RECORDS.contains(key) ? " & " : ",")) {
                    String child = variables.get(name.trim());
                    if (child == null) {
                        unknown = true;
                    } else {
                        walk(child, variables, gated || !(key.equals("Execute") || key.equals("SubAbility")),
                                voluntary, spell, chain, depth + 1);
                    }
                }
            }
        }
        chain.remove(record);
    }

    Strength strength(ChaosRatings.Rating external, Context context) {
        Double rating = external == null ? null : external.value();
        Double baseline = standalone();
        String source = external == null ? "estimated" : external.source();
        List<Map.Entry<String, Support>> dependencies = new ArrayList<>();
        Set<String> evidence = new java.util.LinkedHashSet<>();
        boolean uncertain = unknown;
        boolean missing = false;
        boolean possible = false;
        boolean supported = false;
        boolean aiBlocks = false;
        boolean aiPartial = false;
        boolean aiUnknown = false;
        boolean blockedActivation = false;
        double contribution = 0;
        double unavailable = 0;
        double selectorLoss = 0;
        Strategy strategy = strategy(context);
        for (Effect effect : effects) {
            Support state = support(effect, context);
            if (state != Support.INDEPENDENT) {
                dependencies.add(Map.entry(effect.api().name(), state));
            }
            double weight = switch (effect.api()) {
                case OpenAttraction, AssembleContraption -> 8;
                case RollDice, PutSticker -> 5;
                case PutCounter -> 2;
                default -> 0;
            };
            contribution += state == Support.AVAILABLE ? weight : 0;
            unavailable += state == Support.UNAVAILABLE || state == Support.POTENTIAL ? weight : 0;
            if (isVisit(effect) && context.picked.stream().anyMatch(profile -> profile.rollsMatter)) {
                contribution += 3;
            }
            ChaosAiEvidence.Assessment ai = effect.ai();
            boolean primaryPath = primarySpellRoots == 1 && effect.underSpell() && !effect.conditional();
            boolean auxiliaryVeto = primaryPath && state == Support.UNAVAILABLE
                    && (effect.api() == ApiType.OpenAttraction || effect.api() == ApiType.AssembleContraption);
            aiBlocks |= (ai.status() == ChaosAiEvidence.Status.UNSUPPORTED && primaryPath) || auxiliaryVeto;
            blockedActivation |= ai.status() == ChaosAiEvidence.Status.UNSUPPORTED && effect.params().containsKey("AB");
            aiPartial |= ai.status() == ChaosAiEvidence.Status.PARTIAL || ai.status() == ChaosAiEvidence.Status.UNSUPPORTED;
            aiUnknown |= ai.status() == ChaosAiEvidence.Status.UNKNOWN;
            evidence.add(auxiliaryVeto ? "auxiliary_deck_required_by_chkDrawback:" + effect.api() : ai.reason());
        }
        for (ChaosRequirement requirement : requirements) {
            Support state = support(requirement, context);
            dependencies.add(Map.entry(requirement.kind() + ":" + requirement.selector(), state));
            contribution += state == Support.AVAILABLE ? requirement.value() : 0;
            if (state == Support.UNAVAILABLE || state == Support.POTENTIAL) {
                unavailable += requirement.value();
                selectorLoss += state == Support.POTENTIAL ? requirement.value() * .5 : requirement.value();
            }
        }
        if (strategy != null) {
            dependencies.add(Map.entry(strategy.reason(), strategy.support()));
        }
        DeckHints needs = card.getRules().getAiHints().getDeckNeeds();
        if (needs != null && needs.isValid()) {
            boolean matches = needs.filter(otherCards(context)).iterator().hasNext();
            dependencies.add(Map.entry("DeckNeeds", matches ? Support.POTENTIAL : Support.UNKNOWN));
        }
        DeckHints hints = card.getRules().getAiHints().getDeckHints();
        if (hints != null && hints.isValid() && hints.filter(otherCards(context)).iterator().hasNext()) {
            dependencies.add(Map.entry("DeckHints", Support.POTENTIAL));
        }
        for (var dependency : dependencies) {
            Support state = dependency.getValue();
            uncertain |= state == Support.UNKNOWN;
            missing |= state == Support.UNAVAILABLE;
            possible |= state == Support.POTENTIAL;
            supported |= state == Support.AVAILABLE;
        }
        Support status = missing ? Support.UNAVAILABLE : supported ? Support.AVAILABLE : possible ? Support.POTENTIAL
                : uncertain ? Support.UNKNOWN : Support.INDEPENDENT;
        ChaosAiEvidence.Status ai = aiBlocks ? ChaosAiEvidence.Status.UNSUPPORTED
                : aiPartial ? ChaosAiEvidence.Status.PARTIAL : aiUnknown || unknown ? ChaosAiEvidence.Status.UNKNOWN
                : ChaosAiEvidence.Status.SUPPORTED;
        Double value = rating;
        if (baseline != null && (rating == null || missing || (possible && unavailable > 0))) {
            value = Math.min(100, baseline + Math.min(15, contribution));
            if (rating != null && selectorLoss > 0) {
                value = Math.min(value, Math.max(0, rating - selectorLoss));
            }
        } else if (!uncertain && fullyDependentSpell() && (missing || rating == null)) {
            // These spells have no independent effect if all of their operations are unavailable.
            value = supported ? 35 + Math.min(15, contribution) : 0;
        }
        if (strategy != null) {
            baseline = Math.max(baseline == null ? 0 : baseline, strategy.standalone());
            value = rating == null ? strategy.ceiling() : Math.min(rating, strategy.ceiling());
        }
        if (context.forAi && aiBlocks) {
            value = 0.0;
        } else if (context.forAi && blockedActivation && baseline != null && value != null) {
            value = Math.min(value, baseline + contribution);
        }
        if (value == null) {
            source = "unrated";
            status = Support.UNKNOWN;
        } else if (rating == null && status == Support.INDEPENDENT
                && effects.stream().anyMatch(effect -> independentValue(effect, card.getMainFace().getManaCost().getCMC()) == 0)) {
            status = Support.UNKNOWN;
        }
        return new Strength(value, rating, source, status, baseline, contribution, unavailable,
                dependencies.toString(), ai, String.join(",", evidence), strategy != null
                && card.getMainFace().getType().isLand() && strategy.support() == Support.POTENTIAL);
    }

    private Strategy strategy(Context context) {
        if (effects.isEmpty()) {
            return null;
        }
        if (card.getMainFace().getType().isPermanent() && effects.stream().allMatch(effect -> effect.api() == ApiType.Mana)) {
            for (Effect effect : effects) {
                var amount = effect.manaAmount();
                if (amount.variable() && amount.recognized() && !effect.conditional()
                        && effect.params().getOrDefault("Cost", "").matches("(?:[0-9] )?T")) {
                    // The candidate can complete an assembly whose other pieces are not eligible on their own.
                    int potential = amount.potential(card, amount.needsUrzaLands() ? context.rawPicked : otherCards(context));
                    int payment = "T".equals(effect.params().get("Cost")) ? 0 : effect.params().get("Cost").charAt(0) - '0';
                    int guaranteed = amount.guaranteed(card);
                    boolean supported = potential > Math.max(payment, guaranteed);
                    var source = ChaosManaPlan.readSource(card);
                    Double body = standalone();
                    double floor = (body == null ? 0 : body) + (source.output() > source.activation()
                            ? source.land() ? 26 + 10 * Math.max(0, Integer.bitCount(source.produces() & 63) - 1) : 7 : 0);
                    return new Strategy("ManaAmount:" + amount.condition() + ":" + guaranteed + "->" + potential,
                            supported ? Support.POTENTIAL : Support.UNAVAILABLE, floor,
                            supported ? (amount.needsUrzaLands() ? 100 : Math.max(floor, Math.min(72, 28 + potential * 4))) : floor);
                }
            }
        }
        if (primarySpellRoots != 1 || card.getMainFace().getType().isPermanent()) {
            return null;
        }
        if (effects.size() == 1) {
            Effect effect = effects.get(0);
            int mill = opponentMill(effect);
            if (mill > 0 && effect.underSpell() && !effect.conditional()) {
                int providers = 0;
                int output = 0;
                boolean skipSelf = context.deckBuilding;
                for (ChaosCardProfile profile : context.picked) {
                    if (skipSelf && profile.card.equals(card)) {
                        skipSelf = false;
                        continue;
                    }
                    int repeat = profile.effects.stream().mapToInt(ChaosCardProfile::opponentMill).max().orElse(0);
                    if (repeat > 0) {
                        providers++;
                        output += Math.min(12, repeat);
                    }
                }
                double floor = Math.min(70, 14 + 3 * mill);
                boolean supported = providers >= 3 && output >= 15;
                return new Strategy("OpponentMill:" + providers + "/" + output, supported ? Support.POTENTIAL : Support.UNAVAILABLE,
                        floor, supported ? Math.min(90, floor + 25 + output / 2.0) : floor);
            }
            if (effect.api() == ApiType.Mana && effect.underSpell() && !effect.conditional()) {
                var params = effect.params();
                String produced = params.getOrDefault("Produced", "");
                int cost = card.getMainFace().getManaCost().getCMC();
                int output = effect.manaAmount().minimum();
                if (output > cost && cost > 0 && produced.matches("[WUBRGC]")
                        && !params.containsKey("RestrictValid") && !params.containsKey("ManaRestriction")) {
                    int payoffs = 0;
                    byte color = ManaAtom.fromName(produced);
                    int gain = output - cost;
                    for (PaperCard payoff : otherCards(context)) {
                        var mana = payoff.getMainFace().getManaCost();
                        int earlierLands = Math.max(cost, mana.getCMC() - gain);
                        if (!payoff.getMainFace().getType().isLand() && payoff.getMainFace().getType().isPermanent()
                                && mana.getCMC() >= 4 && mana.getCMC() <= 6 && mana.countX() == 0
                                && earlierLands < mana.getCMC() && nonRitualPips(mana, color) <= earlierLands - cost) {
                            payoffs++;
                        }
                    }
                    double floor = 18 + gain * 3;
                    return new Strategy("TemporaryMana:" + payoffs, payoffs >= 4 ? Support.POTENTIAL : Support.UNAVAILABLE,
                            floor, floor + Math.min(12, Math.max(0, payoffs - 2) * 2));
                }
            }
        }
        if (effects.get(0).api() == ApiType.Discard && effects.get(0).params().containsKey("RememberDiscarded")
                && effects.stream().allMatch(effect -> Set.of(ApiType.Discard, ApiType.ChangeZone, ApiType.Cleanup).contains(effect.api()))
                && effects.stream().anyMatch(effect -> effect.api() == ApiType.ChangeZone
                        && "Library".equals(effect.params().get("Origin")) && "Hand".equals(effect.params().get("Destination"))
                        && "Land.Basic".equals(effect.params().get("ChangeType")))) {
            int fuel = 0;
            for (PaperCard item : otherCards(context)) {
                for (String keyword : item.getMainFace().getKeywords()) {
                    if (keyword.startsWith("Flashback") || keyword.startsWith("Madness") || keyword.startsWith("Dredge")
                            || keyword.startsWith("Retrace") || keyword.startsWith("Unearth")) {
                        fuel++;
                        break;
                    }
                }
            }
            return new Strategy("DiscardForBasicLands:" + fuel, fuel >= 3 ? Support.POTENTIAL : Support.UNAVAILABLE,
                    22, 22 + Math.min(16, fuel * 4));
        }
        return null;
    }

    private static int nonRitualPips(forge.card.mana.ManaCost mana, byte color) {
        int count = 0;
        for (ManaCostShard shard : mana) {
            if (!shard.isPhyrexian() && !shard.isOr2Generic()
                    && (shard.isSnow() || (shard.isColorless() ? color != ManaAtom.COLORLESS
                    : !shard.canBePaidWithManaOfColor(color)))) {
                count++;
            }
        }
        return count;
    }

    private static int opponentMill(Effect effect) {
        var params = effect.params();
        String target = params.getOrDefault("ValidTgts", "");
        String defined = params.getOrDefault("Defined", "");
        String amount = params.getOrDefault("NumCards", "1");
        return effect.api() == ApiType.Mill && (Set.of("Player", "Opponent").contains(target)
                || Set.of("Opponent", "Opponents", "EachOpponent").contains(defined)) && amount.matches("[1-9][0-9]?")
                ? Integer.parseInt(amount) : 0;
    }

    private List<PaperCard> otherCards(Context context) {
        List<PaperCard> cards = new ArrayList<>(context.picked.stream().map(profile -> profile.card).toList());
        if (context.deckBuilding) {
            cards.remove(card);
        }
        return cards;
    }

    private Support support(ChaosRequirement requirement, Context context) {
        if (!requirement.isRecognized()) {
            return Support.UNKNOWN;
        }
        if (requirement.kind() == ChaosRequirement.Kind.ASSEMBLY) {
            boolean assembler = context.picked.stream().anyMatch(profile ->
                    requirement.matches(profile.card.getMainFace().getType(), false) && profile.hasAssembler());
            return context.contraptions >= 2 && assembler ? Support.AVAILABLE
                    : (context.contraptions > 0 || assembler) && !context.deckBuilding ? Support.POTENTIAL : Support.UNAVAILABLE;
        }
        if (requirement.needsSticker()) {
            if (context.stickers.isEmpty()) {
                return Support.UNAVAILABLE;
            }
            for (ChaosCardProfile profile : context.picked) {
                if (profile.effects.stream().anyMatch(effect -> effect.api() == ApiType.PutSticker
                        && profile.stickerSupport(effect, context) == Support.AVAILABLE)) {
                    return Support.AVAILABLE;
                }
            }
            return context.picked.stream().anyMatch(ChaosCardProfile::hasStickerEffect) ? Support.POTENTIAL
                    : Support.UNAVAILABLE;
        }
        if (!requirement.other() && ChaosRequirement.fitsZones(card, requirement.zone())
                && requirement.matches(card.getMainFace().getType(), false)) {
            return "Battlefield".equals(requirement.zone()) ? Support.AVAILABLE : Support.POTENTIAL;
        }
        for (PaperCard provider : otherCards(context)) {
            if (ChaosRequirement.fitsZones(provider, requirement.zone())
                    && requirement.matches(provider.getMainFace().getType(), false)) {
                return "Battlefield".equals(requirement.zone()) ? Support.AVAILABLE : Support.POTENTIAL;
            }
        }
        boolean token = "Battlefield".equals(requirement.zone()) && context.picked.stream().anyMatch(profile ->
                profile.tokens.stream().anyMatch(type -> requirement.matches(type, true)));
        if (token) {
            return Support.POTENTIAL;
        }
        return context.picked.stream().anyMatch(profile -> profile.unresolvedToken) ? Support.UNKNOWN : Support.UNAVAILABLE;
    }

    private void readTokens() {
        StaticData data = StaticData.instance();
        for (Effect effect : effects) {
            if (effect.api() != ApiType.Token || effect.conditional() || !isOwnEffect(effect.params())
                    || !"You".equals(effect.params().getOrDefault("TokenOwner", "You"))) {
                continue;
            }
            String script = effect.params().get("TokenScript");
            var token = script == null || data == null || data.getAllTokens() == null ? null
                    : data.getAllTokens().getToken(script);
            if (token == null) {
                unresolvedToken = true;
            } else {
                tokens.add(token.getRules().getType());
            }
        }
    }

    private boolean hasAssembler() {
        return effects.stream().anyMatch(effect -> effect.api() == ApiType.AssembleContraption
                && !effect.conditional() && !effect.params().containsKey("DefinedContraption")
                && "Self".equals(effect.params().getOrDefault("DefinedAssembler", "Self")));
    }

    boolean interaction() {
        return effects.stream().anyMatch(effect -> switch (effect.api()) {
            case Destroy, DestroyAll, Counter, DamageAll -> true;
            case DealDamage, ChangeZone -> effect.params().getOrDefault("ValidTgts", "").contains("Creature");
            default -> false;
        });
    }

    boolean manaOnly() {
        return !card.getRules().getType().isCreature() && !effects.isEmpty()
                && effects.stream().anyMatch(effect -> effect.api() == ApiType.Mana)
                && effects.stream().allMatch(effect -> effect.api() == ApiType.Mana
                        || effect.api() == ApiType.Tap && "Self".equals(effect.params().get("Defined"))
                                && "True".equalsIgnoreCase(effect.params().get("ETB"))
                        || effect.api() == ApiType.DealDamage && "You".equals(effect.params().get("Defined")));
    }

    private Support support(Effect effect, Context context) {
        Map<String, String> params = effect.params();
        switch (effect.api()) {
            case Mana:
                if (effect.manaAmount().variable() && !effect.manaAmount().recognized()) {
                    return Support.UNKNOWN;
                }
                break;
            case OpenAttraction:
                return effect.conditional() || !isOwnEffect(params) ? Support.UNKNOWN : available(context.attractions >= 3);
            case RollDice:
                if (isVisit(effect)) {
                    if (effect.conditional() || !isOwnEffect(params)) {
                        return Support.UNKNOWN;
                    }
                    boolean opener = hasOpener() || context.picked.stream().anyMatch(ChaosCardProfile::hasOpener);
                    return available(context.attractions >= 3 && opener);
                }
                break;
            case AssembleContraption:
                if ("ReplacedCause".equals(params.get("DefinedAssembler"))
                        && requirements.stream().anyMatch(requirement -> requirement.kind() == ChaosRequirement.Kind.ASSEMBLY)) {
                    return Support.INDEPENDENT;
                }
                if (effect.conditional() || params.containsKey("Reassemble") || params.containsKey("DefinedContraption")
                        || params.getOrDefault("Cost", "").contains("<") || !isOwnEffect(params)
                        || !Set.of("Self", "You").contains(params.getOrDefault("DefinedAssembler", "Self"))
                        || !params.getOrDefault("Amount", "1").matches("[1-9][0-9]?")) {
                    return Support.UNKNOWN;
                }
                return available(context.contraptions > 0);
            case PutSticker:
                return stickerSupport(effect, context);
            case PutCounter:
                if ("TICKET".equals(params.get("CounterType"))) {
                    if (effect.conditional() || !isOwnEffect(params) || ticketCount(effect) == 0) {
                        return Support.UNKNOWN;
                    }
                    return available(!context.stickers.isEmpty() && (hasStickerEffect()
                            || context.picked.stream().anyMatch(ChaosCardProfile::hasStickerEffect)));
                }
                break;
            default:
                break;
        }
        return Support.INDEPENDENT;
    }

    private Support stickerSupport(Effect effect, Context context) {
        Map<String, String> params = effect.params();
        if (context.stickers.isEmpty()) {
            return Support.UNAVAILABLE;
        }
        if (effect.conditional() || params.containsKey("ChoiceZone") || params.containsKey("MaxTickets")) {
            return Support.UNKNOWN;
        }
        boolean self = "Self".equals(params.get("Defined")) && card.getRules().getType().isPermanent();
        boolean ownPermanent = "Permanent.nonLand+YouOwn".equals(params.get("Choices"))
                && (isStickerTarget() || context.picked.stream().anyMatch(ChaosCardProfile::isStickerTarget));
        if (!self && !ownPermanent) {
            return Support.UNKNOWN;
        }
        StickerKind kind = StickerKind.smartValueOf(params.get("Kind"));
        if (kind == null && params.containsKey("Kind")) {
            return Support.UNKNOWN;
        }
        int ownTickets = context.deckBuilding && context.picked.contains(this) ? 0 : ticketBudget();
        int tickets = params.containsKey("NoTicketCost") ? Integer.MAX_VALUE
                : ownTickets + context.picked.stream().mapToInt(ChaosCardProfile::ticketBudget).sum();
        return available(context.stickers.stream().anyMatch(option -> (kind == null
                ? option.kind() == StickerKind.PT || option.kind() == StickerKind.ABILITY
                : kind == option.kind()) && option.tickets() <= tickets));
    }

    private static Support available(boolean available) {
        return available ? Support.AVAILABLE : Support.UNAVAILABLE;
    }

    private static boolean isOwnEffect(Map<String, String> params) {
        return !params.containsKey("ValidTgts") && "You".equals(params.getOrDefault("Defined", "You"));
    }

    private boolean hasOpener() {
        return effects.stream().anyMatch(effect -> effect.api() == ApiType.OpenAttraction
                && !effect.conditional() && isOwnEffect(effect.params()));
    }

    private boolean hasStickerEffect() {
        return effects.stream().anyMatch(effect -> effect.api() == ApiType.PutSticker);
    }

    private boolean isStickerTarget() {
        return card.getRules().getType().isPermanent() && !card.getRules().getType().isLand();
    }

    private int ticketBudget() {
        return effects.stream().filter(effect -> !effect.conditional() && isOwnEffect(effect.params()))
                .mapToInt(ChaosCardProfile::ticketCount).sum();
    }

    private static int ticketCount(Effect effect) {
        if (effect.api() != ApiType.PutCounter || !"TICKET".equals(effect.params().get("CounterType"))) {
            return 0;
        }
        String amount = effect.params().getOrDefault("CounterNum", "1");
        return amount.matches("[1-9]") ? Integer.parseInt(amount) : 0;
    }

    private static boolean isVisit(Effect effect) {
        return effect.api() == ApiType.RollDice && effect.params().containsKey("ToVisitYourAttractions");
    }

    private boolean fullyDependentSpell() {
        ICardFace face = card.getMainFace();
        return card.getOtherFace() == null && (face.getType().isInstant() || face.getType().isSorcery())
                && !face.getKeywords().iterator().hasNext() && !face.getTriggers().iterator().hasNext()
                && !face.getReplacements().iterator().hasNext() && !face.getStaticAbilities().iterator().hasNext()
                && !effects.isEmpty() && effects.stream().allMatch(effect -> switch (effect.api()) {
                    case OpenAttraction, AssembleContraption, PutSticker -> true;
                    case PutCounter -> "TICKET".equals(effect.params().get("CounterType"));
                    case ChangeZone -> ChaosRequirement.fromSpell(effect.params()) != null;
                    default -> false;
                });
    }

    private Double standalone() {
        ICardFace face = card.getMainFace();
        if (face.getManaCost().isNoCost() || face.getManaCost().countX() > 0 || card.getRules().isVariant()) {
            return null;
        }
        double value = 0;
        if (face.getType().isCreature() && Objects.toString(face.getPower(), "").matches("[0-9]+")
                && Objects.toString(face.getToughness(), "").matches("[0-9]+")) {
            value = 42 + 4 * (face.getIntPower() + face.getIntToughness() - 2 * face.getManaCost().getCMC());
            for (String keyword : face.getKeywords()) {
                value += switch (keyword) {
                    case "Flying", "Deathtouch", "First Strike" -> 5;
                    case "Reach", "Trample", "Vigilance", "Haste", "Lifelink" -> 2;
                    case "Defender" -> -7;
                    default -> 0;
                };
            }
        }
        int manaValue = face.getManaCost().getCMC();
        for (Effect effect : effects) {
            value = Math.max(value, independentValue(effect, manaValue));
        }
        return value == 0 ? null : Math.max(12, Math.min(85, value));
    }

    private static double independentValue(Effect effect, int manaValue) {
        Map<String, String> params = effect.params();
        if (effect.conditional() || params.containsKey("AB") || params.containsKey("Cost")) {
            return 0;
        }
        if (effect.api() == ApiType.ChangeZone && "Library".equals(params.get("Origin"))
                && "Battlefield".equals(params.get("Destination")) && "Land.Basic".equals(params.get("ChangeType"))) {
            return 48 - 2 * (manaValue - 3);
        }
        if (effect.api() == ApiType.Draw && isOwnEffect(params)) {
            String amount = params.getOrDefault("NumCards", "1");
            if (amount.matches("[1-5]")) {
                return 37 + 6 * (Integer.parseInt(amount) - 1) - 2 * (manaValue - 2);
            }
        }
        if (effect.api() == ApiType.Destroy && "Creature".equals(params.get("ValidTgts"))) {
            return 60 - 3 * (manaValue - 2);
        }
        return 0;
    }

    private static Map<String, String> variables(ICardFace face) {
        Map<String, String> result = new HashMap<>();
        face.getVariables().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static List<StickerOption> stickerOptions(ICardFace face) {
        Map<String, String> variables = variables(face);
        List<StickerOption> result = new ArrayList<>();
        for (String keyword : face.getKeywords()) {
            if (!keyword.startsWith("StickerSheet:")) {
                continue;
            }
            for (String slot : keyword.substring("StickerSheet:".length()).split(",")) {
                String record = variables.get(slot.trim());
                if (record != null) {
                    Map<String, String> params = AbilityFactory.getMapParams(record);
                    StickerKind kind = StickerKind.smartValueOf(params.get("Kind"));
                    String tickets = params.getOrDefault("Tickets", "0");
                    if (kind != null && tickets.matches("[0-9]{1,2}") && (kind != StickerKind.ABILITY
                            || Set.of("Ability", "Keywords", "Triggers", "Statics").stream().anyMatch(params::containsKey))) {
                        result.add(new StickerOption(kind, Integer.parseInt(tickets)));
                    }
                }
            }
        }
        return result;
    }
}
