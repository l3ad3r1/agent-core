package com.hermes.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hermes.agent.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp ASC
        """
    )
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun recentByConversation(
        conversationId: String,
        limit: Int,
    ): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity): Long

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countForConversation(conversationId: String): Int

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String): Int

    /**
     * Drops a message and everything sent after it in the same conversation.
     *
     * Backs "rewind": the timestamp rather than the row id is the cut point,
     * because the transcript the user is looking at is ordered by time, and a
     * recovered or backfilled row can carry an id that does not match that order.
     */
    @Query(
        """
        DELETE FROM messages
        WHERE conversation_id = :conversationId AND timestamp >= :fromTimestamp
        """,
    )
    suspend fun deleteFrom(conversationId: String, fromTimestamp: Long): Int

    /** The transcript up to and including [throughTimestamp], for forking. */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId AND timestamp <= :throughTimestamp
        ORDER BY timestamp ASC
        """,
    )
    suspend fun messagesThrough(conversationId: String, throughTimestamp: Long): List<MessageEntity>

    /**
     * Global full-text search across ALL conversations ordered by recency.
     * Replaces the per-conversation linear scan in [ConversationSearchTool].
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE content LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchAll(query: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getMessagesSince(sinceTimestamp: Long): List<MessageEntity>

    @Query("SELECT COUNT(DISTINCT conversation_id) FROM messages WHERE timestamp >= :sinceTimestamp")
    suspend fun countActiveConversationsSince(sinceTimestamp: Long): Int
}
