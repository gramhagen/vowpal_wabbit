package vowpalWabbit.slim;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.apache.commons.io.IOUtils;

public class BinaryModel {

    private int checksum;
    private boolean enableChecksumCompute;

    private int index;
    private byte[] buffer;

    public static VWModel Parse(InputStream is) throws ModelParserException, IOException {
        return new BinaryModel(IOUtils.toByteArray(is)).parse();
    }

    private BinaryModel(byte[] buffer) {
        this.buffer = buffer;
    }

    private VWModel parse() throws ModelParserException {
        VWModel model = new VWModel();

        model.setVersion(readString("version"));
        model.setId(readStringUpdateChecksum("id"));

        skipUpdateChecksum("model character", 1);

        model.setMinLabel(readFloatUpdateChecksum("min_label"));
        model.setMaxLabel(readFloatUpdateChecksum("max_label"));

        model.setNumBits((int) readUInt32UpdateChecksum("num_bits"));

        skipUpdateChecksum("lda", 4);

        // TOOD throw exception if > 0
        long ngramLen = readUInt32UpdateChecksum("ngram_len");
        skipUpdateChecksum("ngram", (int) (3 * ngramLen));

        long skipsLen = readUInt32UpdateChecksum("skips_len");
        skipUpdateChecksum("skips", (int) (3 * skipsLen));

        model.setCommandLineArguments(readStringUpdateChecksum("file_options"));

        long checksumLen = readUInt32("check_sum_len");
        if (checksumLen != 4)
            throw new ModelParserException("Check sum length must be 4", "check_sum_len");

        long storedChecksum = readUInt32("check_sum");

        if (enableChecksumCompute && storedChecksum != checksum)
            throw new ModelParserException("Invalid check sum " + storedChecksum + " vs " + checksum, "checksum");

        // gd_resume byte
        if (index + 1 >= buffer.length)
            throw new ModelParserException("Missing GD Resume Byte", "gd_resume");

        if (buffer[index] != 0)
            throw new ModelParserException("GD Resume is not supported", "gd_resume");

        index++;

        model.setWeights(readWeights(model.getNumBits()));

        return model;
    }

    private float[] readWeights(int numBits) throws ModelParserException {
        float[] weights = new float[1 << numBits];

        if (numBits < 31) {
            while (index < buffer.length) {
                long idx = readUInt32Unchecked();
                float value = readFloatUnchecked();

                weights[(int) idx] = value;
            }
        } else
            throw new ModelParserException("64-bit models not supported", "weights");

        return weights;
    }

    private long ceilLog2(long value) {
        long ceilLog2 = 0;
        while (value > 0) {
            ceilLog2++;
            value >>= 1;
        }
        return ceilLog2;
    }

    private void skipUpdateChecksum(String fieldName, int len) throws ModelParserException {
        if (index + len >= buffer.length)
            throw new ModelParserException("Buffer too short to skip: " + (index + len) + " >= " + buffer.length,
                    fieldName);

        if (enableChecksumCompute)
            checksum = VWMurmur.hash(buffer, index, len, checksum);

        index += len;
    }

    private float readFloatUnchecked() {
        return Float.intBitsToFloat((int) readUInt32Unchecked());
    }

    private long readUInt32Unchecked() {
        long value = (((buffer[index + 3] & 0xff) << 24) | ((buffer[index + 2] & 0xff) << 16)
                | ((buffer[index + 1] & 0xff) << 8) | (buffer[index] & 0xff));

        index += 4;

        return value;
    }

    private float readFloatUpdateChecksum(String fieldName) throws ModelParserException {
        return Float.intBitsToFloat((int) readUInt32UpdateChecksum(fieldName));
    }

    private long readUInt32(String fieldName) throws ModelParserException {
        if (index + 4 >= buffer.length)
            throw new ModelParserException("Buffer too short to read UInt32: " + (index + 4) + " >= " + buffer.length,
                    fieldName);

        return readUInt32Unchecked();
    }

    private long readUInt32UpdateChecksum(String fieldName) throws ModelParserException {
        long value = readUInt32(fieldName);

        if (enableChecksumCompute)
            checksum = VWMurmur.hash(buffer, index - 4, 4, checksum);

        return value;
    }

    private String readString(String fieldName) throws ModelParserException {
        long len = readUInt32(fieldName);

        if (index + len >= buffer.length)
            throw new ModelParserException("Buffer too short to read string: " + (index + len) + " >= " + buffer.length,
                    fieldName);

        String str = new String(buffer, index, (int) len, StandardCharsets.UTF_8);

        index += len;

        return str;
    }

    private String readStringUpdateChecksum(String fieldName) throws ModelParserException {
        long len = readUInt32(fieldName);

        if (index + len >= buffer.length)
            throw new ModelParserException("Buffer too short to read string: " + (index + len) + " >= " + buffer.length,
                    fieldName);

        if (enableChecksumCompute)
            checksum = VWMurmur.hash(buffer, index, (int) len, checksum);

        String str = new String(buffer, index, (int) len, StandardCharsets.UTF_8);

        index += len;

        return str;
    }
}