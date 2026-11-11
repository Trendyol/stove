Feature: Concurrent second feature
  Scenario Outline: Concurrent <key>
    Given concurrent scenario <key> completes as <outcome>
    Then the preceding suspend step has finished

    Examples:
      | key | outcome |
      | epsilon | fail |
      | zeta | pass |
      | eta | pass |
      | theta | cancel |
