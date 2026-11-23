package com.margelo.nitro.betterpdf.outline

import kotlin.random.Random

/**
 * Where each object of a file is, by its number: the [XrefEntry]s, packed
 * into two arrays, 12 bytes a slot and at most two slots an object. A map
 * of objects takes three to six times that, for as long as the document is
 * open, and a file can list millions of objects in a few KB of compressed
 * cross-reference data: more than [MAX_OBJECTS] fail.
 */
internal class XrefTable {
  /** Object numbers plus one; 0 marks a free slot. */
  private var numbers = IntArray(INITIAL_SLOTS)
  private var places = LongArray(INITIAL_SLOTS)

  /** Unknown to the file, which picks the numbers: it cannot make them share a slot. */
  private val seed = Random.nextInt()

  var size = 0
    private set

  operator fun get(number: Int): XrefEntry? {
    if (number < 0) return null
    val slot = slotOf(number)
    return if (numbers[slot] == 0) null else unpack(places[slot])
  }

  /** Lists [number] at [entry], instead of where it was. A negative number, which nothing refers to, is not listed. */
  operator fun set(number: Int, entry: XrefEntry) {
    if (number < 0) return
    val slot = slotOf(number)
    if (numbers[slot] == 0) add(number, entry) else places[slot] = pack(entry)
  }

  /** Lists [number] at [entry] unless it is listed. */
  fun putIfAbsent(number: Int, entry: XrefEntry) {
    if (number >= 0 && numbers[slotOf(number)] == 0) add(number, entry)
  }

  /** The numbers listed, in order. */
  fun sortedNumbers(): IntArray {
    val listed = IntArray(size)
    var at = 0
    for (key in numbers) if (key != 0) listed[at++] = key - 1
    listed.sort()
    return listed
  }

  private fun add(number: Int, entry: XrefEntry) {
    if (size == MAX_OBJECTS) throw PdfFormatException("More than $MAX_OBJECTS objects")
    if (2 * (size + 1) > numbers.size) grow()
    val slot = slotOf(number)
    numbers[slot] = number + 1
    places[slot] = pack(entry)
    size++
  }

  /** The slot [number] is in, or the free one it would take. */
  private fun slotOf(number: Int): Int {
    val mask = numbers.size - 1
    var hash = (number xor seed) * -0x7a143595
    hash = (hash xor (hash ushr 13)) * -0x3d4d51cb
    var slot = (hash xor (hash ushr 16)) and mask
    while (numbers[slot] != 0 && numbers[slot] != number + 1) slot = (slot + 1) and mask
    return slot
  }

  private fun grow() {
    val oldNumbers = numbers
    val oldPlaces = places
    numbers = IntArray(oldNumbers.size * 2)
    places = LongArray(oldNumbers.size * 2)
    for (index in oldNumbers.indices) {
      if (oldNumbers[index] == 0) continue
      val slot = slotOf(oldNumbers[index] - 1)
      numbers[slot] = oldNumbers[index]
      places[slot] = oldPlaces[index]
    }
  }

  /**
   * An offset, never negative, as it is; an object stream's number and an
   * index in 31 bits each below the sign bit. A negative one of either,
   * which no object stream has, is stored as [Int.MAX_VALUE]: as unreadable.
   */
  private fun pack(entry: XrefEntry): Long = when (entry) {
    is XrefEntry.InFile -> entry.offset
    is XrefEntry.Compressed -> {
      val stream = entry.streamNumber.takeIf { it >= 0 } ?: Int.MAX_VALUE
      val index = entry.index.takeIf { it >= 0 } ?: Int.MAX_VALUE
      Long.MIN_VALUE or (stream.toLong() shl 31) or index.toLong()
    }
  }

  private fun unpack(place: Long): XrefEntry =
    if (place >= 0) {
      XrefEntry.InFile(place)
    } else {
      XrefEntry.Compressed((place ushr 31).toInt() and Int.MAX_VALUE, place.toInt() and Int.MAX_VALUE)
    }

  companion object {
    const val MAX_OBJECTS = 1_000_000
    private const val INITIAL_SLOTS = 64
  }
}
