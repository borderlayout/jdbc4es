import java.sql.*;

/**
 * Created by 201601050162 on 2016/5/16.
 */

/**
 * Created by 201601050162 on 2016/5/16.
 */
public class TestEsJdbc {

	public static void main(String args[]) {
		System.out.println("Hello World!");
		Connection connection = null;
		try {
			Class.forName("nl.anchormen.sql4es.jdbc.ESDriver");
			connection = DriverManager.getConnection("jdbc:sql4es://192.168.88.132:9300/db_name");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			System.out.println("get class for crate error 1");
			return;
		} catch (SQLException e) {
			e.printStackTrace();
			System.out.println("get conn for crate error 1");
			return;
		}

		try{
			String POST_TABLE = "table_name";  //type name
			PreparedStatement statement = connection.prepareStatement(String.format(
				"SELECT * FROM %s limit 10", POST_TABLE));

			ResultSet rs = statement.executeQuery();
			int col = rs.getMetaData().getColumnCount();
			while (rs.next()) {
				for (int i = 1; i <= col; i++) {
					if(i == 4  ) {
						System.out.print(rs.getInt(i) + "\t");

					}
					else{
						System.out.print(rs.getString(i) + "\t");
					}
				}
				System.out.println("");
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}

	}
}
