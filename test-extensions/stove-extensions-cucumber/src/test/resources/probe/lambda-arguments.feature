Feature: Lambda argument boundaries
  Scenario: Preserve every argument position
    Given arguments 1, 2, 3, 4
    Given arguments 1, 2, 3, 4, 5
    Given arguments 1, 2, 3, 4, 5, 6
    Given arguments 1, 2, 3, 4, 5, 6, 7
    Given arguments 1, 2, 3, 4, 5, 6, 7, 8
    Given arguments 1, 2, 3, 4, 5, 6, 7, 8, 9

  Scenario: Convert typed table values
    Then a lambda converts a typed table
      | 10 |
      | 20 |
