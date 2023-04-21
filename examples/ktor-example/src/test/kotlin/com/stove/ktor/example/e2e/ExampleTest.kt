package com.stove.ktor.example.e2e

import arrow.core.some
import com.trendol.stove.testing.e2e.rdbms.postgres.postgresql
import com.trendyol.stove.testing.e2e.http.http
import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseSystem.Companion.shouldQuery
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import stove.ktor.example.UpdateJediRequest

class ExampleTest : FunSpec({
    data class JediTestAssert(
        val id: Long,
        val name: String,
    )

    test("should save jedi") {
        TestSystem
            .validate {
                val givenId = 10
                val givenName = "Luke Skywalker"
                postgresql {
                    shouldExecute(
                        """
                    DROP TABLE IF EXISTS Jedis;
                    CREATE TABLE IF NOT EXISTS Jedis (
                    	id serial PRIMARY KEY,
                    	name VARCHAR (50)  NOT NULL
                    );
                        """.trimIndent()
                    )
                    shouldExecute("INSERT INTO Jedis (id, name) VALUES ('$givenId', 'Obi Wan Kenobi')")
                }
                http {
                    postAndExpectBodilessResponse(
                        "/jedis/$givenId",
                        body = UpdateJediRequest(givenName).some()
                    ) { actual ->
                        actual.status shouldBe 200
                    }
                }

                postgresql {
                    shouldQuery<JediTestAssert>("Select * FROM Jedis WHERE id=$givenId") {
                        it.count() shouldBe 1
                        it.first().name shouldBe givenName
                    }
                }
            }
    }
})
