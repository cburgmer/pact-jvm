package au.com.dius.pact.core.support.json

import au.com.dius.pact.core.support.Json

sealed class JsonValue {
  class Integer(val value: JsonToken.Integer) : JsonValue() {
    constructor(value: CharArray) : this(JsonToken.Integer(value))
    constructor(value: Int) : this(JsonToken.Integer(value.toString().toCharArray()))
    fun toBigInteger() = String(this.value.chars).toBigInteger()

    override fun copy() = Integer(value.chars)
  }

  class Decimal(val value: JsonToken.Decimal) : JsonValue() {
    constructor(value: CharArray) : this(JsonToken.Decimal(value))
    constructor(value: Number) : this(JsonToken.Decimal(value.toString().toCharArray()))
    fun toBigDecimal() = String(this.value.chars).toBigDecimal()

    override fun copy() = Decimal(value.chars)
  }

  class StringValue(val value: JsonToken.StringValue) : JsonValue() {
    constructor(value: CharArray) : this(JsonToken.StringValue(value))
    constructor(value: String) : this(JsonToken.StringValue(value.toCharArray()))
    override fun toString() = String(value.chars)

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      return when (other) {
        is StringValue -> value == other.value
        is String -> value.chars.contentEquals(other.toCharArray())
        else -> false
      }
    }

    override fun hashCode(): Int {
      var result = super.hashCode()
      result = 31 * result + value.hashCode()
      return result
    }

    override fun copy() = StringValue(value.chars)
  }

  object True : JsonValue() {
    override fun copy() = True
  }

  object False : JsonValue() {
    override fun copy() = False
  }

  object Null : JsonValue() {
    override fun copy() = Null
  }

  class Array @JvmOverloads constructor (val values: MutableList<JsonValue> = mutableListOf()) : JsonValue() {
    fun find(function: (JsonValue) -> Boolean) = values.find(function)
    operator fun get(i: Int): JsonValue {
      return values[i]
    }
    operator fun set(i: Int, value: JsonValue) {
      values[i] = value
    }
    val size: Int
      get() = values.size

    fun addAll(jsonValue: JsonValue) {
      when (jsonValue) {
        is Array -> values.addAll(jsonValue.values)
        else -> values.add(jsonValue)
      }
    }

    fun last() = values.last()

    fun append(value: JsonValue) {
      values.add(value)
    }

    fun appendAll(list: List<JsonValue>) {
      values.addAll(list)
    }

    companion object {
      fun of(vararg value: JsonValue) = Array(value.toMutableList())
    }

    override fun copy() = Array(values.map { it.copy() }.toMutableList())
  }

  class Object @JvmOverloads constructor (val entries: MutableMap<String, JsonValue> = mutableMapOf()) : JsonValue() {
    constructor(vararg values: Pair<String, JsonValue>) : this(values.associate { it }.toMutableMap())
    operator fun get(name: String) = entries[name] ?: Null
    override fun has(field: String) = entries.containsKey(field)

    override fun copy() = Object(entries.mapValues { it.value.copy() }.toMutableMap())

    operator fun set(key: String, value: Any?) {
      entries[key] = Json.toJson(value)
    }

    fun isEmpty() = entries.isEmpty()
    fun isNotEmpty() = entries.isNotEmpty()

    val size: Int
      get() = entries.size

    fun add(key: String, value: JsonValue) {
      entries[key] = value
    }

    fun keys(): Set<String> = entries.keys
  }

  fun asObject(): Object? {
    return if (this is Object) {
      this
    } else {
      null
    }
  }

  fun asArray(): Array? {
    return if (this is Array) {
      this
    } else {
      null
    }
  }

  fun asString(): String? {
    return if (this is StringValue) {
      String(value.chars)
    } else {
      null
    }
  }

  override fun toString(): String {
    return when (this) {
      is Null -> "null"
      is Decimal -> String(this.value.chars)
      is Integer -> String(this.value.chars)
      is StringValue -> this.value.toString()
      is True -> "true"
      is False -> "false"
      is Array -> "[${this.values.joinToString(",") { it.serialise() }}]"
      is Object -> "{${this.entries.entries.sortedBy { it.key }.joinToString(",") { 
        "\"${it.key}\":" + it.value.serialise() 
      }}}"
    }
  }

  fun asBoolean() = when (this) {
    is True -> true
    is False -> false
    else -> null
  }

  fun asNumber(): Number? = when (this) {
    is Integer -> this.toBigInteger()
    is Decimal -> this.toBigDecimal()
    else -> null
  }

  operator fun get(field: Any): JsonValue = when {
    this is Object -> this[field.toString()]
    this is Array && field is Int -> this.values[field]
    this is Null -> Null
    else -> throw UnsupportedOperationException("Indexed lookups only work on Arrays and Objects, not $this")
  }

  open fun has(field: String) = when (this) {
    is Object -> this.entries.containsKey(field)
    else -> false
  }

  fun serialise(): String {
    return when (this) {
      is Null -> "null"
      is Decimal -> String(this.value.chars)
      is Integer -> String(this.value.chars)
      is StringValue -> "\"${Json.escape(this.asString()!!)}\""
      is True -> "true"
      is False -> "false"
      is Array -> "[${this.values.joinToString(",") { it.serialise() }}]"
      is Object -> "{${this.entries.entries.sortedBy { it.key }
        .joinToString(",") { "\"${it.key}\":" + it.value.serialise() }}}"
    }
  }

  fun add(value: JsonValue) {
    if (this is Array) {
      this.values.add(value)
    } else {
      throw UnsupportedOperationException("You can only add single values to Arrays, not $this")
    }
  }

  fun size() = when (this) {
    is Array -> this.values.size
    is Object -> this.entries.size
    else -> 1
  }

  fun type(): String {
    return when (this) {
      is StringValue -> "String"
      is True -> "Boolean"
      is False -> "Boolean"
      else -> this::class.java.simpleName
    }
  }

  fun unwrap(): Any? {
    return when (this) {
      is Null -> null
      is Decimal -> this.toBigDecimal()
      is Integer -> this.toBigInteger()
      is StringValue -> this.asString()
      is True -> true
      is False -> false
      is Array -> this.values
      is Object -> this.entries
    }
  }

  override fun equals(other: Any?): Boolean {
    if (other !is JsonValue) return false
    return when (this) {
      is Null -> other is Null
      is Decimal -> other is Decimal && this.toBigDecimal().compareTo(other.toBigDecimal()) == 0
      is Integer -> other is Integer && this.toBigInteger() == other.toBigInteger()
      is StringValue -> other is StringValue && this.asString() == other.asString()
      is True -> other is True
      is False -> other is False
      is Array -> other is Array && this.values == other.values
      is Object -> other is Object && this.entries == other.entries
    }
  }

  override fun hashCode() = when (this) {
    is Null -> 0.hashCode()
    is Decimal -> this.toBigDecimal().hashCode()
    is Integer -> this.toBigInteger().hashCode()
    is StringValue -> this.asString()!!.hashCode()
    is True -> true.hashCode()
    is False -> false.hashCode()
    is Array -> this.values.hashCode()
    is Object -> this.entries.hashCode()
  }

  fun prettyPrint(indent: Int = 0, skipIndent: Boolean = false): String {
    val indentStr = "".padStart(indent)
    val indentStr2 = "".padStart(indent + 2)
    return if (skipIndent) {
      when (this) {
        is Array -> "[\n" + this.values.joinToString(",\n") {
          it.prettyPrint(indent + 2) } + "\n$indentStr]"
        is Object -> "{\n" + this.entries.entries.sortedBy { it.key }.joinToString(",\n") {
          "$indentStr2\"${it.key}\": ${it.value.prettyPrint(indent + 2, true)}"
          } + "\n$indentStr}"
        else -> this.serialise()
      }
    } else {
      when (this) {
        is Array -> "$indentStr$indentStr[\n" + this.values.joinToString(",\n") {
          it.prettyPrint(indent + 2) } + "\n$indentStr]"
        is Object -> "$indentStr{\n" + this.entries.entries.sortedBy { it.key }.joinToString(",\n") {
          "$indentStr2\"${it.key}\": ${it.value.prettyPrint(indent + 2, true)}"
          } + "\n$indentStr}"
        else -> indentStr + this.serialise()
      }
    }
  }

  val name: String
    get() {
      return when (this) {
        is Null -> "Null"
        is Decimal -> "Decimal"
        is Integer -> "Integer"
        is StringValue -> "String"
        is True -> "True"
        is False -> "False"
        is Array -> "Array"
        is Object -> "Object"
      }
    }

  val isBoolean: Boolean
    get() = when (this) {
      is True, is False -> true
      else -> false
    }

  val isNumber: Boolean
    get() = when (this) {
      is Integer, is Decimal -> true
      else -> false
    }

  val isString: Boolean
    get() = when (this) {
      is StringValue -> true
      else -> false
    }

  val isNull: Boolean
    get() = when (this) {
      is Null -> true
      else -> false
    }

  val isObject: Boolean
    get() = when (this) {
      is Object -> true
      else -> false
    }

  val isArray: Boolean
    get() = when (this) {
      is Array -> true
      else -> false
    }

  inline fun <reified T: JsonValue> downcast() : T {
    return if (this is T) {
      this
    } else {
      throw UnsupportedOperationException("Can not downcast ${this.name} to type ${T::class}")
    }
  }

  /**
   * Makes a copy of this JSON value
   */
  abstract fun copy(): JsonValue
}

fun <R> JsonValue?.map(transform: (JsonValue) -> R): List<R> = when {
  this == null -> emptyList()
  this is JsonValue.Array -> this.values.map(transform)
  else -> emptyList()
}

operator fun JsonValue?.get(field: Any): JsonValue = when {
  this == null -> JsonValue.Null
  else -> this[field]
}

operator fun JsonValue.Object?.get(field: Any): JsonValue = when {
  this == null -> JsonValue.Null
  else -> this[field]
}

fun JsonValue?.orNull() = this ?: JsonValue.Null
