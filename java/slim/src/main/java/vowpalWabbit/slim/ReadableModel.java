package vowpalWabbit.slim;

import java.io.*;

/**
 * Reads Vowpal Wabbit --readable_model file and creates a weights array
 * containing the weight per bucket then using same hashing of vw finds the
 * correct bucket for the input features and computes the inner product.
 *
 * <p>
 * check out this <a href=
 * "https://gist.github.com/luoq/b4c374b5cbabe3ae76ffacdac22750af">gist</a> and
 * <a href=
 * "https://github.com/JohnLangford/vowpal_wabbit/wiki/Feature-Hashing-and-Extraction">Feature-Hashing-and-Extraction</a>
 * for more information
 *
 * <p>
 * Example:
 *
 * <pre>
 * execute: echo "1 |ns a b c:4" | vw --readable_model /tmp/readable_model.txt
 *
 *
 *
 * VWModel m = ReadableModel.Parse(new File("/tmp/readable_model.txt"));
 * float[] p = m.predict(new PredictionRequest(new Namespace("ns", new Feature("a"), new Feature("c",3)));
 * System.out.println(Arrays.toString(p));
 *
 * </pre>
 *
 */
public class ReadableModel {

  private static String getValue(String s, String backup) {
    String[] splitted = s.split(":");
    if (splitted.length == 1) {
      return backup;
    }
    return splitted[1].trim();
  }

  private static String getValue(String s) {
    return getValue(s, "");
  }


  /**
   * Loads the model from a file output from vw --readable_file. The contents of
   * the file looks like this:
   *
   * <pre>
   *   Version 8.6.1
   *   Id
   *   Min label:0
   *   Max label:3
   *   bits:18
   *   lda:0
   *   1 ngram:2
   *   0 skip:
   *   options: --hash_seed 0 --link identity
   *   Checksum: 3984224786
   *   :0
   *   116060:0.532933
   *   155256:0.192113
   *   213375:0.390151
   *   218329:0.158008
   *   250698:0.192113
   *   259670:0.343652
   * </pre>
   *
   * <b>155256:0.192113</b> is hash bucket:weight, we use the same hashing
   * algorithm as vw to find the features in the model
   *
   * @param inputStream the vw --readable_model file as an InputStream
   * @throws IOException if there is a problem with the reading
   * @throws UnsupportedOperationException if the model was built with options we dont support yet
   */
  static VWModel Parse(InputStream inputStream) throws IOException, UnsupportedOperationException {
    // TODO: more robust parsing
    float[] weights = null;
    VWModel model = new VWModel();
    boolean inHeader = true;
    try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
      String line;
      while ((line = br.readLine()) != null) {
        if (inHeader) {
          if (line.equals(":0")) {
            inHeader = false;
          }
          if (line.contains("bits:")) {
            int bits = Integer.parseInt(getValue(line));
            model.setNumBits(bits);
            weights = new float[(1 << bits)];
          }
          if (line.contains("Min label")) {
            model.setMinLabel(Float.parseFloat(getValue(line)));
          }
          if (line.contains("Max label")) {
            model.setMaxLabel(Float.parseFloat(getValue(line)));
          }
          if (line.contains("ngram")) {
            int ngram = Integer.parseInt(getValue(line, "0"));
            if (ngram != 0) {
              throw new UnsupportedOperationException("ngrams are not supported yet");
            }
          }
          if (line.contains("skip")) {
            int skip = Integer.parseInt(getValue(line, "0"));
            if (skip != 0) {
              throw new UnsupportedOperationException("skip is not supported yet");
            }
          }

          if (line.contains("options")) {
            model.setCommandLineArguments(line.split(":", 2)[1]);
          }
        } else {
          String[] v = line.split(":");
          int bucket = Integer.parseInt(v[0]);
          float w = Float.parseFloat(v[1]);
          weights[bucket] = w;
        }
      }
    }

    if (weights == null) {
      throw new UnsupportedOperationException("failed to load the model, did not see 'bits:' line");
    }
    model.setWeights(weights);
    return model;
  }
}
