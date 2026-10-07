# Coding standards

These rules apply to production code, tests, and build-time JavaScript. Code review
checks correctness and style separately. Passing a formatter or linter establishes
only the rules that the tool actually checks.

## Java

Use the [SE-Education basic and intermediate Java standard](https://se-education.org/guides/conventions/java/intermediate.html).
For uncovered topics, use the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
SE-Education rules take precedence. The separate advanced standard is not required.

Use four-space indentation, an eight-space continuation offset, a 110-character
soft target and 120-character hard limit, explicit imports, meaningful names,
braced control structures, and the standard's Javadoc requirements and exceptions.
Test names may follow `methodUnderTest_scenario_expectedBehavior`. Comments use
English and American spelling. A Javadoc opening `/**` occupies its own line.

The user requires a stricter project documentation rule, using the `tp` project's
Javadoc contracts as the reference. Document **every declared Java method and
constructor**, including private helpers, accessors, interface methods, overrides,
test methods, and test utilities. This explicitly overrides the base standard's
method-documentation exceptions; it is a local rule, not an additional rule
imposed by SE-Education. Descriptive type headers follow the base standard.

Each declaration must have an accurate summary and the applicable tags:

- `@param` for each value parameter and method/constructor type parameter, in
  declaration order. Describe meaning, units, nullability, and constraints where
  relevant. Record type headers must document their components too.
- `@return` for every non-void method, describing the result and relevant null,
  future-completion, or failure semantics. Constructors and void methods must
  not have a return tag.
- `@throws` for every declared exception and meaningful unchecked rejection or
  failure condition. Distinguish directly thrown exceptions from asynchronous
  failures delivered through a returned future. Methods with no such condition
  need no invented throws tag.

Separate the summary from tags with a blank Javadoc line. Keep descriptions
specific to the actual operation, using exact monetary units and transaction
boundaries where they matter. Prefer relevant `{@code ...}` and `{@link ...}`
references over unnecessary tags such as author or version boilerplate.
The local reference was read from `tp/src/main/java/seedu/address/` files
`commons/util/ToStringBuilder.java`, `logic/parser/ArgumentTokenizer.java`,
`commons/util/StringUtil.java`, and `storage/Storage.java` under the user's
`/Users/keith/Desktop/CS2103T/` workspace; those reference files remain unchanged.

Checkstyle enforces missing method documentation and nonempty applicable parameter,
return, and declared-exception tags across production and test Java. It also
checks its configured naming, layout, imports, and braces. Review semantic accuracy,
meaningful unchecked failures, and asynchronous error contracts manually.
Configuration is in `config/checkstyle/`.

## JavaScript

Use the applicable ECMAScript and browser portions of the
[Google JavaScript Style Guide](https://google.github.io/styleguide/jsguide.html)
as the base. This is a JavaScript project; migrating to TypeScript is outside this
assignment. The guide is no longer maintained, so new language features need an
explicit project decision rather than an assumed upstream rule.

The project's rules are:

- Use UTF-8 `.js` files with lowercase hyphenated names, native ES modules, and
  explicit imports. Use named exports. Do not add application globals or circular imports. Isolated tests may temporarily
  replace browser globals with mocks, restoring them in `finally`.
- Use two-space block indentation and readable continuation indentation. Keep
  lines within 80 characters, except source URLs. Use K&R braces, semicolons,
  single quotes, and trailing commas in multiline literals. Put each statement
  on its own line. Object braces have no interior padding spaces.
- Use braces around every conditional and loop body, including short bodies.
  Always parenthesize arrow function parameters. These are project additions
  that remove optional variations permitted by the base guide.
- Use `const` unless reassignment is required; otherwise use `let`. Do not use
  `var`, loose equality, implicit globals, unused variables, unreachable code,
  `eval`, or prototype modification. Await or explicitly handle asynchronous
  failures; do not silently discard an uncertain financial outcome.
- Use descriptive `lowerCamelCase` variables and verb-based function names,
  `UpperCamelCase` classes, and `UPPER_SNAKE_CASE` primitive module constants.
  Boolean state uses `is`, `has`, or `should`. Collection names are plural.
- Add a file overview to application modules and JSDoc for named functions and
  classes. Explain side effects, failure semantics, and non-obvious arguments
  or types. Test cases need descriptive scenario names, rather than redundant
  headers. Comments use English and American spelling.
- Keep money and rates as exact strings. Never parse financial values as
  JavaScript `Number`, use floating-point arithmetic on them, or calculate FX
  in the browser. Use server-calculated results and `textContent` for display.
- Retain the original financial request key and exact input strings before
  sending. An uncertain result must retain that identity across retries and
  reloads. Unreadable saved data must prevent admission of a new financial
  request until storage is restored and the original outcome can be resolved.

`eslint.config.js` implements the checkable subset. It uses a default export
because ESLint's configuration loader requires that shape; this is the sole
project exception to named exports. It is build configuration, not a browser
module. ESLint enforces syntax, whitespace, bindings, braces, and equality rules;
review JSDoc accuracy, names, module boundaries, exact money transport, and retry
behavior manually. Do not suppress a finding without a specific reason.

## Review and verification

Read applicable repository instructions and surrounding code before editing or
reporting a finding. Distinguish source requirements, source recommendations,
project additions, and design suggestions. Use concrete file/line findings and
small corrections; do not infer correctness from a style check alone.

Run `./gradlew check` after changes. It includes Checkstyle, JUnit tests, ESLint,
and JavaScript tests. Run `./gradlew bootJar` to build the runnable submission.
Use `npm run check` to check JavaScript separately. Tool versions are pinned;
`package-lock.json` records the JavaScript dependency graph. Node.js 22.13 or newer
and npm are required for the JavaScript checks, but the running application needs
only Java and a modern browser.
