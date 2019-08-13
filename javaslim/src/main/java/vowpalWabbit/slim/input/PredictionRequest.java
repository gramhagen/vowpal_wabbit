package vowpalWabbit.slim.input;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Document to predict on Example
 *
 * <pre>
 * model.predict(
 *     new PredictionRequest(new Namespace("a", new Feature("x"), new Feature("y", 0.5), new Feature("z", 0.3))));
 *
 * </pre>
 */
public class PredictionRequest implements Serializable {
  private static final long serialVersionUID = 1L;

  public List<Namespace> namespaces;

  /** request output to be with normalized probabilities */
  public boolean probabilities = false;

  public PredictionRequest() {
    namespaces = new ArrayList<>();
  }

  public PredictionRequest(Namespace... nss) {
    this.namespaces = Arrays.asList(nss);
  }

  public PredictionRequest(String inputString) {
    this();
    boolean hasNamespace = false;
    String[] test = inputString.split("\\s+");
    for (String value : test) {
      // label |ns f:value f f f \ns
      if (value.startsWith("|")) {
        hasNamespace = true;
        String ns = value.replaceFirst("\\|", "");
        this.namespaces.add(new Namespace(ns));
      } else if (hasNamespace) {
        float weight = 1;
        String feature = value;
        if (value.contains(":")) {
          String[] s = value.split(":");
          feature = s[0];
          weight = Float.parseFloat(s[1]);
        }

        this.namespaces
            .get(this.namespaces.size() - 1)
            .features
            .add(new Feature(feature, weight));
      }
    }
  }

  public PredictionRequest(String inputString, boolean probabilities) {
    this(inputString);
    this.probabilities = probabilities;
  }
}
