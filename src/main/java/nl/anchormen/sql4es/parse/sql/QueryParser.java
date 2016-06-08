package nl.anchormen.sql4es.parse.sql;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.sort.SortOrder;

import com.facebook.presto.sql.tree.AstVisitor;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QueryBody;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.facebook.presto.sql.tree.SelectItem;
import com.facebook.presto.sql.tree.SortItem;

import nl.anchormen.sql4es.QueryState;
import nl.anchormen.sql4es.model.BasicQueryState;
import nl.anchormen.sql4es.model.Heading;
import nl.anchormen.sql4es.model.OrderBy;
import nl.anchormen.sql4es.model.TableRelation;
import nl.anchormen.sql4es.model.Utils;
import nl.anchormen.sql4es.model.expression.IComparison;

/**
 * Interprets the parsed query and build the appropriate ES query (a {@link SearchRequestBuilder} instance). 
 * The other parses within this package are used to parse their speicific clause (WHERE, HAVING etc)
 *  
 * @author cversloot
 *
 */
public class QueryParser extends AstVisitor<Object[], SearchRequestBuilder>{
	
	private final static SelectParser selectParser = new SelectParser();
	private final static WhereParser whereParser = new WhereParser();
	private final static HavingParser havingParser = new HavingParser();
	private final static RelationParser relationParser = new RelationParser();
	private final static GroupParser groupParser = new GroupParser();
	private final static OrderByParser orderOarser = new OrderByParser();
	
	private String sql;
	private int maxRows = -1;
	private Properties props;
	private Map<String, Map<String, Integer>> tableColumnInfo;
	
	/**
	 * Builds the provided {@link SearchRequestBuilder} by parsing the {@link Query} using the properties provided.
	 * @param sql the original sql statement
	 * @param queryBody the Query parsed from the sql
	 * @param searchReq the request to build from
	 * @param props a set of properties to use in certain cases
	 * @param tableColumnInfo mapping from available tables to columns and their typesd
	 * @return an array containing [ {@link Heading}, {@link IComparison} having, List&lt;{@link OrderBy}&gt; orderings, Integer limit]
	 * @throws SQLException
	 */
	public Object[] parse(String sql, QueryBody queryBody, int maxRows, SearchRequestBuilder searchReq, 
			Properties props, Map<String, Map<String, Integer>> tableColumnInfo) throws SQLException{
		this.sql = sql.replace("\r", " ").replace("\n", " ");// TODO: this removes linefeeds from string literals as well!
		this.props = props;
		this.maxRows = maxRows;
		this.tableColumnInfo = tableColumnInfo;
		
		if(queryBody instanceof QuerySpecification){
			Object[] result = queryBody.accept(this, searchReq);
			if(result.length > 0 && result[0] instanceof QueryState ) throw ((QueryState)result[0]).getException();
			else if (result.length < 4) throw new SQLException("Failed to parse query due to unknown reason");
			return result;
		}
		throw new SQLException("The provided query does not contain a QueryBody");
	}
	
	@SuppressWarnings("rawtypes")
	@Override
	protected Object[] visitQuerySpecification(QuerySpecification node, SearchRequestBuilder searchReq){
		Heading heading = new Heading();
		BasicQueryState state = new BasicQueryState(sql, heading, props);
		int limit = -1;
		List<TableRelation> relations = new ArrayList<>();
		AggregationBuilder aggregation = null;
		QueryBuilder query;
		IComparison having = null;
		List<OrderBy> orderings = new ArrayList<>();
		boolean useCache = false;
		
		// check for distinct in combination with group by
		if(node.getSelect().isDistinct() && !node.getGroupBy().isEmpty()){
			state.addException("Unable to combine DISTINCT and GROUP BY within a single query");
			return new Object[]{state};
		}
		
		// get limit (possibly used by other parsers)
		if(node.getLimit().isPresent()){
			limit = Integer.parseInt(node.getLimit().get());
		}
		if(state.hasException()) return new Object[]{state};
		
		//req.getHeading().fixAliases(req.originalSql());
		if(node.getFrom().isPresent()){
			relations = node.getFrom().get().accept(relationParser, state);
			if(state.hasException()) return new Object[]{state};
			if(relations.size() < 1) {
				state.addException("Specify atleast one valid table to execute the query on!");
				return new Object[]{state};
			}
			for(int i=0; i<relations.size(); i++){
				if(relations.get(i).getTable().toLowerCase().equals(props.getProperty(Utils.PROP_QUERY_CACHE_TABLE, "query_cache"))){
					useCache = true;
					relations.remove(i);
					i--;
				}
			}
			heading.setTypes(this.typesForColumns(relations));
			state.setRelations(relations);
		}
		
		// get columns to fetch (builds the header)
		for(SelectItem si : node.getSelect().getSelectItems()){
			si.accept(selectParser, state);
		}
		if(state.hasException()) return new Object[]{state};
		boolean requestScore = heading.hasLabel("_score");
		
		
		// Translate column references and their aliases back to their case sensitive forms
		heading.reorderAndFixColumns(this.sql, "select.+", ".+from");
		heading.setTypes(this.typesForColumns(relations));
		
		// create aggregation in case of DISTINCT
		if(node.getSelect().isDistinct()){
			aggregation = groupParser.addDistinctAggregation(state);
		}

		// add a Query
		query = QueryBuilders.matchAllQuery();
		if(node.getWhere().isPresent()){
			query = node.getWhere().get().accept(whereParser, state);
		}
		if(state.hasException()) return new Object[]{state};
		
		// parse group by and create aggregations accordingly
		if(node.getGroupBy() != null && node.getGroupBy().size() > 0){
			aggregation = groupParser.parse(node.getGroupBy(), state);
		}else if(heading.aggregateOnly()){
			aggregation = groupParser.buildFilterAggregation(query, heading);
		}
		if(state.hasException()) return new Object[]{state};
		
		// parse Having (is executed client side after results have been fetched)
		if(node.getHaving().isPresent()){
			having = node.getHaving().get().accept(havingParser, state);
		}

		// parse ORDER BY
		if(!node.getOrderBy().isEmpty()){
			for(SortItem si : node.getOrderBy()){
				OrderBy ob = si.accept(orderOarser, state);
				if(state.hasException()) return new Object[]{state};
				orderings.add(ob);
			}
		}
		if(state.hasException()) return new Object[]{state};
		
		buildQuery(searchReq, relations, query, aggregation, orderings, limit, useCache, requestScore) ;
		return new Object[]{heading, having, orderings, limit};
	}

	/**
	 * Builds the actual Elasticsearch request using all the information provided
	 * @param searchReq a
	 * @param relations a
	 * @param query a
	 * @param aggregation a
	 * @param orderings a
	 * @param limit a
	 * @param useCache a
	 */
	@SuppressWarnings("rawtypes")
	private void buildQuery(SearchRequestBuilder searchReq, List<TableRelation> relations,
	                        QueryBuilder query, AggregationBuilder aggregation, List<OrderBy> orderings,
	                        int limit, boolean useCache, boolean requestScore) {
		String[] types = new String[relations.size()];
		for(int i=0; i<relations.size(); i++) types[i] = relations.get(i).getTable(); 
		SearchRequestBuilder req = searchReq.setTypes(types);
		
		// add filters and aggregations
		if(aggregation != null){
			// when aggregating the query must be a query and not a filter
			if(query != null)	req.setQuery(query);
			req.addAggregation(aggregation);
			
		// ordering does not work on aggregations (has to be done in client)
		}else if(query != null){
			if(requestScore) req.setQuery(query); // use query instead of filter to get a score
			else req.setPostFilter(query);
			
			// add order
			for(OrderBy ob : orderings){
				req.addSort(ob.getField(), ob.getOrder());
			}
		} else req.setQuery(QueryBuilders.matchAllQuery());
		
		int fetchSize = Utils.getIntProp(props, Utils.PROP_FETCH_SIZE, 10000);
		// add limit and determine to use scroll
		if(aggregation != null) {
			req = req.setSize(0);
		} else if(determineLimit(limit) > 0 && determineLimit(limit)  < fetchSize){
			req.setSize(determineLimit(limit) );
		} else if (orderings.isEmpty()){ // scrolling does not work well with sort
			req.setSize(fetchSize); 
			req.addSort("_doc", SortOrder.ASC);
			req.setScroll(new TimeValue(Utils.getIntProp(props, Utils.PROP_SCROLL_TIMEOUT_SEC, 60)*1000));
		}
		
		// use query cache when this was indicated in FROM clause
		if(useCache) req.setRequestCache(true);
		req.setTimeout(TimeValue.timeValueMillis(Utils.getIntProp(props, Utils.PROP_QUERY_TIMEOUT_MS, 10000)));
	}

	/**
	 * Gets SQL column types for the provided tables as a map from colname to java.sql.Types
	 * @param relations a
	 * @return a
	 */
	public Map<String, Integer> typesForColumns(List<TableRelation> relations){
		HashMap<String, Integer> colType = new HashMap<>();
		colType.put(Heading.ID, Types.VARCHAR);
		colType.put(Heading.TYPE, Types.VARCHAR);
		colType.put(Heading.INDEX, Types.VARCHAR);
		for(TableRelation table : relations){
			if(!tableColumnInfo.containsKey(table.getTable())) continue;
			colType.putAll( tableColumnInfo.get(table.getTable()) );
		}
		return colType;
	}

//	/**
//	 * Gets a property from the connection
//	 * @param name
//	 * @return a
//	 */
	/*
	public Object getProperty(String name){
		return this.props.get(name);
	}
	*/
	public int determineLimit(int limit){
		if(limit <= -1 ) return this.maxRows;
		if(maxRows <= -1) return limit;
		return Math.min(limit, maxRows);
	}

}
