import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Patch;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Weaves the @Patch classes into the game classes as ZombieBuddy does, then verifies and access-checks them. */
public class WeaveCheck {
    static final Map<String, String> DESC = new HashMap<>();
    static {
        DESC.put(Type.getDescriptor(Patch.OnEnter.class), Type.getDescriptor(Advice.OnMethodEnter.class));
        DESC.put(Type.getDescriptor(Patch.OnExit.class), Type.getDescriptor(Advice.OnMethodExit.class));
        DESC.put(Type.getDescriptor(Patch.Return.class), Type.getDescriptor(Advice.Return.class));
        DESC.put(Type.getDescriptor(Patch.This.class), Type.getDescriptor(Advice.This.class));
        DESC.put(Type.getDescriptor(Patch.Argument.class), Type.getDescriptor(Advice.Argument.class));
    }
    static final Type NO_EXCEPTION_HANDLER = Type.getType("Lnet/bytebuddy/asm/Advice$NoExceptionHandler;");

    public static void main(String[] args) throws Exception {
        File gameJar = new File(args[0]), zbJar = new File(args[1]), modDir = new File(args[2]);
        // --jar=path for other mods we patch (Viewpoint).
        List<File> extraJars = new ArrayList<>();
        List<String> patches = new ArrayList<>();
        for (String a : List.of(args).subList(3, args.length)) {
            if (a.startsWith("--jar=")) extraJars.add(new File(a.substring(6)));
            else patches.add(a);
        }

        List<ClassFileLocator> locators = new ArrayList<>(List.of(
                new ClassFileLocator.ForFolder(modDir),
                ClassFileLocator.ForJarFile.of(gameJar),
                ClassFileLocator.ForJarFile.of(zbJar)));
        for (File j : extraJars) locators.add(ClassFileLocator.ForJarFile.of(j));
        locators.add(ClassFileLocator.ForClassLoader.ofPlatformLoader());
        ClassFileLocator base = new ClassFileLocator.Compound(locators);

        Map<String, byte[]> rewritten = new HashMap<>();
        for (String p : patches) rewritten.put(p, rewrite(base.locate(p).resolve()));
        ClassFileLocator loc = new ClassFileLocator.Compound(new ClassFileLocator.Simple(rewritten), base);
        TypePool pool = TypePool.Default.of(loc);

        // target class -> (method -> patch classes)
        Map<String, Map<String, List<String>>> byTarget = new LinkedHashMap<>();
        for (String p : patches) {
            Class<?> c = Class.forName(p);
            Patch ann = c.getAnnotation(Patch.class);
            byTarget.computeIfAbsent(ann.className(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(ann.methodName(), k -> new ArrayList<>()).add(p);
        }

        Map<String, byte[]> woven = new HashMap<>();
        int failures = 0;
        // Advice is inlined into game classes, so what it touches must be public.
        for (String p : patches) {
            for (String problem : notPublic(base.locate(p).resolve(), p)) {
                System.out.println("ACCESS FAIL " + p + ": " + problem);
                failures++;
            }
        }
        for (var t : byTarget.entrySet()) {
            DynamicType.Builder<?> b = new ByteBuddy().redefine(pool.describe(t.getKey()).resolve(), loc);
            for (var m : t.getValue().entrySet()) {
                for (String p : m.getValue()) {
                    b = b.visit(Advice.to(pool.describe(p).resolve(), loc).on(matcher(Class.forName(p), m.getKey())));
                }
            }
            byte[] bytes = b.make().getBytes();
            woven.put(t.getKey(), bytes);
            for (String method : t.getValue().keySet()) {
                boolean hooked = methodCallsMover(bytes, method);
                System.out.println((hooked ? "WOVEN  " : "MISSING ") + t.getKey() + "." + method);
                if (!hooked) failures++;
            }
        }

        List<URL> urls = new ArrayList<>(List.of(gameJar.toURI().toURL(), zbJar.toURI().toURL(), modDir.toURI().toURL()));
        for (File j : extraJars) urls.add(j.toURI().toURL());
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null && woven.containsKey(name)) {
                        byte[] bytes = woven.get(name);
                        c = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (c != null) return c;
                    return super.loadClass(name, resolve);
                }
            }
        };
        for (String target : woven.keySet()) {
            try {
                Class.forName(target, true, loader);
                System.out.println("VERIFY OK  " + target + " (initialized)");
            } catch (VerifyError | ClassFormatError e) {
                System.out.println("VERIFY FAIL " + target + ": " + e);
                failures++;
            } catch (Throwable e) {
                // Static init needs the game; it verified.
                System.out.println("VERIFY OK  " + target + " (static init failed without a running game: " + e.getClass().getSimpleName() + ")");
            }
        }
        System.out.println(failures == 0 ? "weave check passed" : failures + " weave problem(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** ZombieBuddy's matching, by name narrowed by @Patch.Argument types. */
    static ElementMatcher.Junction<MethodDescription> matcher(Class<?> patch, String methodName) {
        ElementMatcher.Junction<MethodDescription> m = ElementMatchers.named(methodName);
        for (Method am : patch.getDeclaredMethods()) {
            Class<?>[] types = am.getParameterTypes();
            java.lang.annotation.Annotation[][] anns = am.getParameterAnnotations();
            for (int i = 0; i < anns.length; i++) {
                for (java.lang.annotation.Annotation a : anns[i]) {
                    if (a instanceof Patch.Argument arg) {
                        m = m.and(ElementMatchers.takesArgument(arg.value(), ElementMatchers.isSubTypeOf(types[i])));
                    }
                }
            }
        }
        return m;
    }

    /** ZombieBuddy's annotation rewriting. */
    static byte[] rewrite(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String d, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, d, sig, ex)) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        String nd = DESC.getOrDefault(descriptor, descriptor);
                        AnnotationVisitor av = super.visitAnnotation(nd, visible);
                        if (nd.equals(descriptor)) return av;
                        return new AnnotationVisitor(Opcodes.ASM9, av) {
                            @Override
                            public void visit(String n, Object value) {
                                if ("skipOn".equals(n) && value instanceof Boolean skip) {
                                    value = skip ? Type.getType(Advice.OnNonDefaultValue.class) : NO_EXCEPTION_HANDLER;
                                }
                                super.visit(n, value);
                            }
                        };
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int p, String descriptor, boolean visible) {
                        return super.visitParameterAnnotation(p, DESC.getOrDefault(descriptor, descriptor), visible);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /** Non-public sourcemove members the patch uses. */
    static List<String> notPublic(byte[] bytes, String patch) {
        List<String> out = new ArrayList<>();
        String self = patch.replace('.', '/');
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String d, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String desc, boolean itf) {
                        check(owner, n, desc, true);
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String desc) {
                        check(owner, n, desc, false);
                    }

                    void check(String owner, String n, String desc, boolean method) {
                        if (!owner.startsWith("sourcemove/") || owner.equals(self)) return;
                        try {
                            Class<?> c = Class.forName(owner.replace('/', '.'));
                            if (!java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
                                out.add("class " + c.getName() + " is not public");
                                return;
                            }
                            int mods = -1;
                            if (method) {
                                for (Method m : c.getDeclaredMethods()) {
                                    if (m.getName().equals(n) && Type.getMethodDescriptor(m).equals(desc)) mods = m.getModifiers();
                                }
                            } else {
                                mods = c.getDeclaredField(n).getModifiers();
                            }
                            if (mods == -1) out.add(c.getName() + "." + n + " not found");
                            else if (!java.lang.reflect.Modifier.isPublic(mods)) out.add(c.getName() + "." + n + " is not public");
                        } catch (ReflectiveOperationException e) {
                            out.add(owner + "." + n + ": " + e);
                        }
                    }
                };
            }
        }, 0);
        return out;
    }

    /** The method calls into sourcemove. */
    static boolean methodCallsMover(byte[] bytes, String methodName) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String d, String sig, String[] ex) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String desc, boolean itf) {
                        if (owner.startsWith("sourcemove/")) found[0] = true;
                    }
                };
            }
        }, 0);
        return found[0];
    }
}
