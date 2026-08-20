package stirling.software.jpdfium.panama;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachabilityMetadataVerificationTest {

    private record Signature(String returnType, List<String> parameterTypes) {
        @Override
        public String toString() {
            return "(" + String.join(", ", parameterTypes) + ") -> " + returnType;
        }
    }

    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
            Map.entry("C_INT", "jint"),
            Map.entry("JAVA_INT", "jint"),
            Map.entry("C_LONG", "jlong"),
            Map.entry("C_LONG_LONG", "jlong"),
            Map.entry("JAVA_LONG", "jlong"),
            Map.entry("C_DOUBLE", "jdouble"),
            Map.entry("JAVA_DOUBLE", "jdouble"),
            Map.entry("C_FLOAT", "jfloat"),
            Map.entry("JAVA_FLOAT", "jfloat"),
            Map.entry("C_CHAR", "jbyte"),
            Map.entry("JAVA_BYTE", "jbyte"),
            Map.entry("C_SHORT", "jshort"),
            Map.entry("JAVA_SHORT", "jshort"),
            Map.entry("C_BOOL", "jint"),
            Map.entry("JAVA_BOOLEAN", "jboolean"),
            Map.entry("C_POINTER", "void*"),
            Map.entry("ADDRESS", "void*")
    );

    @Test
    void allCodeFunctionDescriptorsAreRegisteredInReachabilityMetadata() throws Exception {
        Set<Signature> registeredDowncalls = loadRegisteredDowncalls();
        Set<Signature> registeredUpcalls = loadRegisteredUpcalls();

        Path srcMain = findSourceDir();
        assertTrue(Files.exists(srcMain), "Source directory not found: " + srcMain);

        List<String> missing = new ArrayList<>();

        Pattern fdPattern = Pattern.compile("FunctionDescriptor\\s*\\.\\s*(of|ofVoid)\\s*\\((.*?)\\)", Pattern.DOTALL);

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path javaFile : javaFiles) {
                String content = Files.readString(javaFile);
                Matcher matcher = fdPattern.matcher(content);
                while (matcher.find()) {
                    String kind = matcher.group(1);
                    String rawArgs = matcher.group(2);

                    // Remove comments
                    rawArgs = rawArgs.replaceAll("//.*", "");
                    rawArgs = rawArgs.replaceAll("/\\*.*?\\*/", "");

                    String[] tokens = Arrays.stream(rawArgs.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toArray(String[]::new);

                    List<String> cleaned = new ArrayList<>();
                    for (String token : tokens) {
                        String t = token
                                .replace("JpdfiumH.", "")
                                .replace("ValueLayout.", "")
                                .replace("Linker.Option.", "")
                                .trim();
                        cleaned.add(t);
                    }

                    String ret;
                    List<String> params = new ArrayList<>();

                    if ("ofVoid".equals(kind)) {
                        ret = "void";
                        for (String t : cleaned) {
                            String mapped = TYPE_MAP.get(t);
                            if (mapped != null) params.add(mapped);
                        }
                    } else {
                        if (cleaned.isEmpty()) continue;
                        ret = TYPE_MAP.get(cleaned.get(0));
                        if (ret == null) continue;
                        for (int i = 1; i < cleaned.size(); i++) {
                            String mapped = TYPE_MAP.get(cleaned.get(i));
                            if (mapped != null) params.add(mapped);
                        }
                    }

                    Signature sig = new Signature(ret, params);
                    if (!registeredDowncalls.contains(sig) && !registeredUpcalls.contains(sig)) {
                        missing.add(javaFile.getFileName() + ": " + sig);
                    }
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "Found FunctionDescriptors in source code not registered in reachability-metadata.json:\n" +
                String.join("\n", missing));
    }

    private Path findSourceDir() {
        Path p = Path.of("src/main/java");
        if (Files.exists(p)) return p;
        p = Path.of("jpdfium/src/main/java");
        if (Files.exists(p)) return p;
        return Path.of("../jpdfium/src/main/java");
    }

    private Set<Signature> loadRegisteredDowncalls() throws Exception {
        return loadRegisteredSection("downcalls");
    }

    private Set<Signature> loadRegisteredUpcalls() throws Exception {
        return loadRegisteredSection("upcalls");
    }

    private Set<Signature> loadRegisteredSection(String sectionName) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/com.stirling/jpdfium/reachability-metadata.json")) {
            if (is == null) {
                throw new IllegalStateException("reachability-metadata.json not found on classpath");
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return parseSignatures(json, sectionName);
        }
    }

    private Set<Signature> parseSignatures(String json, String sectionName) {
        Set<Signature> signatures = new HashSet<>();
        int foreignIdx = json.indexOf("\"foreign\"");
        if (foreignIdx == -1) return signatures;
        int sectionIdx = json.indexOf("\"" + sectionName + "\"", foreignIdx);
        if (sectionIdx == -1) return signatures;
        int arrayStart = json.indexOf('[', sectionIdx);
        if (arrayStart == -1) return signatures;

        int depth = 0;
        int arrayEnd = -1;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    arrayEnd = i;
                    break;
                }
            }
        }
        if (arrayEnd == -1) return signatures;

        int i = arrayStart + 1;
        while (i < arrayEnd) {
            char c = json.charAt(i);
            if (c == '{') {
                int objStart = i;
                int objDepth = 0;
                int objEnd = -1;
                for (int j = objStart; j <= arrayEnd; j++) {
                    char oc = json.charAt(j);
                    if (oc == '{') objDepth++;
                    else if (oc == '}') {
                        objDepth--;
                        if (objDepth == 0) {
                            objEnd = j;
                            break;
                        }
                    }
                }
                if (objEnd == -1) break;

                String obj = json.substring(objStart, objEnd + 1);
                Matcher retMatcher = Pattern.compile("\"returnType\"\\s*:\\s*\"([^\"]+)\"").matcher(obj);
                if (retMatcher.find()) {
                    String ret = retMatcher.group(1);
                    if ("float".equals(ret)) ret = "jfloat";

                    List<String> params = new ArrayList<>();
                    Matcher paramsMatcher = Pattern.compile("\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(obj);
                    if (paramsMatcher.find()) {
                        String paramsStr = paramsMatcher.group(1);
                        Matcher itemMatcher = Pattern.compile("\"([^\"]+)\"").matcher(paramsStr);
                        while (itemMatcher.find()) {
                            String param = itemMatcher.group(1);
                            if ("float".equals(param)) param = "jfloat";
                            params.add(param);
                        }
                    }
                    signatures.add(new Signature(ret, params));
                }
                i = objEnd + 1;
            } else {
                i++;
            }
        }
        return signatures;
    }
}
