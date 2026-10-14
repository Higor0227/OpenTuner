// SPDX-License-Identifier: GPL-3.0-or-later
package java.lang.invoke;

/** Compile-time stub only (missing from this android.jar); D8 desugars lambdas, so it is never dexed. */
public final class LambdaMetafactory {
    private LambdaMetafactory() {}

    public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType,
            MethodType samMethodType, MethodHandle implMethod, MethodType instantiatedMethodType)
            throws LambdaConversionException {
        throw new UnsupportedOperationException();
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType,
            Object... args) throws LambdaConversionException {
        throw new UnsupportedOperationException();
    }
}
