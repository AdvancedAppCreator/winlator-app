package com.winlator.api.dependency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.net.URI;

/**
 * Pure JUnit4 tests for {@link RuntimeDependencyCatalog} — no Android runtime required.
 */
public class RuntimeDependencyCatalogTest {

    // ── Entry count / completeness ────────────────────────────────────────────

    @Test
    public void exactlyTwoSupportedEntries() {
        assertEquals(2, RuntimeDependencyCatalog.supportedEntries().size());
    }

    @Test
    public void exactlySixUnsupportedEntries() {
        assertEquals(6, RuntimeDependencyCatalog.unsupportedEntries().size());
    }

    @Test
    public void exactlyOneWrapperConfigEntry() {
        assertEquals(1, RuntimeDependencyCatalog.wrapperConfigEntries().size());
    }

    @Test
    public void allEntriesCountMatchesSections() {
        int total = RuntimeDependencyCatalog.supportedEntries().size()
                + RuntimeDependencyCatalog.unsupportedEntries().size()
                + RuntimeDependencyCatalog.wrapperConfigEntries().size();
        assertEquals(total, RuntimeDependencyCatalog.allEntries().size());
    }

    // ── Supported IDs ─────────────────────────────────────────────────────────

    @Test
    public void vcrun2015_2022IsSupported() {
        RuntimeDependencyCatalog.Entry e =
                RuntimeDependencyCatalog.getEntry(RuntimeDependencyCatalog.VCRUN2015_2022);
        assertNotNull(e);
        assertTrue(e.isSupported());
        assertFalse(e.isUnsupported());
        assertFalse(e.isWrapperConfig());
    }

    @Test
    public void openalIsSupported() {
        RuntimeDependencyCatalog.Entry e =
                RuntimeDependencyCatalog.getEntry(RuntimeDependencyCatalog.OPENAL);
        assertNotNull(e);
        assertTrue(e.isSupported());
    }

    // ── Wrapper-config IDs ────────────────────────────────────────────────────

    @Test
    public void directxIsWrapperConfig() {
        RuntimeDependencyCatalog.Entry e =
                RuntimeDependencyCatalog.getEntry(RuntimeDependencyCatalog.DIRECTX);
        assertNotNull(e);
        assertTrue(e.isWrapperConfig());
        assertFalse(e.isSupported());
        assertFalse(e.isUnsupported());
    }

    // ── Unsupported IDs ───────────────────────────────────────────────────────

    @Test
    public void vcrun2008IsUnsupported() {
        assertIsUnsupported(RuntimeDependencyCatalog.VCRUN2008);
    }

    @Test
    public void vcrun2012IsUnsupported() {
        assertIsUnsupported(RuntimeDependencyCatalog.VCRUN2012);
    }

    @Test
    public void dotnetIsUnsupported() {
        assertIsUnsupported(RuntimeDependencyCatalog.DOTNET);
    }

    @Test
    public void monoIsUnsupported() {
        assertIsUnsupported(RuntimeDependencyCatalog.MONO);
    }

    @Test
    public void xnaIsUnsupported() {
        assertIsUnsupported(RuntimeDependencyCatalog.XNA);
    }

    @Test
    public void physxIsUnsupported() {
        assertIsUnsupported(RuntimeDependencyCatalog.PHYSX);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    @Test
    public void unknownIdReturnsNull() {
        assertNull(RuntimeDependencyCatalog.getEntry("unknown_runtime_xyz"));
    }

    @Test
    public void nullIdReturnsNull() {
        assertNull(RuntimeDependencyCatalog.getEntry(null));
    }

    // ── Description / display-name completeness ───────────────────────────────

    @Test
    public void allEntriesHaveNonEmptyDisplayName() {
        for (RuntimeDependencyCatalog.Entry e : RuntimeDependencyCatalog.allEntries()) {
            assertFalse("Empty displayName for id=" + e.id, e.displayName.isEmpty());
        }
    }

    @Test
    public void allEntriesHaveNonEmptyDescription() {
        for (RuntimeDependencyCatalog.Entry e : RuntimeDependencyCatalog.allEntries()) {
            assertFalse("Empty description for id=" + e.id, e.description.isEmpty());
        }
    }

    @Test
    public void supportedEntriesReturnedListIsIndependentCopy() {
        List<RuntimeDependencyCatalog.Entry> a = RuntimeDependencyCatalog.supportedEntries();
        List<RuntimeDependencyCatalog.Entry> b = RuntimeDependencyCatalog.supportedEntries();
        a.clear();
        assertEquals(2, b.size());
    }

    @Test
    public void unsupportedEntriesDoNotClaimSupported() {
        for (RuntimeDependencyCatalog.Entry e : RuntimeDependencyCatalog.unsupportedEntries()) {
            assertFalse("Unsupported entry claims supported: " + e.id, e.isSupported());
            assertFalse("Unsupported entry claims wrapperConfig: " + e.id, e.isWrapperConfig());
        }
    }

    @Test
    public void bootstrapCatalogUsesPinnedMicrosoftHttpsArtifacts() throws Exception {
        List<RuntimeDependencyCatalog.BootstrapPackage> packages =
                RuntimeDependencyCatalog.bootstrapSelectablePackages();
        assertEquals(13, packages.size());
        for (RuntimeDependencyCatalog.BootstrapPackage entry : packages) {
            assertNotNull(entry.url);
            assertTrue(entry.sha256.matches("^[0-9a-f]{64}$"));
            assertTrue(entry.size > 0);
            URI uri = new URI(entry.url);
            assertEquals("https", uri.getScheme());
            assertTrue(
                    "Unexpected prerequisite host: " + uri.getHost(),
                    "download.microsoft.com".equals(uri.getHost())
                            || "download.visualstudio.microsoft.com".equals(uri.getHost())
            );
        }
    }

    @Test
    public void directxSelectionAddsSetupStage() {
        List<String> expanded = RuntimeDependencyCatalog.expandBootstrapSelection(
                java.util.Collections.singletonList("directx_june2010")
        );
        assertEquals(2, expanded.size());
        assertEquals("directx_june2010", expanded.get(0));
        assertEquals("directx_june2010_setup", expanded.get(1));
        assertFalse(
                RuntimeDependencyCatalog.getBootstrapPackage(
                        "directx_june2010_setup"
                ).selectable
        );
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void assertIsUnsupported(String id) {
        RuntimeDependencyCatalog.Entry e = RuntimeDependencyCatalog.getEntry(id);
        assertNotNull("Missing catalog entry for id=" + id, e);
        assertTrue("Entry should be UNSUPPORTED: " + id, e.isUnsupported());
        assertFalse("Unsupported entry falsely claims SUPPORTED: " + id, e.isSupported());
        assertFalse("Unsupported entry falsely claims WRAPPER_CONFIG: " + id, e.isWrapperConfig());
    }
}
