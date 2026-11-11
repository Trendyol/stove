Feature: Adapter lifecycle
  @passing
  Scenario Outline: Isolated example
    Given a fresh scenario across a dispatcher switch
    Examples:
      | example |
      | first   |
      | second  |

  Scenario: Assertion failure
    Given an assertion fails

  @before-failure
  Scenario: Before hook failure
    Given a fresh scenario across a dispatcher switch

  @after-failure
  Scenario: After hook failure
    Given a fresh scenario across a dispatcher switch

  Scenario: Undefined step
    Given an undefined step

  Scenario: Ambiguous step
    Given an ambiguous step

  Scenario: Pending step
    Given a pending step
