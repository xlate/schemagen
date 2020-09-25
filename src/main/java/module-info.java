module io.xlate.schematools {

    requires jakarta.activation;
    requires java.logging;
    requires java.prefs;
    requires java.sql;
    requires java.xml;
    requires java.xml.bind;

    requires jython.slim;

    requires org.slf4j;

    opens io.xlate.edischema.v4 to java.xml.bind;
}
