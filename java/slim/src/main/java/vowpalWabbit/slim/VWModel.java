package vowpalWabbit.slim;

import java.util.function.DoubleUnaryOperator;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.HashSet;
import vowpalWabbit.slim.input.*;

public class VWModel {
    private static final int intercept = 11650396;
    private final int FNV_prime = 16777619;

    private String id;
    private String commandLineArguments;
    private String interactions;
    private boolean[] ignoreLinear;
    private float[] weights;
    private int strideShift;
    private String version;

    private float minLabel;
    private float maxLabel;

    /**
     * This is the actual model of size 2**bits if you build something with vw -b 18
     * it will be of size 262144
     */
    private int numBits;

    private int oaa = 1;
    private int mask = 0;
    private int multiClassBits = 0;
    private int seed = 0;
    private boolean hashAll = false;

    private boolean hasIntercept = true;

    private Map<Character, Set<Character>> quadratic = new HashMap<>();
    private boolean quadraticAnyToAny = false;

    private DoubleUnaryOperator identity = DoubleUnaryOperator.identity();
    private DoubleUnaryOperator logistic = (o) -> (1. / (1. + Math.exp(-o)));
    private DoubleUnaryOperator glf1 = (o) -> (2. / (1. + Math.exp(-o)) - 1.);
    private DoubleUnaryOperator poisson = (o) -> Math.exp(o);

    private DoubleUnaryOperator link = this.identity;

    private void extractOptions(String o, BiConsumer<String, String> cb) {
        o = o.trim();
        if (o.isEmpty())
            return;
        String[] op = o.split("\\s+");
        for (int i = 0; i < op.length; i++) {
            if (op[i].contains("=")) {
                String[] splitted = op[i].split("=");
                cb.accept(splitted[0], splitted[1]);
            } else {
                cb.accept(op[i], op[i + 1]);
                i++; // skip 2 at a time
            }
        }
    }

    /**
     * @param commandLineArguments the commandLineArguments to set
     */
    public void setCommandLineArguments(String commandLineArguments) {
        this.commandLineArguments = commandLineArguments;

        extractOptions(commandLineArguments, (key, value) -> {
            if (key.equals("--oaa")) {
                oaa = Integer.parseInt(value);

                multiClassBits = 0;
                int ml = oaa - 1;
                while (ml > 0) {
                    multiClassBits++;
                    ml >>= 1;
                }
            }
            if (key.equals("--cubic")) {
                throw new UnsupportedOperationException("we do not support --cubic yet");
            }
            if (key.equals("--link")) {
                switch (value) {
                case "logistic":
                    this.link = this.logistic;
                    break;
                case "identity":
                    this.link = this.identity;
                    break;
                case "poisson":
                    this.link = this.poisson;
                    break;
                case "glf1":
                    this.link = this.glf1;
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "only --link identity, logistic, glf1, or poisson are supported " + value);
                }
            }
            if (key.equals("--noconstant")) {
                hasIntercept = false;
            }

            if (key.equals("--hash_seed")) {
                seed = Integer.parseInt(value);
            }
            if (key.equals("--hash")) {
                if (value.equals("all")) {
                    hashAll = true;
                }
            }
            if (key.equals("--quadratic") || key.equals("-q") || key.equals("--cubic")
                    || key.equals("--interactions")) {
                if (value.equals("::")) {
                    // TODO: the way we do permutation differs from the way we do permutations
                    // TODO: the results will differ
                    /*
                     * 
                     * from vw echo '1 |aa x:1 y:2 ' | vw -f x.bin -a -q :: 2>&1 | grep Constant |
                     * tr "\t" "\n" | sort aa^x*aa^x:113732:1:0@0 aa^x*aa^y:189809:2:0@0
                     * aa^x:63954:1:0@0 aa^y*aa^y:125762:4:0@0 aa^y:237799:2:0@0
                     * 
                     * and we generate aa^x:63954:1:0.000000 aa^y:237799:1:0.000000
                     * aa^x*aa^x:113732:1:0.000000 aa^x*aa^y:189809:1:0.000000
                     * aa^y*aa^x:176759:1:0.000000 aa^y*aa^y:125762:1:0.000000
                     */
                    quadraticAnyToAny = true;
                } else {
                    quadratic.computeIfAbsent(value.charAt(0), k -> new HashSet<>()).add(value.charAt(1));
                }
            }
            // TODO: --cubic
            // TODO: ngrams, skips
            // TODO: lda
        });

    }

    private int getBucket(int featureHash, int klass) {
        return ((featureHash << multiClassBits) | klass) & mask;
    }

    /**
     * @param mmNamespaceHash the namespace hash VWMurmur.hash(namespace, seed)
     *                        where seed is usually 0 unless you pass --hash_seed to
     *                        vw
     * @param feature         the feature to compute hash of
     * @return the hash of the feature according to vw
     *         <p>
     *         check out
     *         https://github.com/JohnLangford/vowpal_wabbit/blob/579c34d2d2fd151b419bea54d9921fc7f3f55bbc/vowpalwabbit/parse_primitives.cc#L48
     */
    public int featureHashOf(int mmNamespaceHash, FeatureInterface feature) {
        if (hashAll) {
            return VWMurmur.hash(feature.getStringName(), mmNamespaceHash);
        } else {
            if (feature.hasIntegerName())
                return feature.getIntegerName() + mmNamespaceHash;
            return VWMurmur.hash(feature.getStringName(), mmNamespaceHash);
        }
    }

    /**
     * really usefull if you want to score a list of items and dont want to be in
     * the mercy of escape analysis
     *
     * @return float array with enough elements to hold one prediction per class
     */
    public float[] getReusableFloatArray() {
        return new float[oaa];
    }

    /**
     * @param input PredictionRequest to evaluate
     * @return prediction per class
     */
    public float[] predict(PredictionRequest input) {
        return predict(input, null);
    }

    /**
     * @param input   PredictionRequest to evaluate
     * @param explain Explanation if you want to get some debug information about
     *                the prediction query
     * @return prediction per class
     */
    public float[] predict(PredictionRequest input, Explanation explain) {
        float[] out = getReusableFloatArray();
        predict(out, input, explain);
        return out;
    }

    private void interact(float[] result, Namespace ans, FeatureInterface a, Namespace bns, FeatureInterface b,
            Explanation explain) {
        int fnv = ((a.getComputedHash() * FNV_prime) ^ b.getComputedHash());
        for (int klass = 0; klass < oaa; klass++) {
            int bucket = getBucket(fnv, klass);
            if (explain != null) {
                explain.add(String.format("%s^%s*%s^%s:%d:%d:%f", ans.namespace, a.getStringName(), bns.namespace,
                        b.getStringName(), bucket, klass + 1, weights[bucket]));
                if (weights[bucket] == 0) {
                    explain.missingFeatures.add(1);
                }
                explain.featuresLookedUp.add(1);
            }

            result[klass] += a.getValue() * b.getValue() * weights[bucket];
        }
    }

    /**
     * @param result  place to put result in (@see getReusableFloatArray)
     * @param input   PredictionRequest to evaluate
     * @param explain Explanation if you want to get some debug information about
     *                the prediction query
     */
    public void predict(float[] result, PredictionRequest input, Explanation explain) {
        for (int klass = 0; klass < oaa; klass++)
            result[klass] = 0;

        // TODO: ngrams skips
        // TODO: --cubic hash calculation

        input.namespaces.forEach(n -> {
            if (!n.hashIsComputed) {
                int namespaceHash = n.namespace.length() == 0 ? 0 : VWMurmur.hash(n.namespace, seed);
                n.computedHashValue = namespaceHash;
                n.hashIsComputed = true;
            }

            n.features.forEach(f -> {
                if (!f.isHashComputed()) {
                    int featureHash = featureHashOf(n.computedHashValue, f);
                    f.setComputedHash(featureHash);
                }
                for (int klass = 0; klass < oaa; klass++) {
                    int bucket = getBucket(f.getComputedHash(), klass);
                    if (explain != null) {
                        explain.add(String.format("%s^%s:%d:%d:%f", n.namespace, f.getStringName(), bucket, klass + 1,
                                weights[bucket]));
                        if (weights[bucket] == 0) {
                            explain.missingFeatures.add(1);
                        }
                        explain.featuresLookedUp.add(1);
                    }
                    result[klass] += f.getValue() * weights[bucket];
                }
            });
        });

        if (quadratic.size() > 0 || quadraticAnyToAny) {

            // foreach namespace nsA
            // foreach interacting namespaces nsB
            // foreach nsA.features a
            // foreach nsB.feature b
            // bucket = ((a.computedHashValue * FNV_prime) ^ b.computedHashValue);

            if (quadraticAnyToAny) {
                input.namespaces.forEach(ans -> input.namespaces.forEach(bns -> {
                    ans.features.forEach(a -> {
                        bns.features.forEach(b -> {
                            interact(result, ans, a, bns, b, explain);
                        });
                    });
                }));
            } else {
                input.namespaces.forEach(ans -> {
                    Set<Character> interactStartingWith = quadratic.get(ans.namespace.charAt(0));
                    if (interactStartingWith == null)
                        return;
                    interactStartingWith.forEach(inter -> {
                        // instead of building a hash Map<Character, List<Namespace>>
                        // it should be better to just scan the list and see if anything matches
                        input.namespaces.forEach(bns -> {
                            if (bns.namespace.charAt(0) == inter) {
                                ans.features.forEach(a -> {
                                    bns.features.forEach(b -> {
                                        interact(result, ans, a, bns, b, explain);
                                    });
                                });
                            }
                        });
                    });
                });
            }
        }

        if (hasIntercept) {
            for (int klass = 0; klass < oaa; klass++) {
                int bucket = getBucket(intercept, klass);
                if (explain != null) {
                    explain.add(String.format("%s:%d:%d:%f", "Constant", bucket, klass + 1, weights[bucket]));
                    if (weights[bucket] == 0) {
                        explain.missingFeatures.add(1);
                    }
                    explain.featuresLookedUp.add(1);
                }

                result[klass] += weights[bucket];
            }
        }

        if (explain != null) {
            // uncliped unnormalized pred historuy
            for (int klass = 0; klass < oaa; klass++) {
                explain.predictions.add(result[klass]);
            }
        }

        if (input.probabilities) {
            this.clip(result);
            this.linkWith(result, this.logistic);
            if (this.oaa > 1)
                this.normalize(result);
        } else {
            this.clip(result);
            this.linkWith(result, link);
        }
    }

    protected void clip(float[] raw_out) {
        for (int klass = 0; klass < this.oaa; klass++) {
            raw_out[klass] = clip(raw_out[klass]);
        }
    }

    protected float clip(float raw_out) {
        return Math.max(Math.min(raw_out, this.maxLabel), this.minLabel);
    }

    protected void linkWith(float[] out, DoubleUnaryOperator f) {
        for (int klass = 0; klass < this.oaa; klass++) {
            out[klass] = (float) f.applyAsDouble(out[klass]);
        }
    }

    protected void normalize(float[] out) {
        float sum = 0;
        for (float o : out)
            sum += o;
        for (int klass = 0; klass < this.oaa; klass++) {
            out[klass] = out[klass] / sum;
        }
    }

    /**
     * @param mask the mask to set
     */
    public void setMask(int mask) {
        this.mask = mask;
    }

    /**
     * @param numBits the numBits to set
     */
    public void setNumBits(int numBits) {
        this.numBits = numBits;
        this.mask = (1 << numBits) - 1;
    }

    /**
     * @return the numBits
     */
    public int getNumBits() {
        return numBits;
    }

    /**
     * @param maxLabel the maxLabel to set
     */
    public void setMaxLabel(float maxLabel) {
        this.maxLabel = maxLabel;
    }

    /**
     * @param minLabel the minLabel to set
     */
    public void setMinLabel(float minLabel) {
        this.minLabel = minLabel;
    }

    /**
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * @param version the version to set
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * @param ignoreLinear the ignoreLinear to set
     */
    public void setIgnoreLinear(boolean[] ignoreLinear) {
        this.ignoreLinear = ignoreLinear;
    }

    /**
     * @param interactions the interactions to set
     */
    public void setInteractions(String interactions) {
        this.interactions = interactions;
    }

    /**
     * @param strideShift the strideShift to set
     */
    public void setStrideShift(int strideShift) {
        this.strideShift = strideShift;
    }

    /**
     * @param weights the weights to set
     */
    public void setWeights(float[] weights) {
        this.weights = weights;
    }

    /**
     * @return the commandLineArguments
     */
    public String getCommandLineArguments() {
        return commandLineArguments;
    }

    /**
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * @param id the id to set
     */
    public void setId(String id) {
        this.id = id;
    }
}