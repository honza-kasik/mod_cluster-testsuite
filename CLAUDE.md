This document contains critical information about working with this codebase. Follow these guidelines strictly.

## Core development rules

1. Use Maven for dependency management
2. Keep classes small and focused on one topic
3. Public methods and tests must have Javadoc
4. Make code easy to understand
5. Make code straightforward
6. Think of the programmer as a user you are creating product for
7. Be specific - use final where it makes sense and never use var for variables. Use explicit type declarations instead.
8. Do not use fully qualified class name for variable types. Use import instead.
9. Do not use * imports
10. Use try-with-resources where available
11. Use fast fail with configuration methods

## Used tools

1. JUnit 5 for tests
2. AssertJ for soft assertions
3. Awaitility for soft waiting
4. Testcotainers for cluster orchestration
5. Creaper for all WildFly/EAP management - use `Address`, `Operations` and `Administration` for server management

## Specific implementation details

1. Use `WildFlyContainer` mainly for lifecycle of the container
2. Use respective `*Manager` class for other topics

## Assertion patterns

1. **Inside Awaitility's `untilAsserted()` blocks**: Use hard assertions
   - Use `assertThat()` (not `softly.assertThat()`)
   - Assertions control retry logic - should fail fast
   - Standard pattern for polling/retry scenarios

2. **Outside retry blocks**: Use soft assertions where appropriate
   - Use `softly.assertThat()` for multiple independent checks
   - JUnit's `@ExtendWith(SoftAssertionsExtension.class)` auto-calls `assertAll()`
   - Good for comprehensive failure reporting on final state

## Feature parity

Goal of this project is to achieve feature parity in modcluster tests which are implemented in ../noe-tests/modcluster

First phase includes just the WildFly/EAP server as the balancer but next phase will include httpd as a balancer, and it must be counted with.

1. Don't implement ignored tests
2. Understand the subject of test before implementing it
3. Don't assume anything, ask when not sure about subject of the test