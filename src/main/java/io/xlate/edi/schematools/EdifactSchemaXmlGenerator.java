package io.xlate.edi.schematools;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.xlate.edischema.v4.BaseType;
import io.xlate.edischema.v4.CompositeStandard;
import io.xlate.edischema.v4.CompositeType;
import io.xlate.edischema.v4.ElementBaseType;
import io.xlate.edischema.v4.ElementStandard;
import io.xlate.edischema.v4.ElementType;
import io.xlate.edischema.v4.GroupControlType;
import io.xlate.edischema.v4.Interchange;
import io.xlate.edischema.v4.LoopStandard;
import io.xlate.edischema.v4.Schema;
import io.xlate.edischema.v4.SegmentStandard;
import io.xlate.edischema.v4.SegmentType;
import io.xlate.edischema.v4.Syntax;
import io.xlate.edischema.v4.SyntaxType;
import io.xlate.edischema.v4.Transaction;
import io.xlate.edischema.v4.TransactionControlType;
import io.xlate.edischema.v4.Value;

public class EdifactSchemaXmlGenerator extends XmlGenerator {

    static final Logger log = LoggerFactory.getLogger(EdifactSchemaXmlGenerator.class);
    static final Pattern syntax = Pattern.compile("^\\s*\\d+\\.[\\+\\*\\#\\|\\-X]*\\s*(D[1-7])\\(([0-9, ]+)\\).*$", Pattern.MULTILINE);

    static final Map<String, BaseType> types = new TreeMap<>();
    static Map<String, Schema> messages;

    static Set<String> nullRefs = new TreeSet<>();

    static SegmentType segment = null;

    static CompositeType composite = null;
    static boolean newComposite = false;
    static boolean includeTitles = false;

    public static void main(String[] args) throws IOException, JAXBException {
        Properties config = new Properties();

        try (InputStream stream = EdifactSchemaXmlGenerator.class.getResourceAsStream("/edifact-versions.properties")) {
            config.load(stream);
        }

        String[] versions = config.getProperty("versions").split(",");
        includeTitles = Boolean.valueOf(config.getProperty("includeTitles", "false"));

        Path output = Paths.get("./target/edifact");
        Files.createDirectories(output);
        JAXBContext context = JAXBContext.newInstance(Schema.class);

        for (String ver : versions) {
            String codelist = config.getProperty(ver + ".codelist");

            if (codelist.matches("^\\$\\{(.+)\\}$")) {
                codelist = config.getProperty(codelist.substring(2, codelist.length() - 1));
            }

            String[] revisions = codelist.split(",");

            log.info("Version {}", ver);
            messages = new TreeMap<>();
            types.clear();

            String revision = revisions[revisions.length - 1];
            log.info("Revision {}", revision);

            final Map<String, Set<String>> values;

            try (InputStream stream = getInputStream(revision)) {
                values = loadCodeList(stream);
            }

            try (InputStream stream = getInputStream(config.getProperty(ver + ".elements"))) {
                loadElements(stream, values);
            }
            try (InputStream stream = getInputStream(config.getProperty(ver + ".composites"))) {
                loadComposites(stream);
            }
            try (InputStream stream = getInputStream(config.getProperty(ver + ".segments"))) {
                loadSegments(stream);
            }

            final Schema schema = new Schema();
            buildControlStructure(schema);
            sortTypes(schema);

            Matcher vermatch = Pattern.compile("(\\d)(\\d{2})\\d{2}").matcher(ver);
            vermatch.find();

            StringBuilder filename = new StringBuilder("./target/edifact/v");
            filename.append(vermatch.group(1));
            if (!vermatch.group(2).equals("00")) {
                filename.append('r');
                filename.append(vermatch.group(2));
            }
            filename.append(".xml");

            try (OutputStream out = new FileOutputStream(filename.toString())) {
                Marshaller m = createMarshaller(context);
                m.marshal(schema, out);
            }

            try (InputStream stream = getInputStream(config.getProperty(ver + ".messages"))) {
                loadMessages(stream, ver);
            }

            for (Map.Entry<String, Schema> entry : messages.entrySet()) {
                filename.setLength(0);
                filename.append("./target/edifact/v");
                filename.append(vermatch.group(1));
                if (!vermatch.group(2).equals("00")) {
                    filename.append('r');
                    filename.append(vermatch.group(2));
                }
                filename.append("-");
                filename.append(entry.getKey());
                filename.append(".xml");

                sortTypes(entry.getValue());

                try (OutputStream out = new FileOutputStream(filename.toString())) {
                    Marshaller m = createMarshaller(context);
                    m.marshal(entry.getValue(), out);
                }
            }

            /*for (String revision : revisions) {
                schema = new Schema();

                final Map<String, Set<String>> values = loadCodeList(getInputStream(revision));
                standardDB.put("codelists." + revision, values);
                loadElements(getInputStream(config.getProperty(ver + ".elements")), values);
                loadComposites(getInputStream(config.getProperty(ver + ".composites")));
                loadSegments(getInputStream(config.getProperty(ver + ".segments")));

                buildControlStructure();

                try (OutputStream out = new FileOutputStream("target/" + revision.replace('/', '.') + ".xml")) {
                    JAXBContext context = JAXBContext.newInstance(Schema.class);
                    Marshaller m = context.createMarshaller();
                    m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
                    m.marshal(schema, out);
                }
            }*/
        }

        String[] directories = config.getProperty("directories").split(",");
        int stdLayoutYear = Integer.parseInt(config.getProperty("directories.std_layout"));

        for (String directory : directories) {
            int year = Integer.parseInt(directory.trim());
            String yearAbbr = String.format("%02d", year % 100);
            String version = 'd' + yearAbbr;

            String revisionKey = version + ".revisions";
            String[] revisions;

            if (config.containsKey(revisionKey)) {
                revisions = config.getProperty(revisionKey).split(",");
            } else {
                revisions = new String[] { "a", "b", "c" };
            }

            for (String revision : revisions) {
                messages = new TreeMap<>();

                String rel = version + revision;
                String codelist;
                String elements;
                String composites;
                String segments;
                String messagesConfig;

                if (config.containsKey(revisionKey)) {
                    if (year < stdLayoutYear) {
                        codelist = resolve(config.getProperty(version + ".codelist"), revision);
                        elements = resolve(config.getProperty(version + ".elements"), revision);
                        composites = resolve(config.getProperty(version + ".composites"), revision);
                        segments = resolve(config.getProperty(version + ".segments"), revision);
                        messagesConfig = resolve(config.getProperty(version + ".messages"), revision);
                    } else {
                        codelist = resolve(config.getProperty("std.codelist"), yearAbbr, revision);
                        elements = resolve(config.getProperty("std.elements"), yearAbbr, revision);
                        composites = resolve(config.getProperty("std.composites"), yearAbbr, revision);
                        segments = resolve(config.getProperty("std.segments"), yearAbbr, revision);
                        messagesConfig = resolve(config.getProperty("std.messages"), yearAbbr, revision);
                    }
                } else {
                    elements = config.getProperty(rel + ".elements");

                    if (elements == null) {
                        continue;
                    }

                    codelist = config.getProperty(rel + ".codelist");
                    composites = config.getProperty(rel + ".composites");
                    segments = config.getProperty(rel + ".segments");
                    messagesConfig = config.getProperty(rel + ".messages");
                }

                log.info("Revision {}", revision);

                final Map<String, Set<String>> values;

                try (InputStream stream = getInputStream(codelist)) {
                    values = loadCodeList(stream);
                }

                try (InputStream stream = getInputStream(elements)) {
                    loadElements(stream, values);
                }

                try (InputStream stream = getInputStream(composites)) {
                    loadComposites(stream);
                }

                try (InputStream stream = getInputStream(segments)) {
                    loadSegments(stream);
                }

                try (InputStream stream = getInputStream(messagesConfig)) {
                    loadMessages(stream, rel);
                }

                StringBuilder filename = new StringBuilder();

                for (Map.Entry<String, Schema> entry : messages.entrySet()) {
                    filename.setLength(0);
                    filename.append("./target/edifact/");
                    filename.append(rel);
                    filename.append("/");
                    filename.append(entry.getKey().substring(0, 1));
                    Files.createDirectories(Path.of(filename.toString()));

                    filename.append("/");
                    filename.append(entry.getKey());
                    filename.append(".xml");

                    sortTypes(entry.getValue());

                    try (OutputStream out = new FileOutputStream(filename.toString())) {
                        Marshaller m = createMarshaller(context);
                        m.marshal(entry.getValue(), out);
                    }
                }
            }
        }
    }

    static String resolve(String value, String revision) {
        return value.replaceAll("\\{revision\\}", revision);
    }

    static String resolve(String value, String version, String revision) {
        return resolve(value, revision).replaceAll("\\{version\\}", version);
    }

    static InputStream getInputStream(String resource) throws IOException {
        String[] elements = resource.split("\\$");
        InputStream stream = EdifactSchemaXmlGenerator.class.getResourceAsStream('/' + elements[0]);

        for (int i = 1; i < elements.length; i++) {
            String entryName = elements[i].toLowerCase();
            ZipInputStream zip = new ZipInputStream(stream);
            ZipEntry entry;

            do {
                entry = zip.getNextEntry();

                if (entry != null) {
                    String name = entry.getName().toLowerCase();

                    if (name.equals(entryName) || name.endsWith("/" + entryName)) {
                        stream = zip;
                        break;
                    }
                }
            } while (entry != null);
        }

        return stream;
    }

    static Map<String, Set<String>> loadCodeList(InputStream archive) throws IOException {
        int blankCount = 0;
        Pattern ibm350Dashes = Pattern.compile("[─-]+");
        Pattern declaration = Pattern.compile("^[\\+\\*\\#\\|\\-X]*[ \\t]*" // Change indicator
                + "(\\d+)" // Element number
                + "[^\\[]+" // Skip the description
                + "(?:\\[.\\])?" // Usage indicator
                + "\\s*\\|?\\s*"
                + "Desc: .*",
                                              Pattern.MULTILINE +
                                                      Pattern.DOTALL);
        boolean declarationSearch = false;
        boolean declarationFound = false;

        Pattern fieldFormat = Pattern.compile("^Repr: (a|an|n)(\\.\\.)?(\\d*)$");
        Pattern valueFormat = null;

        Map<String, Set<String>> codeList = new HashMap<>(250);

        /*Pattern def = Pattern.compile(
        		"^[\\n\\r]+[ \\t]*[\\+\\*\\#\\|\\-X]*[ \\t]*" // Change indicator
        		+ "(\\d+)" // Element number
        		+ "[^\\[]+" // Skip description
        		+ "(?:\\[.\\])?" // Usage indicator
        		+ "\\s*\\|?\\s*"
        		+ "Desc: .*"
        		+ "Repr: (a|an|n)(\\.\\.)?(\\d*)$",
        		Pattern.MULTILINE +
        		Pattern.DOTALL);*/

        BufferedReader reader = new BufferedReader(new InputStreamReader(archive, "IBM850"));
        StringBuilder declarationBuffer = new StringBuilder(1000);
        String line;
        Set<String> values = Collections.emptySet();
        /*Pattern codePattern = null;
        boolean codesBegin = false;*/
        Matcher m;

        while ((line = reader.readLine()) != null) {
            int indent;
            for (indent = 0; indent < line.length(); indent++) {
                if (line.charAt(indent) > ' ') {
                    break;
                }
            }
            line = line.trim();

            if (line.isEmpty() || ibm350Dashes.matcher(line).matches()) {
                if (!declarationSearch) {
                    declarationBuffer.setLength(0);
                }

                if (++blankCount > 2) {
                    declarationSearch = true;
                    declarationFound = false;
                }
                continue;
            }

            blankCount = 0;

            if (declarationSearch) {
                if (declarationBuffer.length() > 0) {
                    declarationBuffer.append('\n');
                }
                declarationBuffer.append(line);

                if ((m = declaration.matcher(declarationBuffer.toString())).matches()) {
                    final String id = "DE" + m.group(1).trim();
                    codeList.put(id, values = new TreeSet<>((val1, val2) -> {
                        int result;
                        if ((result = Integer.compare(val1.length(), val2.length())) != 0) {
                            return result;
                        }
                        return val1.compareTo(val2);
                    }));
                    valueFormat = null;
                    declarationSearch = false;
                    declarationFound = true;
                }
            } else if (declarationFound) {
                if (valueFormat == null) {
                    if (line.startsWith("Repr:") && (m = fieldFormat.matcher(line)).matches()) {
                        final int max = Integer.parseInt(m.group(3));
                        final int min = m.group(2) != null ? 0 : max;
                        valueFormat = Pattern.compile("^[\\+\\*\\#\\|\\-X]*\\s*([A-Z0-9]{" + min + ',' + max
                                + "})\\s+.*$");
                    }
                } else {
                    if (indent < 10 && (m = valueFormat.matcher(line)).matches()) {
                        values.add(m.group(1));
                    }
                }
            }
        }

        if (codeList.isEmpty()) {
            throw new IllegalStateException("Missing codelist");
        }

        reader.close();
        return codeList;
    }

    static void loadElements(InputStream archive, Map<String, Set<String>> codeList) throws IOException {
        Pattern def = Pattern.compile(
                                      "^[\\n\\r]+[ \\t]*[\\+\\*\\#\\|\\-X]*[ \\t]*"
                                              + "(\\d+)"
                                              + "([^\\[]+)"
                                              + "(\\[.\\])?"
                                              + "\\s*\\|?\\s*"
                                              + "Desc: (.*)"
                                              + "Repr: (a|an|n)(\\.\\.)?(\\d*)$",
                                      Pattern.MULTILINE +
                                              Pattern.DOTALL);

        BufferedReader reader = new BufferedReader(new InputStreamReader(archive, "IBM850"));
        StringBuilder buffer = new StringBuilder(1000);
        String line;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            buffer.append(line);
            buffer.append('\n');

            if (!line.startsWith("Repr:")) {
                continue;
            }

            Matcher m = def.matcher(buffer.toString());

            if (m.find()) {
                String number = m.group(1).trim();
                String id = "DE" + number;
                String title = m.group(2).trim();
                String desc = m.group(4).trim().replaceAll("[\\r\\n ]+", " ");
                String base = m.group(5);
                final int max = Integer.parseInt(m.group(7));
                final int min = m.group(6) != null ? 0 : max;
                final ElementBaseType baseCode;

                boolean identifier = false;

                if ("a".equals(base)) {
                    if (codeList.containsKey(id)) {
                        identifier = true;
                        baseCode = ElementBaseType.IDENTIFIER;
                    } else {
                        baseCode = ElementBaseType.STRING;
                    }
                } else if ("an".equals(base)) {
                    if (codeList.containsKey(id)) {
                        identifier = true;
                        baseCode = ElementBaseType.IDENTIFIER;
                    } else {
                        baseCode = ElementBaseType.STRING;
                    }
                } else {
                    baseCode = ElementBaseType.DECIMAL;
                }

                final ElementType element = new ElementType();
                element.setName(id);
                element.setCode(Integer.valueOf(number).toString());

                if (includeTitles) {
                    element.setTitle(title);
                    element.setDescription(desc);
                }

                element.setBase(baseCode);
                element.setMinLength(min > 1 ? BigInteger.valueOf(min) : null);
                element.setMaxLength(max != 1 ? BigInteger.valueOf(max) : null);

                if (identifier) {
                    element.setEnumeration(codeList.get(id).stream().map(v -> {
                        var val = new Value();
                        val.setValue(v);
                        return val;
                    }).collect(Collectors.toList()));
                }

                types.put(id, element);
                //schema.getElementTypeOrCompositeTypeOrSegmentType().add(element);

                if (log.isDebugEnabled()) {
                    log.debug(id + " | " + title + " | " + desc + " | " + base + "(" + min + "," + max
                            + ") | identifier: " + identifier);
                }

                buffer.setLength(0);
            }
        }

        reader.close();
    }

    static void loadComposites(InputStream archive) throws IOException {
        Pattern def = Pattern.compile("^[\\n\\r]+[ \\t]+[\\+\\*\\#\\|\\-X]*[ \\t]*([A-Z0-9]{4})(.*)Desc:(.*)[\\n\\r]{2,}",
                                      Pattern.MULTILINE + Pattern.DOTALL);

        Pattern elem = Pattern.compile("^\\d{3}\\s+(\\d+).*\\s+(C|M)\\s+(?:a|an|n)(?:\\.\\.)?\\d*(?:\\s+)?(\\d+,?)*$",
                                       Pattern.MULTILINE + Pattern.DOTALL);

        BufferedReader reader = new BufferedReader(new InputStreamReader(archive, "IBM850"));
        StringBuilder buffer = new StringBuilder(1000);
        String line;

        String id = null;
        String title = null;
        String desc = null;

        while ((line = reader.readLine()) != null) {
            buffer.append(line);
            buffer.append('\n');

            String buf = buffer.toString();
            Matcher m = def.matcher(buf);

            if (m.find()) {
                id = "CE" + m.group(1).trim();
                title = m.group(2).trim();
                desc = m.group(3).trim().replaceAll("[\r\n ]+", " ");

                composite = new CompositeType();
                composite.setName(id);

                if (includeTitles) {
                    composite.setTitle(title);
                    composite.setDescription(desc);
                }

                composite.setSequence(new ArrayList<>());
                types.put(id, composite);

                log.debug("{}|{}|{}", id, title, desc);
                buffer.setLength(0);
            } else {
                Matcher m2 = elem.matcher(buf);

                if (m2.find()) {
                    String refId = "DE" + m2.group(1);
                    ElementStandard elementRef = new ElementStandard();
                    if (!types.containsKey(refId)) {
                        throw new RuntimeException("Referenced ID " + refId + " does not exist");
                    }
                    elementRef.setType(refId);
                    int min = "M".equals(m2.group(2)) ? 1 : 0;
                    elementRef.setMinOccurs(min != 0 ? BigInteger.valueOf(min) : null);
                    composite.getSequence().add(elementRef);

                    log.debug("\t\t{}", refId);
                    buffer.setLength(0);
                } else {
                    Syntax s = loadSyntax(id, buf);
                    if (s != null) {
                        composite.getSyntax().add(s);
                        buffer.setLength(0);
                    }
                }
            }
        }

        reader.close();
    }

    static void loadSegments(InputStream archive) throws IOException {
        Pattern def = Pattern.compile(""
                + "^\\s*"
                + "[\\n\\r]+[ \\t]+[\\+\\*\\#\\|\\-X]*[ \\t]*"
                + "([A-Z0-9]{3})"
                + "(.*)"
                + "Function:(.*)"
                + "[\\n\\r]{2,}",
                                      Pattern.MULTILINE + Pattern.DOTALL);

        Pattern entr = Pattern.compile(""
                + "^"
                + "\\d{3}"
                + "\\s+[\\+\\*\\#\\|\\-X]*\\s+"
                + "([A-Z0-9]{4})"
                + "(.*)"
                + "\\s+"
                + "(C|M)"
                + "\\s+"
                + "(\\d+)?"
                + "(\\s+(?:a|an|n)(?:\\.\\.)?\\d*)?"
                + "(?:\\s+)?(\\d+,?)*"
                + "$",
                                       Pattern.MULTILINE + Pattern.DOTALL);

        BufferedReader reader = new BufferedReader(new InputStreamReader(archive, "IBM850"));
        StringBuilder buffer = new StringBuilder(1000);
        String line;

        String id = null;
        String title = null;
        String desc = null;

        while ((line = reader.readLine()) != null) {
            buffer.append(line);
            buffer.append('\n');

            String buf = buffer.toString();
            Matcher m = def.matcher(buf);

            if (m.find()) {
                id = m.group(1).trim();
                title = m.group(2).trim();
                desc = m.group(3).trim().replaceAll("[\\r\\n ]+", " ");

                segment = new SegmentType();
                segment.setName(id);

                if (includeTitles) {
                    segment.setTitle(title);
                    segment.setDescription(desc);
                }

                segment.setSequence(new ArrayList<>());
                types.put(id, segment);

                log.debug("{}|{}|{}", id, title, desc);

                buffer.setLength(0);
            } else {
                Matcher m2 = entr.matcher(buf);

                if (m2.find()) {
                    BaseType ref;
                    long minOccurs = "M".equals(m2.group(3)) ? 1 : 0;
                    String max = m2.group(4);
                    long maxOccurs = (max == null) ? 1 : Integer.parseInt(max);
                    String refId;

                    if (m2.group(5) != null) {
                        ElementStandard std = new ElementStandard();
                        refId = "DE" + m2.group(1);
                        std.setType(refId);
                        std.setMinOccurs(minOccurs != 0 ? BigInteger.valueOf(minOccurs) : null);
                        std.setMaxOccurs(maxOccurs != 1 ? BigInteger.valueOf(maxOccurs) : null);
                        ref = std;
                    } else {
                        CompositeStandard std = new CompositeStandard();
                        refId = "CE" + m2.group(1);
                        std.setType(refId);
                        std.setMinOccurs(minOccurs != 0 ? BigInteger.valueOf(minOccurs) : null);
                        std.setMaxOccurs(maxOccurs != 1 ? BigInteger.valueOf(maxOccurs) : null);
                        ref = std;
                    }

                    if (!types.containsKey(refId)) {
                        throw new RuntimeException("Referenced ID " + refId + " does not exist");
                    }

                    segment.getSequence().add(ref);

                    if (log.isDebugEnabled()) {
                        log.debug("\t\t{} {}", m2.group(1), m2.group(2).replaceAll("[\\r\\n ]+", " "));
                    }
                    buffer.setLength(0);
                } else {
                    Syntax s = loadSyntax(id, buf);
                    if (s != null) {
                        segment.getSyntax().add(s);
                        buffer.setLength(0);
                    }
                }
            }
        }

        reader.close();
    }

    static void loadMessages(InputStream archive, String version) throws IOException {
        final Pattern loopPattern = Pattern.compile("^\\d+ +.* +([MC]) +(\\d+).*$");
        final Pattern segmentPattern = Pattern.compile("(^\\d+) +[\\+\\*\\#\\|\\-X]? +([A-Z]{3}).* +([MC]) +(\\d+)( *)([^A-Z0-9a-z ]*)$");

        ZipInputStream zis = new ZipInputStream(archive);
        BufferedReader reader;

        ZipEntry entry;
        Schema messageSchema = null;

        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().toUpperCase();
            int uscore = name.indexOf('_');
            int slash = name.lastIndexOf('/');

            if (uscore < 0) {
                if (name.startsWith("CONTRL")) {
                    int period = name.indexOf('.');
                    name = name.substring(slash + 1, period);
                } else {
                    continue;
                }
            } else {
                name = name.substring(slash + 1, uscore);
            }

            String key = "edifact/directories/corrections/" + version + ".messages." + name;

            log.info("Message: {}; release: {}", name, version);
            final InputStream correction = getInputStream(key);
            final Reader input;

            if (correction != null) {
                log.info("Loading corrected message layout: {}", key);
                input = new InputStreamReader(correction, "IBM850");
            } else {
                input = new InputStreamReader(zis, "IBM850");
            }

            reader = new BufferedReader(input);

            String line;
            boolean table = false;
            int lastDepth = 0;
            int loopCount = 0;
            messageSchema = new Schema();
            Deque<List<BaseType>> refStack = new ArrayDeque<>();
            Deque<Integer> loopIds = new ArrayDeque<>();
            Deque<String> loopUses = new ArrayDeque<>();
            Deque<String> loopMaxes = new ArrayDeque<>();
            List<BaseType> refs;

            refs = new ArrayList<>();
            refStack.add(refs);

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("4.3.1  Segment table")) {
                    table = true;
                    continue;
                }

                if (!table) {
                    continue;
                }

                line = line.trim();
                Matcher m = segmentPattern.matcher(line);

                if (m.matches()) {
                    @SuppressWarnings("unused")
                    String pos = m.group(1);
                    String tag = m.group(2);
                    String use = m.group(3);
                    String max = m.group(4);
                    String gap = m.group(5);
                    String lne = m.group(6);

                    int depth;

                    if (gap.isEmpty()) {
                        if (lne.length() > 0) {
                            depth = lastDepth;
                        } else {
                            depth = 0;
                            while (refStack.size() > 1) {
                                int loopId = loopIds.removeLast();
                                lastDepth--;
                                StringBuilder tabs = new StringBuilder(lastDepth);
                                for (int i = 0; i < lastDepth; i++) {
                                    tabs.append('\t');
                                }

                                log.info("{}END-LOOP." + loopId + " | " + lastDepth + "<-- LEVEL ZERO SEGMENT", tabs);

                                refs = refStack.removeLast();
                                String id = String.format("L%04d", loopId);

                                LoopStandard l = new LoopStandard();
                                l.setCode(id);
                                setLoopOccurs(l, loopUses.removeLast(), loopMaxes.removeLast());
                                l.getSequence().addAll(refs);

                                refs = refStack.getLast();
                                refs.add(l);
                            }
                        }
                    } else {
                        depth = lne.length();

                        if (depth > lastDepth) {
                            refs = new ArrayList<>();
                            refStack.add(refs);
                        } else if (depth < lastDepth) {
                            while (refStack.size() > depth + 1) {
                                int loopId = loopIds.removeLast();
                                lastDepth--;
                                StringBuilder tabs = new StringBuilder(lastDepth);
                                for (int i = 0; i < lastDepth; i++) {
                                    tabs.append('\t');
                                }

                                log.info("{}END-LOOP." + loopId + " | " + lastDepth + " <-- DECREASED DEPTH", tabs);

                                refs = refStack.removeLast();
                                String id = String.format("L%04d", loopId);

                                LoopStandard l = new LoopStandard();
                                l.setCode(id);
                                setLoopOccurs(l, loopUses.removeLast(), loopMaxes.removeLast());
                                l.getSequence().addAll(refs);

                                refs = refStack.getLast();
                                refs.add(l);
                            }
                            refs = refStack.getLast();
                        }
                    }

                    if (!"UNH".equals(tag) && !"UNT".equals(tag)) {
                        log.info("{}" + tag + " | " + use + " | " + max + " | " + depth, "\t".repeat(depth));

                        SegmentStandard seg = new SegmentStandard();

                        if ("M".equals(use)) {
                            seg.setMinOccurs(BigInteger.valueOf(1));
                        }

                        int maxOccurs = Integer.parseInt(max);
                        if (maxOccurs > 1) {
                            seg.setMaxOccurs(BigInteger.valueOf(maxOccurs));
                        }

                        //Object referenced = types.get(tag);
                        seg.setType(fetchSegment(messageSchema, tag));
                        //refs.add(seg);
                        refStack.getLast().add(seg);
                    }

                    if (gap.isEmpty() && lne.length() > 0) {
                        int closeCount;

                        if (lne.indexOf('-') > 0) {
                            closeCount = lne.lastIndexOf('+') - lne.lastIndexOf('-');
                        } else {
                            closeCount = lne.lastIndexOf('┘') - lne.lastIndexOf('─');
                        }

                        while (closeCount-- > 0) {
                            lastDepth = --depth;
                            int loopId = loopIds.removeLast();
                            log.info("{}END-LOOP." + loopId + " | " + lastDepth + " <-- SEGMENT LINES", "\t".repeat(lastDepth));

                            refs = refStack.removeLast();
                            String id = String.format("L%04d", loopId);

                            LoopStandard l = new LoopStandard();
                            l.setCode(id);
                            setLoopOccurs(l, loopUses.removeLast(), loopMaxes.removeLast());
                            l.getSequence().addAll(refs);

                            refs = refStack.getLast();
                            refs.add(l);
                        }
                    } else {
                        lastDepth = depth;
                    }
                } else if ((m = loopPattern.matcher(line)).matches()) {
                    loopCount++;
                    loopIds.add(loopCount);
                    loopUses.addLast(m.group(1));
                    loopMaxes.addLast(m.group(2));
                    StringBuilder tabs = new StringBuilder(lastDepth);
                    for (int i = 0; i < lastDepth; i++) {
                        tabs.append('\t');
                    }
                    log.info("{}LOOP.{} | {} | {} | {}", tabs, String.valueOf(loopCount), loopUses.peekLast(), loopMaxes.peekLast(), lastDepth);

                    refs = new ArrayList<>();
                    refStack.add(refs);
                    lastDepth++;
                } else if (line.isEmpty()) {
                    while (refStack.size() > 1) {
                        int loopId = loopIds.removeLast();
                        lastDepth--;
                        StringBuilder tabs = new StringBuilder(lastDepth);
                        for (int i = 0; i < lastDepth; i++) {
                            tabs.append('\t');
                        }

                        log.info("{}END-LOOP." + loopId + " | " + lastDepth + "<-- BLANK LINE", tabs);

                        refs = refStack.removeLast();
                        String id = String.format("L%04d", loopId);

                        LoopStandard l = new LoopStandard();
                        l.setCode(id);
                        setLoopOccurs(l, loopUses.removeLast(), loopMaxes.removeLast());
                        l.getSequence().addAll(refs);

                        refs = refStack.getLast();
                        refs.add(l);
                    }
                    lastDepth = 0;
                } else {
                    int depth = line.trim().length();
                    if (depth < lastDepth) {
                        while (refStack.size() > depth + 1) {
                            int loopId = loopIds.removeLast();
                            lastDepth--;
                            StringBuilder tabs = new StringBuilder(lastDepth);
                            for (int i = 0; i < lastDepth; i++) {
                                tabs.append('\t');
                            }

                            log.info("{}END-LOOP." + loopId + " | " + lastDepth + " <-------- NONBLANK", tabs);

                            refs = refStack.removeLast();
                            String id = String.format("L%04d", loopId);

                            LoopStandard l = new LoopStandard();
                            l.setCode(id);
                            setLoopOccurs(l, loopUses.removeLast(), loopMaxes.removeLast());
                            l.getSequence().addAll(refs);

                            refs = refStack.getLast();
                            refs.add(l);
                        }
                        refs = refStack.getLast();
                        lastDepth = depth;
                    }
                }
            }

            Transaction main = new Transaction();
            main.setSequence(refStack.getFirst());

            messageSchema.getLayout().add(main);

            messages.put(name, messageSchema);
        }
    }

    static void setLoopOccurs(LoopStandard loop, String loopUse, String loopMax) {
        if ("M".equals(loopUse)) {
            loop.setMinOccurs(BigInteger.ONE);
        }

        int maxOccurs = Integer.parseInt(loopMax);

        if (maxOccurs > 1) {
            loop.setMaxOccurs(BigInteger.valueOf(maxOccurs));
        }
    }

    static Syntax loadSyntax(String id, String buf) {
        Matcher m3 = syntax.matcher(buf);

        if (m3.find()) {
            String type = m3.group(1);
            String[] items = m3.group(2).split(",");
            if (log.isInfoEnabled()) {
                log.info("Structure {} has syntax: {}, items = {}", id, type, Arrays.toString(items));
            }

            Syntax s = new Syntax();

            s.getPosition().addAll(Arrays.stream(items)
                                         .map(String::trim)
                                         .map(BigInteger::new)
                                         .map(value -> value.divide(BigInteger.valueOf(10)))
                                         .collect(Collectors.toList()));

            switch (type) {
            case "D1":
                s.setType(SyntaxType.SINGLE);
                break;
            case "D2":
                s.setType(SyntaxType.PAIRED);
                break;
            case "D3":
                s.setType(SyntaxType.REQUIRED);
                break;
            case "D4":
                s.setType(SyntaxType.EXCLUSION);
                break;
            case "D5":
                s.setType(SyntaxType.CONDITIONAL);
                break;
            case "D6":
                s.setType(SyntaxType.LIST);
                break;
            case "D7":
                log.warn("Unsupported syntax {} (IF FIRST, THEN NONE OF THE OTHERS) for {}", type, id);
                break;
            default:
                break;
            }

            return s;
        }

        return null;
    }

    static void buildControlStructure(Schema schema) {
        Interchange main = new Interchange();
        main.setHeader(fetchSegment(schema, "UNB"));
        main.setTrailer(fetchSegment(schema, "UNZ"));

        GroupControlType group = new GroupControlType();
        group.setHeader(fetchSegment(schema, "UNG"));
        group.setTrailer(fetchSegment(schema, "UNE"));
        TransactionControlType groupTransaction = new TransactionControlType();
        groupTransaction.setHeader(fetchSegment(schema, "UNH"));
        groupTransaction.setTrailer(fetchSegment(schema, "UNT"));
        group.setTransaction(groupTransaction);

        TransactionControlType transaction = new TransactionControlType();
        transaction.setHeader(fetchSegment(schema, "UNH"));
        transaction.setTrailer(fetchSegment(schema, "UNT"));

        Interchange.Sequence sequence = new Interchange.Sequence();
        sequence.setGroup(group);
        sequence.setTransaction(transaction);
        main.setSequence(sequence);

        Syntax interchangeSyntax = new Syntax();
        interchangeSyntax.setType(SyntaxType.SINGLE);
        // UNB is in position 1
        interchangeSyntax.getPosition().add(BigInteger.valueOf(2));
        interchangeSyntax.getPosition().add(BigInteger.valueOf(3));
        main.getSyntax().add(interchangeSyntax);

        schema.getLayout().add(main);
    }

    static String fetchSegment(Schema messageSchema, String id) {
        addIfAbsent(messageSchema, id);
        return id;
    }

    static void addIfAbsent(Schema messageSchema, String typeId) {
        BaseType type = types.get(typeId);
        List<BaseType> schemaTypes = messageSchema.getTypes();

        if (!schemaTypes.contains(type)) {
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
}
