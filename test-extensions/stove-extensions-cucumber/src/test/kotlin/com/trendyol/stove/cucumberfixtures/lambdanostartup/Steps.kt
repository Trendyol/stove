package com.trendyol.stove.cucumberfixtures.lambdanostartup

import com.trendyol.stove.extensions.cucumber.StoveSteps

class Steps : StoveSteps({
  Given("a configured step") { error("must fail before executing the body") }
})
