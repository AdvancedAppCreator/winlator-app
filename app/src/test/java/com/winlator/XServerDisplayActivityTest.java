package com.winlator;

import com.winlator.container.Container;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class XServerDisplayActivityTest {
    @Test
    public void gameTextNamespaceSupportsWineprefixLaunchWithoutContainer() {
        assertEquals(
                "wineprefix",
                XServerDisplayActivity.gameTextNamespace(null, null)
        );
    }

    @Test
    public void gameTextNamespacePrefersManagedGameId() {
        assertEquals(
                "game:managed-game",
                XServerDisplayActivity.gameTextNamespace(
                        "managed-game",
                        new Container(7)
                )
        );
    }

    @Test
    public void gameTextNamespaceFallsBackToContainerId() {
        assertEquals(
                "container:7",
                XServerDisplayActivity.gameTextNamespace(null, new Container(7))
        );
    }
}
