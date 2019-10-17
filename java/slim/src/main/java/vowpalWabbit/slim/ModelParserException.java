package vowpalWabbit.slim;

public class ModelParserException extends Exception {
    private static final long serialVersionUID = 1L;

    private String fieldName;

    public ModelParserException(String message, String fieldName) {
        super(message);
    }

    public String getFieldName() {
        return fieldName;
    }
}