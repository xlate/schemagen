package io.xlate.edi.schematools;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

abstract class XmlGenerator {

    static Marshaller createMarshaller(JAXBContext context) throws JAXBException {
        Marshaller m = context.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        m.setProperty(Marshaller.JAXB_SCHEMA_LOCATION, "http://xlate.io/EDISchema/v4 https://www.xlate.io/EDISchema/EDISchema-v4.xsd");

        return m;
    }

}
