/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.cameltooling.lsp.internal.completion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.tooling.model.EipModel;
import org.apache.camel.tooling.model.EipModel.EipOptionModel;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.github.cameltooling.lsp.internal.catalog.util.ModelDocumentationHelper;

/**
 * Completion and hover for the keys of a Camel YAML DSL document which are not part of an endpoint URI:
 * EIP names in {@code steps}, EIP options and their values, expression languages and data formats.
 * The available keys are computed from the Camel catalog model of the EIP owning the mapping.
 */
public class CamelYamlDslKeywordProcessor {

	private static final String STEPS = "steps";
	private static final String FROM = "from";
	private static final String ROUTE = "route";
	private static final String PARAMETERS = "parameters";
	private static final String EXPRESSION = "expression";
	private static final String OUTPUTS = "outputs";
	private static final String KIND_EXPRESSION = "expression";
	private static final String KIND_ELEMENT = "element";
	private static final String EIP_LABEL = "eip";
	private static final Set<String> MODELS_WITH_PARAMETERS = Set.of(FROM, "to", "toD", "wireTap", "poll", "kamelet");
	/** Options whose key in the YAML DSL is not the option name, per model. */
	private static final Map<String, Map<String, String>> YAML_KEYS = Map.of(
			ROUTE, Map.of("input", FROM),
			"routeTemplate", Map.of("templateParameter", PARAMETERS, "templateBean", "beans"));

	private final YamlDslLines lines;
	private final CompletableFuture<CamelCatalog> camelCatalog;
	private final boolean markdown;

	public CamelYamlDslKeywordProcessor(YamlDslLines lines, CompletableFuture<CamelCatalog> camelCatalog, boolean markdown) {
		this.lines = lines;
		this.camelCatalog = camelCatalog;
		this.markdown = markdown;
	}

	public CompletableFuture<List<CompletionItem>> getCompletions(Position position) {
		int ownerLine = lines.owner(position);
		String ownerKey = ownerLine >= 0 ? lines.key(ownerLine) : null;
		if (ownerKey == null || lines.isInsideBlockScalar(position)) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		String typed = lines.contentBeforeCursor(position);
		int separator = YamlDslLines.separatorIndex(typed);
		// in "expression:", the key is the name of an expression language supported by the EIP owning the expression
		String expressionOwnerKey = EXPRESSION.equals(ownerKey) ? lines.key(lines.owner(new Position(ownerLine, 0))) : null;
		return camelCatalog.thenApply(catalog -> {
			if (separator > 0) {
				return getValueCompletions(catalog, ownerKey, typed.substring(0, separator).trim());
			}
			List<CompletionItem> keys;
			if (STEPS.equals(ownerKey)) {
				keys = getStepCompletions(catalog);
			} else if (EXPRESSION.equals(ownerKey)) {
				keys = createModelItems(catalog, getExpressionLanguages(catalog, expressionOwnerKey), CompletionItemKind.Value);
			} else {
				keys = getKeyCompletions(catalog, ownerKey);
			}
			String filter = typed.toLowerCase(Locale.ROOT);
			return keys.stream()
					.filter(item -> item.getLabel().toLowerCase(Locale.ROOT).startsWith(filter))
					.collect(Collectors.toList());
		});
	}

	public CompletableFuture<Hover> getHover(Position position) {
		int lineNumber = position.getLine();
		String key = lines.key(lineNumber);
		int keyStart = lines.contentColumn(lineNumber);
		int keyEnd = lines.keyEndColumn(lineNumber);
		if (key == null || position.getCharacter() < keyStart || position.getCharacter() >= keyEnd || lines.isInsideBlockScalar(position)) {
			return CompletableFuture.completedFuture(null);
		}
		int ownerLine = lines.owner(position);
		String ownerKey = ownerLine >= 0 ? lines.key(ownerLine) : null;
		Range range = new Range(new Position(lineNumber, keyStart), new Position(lineNumber, keyEnd));
		return camelCatalog.thenApply(catalog -> {
			EipOptionModel option = ownerKey != null ? findOption(catalog, ownerKey, key) : null;
			if (option != null) {
				return new Hover(ModelDocumentationHelper.getOptionDocumentation(key, option, markdown), range);
			}
			EipModel model = catalog.eipModel(key);
			return model != null ? new Hover(ModelDocumentationHelper.getModelDocumentation(model, markdown), range) : null;
		});
	}

	private List<CompletionItem> getStepCompletions(CamelCatalog catalog) {
		List<CompletionItem> items = new ArrayList<>();
		for (String name : catalog.findModelNames()) {
			EipModel model = catalog.eipModel(name);
			if (model != null && model.getLabel() != null && Arrays.asList(model.getLabel().split(",")).contains(EIP_LABEL)) {
				items.add(createModelItem(model, CompletionItemKind.Keyword));
			}
		}
		return items;
	}

	private List<CompletionItem> getKeyCompletions(CamelCatalog catalog, String ownerKey) {
		EipModel model = catalog.eipModel(ownerKey);
		if (model == null) {
			return Collections.emptyList();
		}
		List<CompletionItem> items = new ArrayList<>();
		for (EipOptionModel option : model.getOptions()) {
			String yamlKey = getYamlKey(ownerKey, option);
			List<String> oneOfs = option.getOneOfs() != null ? option.getOneOfs() : Collections.emptyList();
			if (yamlKey == null) {
				continue;
			}
			if (OUTPUTS.equals(option.getName())) {
				items.add(createOptionItem(yamlKey, option));
			} else if (KIND_EXPRESSION.equals(option.getKind())) {
				// the expression can be written either in an expression option or directly with the language name
				items.add(createOptionItem(yamlKey, option));
				items.addAll(createModelItems(catalog, oneOfs, CompletionItemKind.Value));
			} else if (KIND_ELEMENT.equals(option.getKind()) && !oneOfs.isEmpty() && !oneOfs.equals(List.of(option.getName()))) {
				// for instance the data formats of marshal, which are written with their own name
				items.addAll(createModelItems(catalog, oneOfs, CompletionItemKind.Value));
			} else {
				items.add(createOptionItem(yamlKey, option));
			}
		}
		if (MODELS_WITH_PARAMETERS.contains(ownerKey)) {
			items.add(createKeyItem(PARAMETERS, "The options of the endpoint"));
		}
		if (FROM.equals(ownerKey)) {
			items.add(createKeyItem(STEPS, "The EIPs processing the messages consumed by the endpoint"));
		}
		return items;
	}

	private List<CompletionItem> getValueCompletions(CamelCatalog catalog, String ownerKey, String key) {
		EipOptionModel option = findOption(catalog, ownerKey, key);
		if (option == null) {
			return Collections.emptyList();
		}
		List<String> values;
		if (option.getEnums() != null && !option.getEnums().isEmpty()) {
			values = option.getEnums();
		} else if ("boolean".equals(option.getType())) {
			values = List.of("true", "false");
		} else {
			return Collections.emptyList();
		}
		return createValueItems(values, option.getDefaultValue() != null ? option.getDefaultValue().toString() : null);
	}

	static List<CompletionItem> createValueItems(List<String> values, String defaultValue) {
		List<CompletionItem> items = new ArrayList<>();
		for (String value : values) {
			CompletionItem item = new CompletionItem(value);
			item.setKind(CompletionItemKind.EnumMember);
			boolean isDefault = value.equals(defaultValue);
			if (isDefault) {
				item.setDetail("Default value");
			}
			item.setSortText((isDefault ? "0-" : "1-") + value);
			items.add(item);
		}
		return items;
	}

	private Collection<String> getExpressionLanguages(CamelCatalog catalog, String expressionOwnerKey) {
		EipModel owner = expressionOwnerKey != null ? catalog.eipModel(expressionOwnerKey) : null;
		if (owner != null) {
			for (EipOptionModel option : owner.getOptions()) {
				if (KIND_EXPRESSION.equals(option.getKind()) && option.getOneOfs() != null) {
					return option.getOneOfs();
				}
			}
		}
		return catalog.findLanguageNames();
	}

	private EipOptionModel findOption(CamelCatalog catalog, String ownerKey, String yamlKey) {
		EipModel model = catalog.eipModel(ownerKey);
		if (model == null) {
			return null;
		}
		return model.getOptions().stream()
				.filter(option -> yamlKey.equals(getYamlKey(ownerKey, option)))
				.findFirst()
				.orElse(null);
	}

	/**
	 * @return the key of the option in the YAML DSL, or null if the option is not written as a key of the model
	 */
	private static String getYamlKey(String ownerKey, EipOptionModel option) {
		if (OUTPUTS.equals(option.getName())) {
			// the outputs of a route are the steps of its from
			return ROUTE.equals(ownerKey) ? null : STEPS;
		}
		return YAML_KEYS.getOrDefault(ownerKey, Collections.emptyMap()).getOrDefault(option.getName(), option.getName());
	}

	private List<CompletionItem> createModelItems(CamelCatalog catalog, Collection<String> names, CompletionItemKind kind) {
		List<CompletionItem> items = new ArrayList<>();
		for (String name : names) {
			EipModel model = catalog.eipModel(name);
			items.add(model != null ? createModelItem(model, kind) : createKeyItem(name, null));
		}
		return items;
	}

	private CompletionItem createModelItem(EipModel model, CompletionItemKind kind) {
		CompletionItem item = createKeyItem(model.getName(), model.getTitle());
		item.setKind(kind);
		setDocumentation(item, ModelDocumentationHelper.getModelDocumentation(model, markdown));
		CompletionResolverUtils.applyDeprecation(item, model.isDeprecated());
		return item;
	}

	private CompletionItem createOptionItem(String key, EipOptionModel option) {
		CompletionItem item = createKeyItem(key, option.getJavaType() != null ? option.getJavaType() : option.getType());
		setDocumentation(item, ModelDocumentationHelper.getOptionDocumentation(key, option, markdown));
		item.setSortText((option.isRequired() ? "0-" : "1-") + key);
		CompletionResolverUtils.applyDeprecation(item, option.isDeprecated());
		return item;
	}

	private CompletionItem createKeyItem(String key, String detail) {
		CompletionItem item = new CompletionItem(key);
		item.setKind(CompletionItemKind.Property);
		item.setInsertText(key + ": ");
		item.setDetail(detail);
		return item;
	}

	private void setDocumentation(CompletionItem item, MarkupContent documentation) {
		if (markdown) {
			item.setDocumentation(documentation);
		} else {
			item.setDocumentation(documentation.getValue());
		}
	}
}
