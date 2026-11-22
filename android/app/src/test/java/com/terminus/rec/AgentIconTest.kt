package com.terminus.rec

import org.junit.Assert.*
import org.junit.Test

/** Agent badge mapping: one distinct icon per backend, unknown canons to
 *  opencode. The badge is a non-interactive indicator; the name travels in
 *  contentDescription, never as repeated row text. */
class AgentIconTest {

    @Test fun `each backend maps to its own icon`() {
        assertEquals(R.drawable.ic_agent_opencode, agentIcon("opencode"))
        assertEquals(R.drawable.ic_agent_codex, agentIcon("codex"))
        assertEquals(R.drawable.ic_agent_claude, agentIcon("claude"))
        assertEquals(R.drawable.ic_agent_agy, agentIcon("agy"))
    }

    @Test fun `unknown backend falls back to opencode`() {
        assertEquals(R.drawable.ic_agent_opencode, agentIcon("nope"))
        assertEquals(R.drawable.ic_agent_opencode, agentIcon(""))
    }

    @Test fun `labels stay available for accessibility`() {
        assertEquals("OpenCode", backendDisplayName("opencode"))
        assertEquals("Codex", backendDisplayName("codex"))
        assertEquals("Claude", backendDisplayName("claude"))
        assertEquals("Agy", backendDisplayName("agy"))
    }
}
