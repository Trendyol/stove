Feature: Concurrent first feature
  Scenario Outline: Concurrent <key>
    Given concurrent scenario <key> completes as <outcome>
    Then the preceding suspend step has finished

    Examples:
      | key | outcome |
      | alpha | pass |
      | beta | fail |
      | gamma | cancel |
      | delta | pass |
