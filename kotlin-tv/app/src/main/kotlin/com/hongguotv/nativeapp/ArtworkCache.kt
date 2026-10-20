// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Public artwork only, bounded to 24 MiB. All calls run on the image workers.
 *
 * The workers are more than one, so several pictures are read and written at the same time. A
 * lock around every call would make the picture one worker is fetching wait for a whole
 * directory to be listed and sorted - and that scan belongs to the writer that is trimming the
 * cache, not to the reader of one picture. So a single picture is read and written without any
 * lock at all, and the lock is held only for the eviction pass, which is the one thing that
 * has to happen once at a time.
 */
class ArtworkCache(private val directory: File) {
    private companion object {
        const val MAX_BYTES=24L*1024*1024
        const val MAX_FILES=100
        const val MAX_FILE_BYTES=4*1024*1024
        const val MAX_AGE_MS=7L*24*60*60*1000
        /** How long a half-written file may stay before it is taken for rubbish. */
        const val STALE_TEMP_MS=10*60*1000L
    }
    /** Held for the eviction pass only, so two workers cannot trim the cache at the same time. */
    private val eviction=ReentrantLock()
    /** The size of the cache as far as this process knows it; the scan corrects it. */
    private val cachedBytes=AtomicLong(0)
    private val cachedFiles=AtomicInteger(0)
    /** Whether the cache has been counted once; until then the budget is not known. */
    private val counted=AtomicBoolean(false)
    private fun file(url: String)=File(directory,MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
    fun get(url: String): ByteArray? = runCatching {
        val f=file(url)
        if(!f.isFile || f.length() !in 1..MAX_FILE_BYTES || System.currentTimeMillis()-f.lastModified() !in 0..MAX_AGE_MS) null else f.readBytes()
    }.getOrNull()
    fun put(url: String,bytes: ByteArray) {
        if(bytes.size>MAX_FILE_BYTES) return
        val dest=file(url)
        // The name is unique, so two workers caching two pictures cannot fight over one
        // temporary file, and a temporary file is never mistaken for a cache entry.
        val temp=File(directory,"${dest.name}.${UUID.randomUUID()}.tmp")
        try {
            directory.mkdirs()
            temp.writeBytes(bytes)
            if(!temp.renameTo(dest)) { temp.delete(); return }
        } catch(e: Exception) {
            // A failed write leaves nothing behind. A leftover temporary file used to be the
            // newest file in the directory, so the eviction pass kept it and deleted the real
            // entries instead - one failed picture emptied the cache.
            temp.delete(); return
        }
        cachedBytes.addAndGet(bytes.size.toLong()); cachedFiles.incrementAndGet()
        evictIfNeeded()
    }
    /**
     * Keeps the cache inside its budget.
     *
     * The directory is only listed when the cache is actually over its limits, or the first
     * time - until then the size of what is on disk is not known. The scan takes the lock, so
     * the workers cannot delete each other's files while they are being written.
     */
    private fun evictIfNeeded() {
        if(counted.get() && cachedBytes.get()<=MAX_BYTES && cachedFiles.get()<=MAX_FILES) return
        if(!eviction.tryLock()) return
        try {
            val entries=directory.listFiles().orEmpty().filter { it.isFile }
            // A temporary file is not a cache entry: it is not counted against the budget, and
            // it is not allowed to be the newest thing in the directory either.
            val temps=entries.filter { it.name.endsWith(".tmp") }
            val real=entries.filter { !it.name.endsWith(".tmp") }.sortedByDescending { it.lastModified() }
            var size=0L
            real.forEachIndexed { index,f -> size+=f.length(); if(size>MAX_BYTES || index>=MAX_FILES) f.delete() }
            // A temporary file whose writer is gone is rubbish; one that is still being written
            // is too young to be it.
            val cutoff=System.currentTimeMillis()-STALE_TEMP_MS
            temps.filter { it.lastModified()<cutoff }.forEach { it.delete() }
            val kept=real.filter { it.isFile }
            cachedBytes.set(kept.sumOf { it.length() }); cachedFiles.set(kept.size); counted.set(true)
        } catch(e: Exception) {
            // The cache is a cache: failing to trim it is not a failure of the app.
        } finally { eviction.unlock() }
    }
}
