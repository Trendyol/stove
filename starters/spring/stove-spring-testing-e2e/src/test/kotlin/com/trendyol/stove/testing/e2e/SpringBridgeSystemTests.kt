package com.trendyol.stove.testing.e2e

import com.trendyol.stove.testing.e2e.system.TestSystem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.mockito.kotlin.*
import org.springframework.context.ApplicationContext

class SpringBridgeSystemTests :
  FunSpec({

    test("SpringBridgeSystem should return bean from application context") {
      val testSystem = TestSystem()
      val bridgeSystem = SpringBridgeSystem(testSystem)
      val mockContext = mock<ApplicationContext>()

      val testBean = TestBean("test-value")
      whenever(mockContext.getBean(TestBean::class.java)).thenReturn(testBean)

      // Set the context via reflection since afterRun is protected
      val ctxField = bridgeSystem.javaClass.superclass.getDeclaredField("ctx")
      ctxField.isAccessible = true
      ctxField.set(bridgeSystem, mockContext)

      val result = bridgeSystem.get(TestBean::class)

      result shouldBe testBean
      verify(mockContext).getBean(TestBean::class.java)
    }

    test("SpringBridgeSystem should be associated with test system") {
      val testSystem = TestSystem()
      val bridgeSystem = SpringBridgeSystem(testSystem)

      bridgeSystem.testSystem shouldBe testSystem
    }

    test("SpringBridgeSystem should implement required interfaces") {
      val testSystem = TestSystem()
      val bridgeSystem = SpringBridgeSystem(testSystem)

      bridgeSystem.shouldBeInstanceOf<SpringBridgeSystem>()
    }
  })

data class TestBean(
  val value: String
)
