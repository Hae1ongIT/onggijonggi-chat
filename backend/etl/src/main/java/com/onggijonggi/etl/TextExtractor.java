package com.onggijonggi.etl;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

/**
 * Class Name : TextExtractor.java
 * Description : 원본에서 검색용 텍스트를 뽑는다. 첨부와 달리 잘라내지 않는다(지도 선택 9).
 *               txt·md·csv는 Tika 문자셋 추정 대신 UTF-8 → CP949 순서로 엄격히 읽는다(짧은 한글 파일을 Tika가 잘못 추정할 수 있어서다).
 *               PDF는 페이지 단위로 읽어 출처 위치를 페이지로 남기고, DOCX는 Tika로 읽는다.
 */
@Component
public class TextExtractor {

	/** 뽑은 텍스트 한 덩어리. page는 PDF만 있고 그 외는 null이다. */
	public record Section(Integer page, String text) { }

	public List<Section> extract(String fileName, byte[] bytes) {
		String extension = extension(fileName);
		List<Section> sections;
		try {
			sections = switch (extension) {
				case "txt", "md", "csv" -> List.of(new Section(null, stripBom(decodeText(bytes))));
				case "pdf" -> pdf(bytes);
				// Tika는 DOCX 문단 사이에 줄바꿈 하나만 넣는다. 빈 줄로 바꿔 청킹이 문단을 알아보게 한다(출처 위치 para=N).
				case "docx" -> List.of(new Section(null, tika(bytes).replaceAll("\\n+", "\n\n")));
				default -> throw EtlFailure.permanent("UNSUPPORTED_FILE", "지원하지 않는 형식: " + extension);
			};
		} catch (EtlFailure failure) {
			throw failure;
		} catch (RuntimeException error) {
			throw EtlFailure.permanent("UNREADABLE", "원본을 읽지 못했다(손상·암호)", error);
		}
		List<Section> nonBlank = sections.stream().filter(section -> section.text() != null && !section.text().isBlank()).toList();
		if (nonBlank.isEmpty()) throw EtlFailure.permanent("EMPTY_TEXT", "글자를 찾지 못했다(스캔 PDF 등)");
		return nonBlank;
	}

	private static List<Section> pdf(byte[] bytes) {
		var config = PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build();
		List<Document> pages = new PagePdfDocumentReader(new ByteArrayResource(bytes), config).get();
		return pages.stream().map(page -> new Section(pageNumber(page), tidyLayout(page.getText()))).toList();
	}

	/** PDF 페이지 리더는 화면 배치를 공백으로 흉내 낸다. 검색·청크 길이에 의미 없는 공백을 줄인다. */
	static String tidyLayout(String text) {
		if (text == null) return "";
		return text.replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").strip();
	}

	private static Integer pageNumber(Document page) {
		Object value = page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
		return value instanceof Number number ? number.intValue() : null;
	}

	private static String tika(byte[] bytes) {
		return new TikaDocumentReader(new ByteArrayResource(bytes)).get().stream()
				.map(Document::getText).filter(Objects::nonNull).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
	}

	/**
	 * UTF-8로 엄격히 읽고, 아니면 CP949(MS949)로 읽는다 — 한국어 Windows·엑셀에서 저장한 TXT·CSV가 흔히 CP949다. 느슨하게 읽으면
	 * 깨진 바이트가 대체 문자(U+FFFD)로 바뀐 채 READY가 되어, 검색·출처에 쓸 수 없는 본문이 정상 처리로 보인다. 둘 다 아니면 영구 실패다.
	 */
	static String decodeText(byte[] bytes) {
		for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("MS949"))) {
			try {
				return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(bytes)).toString();
			} catch (CharacterCodingException notThisCharset) {
				// 다음 문자셋으로 본다.
			}
		}
		throw EtlFailure.permanent("UNSUPPORTED_ENCODING", "UTF-8·CP949가 아닌 텍스트");
	}

	private static String stripBom(String text) {
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	static String extension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
