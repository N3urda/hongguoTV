// SPDX-License-Identifier: GPL-3.0-only
package ms.bd.c;

/** JNI ABI shim. Names and descriptors are required by the reviewed SDK binary. */
public abstract class k3 {
    public interface Callback {
        Object invoke(int operation, int argument, long handle, String text, Object value);
    }

    private static volatile Callback callback;

    public static void setCallback(Callback value) { callback = value; }

    public static native Object a(int operation, int argument, long handle, String text, Object value);

    public static Object b(int operation, int argument, long handle, String text, Object value) {
        Callback current = callback;
        if (current == null) throw new IllegalStateException("Native signing callback is unavailable");
        return current.invoke(operation, argument, handle, text, value);
    }
}
