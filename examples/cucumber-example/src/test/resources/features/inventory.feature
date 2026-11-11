Feature: Reserve inventory
  Customers can reserve available stock without overselling a product.

  @reservation
  Scenario Outline: Reserve stock
    Given a product with <available> units in stock
    When I reserve <requested> units
    Then the reservation response is <status>
    And <remaining> units remain in stock

    Examples:
      | available | requested | status | remaining |
      |        10 |         3 |    204 |         7 |
      |         2 |         3 |    409 |         2 |
      |         5 |         5 |    204 |         0 |
      |         5 |         0 |    400 |         5 |

  @failure-demo
  Scenario: Show Stove diagnostics for an incorrect stock expectation
    Given a product with 10 units in stock
    When I reserve 3 units
    Then 999 units remain in stock
