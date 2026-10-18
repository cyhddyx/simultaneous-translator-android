package com.cyh.translator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class HistorySession(val id: String, val startedAt: Long, val title: String, val captions: List<Caption>)
class HistoryStore private constructor(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    val worker = Executors.newSingleThreadExecutor()
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE captions (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, channel TEXT NOT NULL, created INTEGER NOT NULL, source TEXT NOT NULL, translation TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX captions_session ON captions(session_id, created)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun begin(id: String, startedAt: Long, title: String) = worker.execute {
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply { put("id", id); put("started", startedAt); put("title", title) })
        writableDatabase.execSQL("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY started DESC LIMIT 100)")
        writableDatabase.execSQL("DELETE FROM captions WHERE session_id NOT IN (SELECT id FROM sessions)")
    }
    fun put(sessionId: String, caption: Caption) = worker.execute {
        writableDatabase.insertWithOnConflict("captions", null, ContentValues().apply {
            put("id", caption.id); put("session_id", sessionId); put("channel", caption.channel); put("created", caption.createdAt)
            put("source", caption.source); put("translation", caption.translation); put("status", caption.status)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun load(): List<HistorySession> {
        val result = mutableListOf<HistorySession>()
        readableDatabase.rawQuery("SELECT id, started, title FROM sessions ORDER BY started DESC LIMIT 100", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val captions = mutableListOf<Caption>()
                readableDatabase.rawQuery("SELECT id, channel, created, source, translation, status FROM captions WHERE session_id = ? ORDER BY created, rowid", arrayOf(id)).use { c ->
                    while (c.moveToNext()) captions.add(Caption(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5)))
                }
                result.add(HistorySession(id, cursor.getLong(1), cursor.getString(2), captions))
            }
        }
        return result
    }
    fun clear() { writableDatabase.beginTransaction(); try {
        writableDatabase.delete("captions", null, null); writableDatabase.delete("sessions", null, null); writableDatabase.setTransactionSuccessful()
    } finally { writableDatabase.endTransaction() } }
    companion object {
        @Volatile private var instance: HistoryStore? = null
        fun get(context: Context): HistoryStore = instance ?: synchronized(this) {
            instance ?: HistoryStore(context.applicationContext).also { instance = it }
        }
        fun export(sessions: List<HistorySession>): String {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return buildString {
                appendLine("同传翻译 · Android 会话记录"); appendLine()
                sessions.forEach { s ->
                    appendLine("${format.format(Date(s.startedAt))}  ${s.title}")
                    s.captions.forEach { c ->
                        appendLine("[${if (c.channel == "listen") "收听" else "发言"}] ${format.format(Date(c.createdAt))}")
                        appendLine("原文：${c.source.ifBlank { "（未收到原文）" }}")
                        appendLine("译文：${c.translation.ifBlank { "（未完成）" }}")
                        if (c.status != "translated") appendLine("状态：${if (c.status == "interrupted") "已中断" else "未完整配对"}")
                        appendLine()
                    }
                    appendLine("────────────────────────")
                }
            }
        }
    }
}
