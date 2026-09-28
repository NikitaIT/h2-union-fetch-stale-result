package repro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each test prepares one statement and executes it twice, changing a single
 * parameter in between, over committed and unchanged data. The second
 * execution must reflect the new parameter value.
 *
 * <p>The tests named {@code bug...} fail on H2 2.4.240 and 2.5.252 with the
 * default {@code OPTIMIZE_REUSE_RESULTS=TRUE}: the parenthesized UNION term
 * returns its previous result. The tests named {@code control...} pass.
 */
class UnionTermParameterTest {

    /** A second UNION term that returns nothing, so the result is the first term's. */
    private static final String EMPTY_TERM = "(SELECT ID FROM T WHERE ID < 0)";

    private static int databases;

    private final Map<String, PreparedStatement> statements = new HashMap<>();

    private Connection connection;

    @BeforeEach
    void createTable() throws SQLException {
        connection = DriverManager.getConnection(url(""), "sa", "");
        connection.createStatement().execute("CREATE TABLE T(ID INT PRIMARY KEY)");
        connection.createStatement().execute("INSERT INTO T SELECT X FROM SYSTEM_RANGE(1, 5)");
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    @Test
    void bugFetchParameterInUnionTerm() throws SQLException {
        String sql = "(SELECT ID FROM T ORDER BY ID FETCH FIRST ? ROWS ONLY) UNION " + EMPTY_TERM;
        assertEquals(List.of(1), ids(sql, 1));
        assertEquals(List.of(1, 2, 3), ids(sql, 3));
    }

    @Test
    void bugOffsetParameterInUnionTerm() throws SQLException {
        String sql = "(SELECT ID FROM T ORDER BY ID OFFSET ? ROWS) UNION " + EMPTY_TERM;
        assertEquals(List.of(1, 2, 3, 4, 5), ids(sql, 0));
        assertEquals(List.of(3, 4, 5), ids(sql, 2));
    }

    @Test
    void bugOrderByParameterInUnionTerm() throws SQLException {
        String sql = "(SELECT ID FROM T ORDER BY ABS(ID - ?) FETCH FIRST 1 ROWS ONLY) UNION " + EMPTY_TERM;
        assertEquals(List.of(1), ids(sql, 1));
        assertEquals(List.of(5), ids(sql, 5));
    }

    @Test
    void bugFetchParameterInRightUnionTerm() throws SQLException {
        String sql = "SELECT ID FROM T WHERE ID < 0 UNION (SELECT ID FROM T ORDER BY ID FETCH FIRST ? ROWS ONLY)";
        assertEquals(List.of(1), ids(sql, 1));
        assertEquals(List.of(1, 2, 3), ids(sql, 3));
    }

    @Test
    void bugFetchParameterInUnionUnderInPredicate() throws SQLException {
        String sql = "SELECT ID FROM T WHERE ID IN ("
                + "(SELECT ID FROM T ORDER BY ID FETCH FIRST ? ROWS ONLY) UNION " + EMPTY_TERM + ")";
        assertEquals(List.of(1), ids(sql, 1));
        assertEquals(List.of(1, 2, 3), ids(sql, 3));
    }

    @Test
    void controlWhereParameterInUnionTerm() throws SQLException {
        String sql = "(SELECT ID FROM T WHERE ID >= ? ORDER BY ID FETCH FIRST 2 ROWS ONLY) UNION " + EMPTY_TERM;
        assertEquals(List.of(1, 2), ids(sql, 1));
        assertEquals(List.of(3, 4), ids(sql, 3));
    }

    @Test
    void controlFetchParameterWithoutUnion() throws SQLException {
        String sql = "SELECT * FROM (SELECT ID FROM T ORDER BY ID FETCH FIRST ? ROWS ONLY) X";
        assertEquals(List.of(1), ids(sql, 1));
        assertEquals(List.of(1, 2, 3), ids(sql, 3));
    }

    @Test
    void controlFetchParameterInUnionTermWithReuseDisabled() throws SQLException {
        connection.close();
        connection = DriverManager.getConnection(url(";OPTIMIZE_REUSE_RESULTS=FALSE"), "sa", "");
        connection.createStatement().execute("CREATE TABLE T(ID INT PRIMARY KEY)");
        connection.createStatement().execute("INSERT INTO T SELECT X FROM SYSTEM_RANGE(1, 5)");
        String sql = "(SELECT ID FROM T ORDER BY ID FETCH FIRST ? ROWS ONLY) UNION " + EMPTY_TERM;
        assertEquals(List.of(1), ids(sql, 1));
        assertEquals(List.of(1, 2, 3), ids(sql, 3));
    }

    /** A fresh in-memory database per test, so no state is shared between tests. */
    private static String url(String settings) {
        return "jdbc:h2:mem:repro" + (++databases) + settings;
    }

    /**
     * Executes {@code sql} with a single parameter, reusing one prepared
     * statement per SQL text, as a connection pool's statement cache or an ORM
     * would.
     */
    private List<Integer> ids(String sql, int parameter) throws SQLException {
        PreparedStatement ps = statements.get(sql);
        if (ps == null || ps.isClosed()) {
            ps = connection.prepareStatement(sql);
            statements.put(sql, ps);
        }
        ps.setInt(1, parameter);
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        }
        ids.sort(null);
        return ids;
    }
}
