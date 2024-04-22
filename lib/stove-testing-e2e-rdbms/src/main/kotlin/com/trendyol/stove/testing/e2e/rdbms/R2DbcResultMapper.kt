package com.trendyol.stove.testing.e2e.rdbms

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

@PublishedApi
internal fun <T : Any> mapper(
  row: Row,
  rowMetadata: RowMetadata,
  clazz: KClass<T>
): T =
  when {
    clazz.isData -> dataClassMapper(clazz, rowMetadata, row)
    else -> classMapper(clazz, rowMetadata, row)
  }

private fun <T : Any> dataClassMapper(
  clazz: KClass<T>,
  rowMetadata: RowMetadata,
  row: Row
): T {
  val constructor = clazz.primaryConstructor!!

  val args =
    constructor.parameters.map { param ->
      val jClazz = rowMetadata.getColumnMetadata(param.name!!).javaType!!
      row.get(param.name!!, jClazz)
    }.toTypedArray()

  return constructor.call(*args)
}

private fun <T : Any> classMapper(
  clazz: KClass<T>,
  rowMetadata: RowMetadata,
  row: Row
): T {
  val classMemberProperties = clazz.memberProperties.filterIsInstance<KMutableProperty<*>>()
  val instance = clazz.createInstance()
  classMemberProperties.forEach { memberProperty ->
    val columnTypeOfRow = rowMetadata.getColumnMetadata(memberProperty.name).javaType!!
    val propClazz = memberProperty.returnType.jvmErasure
    val propJClazz = propClazz.java
    when (columnTypeOfRow) {
      propJClazz -> memberProperty.setter.call(instance, row.get(memberProperty.name, columnTypeOfRow))
      else -> {
        if (!checkBothNumeric(columnTypeOfRow, propJClazz)) {
          throw IllegalStateException(
            """Sql field and class property objects are being mismatched and not be able to convert each other.
                            | SqlType: $columnTypeOfRow, PropertyType: $propJClazz
            """.trimMargin()
          )
        }
        val columnValue = row.get(memberProperty.name, columnTypeOfRow)
        val propertyValue: Any? =
          try {
            columnValue?.cast(propClazz)
          } catch (ex: ClassCastException) {
            columnValue.convertToNumericOverString(propClazz)
          }

        memberProperty.setter.call(instance, propertyValue)
      }
    }
  }
  return instance
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> Any?.convertToNumericOverString(clazz: KClass<T>): T? {
  if (this == null) return null
  val stringValue = this.toString()
  return when (clazz) {
    Double::class -> stringValue.toDouble()
    Int::class -> stringValue.toInt()
    Long::class -> stringValue.toLong()
    Float::class -> stringValue.toFloat()
    Byte::class -> stringValue.toByte()
    Short::class -> stringValue.toShort()
    else -> error("Numeric conversion unavailable for $clazz")
  } as T
}

private fun <T : Any> Any.cast(targetClazz: KClass<T>): T = targetClazz.javaObjectType.cast(this)

private val primitiveNumbers: Set<Class<*>> =
  setOf(
    Int::class.javaPrimitiveType as Class<*>,
    Long::class.javaPrimitiveType as Class<*>,
    Float::class.javaPrimitiveType as Class<*>,
    Double::class.javaPrimitiveType as Class<*>,
    Byte::class.javaPrimitiveType as Class<*>,
    Short::class.javaPrimitiveType as Class<*>
  )

private fun Class<*>.isNumericType(): Boolean {
  return if (this.isPrimitive) {
    primitiveNumbers.contains(this)
  } else {
    Number::class.java.isAssignableFrom(this)
  }
}

private fun checkBothNumeric(
  jClazz1: Class<*>,
  jClazz2: Class<*>
): Boolean = jClazz1.isNumericType() && jClazz2.isNumericType()
