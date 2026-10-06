package com.onggijonggi.common.document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Class Name : Chunker.java
 * Description : 문단 우선으로 청크를 나눈다. 문단을 목표 길이까지 이어 붙이고, 최대 길이를 넘는 문단만 문장 경계에서 자른다.
 *               다음 청크 앞에 직전 청크의 끝부분을 겹쳐 문맥이 끊기지 않게 한다. 청크는 섹션(PDF 페이지)을 넘지 않는다.
 *               청크 ID는 문서·회차·순번으로 정해져 같은 회차를 다시 처리해도 같은 청크를 덮어쓴다(중복 없음).
 *               ETL 적재와 검색 평가 세트(#345)가 같은 규칙으로 나눠야 관련성 기준이 실제 색인과 맞아 공용 모듈에 둔다.
 */
public class Chunker {

	/** 추출한 텍스트 한 덩어리. page는 PDF 페이지 번호이고 그 외 형식은 null이다. */
	public record Section(Integer page, String text) { }

	/** 청크 하나. loc은 출처 위치(PDF는 page=N, 그 외는 para=N — 청크가 시작하는 문단 순번). */
	public record Chunk(String id, int seq, String content, String loc) { }

	/** 문단 우선 분할 설정. target까지 문단을 이어 붙이고, max를 넘는 문단만 자르며, 다음 청크 앞에 overlap자를 겹친다. */
	public record Settings(int target, int max, int overlap) {
		/** 처리 회차에 남기는 청킹 설정 지문. 설정이 바뀐 문서를 골라 재처리할 때 쓴다. */
		public String fingerprint() {
			return "para-v1:" + target + "/" + max + "/" + overlap;
		}
	}

	private final Settings settings;

	public Chunker(Settings settings) {
		this.settings = settings;
	}

	public List<Chunk> chunk(UUID document, int runSeq, List<Section> sections) {
		List<Chunk> chunks = new ArrayList<>();
		int paragraphIndex = 0;
		for (Section section : sections) {
			StringBuilder current = new StringBuilder();
			int startParagraph = paragraphIndex + 1;
			String previousTail = "";
			for (String paragraph : paragraphs(section.text())) {
				paragraphIndex++;
				for (String piece : splitLong(paragraph)) {
					// 겹침 꼬리만 있는 상태에서는 내보내지 않는다(꼬리만으로 된 청크 방지). 그래서 청크는 최대 max + overlap + 구분자(2자)다.
					if (current.length() > previousTail.length() && current.length() + piece.length() + 2 > settings.target()) {
						previousTail = emit(chunks, document, runSeq, section, startParagraph, current.toString());
						current = new StringBuilder(previousTail);
						startParagraph = paragraphIndex;
					}
					if (current.length() > 0) current.append("\n\n");
					current.append(piece);
				}
			}
			if (current.length() > previousTail.length()) emit(chunks, document, runSeq, section, startParagraph, current.toString());
		}
		return chunks;
	}

	private String emit(List<Chunk> chunks, UUID document, int runSeq, Section section, int paragraph, String content) {
		int seq = chunks.size() + 1;
		String loc = section.page() != null ? "page=" + section.page() : "para=" + paragraph;
		chunks.add(new Chunk(document + ":" + runSeq + ":" + seq, seq, content.strip(), loc));
		// 겹침: 끝에서 overlap 자 안쪽의 공백부터 잘라 단어 중간에서 시작하지 않게 한다.
		if (settings.overlap() <= 0 || content.length() <= settings.overlap()) return "";
		String tail = content.substring(content.length() - settings.overlap());
		int space = tail.indexOf(' ');
		return (space >= 0 && space < tail.length() - 1 ? tail.substring(space + 1) : tail).strip();
	}

	private static List<String> paragraphs(String text) {
		List<String> result = new ArrayList<>();
		for (String block : text.replace("\r\n", "\n").split("\n\\s*\n")) {
			String paragraph = block.strip();
			if (!paragraph.isEmpty()) result.add(paragraph);
		}
		return result;
	}

	/** 최대 길이를 넘는 문단을 문장 경계(마침표·물음표·느낌표·줄바꿈)에서 자른다. 경계가 없으면 최대 길이에서 자른다. */
	private List<String> splitLong(String paragraph) {
		if (paragraph.length() <= settings.max()) return List.of(paragraph);
		List<String> pieces = new ArrayList<>();
		String rest = paragraph;
		while (rest.length() > settings.max()) {
			int cut = boundary(rest, settings.max());
			pieces.add(rest.substring(0, cut).strip());
			rest = rest.substring(cut).strip();
		}
		if (!rest.isEmpty()) pieces.add(rest);
		return pieces;
	}

	private int boundary(String text, int max) {
		for (int i = max; i > settings.target() / 2; i--) {
			char c = text.charAt(i - 1);
			if (c == '.' || c == '?' || c == '!' || c == '\n' || c == '。') return i;
		}
		return max;
	}
}
