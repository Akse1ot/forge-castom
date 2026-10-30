package forge.game.cost;

import forge.card.CardType.CoreType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in preselection for Law of Causality; never changes a game zone. */
public final class CausalityCostHelper {
    public static final String PARAM = "CausalityCost";
    public static final String TAG = "Causality";
    public static final String CHOICE = "CausalityPreselection";

    private CausalityCostHelper() { }

    public record Material(int id, long timestamp, List<CoreType> types) implements Serializable {
        public Material {
            types = List.copyOf(types);
        }
    }

    /** Only immutable values; safe to share between ability copies and simulation games. */
    public record Selection(List<Material> materials, List<CoreType> commonTypes,
                            boolean paid) implements Serializable {
        public Selection {
            materials = List.copyOf(materials);
            commonTypes = List.copyOf(commonTypes);
        }
    }

    public static boolean isEnabled(SpellAbility sa) {
        return sa != null && "True".equals(sa.getRootAbility().getParam(PARAM));
    }

    public static boolean isSelectedPart(SpellAbility sa, CostPart part) {
        return isEnabled(sa) && part instanceof CostExile exile && TAG.equals(exile.getExileTag());
    }

    public static CardCollection candidates(SpellAbility sa) {
        CardCollection result = new CardCollection();
        for (Card card : sa.getActivatingPlayer().getCardsIn(ZoneType.Graveyard)) {
            if (card.isPermanent() && card.canExiledBy(sa, false)) {
                result.add(card);
            }
        }
        return result;
    }

    public static boolean select(SpellAbility sa, Iterable<Card> chosen) {
        if (!isEnabled(sa) || sa.isCopied() || chosen == null) {
            return false;
        }
        CardCollection available = candidates(sa);
        List<Material> materials = new ArrayList<>();
        EnumSet<CoreType> common = EnumSet.allOf(CoreType.class);
        for (Card card : chosen) {
            Card current = sa.getHostCard().getGame().findById(card.getId());
            if (current == null || !current.equalsWithGameTimestamp(card)
                    || !available.contains(current)
                    || materials.stream().anyMatch(m -> m.id() == card.getId())) {
                return false;
            }
            List<CoreType> types = List.copyOf(card.getType().getCoreTypes());
            common.retainAll(types);
            materials.add(new Material(card.getId(), card.getGameTimestamp(), types));
        }
        if (materials.isEmpty() || common.isEmpty()) {
            return false;
        }
        sa.setCausalitySelection(new Selection(materials, List.copyOf(common), false));
        return true;
    }

    private static CardCollection resolveSelection(SpellAbility sa) {
        CardCollection result = new CardCollection();
        Selection selection = sa.getCausalitySelection();
        if (selection == null || selection.paid()) {
            return result;
        }
        CardCollection available = candidates(sa);
        for (Material material : selection.materials()) {
            Card card = sa.getHostCard().getGame().findById(material.id());
            if (card == null || card.getGameTimestamp() != material.timestamp()
                    || !available.contains(card)
                    || !EnumSet.copyOf(material.types()).equals(
                            EnumSet.copyOf(card.getType().getCoreTypes()))) {
                return new CardCollection();
            }
            result.add(card);
        }
        return result;
    }

    public static boolean hasValidSelection(SpellAbility sa) {
        Selection selection = sa.getCausalitySelection();
        return selection != null && !selection.paid()
                && resolveSelection(sa).size() == selection.materials().size();
    }

    /** Target types must meet the intersection, including Kindred when present. */
    public static boolean matchesTarget(SpellAbility sa, Card target) {
        if (!isEnabled(sa) || target == null || !target.isPermanent() || target.isLand()) {
            return false;
        }
        Selection selection = sa.getCausalitySelection();
        if (selection != null) {
            return selection.commonTypes().stream().anyMatch(target.getType()::hasType);
        }
        // Availability query before a cast; never substitute new graveyard cards on the stack.
        if (sa.isCopied() || sa.getHostCard().isInZone(ZoneType.Stack)) {
            return false;
        }
        for (Card material : candidates(sa)) {
            for (CoreType type : material.getType().getCoreTypes()) {
                if (target.getType().hasType(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean targetsMatch(SpellAbility sa) {
        if (!sa.isTargetNumberValid()) {
            return false;
        }
        for (Card target : sa.getTargets().getTargetCards()) {
            if (!matchesTarget(sa, target) || !sa.canTarget(target, true)) {
                return false;
            }
        }
        return true;
    }

    public static boolean prepare(SpellAbility sa) {
        if (!isEnabled(sa)) {
            return true;
        }
        if (sa.isCopied()) {
            return sa.getCausalitySelection() != null && sa.getCausalitySelection().paid();
        }
        if (hasValidSelection(sa)) {
            return true;
        }
        sa.setCausalitySelection(null);
        CardCollection available = candidates(sa);
        if (available.isEmpty()) {
            return false;
        }
        // The controller differs; validation and the subsequent payment do not.
        CardCollectionView chosen = sa.getActivatingPlayer().getController().chooseCardsForEffect(
                available, sa, "Choose one or more permanent cards sharing a card type to exile as a cost",
                1, available.size(), true, Collections.singletonMap(CHOICE, true));
        if (!select(sa, chosen)) {
            return false;
        }
        boolean legalTarget = sa.getHostCard().getGame().getCardsIn(ZoneType.Battlefield).stream()
                .anyMatch(target -> sa.canTarget(target));
        if (!legalTarget) {
            clearUnpaid(sa);
        }
        return legalTarget;
    }

    /** Materialize the selected count only on the copied Cost returned by CostAdjustment. */
    public static void applyCount(Cost cost, SpellAbility sa) {
        if (!isEnabled(sa) || sa.isCopied() || sa.getCausalitySelection() == null) {
            return;
        }
        for (CostPart part : cost.getCostParts()) {
            if (isSelectedPart(sa, part)) {
                part.setAmount(Integer.toString(sa.getCausalitySelection().materials().size()));
            }
        }
    }

    public static int reduction(SpellAbility sa) {
        return isEnabled(sa) && !sa.isCopied() && sa.getCausalitySelection() != null
                ? sa.getCausalitySelection().materials().size() : 0;
    }

    /** No additional irreversible cost before a human can cancel mana input. */
    public static List<CostPart> exileLast(List<CostPart> parts, SpellAbility sa) {
        if (!isEnabled(sa)) {
            return parts;
        }
        List<CostPart> result = new ArrayList<>(parts);
        result.sort((a, b) -> Boolean.compare(isSelectedPart(sa, a), isSelectedPart(sa, b)));
        return result;
    }

    public static boolean validatePayment(SpellAbility sa) {
        return !isEnabled(sa) || sa.isCopied() || hasValidSelection(sa) && targetsMatch(sa);
    }

    public static PaymentDecision decision(SpellAbility sa) {
        return validatePayment(sa) ? PaymentDecision.card(resolveSelection(sa)) : null;
    }

    /** Finalization cannot veto an accepted payment or fabricate missing payment evidence. */
    public static void completePayment(SpellAbility sa, Selection paid) {
        if (isEnabled(sa) && !sa.isCopied()) {
            sa.setCausalitySelection(paid);
        }
    }

    public static Reservation reserve(SpellAbility sa) {
        return new Reservation(sa);
    }

    /** Check other costs without letting them count the materials already chosen for Causality. */
    public static boolean otherCostsPayable(SpellAbility sa) {
        if (!isEnabled(sa) || sa.isCopied()) {
            return true;
        }
        if (!hasValidSelection(sa)) {
            return false;
        }
        Cost cost = CostAdjustment.adjust(sa.getPayCosts(), sa, false);
        try (Reservation reservation = reserve(sa)) {
            int ownParts = 0;
            for (CostPart part : cost.getCostParts()) {
                if (isSelectedPart(sa, part)) {
                    ownParts++;
                } else if (!part.canPay(sa, sa.getActivatingPlayer(), false)) {
                    return false;
                }
            }
            return ownParts == 1;
        }
    }

    /** Existing Card reservation flags are owned only for the duration of this CostPayment. */
    public static final class Reservation implements AutoCloseable {
        private final SpellAbility ability;
        private final Selection selected;
        private final Map<Card, Boolean> previousFlags = new IdentityHashMap<>();
        private Selection paid;

        private Reservation(SpellAbility sa) {
            ability = sa;
            selected = isEnabled(sa) && !sa.isCopied() ? sa.getCausalitySelection() : null;
            if (selected != null) {
                for (Card card : resolveSelection(sa)) {
                    previousFlags.put(card, card.isReservedForZoneChangePayment());
                }
                forPart(null);
            }
        }

        public void forPart(CostPart part) {
            boolean reserve = !isSelectedPart(ability, part);
            for (Map.Entry<Card, Boolean> entry : previousFlags.entrySet()) {
                entry.getKey().setReservedForZoneChangePayment(reserve || entry.getValue());
            }
        }

        public boolean preflight(List<CostPart> parts, boolean effect) {
            if (!isEnabled(ability) || ability.isCopied()) {
                return true;
            }
            if (selected == null || previousFlags.size() != selected.materials().size()) {
                return false;
            }
            CostPart own = null;
            for (CostPart part : parts) {
                if (isSelectedPart(ability, part)) {
                    if (own != null) {
                        return false;
                    }
                    own = part;
                }
            }
            if (own == null) {
                return false;
            }
            forPart(own);
            boolean valid = validateDecision(own, decision(ability), effect);
            forPart(null);
            if (!valid) {
                return false;
            }
            for (CostPart part : parts) {
                if (part != own && !part.canPay(ability, ability.getActivatingPlayer(), effect)) {
                    return false;
                }
            }
            return true;
        }

        public boolean validateDecision(CostPart part, PaymentDecision choice, boolean effect) {
            if (!isEnabled(ability) || ability.isCopied()) {
                return true;
            }
            if (choice == null || selected == null || !selected.equals(ability.getCausalitySelection())) {
                return false;
            }
            if (isSelectedPart(ability, part)) {
                if (!validatePayment(ability)
                        || choice.cards.size() != selected.materials().size()
                        || part.getAbilityAmount(ability) != choice.cards.size()
                        || !part.canPay(ability, ability.getActivatingPlayer(), effect)) {
                    return false;
                }
                CardCollection expected = resolveSelection(ability);
                for (Card card : choice.cards) {
                    if (expected.stream().noneMatch(card::equalsWithGameTimestamp)) {
                        return false;
                    }
                }
                return true;
            }
            if (part instanceof CostOr orCost) {
                Cost branch = "Left".equals(choice.type) ? orCost.getLeftCost()
                        : "Right".equals(choice.type) ? orCost.getRightCost() : null;
                if (branch == null || choice.nested == null
                        || branch.getCostParts().size() != choice.nested.size()) {
                    return false;
                }
                for (int i = 0; i < choice.nested.size(); i++) {
                    if (!validateDecision(branch.getCostParts().get(i), choice.nested.get(i), effect)) {
                        return false;
                    }
                }
            }
            if (part instanceof CostExile || part instanceof CostCollectEvidence || part instanceof CostForage
                    || part instanceof CostSacrifice || part instanceof CostReturn
                    || part instanceof CostPutCardToLib || part instanceof CostDiscard) {
                for (Card card : choice.cards) {
                    if (selected.materials().stream().anyMatch(material -> material.id() == card.getId())) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Capture stock CostExile's actual result before CostPayment resets its lists. */
        public void recordPayment(CostPart part) {
            if (!isSelectedPart(ability, part)) {
                return;
            }
            CostExile exile = (CostExile) part;
            List<Material> materials = new ArrayList<>();
            EnumSet<CoreType> common = EnumSet.allOf(CoreType.class);
            for (Card moved : exile.getCardList()) {
                Card original = exile.getLKIList().stream()
                        .filter(card -> card.getId() == moved.getId()).findFirst().orElse(null);
                if (original != null) {
                    List<CoreType> types = List.copyOf(original.getType().getCoreTypes());
                    materials.add(new Material(moved.getId(), moved.getGameTimestamp(), types));
                    common.retainAll(types);
                }
            }
            if (materials.isEmpty()) {
                common.clear();
            }
            paid = new Selection(materials, List.copyOf(common), true);
        }

        public Selection paidSelection() {
            return paid;
        }

        @Override
        public void close() {
            for (Map.Entry<Card, Boolean> entry : previousFlags.entrySet()) {
                entry.getKey().setReservedForZoneChangePayment(entry.getValue());
            }
        }
    }

    public static void clearUnpaid(SpellAbility sa) {
        if (isEnabled(sa) && sa.getCausalitySelection() != null && !sa.getCausalitySelection().paid()) {
            sa.setCausalitySelection(null);
        }
    }

    /** Tracks only this cast's abilities, including variants created by existing casting code. */
    public static final class Attempt {
        private final List<SpellAbility> abilities = new ArrayList<>();

        public Attempt(SpellAbility sa) {
            track(sa);
            if (isEnabled(sa) && !sa.isCopied() && sa.getCausalitySelection() != null
                    && sa.getCausalitySelection().paid()) {
                sa.setCausalitySelection(null);
            }
        }

        public void track(SpellAbility sa) {
            if (isEnabled(sa) && abilities.stream().noneMatch(other -> other == sa)) {
                abilities.add(sa);
            }
        }

        public void finish(boolean success) {
            for (SpellAbility sa : abilities) {
                if (!success && !sa.isCopied()) {
                    sa.setCausalitySelection(null);
                    sa.getPaidHash().row(CostExile.TAGGED_PAID_LIST_PREFIX + TAG).clear();
                } else {
                    clearUnpaid(sa);
                }
            }
        }
    }
}
