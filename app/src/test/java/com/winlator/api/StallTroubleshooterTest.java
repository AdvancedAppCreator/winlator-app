package com.winlator.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class StallTroubleshooterTest {
    private static final String KB = "{"
            + "\"version\":1,\"flows\":["
            + "{\"id\":\"flowA\",\"priority\":90,\"match\":{\"causes\":[\"presenting_stall\"]},"
            + "\"steps\":["
            + "{\"id\":\"s1\",\"title\":\"S1\",\"set\":{\"box64Preset\":\"STABILITY\"},"
            + "\"skipIfConfig\":{\"box64Preset\":\"STABILITY\"}},"
            + "{\"id\":\"s2\",\"title\":\"S2\",\"set\":{\"dxwrapper\":\"dxvk\"}},"
            + "{\"id\":\"s3\",\"title\":\"S3\",\"set\":{\"dxwrapper\":\"wined3d\"}}]},"
            + "{\"id\":\"flowB\",\"priority\":50,\"match\":{\"causes\":[\"presenting_stall\"]},"
            + "\"steps\":["
            + "{\"id\":\"s3\",\"title\":\"S3dup\",\"set\":{\"dxwrapper\":\"wined3d\"}},"
            + "{\"id\":\"s4\",\"title\":\"S4\",\"set\":{\"screenSize\":\"1280x720\"}}]}]}";

    private static List<StallKnowledgeBase.Flow> flows() throws Exception {
        return StallKnowledgeBase.parse(new JSONObject(KB)).flows;
    }

    private static Set<String> tried(String... ids) {
        return new HashSet<>(java.util.Arrays.asList(ids));
    }

    @Test
    public void recommendsFirstStepAndMergesFlows() throws Exception {
        StallTroubleshooter.Plan plan = StallTroubleshooter.buildPlan(
                flows(), new JSONObject(), Collections.emptySet());
        assertEquals("s1", plan.recommended.id);
        assertEquals(3, plan.remaining.size());
        assertEquals("s4", plan.remaining.get(2).title.isEmpty() ? "" : plan.remaining.get(2).id);
        assertFalse(plan.terminal);
        assertEquals(2, plan.flowIds.size());
    }

    @Test
    public void escalatesPastTriedSteps() throws Exception {
        StallTroubleshooter.Plan plan = StallTroubleshooter.buildPlan(
                flows(), new JSONObject(), tried("s1"));
        assertEquals("s2", plan.recommended.id);
        assertEquals(1, plan.triedCount);
    }

    @Test
    public void skipsStepAlreadySatisfiedByConfig() throws Exception {
        JSONObject config = new JSONObject().put("box64Preset", "STABILITY");
        StallTroubleshooter.Plan plan = StallTroubleshooter.buildPlan(
                flows(), config, Collections.emptySet());
        assertEquals("s2", plan.recommended.id);
        assertEquals(1, plan.triedCount);
    }

    @Test
    public void terminalWhenLadderExhausted() throws Exception {
        StallTroubleshooter.Plan plan = StallTroubleshooter.buildPlan(
                flows(), new JSONObject(), tried("s1", "s2", "s3", "s4"));
        assertNull(plan.recommended);
        assertTrue(plan.terminal);
    }

    @Test
    public void dedupesRepeatedStepIdAcrossFlows() throws Exception {
        StallTroubleshooter.Plan plan = StallTroubleshooter.buildPlan(
                flows(), new JSONObject(), Collections.emptySet());
        int s3Count = 0;
        if ("s3".equals(plan.recommended.id)) s3Count++;
        for (StallKnowledgeBase.Step step : plan.remaining) {
            if ("s3".equals(step.id)) s3Count++;
        }
        assertEquals(1, s3Count);
    }
}
