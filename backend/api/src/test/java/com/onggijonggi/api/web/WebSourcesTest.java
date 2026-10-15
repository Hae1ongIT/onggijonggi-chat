package com.onggijonggi.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Class Name : WebSourcesTest.java
 * Description : 턴별 웹 출처 모음을 검증한다(이슈 #401) — 같은 주소 합치기(읽은 쪽 우선, 요약·검색어 이어받기),
 *               요약·제목 자르기, 답변당 상한과 그때 읽은 페이지를 먼저 남기는 것, 넣은 순서 유지.
 */
class WebSourcesTest {

	private static WebSearchProvider.Result result(String title, String url, String snippet) {
		return new WebSearchProvider.Result(title, url, snippet);
	}

	@Test
	void keepsSearchResultsInOrderWithTheirQuery() {
		WebSources sources = new WebSources();

		sources.addSearch("스프링 가이드", List.of(result("Spring", "https://spring.io/guides", "요약 1"),
				result("Docs", "https://docs.spring.io/", "요약 2")));

		assertThat(sources.sources()).extracting(WebSources.Source::url, WebSources.Source::title,
				WebSources.Source::snippet, WebSources.Source::query, WebSources.Source::read)
				.containsExactly(tuple("https://spring.io/guides", "Spring", "요약 1", "스프링 가이드", false),
						tuple("https://docs.spring.io/", "Docs", "요약 2", "스프링 가이드", false));
	}

	@Test
	void keepsTheFirstQueryForTheSameUrl() {
		WebSources sources = new WebSources();

		sources.addSearch("처음", List.of(result("Spring", "https://spring.io/guides#top", "요약")));
		sources.addSearch("다음", List.of(result("Spring 2", "https://SPRING.io/guides", "다른 요약")));

		assertThat(sources.sources()).singleElement().satisfies(source -> {
			assertThat(source.query()).isEqualTo("처음");
			assertThat(source.title()).isEqualTo("Spring");
		});
	}

	@Test
	void mergesAReadPageIntoItsSearchResult() {
		WebSources sources = new WebSources();
		sources.addSearch("스프링", List.of(result("Spring", "https://spring.io/guides", "검색 요약")));

		sources.addPage(WebTools.key("https://spring.io/guides"), "https://spring.io/guides/index.html", "Guides");

		assertThat(sources.sources()).singleElement().isEqualTo(new WebSources.Source(
				"https://spring.io/guides/index.html", "Guides", "검색 요약", "스프링", true));
	}

	@Test
	void keepsTheSearchTitleWhenThePageHasNone() {
		WebSources sources = new WebSources();
		sources.addSearch("스프링", List.of(result("Spring", "https://spring.io/guides", "요약")));

		sources.addPage(WebTools.key("https://spring.io/guides"), "https://spring.io/guides", " ");

		assertThat(sources.sources()).singleElement().extracting(WebSources.Source::title).isEqualTo("Spring");
	}

	@Test
	void keepsAPageReadWithoutSearchWithoutQueryOrSnippet() {
		WebSources sources = new WebSources();

		sources.addPage(WebTools.key("https://a.com/x"), "https://a.com/x", "제목");

		assertThat(sources.sources()).singleElement()
				.isEqualTo(new WebSources.Source("https://a.com/x", "제목", "", null, true));
	}

	@Test
	void cutsLongSnippetsAndTitles() {
		WebSources sources = new WebSources();

		sources.addSearch("q", List.of(result("제".repeat(WebSources.MAX_TITLE + 5), "https://a.com/",
				"요".repeat(WebSources.MAX_SNIPPET + 5))));

		WebSources.Source source = sources.sources().get(0);
		assertThat(source.title()).isEqualTo("제".repeat(WebSources.MAX_TITLE) + "…");
		assertThat(source.snippet()).isEqualTo("요".repeat(WebSources.MAX_SNIPPET) + "…");
	}

	@Test
	void ignoresResultsThatAreNotWebUrls() {
		WebSources sources = new WebSources();

		sources.addSearch("q", List.of(result("파일", "file:///etc/passwd", "요약")));

		assertThat(sources.sources()).isEmpty();
	}

	@Test
	void capsTheSourcesKeepingReadPagesFirst() {
		WebSources sources = new WebSources();
		sources.addSearch("q", IntStream.range(0, WebSources.MAX + 5)
				.mapToObj(i -> result("결과 " + i, "https://a.com/" + i, "요약")).toList());
		sources.addPage(WebTools.key("https://b.com/late"), "https://b.com/late", "늦게 읽은 페이지");

		List<WebSources.Source> kept = sources.sources();

		assertThat(kept).hasSize(WebSources.MAX);
		assertThat(kept).last().extracting(WebSources.Source::url).isEqualTo("https://b.com/late");
		assertThat(kept.get(0).url()).isEqualTo("https://a.com/0");
		assertThat(kept).extracting(WebSources.Source::url).doesNotContain("https://a.com/19");
	}
}
