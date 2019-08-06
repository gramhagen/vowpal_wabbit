package vowpalWabbit.learner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import vowpalWabbit.VWTestHelper;
import vowpalWabbit.responses.ActionScore;
import vowpalWabbit.responses.ActionScores;

import javax.swing.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Created by jmorra on 10/2/15.
 */
public class VWActionScoresLearnerTest extends VWTestHelper {
    private void compareActionScores(ActionScores[] expected, ActionScores[] pred) {
        Comparator actionScoreComparator = new Comparator<ActionScore>() {
            @Override
            public int compare(ActionScore a, ActionScore b) {
                return a.getAction() - b.getAction();
            }
        };

        for (int i=0; i<expected.length; i++) {
            ActionScore[] pred_i = pred[i].getActionScores();
            Arrays.sort(pred_i, actionScoreComparator);

            ActionScore[] expected_i = expected[i].getActionScores();
            Arrays.sort(expected_i, actionScoreComparator);

            for (int j=0; j<expected_i.length; j++) {
                assertEquals(pred_i[j].getAction(), expected_i[j].getAction());
                assertEquals(pred_i[j].getScore(), expected_i[j].getScore(), 0.0001f);
            }
        }
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testCSOAA() throws IOException {
        String[][] data = new String[][]{
                new String[]{
                        "1:1.0 | a_1 b_1 c_1",
                        "2:0.0 | a_2 b_2 c_2",
                        "3:2.0 | a_3 b_3 c_3"
                },
                new String[]{
                        "1:1.0 | b_1 c_1 d_1",
                        "2:0.0 | b_2 c_2 d_2"
                },
                new String[]{
                        "1:1.0 | a_1 b_1 c_1",
                        "3:2.0 | a_3 b_3 c_3"
                }
        };

        VWActionScoresLearner vw = VWLearners.create("--csoaa_ldf mc --quiet --csoaa_rank");

        ActionScores[] pred = new ActionScores[data.length];
        for (int j=0; j<100; ++j) {
            for (int i=0; i<data.length; ++i) {
                pred[i] = vw.learn(data[i]);
            }
        }

        vw.close();

        ActionScores[] expected = new ActionScores[]{
                actionScores(
                        actionScore(1, -1.0573887f),
                        actionScore(0, -0.033036415f),
                        actionScore(2, 1.0063205f)
                ),
                actionScores(
                        actionScore(1, -1.0342788f),
                        actionScore(0, 0.9994181f)
                ),
                actionScores(
                        actionScore(0, 0.033397526f),
                        actionScore(1, 1.0227613f)
                )
        };

        compareActionScores(expected, pred);
    }

    @Test
    public void testCBADF() throws IOException {
        testCBADF(false);
    }

    @Test
    public void testCBADFWithRank() throws IOException {
        testCBADF(true);
    }

    private void testCBADF(boolean withRank) throws IOException {
        String[][] cbADFTrain = new String[][]{
            new String[]{"| a:1 b:0.5","0:0.1:0.75 | a:0.5 b:1 c:2"},
            new String[]{"shared | s_1 s_2","0:1.0:0.5 | a:1 b:1 c:1","| a:0.5 b:2 c:1"},
            new String[]{"| a:1 b:0.5","0:0.1:0.75 | a:0.5 b:1 c:2"},
            new String[]{"shared | s_1 s_2","0:1.0:0.5 | a:1 b:1 c:1","| a:0.5 b:2 c:1"}
        };
        String model = temporaryFolder.newFile().getAbsolutePath();
        String cli = "--quiet --cb_adf -f " + model;
        if (withRank)
            cli += " --rank_all";
        VWActionScoresLearner vw = VWLearners.create(cli);
        ActionScores[] trainPreds = new ActionScores[cbADFTrain.length];
        for (int i=0; i<cbADFTrain.length; ++i) {
            trainPreds[i] = vw.learn(cbADFTrain[i]);
        }

        ActionScores[] expectedTrainPreds = new ActionScores[]{
            actionScores(
                actionScore(0, 0),
                actionScore(1, 0)
            ),
            actionScores(
                actionScore(0, 0.11246802f),
                actionScore(1, 0.11246802f)
            ),
            actionScores(
                actionScore(0, 0.3682006f),
                actionScore(1, 0.5136312f)
            ),
            actionScores(
                actionScore(0, 0.58848584f),
                actionScore(1, 0.6244352f)
            )
        };
        vw.close();
        compareActionScores(expectedTrainPreds, trainPreds);

        vw = VWLearners.create("--quiet -t -i " + model);
        ActionScores[] testPreds = new ActionScores[]{vw.predict(cbADFTrain[0])};

        ActionScores[] expectedTestPreds = new ActionScores[]{
            actionScores(
                actionScore(0, 0.39904374f),
                actionScore(1, 0.49083984f)
            )
        };

        vw.close();
        compareActionScores(expectedTrainPreds, trainPreds);
    }
}
