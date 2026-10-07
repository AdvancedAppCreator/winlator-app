package com.winlator.text;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TextReplacementRulesTest {
    @Test
    public void appliesLiteralRulesCaseInsensitivelyAndLongestFirst() {
        TextReplacementRules rules = new TextReplacementRules(
                "door => gate\n" +
                "the door => this passage\n" +
                "# ignored\n" +
                "locked => sealed"
        );

        assertEquals("this passage is sealed.", rules.apply("The door is locked."));
        assertEquals(3, rules.size());
    }

    @Test
    public void leavesUnknownTextUntouched() {
        TextReplacementRules rules = new TextReplacementRules("hello => bonjour");
        assertEquals("Something else", rules.apply("Something else"));
    }

    @Test
    public void caseInsensitiveMatchingKeepsOriginalUnicodeIndexes() {
        TextReplacementRules rules = new TextReplacementRules("x => y");

        assertEquals("\u0130y", rules.apply("\u0130x"));
    }
}
