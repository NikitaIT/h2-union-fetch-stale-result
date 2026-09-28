package repro;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** The minimal reproduction: expected 1 / 3 / 5 rows, H2 returns 1 / 1 / 1. */
public class Main {

    public static void main(String[] args) throws Exception {
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
                        while (rs.next()) {
                            rows++;
                        }
                    }
                    System.out.println("FETCH FIRST " + limit + " -> " + rows + " row(s)");
                }
            }
        }
    }
}
