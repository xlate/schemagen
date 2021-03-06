package io.xlate.edi.schematools;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;

import org.python.core.PyDictionary;
import org.python.core.PyInteger;
import org.python.core.PyList;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.core.PyTuple;
import org.python.util.PythonInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.xlate.edischema.v4.BaseType;
import io.xlate.edischema.v4.CompositeStandard;
import io.xlate.edischema.v4.CompositeType;
import io.xlate.edischema.v4.ElementBaseType;
import io.xlate.edischema.v4.ElementStandard;
import io.xlate.edischema.v4.ElementType;
import io.xlate.edischema.v4.LoopStandard;
import io.xlate.edischema.v4.Schema;
import io.xlate.edischema.v4.SegmentStandard;
import io.xlate.edischema.v4.SegmentType;
import io.xlate.edischema.v4.Transaction;

public class X12SchemaXmlGenerator extends XmlGenerator {

    static final Logger log = LoggerFactory.getLogger(X12SchemaXmlGenerator.class);
    static final String ID = "ID";
    static final String MIN = "MIN";
    static final String MAX = "MAX";
    static final String LEVEL = "LEVEL";

    final PythonInterpreter python;
    static Map<String, BaseType> types = new HashMap<>();

    final ClassLoader loader;
    int loopId = 0;

    public static void main(String[] args) throws IOException {
        log.info("Starting Python interpreter");
        try (PythonInterpreter python = new PythonInterpreter()) {
            log.info("Creating processor");
            new X12SchemaXmlGenerator(python).process();
        }
    }

    public X12SchemaXmlGenerator(PythonInterpreter python) {
        loader = Thread.currentThread().getContextClassLoader();
        this.python = python;
    }

    private void process() throws IOException {
        String zipFile = System.getProperty("X12.botszip");

        if (zipFile != null) {
            try {
                addVersion(zipFile);
            } catch (Exception e) {
                log.error("Exception processing file {}", zipFile, e);
            }
        } else {
            Properties config = new Properties();
            try (InputStream stream = loader.getResourceAsStream("x12-versions.properties")) {
                config.load(stream);
            }
            String[] versions = config.getProperty("versions").split(",");

            for (String version : versions) {
                try {
                    addVersion(findZip(version), version);
                } catch (Exception e) {
                    log.error("Exception processing version {}", version, e);
                    break;
                }
            }
        }
    }

    private void addVersion(String zipFile) throws IOException {
        ZipEntry records = null;
        Pattern recordsPattern = Pattern.compile(".*records(\\d{6})\\.py$");

        try (ZipFile file = new ZipFile(zipFile)) {
            records = file.stream().filter(entry -> {
                return recordsPattern.matcher(entry.getName()).matches();
            }).findFirst().orElse(null);
        }

        if (records != null) {
            Matcher recordsMatcher = recordsPattern.matcher(records.getName());
            if (recordsMatcher.matches()) {
                String version = recordsMatcher.group(1);
                addVersion(new ZipInputStream(new FileInputStream(zipFile)), version);
            }
        }
    }

    @SuppressWarnings("resource")
    private ZipInputStream findZip(String version) {
        String resource = "/x12/X12_" + version + "_all_transactions_and_segments.zip";
        InputStream stream = getClass().getResourceAsStream(resource);

        if (stream == null) {
            resource = "/x12/X12" + version + "_all_messages_and_segments.zip";
            stream = getClass().getResourceAsStream(resource);
        }

        return new ZipInputStream(stream);
    }

    @SuppressWarnings("preview")
    private void addVersion(ZipInputStream zip, String version) throws IOException {
        types.clear();

        Path output = Paths.get("./target/x12/" + version);
        Files.createDirectories(output);

        Map<String, PyList> structures = new TreeMap<>();
        PyDictionary recordDefs = null;
        Pattern namePattern = Pattern.compile(".*(\\d{3})(\\d{6})\\.py$");

        ZipEntry entry;

        do {
            entry = zip.getNextEntry();

            if (entry != null) {
                final String name = entry.getName();
                final Matcher m = namePattern.matcher(name);
                final URL alt = loader.getResource("x12/" + name);
                @SuppressWarnings("resource") // input closed in finally if 'alt' is non-null
                final InputStream input = alt != null ? alt.openStream() : zip;

                try {
                    if (m.find()) {
                        if (alt != null) {
                            log.info("Using alternate stream: {}", alt);
                        }

                        String transaction = m.group(1);
                        PyList structure = getStructure(python, input, entry);
                        structures.put(transaction, structure);
                    } else if (name.matches(".*records\\d+\\.py")) {
                        if (alt != null) {
                            log.info("Using alternate stream: {}", alt);
                        }

                        recordDefs = getRecordDefs(python, input, entry);
                    }
                } finally {
                    if (alt != null) {
                        input.close();
                    }
                }
            }
        } while (entry != null);

        zip.close();

        if (recordDefs == null) {
            throw new IllegalStateException("recorddefs not found");
        }

        if (log.isDebugEnabled()) {
            for (Map.Entry<String, PyList> list : structures.entrySet()) {
                log.debug("{} => {}", list.getKey(), list.getValue());
            }
            log.debug("{}", recordDefs);
        }

        loadTypes(recordDefs);

        log.info("Version {} - Writing {} transactions", version, structures.size());

        for (Map.Entry<String, PyList> structure : structures.entrySet()) {
            String name = structure.getKey();
            PyList tree = structure.getValue();

            Schema messageSchema = new Schema();
            loopId = 0;
            List<BaseType> references;

            try {
                references = buildTree(tree, messageSchema);
            } catch (Exception e) {
                log.error("Exception building tree for {}", name);
                throw e;
            }

            sortTypes(messageSchema);

            Transaction tx = new Transaction();
            tx.setSequence(new ArrayList<>(references));
            messageSchema.getLayout().add(tx);

            Path subdir = output.resolve(name.substring(0, 1) + "XX");
            Files.createDirectories(subdir);
            StringBuilder filename = new StringBuilder(subdir.toString());
            filename.append('/');
            filename.append(name);
            filename.append(".xml");

            try (OutputStream out = new FileOutputStream(filename.toString())) {
                JAXBContext context = JAXBContext.newInstance(Schema.class);
                Marshaller m = createMarshaller(context);
                m.marshal(messageSchema, out);
            } catch (Exception e) {
                log.error("Exception writing schema: {}", filename, e);
                break;
            }
        }

        log.info("Version {} - {} transactions added", version, structures.size());
    }

    private List<BaseType> buildTree(PyList tree, Schema messageSchema) {
        @SuppressWarnings({ "all" })
        ListIterator<PyDictionary> entries = (ListIterator<PyDictionary>) tree.listIterator();
        List<BaseType> references = new ArrayList<>(tree.size());

        while (entries.hasNext()) {
            PyDictionary entry = entries.next();
            String id = ((String) entry.get(ID)).toUpperCase();

            if ("SE".equals(id)) {
                continue;
            }

            PyList loop = (PyList) entry.get(LEVEL);

            if ("ST".equals(id)) {
                return buildTree(loop, messageSchema);
            }

            int min = (Integer) entry.get(MIN);
            int max = (Integer) entry.get(MAX);

            if (loop != null) {
                final String loopCode = String.format("L%04d", ++loopId);
                final SegmentStandard loopStart = segmentRef(messageSchema, id, null, null);

                LoopStandard loopX = new LoopStandard();
                loopX.setCode(loopCode);
                loopX.setMinOccurs(min != 0 ? BigInteger.valueOf(min) : null);
                loopX.setMaxOccurs(max != 1 ? BigInteger.valueOf(max) : null);
                loopX.setSequence(new ArrayList<>());
                loopX.getSequence().add(loopStart);
                loopX.getSequence().addAll(buildTree(loop, messageSchema));

                references.add(loopX);
            } else {
                references.add(segmentRef(messageSchema, id, min, max));
            }
        }

        return references;
    }

    private SegmentStandard segmentRef(Schema messageSchema, String id, Integer min, Integer max) {
        SegmentType segmentType = (SegmentType) types.get(id);

        if (segmentType == null) {
            throw new IllegalStateException("Type not found: " + id);
        }

        SegmentStandard segment = new SegmentStandard();
        segment.setType(segmentType.getName());
        segment.setMinOccurs(min != null && min != 0 ? BigInteger.valueOf(min) : null);
        segment.setMaxOccurs(max != null && max != 1 ? BigInteger.valueOf(max) : null);

        addIfAbsent(messageSchema, segmentType.getName());

        return segment;
    }

    @SuppressWarnings("preview")
    private void addIfAbsent(Schema messageSchema, String typeId) {
        BaseType type = types.get(typeId);
        var schemaTypes = messageSchema.getTypes();

        if (!schemaTypes.contains(type)) {
            if (type == null) {
                throw new NullPointerException("Type for " + typeId + " is null");
            }
            schemaTypes.add(type);

            if (type instanceof SegmentType) {
                for (BaseType target : ((SegmentType) type).getSequence()) {
                    if (target instanceof ElementStandard) {
                        addIfAbsent(messageSchema, ((ElementStandard) target).getType());
                    } else if (target instanceof CompositeStandard) {
                        addIfAbsent(messageSchema, ((CompositeStandard) target).getType());
                    }
                }
            } else if (type instanceof CompositeType) {
                for (BaseType target : ((CompositeType) type).getSequence()) {
                    if (target instanceof ElementStandard) {
                        addIfAbsent(messageSchema, ((ElementStandard) target).getType());
                    }
                }
            }
        } else {
            log.debug("{} already in messageTypes", type);
        }
    }

    private void loadTypes(PyDictionary recordDefs) {
        for (Entry<PyObject, PyObject> entry : recordDefs.getMap().entrySet()) {
            final String segmentId = entry.getKey().toString();
            PyList record = (PyList) entry.getValue();
            SegmentType segment = new SegmentType();
            segment.setName(segmentId);
            segment.setSequence(new ArrayList<>());

            for (int e = 0, m = record.size(); e < m; e++) {
                PyList element = (PyList) record.pyget(e);
                String id = ((PyString) element.pyget(0)).getString();

                if ("BOTSID".equals(id)) {
                    continue;
                }

                final PyObject usage = element.pyget(1);
                final String usageCode;
                final int min;
                final int max;

                if (usage instanceof PyTuple) {
                    usageCode = ((PyTuple) usage).get(0).toString();
                    max = ((Integer) ((PyTuple) usage).get(1));
                    log.debug("Segment {} has repeating element {}", segmentId, id);
                } else {
                    usageCode = usage.toString();
                    max = 1;
                }

                switch (usageCode) {
                case "C":
                    min = 0;
                    break;
                case "M":
                    min = 1;
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected usage " + usageCode);
                }

                PyObject third = element.pyget(2);
                BaseType type;
                BaseType ref;

                if (third instanceof PyList) {
                    // Composite
                    type = buildCompositeElement(id, (PyList) third);
                    CompositeStandard std = new CompositeStandard();
                    std.setType(((CompositeType) type).getName());
                    std.setMinOccurs(min != 0 ? BigInteger.valueOf(min) : null);
                    std.setMaxOccurs(max != 1 ? BigInteger.valueOf(max) : null);
                    ref = std;
                } else if (third != null) {
                    // Simple element
                    type = buildSimpleElement(id, element);
                    ElementStandard std = new ElementStandard();
                    std.setType(((ElementType) type).getName());
                    std.setMinOccurs(min != 0 ? BigInteger.valueOf(min) : null);
                    std.setMaxOccurs(max != 1 ? BigInteger.valueOf(max) : null);
                    ref = std;
                } else {
                    throw new IllegalArgumentException("Unexpected third " + third);
                }

                segment.getSequence().add(ref);
                types.put(id, type);
            }

            types.put(segmentId, segment);
        }
    }

    private CompositeType buildCompositeElement(String id, PyList record) {
        int referenceCount = record.size();
        CompositeType composite = new CompositeType();
        composite.setName(id);
        composite.setSequence(new ArrayList<>(referenceCount));

        for (int e = 0; e < referenceCount; e++) {
            PyList element = (PyList) record.pyget(e);
            String cid = ((PyString) element.pyget(0)).getString();
            final String usageCode = element.get(1).toString();
            final int min;

            switch (usageCode) {
            case "C":
                min = 0;
                break;
            case "M":
                min = 1;
                break;
            default:
                throw new IllegalArgumentException("Unexpected usage " + usageCode);
            }
            ElementType component = buildSimpleElement(cid.replace(".", ""), element);
            ElementStandard elementRef = new ElementStandard();
            elementRef.setType(component.getName());
            elementRef.setMinOccurs(min != 0 ? BigInteger.valueOf(min) : null);
            composite.getSequence().add(elementRef);
            types.put(component.getName(), component);
        }

        return composite;
    }

    @SuppressWarnings("preview")
    private ElementType buildSimpleElement(String id, PyList element) {
        PyObject third = element.pyget(2);
        PyTuple range = (third instanceof PyTuple) ? (PyTuple) third : null;

        int min = (Integer) (range != null ? range.get(0) : 1);
        int max = (Integer) (range != null ? range.get(1) : ((PyInteger) third).getValue());
        String typeCode = element.pyget(3).toString();
        final ElementBaseType base;
        final BigInteger scale;

        switch (typeCode) {
        case "AN":
            base = ElementBaseType.STRING;
            scale = null;
            break;
        case "B":
            base = ElementBaseType.BINARY;
            scale = null;
            break;
        case "DT":
            base = ElementBaseType.DATE;
            scale = null;
            break;
        case "N0":
        case "N1":
        case "N2":
        case "N3":
        case "N4":
        case "N5":
        case "N6":
        case "N7":
        case "N8":
        case "N9":
            base = ElementBaseType.NUMERIC;
            scale = BigInteger.valueOf(Long.valueOf(typeCode.substring(1)));
            break;
        case "R":
            base = ElementBaseType.DECIMAL;
            scale = null;
            break;
        case "TM":
            base = ElementBaseType.TIME;
            scale = null;
            break;
        default:
            throw new IllegalArgumentException("Unexpected type " + typeCode);
        }

        ElementType type = new ElementType();
        type.setName(id);
        type.setBase(base);
        type.setScale(scale);
        type.setMinLength(min != 1 ? BigInteger.valueOf(min) : null);
        type.setMaxLength(max != 1 ? BigInteger.valueOf(max) : null);

        return type;
    }

    private static PyList getStructure(PythonInterpreter py, InputStream zip, ZipEntry entry)
            throws IOException {

        Writer writer = new StringWriter((int) entry.getSize());
        PrintWriter printer = new PrintWriter(writer);
        printer.println(assignment(ID));
        printer.println(assignment(MIN));
        printer.println(assignment(MAX));
        printer.println(assignment(LEVEL));

        BufferedReader reader = new BufferedReader(new InputStreamReader(zip));
        String line;
        boolean complete = false;

        while ((line = reader.readLine()) != null) {
            if (!line.matches(".*from .* import .*")) {
                printer.println(line);
                if (line.equals("]")) {
                    complete = true;
                }
            }
        }

        if (!complete) {
            printer.println("]}\n]");
        }

        try {
            py.exec(writer.toString());
        } catch (Exception e) {
            log.error("Exception in {}", entry.getName());
            throw e;
        }

        return (PyList) py.get("structure");
    }

    static String assignment(String name) {
        return name + " = '" + name + '\'';
    }

    private static PyDictionary getRecordDefs(PythonInterpreter py, InputStream zip, ZipEntry entry)
            throws IOException {

        StringWriter writer = new StringWriter((int) entry.getSize());
        PrintWriter printer = new PrintWriter(writer);
        BufferedReader reader = new BufferedReader(new InputStreamReader(zip));
        String line;
        int def = 0;
        List<PyDictionary> recorddefs = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            if (line.matches(".*from .* import .*")) {
                continue;
            } else if (line.startsWith("],")) {
                if (++def > 9) {
                    printer.println("]}");

                    try {
                        py.exec(writer.toString());
                    } catch (Exception e) {
                        log.error("Exception in {}", entry.getName());
                        throw e;
                    }

                    recorddefs.add((PyDictionary) py.get("recorddefs"));
                    writer.getBuffer().setLength(0);
                    printer.println("recorddefs = {");
                    def = 0;
                } else {
                    printer.println(line);
                }
            } else {
                printer.println(line);
            }
        }

        if (def > 0) {
            try {
                py.exec(writer.toString());
            } catch (Exception e) {
                log.error("Exception in {}", entry.getName());
                throw e;
            }

            recorddefs.add((PyDictionary) py.get("recorddefs"));
        }

        PyDictionary result = new PyDictionary();

        for (PyDictionary dictionary : recorddefs) {
            result.putAll(dictionary);
        }

        return result;
    }
}
