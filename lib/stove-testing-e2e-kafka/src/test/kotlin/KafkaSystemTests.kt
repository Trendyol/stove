import com.trendyol.stove.testing.e2e.kafka.kafka
import com.trendyol.stove.testing.e2e.kafka.setup.DomainEvents.ProductCreated
import com.trendyol.stove.testing.e2e.kafka.setup.DomainEvents.ProductFailingCreated
import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec

class KafkaSystemTests : FunSpec({

    test("When publish then it should work") {
        TestSystem.instance
            .kafka()
            .publish("product", ProductCreated("1"))
            .publish("product", ProductCreated("2"))
            .publish("product", ProductCreated("3"))

        // delay(5000)
    }

    xtest("When publish to a failing consumer should end-up throwing exception") {
        TestSystem.instance
            .kafka()
            .publish("productFailing", ProductFailingCreated("1"))
            .shouldBeConsumed(message = ProductFailingCreated("1"))

        // delay(5000)
    }
})
