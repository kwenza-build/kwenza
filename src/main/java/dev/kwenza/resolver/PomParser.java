package dev.kwenza.resolver;

import dev.kwenza.context.Result;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses just the parts of a Maven POM that Kwenza's resolver needs. */
final class PomParser {

    record RawDependency(String group, String artifact, String version, String scope, boolean optional) {
    }

    record ParentRef(String group, String artifact, String version) {
    }

    record ParsedPom(
            String group,
            String artifact,
            String version,
            String packaging,
            ParentRef parent,
            Map<String, String> properties,
            List<RawDependency> dependencies,
            List<RawDependency> managedDependencies) {
    }

    /** The parts of the POM kwenza needs, or a failure if it isn't a POM kwenza can read. */
    static Result<ParsedPom> parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();

            ParentRef parent = null;
            Element parentEl = firstChild(root, "parent");
            if (parentEl != null) {
                parent = new ParentRef(text(parentEl, "groupId"), text(parentEl, "artifactId"), text(parentEl, "version"));
            }

            String group = textOrNull(root, "groupId");
            if (group == null && parent != null) group = parent.group();
            String version = textOrNull(root, "version");
            if (version == null && parent != null) version = parent.version();
            String artifact = text(root, "artifactId");
            String packaging = textOrNull(root, "packaging");
            if (packaging == null) packaging = "jar";

            Map<String, String> properties = new LinkedHashMap<>();
            Element propsEl = firstChild(root, "properties");
            if (propsEl != null) {
                NodeList children = propsEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node n = children.item(i);
                    if (n instanceof Element el) {
                        properties.put(el.getTagName(), el.getTextContent().trim());
                    }
                }
            }

            List<RawDependency> dependencies = parseDependencyList(firstChild(root, "dependencies"));
            List<RawDependency> managed = new ArrayList<>();
            Element dm = firstChild(root, "dependencyManagement");
            if (dm != null) {
                managed = parseDependencyList(firstChild(dm, "dependencies"));
            }

            return Result.ok(new ParsedPom(group, artifact, version, packaging, parent, properties, dependencies, managed));
        } catch (SAXException | IOException e) {
            return Result.err("Failed to parse POM: " + e.getMessage());
        } catch (MissingElement e) {
            return Result.err(e.getMessage());
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the JDK's XML parser doesn't support secure processing", e); // a bug
        }
    }

    private static List<RawDependency> parseDependencyList(Element dependenciesEl) {
        List<RawDependency> list = new ArrayList<>();
        if (dependenciesEl == null) return list;
        NodeList depNodes = dependenciesEl.getChildNodes();
        for (int i = 0; i < depNodes.getLength(); i++) {
            Node n = depNodes.item(i);
            if (n instanceof Element el && el.getTagName().equals("dependency")) {
                String group = text(el, "groupId");
                String artifact = text(el, "artifactId");
                String version = textOrNull(el, "version");
                String scope = textOrNull(el, "scope");
                if (scope == null) scope = "compile";
                boolean optional = "true".equalsIgnoreCase(textOrNull(el, "optional"));
                list.add(new RawDependency(group, artifact, version, scope, optional));
            }
        }
        return list;
    }

    private static Element firstChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && el.getTagName().equals(tagName)) {
                return el;
            }
        }
        return null;
    }

    private static String textOrNull(Element parent, String tagName) {
        Element el = firstChild(parent, tagName);
        return el == null ? null : el.getTextContent().trim();
    }

    private static String text(Element parent, String tagName) {
        String t = textOrNull(parent, tagName);
        if (t == null) {
            throw new MissingElement("Missing required <" + tagName + "> in POM");
        }
        return t;
    }

    /** Bails out of the nested element lookups above; caught in {@link #parse}, never thrown out of it. */
    private static final class MissingElement extends RuntimeException {
        MissingElement(String message) {
            super(message, null, false, false);
        }
    }
}
