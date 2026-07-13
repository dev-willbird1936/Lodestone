// SPDX-License-Identifier: MIT
package dev.lodestone.runtime;

import dev.lodestone.adapter.CapabilityHandler;
import dev.lodestone.adapter.InvocationContext;
import dev.lodestone.adapter.LodestoneAdapter;
import dev.lodestone.protocol.AdapterDescriptor;
import dev.lodestone.protocol.Availability;
import dev.lodestone.protocol.CapabilityDescriptor;
import dev.lodestone.protocol.CapabilityManifest;
import dev.lodestone.protocol.Environment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Conservative, loader-neutral structural inspection for decoded WorldEdit 7.4 mask text.
 * This deliberately performs no registry, plugin, expression, command, or server evaluation.
 */
final class WorldEditMaskValidationAdapter implements LodestoneAdapter {
    static final String CAPABILITY_ID = "lodestone.worldedit.mask.validate";
    static final String VALIDITY_SCOPE = "lodestone-worldedit-7.4-structural-subset";

    private static final int MAX_TERMS = 256;
    private static final int MAX_LIST_ENTRIES = 256;
    private static final int MAX_PREFIXES = 64;
    private static final int MAX_DELIMITER_DEPTH = 32;
    private static final int MAX_EXPRESSION_TOKENS = 512;
    private static final int MAX_DIAGNOSTICS = 32;
    private static final int MAX_DIAGNOSTIC_MESSAGE = 512;

    private static final Set<String> BUILT_INS = Set.of(
            "#existing", "#solid", "#fullcube", "#air",
            "#region", "#sel", "#selection",
            "#dregion", "#dsel", "#dselection",
            "#clipboard", "#surface", "#exposed");
    private static final Set<String> CONTROL_FLOW = Set.of(
            "break", "case", "continue", "do", "else", "for", "if", "return", "switch", "while");

    private final AdapterDescriptor descriptor = new AdapterDescriptor(
            "lodestone.worldedit.mask", "1.0.0", "minecraft", "worldedit-7.4-structural-subset",
            "runtime", Environment.REMOTE);
    private final CapabilityDescriptor contract = CoreCatalog.load().stream()
            .filter(capability -> capability.id().equals(CAPABILITY_ID))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("core catalog is missing " + CAPABILITY_ID));

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CapabilityManifest manifest() {
        return new CapabilityManifest(descriptor,
                List.of(contract.forAdapter(descriptor, Availability.AVAILABLE, null)));
    }

    @Override
    public Map<String, CapabilityHandler> handlers() {
        return Map.of(CAPABILITY_ID, this::validate);
    }

    private CompletionStage<Map<String, Object>> validate(InvocationContext context) {
        context.cancellation().throwIfCancelled();
        var mask = String.valueOf(context.request().input().get("mask"));
        return CompletableFuture.completedFuture(new Validator(context, mask).validate());
    }

    private static final class Validator {
        private final InvocationContext context;
        private final String raw;
        private final List<Diagnostic> errors = new ArrayList<>();
        private final List<Diagnostic> warnings = new ArrayList<>();
        private final Set<String> warningCodes = new HashSet<>();
        private final TreeSet<String> recognizedKinds = new TreeSet<>();
        private final TreeSet<String> features = new TreeSet<>();
        private boolean diagnosticsTruncated;
        private boolean lexicalOnly;

        private Validator(InvocationContext context, String raw) {
            this.context = context;
            this.raw = raw;
        }

        private Map<String, Object> validate() {
            inspectCharacters();
            var start = 0;
            var end = raw.length();
            while (start < end && raw.charAt(start) == ' ') start++;
            while (end > start && raw.charAt(end - 1) == ' ') end--;
            if (start == end) {
                error("EMPTY_MASK", "Mask must contain non-space syntax.", start);
            }

            var terms = errors.isEmpty() ? splitTerms(start, end) : List.<Term>of();
            if (terms.size() > 1) {
                recognizedKinds.add("intersection");
                features.add("intersection");
            }
            for (var term : terms) {
                context.cancellation().throwIfCancelled();
                parseTerm(term);
            }

            var accepted = errors.isEmpty();
            var output = new LinkedHashMap<String, Object>();
            output.put("valid", accepted);
            output.put("locallyAccepted", accepted);
            output.put("verification", accepted ? (lexicalOnly ? "lexical-only" : "recognized-grammar") : "invalid");
            output.put("validityScope", VALIDITY_SCOPE);
            output.put("semanticValidationPerformed", false);
            output.put("serverValidationRequired", true);
            if (accepted) output.put("normalizedMask", normalize(terms));
            output.put("recognizedKinds", List.copyOf(recognizedKinds));
            output.put("features", List.copyOf(features));
            output.put("errors", diagnosticMaps(errors));
            output.put("warnings", diagnosticMaps(warnings));
            output.put("diagnosticsTruncated", diagnosticsTruncated);
            output.put("bounded", true);
            return Map.copyOf(output);
        }

        private void inspectCharacters() {
            for (var index = 0; index < raw.length(); index++) {
                checkCancellation(index);
                var current = raw.charAt(index);
                if (current < 0x20 || current > 0x7e) {
                    error("NON_ASCII_OR_CONTROL",
                            "Only printable ASCII mask text is accepted by this local policy.", index);
                    return;
                }
                if (current == '\'' || current == '"') {
                    error("QUOTING_NOT_ALLOWED",
                            "Pass decoded mask text without command-layer quotes.", index);
                    return;
                }
            }
        }

        private List<Term> splitTerms(int start, int end) {
            var terms = new ArrayList<Term>();
            var termStart = start;
            var squareDepth = 0;
            var parenthesisDepth = 0;
            for (var index = start; index < end; index++) {
                checkCancellation(index);
                var current = raw.charAt(index);
                if (current == '[') {
                    squareDepth++;
                    if (squareDepth > MAX_DELIMITER_DEPTH) {
                        error("COMPLEXITY_LIMIT", "Bracket nesting exceeds 32 levels.", index);
                        return List.copyOf(terms);
                    }
                } else if (current == ']') {
                    if (squareDepth == 0) {
                        error("UNBALANCED_DELIMITER", "Closing bracket has no opening bracket.", index);
                        return List.copyOf(terms);
                    }
                    squareDepth--;
                } else if (current == '(') {
                    parenthesisDepth++;
                    if (parenthesisDepth > MAX_DELIMITER_DEPTH) {
                        error("COMPLEXITY_LIMIT", "Parenthesis nesting exceeds 32 levels.", index);
                        return List.copyOf(terms);
                    }
                } else if (current == ')') {
                    if (parenthesisDepth == 0) {
                        error("UNBALANCED_DELIMITER", "Closing parenthesis has no opening parenthesis.", index);
                        return List.copyOf(terms);
                    }
                    parenthesisDepth--;
                }
                if (current == ' ' && squareDepth == 0 && parenthesisDepth == 0) {
                    if (index > termStart) {
                        terms.add(new Term(raw.substring(termStart, index), termStart));
                        if (terms.size() > MAX_TERMS) {
                            error("TOO_MANY_TERMS", "Mask exceeds 256 intersection terms.", index);
                            return List.copyOf(terms);
                        }
                    }
                    while (index + 1 < end && raw.charAt(index + 1) == ' ') index++;
                    termStart = index + 1;
                }
            }
            if (squareDepth != 0 || parenthesisDepth != 0) {
                error("UNBALANCED_DELIMITER", "Mask contains an unclosed delimiter.", Math.max(start, end - 1));
                return List.copyOf(terms);
            }
            if (termStart < end) terms.add(new Term(raw.substring(termStart, end), termStart));
            if (terms.size() > MAX_TERMS) {
                error("TOO_MANY_TERMS", "Mask exceeds 256 intersection terms.", end - 1);
            }
            return List.copyOf(terms);
        }

        private void parseTerm(Term term) {
            var cursor = 0;
            var prefixCount = 0;
            while (cursor < term.text().length() && isUnaryPrefix(term.text().charAt(cursor))) {
                if (++prefixCount > MAX_PREFIXES) {
                    error("TOO_MANY_PREFIXES", "Mask term exceeds 64 unary prefixes.", term.offset() + cursor);
                    return;
                }
                var prefix = term.text().charAt(cursor++);
                switch (prefix) {
                    case '!' -> addKindFeature("negation", "negation");
                    case '>' -> addKindFeature("offset-above", "offset-above");
                    case '<' -> addKindFeature("offset-below", "offset-below");
                    case '~' -> addKindFeature("adjacent", "adjacency-six-face");
                    default -> throw new IllegalStateException("unreachable prefix");
                }
            }
            if (cursor == term.text().length()) {
                error("MISSING_OPERAND", "Unary mask prefix has no operand.", term.offset() + cursor - 1);
                return;
            }

            var atom = term.text().substring(cursor);
            var atomOffset = term.offset() + cursor;
            if (atom.startsWith("##")) {
                parseRegistryAtom(atom.substring(2), atomOffset + 2, "tag", "tag");
            } else if (atom.charAt(0) == '#') {
                parseSpecialMask(atom, atomOffset);
            } else if (atom.charAt(0) == '$') {
                parseRegistryAtom(atom.substring(1), atomOffset + 1, "biome", "biome");
            } else if (atom.charAt(0) == '%') {
                parsePercentage(atom, atomOffset);
            } else if (atom.startsWith("^=[")) {
                parseStateMask(atom, atomOffset, true);
            } else if (atom.startsWith("^[")) {
                parseStateMask(atom, atomOffset, false);
            } else if (atom.charAt(0) == '^') {
                error("MALFORMED_STATE", "State masks must use ^[...] or ^=[...].", atomOffset);
            } else if (atom.charAt(0) == '=') {
                parseExpression(atom.substring(1), atomOffset + 1);
            } else {
                parseBlockList(atom, atomOffset);
            }
        }

        private void parseSpecialMask(String atom, int offset) {
            if (BUILT_INS.contains(atom)) {
                recognizedKinds.add("builtin");
                features.add("builtin:" + atom);
                return;
            }
            var extension = atom.substring(1);
            if (!isResourceId(extension)) {
                error("MALFORMED_IDENTIFIER",
                        "Unknown special masks are accepted only as a bare #identifier for server validation.", offset);
                return;
            }
            recognizedKinds.add("plugin-extension");
            features.add("plugin-extension");
            lexicalOnly = true;
            warningOnce("PLUGIN_MASK_REQUIRES_SERVER",
                    "Plugin mask syntax and availability require validation by the target WorldEdit server.", offset);
        }

        private void parseRegistryAtom(String identifier, int offset, String kind, String feature) {
            if (!isResourceId(identifier)) {
                error("MALFORMED_IDENTIFIER", "Malformed " + kind + " identifier.", offset);
                return;
            }
            addKindFeature(kind, feature);
            registryWarning(offset);
        }

        private void parsePercentage(String atom, int offset) {
            var digits = atom.substring(1);
            if (digits.isEmpty()) {
                error("MALFORMED_PERCENT", "Noise percentage requires an integer from 0 through 100.", offset);
                return;
            }
            var value = 0;
            for (var index = 0; index < digits.length(); index++) {
                var current = digits.charAt(index);
                if (current < '0' || current > '9') {
                    error("MALFORMED_PERCENT", "Noise percentage accepts decimal integer digits only.",
                            offset + index + 1);
                    return;
                }
                value = Math.min(101, value * 10 + current - '0');
            }
            if (value > 100) {
                error("PERCENT_OUT_OF_RANGE", "Noise percentage must be from 0 through 100.", offset + 1);
                return;
            }
            addKindFeature("noise", "noise-percent");
        }

        private void parseStateMask(String atom, int offset, boolean exact) {
            var prefixLength = exact ? 3 : 2;
            if (!atom.endsWith("]") || atom.length() <= prefixLength) {
                error("MALFORMED_STATE", "State mask must contain one or more key=value pairs.", offset);
                return;
            }
            var content = atom.substring(prefixLength, atom.length() - 1);
            if (content.indexOf('[') >= 0 || content.indexOf(']') >= 0) {
                error("MALFORMED_STATE", "Nested state brackets are not accepted.", offset + prefixLength);
                return;
            }
            if (!parseStatePairs(content, offset + prefixLength)) return;
            addKindFeature(exact ? "exact-state-mask" : "state-mask", exact ? "exact-state-mask" : "state-mask");
        }

        private void parseBlockList(String atom, int offset) {
            var entries = splitBlockEntries(atom, offset);
            if (entries == null) return;
            if (entries.size() > 1) addKindFeature("block-list", "block-list");
            for (var entry : entries) {
                parseBlock(entry.text(), entry.offset());
            }
        }

        private List<Term> splitBlockEntries(String atom, int offset) {
            var entries = new ArrayList<Term>();
            var start = 0;
            var bracketDepth = 0;
            for (var index = 0; index < atom.length(); index++) {
                var current = atom.charAt(index);
                if (current == '[') bracketDepth++;
                else if (current == ']') bracketDepth--;
                else if (current == ',' && bracketDepth == 0) {
                    if (index == start) {
                        error("MALFORMED_BLOCK_LIST", "Block list contains an empty entry.", offset + index);
                        return null;
                    }
                    entries.add(new Term(atom.substring(start, index), offset + start));
                    if (entries.size() > MAX_LIST_ENTRIES) {
                        error("COMPLEXITY_LIMIT", "Block list exceeds 256 entries.", offset + index);
                        return null;
                    }
                    start = index + 1;
                }
            }
            if (start == atom.length()) {
                error("MALFORMED_BLOCK_LIST", "Block list contains an empty entry.", offset + atom.length() - 1);
                return null;
            }
            entries.add(new Term(atom.substring(start), offset + start));
            if (entries.size() > MAX_LIST_ENTRIES) {
                error("COMPLEXITY_LIMIT", "Block list exceeds 256 entries.", offset + start);
                return null;
            }
            return List.copyOf(entries);
        }

        private void parseBlock(String block, int offset) {
            var bracket = block.indexOf('[');
            var identifier = bracket < 0 ? block : block.substring(0, bracket);
            if (!isResourceId(identifier)) {
                error("MALFORMED_IDENTIFIER", "Malformed block identifier.", offset);
                return;
            }
            addKindFeature("block", "block");
            registryWarning(offset);
            if (bracket < 0) return;
            if (!block.endsWith("]") || block.indexOf('[', bracket + 1) >= 0
                    || block.substring(bracket + 1, block.length() - 1).indexOf(']') >= 0) {
                error("MALFORMED_STATE", "Block state must be one terminal [key=value,...] list.", offset + bracket);
                return;
            }
            var content = block.substring(bracket + 1, block.length() - 1);
            if (!parseStatePairs(content, offset + bracket + 1)) return;
            addKindFeature("block-state", "block-state");
        }

        private boolean parseStatePairs(String content, int offset) {
            if (content.isEmpty()) {
                error("MALFORMED_STATE", "State list cannot be empty.", offset);
                return false;
            }
            var keys = new HashSet<String>();
            var start = 0;
            var entries = 0;
            for (var index = 0; index <= content.length(); index++) {
                if (index < content.length() && content.charAt(index) != ',') continue;
                if (++entries > MAX_LIST_ENTRIES) {
                    error("COMPLEXITY_LIMIT", "State list exceeds 256 entries.", offset + index);
                    return false;
                }
                if (index == start) {
                    error("MALFORMED_STATE", "State list contains an empty entry.", offset + index);
                    return false;
                }
                var pair = content.substring(start, index);
                var equals = pair.indexOf('=');
                if (equals <= 0 || equals == pair.length() - 1 || pair.indexOf('=', equals + 1) >= 0) {
                    error("MALFORMED_STATE", "State entry must be exactly key=value.", offset + start);
                    return false;
                }
                var key = pair.substring(0, equals);
                var value = pair.substring(equals + 1);
                if (!isStateKey(key) || !isStateValue(value)) {
                    error("MALFORMED_STATE", "State key or value is outside the local ASCII subset.", offset + start);
                    return false;
                }
                if (!keys.add(key)) {
                    error("DUPLICATE_STATE", "State key is repeated: " + key, offset + start);
                    return false;
                }
                start = index + 1;
            }
            return true;
        }

        private void parseExpression(String expression, int offset) {
            if (expression.isBlank()) {
                error("MALFORMED_EXPRESSION", "Expression mask cannot be empty.", offset);
                return;
            }
            var depth = 0;
            var tokens = 0;
            var expectedOperand = true;
            var lastIdentifier = false;
            var sawValue = false;
            for (var index = 0; index < expression.length();) {
                checkCancellation(index);
                var current = expression.charAt(index);
                if (current == ' ') {
                    index++;
                    continue;
                }
                if (isIdentifierStart(current)) {
                    if (!expectedOperand) {
                        expressionError("MALFORMED_EXPRESSION", "Expression is missing an operator.", offset + index);
                        return;
                    }
                    var end = index + 1;
                    while (end < expression.length() && isIdentifierPart(expression.charAt(end))) end++;
                    var identifier = expression.substring(index, end);
                    if (CONTROL_FLOW.contains(identifier.toLowerCase(java.util.Locale.ROOT))) {
                        expressionError("UNSUPPORTED_BY_LODESTONE_POLICY",
                                "Control-flow expressions are outside the local safety subset.", offset + index);
                        return;
                    }
                    tokens++;
                    sawValue = true;
                    expectedOperand = false;
                    lastIdentifier = true;
                    index = end;
                } else if (isNumberStart(expression, index)) {
                    if (!expectedOperand) {
                        expressionError("MALFORMED_EXPRESSION", "Expression is missing an operator.", offset + index);
                        return;
                    }
                    var end = scanNumber(expression, index);
                    tokens++;
                    sawValue = true;
                    expectedOperand = false;
                    lastIdentifier = false;
                    index = end;
                } else if (current == '(') {
                    if (!expectedOperand && !lastIdentifier) {
                        expressionError("MALFORMED_EXPRESSION", "Opening parenthesis is missing an operator.",
                                offset + index);
                        return;
                    }
                    if (++depth > MAX_DELIMITER_DEPTH) {
                        expressionError("EXPRESSION_TOO_COMPLEX", "Expression exceeds 32 parenthesis levels.",
                                offset + index);
                        return;
                    }
                    tokens++;
                    expectedOperand = true;
                    lastIdentifier = false;
                    index++;
                } else if (current == ')') {
                    if (depth == 0 || expectedOperand) {
                        expressionError("MALFORMED_EXPRESSION", "Unexpected closing parenthesis.", offset + index);
                        return;
                    }
                    depth--;
                    tokens++;
                    expectedOperand = false;
                    lastIdentifier = false;
                    index++;
                } else if (current == ',') {
                    if (depth == 0 || expectedOperand) {
                        expressionError("MALFORMED_EXPRESSION", "Comma must separate function arguments.",
                                offset + index);
                        return;
                    }
                    tokens++;
                    expectedOperand = true;
                    lastIdentifier = false;
                    index++;
                } else if (startsWith(expression, index, "++") || startsWith(expression, index, "--")) {
                    expressionError("UNSUPPORTED_BY_LODESTONE_POLICY",
                            "Increment and decrement are outside the local safety subset.", offset + index);
                    return;
                } else {
                    var operatorLength = operatorLength(expression, index);
                    if (operatorLength == 0) {
                        var code = current == '=' || current == ';' || current == '{' || current == '}'
                                || current == '[' || current == ']' || current == '\\' || current == '`'
                                || current == '?' || current == ':'
                                ? "UNSUPPORTED_BY_LODESTONE_POLICY" : "MALFORMED_EXPRESSION";
                        expressionError(code, "Expression token is outside the local safety subset.", offset + index);
                        return;
                    }
                    var operator = expression.substring(index, index + operatorLength);
                    if (expectedOperand) {
                        if (!(operator.equals("+") || operator.equals("-") || operator.equals("!")
                                || operator.equals("~"))) {
                            expressionError("MALFORMED_EXPRESSION", "Binary operator has no left operand.",
                                    offset + index);
                            return;
                        }
                    } else if (operator.equals("!") || operator.equals("~")) {
                        expressionError("MALFORMED_EXPRESSION", "Unary operator appears after an operand.",
                                offset + index);
                        return;
                    }
                    tokens++;
                    expectedOperand = true;
                    lastIdentifier = false;
                    index += operatorLength;
                }
                if (tokens > MAX_EXPRESSION_TOKENS) {
                    expressionError("EXPRESSION_TOO_COMPLEX", "Expression exceeds 512 lexical tokens.",
                            offset + Math.min(index, expression.length() - 1));
                    return;
                }
            }
            if (depth != 0 || expectedOperand || !sawValue) {
                expressionError("MALFORMED_EXPRESSION", "Expression is incomplete or unbalanced.",
                        offset + Math.max(0, expression.length() - 1));
                return;
            }
            addKindFeature("expression", "expression");
            lexicalOnly = true;
            warningOnce("EXPRESSION_NOT_EVALUATED",
                    "Expression is only lexically screened; Lodestone never evaluates it.", offset);
        }

        private void expressionError(String code, String message, int offset) {
            error(code, message, offset);
        }

        private void registryWarning(int offset) {
            warningOnce("REGISTRY_NOT_VALIDATED",
                    "Block, tag, biome, state, and property existence require validation by the target server.", offset);
        }

        private void addKindFeature(String kind, String feature) {
            recognizedKinds.add(kind);
            features.add(feature);
        }

        private void error(String code, String message, int offset) {
            addDiagnostic(errors, code, message, offset);
        }

        private void warningOnce(String code, String message, int offset) {
            if (warningCodes.add(code)) addDiagnostic(warnings, code, message, offset);
        }

        private void addDiagnostic(List<Diagnostic> destination, String code, String message, int offset) {
            if (destination.size() >= MAX_DIAGNOSTICS) {
                diagnosticsTruncated = true;
                return;
            }
            var boundedMessage = message.length() <= MAX_DIAGNOSTIC_MESSAGE
                    ? message : message.substring(0, MAX_DIAGNOSTIC_MESSAGE);
            destination.add(new Diagnostic(code, boundedMessage, Math.max(0, Math.min(raw.length(), offset))));
        }

        private void checkCancellation(int index) {
            if ((index & 63) == 0) context.cancellation().throwIfCancelled();
        }

        private static List<Map<String, Object>> diagnosticMaps(List<Diagnostic> diagnostics) {
            var result = new ArrayList<Map<String, Object>>(diagnostics.size());
            for (var diagnostic : diagnostics) {
                result.add(Map.of("code", diagnostic.code(), "message", diagnostic.message(),
                        "offset", diagnostic.offset()));
            }
            return List.copyOf(result);
        }

        private static String normalize(List<Term> terms) {
            var normalized = new StringBuilder();
            for (var term : terms) {
                if (!normalized.isEmpty()) normalized.append(' ');
                normalized.append(term.text());
            }
            return normalized.toString();
        }

        private static boolean isUnaryPrefix(char current) {
            return current == '!' || current == '>' || current == '<' || current == '~';
        }

        private static boolean isResourceId(String identifier) {
            if (identifier.isEmpty()) return false;
            var colon = identifier.indexOf(':');
            if (colon != identifier.lastIndexOf(':')) return false;
            if (colon < 0) return validPath(identifier, 0, identifier.length());
            return colon > 0 && colon < identifier.length() - 1
                    && validNamespace(identifier, 0, colon)
                    && validPath(identifier, colon + 1, identifier.length());
        }

        private static boolean validNamespace(String value, int start, int end) {
            for (var index = start; index < end; index++) {
                var current = value.charAt(index);
                if (!((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')
                        || current == '_' || current == '.' || current == '-')) return false;
            }
            return start < end;
        }

        private static boolean validPath(String value, int start, int end) {
            if (start >= end || value.charAt(start) == '/' || value.charAt(end - 1) == '/') return false;
            for (var index = start; index < end; index++) {
                var current = value.charAt(index);
                if (!((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')
                        || current == '_' || current == '.' || current == '-' || current == '/')) return false;
                if (current == '/' && index > start && value.charAt(index - 1) == '/') return false;
            }
            return true;
        }

        private static boolean isStateKey(String value) {
            if (value.isEmpty()) return false;
            for (var index = 0; index < value.length(); index++) {
                var current = value.charAt(index);
                if (!((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')
                        || current == '_' || current == '-')) return false;
            }
            return true;
        }

        private static boolean isStateValue(String value) {
            if (value.isEmpty()) return false;
            for (var index = 0; index < value.length(); index++) {
                var current = value.charAt(index);
                if (!((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')
                        || current == '_' || current == '.' || current == '-')) return false;
            }
            return true;
        }

        private static boolean isIdentifierStart(char current) {
            return (current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z') || current == '_';
        }

        private static boolean isIdentifierPart(char current) {
            return isIdentifierStart(current) || (current >= '0' && current <= '9');
        }

        private static boolean isNumberStart(String expression, int index) {
            var current = expression.charAt(index);
            return current >= '0' && current <= '9'
                    || current == '.' && index + 1 < expression.length()
                    && expression.charAt(index + 1) >= '0' && expression.charAt(index + 1) <= '9';
        }

        private static int scanNumber(String expression, int index) {
            var cursor = index;
            var dot = false;
            while (cursor < expression.length()) {
                var current = expression.charAt(cursor);
                if (current >= '0' && current <= '9') {
                    cursor++;
                } else if (current == '.' && !dot) {
                    dot = true;
                    cursor++;
                } else {
                    break;
                }
            }
            return cursor;
        }

        private static int operatorLength(String expression, int index) {
            if (startsWith(expression, index, "&&") || startsWith(expression, index, "||")
                    || startsWith(expression, index, "==") || startsWith(expression, index, "!=")
                    || startsWith(expression, index, "<=") || startsWith(expression, index, ">=")) return 2;
            return "+-*/%^<>&|!~".indexOf(expression.charAt(index)) >= 0 ? 1 : 0;
        }

        private static boolean startsWith(String value, int offset, String candidate) {
            return offset + candidate.length() <= value.length() && value.startsWith(candidate, offset);
        }
    }

    private record Term(String text, int offset) {
    }

    private record Diagnostic(String code, String message, int offset) {
    }
}
