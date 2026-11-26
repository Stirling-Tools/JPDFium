package stirling.software.jpdfium.instance;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;

/**
 * Defines its own copy of every class under the isolated prefixes, reading the bytes from the
 * parent, and delegates everything else. Each copy has its own statics, so JPDFium's native
 * bindings and its global PDFium lock exist once per loader.
 */
final class IsolatingClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final List<String> isolatedPrefixes;

    IsolatingClassLoader(String name, ClassLoader parent, List<String> isolatedPrefixes) {
        super(name, parent);
        this.isolatedPrefixes = List.copyOf(isolatedPrefixes);
    }

    boolean isolates(String className) {
        for (String prefix : isolatedPrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isolates(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                type = defineFromParent(name);
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }

    private Class<?> defineFromParent(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        URL url = getParent().getResource(path);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes;
        try (InputStream in = url.openStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String pkg = name.substring(0, dot);
            if (getDefinedPackage(pkg) == null) {
                try {
                    definePackage(pkg, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException alreadyDefined) {
                    // Another thread defined it first.
                }
            }
        }
        CodeSource source = new CodeSource(codeBase(url, path), (CodeSigner[]) null);
        return defineClass(name, bytes, 0, bytes.length, new ProtectionDomain(source, null, this, null));
    }

    private static URL codeBase(URL classUrl, String path) {
        String spec = classUrl.toString();
        if (!spec.endsWith(path)) {
            return classUrl;
        }
        try {
            return URI.create(spec.substring(0, spec.length() - path.length())).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            return classUrl;
        }
    }
}
