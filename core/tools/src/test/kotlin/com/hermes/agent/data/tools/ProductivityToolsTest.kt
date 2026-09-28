package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.Bookmark
import com.hermes.agent.domain.model.MoodEntry
import com.hermes.agent.domain.model.MoodLevel
import com.hermes.agent.domain.model.Note
import com.hermes.agent.domain.repository.BookmarkRepository
import com.hermes.agent.domain.repository.MoodRepository
import com.hermes.agent.domain.repository.NotesRepository
import com.hermes.agent.domain.repository.TodoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductivityToolsTest {

    @Test
    fun `notes create uses general category and removes blank tags`() = runTest {
        val repository = mockk<NotesRepository>()
        coEvery { repository.create(any(), any(), any(), any(), any()) } answers {
            Note(
                id = "n_1",
                title = firstArg(),
                content = secondArg(),
                tags = thirdArg(),
                category = arg(3),
                folder = arg(4),
            )
        }

        val result = NotesTool(repository).execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Release notes"),
                "category" to JsonPrimitive("   "),
                "tags" to JsonArray(listOf(JsonPrimitive("android"), JsonPrimitive("  "))),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        coVerify { repository.create("Release notes", "", listOf("android"), "general", null) }
    }

    @Test
    fun `todo rejects unknown priority instead of silently changing it`() = runTest {
        val repository = mockk<TodoRepository>(relaxed = true)

        val result = TodoTool(repository).execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Ship"),
                "priority" to JsonPrimitive("urgent"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("Invalid priority"))
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `todo takes a local due date and refuses one well in the past`() = runTest {
        val repository = mockk<TodoRepository>(relaxed = true)
        val zone = java.time.ZoneId.systemDefault()
        val due = java.time.LocalDateTime.of(2026, 9, 28, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val tool = TodoTool(repository).apply { clock = { due - 3_600_000L } }

        val ok = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Buy groceries"),
                "due_date_ms" to JsonPrimitive("2026-09-28T18:00"),
            ),
        )
        assertTrue(ok.errorMessage, ok.success)
        coVerify { repository.create("Buy groceries", "", any(), due, any(), any()) }

        val refused = tool.execute(
            mapOf(
                "action" to JsonPrimitive("create"),
                "title" to JsonPrimitive("Old"),
                "due_date_ms" to JsonPrimitive("2025-09-28T18:00"),
            ),
        )
        assertFalse(refused.success)
        assertTrue(refused.errorMessage, refused.errorMessage.orEmpty().contains("in the past"))
        coVerify(exactly = 0) { repository.create("Old", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `bookmark list clamps a negative limit`() = runTest {
        val repository = mockk<BookmarkRepository>()
        every { repository.observeAll() } returns flowOf(
            listOf(Bookmark("b_1", "https://example.com", "Example")),
        )

        val result = BookmarkTool(repository).execute(
            mapOf(
                "action" to JsonPrimitive("list"),
                "limit" to JsonPrimitive(-4),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertTrue(result.output.contains("Example"))
    }

    @Test
    fun `mood rejects out of range intensity`() = runTest {
        val repository = mockk<MoodRepository>(relaxed = true)

        val result = MoodTool(repository).execute(
            mapOf(
                "action" to JsonPrimitive("log"),
                "mood" to JsonPrimitive("GOOD"),
                "intensity" to JsonPrimitive(11),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("between 1 and 10"))
        coVerify(exactly = 0) { repository.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `mood log preserves nonblank tags`() = runTest {
        val repository = mockk<MoodRepository>()
        coEvery { repository.create(any(), any(), any(), any(), any()) } answers {
            MoodEntry(
                id = "md_1",
                dateMs = firstArg(),
                mood = secondArg<MoodLevel>(),
                intensity = thirdArg(),
                note = arg(3),
                tags = arg(4),
            )
        }

        val result = MoodTool(repository).execute(
            mapOf(
                "action" to JsonPrimitive("log"),
                "mood" to JsonPrimitive("GOOD"),
                "tags" to JsonArray(listOf(JsonPrimitive("sleep"), JsonPrimitive(" "))),
            ),
        )

        assertTrue(result.errorMessage.orEmpty(), result.success)
        coVerify { repository.create(any(), MoodLevel.GOOD, 5, "", listOf("sleep")) }
        assertEquals("mood", MoodTool(repository).descriptor.name)
    }
}
