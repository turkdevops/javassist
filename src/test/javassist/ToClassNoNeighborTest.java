package javassist;

import junit.framework.TestCase;

/**
 * Regression test for {@code ClassPool#toClass(CtClass, Class, ClassLoader, ProtectionDomain)}
 * deriving a same-package neighbor when the caller supplies none, so that
 * {@code CtClass#toClass()} (and the other no-neighbor overloads) can use
 * {@code java.lang.invoke.MethodHandles.Lookup} instead of falling back to a
 * reflective call to the protected {@code ClassLoader#defineClass}.
 *
 * <p><b>Why this matters:</b> the reflective fallback is illegal reflective
 * access to a JDK-internal method. On <b>Java 9-15</b> it's allowed but
 * prints a warning ("An illegal reflective access operation has occurred ...
 * javassist.util.proxy.SecurityActions ...") straight to the process's
 * stderr file descriptor, bypassing {@code System.setErr()} — not something
 * a test can assert on from within the same JVM. On <b>Java 16+</b>,
 * {@code --illegal-access} was removed (JEP 403) and the access is denied
 * outright, throwing {@code InaccessibleObjectException} instead — an
 * ordinary exception a test can assert on directly. Both are the same root
 * cause: no same-package neighbor was available, so {@code DefineClassHelper}
 * fell back to reflection.
 */
public class ToClassNoNeighborTest extends TestCase {

    public static class Base {}

    /**
     * {@link Base} is in the same package as the generated class, so
     * {@code ClassPool} derives it as a neighbor and never needs the
     * reflective fallback described in the class Javadoc. Passes on every
     * JDK.
     */
    public void testToClassWithSamePackageSuperclassAvoidsReflectiveFallback()
        throws Exception
    {
        ClassPool cp = ClassPool.getDefault();
        CtClass base = cp.get(Base.class.getName());
        CtClass generated = cp.makeClass(
                "javassist.ToClassNoNeighborTest$SamePackageGenerated", base);

        Class<?> loaded = generated.toClass();

        assertEquals(Base.class, loaded.getSuperclass());
    }

    /**
     * Control case: {@link Object}, the generated class's only ancestor, is
     * not in the same package, so {@code ClassPool} has no neighbor to
     * derive and must still fall back to reflection. Proves the positive
     * test above is exercising the fix rather than passing regardless.
     * Expected outcome depends on the JDK (see class Javadoc); on Java 16+
     * it also depends on whether {@code --add-opens
     * java.base/java.lang=ALL-UNNAMED} was granted, detected via
     * {@code Module.isOpen} so both outcomes are still asserted precisely.
     */
    public void testToClassWithoutDerivableNeighborStillUsesReflectiveFallback()
        throws Exception
    {
        ClassPool cp = ClassPool.getDefault();
        CtClass generated = cp.makeClass(
                "javassist.ToClassNoNeighborTest$NoNeighborGenerated");

        if (isJava16OrLater()) {
            boolean javaLangOpened =
                    Object.class.getModule().isOpen("java.lang", ToClassNoNeighborTest.class.getModule());

            if (javaLangOpened) {
                Class<?> loaded = generated.toClass();
                assertEquals(Object.class, loaded.getSuperclass());
            }
            else {
                try {
                    generated.toClass();
                    fail("Expected the reflective defineClass fallback to be denied by the JVM "
                         + "(java.lang.reflect.InaccessibleObjectException), since java.lang is not "
                         + "opened to this module.");
                }
                catch (RuntimeException e) {
                    assertEquals("java.lang.reflect.InaccessibleObjectException", e.getClass().getName());
                }
            }
        }
        else {
            Class<?> loaded = generated.toClass();
            assertEquals(Object.class, loaded.getSuperclass());
        }
    }

    /**
     * Returns true if {@code java.specification.version} is 16 or higher.
     */
    private static boolean isJava16OrLater() {
        String v = System.getProperty("java.specification.version");
        if (v.startsWith("1."))
            return false; // Java 8 or older ("1.6", "1.7", "1.8")

        try {
            return Integer.parseInt(v) >= 16;
        }
        catch (NumberFormatException e) {
            return false;
        }
    }
}
