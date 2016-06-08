import com.google.gson.internal.LinkedTreeMap;
import io.searchbox.annotations.JestId;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;


import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Created by 201601050162 on 2016/5/23.
 */
public class TestTsRest {
	public static void main(String args[]) {
		JestClientFactory factory = new JestClientFactory();
		factory.setHttpClientConfig(new HttpClientConfig
			.Builder("http://10.100.30.220:9200")
			.multiThreaded(true)
			.build());
		JestClient client = factory.getObject();

		//client.execute(new CreateIndex.Builder("articles").build());
		String query = "{\n" +
			"    \"query\": {\n" +
			"        \"match\" : {\n" +
			"        \"col_1\": 8405 \n " +
			"        }\n" +
			"    }\n" +
			"}";

		Search search = new Search.Builder(query)
			// multiple index or types can be added.
			.addIndex("people")
			.build();

		class Article {
			@JestId
			private String documentId;
		};

		try{
			SearchResult result = client.execute(search);
			Integer total = result.getTotal();
			System.out.println(total);

			List<Article> articles = result.getSourceAsObjectList(Article.class);
			for(Integer i=0; i<articles.size(); i++){
				Article a = articles.get(i);
				System.out.println(a.documentId);
			}

			List<SearchResult.Hit<Object, Void>> hits = result.getHits(Object.class);
			LinkedTreeMap<String,String> ltmap = (LinkedTreeMap<String,String>)hits.get(0).source;
			System.out.println(ltmap.get("id"));

			System.out.println(hits.get(0).index);
			System.out.println(hits.get(0).score);
			System.out.println(hits.get(0).type);
			System.out.println(hits.get(0).sort.get(0));

		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("get conn for crate error 1");
			return;
		}
	}
}
