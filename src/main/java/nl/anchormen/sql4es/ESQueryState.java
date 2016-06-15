package nl.anchormen.sql4es;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.aggregations.Aggregation;

import com.facebook.presto.sql.tree.Explain;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QueryBody;

import nl.anchormen.sql4es.jdbc.ESStatement;
import nl.anchormen.sql4es.model.Column;
import nl.anchormen.sql4es.model.Heading;
import nl.anchormen.sql4es.model.OrderBy;
import nl.anchormen.sql4es.model.Utils;
import nl.anchormen.sql4es.model.Column.Operation;
import nl.anchormen.sql4es.model.expression.IComparison;
import nl.anchormen.sql4es.parse.se.SearchAggregationParser;
import nl.anchormen.sql4es.parse.se.SearchHitParser;
import nl.anchormen.sql4es.parse.sql.QueryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains the state of a {@link ESStatement} and is used interpret SELECT statements,
 * execute and parse them and keep {@link ResultSet} state while doing so.
 *  
 * @author cversloot
 *
 */
public class ESQueryState{

	// relevant resources
	private final QueryParser parser = new QueryParser();
	private final Client client;
	private final Properties props;
	private final Statement statement;
	private final SearchHitParser hitParser = new SearchHitParser();
	private final SearchAggregationParser aggParser = new SearchAggregationParser();
	private final Logger logger = LoggerFactory.getLogger(ESQueryState.class);

	// state definition
	private int maxRows = -1;
	private SearchRequestBuilder request;
	private ESResultSet result = null;
	private SearchResponse esResponse;
	private Heading heading = new Heading();;
	private int limit = -1;
	private IComparison having = null;
	private List<OrderBy> orderings = new ArrayList<OrderBy>();

	/**
	 * Creates a QueryState using the specified client. This involves retrieving index and type information
	 * from Elasticsearch.
	 * @param client
	 * @param statement
	 * @throws SQLException
	 */
	public ESQueryState(Client client, Statement statement) throws SQLException{
		this.client = client;
		this.statement = statement;
		this.props = statement.getConnection().getClientInfo();
	}
	
	/**
	 * Builds the Elasticsearch query to be executed on the specified indexes. This function refreshes the 
	 * state after which it is not possible to retrieve results for any previously build queries. 
	 * @param sql
	 * @param indices
	 * @return 
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	public void buildRequest(String sql, QueryBody query, String... indices) throws SQLException {
		if(this.esResponse != null && this.esResponse.getScrollId() != null){
			client.prepareClearScroll().addScrollId(this.esResponse.getScrollId()).execute();
		}
		logger.info("es index: " + indices + " ; " + "sql: " + sql);
		this.request = client.prepareSearch(indices);
		Map<String, Map<String, Integer>> esInfo = (Map<String, Map<String, Integer>>)Utils.getObjectProperty(props, Utils.PROP_TABLE_COLUMN_MAP);
		Object[] info = parser.parse(sql, query, maxRows, request, this.statement.getConnection().getClientInfo(), esInfo);
		this.heading = (Heading)info[0];
		if(info[1] != null) having = (IComparison)info[1];
		if(info[2] != null) orderings = (List<OrderBy>)info[2];
		this.limit = (int)info[3];
		
		// add highlighting
		for(Column column : heading.columns()){
			if(column.getOp() == Operation.HIGHLIGHT){
				request.addHighlightedField(column.getColumn(), Utils.getIntProp(props, Utils.PROP_FRAGMENT_SIZE, 100), 
						Utils.getIntProp(props, Utils.PROP_FRAGMENT_NUMBER, 1));
			}
		}
	}
	
	/**
	 * Builds the request defined within the explain statement and returns its string representation
	 * @param sql
	 * @param explain
	 * @param indexes
	 * @return
	 * @throws SQLException
	 */
	public String explain(String sql, Explain explain, String... indexes) throws SQLException {
		com.facebook.presto.sql.tree.Statement explanSt = explain.getStatement();
		if(!(explanSt instanceof Query)) throw new SQLException("Can only EXPLAIN SELECT ... statements");
		this.buildRequest(sql, ((Query)explanSt).getQueryBody(), indexes);
		return this.request.toString();
	}

	/**
	 * Executes the current query and returns the first ResultSet if query was successful
	 * @return
	 * @throws SQLException
	 */
	public ResultSet execute() throws SQLException {
		return this.execute(Utils.getBooleanProp(props, Utils.PROP_RESULT_NESTED_LATERAL, true));
	}
	
	/**
	 * Used by {@link ESUpdateState} to execute a query and force nested view. This result can be used to
	 * to insert data or delete rows. 
	 * @param useLateral
	 * @return
	 * @throws SQLException
	 */
	ResultSet execute(boolean useLateral) throws SQLException{
		if(request == null) throw new SQLException("Unable to execute query because it has not correctly been parsed");
		//System.out.println(request);
		this.esResponse = this.request.execute().actionGet();
		//System.out.println(esResponse);
		ESResultSet rs = convertResponse(useLateral);
		if(rs == null) throw new SQLException("No result found for this query");
		if(this.result != null) this.result.close();
		this.result = rs;
		return this.result;
	}

	/**
	 * Parses the result from ES and converts it into an ESResultSet object
	 * @return
	 * @throws SQLException
	 */
	private ESResultSet convertResponse(boolean useLateral) throws SQLException{
		if(esResponse.getHits().getHits().length == 0 && esResponse.getScrollId() != null){
			esResponse = client.prepareSearchScroll(esResponse.getScrollId())
					.setScroll(new TimeValue(Utils.getIntProp(props, Utils.PROP_SCROLL_TIMEOUT_SEC, 60)*1000))
					.execute().actionGet();
		}
		// parse aggregated result
		if(esResponse.getAggregations() != null){
			ESResultSet rs = new ESResultSet(this);
			for(Aggregation agg : esResponse.getAggregations()){
				aggParser.parseAggregation(agg, rs);
			}
			if(rs.getNrRows() == 0) return null;
			if(having != null) rs.filterHaving(having);
			rs.setTotal(rs.getNrRows());
			if(!orderings.isEmpty()){
				rs.orderBy(orderings);
			}
			rs.executeComputations();
			return rs;
		}else{
			// parse plain document hits
			long total = esResponse.getHits().getTotalHits();
			if(getLimit() > 0) total = Math.min(total, getLimit());
			ESResultSet rs = hitParser.parse(esResponse.getHits(), this.heading, total, Utils.getIntProp(props, Utils.PROP_DEFAULT_ROW_LENGTH, 1000), useLateral, 0);
			rs.executeComputations();
			return rs;
		}
	}
	
	public ResultSet moreResutls(boolean useLateral) throws SQLException {
		if(result != null && result.getOffset() + result.getNrRows() >= result.getTotal()) return null;
		if(result != null) result.close();
		if(esResponse.getScrollId() != null ){
			esResponse = client.prepareSearchScroll(esResponse.getScrollId())
					.setScroll(new TimeValue(Utils.getIntProp(props, Utils.PROP_SCROLL_TIMEOUT_SEC, 60)*1000))
					.execute().actionGet();
			ESResultSet rs = convertResponse(useLateral);
			if(rs.getNrRows() == 0) return null;
			result = rs;
			return result;
		}
		return null;
	}
	
	public Heading getHeading() {
		return heading;
	}
	
	public Statement getStatement(){
		return statement;
	}

	public void close() throws SQLException {
		if(this.esResponse != null && this.esResponse.getScrollId() != null){
			client.prepareClearScroll().addScrollId(this.esResponse.getScrollId()).execute();
		}
		if(this.result != null) result.close();
	}

	/**
	 * Allows to set a limit other than using LIMIT in the SQL
	 */
	public void setMaxRows(int size){
		this.maxRows = size;
	}
	
	public int getMaxRows(){
		return maxRows;
	}
	
	public ESQueryState copy() throws SQLException{
		return new ESQueryState(client, statement);
	}
	
	public int getLimit(){
		if(limit <= -1 ) return this.getMaxRows();
		if(getMaxRows() <= -1) return limit;
		return Math.min(limit, getMaxRows());
	}	
	
	public int getIntProp(String name, int def) {
		return Utils.getIntProp(props, name, def);
	}
	
}


