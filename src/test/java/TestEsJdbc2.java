import java.sql.*;

/**
 * Created by 201601050162 on 2016/5/16.
 */

/**
 * Created by 201601050162 on 2016/5/16.
 */
public class TestEsJdbc2 {

	public static void main(String args[]) {
		System.out.println("Hello World!");
		Connection connection = null;
		try {
			Class.forName("nl.anchormen.sql4es.jdbc.ESDriver");
			connection = DriverManager.getConnection("jdbc:sql4es://10.100.30.220:9300/park?cluster.name=my-olap-cluster");
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
/*			String POST_TABLE = "data";  //type name
			PreparedStatement statement = connection.prepareStatement(String.format(
				"SELECT * FROM %s limit 10", POST_TABLE));

			ResultSet rs = statement.executeQuery();
			int col = rs.getMetaData().getColumnCount();
			while (rs.next()) {
				for (int i = 1; i <= col; i++) {
					if(i==1 || i==2 || i==3|| i==9|| i==11|| i==15|| i==16|| i==21){
						System.out.print(rs.getString(i) + "\t");
					}
					else {
						System.out.print(rs.getInt(i) + "\t");
					}
				}
				System.out.println("");
			}*/
			String POST_TABLE = "animal";  //type name
			PreparedStatement statement = connection.prepareStatement(String.format(
				"select id, col_1, col_2 from animal  ORDER BY col_1, col_2", POST_TABLE));

			ResultSet rs = statement.executeQuery();
			int col = rs.getMetaData().getColumnCount();
			while (rs.next()) {
				for (int i = 1; i <= col; i++) {

						System.out.print(rs.getInt(i) + "\t");

				}
				System.out.println("");
			}
			rs.close();
			connection.close();

		} catch (SQLException e) {
			e.printStackTrace();
		}

	}
}
