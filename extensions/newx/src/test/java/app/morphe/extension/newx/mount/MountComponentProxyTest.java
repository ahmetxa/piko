package app.morphe.extension.newx.mount;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Guards the AppComponentFactory receive side of the root-mount proxy: the hook runs for every
 * activity and receiver the app instantiates, so {@code resolveClassName} must never swap a class
 * unless the intent was addressed to the selected stock proxy of the matching kind. A swap on any
 * other component would hijack unrelated app launches into Piko screens.
 *
 * <p>The selected proxy needs a populated package manager that these tests do not model, so only
 * the negative invariants are covered here; the positive swap and rejection of a wrong kind
 * (which needs the selected proxy) are not covered.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class MountComponentProxyTest {
    private static final String PACKAGE = "com.twitter.android";
    private static final String SETTINGS_ACTIVITY = "app.morphe.extension.newx.settings.NewXSettingsActivity";
    private static final String STOCK_ACTIVITY = "com.twitter.android.StockActivity";
    private static final String MARKER = "piko-mount-proxy:";

    @Test
    public void classNameIsKeptUnlessIntentTargetsTheSelectedProxy() {
        // Stock component that is not the selected proxy, marked with a valid extension target.
        Intent marked = new Intent().setClassName(PACKAGE, STOCK_ACTIVITY);
        marked.setIdentifier(MARKER + SETTINGS_ACTIVITY);
        assertEquals(STOCK_ACTIVITY,
                MountComponentProxy.resolveClassName(STOCK_ACTIVITY, marked, MountComponentProxy.KIND_ACTIVITY));

        // Intent addressed to a different component than the class being instantiated.
        assertEquals("com.twitter.android.Other",
                MountComponentProxy.resolveClassName("com.twitter.android.Other", marked, MountComponentProxy.KIND_ACTIVITY));

        // Targets outside the extension package are never trusted, even with the marker.
        Intent foreign = new Intent().setClassName(PACKAGE, STOCK_ACTIVITY);
        foreign.setIdentifier(MARKER + "java.lang.String");
        assertEquals(STOCK_ACTIVITY,
                MountComponentProxy.resolveClassName(STOCK_ACTIVITY, foreign, MountComponentProxy.KIND_ACTIVITY));

        // No marker, unrelated identifier, no intent: untouched.
        Intent plain = new Intent().setClassName(PACKAGE, STOCK_ACTIVITY);
        assertEquals(STOCK_ACTIVITY,
                MountComponentProxy.resolveClassName(STOCK_ACTIVITY, plain, MountComponentProxy.KIND_ACTIVITY));
        plain.setIdentifier("something-else:" + SETTINGS_ACTIVITY);
        assertEquals(STOCK_ACTIVITY,
                MountComponentProxy.resolveClassName(STOCK_ACTIVITY, plain, MountComponentProxy.KIND_ACTIVITY));
        assertEquals(STOCK_ACTIVITY,
                MountComponentProxy.resolveClassName(STOCK_ACTIVITY, null, MountComponentProxy.KIND_ACTIVITY));
    }
}
