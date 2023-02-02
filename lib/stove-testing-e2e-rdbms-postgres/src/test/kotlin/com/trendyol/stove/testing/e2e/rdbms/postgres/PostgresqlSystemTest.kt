package com.trendyol.stove.testing.e2e.rdbms.postgres

import com.trendyol.stove.testing.e2e.rdbms.RelationalDatabaseSystem.Companion.shouldQuery
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.ApplicationUnderTest
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import postgresql
import withPostgresql

class Setup : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestSystem()
            .withPostgresql()
            .applicationUnderTest(NoOpApplication())
            .run()
    }

    override suspend fun afterProject() {
        TestSystem.instance.close()
    }
}

class NoOpApplication : ApplicationUnderTest<Unit> {
    override suspend fun start(configurations: List<String>) {
    }

    override suspend fun stop() {
    }
}

class PostgresqlSystemTests : FunSpec({

    data class Dummy1(
        val id: Long,
        val description: String,
    )

    test("should save and get with immutable data class") {
        TestSystem.instance
            .postgresql()
            .shouldExecute(
                """
                    DROP TABLE IF EXISTS Dummies;                    
                    CREATE TABLE IF NOT EXISTS Dummies (
                    	id serial PRIMARY KEY,
                    	description VARCHAR (50)  NOT NULL
                    );
                """.trimIndent()
            )
            .shouldExecute("INSERT INTO Dummies (description) VALUES ('${testCase.name.testName}')")
            .shouldQuery<Dummy1>("SELECT * FROM Dummies") { actual ->
                actual.size shouldBeGreaterThan 0
                actual.first().description shouldBe testCase.name.testName
            }
    }

    class Dummy2 {
        var id: Long? = null
        var description: String? = null
    }

    test("should save and get with mutable class") {
        TestSystem.instance
            .postgresql()
            .shouldExecute(
                """
                    DROP TABLE IF EXISTS Dummies;
                    CREATE TABLE IF NOT EXISTS Dummies (
                    	id serial PRIMARY KEY,
                    	description VARCHAR (50)  NOT NULL
                    );
                """.trimIndent()
            )
            .shouldExecute("INSERT INTO Dummies (description) VALUES ('${testCase.name.testName}')")
            .shouldQuery<Dummy2>("SELECT * FROM Dummies") { actual ->
                actual.size shouldBeGreaterThan 0
                actual.first().description shouldBe testCase.name.testName
            }
    }
})
