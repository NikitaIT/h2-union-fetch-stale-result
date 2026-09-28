# H2: stale result from a parenthesized UNION term when only its FETCH / OFFSET / ORDER BY parameter changes

With `OPTIMIZE_REUSE_RESULTS` on (the default), a parenthesized query term of a
`UNION` returns its **previous result** when the only parameter that changed is
one in its own `ORDER BY`, `OFFSET` or `FETCH` clause. Parameters in its
`WHERE` clause are handled correctly. No error is raised; the rows are just
wrong.

Reproduced on H2 **2.4.240** and **2.5.252** (latest release at the time of
writing). The parser code involved is unchanged on `master`.

## Run it

Requires JDK 17+.

```bash
./gradlew run                            # the minimal reproduction below
./gradlew test                           # 5 bug cases fail, 3 controls pass
./gradlew test -Ph2Version=2.4.240       # against another H2 release
```

## Minimal reproduction

[`src/main/java/repro/Main.java`](src/main/java/repro/Main.java):

```java
try (Connection c = DriverManager.getConnection("jdbc:h2:mem:repro", "sa", "")) {
    c.createStatement().execute("CREATE TABLE T(ID INT PRIMARY KEY)");
    c.createStatement().execute("INSERT INTO T SELECT X FROM SYSTEM_RANGE(1, 5)");
    try (PreparedStatement ps = c.prepareStatement(
            "(SELECT ID FROM T ORDER BY ID FETCH FIRST ? ROWS ONLY)"
                    + " UNION (SELECT ID FROM T WHERE ID < 0)")) {
        for (int limit : new int[] {1, 3, 5}) {
            ps.setInt(1, limit);
            int rows = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows++;
            }
            System.out.println("FETCH FIRST " + limit + " -> " + rows + " row(s)");
        }
    }
}
```

Expected:

```
FETCH FIRST 1 -> 1 row(s)
FETCH FIRST 3 -> 3 row(s)
FETCH FIRST 5 -> 5 row(s)
```

Actual, on 2.4.240 and 2.5.252:

```
FETCH FIRST 1 -> 1 row(s)
FETCH FIRST 3 -> 1 row(s)
FETCH FIRST 5 -> 1 row(s)
```

With `;OPTIMIZE_REUSE_RESULTS=FALSE` in the URL the output is correct.

## Cases

[`UnionTermParameterTest`](src/test/java/repro/UnionTermParameterTest.java)
prepares each statement once and executes it twice over committed, unchanged
data, changing a single parameter in between:

| Test                                                  | Parameter that changes                                   | Result |
| ----------------------------------------------------- | -------------------------------------------------------- | ------ |
| `bugFetchParameterInUnionTerm`                        | `(… FETCH FIRST ? ROWS ONLY) UNION (…)`                  | stale  |
| `bugOffsetParameterInUnionTerm`                       | `(… OFFSET ? ROWS) UNION (…)`                            | stale  |
| `bugOrderByParameterInUnionTerm`                      | `(… ORDER BY ABS(ID - ?) FETCH FIRST 1 ROWS ONLY) UNION (…)` | stale  |
| `bugFetchParameterInRightUnionTerm`                   | `SELECT … UNION (… FETCH FIRST ? ROWS ONLY)`             | stale  |
| `bugFetchParameterInUnionUnderInPredicate`            | `… WHERE ID IN ((… FETCH FIRST ? ROWS ONLY) UNION (…))`  | stale  |
| `controlWhereParameterInUnionTerm`                    | `(… WHERE ID >= ? …) UNION (…)`                          | correct |
| `controlFetchParameterWithoutUnion`                   | `SELECT * FROM (SELECT … FETCH FIRST ? ROWS ONLY) X`     | correct |
| `controlFetchParameterInUnionTermWithReuseDisabled`   | first case, with `OPTIMIZE_REUSE_RESULTS=FALSE`          | correct |

```
UnionTermParameterTest > bugFetchParameterInUnionTerm() FAILED
    org.opentest4j.AssertionFailedError: expected: <[1, 2, 3]> but was: <[1]>
UnionTermParameterTest > bugOffsetParameterInUnionTerm() FAILED
    org.opentest4j.AssertionFailedError: expected: <[3, 4, 5]> but was: <[1, 2, 3, 4, 5]>
UnionTermParameterTest > bugOrderByParameterInUnionTerm() FAILED
    org.opentest4j.AssertionFailedError: expected: <[5]> but was: <[1]>
```

The `IN` form is how this shows up in practice: an ORM-generated "top k per
group" query, `id IN ((… FETCH FIRST ? ROWS ONLY) UNION (…) …)`, returned the
first execution's k for every later k.

## Cause

`Parser.parseSelect()` opens a parameter scope for the `SELECT` body and closes
it after `QUALIFY`:

```java
command.setParameterList(closeParametersScope(outerUsedParameters));
```

`ORDER BY`, `OFFSET` and `FETCH` are parsed afterwards, by `parseEndOfQuery()`
(via `parseQueryExpressionBodyAndEndOfQuery()`), so their parameters are only
recorded in the enclosing scope. For a top-level query that is fine:
`parseQuery()` sets the complete list on the outermost query. A parenthesized
term, parsed by `parseQueryPrimary()`, never gets a list of its own that
includes them.

`SelectUnion` deliberately bypasses the result cache ("union doesn't always know
the parameter list of the left and right queries"), but runs its terms with
`left.query(0)` / `right.query(0)`, and each term's `Query.query()` does use
the cache. Its reuse check, `sameParameters(getParameterValues(), lastParameters)`,
only sees the term's own list. Inspecting the prepared statement of the minimal
reproduction after one execution:

```
SelectUnion parameters:        [?1, ?2]
left term (Select) parameters: [?1]      <- WHERE only
left term fetchExpr:           ?2        <- not in the list
```

So a different `FETCH` value looks like the same query, and the term's
`lastResult` is returned.

Related, in the same area: [#3534](https://github.com/h2database/h2database/issues/3534).

## Possible fix

Give a parenthesized query its own parameter scope that also covers its
`ORDER BY` / `OFFSET` / `FETCH`, in `h2/src/main/org/h2/command/Parser.java`:

```diff
     private Query parseQueryPrimary() {
         if (readIf(OPEN_PAREN)) {
+            BitSet outerUsedParameters = openParametersScope();
             Query query = parseQueryExpressionBodyAndEndOfQuery(tokenIndex);
+            query.setParameterList(closeParametersScope(outerUsedParameters));
             query.setOuterQueryScope(queryScope);
             read(CLOSE_PAREN);
             return query;
         }
```

With this change applied to the 2.4.240 sources, the minimal reproduction
prints 1 / 3 / 5 rows and all eight tests pass. H2's own test suite has not
been run against it.

## Workaround

Add `;OPTIMIZE_REUSE_RESULTS=FALSE` to the JDBC URL.
