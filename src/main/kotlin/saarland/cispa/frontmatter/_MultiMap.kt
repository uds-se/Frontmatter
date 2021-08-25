package saarland.cispa.frontmatter

import soot.util.HashMultiMap
import soot.util.MultiMap

fun <K, V> multiMapOf(): MultiMap<K, V> = HashMultiMap()

fun <K, V> multiMapOf(vararg pairs: Pair<K, V>): MultiMap<K, V> = pairs.toMultiMap()

//public fun <K, V> multiMapOf(vararg pairs: Pair<K, V>): MultiMap<K, V> =
//    LinkedHashMap<K, V>(mapCapacity(pairs.size)).apply { putAll(pairs) }

/**
 * Returns a new multiMap containing all key-value pairs from the given collection of pairs.
 *
 * The returned multiMap preserves the entry iteration order of the original collection.
 */
fun <K, V> Iterable<Pair<K, V>>.toMultiMap(): MultiMap<K, V> {
    if (this is Collection) {
        return toMultiMap(HashMultiMap<K, V>(size + 1))
    }
    return toMultiMap(HashMultiMap<K, V>())
}

/**
 * Populates and returns the [destination] mutable multiMap with key-value pairs from the given collection of pairs.
 */
fun <K, V, M : MultiMap<in K, in V>> Iterable<Pair<K, V>>.toMultiMap(destination: M): M =
    destination.apply { putAll(this@toMultiMap) }

/**
 *  Populates and returns the [destination] mutable multiMap with key-value pairs from the given array of pairs.
 */
fun <K, V, M : MultiMap<in K, in V>> Array<out Pair<K, V>>.toMultiMap(destination: M): M =
    destination.apply { putAll(this@toMultiMap) }

/**
 * Returns a new multiMap containing all key-value pairs from the given array of pairs.
 *
 * The returned multiMap preserves the entry iteration order of the original array.
 * If any of two pairs would have the same key the last one gets added to the multiMap.
 */
fun <K, V> Array<out Pair<K, V>>.toMultiMap(): MultiMap<K, V> = toMultiMap(HashMultiMap<K, V>(size + 1))

/**
 * Returns a new multiMap containing all key-value pairs from the given sequence of pairs.
 *
 * The returned multiMap preserves the entry iteration order of the original sequence.
 * If any of two pairs would have the same key the last one gets added to the multiMap.
 */
fun <K, V> Sequence<Pair<K, V>>.toMultiMap(): MultiMap<K, V> = toMultiMap(HashMultiMap<K, V>())

/**
 * Populates and returns the [destination] mutable multiMap with key-value pairs from the given sequence of pairs.
 */
fun <K, V, M : MultiMap<in K, in V>> Sequence<Pair<K, V>>.toMultiMap(destination: M): M =
    destination.apply { putAll(this@toMultiMap) }

/**
 * Returns a new mutable multiMap containing all key-value pairs from the original multiMap.
 *
 * The returned multiMap preserves the entry iteration order of the original multiMap.
 */
fun <K, V> Map<out K, V>.toMultiMap(): MultiMap<K, V> = toMultiMap(HashMultiMap<K, V>(size + 1))


fun <K, V, M : MultiMap<in K, in V>> Map<out K, V>.toMultiMap(destination: M): M =
    destination.apply { putAll(this@toMultiMap.entries) }


fun <K, V> MultiMap<K, V>.reverse(): MultiMap<V, K> {
    return this.map { (k, v) -> v to k }.toMultiMap()
}


/**
 * Puts all the given [pairs] into this [MutableMap] with the first component in the pair being the key and the second the value.
 */
fun <K, V> MultiMap<in K, in V>.putAll(pairs: Array<out Pair<K, V>>): Unit {
    for ((key, value) in pairs) {
        put(key, value)
    }
}

/**
 * Puts all the elements of the given collection into this [MutableMap] with the first component in the pair being the key and the second the value.
 */
fun <K, V> MultiMap<in K, in V>.putAll(pairs: Iterable<Pair<K, V>>): Unit {
    for ((key, value) in pairs) {
        put(key, value)
    }
}

/**
 * Puts all the elements of the given sequence into this [MutableMap] with the first component in the pair being the key and the second the value.
 */
fun <K, V> MultiMap<in K, in V>.putAll(pairs: Sequence<Pair<K, V>>): Unit {
    for ((key, value) in pairs) {
        put(key, value)
    }
}

/**
 * Puts all the elements of the given sequence into this [MutableMap] with the first component in the pair being the key and the second the value.
 */
fun <K, V> MultiMap<in K, in V>.putAll(pairs: Set<Map.Entry<K, V>>): Unit {
    for ((key, value) in pairs) {
        put(key, value)
    }
}

operator fun <K, V> heros.solver.Pair<K, V>.component1(): K {
    return this.o1
}

/**
 * used to iterate over MultiMap, it's flattened
 */

operator fun <K, V> heros.solver.Pair<K, V>.component2(): V {
    return this.o2
}

operator fun <K, V> MultiMap<out K, V>.contains(key: K): Boolean = this.keySet().contains(key)
