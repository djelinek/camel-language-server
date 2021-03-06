/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.cameltooling.lsp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.junit.After;

public abstract class AbstractCamelLanguageServerTest {

	protected static final String KAFKA_SYNTAX_HOVER = "kafka:topic";
	protected static final String AHC_DOCUMENTATION = "To call external HTTP services using Async Http Client.";
	protected static final String FILE_FILTER_DOCUMENTATION = "Pluggable filter as a org.apache.camel.component.file.GenericFileFilter class. Will skip files if filter returns false in its accept() method.";
	protected static final String DUMMY_URI = "dummyUri";
	private String extensionUsed;
	protected PublishDiagnosticsParams lastPublishedDiagnostics;
	private CamelLanguageServer camelLanguageServer;

	public AbstractCamelLanguageServerTest() {
		super();
	}
	
	@After
	public void tearDown() {
		if (camelLanguageServer != null) {
			camelLanguageServer.stopServer();
		}
	}

	protected CompletionItem createExpectedAhcCompletionItem(int lineStart, int characterStart, int lineEnd, int characterEnd) {
		CompletionItem expectedAhcCompletioncompletionItem = new CompletionItem("ahc:httpUri");
		expectedAhcCompletioncompletionItem.setDocumentation(AHC_DOCUMENTATION);
		expectedAhcCompletioncompletionItem.setDeprecated(false);
		expectedAhcCompletioncompletionItem.setTextEdit(new TextEdit(new Range(new Position(lineStart, characterStart), new Position(lineEnd, characterEnd)), "ahc:httpUri"));
		return expectedAhcCompletioncompletionItem;
	}
	
	final class DummyLanguageClient implements LanguageClient {

		@Override
		public void telemetryEvent(Object object) {
		}

		@Override
		public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
			return null;
		}

		@Override
		public void showMessage(MessageParams messageParams) {
		}

		@Override
		public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
			AbstractCamelLanguageServerTest.this.lastPublishedDiagnostics = diagnostics;
		}

		@Override
		public void logMessage(MessageParams message) {
		}
	}

	protected CamelLanguageServer initializeLanguageServer(String text) throws URISyntaxException, InterruptedException, ExecutionException {
		return initializeLanguageServer(text, ".xml");
	}

	protected CamelLanguageServer initializeLanguageServer(String text, String suffixFileName) throws URISyntaxException, InterruptedException, ExecutionException {
		this.extensionUsed = suffixFileName;
		InitializeParams params = new InitializeParams();
		params.setProcessId(new Random().nextInt());
		params.setRootUri(getTestResource("/workspace/").toURI().toString());
		camelLanguageServer = new CamelLanguageServer();
		camelLanguageServer.connect(new DummyLanguageClient());
		camelLanguageServer.startServer();
		CompletableFuture<InitializeResult> initialize = camelLanguageServer.initialize(params);

		assertThat(initialize).isCompleted();
		assertThat(initialize.get().getCapabilities().getCompletionProvider().getResolveProvider()).isTrue();

		camelLanguageServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(createTestTextDocument(text, suffixFileName)));

		return camelLanguageServer;
	}

	protected CamelLanguageServer initializeLanguageServer(FileInputStream stream, String suffixFileName) {
		try (BufferedReader buffer = new BufferedReader(new InputStreamReader(stream))) {
            return initializeLanguageServer(buffer.lines().collect(Collectors.joining("\n")), suffixFileName);
        } catch (ExecutionException | InterruptedException | URISyntaxException | IOException ex) {
        	return null;
        }
	}
	
	private TextDocumentItem createTestTextDocument(String text, String suffixFileName) {
		return new TextDocumentItem(DUMMY_URI + suffixFileName, CamelLanguageServer.LANGUAGE_ID, 0, text);
	}

	protected CompletableFuture<Either<List<CompletionItem>, CompletionList>> getCompletionFor(CamelLanguageServer camelLanguageServer, Position position) {
		TextDocumentService textDocumentService = camelLanguageServer.getTextDocumentService();
		
		CompletionParams completionParams = new CompletionParams(new TextDocumentIdentifier(DUMMY_URI+extensionUsed), position);
		CompletableFuture<Either<List<CompletionItem>, CompletionList>> completions = textDocumentService.completion(completionParams);
		return completions;
	}
	
	protected CompletableFuture<List<? extends SymbolInformation>> getDocumentSymbolFor(CamelLanguageServer camelLanguageServer) {
		TextDocumentService textDocumentService = camelLanguageServer.getTextDocumentService();
		DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(DUMMY_URI+extensionUsed));
		return textDocumentService.documentSymbol(params);
	}
	
	protected CompletableFuture<List<? extends Location>> getReferencesFor(CamelLanguageServer camelLanguageServer, Position position) {
		TextDocumentService textDocumentService = camelLanguageServer.getTextDocumentService();
		ReferenceParams params = new ReferenceParams();
		params.setPosition(position);
		params.setTextDocument(new TextDocumentIdentifier(DUMMY_URI+extensionUsed));
		return textDocumentService.references(params);
	}
	
	protected CompletableFuture<List<? extends Location>> getDefinitionsFor(CamelLanguageServer camelLanguageServer, Position position) {
		TextDocumentService textDocumentService = camelLanguageServer.getTextDocumentService();
		TextDocumentPositionParams params = new TextDocumentPositionParams();
		params.setPosition(position);
		params.setTextDocument(new TextDocumentIdentifier(DUMMY_URI+extensionUsed));
		return textDocumentService.definition(params);
	}

	public File getTestResource(String name) throws URISyntaxException {
		return Paths.get(CamelLanguageServerTest.class.getResource(name).toURI()).toFile();
	}
	
	protected boolean hasTextEdit(CompletionItem item) {
		return item != null && item.getTextEdit() != null;
	}
	
}