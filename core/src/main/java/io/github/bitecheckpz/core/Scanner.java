package io.github.bitecheckpz.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Static scan of a mod jar for APIs a reviewer must look at.
 *
 * <p>The scanner reads the constant pool of every class: referenced classes, referenced fields
 * and methods, and string constants. It does not execute anything. It sorts, it does not decide:
 * <ul>
 *   <li>{@link Severity#HARD} findings (process start, native code, runtime code loading, Unsafe,
 *       nested archives, heavy obfuscation) block a release automatically.</li>
 *   <li>{@link Severity#SOFT} findings (network, file access, reflection, …) must be explained in
 *       every review.</li>
 * </ul>
 * A clean scan does not mean a mod is safe; reflection with computed names can hide calls.
 */
public final class Scanner {

    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_CLASS_BYTES = 16L * 1024 * 1024;

    private Scanner() {
    }

    public enum Severity {
        HARD, SOFT
    }

    public record Finding(Severity severity, String rule, String where, String detail) {
    }

    public record Report(String jarSha256, int classCount, List<Finding> findings) {
        public boolean hasHardFindings() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.HARD);
        }

        public long count(Severity s) {
            return findings.stream().filter(f -> f.severity() == s).count();
        }
    }

    private record Rule(Severity severity, String rule, String what) {
    }

    // ---- rules on referenced classes (internal names, prefix match) ----

    private static final List<Object[]> CLASS_RULES = List.of(
            new Object[] {"java/lang/ProcessBuilder", new Rule(Severity.HARD, "process", "starts external programs")},
            new Object[] {"java/lang/ProcessHandle*", new Rule(Severity.HARD, "process", "controls other processes")},
            new Object[] {"com/sun/jna/", new Rule(Severity.HARD, "native", "calls native code (JNA)")},
            new Object[] {"java/lang/foreign/", new Rule(Severity.HARD, "native", "calls native code (FFM API)")},
            new Object[] {"java/net/URLClassLoader", new Rule(Severity.HARD, "code-loading", "loads classes at runtime")},
            new Object[] {"java/security/SecureClassLoader", new Rule(Severity.HARD, "code-loading", "defines classes at runtime")},
            new Object[] {"javax/script/", new Rule(Severity.HARD, "code-loading", "runs script code")},
            new Object[] {"javax/tools/", new Rule(Severity.HARD, "code-loading", "compiles code at runtime")},
            new Object[] {"sun/misc/Unsafe", new Rule(Severity.HARD, "unsafe", "uses sun.misc.Unsafe")},
            new Object[] {"jdk/internal/", new Rule(Severity.HARD, "unsafe", "uses JDK internals")},
            new Object[] {"java/lang/instrument/", new Rule(Severity.HARD, "unsafe", "uses the instrumentation API")},
            new Object[] {"java/net/http/", new Rule(Severity.SOFT, "network", "makes HTTP requests")},
            new Object[] {"java/net/Socket", new Rule(Severity.SOFT, "network", "opens network sockets")},
            new Object[] {"java/net/ServerSocket", new Rule(Severity.SOFT, "network", "listens on the network")},
            new Object[] {"java/net/DatagramSocket", new Rule(Severity.SOFT, "network", "sends UDP packets")},
            new Object[] {"java/net/URL", new Rule(Severity.SOFT, "network", "opens URLs")},
            new Object[] {"java/net/URLConnection", new Rule(Severity.SOFT, "network", "opens URLs")},
            new Object[] {"java/net/HttpURLConnection", new Rule(Severity.SOFT, "network", "makes HTTP requests")},
            new Object[] {"javax/net/", new Rule(Severity.SOFT, "network", "uses TLS/sockets")},
            new Object[] {"java/nio/channels/SocketChannel", new Rule(Severity.SOFT, "network", "opens network sockets")},
            new Object[] {"java/io/File*", new Rule(Severity.SOFT, "files", "accesses files")},
            new Object[] {"java/io/RandomAccessFile", new Rule(Severity.SOFT, "files", "accesses files")},
            new Object[] {"java/nio/file/Files", new Rule(Severity.SOFT, "files", "accesses files")},
            new Object[] {"java/nio/channels/FileChannel", new Rule(Severity.SOFT, "files", "accesses files")},
            new Object[] {"java/lang/reflect/", new Rule(Severity.SOFT, "reflection", "uses reflection")},
            new Object[] {"java/lang/invoke/MethodHandles*", new Rule(Severity.SOFT, "reflection", "uses method handles")},
            new Object[] {"java/io/ObjectInputStream", new Rule(Severity.SOFT, "serialization", "deserializes Java objects")},
            new Object[] {"java/awt/Robot", new Rule(Severity.SOFT, "system", "simulates keyboard/mouse input")},
            new Object[] {"java/awt/datatransfer/", new Rule(Severity.SOFT, "system", "accesses the clipboard")},
            new Object[] {"java/awt/Desktop", new Rule(Severity.SOFT, "system", "opens files or URLs in other programs")});

    // ---- rules on referenced members: owner.name ----

    private static final List<Object[]> MEMBER_RULES = List.of(
            new Object[] {"java/lang/Runtime.exec", new Rule(Severity.HARD, "process", "starts external programs")},
            new Object[] {"java/lang/System.load", new Rule(Severity.HARD, "native", "loads a native library")},
            new Object[] {"java/lang/System.loadLibrary", new Rule(Severity.HARD, "native", "loads a native library")},
            new Object[] {"java/lang/Runtime.load", new Rule(Severity.HARD, "native", "loads a native library")},
            new Object[] {"java/lang/Runtime.loadLibrary", new Rule(Severity.HARD, "native", "loads a native library")},
            new Object[] {"java/lang/ClassLoader.defineClass", new Rule(Severity.HARD, "code-loading", "defines classes at runtime")},
            new Object[] {"java/lang/invoke/MethodHandles$Lookup.defineClass", new Rule(Severity.HARD, "code-loading", "defines classes at runtime")},
            new Object[] {"java/lang/invoke/MethodHandles$Lookup.defineHiddenClass", new Rule(Severity.HARD, "code-loading", "defines hidden classes")},
            new Object[] {"java/lang/Class.forName", new Rule(Severity.SOFT, "reflection", "looks up classes by name")},
            new Object[] {"java/lang/System.getenv", new Rule(Severity.SOFT, "system", "reads environment variables")},
            new Object[] {"java/lang/System.exit", new Rule(Severity.SOFT, "system", "can terminate the game")},
            new Object[] {"java/lang/Runtime.halt", new Rule(Severity.SOFT, "system", "can terminate the game")});

    // ---- suspicious string constants (case-insensitive substring) ----

    private static final List<String> SENSITIVE_STRINGS = List.of(
            ".ssh", "id_rsa", "login data", "cookies", "local state", "wallet", "discord", "leveldb",
            "loginusers.vdf", "ssfn", "config.vdf", "token", "password", "appdata", "powershell", "cmd.exe",
            "/bin/sh", "/bin/bash", "webhook", "pastebin", "http://", "https://");

    private static final Set<String> NATIVE_SUFFIXES = Set.of(".dll", ".so", ".dylib", ".jnilib", ".exe");
    private static final Set<String> ARCHIVE_SUFFIXES = Set.of(".jar", ".zip", ".war", ".class.bin");

    public static Report scan(Path jar) throws IOException {
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int classCount = 0;
        int obfuscatedNames = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.size() > MAX_ENTRIES) {
                throw new IOException("too many entries in " + jar);
            }
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (entry.isDirectory()) {
                    continue;
                }
                if (NATIVE_SUFFIXES.stream().anyMatch(lower::endsWith)) {
                    add(findings, seen, new Finding(Severity.HARD, "native", name, "contains a native binary"));
                    continue;
                }
                if (ARCHIVE_SUFFIXES.stream().anyMatch(lower::endsWith)) {
                    add(findings, seen, new Finding(Severity.HARD, "nested-archive", name,
                            "contains another archive that cannot be reviewed in place"));
                    continue;
                }
                if (!lower.endsWith(".class")) {
                    continue;
                }
                if (entry.getSize() > MAX_CLASS_BYTES) {
                    add(findings, seen, new Finding(Severity.HARD, "obfuscation", name, "class file is unusually large"));
                    continue;
                }
                classCount++;
                if (looksObfuscated(name)) {
                    obfuscatedNames++;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readNBytes((int) MAX_CLASS_BYTES + 1);
                }
                String where = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                ConstantPool pool;
                try {
                    pool = ConstantPool.read(bytes);
                } catch (IOException e) {
                    add(findings, seen, new Finding(Severity.HARD, "obfuscation", where,
                            "class file cannot be parsed: " + e.getMessage()));
                    continue;
                }
                applyRules(pool, where, findings, seen);
            }
        }
        if (classCount >= 10 && obfuscatedNames * 10 >= classCount * 8) {
            add(findings, seen, new Finding(Severity.HARD, "obfuscation", "(jar)",
                    obfuscatedNames + " of " + classCount + " classes have obfuscated names"));
        } else if (classCount >= 10 && obfuscatedNames * 2 >= classCount) {
            add(findings, seen, new Finding(Severity.SOFT, "obfuscation", "(jar)",
                    obfuscatedNames + " of " + classCount + " classes have very short names"));
        }
        List<Finding> grouped = group(findings);
        grouped.sort((a, b) -> a.severity() != b.severity()
                ? a.severity().compareTo(b.severity())
                : (a.rule() + a.where()).compareTo(b.rule() + b.where()));
        return new Report(Hashing.sha256Hex(jar), classCount, grouped);
    }

    /** One finding per severity, rule and class; the details of merged findings are joined. */
    private static List<Finding> group(List<Finding> findings) {
        java.util.Map<String, List<Finding>> byKey = new java.util.LinkedHashMap<>();
        for (Finding f : findings) {
            byKey.computeIfAbsent(f.severity() + "|" + f.rule() + "|" + f.where(), k -> new ArrayList<>()).add(f);
        }
        List<Finding> result = new ArrayList<>();
        for (List<Finding> group : byKey.values()) {
            Finding first = group.get(0);
            if (group.size() == 1) {
                result.add(first);
                continue;
            }
            Set<String> parts = new LinkedHashSet<>();
            for (Finding f : group) {
                parts.add(f.detail());
            }
            List<String> shown = parts.stream().limit(4).toList();
            String detail = String.join("; ", shown) + (parts.size() > 4 ? "; +" + (parts.size() - 4) + " more" : "");
            result.add(new Finding(first.severity(), first.rule(), first.where(), detail));
        }
        return result;
    }

    private static void applyRules(ConstantPool pool, String where, List<Finding> findings, Set<String> seen) {
        for (String cls : pool.classes) {
            String c = stripArray(cls);
            for (Object[] r : CLASS_RULES) {
                if (matches(c, (String) r[0])) {
                    Rule rule = (Rule) r[1];
                    add(findings, seen, new Finding(rule.severity(), rule.rule(), where,
                            rule.what() + " (" + c.replace('/', '.') + ")"));
                }
            }
        }
        for (String member : pool.members) {
            for (Object[] r : MEMBER_RULES) {
                if (member.equals(r[0])) {
                    Rule rule = (Rule) r[1];
                    add(findings, seen, new Finding(rule.severity(), rule.rule(), where,
                            rule.what() + " (" + member.replace('/', '.') + ")"));
                }
            }
        }
        for (String s : pool.strings) {
            String lower = s.toLowerCase(Locale.ROOT);
            for (String needle : SENSITIVE_STRINGS) {
                if (lower.contains(needle)) {
                    String shown = s.length() > 80 ? s.substring(0, 77) + "..." : s;
                    add(findings, seen, new Finding(Severity.SOFT, "strings", where,
                            "string constant mentions '" + needle + "': \"" + shown + "\""));
                    break;
                }
            }
        }
    }

    /**
     * Pattern forms: "pkg/" matches a whole package, "Name*" any class starting with Name,
     * otherwise the exact class and its nested classes.
     */
    static boolean matches(String cls, String pattern) {
        if (pattern.endsWith("/")) {
            return cls.startsWith(pattern);
        }
        if (pattern.endsWith("*")) {
            return cls.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return cls.equals(pattern) || cls.startsWith(pattern + "$");
    }

    private static String stripArray(String cls) {
        String c = cls;
        while (c.startsWith("[")) {
            c = c.substring(1);
        }
        if (c.startsWith("L") && c.endsWith(";")) {
            c = c.substring(1, c.length() - 1);
        }
        return c;
    }

    private static boolean looksObfuscated(String classFile) {
        String simple = classFile.substring(classFile.lastIndexOf('/') + 1, classFile.length() - ".class".length());
        int dollar = simple.indexOf('$');
        if (dollar >= 0) {
            return false; // inner and anonymous classes are naturally short
        }
        if (simple.equals("Main")) {
            return false;
        }
        boolean nonAscii = simple.chars().anyMatch(ch -> ch > 127 || Character.isISOControl(ch));
        return nonAscii || simple.length() <= 2;
    }

    private static void add(List<Finding> findings, Set<String> seen, Finding f) {
        if (seen.add(f.severity() + "|" + f.rule() + "|" + f.where() + "|" + f.detail())) {
            findings.add(f);
        }
    }

    /** Reads only the constant pool of a class file. */
    static final class ConstantPool {
        final Set<String> classes = new LinkedHashSet<>();
        final Set<String> members = new LinkedHashSet<>();
        final Set<String> strings = new LinkedHashSet<>();

        static ConstantPool read(byte[] bytes) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            int[] stringIndex = new int[count];
            int[][] memberRef = new int[count][];
            int[][] nameAndType = new int[count][];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++; // takes two slots
                    }
                    case 7 -> classNameIndex[i] = in.readUnsignedShort();
                    case 8 -> stringIndex[i] = in.readUnsignedShort();
                    case 9, 10, 11 -> memberRef[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                    case 12 -> nameAndType[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    case 16, 19, 20 -> in.readUnsignedShort();
                    case 17, 18 -> {
                        in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag);
                }
            }
            ConstantPool pool = new ConstantPool();
            for (int i = 1; i < count; i++) {
                if (classNameIndex[i] != 0) {
                    pool.classes.add(at(utf8, classNameIndex[i]));
                }
                if (stringIndex[i] != 0) {
                    pool.strings.add(at(utf8, stringIndex[i]));
                }
            }
            for (int i = 1; i < count; i++) {
                if (memberRef[i] != null) {
                    String owner = at(utf8, classNameIndex[check(memberRef[i][0], count)]);
                    int[] nat = nameAndType[check(memberRef[i][1], count)];
                    if (nat == null) {
                        throw new IOException("broken member reference");
                    }
                    pool.members.add(owner + "." + at(utf8, nat[0]));
                }
            }
            return pool;
        }

        private static int check(int index, int count) throws IOException {
            if (index <= 0 || index >= count) {
                throw new IOException("constant pool index out of range");
            }
            return index;
        }

        private static String at(String[] utf8, int index) throws IOException {
            if (index <= 0 || index >= utf8.length || utf8[index] == null) {
                throw new IOException("constant pool index does not point to a string");
            }
            return utf8[index];
        }
    }
}
