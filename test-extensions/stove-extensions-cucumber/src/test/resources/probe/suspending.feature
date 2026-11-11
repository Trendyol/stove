Feature: Suspend invocation
  Scenario: Native and suspend glue share injection and argument conversion
    Given a synchronous Java-backend step
    Then a suspend step receives 42, "hello" and blue
    And a suspend step receives a table
      | a | b |
      | c | d |
    And a suspend step receives a docstring
      """
      hello
      world
      """

  Scenario: Await child failure
    Given a suspend child fails

  Scenario: Propagate cancellation
    Given a suspend step is cancelled

  Scenario: Context is fresh after failure and cancellation
    Given a fresh scenario across a dispatcher switch
