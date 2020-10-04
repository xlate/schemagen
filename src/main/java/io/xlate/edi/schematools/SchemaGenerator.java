package io.xlate.edi.schematools;

public class SchemaGenerator {

    public static void main(String[] args) throws Exception {
        final String dialect = System.getProperty("dialect", "<Not specified>");

        if ("X12".equals(dialect)) {
            X12SchemaXmlGenerator.main(args);
        } else if ("EDIFACT".equals(dialect)) {
            EdifactSchemaXmlGenerator.main(args);
        } else {
            System.err.println("Unknown dialect: " + dialect);
        }
    }

}
