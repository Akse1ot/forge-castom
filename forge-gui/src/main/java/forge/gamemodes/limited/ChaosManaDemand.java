package forge.gamemodes.limited;

import forge.item.PaperCard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded mana-development diagnostic. It never creates game objects or changes a tuner score. */
final class ChaosManaDemand implements AutoCloseable {
    record Config(int samples, int horizon, long seed, boolean onDraw, boolean expandedQueries,
                  int cacheEntries, long cacheBytes, int maxNodesPerScenario) {
        Config {
            if (samples < 2 || samples > 2048 || horizon < 1 || horizon > 12
                    || cacheEntries < 1 || cacheEntries > 1024 || cacheBytes < 1024
                    || maxNodesPerScenario < 1) {
                throw new IllegalArgumentException("Invalid mana-demand limits");
            }
        }
        static Config defaults() { return new Config(128, 8, 0x4348414f534d414eL, false, true,
                256, 8 * 1024 * 1024, 200000); }
    }
    record Cost(int generic, List<Integer> pips, int cmc, boolean known, String text) {
        Cost { pips = pips.stream().sorted().toList(); }
    }
    record Source(int activation, List<Integer> units) {
        Source { units = units.stream().sorted().toList(); }
    }
    record Card(String id, Cost cost, boolean land, boolean creature, boolean tapped, Source source) {}
    record Probe(Cost cost, int deadline, int weight) {}
    record Unit(int mask, int parent) {}
    record Payment(List<Unit> remainder, Set<Integer> used) {}
    record ModelKey(Config options, String queries, String workload, String policy) {}
    private record CacheKey(ModelKey model, List<Card> slots) {}
    record Delta(double mean, double se, double low, double high) {}
    record GenericPair(List<Card> before, List<Card> after) {}
    record Stats(long hits, long misses, int uniqueConfigurations, int uniqueFullConfigurations,
                 boolean uniqueCountLimited, long evictions, int entries, int peakEntries,
                 long retainedBytesEstimate, long peakRetainedBytesEstimate, long hitPayloadBytes,
                 long peakHitPayloadBytes) {}

    static final Card BLANK = new Card("inert", new Cost(0, List.of(), 0, true, "0"),
            false, false, false, null);
    static Card resource(Cost cost, boolean land, boolean creature, boolean tapped, Source source) {
        return new Card(cost + "/" + land + "/" + creature + "/" + tapped + "/" + source,
                cost, land, creature, tapped, source);
    }

    static final class Evaluation {
        private final String id;
        private final long[][] hits;
        private final long bytes;
        private final long payload;
        private final int samples;
        private Evaluation(String id, long[][] hits, long bytes, int samples) {
            this.samples = samples;
            this.id = id;
            this.hits = hits;
            this.bytes = bytes;
            this.payload = (long) hits.length * ((samples + 63) / 64) * Long.BYTES;
        }
        String id() { return id; }
        List<List<String>> hitWords() {
            List<List<String>> result = new ArrayList<>();
            for (long[] words : hits) {
                List<String> row = new ArrayList<>();
                for (long word : words) { row.add(Long.toUnsignedString(word, 16)); }
                result.add(List.copyOf(row));
            }
            return List.copyOf(result);
        }
        double[] probabilities() {
            double[] values = new double[hits.length];
            for (int i = 0; i < hits.length; i++) {
                for (long word : hits[i]) { values[i] += Long.bitCount(word); }
                values[i] /= samples;
            }
            return values;
        }
    }

    private final Config config;
    private final ChaosManaDemandReader reader;
    private final List<Probe> workload;
    private final List<Probe> queries;
    private List<Card> reference;
    private final ModelKey model;
    private final int[] demandWeights;
    private final int[][] scenarios;
    private final LinkedHashMap<CacheKey, Evaluation> cache = new LinkedHashMap<>(16, .75f, true);
    private final Set<String> unique = new HashSet<>();
    private final Set<String> fullUnique = new HashSet<>();
    private long cacheHits, cacheMisses, evictions, bytes, payload, peakBytes, peakPayload;
    private int peakEntries;
    private boolean countLimited, closed;

    ChaosManaDemand(List<PaperCard> seed, List<PaperCard> stock, Config config) {
        if (seed.size() != 40 || stock.size() > 256) {
            throw new IllegalArgumentException("Unsupported diagnostic input size");
        }
        this.config = config;
        reader = new ChaosManaDemandReader();
        workload = probes(seed);
        List<PaperCard> union = new ArrayList<>(seed);
        if (config.expandedQueries) {
            Map<PaperCard, Integer> remaining = new HashMap<>();
            seed.forEach(c -> remaining.merge(c, 1, Integer::sum));
            for (PaperCard card : stock) {
                int n = remaining.getOrDefault(card, 0);
                if (n > 0) { remaining.put(card, n - 1); } else { union.add(card); }
            }
        }
        queries = probes(union);
        if (workload.isEmpty() || queries.isEmpty() || queries.size() > 128) {
            throw new IllegalArgumentException("No bounded confirmed cost workload");
        }
        demandWeights = new int[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            for (Probe demand : workload) {
                if (queries.get(i).cost.equals(demand.cost)) { demandWeights[i] += demand.weight; }
            }
        }
        reference = seed.stream().map(c -> reader.read(c).resource())
                .sorted(Comparator.comparing(Card::id)).toList();
        model = new ModelKey(config, queries.toString(), workload.toString(), "bounded-source-development-v1");
        scenarios = scenarios(config.samples, 40, config.seed);
    }

    private List<Probe> probes(List<PaperCard> cards) {
        Map<String, Probe> grouped = new TreeMap<>();
        for (PaperCard card : cards) {
            var profile = reader.read(card);
            var cost = reader.read(card).castingCost();
            if (card.getMainFace().getType().isLand() || profile.pureMana()
                    || !cost.known || cost.cmc < 1 || cost.cmc > config.horizon || cost.pips.size() > 10) {
                continue;
            }
            String key = cost.toString();
            Probe old = grouped.get(key);
            grouped.put(key, new Probe(cost, cost.cmc, old == null ? 1 : old.weight + 1));
        }
        return List.copyOf(grouped.values());
    }

    // Match the seed's resource signatures first. Identical physical copies are interchangeable;
    // new nonmana names cannot relocate an unchanged source or change the scenario bank.
    List<Card> slots(List<PaperCard> cards) {
        if (cards.size() != 40) { throw new IllegalArgumentException("Expected 40 slots"); }
        List<Card> remaining = new ArrayList<>();
        cards.forEach(c -> remaining.add(reader.read(c).resource()));
        List<Card> result = new ArrayList<>(Collections.nCopies(40, null));
        for (int i = 0; i < 40; i++) {
            int at = remaining.indexOf(reference.get(i));
            if (at >= 0) { result.set(i, remaining.remove(at)); }
        }
        remaining.sort(Comparator.comparing(Card::id));
        int at = 0;
        for (int i = 0; i < 40; i++) { if (result.get(i) == null) { result.set(i, remaining.get(at++)); } }
        return List.copyOf(result);
    }

    // Keep newly accepted sources at their previous physical slots in the next round.
    // Cache keys contain the complete slot vector, so immutable cached scenarios remain valid.
    void anchor(List<PaperCard> current) {
        reference = slots(current);
    }

    List<Card> genericPath(List<Card> slots, PaperCard card) {
        Card resource = reader.read(card).resource();
        int at = slots.indexOf(resource);
        return at < 0 ? slots : genericSlot(slots, at);
    }

    // Neutralize the changed physical slot, not an arbitrary equal copy of a basic land.
    // This keeps a Forest -> Plains swap's generic-only configurations exactly identical.
    GenericPair genericSwap(List<Card> before, List<Card> after) {
        int changed = -1;
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                if (changed >= 0) { throw new IllegalStateException("unstable_swap_slots"); }
                changed = i;
            }
        }
        return changed < 0 ? new GenericPair(before, after)
                : new GenericPair(genericSlot(before, changed), genericSlot(after, changed));
    }

    private List<Card> genericSlot(List<Card> slots, int at) {
        Card resource = slots.get(at);
        if (resource.source == null) { return slots; }
        List<Card> result = new ArrayList<>(slots);
        result.set(at, resource(resource.cost, resource.land, resource.creature, resource.tapped,
                new Source(resource.source.activation, Collections.nCopies(resource.source.units.size(), 0))));
        return List.copyOf(result);
    }

    Evaluation evaluate(List<Card> slots, boolean full) {
        if (closed) { throw new IllegalStateException("Diagnostic context closed"); }
        CacheKey key = new CacheKey(model, List.copyOf(slots));
        Evaluation result = cache.get(key);
        if (result != null) {
            cacheHits++;
        } else {
            cacheMisses++;
            long[][] hits = new long[queries.size()][(config.samples + 63) / 64];
            for (int s = 0; s < config.samples; s++) {
                double[] values = simulate(slots, queries, scenarios[s], config.onDraw);
                for (int p = 0; p < values.length; p++) {
                    if (values[p] != 0) { hits[p][s / 64] |= 1L << (s % 64); }
                }
            }
            String id = digest(key.toString());
            long estimate = 512L + 128L * slots.size() + key.toString().length() * 2L
                    + (long) queries.size() * ((config.samples + 63) / 64) * Long.BYTES;
            result = new Evaluation(id, hits, estimate, config.samples);
            track(unique, id);
            if (estimate <= config.cacheBytes) {
                while (!cache.isEmpty() && (cache.size() >= config.cacheEntries || bytes + estimate > config.cacheBytes)) {
                    var first = cache.entrySet().iterator();
                    var removed = first.next().getValue();
                    bytes -= removed.bytes;
                    payload -= removed.payload;
                    first.remove();
                    evictions++;
                }
                cache.put(key, result);
                bytes += estimate;
                payload += result.payload;
                peakBytes = Math.max(peakBytes, bytes);
                peakPayload = Math.max(peakPayload, payload);
                peakEntries = Math.max(peakEntries, cache.size());
            }
        }
        if (full) { track(fullUnique, result.id); }
        return result;
    }

    private void track(Set<String> set, String id) {
        if (set.size() < 4096) { set.add(id); } else if (!set.contains(id)) { countLimited = true; }
    }

    Delta difference(Evaluation before, Evaluation after) {
        return difference(before, after, null, null);
    }
    Delta fixingDifference(Evaluation before, Evaluation after, Evaluation genericBefore, Evaluation genericAfter) {
        return difference(before, after, genericBefore, genericAfter);
    }
    private Delta difference(Evaluation before, Evaluation after, Evaluation genericBefore, Evaluation genericAfter) {
        int weight = Arrays.stream(demandWeights).sum();
        double sum = 0, squares = 0;
        for (int s = 0; s < config.samples; s++) {
            double value = 0;
            for (int p = 0; p < queries.size(); p++) {
                int a = (int) ((after.hits[p][s / 64] >>> (s % 64)) & 1);
                int b = (int) ((before.hits[p][s / 64] >>> (s % 64)) & 1);
                int generic = genericBefore == null ? 0
                        : (int) ((genericAfter.hits[p][s / 64] >>> (s % 64)) & 1)
                        - (int) ((genericBefore.hits[p][s / 64] >>> (s % 64)) & 1);
                value += demandWeights[p] * (a - b - generic);
            }
            value /= weight;
            sum += value;
            squares += value * value;
        }
        double mean = sum / config.samples;
        double se = Math.sqrt(Math.max(0, (squares - sum * sum / config.samples)
                / (config.samples - 1) / config.samples));
        return new Delta(mean, se, mean - 1.96 * se, mean + 1.96 * se);
    }

    Map<String, Object> coverage(List<PaperCard> cards) {
        List<Object> omitted = new ArrayList<>(), excludedCosts = new ArrayList<>();
        int confirmed = 0;
        for (PaperCard card : cards) {
            var p = reader.read(card);
            if (p.resource().source != null) { confirmed++; }
            if (p.potentialMana() && !p.unknown().isEmpty()) {
                omitted.add(ChaosReportFile.fields("printing", ChaosDraftReport.printing(card),
                        "reasons", p.unknown(), "confirmedPath", p.resource().source));
            }
            var cost = reader.read(card).castingCost();
            if (!card.getMainFace().getType().isLand() && !p.pureMana()
                    && (!cost.known || cost.cmc > config.horizon || cost.cmc == 0)) {
                excludedCosts.add(ChaosReportFile.fields("printing", ChaosDraftReport.printing(card), "cost", cost));
            }
        }
        return ChaosReportFile.fields("confirmedSources", confirmed, "omittedPaths", omitted,
                "excludedCosts", excludedCosts, "interpretation", "confirmed_paths_only_not_card_utility");
    }
    Map<String, Object> facts(PaperCard card) { return reader.read(card).facts(); }
    Object workload() { return workload; }
    Object queries() { return queries; }
    Stats stats() { return new Stats(cacheHits, cacheMisses, unique.size(), fullUnique.size(), countLimited,
            evictions, cache.size(), peakEntries, bytes, peakBytes, payload, peakPayload); }
    @Override public void close() { cache.clear(); unique.clear(); fullUnique.clear(); reader.clear(); bytes = 0; payload = 0; closed = true; }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }
    private static int[][] scenarios(int n, int size, long seed) {
        int[][] result = new int[n][size];
        for (int k = 0; k < n; k++) {
            var rng = new SplittableRandom(mix(seed + k));
            int[] order = result[k];
            for (int i = 0; i < size; i++) { order[i] = i; }
            for (int i = size - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1), v = order[i];
                order[i] = order[j]; order[j] = v;
            }
        }
        return result;
    }
    private static int need(int mask, List<Probe> probes) {
        int n=0;
        for(var p:probes)for(int pip:p.cost.pips)if((mask&pip)!=0)n+=p.weight;
        return n;
    }
    private static int relevance(Card c, List<Probe> p) {
        if(c.source==null)return 0;
        int n=0;
        for(int mask:c.source.units)n+=need(mask, p);
        return n;
    }
    // Exact pip matching for one cost. Choice mana is ONE unit; fixed signet outputs are separate units.
    private static Payment pay(List<Unit> units, Cost cost, List<Probe> roster) {
        if(units.size()>48)throw new IllegalStateException("payment_units_limit");
        if(!cost.known||units.size()<cost.generic+cost.pips.size())return null;
        Integer[] order=new Integer[cost.pips.size()];
        for(int i=0; i<order.length; i++)order[i]=i;
        Arrays.sort(order, Comparator.comparingInt(i-> {
            int n=0;
            for(var u:units)if((u.mask&cost.pips.get(i))!=0)n++;
            return n;
        }));
        boolean[] used=new boolean[units.size()];
        if(!match(0, order, cost, units, used))return null;
        var generic=new ArrayList<Integer>();
        for(int i=0; i<used.length; i++)if(!used[i])generic.add(i);
        generic.sort(Comparator.<Integer>comparingInt(i->need(units.get(i).mask, roster)).thenComparingInt(i->Integer.bitCount(units.get(i).mask)));
        for(int i=0; i<cost.generic; i++)used[generic.get(i)]=true;
        List<Unit> left=new ArrayList<>();
        Set<Integer> parents=new HashSet<>();
        for(int i=0; i<used.length; i++)if(used[i]) {
            if(units.get(i).parent>=0)parents.add(units.get(i).parent);
        }else left.add(units.get(i));
        // Tapping a multi-output source also floats its unspent sibling units.
        left.replaceAll(u->parents.contains(u.parent)?new Unit(u.mask, -1):u);
        return new Payment(left, parents);
    }
    private static boolean match(int j, Integer[] order, Cost c, List<Unit> units, boolean[] used) {
        if(j==order.length)return true;
        int pip=c.pips.get(order[j]);
        Set<Integer> tried=new HashSet<>();
        for(int i=0; i<units.size(); i++)if(!used[i]&&(pip&units.get(i).mask)!=0&&tried.add(units.get(i).mask)) {
            used[i]=true;
            if(match(j+1, order, c, units, used))return true;
            used[i]=false;
        }
        return false;
    }
    static Cost generic(int n) {
        return new Cost(n, List.of(), n, true, Integer.toString(n));
    }
    static final class Development {
        final List<Card> cards;
        final boolean[] on, tapped;
        final int[] ready;
        List<Unit> floating=new ArrayList<>();
        int turn;
        int fuel;
        Development(List<Card> c, int fuel) {
            this.fuel=fuel;
            cards=c;
            on=new boolean[c.size()];
            tapped=new boolean[c.size()];
            ready=new int[c.size()];
        }
        List<Unit> free() {
            List<Unit> u=new ArrayList<>(floating);
            for(int i=0; i<cards.size(); i++) {
                var c=cards.get(i);
                if(on[i]&&!tapped[i]&&ready[i]<=turn&&c.source!=null&&c.source.activation==0)for(int mask:c.source.units)u.add(new Unit(mask, i));
            }
            return u;
        }
        List<Integer> activated() {
            List<Integer> r=new ArrayList<>();
            for(int i=0; i<cards.size(); i++)if(on[i]&&!tapped[i]&&ready[i]<=turn&&cards.get(i).source!=null&&cards.get(i).source.activation>0)r.add(i);
            return r;
        }
        void enter(int i) {
            on[i]=true;
            ready[i]=turn+((cards.get(i).creature||cards.get(i).tapped)?1:0);
        }
    }
    record Funding(Payment payment, Set<Integer> activated) {
    }
    static Funding fund(Development b, Cost cost, List<Probe> probes) {
        return fund0(b, 0, b.activated(), b.free(), new HashSet<>(), cost, probes);
    }
    private static Funding fund0(Development b, int at, List<Integer> rocks, List<Unit> u, Set<Integer> act, Cost cost, List<Probe> p) {
        if(--b.fuel<0)throw new IllegalStateException("scenario_node_budget");
        Payment direct=pay(u, cost, p);
        if(direct!=null)return new Funding(direct, act);
        if(at==rocks.size())return null;
        // Enumerate whether to activate. Generic activation cannot be paid using its own future output.
        for(int pos=at; pos<rocks.size(); pos++) {
            int slot=rocks.get(pos);
            var src=b.cards.get(slot).source;
            // Activation order and alternate generic assignments both matter; each source is used at most once.
            var ordered=new ArrayList<>(rocks);
            Collections.swap(ordered, at, pos);
            for(var paid:genericPayments(u, src.activation)) {
                var next=new ArrayList<>(paid.remainder);
                for(int mask:src.units)next.add(new Unit(mask, slot));
                var a=new HashSet<>(act);
                a.add(slot);
                a.addAll(paid.used);
                Funding f=fund0(b, at+1, ordered, next, a, cost, p);
                if(f!=null)return f;
            }
        }
        return null;
    }
    private static List<Payment> genericPayments(List<Unit> u, int n) {
        List<Payment> out=new ArrayList<>();
        comb(u, n, 0, new boolean[u.size()], out, new HashSet<>());
        return out;
    }
    private static void comb(List<Unit> u, int n, int from, boolean[] used, List<Payment> out, Set<String> seen) {
        if(n==0) {
            List<Unit> left=new ArrayList<>();
            Set<Integer> parents=new HashSet<>();
            for(int i=0; i<u.size(); i++)if(used[i]) {
                if(u.get(i).parent>=0)parents.add(u.get(i).parent);
            }else left.add(u.get(i));
            left.replaceAll(x->parents.contains(x.parent)?new Unit(x.mask, -1):x);
            String k=left.stream().map(x->x.mask).sorted().toList().toString();
            if(seen.add(k))out.add(new Payment(left, parents));
            return;
        }
        for(int i=from; i<=u.size()-n; i++) {
            used[i]=true;
            comb(u, n-1, i+1, used, out, seen);
            used[i]=false;
        }
    }
    static void commit(Development b, Funding f) {
        for(int i:f.payment.used)b.tapped[i]=true;
        for(int i:f.activated)b.tapped[i]=true;
        b.floating=new ArrayList<>();
        for(var u:f.payment.remainder)if(u.parent<0||b.tapped[u.parent])b.floating.add(new Unit(u.mask, -1));
    }
    private static void witness(Development b, List<Probe> probes, List<Probe> workload, double[] hit) {
        for(int j=0; j<probes.size(); j++)if(hit[j]==0&&b.turn<=probes.get(j).deadline&&fund(b, probes.get(j).cost, workload)!=null)hit[j]=1;
    }
    private static double[] landPriority(Development b, int slot, List<Probe> probes) {
        b.enter(slot);
        double now=0, future=0;
        for(var p:probes)if(p.deadline>=b.turn&&fund(b, p.cost, probes)!=null)now+=p.weight;
        int old=b.ready[slot];
        b.ready[slot]=b.turn;
        // Coverage of required pips is capped separately for each virtual cost, so repeated Forests do not
        // eclipse an absent Island merely because the deck contains more G than U symbols in total.
        for(var p:probes) {
            if(p.cost.pips.size()==0)continue;
            int maximum=0;
            List<Integer> pips=p.cost.pips;
            for(int subset=1; subset<(1<<pips.size()); subset++) {
                int[] selected=new int[Integer.bitCount(subset)];
                int j=0;
                for(int k=0; k<pips.size(); k++)if((subset&(1<<k))!=0)selected[j++]=pips.get(k);
                if(selected.length>maximum&&fund(b, new Cost(0, Arrays.stream(selected).boxed().toList(), selected.length, true, "pip_coverage"), probes)!=null)maximum=selected.length;
            }
            future+=p.weight*maximum/(double)pips.size();
        }
        b.on[slot]=false;
        b.ready[slot]=old;
        return new double[] {
            now, future, b.cards.get(slot).tapped?0:1
        };
    }
    private static boolean greater(double[] a, double[] b) {
        for(int i=0; i<a.length; i++)if(Math.abs(a[i]-b[i])>1e-9)return a[i]>b[i];
        return false;
    }
    private double[] simulate(List<Card> cards, List<Probe> queries, int[] order, boolean onDraw) {
        Development b=new Development(cards, config.maxNodesPerScenario);
        boolean[] hand=new boolean[cards.size()];
        int drawn=0;
        for(; drawn<7; drawn++)hand[order[drawn]]=true;
        double[] hit=new double[queries.size()];
        for(int t=1; t<=config.horizon; t++) {
            b.turn=t;
            Arrays.fill(b.tapped, false);
            b.floating.clear();
            if((t>1||onDraw)&&drawn<order.length)hand[order[drawn++]]=true;
            // Fixed lexicographic policy; no access to undrawn order or card ratings.
            int land=-1;
            double[] priority=null;
            Set<String> checked=new HashSet<>();
            for(int i=0; i<cards.size(); i++)if(hand[i]&&cards.get(i).land&&cards.get(i).source!=null&&checked.add(cards.get(i).id)) {
                double[] candidate=landPriority(b, i, workload);
                if(land<0||greater(candidate, priority)) {
                    land=i;
                    priority=candidate;
                }
            }
            if(land>=0) {
                b.enter(land);
                hand[land]=false;
            }
            witness(b, queries, workload, hit);
            for(int step=0; step<cards.size(); step++) {
                int best=-1;
                Funding payment=null;
                for(int i=0; i<cards.size(); i++) {
                    Card c=cards.get(i);
                    if(!hand[i]||c.land||c.source==null)continue;
                    Funding f=fund(b, c.cost, workload);
                    if(f==null)continue;
                    if(best<0||c.cost.cmc<cards.get(best).cost.cmc||c.cost.cmc==cards.get(best).cost.cmc&&relevance(c, workload)>relevance(cards.get(best), workload)) {
                        best=i;
                        payment=f;
                    }
                }
                if(best<0)break;
                commit(b, payment);
                b.enter(best);
                hand[best]=false;
                witness(b, queries, workload, hit);
            }
        }
        return hit;
    }
}
