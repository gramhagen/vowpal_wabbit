package vowpalWabbit.slim;

import vowpalWabbit.slim.input.PredictionRequest;

import java.io.*;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class ModelTest {

    /**
     * parse vw prediction output
     *
     * @param prediction input string
     */
    static float[] parsePrediction(String prediction) {
        // ran with --probabilities for -oaa
        if (prediction.contains(":")) {
            Supplier<Stream<String[]>> predictions = () ->
                    Arrays.stream(prediction.split(" ")).map(p -> p.split(":"));
            int maxClasses = predictions.get().map(p -> Integer.parseInt(p[0])).max(Integer::compare).orElse(1);
            float[] result = new float[maxClasses];
            predictions.get().forEach(kv ->
                    result[Integer.parseInt(kv[0]) - 1] = Float.parseFloat(kv[1])
            );
            return result;
        } else {
            return new float[]{Float.parseFloat(prediction)};
        }
    }

    /**
     * read the test file and pred file and check that we get the same predictions
     *
     * @param testInputStream        input stream with one example per line
     * @param predictionsInputStream input stream with output from vw -t -i model
     *                               --predictions -r
     * @param probabilities          predictions.txt contains probabilities
     * @throws IOException           if file reading fails
     * @throws IllegalStateException if predictions mismatch
     */
    static void testModel(VWModel model, InputStream testInputStream, InputStream predictionsInputStream,
                                 boolean probabilities) throws IOException, IllegalStateException {

        try (BufferedReader brTest = new BufferedReader(new InputStreamReader(testInputStream));
             BufferedReader brPred = new BufferedReader(new InputStreamReader(predictionsInputStream))) {

            String testLine;
            String predLine;
            int lineNum = 0;
            while ((testLine = brTest.readLine()) != null && ((predLine = brPred.readLine()) != null)) {
                float[] actualPrediction = model.predict(new PredictionRequest(testLine, probabilities));
                float[] expectedPrediction = parsePrediction(predLine);

                float actual;
                float expected;
                for (int index = 0; index < expectedPrediction.length; index++) {
                    actual = actualPrediction[index];
                    expected = expectedPrediction[index];
                    if (Math.abs(actual - expected) > 0.01) {
                        throw new IllegalStateException(String.format(
                                "line: %d index %d, actual prediction: %f, expected prediction: %f \npred line: %s\ntest line: %s",
                                lineNum, index, actual, expected, predLine, testLine));
                    }
                }
                lineNum++;
            }
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
    static void testModel(VWModel newModel, File testFile, File predFile, boolean probabilities)
            throws IOException, IllegalStateException {
        /* to make sure we predict the same values as VW */

        InputStream isTest = new FileInputStream(testFile);
        InputStream isPred = new FileInputStream(predFile);
        testModel(newModel, isTest, isPred, probabilities);
    }
}
