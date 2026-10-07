package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class StallKnowledgeBaseTest {
    private static final String KB = "{"
            + "\"version\":3,\"flows\":["
            + "{\"id\":\"unity\",\"priority\":90,\"match\":{\"causes\":[\"presenting_stall\"],"
            + "\"anyLog\":[\"unity\"]},\"steps\":[{\"id\":\"u1\",\"title\":\"U1\"}]},"
            + "{\"id\":\"adreno\",\"priority\":60,\"match\":{\"causes\":[\"presenting_stall\"],"
            + "\"gpuContains\":[\"adreno\"]},\"steps\":[{\"id\":\"a1\",\"title\":\"A1\"}]},"
            + "{\"id\":\"exclude-wined3d\",\"priority\":50,"
            + "\"match\":{\"causes\":[\"presenting_stall\"],"
            + "\"configNot\":{\"dxwrapper\":\"wined3d\"}},"
            + "\"steps\":[{\"id\":\"e1\",\"title\":\"E1\"}]}]}";

    private static StallEvidence evidence(String cause, List<String> log, JSONObject config,
                                          String gpu) {
        StallSignals signals = new StallSignals(
                1000, 1000, 1000, 0, false, log, config, "hash");
        return StallEvidence.from(cause, signals, gpu);
    }

    private static boolean matched(StallKnowledgeBase kb, StallEvidence evidence, String flowId) {
        for (StallKnowledgeBase.Flow flow : kb.match(evidence)) {
            if (flowId.equals(flow.id)) return true;
        }
        return false;
    }

    @Test
    public void parsesVersionAndFlows() throws Exception {
        StallKnowledgeBase kb = StallKnowledgeBase.parse(new JSONObject(KB));
        assertEquals(3, kb.version);
        assertEquals(3, kb.flows.size());
    }

    @Test
    public void matchesByLogSignature() throws Exception {
        StallKnowledgeBase kb = StallKnowledgeBase.parse(new JSONObject(KB));
        StallEvidence unity = evidence("presenting_stall",
                Arrays.asList("loading UnityPlayer.dll"), new JSONObject(), "mali-g78");
        assertTrue(matched(kb, unity, "unity"));

        StallEvidence generic = evidence("presenting_stall",
                Arrays.asList("heartbeat"), new JSONObject(), "mali-g78");
        assertFalse(matched(kb, generic, "unity"));
    }

    @Test
    public void matchesByGpu() throws Exception {
        StallKnowledgeBase kb = StallKnowledgeBase.parse(new JSONObject(KB));
        StallEvidence adreno = evidence("presenting_stall",
                Arrays.asList("heartbeat"), new JSONObject(), "Adreno (TM) 740");
        assertTrue(matched(kb, adreno, "adreno"));

        StallEvidence mali = evidence("presenting_stall",
                Arrays.asList("heartbeat"), new JSONObject(), "Mali-G78");
        assertFalse(matched(kb, mali, "adreno"));
    }

    @Test
    public void configNotExcludesFlow() throws Exception {
        StallKnowledgeBase kb = StallKnowledgeBase.parse(new JSONObject(KB));
        StallEvidence dxvk = evidence("presenting_stall",
                Arrays.asList("heartbeat"),
                new JSONObject().put("dxwrapper", "dxvk"), "mali-g78");
        assertTrue(matched(kb, dxvk, "exclude-wined3d"));

        StallEvidence wined3d = evidence("presenting_stall",
                Arrays.asList("heartbeat"),
                new JSONObject().put("dxwrapper", "wined3d"), "mali-g78");
        assertFalse(matched(kb, wined3d, "exclude-wined3d"));
    }

    @Test
    public void matchSortedByPriority() throws Exception {
        StallKnowledgeBase kb = StallKnowledgeBase.parse(new JSONObject(KB));
        StallEvidence unityAdreno = evidence("presenting_stall",
                Arrays.asList("UnityPlayer"),
                new JSONObject().put("dxwrapper", "dxvk"), "Adreno (TM) 740");
        List<StallKnowledgeBase.Flow> matches = kb.match(unityAdreno);
        assertEquals("unity", matches.get(0).id);
    }
}
