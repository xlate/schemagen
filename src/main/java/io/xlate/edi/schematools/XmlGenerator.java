package io.xlate.edi.schematools;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import io.xlate.edischema.v4.CompositeType;
import io.xlate.edischema.v4.ElementType;
import io.xlate.edischema.v4.Schema;
import io.xlate.edischema.v4.SegmentType;

abstract class XmlGenerator {

    static Marshaller createMarshaller(JAXBContext context) throws JAXBException {
        Marshaller m = context.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        m.setProperty(Marshaller.JAXB_SCHEMA_LOCATION, "http://xlate.io/EDISchema/v4 https://www.xlate.io/EDISchema/EDISchema-v4.xsd");

        return m;
    }

    static void sortTypes(Schema s) {
        AtomicInteger result = new AtomicInteger();

        s.getTypes().sort((r1, r2) -> {

            if (doWithInstanceOf(r1, r2, ElementType.class, (t1, t2) -> result.set(t1.getName().compareTo(t2.getName())))) {
                return result.get();
            }

            if (doWithInstanceOf(r1, r2, CompositeType.class, (t1, t2) -> result.set(t1.getName().compareTo(t2.getName())))) {
                return result.get();
            }

            if (doWithInstanceOf(r1, r2, SegmentType.class, (t1, t2) -> result.set(t1.getName().compareTo(t2.getName())))) {
                return result.get();
            }

            List<Class<?>> order = Arrays.asList(ElementType.class, CompositeType.class, SegmentType.class);
            return Integer.compare(order.indexOf(r1.getClass()), order.indexOf(r2.getClass()));
        });
    }

    static <P> boolean doWithInstanceOf(Object instance1, Object instance2, Class<P> requiredType, BiConsumer<P, P> action) {
        if (instance1 != null && requiredType.isAssignableFrom(instance1.getClass()) && instance2 != null
                && requiredType.isAssignableFrom(instance2.getClass())) {
            action.accept(requiredType.cast(instance1), requiredType.cast(instance2));
            return true;
        }
        return false;
    }
}
