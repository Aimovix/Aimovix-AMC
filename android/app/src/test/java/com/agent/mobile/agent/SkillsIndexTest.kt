package com.agent.mobile.agent

import org.junit.Assert.*
import org.junit.Test

class SkillsIndexTest {

    @Test
    fun parsesBothSkillLayouts() {
        val output = """

${SkillsIndex.MARKER}/data/data/com.termux/files/home/.amc/skills/backup-photos.md
# Backup photos
Copies new photos from DCIM to the NAS with rsync.
${SkillsIndex.MARKER}/data/data/com.termux/files/home/.amc/skills/weather/SKILL.md
---
name: weather
description: "Current weather and forecast via wttr.in"
---
# Weather
Run curl wttr.in
"""
        val skills = SkillsIndex.parse(output)
        assertEquals(2, skills.size)
        assertEquals("backup-photos", skills[0].name)
        assertEquals("Copies new photos from DCIM to the NAS with rsync.", skills[0].description)
        assertEquals("weather", skills[1].name)
        assertEquals("Current weather and forecast via wttr.in", skills[1].description)

        val text = SkillsIndex.format(skills)
        assertTrue(text.contains("- weather: Current weather and forecast via wttr.in (/data/data/com.termux/files/home/.amc/skills/weather/SKILL.md)"))
    }

    @Test
    fun emptyOutputMeansNoSkills() {
        assertTrue(SkillsIndex.parse("").isEmpty())
        assertEquals("", SkillsIndex.format(emptyList()))
    }

    @Test
    fun listCommandIsReadOnlyAndNotBlocked() {
        val assessment = com.agent.mobile.security.CommandSecurityFilter.analyze(SkillsIndex.LIST_COMMAND)
        assertFalse(assessment.isBlocked)
        assertTrue(SkillsIndex.LIST_COMMAND.contains("head -c"))
        assertFalse(SkillsIndex.LIST_COMMAND.contains(" rm "))
    }
}
