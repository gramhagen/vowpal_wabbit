package vowpalWabbit.slim;

import vowpalWabbit.slim.input.Feature;
import vowpalWabbit.slim.input.FeatureInterface;
import vowpalWabbit.slim.input.Namespace;
import vowpalWabbit.slim.input.PredictionRequest;

import java.io.*;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.DoubleUnaryOperator;
import java.util.zip.GZIPInputStream;

/**
 * Reades Vowpal Wabbit --readable_model file and creates a weights array
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
 * ReadableModel m = new ReadableModel(new File("/tmp/readable_model.txt"));
 * float[] p = m.predict(new PredictionRequest(new Namespace("ns", new Feature("a"), new Feature("c",3)));
 * System.out.println(Arrays.toString(p));
 *
 * </pre>
 *
 * ReadableModel is thread safe, so make sure to reuse it between threads
 */
public class ReadableModel {
  public VWModel model;

  private String getSecondValue(String s) {
    String[] splitted = s.split(":");
    if (splitted.length == 1) {
      return "";
    }
    return splitted[1].trim();
  }

  private int intOrZero(String s) {
    if (s.equals("")) {
      return 0;
    }
    return Integer.parseInt(s);
  }

  private InputStream getReaderForExt(File f) throws IOException {
    if (f.toString().endsWith(".gz")) {
      FileInputStream fin = new FileInputStream(f);
      InputStream gzipStream = new GZIPInputStream(fin);
      return gzipStream;
    } else {
      return new FileInputStream(f);
    }
  }

  private File findFileWithExt(File root, String name) {
    File x = Paths.get(root.toString(), name + ".gz").toFile();
    if (x.exists()) {
      return x;
    }
    return Paths.get(root.toString(), name).toFile();
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
   * @param file the vw --readable_model file.txt, also supports .gz and will
   *             automatically decompress
   * @throws IOException                   if there is a problem with the reading
   * @throws UnsupportedOperationException if the model was built with options we
   *                                       dont support yet
   */
  public void loadReadableModel(File file) throws IOException, UnsupportedOperationException {
    InputStream is = getReaderForExt(file);
    loadReadableModel(is);
  }

  public void loadReadableModel(InputStream is) throws IOException, UnsupportedOperationException {
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    model = new VWModel();
    boolean inHeader = true;
    float[] weights = null;
    // TODO: more robust parsing
    try {
      String line;
      while ((line = br.readLine()) != null) {
        if (inHeader) {
          if (line.equals(":0")) {
            inHeader = false;
          }
          if (line.contains("bits:")) {
            int bits = Integer.parseInt(getSecondValue(line));
            model.setNumBits(bits);
            weights = new float[(1 << bits)];
          }
          if (line.contains("Min label")) {
            model.setMinLabel(Float.parseFloat(getSecondValue(line)));
          }
          if (line.contains("Max label")) {
            model.setMaxLabel(Float.parseFloat(getSecondValue(line)));
          }
          if (line.contains("ngram")) {
            int ngram = intOrZero(getSecondValue(line));
            if (ngram != 0) {
              throw new UnsupportedOperationException("ngrams are not supported yet");
            }
          }
          if (line.contains("skip")) {
            int skip = intOrZero(getSecondValue(line));
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
    } finally {
      br.close();
    }

    if (weights == null) {
      throw new UnsupportedOperationException("failed to load the model, did not see 'bits:' line");
    }
    model.setWeights(weights);
  }

  /**
   * Takes a file or directory and loads the model.
   *
   * <p>
   * If you pass a directory as input it will look for 3 files
   *
   * <ul>
   * <li>readable_model.txt
   * <li>test.txt
   * <li>predictions.txt
   * </ul>
   *
   * If test.txt and predictions.txt exists it will automatically run
   * makeSureItWorks() (or test.txt.gz, predictions.txt.gz readable_model.txt.gz)
   *
   * <p>
   * If you pass a file it will just load the model
   *
   * @param root          file or directory to read from
   * @param hasIntercept  model was built without --nocache option
   * @param probabilities if file is directory and predictions.txt exist, test
   *                      there with normalized probabilities
   * @throws IOException                   if reading fails
   * @throws UnsupportedOperationException if the model was built with options we
   *                                       dont support yet
   */
  public ReadableModel(File root, boolean hasIntercept, boolean probabilities)
      throws IOException, UnsupportedOperationException, ModelParserException {

    if (root.isDirectory()) {
      File model = findFileWithExt(root, "readable_model.txt");
      File test = findFileWithExt(root, "test.txt");
      File predictions = findFileWithExt(root, "predictions.txt");
      File binaryModelFile = findFileWithExt(root, "model.bin");

      VWModel binaryModel = BinaryModel.Parse(new FileInputStream(binaryModelFile));
      loadReadableModel(model);

      if (test.exists() && predictions.exists()) {
        makeSureItWorks(this.model, test, predictions, probabilities);
        makeSureItWorks(binaryModel, test, predictions, probabilities);
      }
    } else {
      loadReadableModel(root);
    }
  }

  public ReadableModel(InputStream is) throws IOException, UnsupportedOperationException {
    loadReadableModel(is);
  }

  public ReadableModel(URL root, boolean hasIntercept)
      throws IOException, UnsupportedOperationException, ModelParserException {
    this(new File(root.getFile()), hasIntercept);
  }

  public ReadableModel(File root, boolean hasIntercept)
      throws IOException, UnsupportedOperationException, ModelParserException {
    this(root, hasIntercept, false);
  }

  public ReadableModel(URL root) throws IOException, UnsupportedOperationException, ModelParserException {
    this(new File(root.getFile()), true, false);
  }

  public ReadableModel(File root) throws IOException, UnsupportedOperationException, ModelParserException {
    this(root, true, false);
  }

  // TODO: restructure
  public float[] predict(PredictionRequest input) {
    return model.predict(input);
  }

  public float[] predict(PredictionRequest input, Explanation explain) {
    return model.predict(input, explain);
  }

  public int featureHashOf(int mmNamespaceHash, FeatureInterface feature) {
    return model.featureHashOf(mmNamespaceHash, feature);
  }

  /**
   * read the test file and pred file and try to do the same predictions
   *
   * @param testInputStream        input stream with one example per line
   * @param predictionsInputStream input stream with output from vw -t -i model
   *                               --predictions -r
   * @param probabilities          predictions.txt contains probabilities
   * @throws IOException           if file reading fails
   * @throws IllegalStateException if predictions mismatch
   */
  public void makeSureItWorks(VWModel newModel, InputStream testInputStream, InputStream predictionsInputStream,
      boolean probabilities) throws IOException, IllegalStateException {
    BufferedReader brTest = new BufferedReader(new InputStreamReader(testInputStream));
    BufferedReader brPred = new BufferedReader(new InputStreamReader(predictionsInputStream));

    int lineNum = 0;
    try {
      String testLine;
      String predLine;

      while ((testLine = brTest.readLine()) != null && ((predLine = brPred.readLine()) != null)) {
        String[] test = testLine.split("\\s+");
        PredictionRequest predictionRequest = new PredictionRequest();
        predictionRequest.probabilities = probabilities;
        boolean hasNamespace = false;
        for (int i = 0; i < test.length; i++) {
          // label |ns f:value f f f \ns
          if (test[i].startsWith("|")) {
            hasNamespace = true;
            String ns = test[i].replaceFirst("\\|", "");
            predictionRequest.namespaces.add(new Namespace(ns));
          } else if (hasNamespace) {

            float weight = 1;
            String feature = test[i];
            if (test[i].contains(":")) {
              String[] s = test[i].split(":");
              feature = s[0];
              weight = Float.parseFloat(s[1]);
            }

            predictionRequest.namespaces.get(predictionRequest.namespaces.size() - 1).features
                .add(new Feature(feature, weight));
          }
        }
        float[] ourPrediction = newModel.predict(predictionRequest);

        // ran with --probabilities for -oaa
        if (predLine.contains(":")) {
          String[] perKlass = predLine.split(" ");
          for (int i = 0; i < perKlass.length; i++) {
            String[] kv = perKlass[i].split(":");
            int index = Integer.parseInt(kv[0]) - 1;
            float pred = Float.parseFloat(kv[1]);

            if (Math.abs(pred - ourPrediction[index]) > 0.01) {
              throw new IllegalStateException(String.format(
                  "line: %d index %d, prediction: %f, ourPrediction: %f \noaa %s,\npred line: %s\ntest line: %s",
                  lineNum, index, pred, ourPrediction[index], Arrays.toString(ourPrediction), predLine, testLine));
            }
          }
        } else {
          float pred = Float.parseFloat(predLine);

          if (Math.abs(pred - ourPrediction[0]) > 0.01) {
            throw new IllegalStateException(String.format(
                "line: %d index %d, prediction: %f, ourPrediction: %f \noaa %s,\npred line: %s\ntest line: %s", lineNum,
                0, pred, ourPrediction[0], Arrays.toString(ourPrediction), predLine, testLine));
          }
        }
        lineNum++;
      }
    } finally {
      brPred.close();
      brTest.close();
    }
  }

  /**
   * read the test file and pred file and try to do the same predictions
   *
   * @param testFile      file with one example per line
   * @param predFile      file output from vw -t -i model --predictions -r
   * @param probabilities predictions.txt contains probabilities
   * @throws IOException           if file reading fails
   * @throws IllegalStateException if predictions mismatch
   */
  public void makeSureItWorks(VWModel newModel, File testFile, File predFile, boolean probabilities)
      throws IOException, IllegalStateException {
    /* to make sure we predict the same values as VW */

    InputStream isTest = getReaderForExt(testFile);
    InputStream isPred = getReaderForExt(predFile);
    makeSureItWorks(newModel, isTest, isPred, probabilities);
  }

}
