package vowpalWabbit.slim;

import vowpalWabbit.slim.input.Feature;
import vowpalWabbit.slim.input.Namespace;
import vowpalWabbit.slim.input.PredictionRequest;
import org.junit.Test;

import java.io.*;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static vowpalWabbit.slim.ModelTest.testModel;

public class ReadableModelTest {

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
     * <p>
     * If test.txt and predictions.txt exists it will automatically run
     * testModel() (or test.txt.gz, predictions.txt.gz readable_model.txt.gz)
     *
     * <p>
     * If you pass a file it will just load the model
     *
     * @param root          file or directory to read from
     * @param hasIntercept  model was built without --noconstant option
     * @param probabilities if file is directory and predictions.txt exist, test
     *                      there with normalized probabilities
     * @throws IOException                   if reading fails
     * @throws UnsupportedOperationException if the model was built with options we
     *                                       dont support yet
     */
    private VWModel readableModelTester(File root, boolean hasIntercept, boolean probabilities)
            throws IOException, UnsupportedOperationException, ModelParserException {

        if (root.isDirectory()) {
            File model = Paths.get(root.toString(), "readable_model.txt").toFile();
            File test = Paths.get(root.toString(), "test.txt").toFile();
            File predictions = Paths.get(root.toString(), "predictions.txt").toFile();
            File binaryModelFile = Paths.get(root.toString(), "model.bin").toFile();

            VWModel binaryModel = BinaryModel.Parse(new FileInputStream(binaryModelFile));
            VWModel readableModel = ReadableModel.Parse(new FileInputStream(model));

            if (test.exists() && predictions.exists()) {
                testModel(readableModel, test, predictions, probabilities);
                testModel(binaryModel, test, predictions, probabilities);
            }
            return readableModel;
        } else {
            return ReadableModel.Parse(new FileInputStream(root));
        }
    }

    private VWModel readableModelTester(File root) throws IOException, ModelParserException {
        return readableModelTester(root, false, false);
    }


    @Test
    public void predictBasic() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("test");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        Explanation e = new Explanation();
        assertEquals(m.predict(new PredictionRequest(new Namespace("f", new Feature("a"), new Feature("b"),
                new Feature("c"), new Feature("odd=-1"))), e)[0], -1, 0.01);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void brokenInput() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("test/test.txt");
        File file = new File(Objects.requireNonNull(resource).getFile());
        readableModelTester(file);
    }

    @Test
    public void predictBasicFile() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("example.txt");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        assertEquals(m.predict(new PredictionRequest(new Namespace("f", new Feature("a"), new Feature("b"),
                new Feature("c"), new Feature("odd=-1"))))[0], -1, 0.01);
    }

    @Test
    public void predictQuadratic() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testq");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        // echo "1 |a x z |b x1 z1" | vw -t -i model.bin
        assertEquals(m.predict(new PredictionRequest(new Namespace("a", new Feature("x"), new Feature("z")),
                new Namespace("b", new Feature("x1"), new Feature("z1"))))[0], -0.0657, 0.01);
    }

    @Test
    public void testReusableNamespace() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testq");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        Namespace a = new Namespace("a", new Feature("x"));
        assertEquals(m.predict(new PredictionRequest(a))[0], -0.0843, 0.01);

        a.rename("b");
        assertEquals(m.predict(new PredictionRequest(a))[0], -0.0421, 0.01);

        a.rename("a");
        assertEquals(m.predict(new PredictionRequest(a))[0], -0.0843, 0.01);
    }

    @Test
    public void testErrorPileup() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("test_error_pileup");
        File file = new File(Objects.requireNonNull(resource).getFile());
        readableModelTester(file);
    }

    @Test
    public void predictQuadraticNumeric() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testqnum");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        // echo "1 |a numa:2 cat1 cat2 |b numb:4 cat3 cat4" | vw -t -i model.bin
        assertEquals(m.predict(new PredictionRequest(
                        new Namespace("a", Feature.fromString("numa:2"), Feature.fromString("cat1"),
                                Feature.fromString("cat2")),
                        new Namespace("b", Feature.fromString("numb:4"), Feature.fromString("cat3"),
                                Feature.fromString("cat4"))))[0],
                0.864206, 0.001);
    }

    @Test
    public void testOaa() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("oaa");
        File file = new File(Objects.requireNonNull(resource).getFile());
        readableModelTester(file);

        resource = this.getClass().getClassLoader().getResource("oaa7");
        file = new File(Objects.requireNonNull(resource).getFile());
        readableModelTester(file);

        resource = this.getClass().getClassLoader().getResource("oaa10");
        file = new File(Objects.requireNonNull(resource).getFile());
        readableModelTester(file);
    }

    @Test
    public void hashAllVsStrings() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testhashnum");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        PredictionRequest predictionRequest = new PredictionRequest(
                new Namespace("a", new Feature("42"), new Feature("x")),
                new Namespace("b", new Feature(42), new Feature("y")));
        assertEquals(m.predict(predictionRequest)[0], 0.281, 0.01);

        resource = this.getClass().getClassLoader().getResource("testhashall");
        file = new File(Objects.requireNonNull(resource).getFile());
        VWModel mHashAll = readableModelTester(file, true, false);
        assertEquals(mHashAll.predict(predictionRequest)[0], m.predict(predictionRequest)[0], 0.01);

        assertNotEquals(mHashAll.featureHashOf(100, new Feature("42")),
                m.featureHashOf(100, new Feature("42")));

        assertEquals(
                m.featureHashOf(100, new Feature("42")),
                m.featureHashOf(100, new Feature(42))
        );
    }

    @Test
    public void testClip() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testclip");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        assertEquals(m.predict(new PredictionRequest(new Namespace("", new Feature("pos"), new Feature("pos"),
                new Feature("pos"), new Feature("pos"), new Feature("pos"), new Feature("pos"),
                new Feature("pos"))))[0], 2, 0.01);

        assertEquals(m.predict(new PredictionRequest(new Namespace("", new Feature("neg"), new Feature("neg"),
                new Feature("neg"), new Feature("neg"), new Feature("neg"), new Feature("neg"),
                new Feature("neg"))))[0], -2, 0.01);
    }

    @Test
    public void testLinkLogistic() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testlinklogistic");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        assertEquals(m.predict(new PredictionRequest(new Namespace("", new Feature("pos"), new Feature("pos"),
                new Feature("pos"), new Feature("pos"), new Feature("pos"), new Feature("pos"),
                new Feature("pos"))))[0], 0.880797, 0.0001);
    }

    @Test
    public void testLinkPoisson() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testlinkpoisson");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        assertEquals(m.predict(new PredictionRequest(new Namespace("", new Feature("pos"), new Feature("pos"),
                new Feature("pos"), new Feature("pos"), new Feature("pos"), new Feature("pos"),
                new Feature("pos"))))[0], 7.38905, 0.0001);
    }

    @Test
    public void testLinkGlf1() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testlinkglf1");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        assertEquals(m.predict(new PredictionRequest(new Namespace("", new Feature("pos"), new Feature("pos"),
                new Feature("pos"), new Feature("pos"), new Feature("pos"), new Feature("pos"),
                new Feature("pos"))))[0], 0.76159, 0.0001);
    }

    @Test
    public void testBrokenOptions() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("test_break_options/model.txt");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file);

        // TODO: don't know why Namespace("", ...) triggers charAt(0) exception
        assertEquals(m.predict(new PredictionRequest(new Namespace(" ", new Feature("pos"), new Feature("pos"),
                new Feature("pos"), new Feature("pos"), new Feature("pos"), new Feature("pos"),
                new Feature("pos"))))[0], 0.5, 0.0001);
    }

    @Test
    public void testProbabilities() throws Exception {
        URL resource = this.getClass().getClassLoader().getResource("testprobabilities");
        File file = new File(Objects.requireNonNull(resource).getFile());
        VWModel m = readableModelTester(file, true, true);

        assertEquals(m.predict(new PredictionRequest(new Namespace("", new Feature("pos"), new Feature("pos"),
                new Feature("pos"), new Feature("pos"), new Feature("pos"), new Feature("pos"),
                new Feature("pos"))))[0], -0.113007, 0.0001);
    }
}
