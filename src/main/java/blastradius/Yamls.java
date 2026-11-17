package blastradius;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Small helpers around SnakeYAML (safe loading only) plus typed getters. */
public final class Yamls {

    private Yamls() {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Reader reader) {
        Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    public static Map<String, Object> readFile(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(r);
        }
    }

    /** Reads a classpath resource such as "/blastradius/rules/default.yaml". */
    public static Map<String, Object> readResource(String path) throws IOException {
        try (InputStream in = Yamls.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing resource " + path);
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    public static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    public static boolean bool(Object o, boolean def) {
        return o instanceof Boolean b ? b : def;
    }

    public static int integer(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    /** A scalar becomes a one-element list; null becomes an empty list. */
    public static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(String.valueOf(x));
            }
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map) {
                    out.add((Map<String, Object>) x);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
}
