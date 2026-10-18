package com.margelo.nitro.betterpdf

/**
 * A byte-budgeted LRU map. Not thread-safe: the page cache is confined to the
 * UI thread, which is also the only thread that draws cached bitmaps, so an
 * evicted value can never be in the middle of a draw.
 *
 * Generic so the eviction policy can be unit tested on the JVM.
 */
class LruByteCache<K : Any, V : Any>(
  budgetBytes: Long,
  private val sizeOf: (V) -> Long,
  private val onEvict: (K, V) -> Unit,
) {
  private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

  var budgetBytes: Long = budgetBytes
    private set
  var sizeBytes: Long = 0
    private set

  val count: Int get() = entries.size
  val keys: Set<K> get() = entries.keys
  val values: Collection<V> get() = entries.values

  /** Lookup that marks [key] as most recently used. */
  fun get(key: K): V? = entries[key]

  fun contains(key: K): Boolean = entries.containsKey(key)

  /**
   * Inserts [value]. Entries in [protectedKeys] (typically the visible pages)
   * are never evicted to make room, so the budget can be exceeded temporarily
   * when the viewport alone needs more than the budget.
   */
  fun put(key: K, value: V, protectedKeys: Set<K> = emptySet()) {
    entries.remove(key)?.let { old ->
      sizeBytes -= sizeOf(old)
      if (old !== value) onEvict(key, old)
    }
    entries[key] = value
    sizeBytes += sizeOf(value)
    trimTo(budgetBytes, protectedKeys + key)
  }

  fun remove(key: K) {
    entries.remove(key)?.let { old ->
      sizeBytes -= sizeOf(old)
      onEvict(key, old)
    }
  }

  /** Evicts least recently used entries until the size fits [targetBytes]. */
  fun trimTo(targetBytes: Long, protectedKeys: Set<K> = emptySet()) {
    if (sizeBytes <= targetBytes) return
    val iterator = entries.entries.iterator()
    while (sizeBytes > targetBytes && iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key in protectedKeys) continue
      iterator.remove()
      sizeBytes -= sizeOf(entry.value)
      onEvict(entry.key, entry.value)
    }
  }

  fun updateBudget(bytes: Long, protectedKeys: Set<K> = emptySet()) {
    budgetBytes = bytes
    trimTo(bytes, protectedKeys)
  }

  fun clear() {
    val snapshot = entries.toList()
    entries.clear()
    sizeBytes = 0
    for ((key, value) in snapshot) onEvict(key, value)
  }
}
