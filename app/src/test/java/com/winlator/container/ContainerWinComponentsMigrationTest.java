package com.winlator.container;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ContainerWinComponentsMigrationTest {
    @Test
    public void legacyNativeSelectionsNormalizeToWineBuiltins() throws Exception {
        JSONObject data = new JSONObject()
                .put("name", "legacy")
                .put("wincomponents",
                        "direct3d=1,directsound=1,directmusic=1,directshow=1,"
                                + "directplay=1,xaudio=1,vcrun2005=1,vcrun2010=1,wmdecoder=1");
        Container container = new Container(1);
        container.loadData(data);

        assertEquals(Container.FALLBACK_WINCOMPONENTS, container.getWinComponents());
        assertEquals(
                Container.FALLBACK_WINCOMPONENTS,
                data.getString("wincomponents")
        );
    }
}
