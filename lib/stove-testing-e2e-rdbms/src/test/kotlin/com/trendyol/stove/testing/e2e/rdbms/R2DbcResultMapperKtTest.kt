package com.trendyol.stove.testing.e2e.rdbms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.r2dbc.spi.ColumnMetadata
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class R2DbcResultMapperKtTest : FunSpec({

    test("should map if given class type is simple data class") {
        data class Dummy1(
            val id: Long,
            val description: String,
        )

        // given
        val givenId = 33L
        val givenDescription = "LukeSkywalker"

        val givenIdColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn Long::class.java
        }

        val givenDescriptionColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn String::class.java
        }

        val givenRowMetadata = mock<RowMetadata> {
            on { it.getColumnMetadata(Dummy1::id.name) } doReturn givenIdColumnMetadata
            on { it.getColumnMetadata(Dummy1::description.name) } doReturn givenDescriptionColumnMetadata
        }

        val givenRow = mock<Row> {
            on { it.get(Dummy1::id.name, Long::class.java) } doReturn givenId
            on { it.get(Dummy1::description.name, String::class.java) } doReturn givenDescription
            on { it.metadata } doReturn givenRowMetadata
        }

        // when
        val result = mapper(
            givenRow,
            givenRowMetadata,
            Dummy1::class
        )

        // then
        result.id shouldBe givenId
        result.description shouldBe givenDescription
    }

    test("should map if given class type is simple reference class") {
        class Dummy2 {
            var id: Long? = null
            var description: String? = null
        }

        // given
        val givenId = 33L
        val givenDescription = "LukeSkywalker"

        val givenIdColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn Long::class.java
        }

        val givenDescriptionColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn String::class.java
        }

        val givenRowMetadata = mock<RowMetadata> {
            on { it.getColumnMetadata(Dummy2::id.name) } doReturn givenIdColumnMetadata
            on { it.getColumnMetadata(Dummy2::description.name) } doReturn givenDescriptionColumnMetadata
        }

        val givenRow = mock<Row> {
            on { it.get(Dummy2::id.name, Long::class.java) } doReturn givenId
            on { it.get(Dummy2::description.name, String::class.java) } doReturn givenDescription
            on { it.metadata } doReturn givenRowMetadata
        }

        // when
        val result = mapper(
            givenRow,
            givenRowMetadata,
            Dummy2::class
        )

        // then
        result.id shouldBe givenId
        result.description shouldBe givenDescription
    }

    test("should throw IllegalStateException if given column dataType and class property dataType don't equal") {
        class Dummy3 {
            var id: String? = null
            var description: String? = null
        }

        // given
        val givenId = 33L
        val givenDescription = "LukeSkywalker"

        val givenIdColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn Long::class.java
        }

        val givenDescriptionColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn String::class.java
        }

        val givenRowMetadata = mock<RowMetadata> {
            on { it.getColumnMetadata(Dummy3::id.name) } doReturn givenIdColumnMetadata
            on { it.getColumnMetadata(Dummy3::description.name) } doReturn givenDescriptionColumnMetadata
        }

        val givenRow = mock<Row> {
            on { it.get(Dummy3::id.name, Long::class.java) } doReturn givenId
            on { it.get(Dummy3::description.name, String::class.java) } doReturn givenDescription
            on { it.metadata } doReturn givenRowMetadata
        }

        // when
        val ex = shouldThrow<IllegalStateException> {
            mapper(
                givenRow,
                givenRowMetadata,
                Dummy3::class
            )
        }

        // then
        ex.message shouldNotBe null
    }

    test("should do numeric conversion if given column dataType and class property dataType don't matching but both are numeric") {
        class Dummy3 {
            var id: Long? = null
            var description: String? = null
        }

        // given
        val givenId = 33
        val givenDescription = "LukeSkywalker"

        val givenIdColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn Int::class.java
        }

        val givenDescriptionColumnMetadata = mock<ColumnMetadata> {
            on { it.javaType } doReturn String::class.java
        }

        val givenRowMetadata = mock<RowMetadata> {
            on { it.getColumnMetadata(Dummy3::id.name) } doReturn givenIdColumnMetadata
            on { it.getColumnMetadata(Dummy3::description.name) } doReturn givenDescriptionColumnMetadata
        }

        val givenRow = mock<Row> {
            on { it.get(Dummy3::id.name, Int::class.java) } doReturn givenId
            on { it.get(Dummy3::description.name, String::class.java) } doReturn givenDescription
            on { it.metadata } doReturn givenRowMetadata
        }

        // when
        val result = mapper(
            givenRow,
            givenRowMetadata,
            Dummy3::class
        )

        // then
        result.id shouldBe givenId
        result.description shouldBe givenDescription
    }
})
