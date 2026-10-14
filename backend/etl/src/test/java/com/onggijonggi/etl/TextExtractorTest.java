package com.onggijonggi.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.onggijonggi.common.document.Chunker;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * Class Name : TextExtractorTest.java
 * Description : 형식별 추출과 출처 위치, 글자 없음·손상 파일의 영구 실패를 확인한다.
 */
class TextExtractorTest {

	private final TextExtractor extractor = new TextExtractor();

	@Test
	void plainTextIsReadAsUtf8WithoutTheBom() {
		var sections = extractor.extract("규정.md", ("﻿연차는 사흘 전에 신청한다.").getBytes(StandardCharsets.UTF_8));
		assertThat(sections).singleElement().satisfies(section -> {
			assertThat(section.page()).isNull();
			assertThat(section.text()).isEqualTo("연차는 사흘 전에 신청한다.");
		});
	}

	/** macOS 등에서 온 분해형(NFD) 한글을 조합형(NFC)으로 맞춘다 — 그대로 두면 NFC 질문과 키워드가 맞지 않는다. */
	@Test
	void decomposedHangulIsComposed() {
		String decomposed = java.text.Normalizer.normalize("연차 이월", java.text.Normalizer.Form.NFD);
		var sections = extractor.extract("notes.txt", decomposed.getBytes(StandardCharsets.UTF_8));
		assertThat(sections).singleElement().satisfies(section -> assertThat(section.text()).isEqualTo("연차 이월"));
	}

	@Test
	void pdfIsReadPageByPage() throws IOException {
		var sections = extractor.extract("guide.pdf", pdf("first page text", "second page text"));
		assertThat(sections).extracting(Chunker.Section::page).containsExactly(1, 2);
		assertThat(sections.get(1).text()).contains("second page text");
	}

	@Test
	void docxIsReadThroughTika() throws IOException {
		try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
			document.createParagraph().createRun().setText("휴가 규정 본문");
			document.write(out);
			assertThat(extractor.extract("rule.docx", out.toByteArray())).singleElement()
					.satisfies(section -> assertThat(section.text()).contains("휴가 규정 본문"));
		}
	}

	@Test
	void blankOrUnreadableSourcesFailPermanently() throws IOException {
		assertThatThrownBy(() -> extractor.extract("empty.txt", "  \n ".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.code()).isEqualTo("EMPTY_TEXT"));
		assertThatThrownBy(() -> extractor.extract("scan.pdf", pdf()))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> assertThat(failure.code()).isEqualTo("EMPTY_TEXT"));
		assertThatThrownBy(() -> extractor.extract("broken.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("UNREADABLE");
					assertThat(failure.permanent()).isTrue();
				});
	}

	@Test
	void cp949TextFromWindowsIsReadAndUndecodableTextFailsInsteadOfBeingGarbled() {
		var sections = extractor.extract("명단.csv", "이름,팀\n홍길동,인사".getBytes(java.nio.charset.Charset.forName("MS949")));
		assertThat(sections.get(0).text()).isEqualTo("이름,팀\n홍길동,인사");
		assertThatThrownBy(() -> extractor.extract("깨짐.txt", new byte[] {0x41, (byte) 0x80}))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("UNSUPPORTED_ENCODING");
					assertThat(failure.permanent()).isTrue();
				});
	}

	@Test
	void docxParagraphsAreSeparatedByBlankLinesSoChunkLocationsCountParagraphs() throws IOException {
		try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
			for (String text : new String[] {"첫 문단", "둘째 문단", "셋째 문단"}) document.createParagraph().createRun().setText(text);
			document.write(out);
			String text = extractor.extract("rule.docx", out.toByteArray()).get(0).text();
			assertThat(text).contains("첫 문단\n\n둘째 문단\n\n셋째 문단");
		}
	}

	@Test
	void unsupportedExtensionFailsPermanently() {
		assertThatThrownBy(() -> extractor.extract("slide.pptx", new byte[] {1}))
				.isInstanceOfSatisfying(EtlFailure.class, failure -> {
					assertThat(failure.code()).isEqualTo("UNSUPPORTED_FILE");
					assertThat(failure.permanent()).isTrue();
				});
	}

	@Test
	void pdfLayoutSpacingIsTidied() {
		assertThat(TextExtractor.tidyLayout("  제   1   조 \n\n\n\n  연차\u00A0\u00A0휴가  "))
				.isEqualTo("제 1 조\n\n연차 휴가");
	}

	/** 글자만 있는 PDF(페이지마다 한 줄). 인자가 없으면 글자 없는 한 페이지 — 스캔 PDF와 같다. */
	static byte[] pdf(String... pages) throws IOException {
		try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
			if (pages.length == 0) document.addPage(new PDPage());
			for (String text : pages) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (var content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					content.newLineAtOffset(72, 700);
					content.showText(text);
					content.endText();
				}
			}
			document.save(out);
			return out.toByteArray();
		}
	}
}
