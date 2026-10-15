package com.onggijonggi.api.web;

import java.util.List;

/**
 * Class Name : WebSourcesFixture.java
 * Description : 다른 패키지 테스트가 웹 도구를 거치지 않고 턴의 웹 출처 모음을 채우게 한다(이슈 #401).
 *               채우는 메서드는 웹 도구만 부르도록 패키지 안에 닫아 두었다.
 */
public final class WebSourcesFixture {

	private WebSourcesFixture() {
	}

	/** 웹 검색 한 번이 결과 하나를 찾은 것처럼 넣는다. */
	public static void searched(WebSources sources, String query, String title, String url, String snippet) {
		sources.addSearch(query, List.of(new WebSearchProvider.Result(title, url, snippet)));
	}

	/** 웹 읽기 한 번이 성공한 것처럼 넣는다. */
	public static void read(WebSources sources, String url, String title) {
		sources.addPage(WebTools.key(url), url, title);
	}
}
