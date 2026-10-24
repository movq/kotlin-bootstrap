package kotlinbootstrap.syntax;

import static kotlinbootstrap.syntax.TokenKind.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import kotlinbootstrap.ast.Annotation;
import kotlinbootstrap.ast.Decl;
import kotlinbootstrap.ast.Decl.ValOrVar;
import kotlinbootstrap.ast.Expr;
import kotlinbootstrap.ast.KtFile;
import kotlinbootstrap.ast.Modifier;
import kotlinbootstrap.ast.Modifiers;
import kotlinbootstrap.ast.NamedType;
import kotlinbootstrap.ast.Stmt;
import kotlinbootstrap.ast.TypeRef;
import kotlinbootstrap.ast.ValueArg;

/**
 * Recursive-descent parser for Kotlin, modelled closely on the reference
 * parser (compiler/psi/parser/.../KotlinParsing.java and
 * KotlinExpressionParsing.java). Method names and structure follow the
 * reference so that the two can be compared side by side; comments of the
 * form "reference: ..." point out deliberate differences.
 *
 * <p>Unlike the reference, this parser does no error recovery: it throws a
 * {@link SyntaxError} at the first error. Where the reference speculatively
 * parses and rolls back, we save and restore the token position; places where
 * the reference rolls back after inner errors catch the SyntaxError instead.
 *
 * <p>Newline sensitivity works as in the reference: a stack records whether
 * newlines are currently significant (they are not inside parentheses, for
 * example), and {@link #newlineBefore()} consults it. Likewise the reference
 * joins adjacent '?' '.' into '?.', '?' ':' into '?:' and '!' '!' into '!!'
 * except where joining is disabled, and {@link #tt()} does the same.
 */
public final class Parser {
    private static final Set<String> HARD_KEYWORD_TEXTS;
    private static final Set<String> ANNOTATION_TARGETS = Set.of(
            "all", "file", "field", "get", "set", "property", "receiver", "param", "setparam", "delegate");

    static {
        java.util.HashSet<String> texts = new java.util.HashSet<>();
        for (TokenKind kind : TokenKind.values()) {
            if (kind.isHardKeyword()) texts.add(kind.text());
        }
        HARD_KEYWORD_TEXTS = Set.copyOf(texts);
    }

    private static final Set<TokenKind> EXPRESSION_FIRST = EnumSet.of(
            MINUS, PLUS, MINUSMINUS, PLUSPLUS, EXCL, EXCLEXCL, COLONCOLON, LPAR,
            TRUE_KEYWORD, FALSE_KEYWORD, INTERPOLATION_PREFIX, OPEN_QUOTE,
            INTEGER_LITERAL, CHARACTER_LITERAL, FLOAT_LITERAL, NULL_KEYWORD,
            LBRACE, FUN_KEYWORD, THIS_KEYWORD, SUPER_KEYWORD, IF_KEYWORD, WHEN_KEYWORD, TRY_KEYWORD,
            OBJECT_KEYWORD, THROW_KEYWORD, RETURN_KEYWORD, CONTINUE_KEYWORD, BREAK_KEYWORD,
            FOR_KEYWORD, WHILE_KEYWORD, DO_KEYWORD, IDENTIFIER, AT, LBRACKET);

    /** EXPRESSION_FIRST plus declaration starts and (hard) modifier keywords; soft ones are IDENTIFIER. */
    private static final Set<TokenKind> STATEMENT_FIRST;

    static {
        EnumSet<TokenKind> set = EnumSet.copyOf(EXPRESSION_FIRST);
        set.addAll(EnumSet.of(FUN_KEYWORD, VAL_KEYWORD, VAR_KEYWORD, INTERFACE_KEYWORD, CLASS_KEYWORD,
                TYPEALIAS_KEYWORD, IN_KEYWORD));
        STATEMENT_FIRST = set;
    }

    /** Tokens after which newlines do not end an expression. */
    private static final Set<TokenKind> ALLOW_NEWLINE_OPERATIONS = EnumSet.of(
            DOT, SAFE_ACCESS, COLON, AS_KEYWORD, AS_SAFE, ELVIS, ANDAND, OROR);

    private static final Set<TokenKind> TOP_LEVEL_DECLARATION_FIRST = EnumSet.of(
            TYPEALIAS_KEYWORD, INTERFACE_KEYWORD, CLASS_KEYWORD, OBJECT_KEYWORD,
            FUN_KEYWORD, VAL_KEYWORD, VAR_KEYWORD, PACKAGE_KEYWORD);

    private static final Set<TokenKind> NO_MODIFIER_BEFORE_FOR_VALUE_PARAMETER = EnumSet.of(COMMA, COLON, EQ, RPAR);
    private static final Set<TokenKind> FUNCTION_NAME_FOLLOW_SET = EnumSet.of(LT, LPAR, RPAR, COLON, EQ);
    private static final Set<TokenKind> WHEN_CONDITION_RECOVERY_SET = EnumSet.of(
            RBRACE, IN_KEYWORD, NOT_IN, IS_KEYWORD, NOT_IS, ELSE_KEYWORD);

    /**
     * Binary operator precedence, from tightest to loosest binding. As in the
     * reference, IDENTIFIER stands for infix function calls.
     */
    private enum Precedence {
        AS, MULTIPLICATIVE, ADDITIVE, RANGE, INFIX, ELVIS, IN_OR_IS, COMPARISON, EQUALITY,
        CONJUNCTION, DISJUNCTION, ASSIGNMENT;

        Precedence higherPriority() {
            return this == AS ? null : values()[ordinal() - 1];
        }

        static Precedence of(TokenKind kind) {
            return switch (kind) {
                case AS_KEYWORD, AS_SAFE -> AS;
                case MUL, DIV, PERC -> MULTIPLICATIVE;
                case PLUS, MINUS -> ADDITIVE;
                case RANGE, RANGE_UNTIL -> Precedence.RANGE;
                case IDENTIFIER -> INFIX;
                case ELVIS -> Precedence.ELVIS;
                case IN_KEYWORD, NOT_IN, IS_KEYWORD, NOT_IS -> IN_OR_IS;
                case LT, GT, LTEQ, GTEQ -> COMPARISON;
                case EQEQ, EXCLEQ, EQEQEQ, EXCLEQEQEQ -> EQUALITY;
                case ANDAND -> CONJUNCTION;
                case OROR -> DISJUNCTION;
                case EQ, PLUSEQ, MINUSEQ, MULTEQ, DIVEQ, PERCEQ -> ASSIGNMENT;
                default -> null;
            };
        }
    }

    private enum DeclarationMode {
        MEMBER_OR_TOPLEVEL(false, true, true),
        LOCAL(true, false, false);

        final boolean destructuringAllowed;
        final boolean accessorsAllowed;
        final boolean canBeEnumUsedAsSoftKeyword;

        DeclarationMode(boolean destructuringAllowed, boolean accessorsAllowed, boolean canBeEnumUsedAsSoftKeyword) {
            this.destructuringAllowed = destructuringAllowed;
            this.accessorsAllowed = accessorsAllowed;
            this.canBeEnumUsedAsSoftKeyword = canBeEnumUsedAsSoftKeyword;
        }
    }

    private enum NameMode { REQUIRED, ALLOWED, PROHIBITED }

    private enum MultiDeclarationMode { SHORT, FULL, FULL_VAL_ONLY }

    // reference: the file annotation modes are handled separately, in parsePreamble.
    private enum AnnotationMode {
        DEFAULT(true, true, false, false),
        TYPE_CONTEXT(true, false, true, false),
        WITH_SIGNIFICANT_WHITESPACE_BEFORE_ARGUMENTS(true, true, true, true),
        WITH_SIGNIFICANT_WHITESPACE_BEFORE_ARGUMENTS_NO_CONTEXT(true, false, true, true),
        NO_ANNOTATIONS_NO_CONTEXT(false, false, false, false);

        final boolean allowAnnotations;
        final boolean allowContextList;
        final boolean typeContext;
        final boolean withSignificantWhitespaceBeforeArguments;

        AnnotationMode(boolean allowAnnotations, boolean allowContextList, boolean typeContext,
                       boolean withSignificantWhitespaceBeforeArguments) {
            this.allowAnnotations = allowAnnotations;
            this.allowContextList = allowContextList;
            this.typeContext = typeContext;
            this.withSignificantWhitespaceBeforeArguments = withSignificantWhitespaceBeforeArguments;
        }
    }

    private record Mark(int p, int newlineDepth, int joinDepth) {
    }

    private final SourceFile file;
    private final Token[] tokens;
    /** Index of the current token. */
    private int p;
    /** Tokens from this index on read as EOF; lowered while parsing receiver types. */
    private int limit;
    private boolean[] newlineStack = new boolean[32];
    private int newlineDepth;
    private boolean[] joinStack = new boolean[32];
    private int joinDepth;
    /**
     * While parsing the expression after {@code by} in a supertype list, the
     * newline stack depth at its start; otherwise -1. Trailing lambdas are not
     * parsed at that depth, so that the class body is not taken for one.
     */
    private int byClauseDepth = -1;

    private Parser(SourceFile file) {
        this.file = file;
        this.tokens = Lexer.tokenize(file).toArray(new Token[0]);
        this.limit = tokens.length - 1;
        pushNewlines(true);
        pushJoin(true);
    }

    public static KtFile parse(SourceFile file) {
        return new Parser(file).parseFile();
    }

    // ==== Token stream ====

    private Token token() {
        return tokens[Math.min(p, tokens.length - 1)];
    }

    private int pos() {
        return token().start();
    }

    private String text() {
        return token().text();
    }

    private TokenKind kindAt(int i) {
        return i >= limit ? EOF : tokens[i].kind();
    }

    private TokenKind joinedKindAt(int i) {
        TokenKind kind = kindAt(i);
        if ((kind == QUEST || kind == EXCL) && i + 1 < limit && !tokens[i + 1].spaceBefore()) {
            TokenKind next = tokens[i + 1].kind();
            if (kind == QUEST && next == DOT) return SAFE_ACCESS;
            if (kind == QUEST && next == COLON) return ELVIS;
            if (kind == EXCL && next == EXCL) return EXCLEXCL;
        }
        return kind;
    }

    private boolean joining() {
        return joinStack[joinDepth - 1];
    }

    private int widthAt(int i) {
        return joining() && joinedKindAt(i) != kindAt(i) ? 2 : 1;
    }

    /** The current token kind, with complex tokens joined if enabled. */
    private TokenKind tt() {
        return joining() ? joinedKindAt(p) : kindAt(p);
    }

    private TokenKind lookahead(int k) {
        int i = p;
        for (int j = 0; j < k && i < limit; j++) i += widthAt(i);
        return joining() ? joinedKindAt(i) : kindAt(i);
    }

    /** The next raw token, if it directly follows the current one with no whitespace or comment between. */
    private TokenKind adjacentNextKind() {
        return p + 1 < limit && !tokens[p + 1].spaceBefore() ? tokens[p + 1].kind() : null;
    }

    private TokenKind lastTokenKind() {
        return p > 0 ? tokens[p - 1].kind() : null;
    }

    private boolean eof() {
        return p >= limit;
    }

    private void advance() {
        if (!eof()) p += widthAt(p);
    }

    private boolean at(TokenKind kind) {
        return tt() == kind;
    }

    private boolean atIdent() {
        return tt() == IDENTIFIER;
    }

    /** At a soft keyword. Backticked identifiers never match since their text includes the backticks. */
    private boolean atSoft(String keyword) {
        return tt() == IDENTIFIER && text().equals(keyword);
    }

    private boolean atEolOrSemicolon() {
        return eof() || at(SEMICOLON) || newlineBefore();
    }

    private boolean newlineBefore() {
        if (!newlineStack[newlineDepth - 1]) return false;
        if (eof()) return true;
        return tokens[p].newlineBefore();
    }

    private boolean interruptedWithNewLine() {
        return !ALLOW_NEWLINE_OPERATIONS.contains(tt()) && newlineBefore();
    }

    private void pushNewlines(boolean enabled) {
        if (newlineDepth == newlineStack.length) newlineStack = java.util.Arrays.copyOf(newlineStack, newlineDepth * 2);
        newlineStack[newlineDepth++] = enabled;
    }

    private void popNewlines() {
        newlineDepth--;
    }

    private void pushJoin(boolean enabled) {
        if (joinDepth == joinStack.length) joinStack = java.util.Arrays.copyOf(joinStack, joinDepth * 2);
        joinStack[joinDepth++] = enabled;
    }

    private void popJoin() {
        joinDepth--;
    }

    private Mark mark() {
        return new Mark(p, newlineDepth, joinDepth);
    }

    private void reset(Mark m) {
        p = m.p();
        newlineDepth = m.newlineDepth();
        joinDepth = m.joinDepth();
    }

    /** Runs {@code parse}; if it fails, restores the position and returns null. */
    private <T> T attempt(Supplier<T> parse) {
        Mark m = mark();
        try {
            return parse.get();
        } catch (SyntaxError e) {
            reset(m);
            return null;
        }
    }

    private SyntaxError error(String message) {
        String found = eof() ? "end of file" : "'" + text() + "'";
        return new SyntaxError(file, pos(), message + ", found " + found);
    }

    private void expect(TokenKind kind, String message) {
        if (!at(kind)) throw error(message);
        advance();
    }

    private boolean consumeIf(TokenKind kind) {
        if (at(kind)) {
            advance();
            return true;
        }
        return false;
    }

    private String expectIdent(String message) {
        if (!atIdent()) throw error(message);
        String name = token().value();
        advance();
        return name;
    }

    // ==== File structure ====

    private KtFile parseFile() {
        List<Annotation> fileAnnotations = parsePreambleFileAnnotations();
        List<String> packageName = List.of();
        if (at(PACKAGE_KEYWORD)) {
            advance();
            packageName = parsePackageName();
            consumeIf(SEMICOLON);
        }

        List<KtFile.Import> imports = new ArrayList<>();
        while (atSoft("import")) {
            imports.add(parseImportDirective());
        }

        List<Decl> declarations = new ArrayList<>();
        while (!eof()) {
            Decl decl = parseTopLevelDeclaration();
            if (decl != null) declarations.add(decl);
        }
        return new KtFile(file, fileAnnotations, packageName, List.copyOf(imports), List.copyOf(declarations));
    }

    /*
     * reference: parsePreamble parses all leading annotations as file
     * annotations when a package directive follows (erroring on those without
     * "file:"), and otherwise only those with "file:". For valid code both
     * amount to taking the leading annotations that have a "file:" target.
     */
    private List<Annotation> parsePreambleFileAnnotations() {
        List<Annotation> annotations = new ArrayList<>();
        while (at(AT) && adjacentNextKind() == IDENTIFIER && tokens[p + 1].text().equals("file")
                && lookahead(2) == COLON) {
            parseAnnotationOrList(AnnotationMode.DEFAULT, annotations);
        }
        return List.copyOf(annotations);
    }

    private List<String> parsePackageName() {
        List<String> name = new ArrayList<>();
        while (true) {
            if (newlineBefore()) throw error("Package name must be a '.'-separated identifier list placed on a single line");
            name.add(expectIdent("Package name must be a '.'-separated identifier list"));
            if (!consumeIf(DOT)) break;
        }
        return List.copyOf(name);
    }

    private KtFile.Import parseImportDirective() {
        int start = pos();
        advance(); // import
        if (newlineBefore()) throw error("Expecting qualified name");

        List<String> path = new ArrayList<>();
        path.add(expectIdent("Expecting qualified name"));
        while (at(DOT) && lookahead(1) != MUL) {
            advance(); // DOT
            if (newlineBefore()) throw error("Import must be placed on a single line");
            path.add(expectIdent("Qualified name must be a '.'-separated identifier list"));
        }

        boolean allUnder = false;
        if (at(DOT)) {
            advance(); // DOT
            advance(); // MUL
            allUnder = true;
            if (at(AS_KEYWORD)) throw error("Cannot rename all imported items to one identifier");
        }
        String alias = null;
        if (at(AS_KEYWORD)) {
            advance(); // AS_KEYWORD
            if (newlineBefore()) throw error("Expecting identifier");
            alias = expectIdent("Expecting identifier");
        }
        consumeIf(SEMICOLON);
        return new KtFile.Import(start, List.copyOf(path), allUnder, alias);
    }

    private Decl parseTopLevelDeclaration() {
        if (at(SEMICOLON)) {
            advance();
            return null;
        }
        int start = pos();
        Modifiers modifiers = parseModifierList(EnumSet.noneOf(TokenKind.class), false);
        Decl decl = parseCommonDeclaration(start, modifiers, NameMode.REQUIRED, DeclarationMode.MEMBER_OR_TOPLEVEL);
        if (decl == null) {
            if (atSoft("import")) throw error("Imports are only allowed in the beginning of file");
            throw error("Expecting a top level declaration");
        }
        return decl;
    }

    private Decl parseCommonDeclaration(int start, Modifiers modifiers, NameMode nameModeForObject,
                                        DeclarationMode mode) {
        switch (tt()) {
            case CLASS_KEYWORD, INTERFACE_KEYWORD -> {
                return parseClassOrObject(start, modifiers, false, NameMode.REQUIRED, true, modifiers.has(Modifier.ENUM));
            }
            case FUN_KEYWORD -> {
                return parseFunction(start, modifiers, false);
            }
            case VAL_KEYWORD, VAR_KEYWORD -> {
                return parseProperty(start, modifiers, mode);
            }
            case LPAR, LBRACKET -> {
                TokenKind la = lookahead(1);
                return la == VAL_KEYWORD || la == VAR_KEYWORD ? parseProperty(start, modifiers, mode) : null;
            }
            case TYPEALIAS_KEYWORD -> {
                return parseTypeAlias(start, modifiers);
            }
            case OBJECT_KEYWORD -> {
                return parseClassOrObject(start, modifiers, true, nameModeForObject, true, false);
            }
            case IDENTIFIER -> {
                if (modifiers.has(Modifier.ENUM) && mode.canBeEnumUsedAsSoftKeyword) {
                    throw error("'class' keyword is expected after 'enum'");
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // ==== Modifiers and annotations ====

    private Modifiers parseModifierList(Set<TokenKind> noModifiersBefore, boolean localDeclaration) {
        return parseModifierList(null, AnnotationMode.DEFAULT, noModifiersBefore, localDeclaration);
    }

    /**
     * (modifier | annotation | contextList)*
     *
     * @param allowed            permitted modifier keywords, or null for all
     * @param noModifiersBefore  if a modifier keyword is followed by one of these,
     *                           it is a name rather than a modifier
     * @param localDeclaration   whether parsing a possible local declaration, in which
     *                           case a dubious context list ends the modifiers
     */
    private Modifiers parseModifierList(Set<Modifier> allowed, AnnotationMode mode, Set<TokenKind> noModifiersBefore,
                                        boolean localDeclaration) {
        List<Annotation> annotations = new ArrayList<>();
        EnumSet<Modifier> keywords = EnumSet.noneOf(Modifier.class);
        List<NamedType> context = new ArrayList<>();
        parseModifierListBody(allowed, mode, noModifiersBefore, localDeclaration, annotations, keywords, context);
        if (annotations.isEmpty() && keywords.isEmpty() && context.isEmpty()) return Modifiers.EMPTY;
        return new Modifiers(List.copyOf(annotations), Collections.unmodifiableSet(keywords), List.copyOf(context));
    }

    private void parseModifierListBody(Set<Modifier> allowed, AnnotationMode mode, Set<TokenKind> noModifiersBefore,
                                       boolean localDeclaration, List<Annotation> annotations,
                                       Set<Modifier> keywords, List<NamedType> context) {
        while (!eof()) {
            if (at(AT) && mode.allowAnnotations) {
                Mark before = mark();
                int count = annotations.size();
                if (!parseAnnotationOrList(mode, annotations) && !mode.withSignificantWhitespaceBeforeArguments) {
                    // Try again, without taking a parenthesized list after whitespace as arguments.
                    reset(before);
                    annotations.subList(count, annotations.size()).clear();
                    AnnotationMode newMode = mode.allowContextList
                            ? AnnotationMode.WITH_SIGNIFICANT_WHITESPACE_BEFORE_ARGUMENTS
                            : AnnotationMode.WITH_SIGNIFICANT_WHITESPACE_BEFORE_ARGUMENTS_NO_CONTEXT;
                    parseModifierListBody(allowed, newMode, noModifiersBefore, localDeclaration, annotations,
                            keywords, context);
                    break;
                }
            } else if (atSoft("context") && mode.allowContextList && lookahead(1) == LPAR) {
                if (localDeclaration) {
                    // A call of a function named "context" is more likely than a context
                    // receiver list on a local declaration.
                    Mark before = mark();
                    ContextList list = attempt(() -> parseContextParameterOrReceiverList(false));
                    if (list == null || !list.noError()) {
                        reset(before);
                        break;
                    }
                    context.addAll(list.params());
                } else {
                    context.addAll(parseContextParameterOrReceiverList(false).params());
                }
            } else if (!tryParseModifier(allowed, noModifiersBefore, keywords)) {
                break;
            }
        }
    }

    /** The modifier the current token spells, if any. */
    private Modifier currentModifier() {
        return switch (tt()) {
            case FUN_KEYWORD -> Modifier.FUN;
            case IN_KEYWORD -> Modifier.IN;
            case IDENTIFIER -> Modifier.fromKeyword(text());
            default -> null;
        };
    }

    private boolean tryParseModifier(Set<Modifier> allowed, Set<TokenKind> noModifiersBefore, Set<Modifier> keywords) {
        Modifier modifier = currentModifier();
        if (modifier == null || (allowed != null && !allowed.contains(modifier))) return false;
        TokenKind la = lookahead(1);
        if (modifier == Modifier.FUN && la != INTERFACE_KEYWORD) return false;
        if (la != EOF && !noModifiersBefore.contains(la)) {
            keywords.add(modifier);
            advance();
            return true;
        }
        return false;
    }

    private record ContextList(List<NamedType> params, boolean noError) {
    }

    /**
     * contextParameterList : "context" "(" (parameter | label? type){","} ")"
     *
     * <p>{@code noError} is false if it is the deprecated context receiver form
     * (types rather than parameters), outside function types.
     */
    private ContextList parseContextParameterOrReceiverList(boolean inFunctionType) {
        advance(); // context
        if (lookahead(1) == RPAR) throw error("Empty context parameter list");

        List<NamedType> params = new ArrayList<>();
        boolean noError = true;
        pushNewlines(false);
        advance(); // LPAR
        while (!at(RPAR)) {
            Decl.Param param = tryParseValueParameter(true);
            if (param != null) {
                params.add(new NamedType(param.pos(), param.name(), param.type()));
            } else {
                int start = pos();
                if (!inFunctionType && isAtLabelDefinition()) parseLabelDefinition();
                params.add(new NamedType(start, null, parseTypeRef()));
                noError &= inFunctionType;
            }
            if (!consumeIf(COMMA)) break;
        }
        expect(RPAR, "Expecting ')'");
        popNewlines();
        return new ContextList(List.copyOf(params), noError);
    }

    private List<Annotation> parseAnnotations(AnnotationMode mode) {
        List<Annotation> annotations = new ArrayList<>();
        while (parseAnnotationOrList(mode, annotations)) {
            // keep going
        }
        return annotations.isEmpty() ? List.of() : List.copyOf(annotations);
    }

    /**
     * annotation | annotationList
     *
     * @return false if not at '@'; also false in the type-context case where the
     *         reference parser asks its caller to roll back (see parseAnnotation)
     */
    private boolean parseAnnotationOrList(AnnotationMode mode, List<Annotation> out) {
        if (!at(AT)) return false;
        TokenKind next = adjacentNextKind();
        TokenKind tokenToMatch = next;
        if (next == IDENTIFIER && lookahead(2) == COLON) {
            tokenToMatch = lookahead(3);
        } else if (lookahead(1) == COLON) {
            throw error("Expected annotation target before ':'");
        }

        if (tokenToMatch == IDENTIFIER) {
            return parseAnnotation(mode, out);
        }
        if (tokenToMatch == LBRACKET) {
            parseAnnotationList(out);
            return true;
        }
        throw error("Expected annotation identifier after '@'");
    }

    /** "@" (target ":")? "[" unescapedAnnotation+ "]" */
    private void parseAnnotationList(List<Annotation> out) {
        pushNewlines(false);
        advance(); // AT
        String target = parseAnnotationTarget();
        expect(LBRACKET, "Expecting '['");
        if (!atIdent()) throw error("Expecting a list of annotations");
        while (atIdent()) {
            List<Annotation> one = new ArrayList<>();
            parseAnnotation(AnnotationMode.DEFAULT, one);
            Annotation a = one.getFirst();
            out.add(new Annotation(a.pos(), target, a.type(), a.args()));
        }
        expect(RBRACKET, "Expecting ']' to close the annotation list");
        popNewlines();
    }

    private String parseAnnotationTarget() {
        if (at(COLON)) throw error("Expected annotation target before ':'");
        String target = atIdent() && ANNOTATION_TARGETS.contains(text()) ? text() : null;
        if (target == null) {
            if (atIdent() && lookahead(1) == COLON) throw error("Expected annotation target before ':'");
            return null;
        }
        advance(); // target
        expect(COLON, "Expecting \"" + target + ":\" prefix for " + target + " annotations");
        return target;
    }

    /**
     * annotation : "@" (target ":")? unescapedAnnotation
     * unescapedAnnotation : SimpleName{"."} typeArguments? valueArguments?
     *
     * <p>In a type context, {@code @Ann (A) -> B} is ambiguous: the reference
     * parses "(A)" as arguments and then, if "->" follows, returns false so the
     * caller retries treating parentheses after whitespace as not being arguments.
     */
    private boolean parseAnnotation(AnnotationMode mode, List<Annotation> out) {
        int start = pos();
        String target = null;
        if (at(AT)) {
            advance(); // AT
            target = parseAnnotationTarget();
        }
        TypeRef.Simple type = parseUserType();

        List<ValueArg> args = List.of();
        boolean ok = true;
        if (at(LPAR) && lookahead(1) != VAL_KEYWORD && lookahead(1) != VAR_KEYWORD
                && !(token().spaceBefore() && mode.withSignificantWhitespaceBeforeArguments)) {
            if (mode.typeContext) {
                List<ValueArg> parsed = attempt(this::parseValueArgumentList);
                if (parsed == null) {
                    if (mode.withSignificantWhitespaceBeforeArguments) throw error("Expecting annotation arguments");
                    return false;
                }
                args = parsed;
                ok = !at(ARROW);
            } else {
                args = parseValueArgumentList();
            }
        }
        out.add(new Annotation(start, target, type, args));
        return ok;
    }

    // ==== Classes ====

    /*
     * class
     *   : modifiers ("class" | "interface") SimpleName typeParameters? primaryConstructor?
     *       (":" delegationSpecifier{","})? typeConstraints (classBody? | enumClassBody)
     * object
     *   : "object" SimpleName? primaryConstructor? ":" delegationSpecifier{","}? classBody?
     */
    private Decl.Class parseClassOrObject(int start, Modifiers modifiers, boolean object, NameMode nameMode,
                                          boolean optionalBody, boolean enumClass) {
        Decl.ClassKind kind = object ? Decl.ClassKind.OBJECT
                : at(INTERFACE_KEYWORD) ? Decl.ClassKind.INTERFACE : Decl.ClassKind.CLASS;
        advance(); // CLASS_KEYWORD, INTERFACE_KEYWORD or OBJECT_KEYWORD

        String name = null;
        if (nameMode == NameMode.REQUIRED) {
            name = expectIdent("Name expected");
        } else if (atIdent()) {
            if (nameMode == NameMode.PROHIBITED) throw error("An object expression cannot bind a name");
            name = expectIdent("Name expected");
        }

        List<Decl.TypeParam> typeParams = parseTypeParameterList();

        Mark beforeConstructorModifiers = mark();
        int constructorStart = pos();
        Modifiers constructorModifiers = parseModifierList(EnumSet.noneOf(TokenKind.class), false);
        boolean hasConstructorModifiers = !constructorModifiers.isEmpty();

        List<Decl.SuperTypeEntry> superTypes = List.of();
        List<Decl.TypeConstraint> constraints = List.of();
        List<Decl.EnumEntry> enumEntries = List.of();
        List<Decl> members = List.of();
        Decl.PrimaryConstructor primaryConstructor = null;

        // Some modifiers found, but no parentheses following: the class has already ended.
        if (hasConstructorModifiers && !(at(LPAR) || at(LBRACE) || at(COLON) || atSoft("constructor"))) {
            reset(beforeConstructorModifiers);
            return new Decl.Class(start, modifiers, kind, name, typeParams, null, superTypes, constraints,
                    enumEntries, members);
        }

        boolean hasConstructorKeyword = atSoft("constructor");
        if (hasConstructorKeyword) advance();

        if (at(LPAR)) {
            primaryConstructor = new Decl.PrimaryConstructor(constructorStart, constructorModifiers,
                    parseValueParameterList(true));
        } else if (hasConstructorModifiers || hasConstructorKeyword) {
            throw error(hasConstructorKeyword ? "Expecting primary constructor parameter list"
                    : "Expecting 'constructor' keyword");
        }

        if (at(COLON)) {
            advance(); // COLON
            superTypes = parseDelegationSpecifierList();
        }

        if (atSoft("where")) {
            if (object) throw error("Where clause is not allowed for objects");
            if (typeParams.isEmpty()) throw error("Type constraints are not allowed when no type parameters declared");
            constraints = parseTypeConstraintList();
        }

        if (at(LBRACE)) {
            if (enumClass) {
                List<Decl.EnumEntry> entries = new ArrayList<>();
                members = parseEnumClassBody(entries);
                enumEntries = List.copyOf(entries);
            } else {
                members = parseClassBody();
            }
        } else if (!optionalBody) {
            throw error("Expecting a class body");
        }

        return new Decl.Class(start, modifiers, kind, name, typeParams, primaryConstructor, superTypes, constraints,
                enumEntries, members);
    }

    /* enumClassBody : "{" enumEntries (";" members)? "}" */
    private List<Decl> parseEnumClassBody(List<Decl.EnumEntry> entries) {
        pushNewlines(true);
        advance(); // LBRACE
        if (!parseEnumEntries(entries) && !at(RBRACE)) {
            throw error("Expecting ';' after the last enum entry or '}' to close enum class body");
        }
        List<Decl> members = parseMembers();
        expect(RBRACE, "Expecting '}' to close enum class body");
        popNewlines();
        return members;
    }

    /** Returns true if member declarations may follow (the entries ended with ';'). */
    private boolean parseEnumEntries(List<Decl.EnumEntry> entries) {
        while (!eof() && !at(RBRACE)) {
            Mark before = mark();
            int start = pos();
            Modifiers modifiers = parseModifierList(EnumSet.of(COMMA, SEMICOLON, RBRACE), false);
            if (atSoft("init") || atSoft("constructor") || !atIdent()) {
                reset(before);
                // No (more) entries; members may still follow a semicolon.
                return consumeIf(SEMICOLON);
            }
            String name = expectIdent("Expecting an enum entry name");
            List<ValueArg> args = at(LPAR) ? parseValueArgumentList() : List.of();
            List<Decl> body = at(LBRACE) ? parseClassBody() : null;
            entries.add(new Decl.EnumEntry(start, modifiers, name, args, body));

            boolean commaFound = consumeIf(COMMA);
            if (consumeIf(SEMICOLON)) return true;
            if (!commaFound) return false;
        }
        return false;
    }

    private List<Decl> parseClassBody() {
        pushNewlines(true);
        expect(LBRACE, "Expecting a class body");
        List<Decl> members = parseMembers();
        expect(RBRACE, "Missing '}'");
        popNewlines();
        return members;
    }

    private List<Decl> parseMembers() {
        List<Decl> members = new ArrayList<>();
        while (!eof() && !at(RBRACE)) {
            Decl member = parseMemberDeclaration();
            if (member != null) members.add(member);
        }
        return List.copyOf(members);
    }

    private Decl parseMemberDeclaration() {
        if (at(SEMICOLON)) {
            advance();
            return null;
        }
        int start = pos();
        boolean isCompanionBlock = atSoft("companion") && lookahead(1) == LBRACE;

        Modifiers modifiers = parseModifierList(EnumSet.noneOf(TokenKind.class), false);
        if (isCompanionBlock) return new Decl.CompanionBlock(start, parseClassBody());
        Decl decl = parseCommonDeclaration(start, modifiers,
                modifiers.has(Modifier.COMPANION) ? NameMode.ALLOWED : NameMode.REQUIRED,
                DeclarationMode.MEMBER_OR_TOPLEVEL);
        if (decl != null) return decl;

        if (atSoft("init")) {
            advance(); // init
            if (!at(LBRACE)) throw error("Expecting '{' after 'init'");
            return new Decl.Initializer(start, modifiers, parseBlock());
        }
        if (atSoft("constructor")) {
            return parseSecondaryConstructor(start, modifiers);
        }
        throw error("Expecting member declaration");
    }

    /*
     * secondaryConstructor
     *   : modifiers "constructor" valueParameters (":" constructorDelegationCall)? block?
     */
    private Decl.SecondaryConstructor parseSecondaryConstructor(int start, Modifiers modifiers) {
        advance(); // constructor
        if (!at(LPAR)) throw error("Expecting '('");
        List<Decl.Param> params = parseValueParameterList(true);

        Decl.DelegationKind delegation = Decl.DelegationKind.NONE;
        List<ValueArg> delegationArgs = List.of();
        if (at(COLON)) {
            advance(); // COLON
            if (at(THIS_KEYWORD)) {
                delegation = Decl.DelegationKind.THIS;
            } else if (at(SUPER_KEYWORD)) {
                delegation = Decl.DelegationKind.SUPER;
            } else {
                throw error("Expecting a 'this' or 'super' constructor call");
            }
            advance(); // THIS_KEYWORD or SUPER_KEYWORD
            delegationArgs = parseValueArgumentList();
        }

        Expr.Block body = at(LBRACE) ? parseBlock() : null;
        return new Decl.SecondaryConstructor(start, modifiers, params, delegation, delegationArgs, body);
    }

    /* typeAlias : modifiers "typealias" SimpleName typeParameters? "=" type */
    private Decl.TypeAlias parseTypeAlias(int start, Modifiers modifiers) {
        advance(); // TYPEALIAS_KEYWORD
        String name = expectIdent("Type name expected");
        List<Decl.TypeParam> typeParams = parseTypeParameterList();
        if (atSoft("where")) throw error("Type alias parameters can't have bounds");
        expect(EQ, "Expecting '='");
        TypeRef type = parseTypeRef();
        consumeIf(SEMICOLON);
        return new Decl.TypeAlias(start, modifiers, name, typeParams, type);
    }

    /* delegationSpecifier{","} */
    private List<Decl.SuperTypeEntry> parseDelegationSpecifierList() {
        List<Decl.SuperTypeEntry> entries = new ArrayList<>();
        while (true) {
            entries.add(parseDelegationSpecifier());
            if (!consumeIf(COMMA)) break;
        }
        return List.copyOf(entries);
    }

    /*
     * delegationSpecifier
     *   : constructorInvocation
     *   : userType
     *   : userType "by" element
     */
    private Decl.SuperTypeEntry parseDelegationSpecifier() {
        int start = pos();
        TypeRef type = parseTypeRef();
        if (atSoft("by")) {
            advance(); // by
            int saved = byClauseDepth;
            byClauseDepth = newlineDepth;
            try {
                return new Decl.SuperTypeEntry(start, type, null, parseExpression());
            } finally {
                byClauseDepth = saved;
            }
        }
        if (at(LPAR)) {
            return new Decl.SuperTypeEntry(start, type, parseValueArgumentList(), null);
        }
        return new Decl.SuperTypeEntry(start, type, null, null);
    }

    // ==== Properties ====

    /*
     * property
     *   : modifiers ("val" | "var") typeParameters? (type ".")?
     *       ("(" variableDeclarationEntry{","} ")" | variableDeclarationEntry)
     *       typeConstraints ("by" | "=" expression SEMI?)? (getter? setter? | setter? getter?) SEMI?
     */
    private Decl parseProperty(int start, Modifiers modifiers, DeclarationMode mode) {
        boolean isShortForm = at(VAL_KEYWORD) || at(VAR_KEYWORD);
        boolean isVar = at(VAR_KEYWORD);
        if (isShortForm) advance();

        List<Decl.TypeParam> typeParams = at(LT) ? parseTypeParameterList() : List.of();

        pushJoin(false);
        TypeRef receiver = parseReceiverType("property");
        boolean multiDeclaration = at(LPAR) || at(LBRACKET);
        if (multiDeclaration && receiver != null) throw error("Receiver type is not allowed on a destructuring declaration");

        boolean isNameOnTheNextLine = eof() || newlineBefore();
        String name = null;
        boolean squareBrackets = at(LBRACKET);
        List<Decl.DestructuringEntry> entries = null;
        if (multiDeclaration) {
            entries = parseMultiDeclarationEntry(isShortForm ? MultiDeclarationMode.SHORT : MultiDeclarationMode.FULL);
            if (!mode.destructuringAllowed) throw error("Destructuring declarations are only allowed for local variables/values");
        } else {
            name = expectIdent("Expecting property name" + (receiver == null ? " or receiver type" : ""));
        }
        popJoin();

        TypeRef type = null;
        if (at(COLON)) {
            if (multiDeclaration) throw error("Type annotations are not allowed on destructuring declarations");
            advance(); // COLON
            type = parseTypeRef();
        }

        List<Decl.TypeConstraint> constraints = List.of();
        if (atSoft("where")) {
            if (typeParams.isEmpty()) throw error("Type constraints are not allowed when no type parameters declared");
            constraints = parseTypeConstraintList();
        }

        Expr initializer = null;
        Expr delegate = null;
        if (atSoft("by")) {
            advance(); // by
            delegate = parseExpression();
        } else if (at(EQ)) {
            advance(); // EQ
            initializer = parseExpression();
        } else if (isNameOnTheNextLine && type == null && receiver == null) {
            throw error("Expecting property name or receiver type");
        }

        if (multiDeclaration) {
            ValOrVar valOrVar = !isShortForm ? ValOrVar.NONE : isVar ? ValOrVar.VAR : ValOrVar.VAL;
            return new Decl.Destructuring(start, modifiers, valOrVar, squareBrackets, entries, initializer);
        }

        Decl.Accessor getter = null;
        Decl.Accessor setter = null;
        Decl.BackingField backingField = null;
        if (mode.accessorsAllowed) {
            // Only for non-local properties: in a block, "val a = 1; b" must not
            // be an infix call of b on "val ...".
            pushNewlines(true);
            boolean hasNewLineWithSemicolon = consumeIf(SEMICOLON) && newlineBefore();
            popNewlines();

            if (!hasNewLineWithSemicolon) {
                while (true) {
                    Mark before = mark();
                    int componentStart = pos();
                    Modifiers componentModifiers = parseModifierList(EnumSet.noneOf(TokenKind.class), false);
                    if (atSoft("get") && getter == null) {
                        getter = parseAccessor(componentStart, componentModifiers, true);
                    } else if (atSoft("set") && setter == null) {
                        setter = parseAccessor(componentStart, componentModifiers, false);
                    } else if (atSoft("field") && backingField == null) {
                        backingField = parseBackingField(componentStart, componentModifiers);
                    } else {
                        reset(before);
                        break;
                    }
                }
                if (!(atEolOrSemicolon() || at(RBRACE))) {
                    if (lastTokenKind() != SEMICOLON) throw error("Property getter or setter expected");
                } else {
                    consumeIf(SEMICOLON);
                }
            }
        }

        return new Decl.Property(start, modifiers, isVar, typeParams, receiver, name, type, constraints,
                initializer, delegate, getter, setter, backingField);
    }

    /*
     * getter : modifiers "get" ("(" ")" (":" type)? functionBody)?
     * setter : modifiers "set" ("(" modifiers SimpleName (":" type)? ")" functionBody)?
     */
    private Decl.Accessor parseAccessor(int start, Modifiers modifiers, boolean isGetter) {
        advance(); // get or set
        if (!at(LPAR)) {
            // An accessor without a body, e.g. "private set".
            if (!atAccessorFirstOrPropertyEnd()) throw error("Accessor body expected");
            return new Decl.Accessor(start, modifiers, isGetter, null, null, null);
        }

        pushNewlines(false);
        advance(); // LPAR
        Decl.Param param = null;
        if (!isGetter) {
            int paramStart = pos();
            Modifiers paramModifiers = parseModifierList(EnumSet.of(COMMA, COLON, RPAR), false);
            String name = expectIdent("Expecting parameter name");
            TypeRef type = null;
            if (consumeIf(COLON)) type = parseTypeRef();
            param = new Decl.Param(paramStart, paramModifiers, ValOrVar.NONE, name, type, null);
            consumeIf(COMMA);
        }
        expect(RPAR, "Expecting ')'");
        popNewlines();

        TypeRef returnType = null;
        if (consumeIf(COLON)) returnType = parseTypeRef();
        parseFunctionContract();
        Expr body = parseFunctionBody();
        return new Decl.Accessor(start, modifiers, isGetter, param, returnType, body);
    }

    private boolean atAccessorFirstOrPropertyEnd() {
        return currentModifier() != null || at(AT) || atSoft("get") || atSoft("set") || atSoft("field")
                || atEolOrSemicolon() || at(RBRACE);
    }

    /* backingField : modifiers "field" (":" type)? ("=" expression)? */
    private Decl.BackingField parseBackingField(int start, Modifiers modifiers) {
        advance(); // field
        TypeRef type = null;
        if (consumeIf(COLON)) type = parseTypeRef();
        Expr initializer = null;
        if (consumeIf(EQ)) {
            initializer = parseExpression();
            consumeIf(SEMICOLON);
        }
        return new Decl.BackingField(start, modifiers, type, initializer);
    }

    /*
     * Parses "(a, b: T)", "[a, b]" or "(val a, var b = c)" after val/var or in
     * lambda and loop parameters.
     */
    private List<Decl.DestructuringEntry> parseMultiDeclarationEntry(MultiDeclarationMode mode) {
        pushNewlines(false);
        boolean parentheses = at(LPAR);
        TokenKind closing = parentheses ? RPAR : RBRACKET;
        advance(); // LPAR or LBRACKET

        List<Decl.DestructuringEntry> entries = new ArrayList<>();
        while (true) {
            if (at(COMMA) || at(closing)) throw error("Expecting a name");
            int start = pos();
            Modifiers modifiers = Modifiers.EMPTY;
            ValOrVar valOrVar = ValOrVar.NONE;
            switch (mode) {
                case FULL -> {
                    if (!at(VAL_KEYWORD) && !at(VAR_KEYWORD)) throw error("Expecting val or var keyword");
                    valOrVar = at(VAR_KEYWORD) ? ValOrVar.VAR : ValOrVar.VAL;
                    advance();
                }
                case FULL_VAL_ONLY -> {
                    expect(VAL_KEYWORD, "Expecting val keyword");
                    valOrVar = ValOrVar.VAL;
                }
                case SHORT -> modifiers = parseModifierList(EnumSet.of(COMMA, RPAR, RBRACKET, COLON, EQ), false);
            }
            String name = expectIdent("Expecting a name");
            TypeRef type = null;
            if (consumeIf(COLON)) type = parseTypeRef();
            String sourceName = null;
            // Renaming is only allowed in name-based destructuring.
            if (at(EQ) && parentheses) {
                advance(); // EQ
                sourceName = expectIdent("Expecting an identifier");
            }
            entries.add(new Decl.DestructuringEntry(start, modifiers, valOrVar, name, type, sourceName));
            if (!consumeIf(COMMA)) break;
            if (at(closing)) break;
        }
        expect(closing, parentheses ? "Expecting ')'" : "Expecting ']'");
        popNewlines();
        return List.copyOf(entries);
    }

    // ==== Functions ====

    /*
     * function
     *   : modifiers "fun" typeParameters? (type ".")? SimpleName typeParameters?
     *       functionParameters (":" type)? typeConstraints functionBody?
     *
     * Returns null if failIfIdentifierExists and the function is named.
     */
    private Decl.Function parseFunction(int start, Modifiers modifiers, boolean failIfIdentifierExists) {
        advance(); // FUN_KEYWORD
        if (at(RBRACE)) throw error("Function body expected");

        List<Decl.TypeParam> typeParams = List.of();
        boolean typeParameterListOccurred = false;
        if (at(LT)) {
            typeParams = parseTypeParameterList();
            typeParameterListOccurred = true;
        }

        pushJoin(false);
        TypeRef receiver = parseReceiverType("function");
        if (atIdent() && failIfIdentifierExists) {
            popJoin();
            return null;
        }
        // An anonymous function has no name.
        String name = null;
        if (!FUNCTION_NAME_FOLLOW_SET.contains(tt())) {
            name = expectIdent("Expecting function name" + (receiver == null ? " or receiver type" : ""));
        }
        popJoin();

        if (at(LT)) {
            if (typeParameterListOccurred) throw error("Only one type parameter list is allowed for a function");
            typeParams = parseTypeParameterList();
            typeParameterListOccurred = true;
        }

        if (!at(LPAR)) throw error("Expecting '('");
        List<Decl.Param> params = parseValueParameterList(false);

        TypeRef returnType = null;
        if (consumeIf(COLON)) returnType = parseTypeRef();

        List<Expr> contract = parseFunctionContract();

        List<Decl.TypeConstraint> constraints = List.of();
        if (atSoft("where")) {
            if (!typeParameterListOccurred) throw error("Type constraints are not allowed when no type parameters declared");
            constraints = parseTypeConstraintList();
        }
        if (contract.isEmpty()) contract = parseFunctionContract();

        Expr body = null;
        if (at(SEMICOLON)) {
            advance();
        } else if (at(EQ) || at(LBRACE)) {
            body = parseFunctionBody();
        }
        return new Decl.Function(start, modifiers, typeParams, receiver, name, params, returnType, constraints,
                contract, body);
    }

    /* functionBody : block | "=" element */
    private Expr parseFunctionBody() {
        if (at(LBRACE)) return parseBlock();
        if (at(EQ)) {
            advance(); // EQ
            Expr body = parseExpression();
            consumeIf(SEMICOLON);
            return body;
        }
        throw error("Expecting function body");
    }

    /* contract : "contract" "[" expression{","} "]" */
    private List<Expr> parseFunctionContract() {
        if (!atSoft("contract")) return List.of();
        advance(); // contract
        expect(LBRACKET, "Expecting '['");
        pushNewlines(true);
        List<Expr> effects = new ArrayList<>();
        while (!at(RBRACKET)) {
            effects.add(parseExpression());
            if (!consumeIf(COMMA)) break;
        }
        expect(RBRACKET, "Expecting ']'");
        popNewlines();
        return List.copyOf(effects);
    }

    /*
     * (type ".")?
     *
     * Whether there is a receiver type is decided by scanning ahead for the
     * last top-level dot before the name, and the type is then parsed as if
     * the input ended at that dot.
     */
    private TypeRef parseReceiverType(String title) {
        Mark beforeAnnotations = mark();
        List<Annotation> annotations = parseAnnotations(AnnotationMode.DEFAULT);
        int lastDot = lastDotAfterReceiver();
        if (!annotations.isEmpty()) {
            if (lastDot == -1) throw error("Annotations are not allowed in this position");
            reset(beforeAnnotations);
        }
        if (lastDot == -1) return null;

        int savedLimit = limit;
        limit = Math.min(limit, lastDot);
        TypeRef receiver;
        try {
            receiver = parseTypeRefWithoutIntersections();
        } finally {
            limit = savedLimit;
        }
        if (!at(DOT) && !at(SAFE_ACCESS)) throw error("Expecting '.' before a " + title + " name");
        advance(); // DOT
        return receiver;
    }

    private boolean atDefinitelyOutOfReceiver() {
        return at(EQ) || at(COLON) || at(LBRACE) || at(RBRACE) || atSoft("by")
                || TOP_LEVEL_DECLARATION_FIRST.contains(tt());
    }

    /**
     * Index of the dot ending a receiver type, or -1. Mirrors the reference's
     * lastDotAfterReceiverLParPattern (when at '(') and
     * lastDotAfterReceiverNotLParPattern.
     */
    private int lastDotAfterReceiver() {
        boolean startsWithLPar = at(LPAR);
        Mark start = mark();
        int result = -1;
        int angles = 0, braces = 0, parens = 0, brackets = 0;
        while (!eof()) {
            boolean topLevel = angles == 0 && braces == 0 && parens == 0 && brackets == 0;
            boolean atDot = at(DOT) || at(SAFE_ACCESS);
            if (startsWithLPar) {
                // The first top-level dot, unless something definitely not in a receiver comes first.
                if (topLevel && atDot) {
                    result = p;
                    break;
                }
                if (topLevel && (atDefinitelyOutOfReceiver() || (!at(QUEST) && !at(LPAR) && !at(RPAR)))) break;
            } else {
                // The last top-level dot before the name.
                if (topLevel && atDot) result = p;
                if (topLevel) {
                    if (atDefinitelyOutOfReceiver() || at(LPAR)) break;
                    if (atIdent()) {
                        TokenKind la = lookahead(1);
                        if (la != LT && la != DOT && la != SAFE_ACCESS && la != QUEST) break;
                    }
                }
            }
            switch (tt()) {
                case LPAR -> parens++;
                case RPAR -> parens--;
                case LT -> angles++;
                case GT -> angles--;
                case LBRACE -> braces++;
                case RBRACE -> braces--;
                case LBRACKET -> brackets++;
                case RBRACKET -> brackets--;
                default -> {
                }
            }
            advance();
        }
        reset(start);
        return result;
    }

    /*
     * functionParameters : "(" functionParameter{","}? ")"
     */
    private List<Decl.Param> parseValueParameterList(boolean typeRequired) {
        pushNewlines(false);
        expect(LPAR, "Expecting '('");
        List<Decl.Param> params = new ArrayList<>();
        while (!at(RPAR)) {
            params.add(parseValueParameter(typeRequired));
            if (at(COMMA)) {
                advance();
            } else if (!at(RPAR)) {
                throw error("Expecting comma or ')'");
            }
        }
        expect(RPAR, "Expecting ')'");
        popNewlines();
        return List.copyOf(params);
    }

    /*
     * functionParameter : modifiers ("val" | "var")? SimpleName (":" type)? ("=" element)?
     */
    private Decl.Param parseValueParameter(boolean typeRequired) {
        int start = pos();
        Modifiers modifiers = parseModifierList(NO_MODIFIER_BEFORE_FOR_VALUE_PARAMETER, false);
        ValOrVar valOrVar = ValOrVar.NONE;
        if (at(VAL_KEYWORD) || at(VAR_KEYWORD)) {
            valOrVar = at(VAR_KEYWORD) ? ValOrVar.VAR : ValOrVar.VAL;
            advance();
        }

        if (at(COMMA) || at(RPAR)) throw error("Expecting a parameter declaration");
        if ((atIdent() && lookahead(1) == LT) || at(COLON)) throw error("Parameter name expected");
        String name = expectIdent("Parameter name expected");
        TypeRef type = null;
        if (at(COLON)) {
            advance(); // COLON
            if (atIdent() && lookahead(1) == COLON) throw error("Type reference expected");
            type = parseTypeRef();
        } else if (typeRequired) {
            throw error("Parameters must have type annotation");
        }

        Expr defaultValue = null;
        if (consumeIf(EQ)) defaultValue = parseExpression();
        return new Decl.Param(start, modifiers, valOrVar, name, type, defaultValue);
    }

    /** A value parameter if one can be parsed here, otherwise null with the position unchanged. */
    private Decl.Param tryParseValueParameter(boolean typeRequired) {
        // Cheap check to avoid throwing in the common case of a plain type.
        boolean plausible = (atIdent() && lookahead(1) == COLON) || at(AT) || at(VAL_KEYWORD) || at(VAR_KEYWORD)
                || currentModifier() != null;
        if (!plausible) return null;
        return attempt(() -> parseValueParameter(typeRequired));
    }

    // ==== Type parameters and constraints ====

    /* typeParameters : ("<" typeParameter{","} ">")? */
    private List<Decl.TypeParam> parseTypeParameterList() {
        if (!at(LT)) return List.of();
        pushNewlines(false);
        advance(); // LT
        List<Decl.TypeParam> params = new ArrayList<>();
        while (true) {
            params.add(parseTypeParameter());
            if (!consumeIf(COMMA)) break;
            if (at(GT)) break;
        }
        expect(GT, "Missing '>'");
        popNewlines();
        return List.copyOf(params);
    }

    /* typeParameter : modifiers SimpleName (":" userType)? */
    private Decl.TypeParam parseTypeParameter() {
        if (atSoft("where") || at(LPAR) || at(COLON) || at(LBRACE) || at(GT)) {
            throw error("Type parameter declaration expected");
        }
        int start = pos();
        Modifiers modifiers = parseModifierList(EnumSet.of(GT, COMMA, COLON), false);
        String name = expectIdent("Type parameter name expected");
        TypeRef bound = null;
        if (consumeIf(COLON)) bound = parseTypeRef();
        return new Decl.TypeParam(start, modifiers, name, bound);
    }

    /* typeConstraints : "where" typeConstraint{","} */
    private List<Decl.TypeConstraint> parseTypeConstraintList() {
        advance(); // where
        List<Decl.TypeConstraint> constraints = new ArrayList<>();
        while (true) {
            int start = pos();
            List<Annotation> annotations = parseAnnotations(AnnotationMode.DEFAULT);
            String name = expectIdent("Expecting type parameter name");
            expect(COLON, "Expecting ':' before the upper bound");
            constraints.add(new Decl.TypeConstraint(start, annotations, name, parseTypeRef()));
            if (!consumeIf(COMMA)) break;
        }
        return List.copyOf(constraints);
    }

    // ==== Types ====

    private TypeRef parseTypeRef() {
        return parseTypeRef(true);
    }

    private TypeRef parseTypeRefWithoutIntersections() {
        return parseTypeRef(false);
    }

    /*
     * type : typeModifiers (functionType | userType | nullableType | "dynamic")
     */
    private TypeRef parseTypeRef(boolean allowSimpleIntersectionTypes) {
        int start = pos();
        Modifiers modifiers = parseModifierList(EnumSet.of(Modifier.SUSPEND), AnnotationMode.TYPE_CONTEXT,
                EnumSet.noneOf(TokenKind.class), false);

        int typeStart = pos();
        List<NamedType> context = null;
        boolean withContextReceiver = atSoft("context") && lookahead(1) == LPAR;
        if (withContextReceiver) context = parseContextParameterOrReceiverList(true).params();

        TypeRef type;
        boolean typeBeforeDot = true;
        boolean wasFunctionTypeParsed = false;
        TokenKind la = lookahead(1);
        if (atSoft("dynamic") && !(la == DOT && lookahead(2) == IDENTIFIER) && la != LT) {
            advance(); // dynamic
            type = new TypeRef.Dynamic(typeStart);
        } else if (atIdent()) {
            type = parseUserType();
        } else if (at(LPAR)) {
            // Either a parenthesized type "(A)" or a function type "(A, B) -> C".
            Mark before = mark();
            advance(); // LPAR
            TypeRef inner = attempt(() -> parseTypeRef(true));
            if (inner != null && at(RPAR) && lookahead(1) != ARROW) {
                advance(); // RPAR
                type = inner;
            } else {
                reset(before);
                type = parseFunctionType(typeStart, context, null);
                wasFunctionTypeParsed = true;
            }
        } else {
            throw error("Type expected");
        }

        // Joining is disabled for cases like "Int?.(Foo) -> Bar". "?:" is never a nullable suffix.
        pushJoin(false);
        while (at(QUEST) && adjacentNextKind() != COLON) {
            advance(); // QUEST
            type = new TypeRef.Nullable(typeStart, type);
        }
        popJoin();

        boolean wasIntersection = false;
        if (allowSimpleIntersectionTypes && at(AND)) {
            advance(); // AND
            type = new TypeRef.Intersection(typeStart, type, parseTypeRef(true));
            wasIntersection = true;
        }

        if (typeBeforeDot && at(DOT) && !wasIntersection && !wasFunctionTypeParsed) {
            // A receiver for a function type: A.(B) -> C
            advance(); // DOT
            if (!at(LPAR)) throw error("Expecting function type");
            type = parseFunctionType(typeStart, context, type);
            wasFunctionTypeParsed = true;
        }

        if (withContextReceiver && !wasFunctionTypeParsed) throw error("Function type expected");

        return modifiers.isEmpty() ? type : new TypeRef.Modified(start, modifiers, type);
    }

    /* userType : simpleUserType{"."} */
    private TypeRef.Simple parseUserType() {
        int start = pos();
        List<TypeRef.Segment> segments = new ArrayList<>();
        while (true) {
            String name = expectIdent("Expecting type name");
            List<TypeRef.TypeArg> args = at(LT) ? parseTypeArgumentList() : List.of();
            segments.add(new TypeRef.Segment(name, args));
            if (!at(DOT)) break;
            // This may be a receiver for a function type: Int.(Int) -> Int
            if (lookahead(1) == LPAR) break;
            advance(); // DOT
        }
        return new TypeRef.Simple(start, List.copyOf(segments));
    }

    private List<TypeRef.TypeArg> parseTypeArgumentList() {
        List<TypeRef.TypeArg> args = tryParseTypeArgumentList();
        if (args == null) throw error("Expecting a '>'");
        return args;
    }

    /* "<" (optionalProjection type){","} ">"; returns null if there is no closing '>'. */
    private List<TypeRef.TypeArg> tryParseTypeArgumentList() {
        pushNewlines(false);
        advance(); // LT
        List<TypeRef.TypeArg> args = new ArrayList<>();
        while (true) {
            int start = pos();
            Modifiers projection = parseModifierList(EnumSet.of(Modifier.IN, Modifier.OUT),
                    AnnotationMode.NO_ANNOTATIONS_NO_CONTEXT, EnumSet.of(COMMA, COLON, GT), false);
            TypeRef.Variance variance = projection.has(Modifier.IN) ? TypeRef.Variance.IN
                    : projection.has(Modifier.OUT) ? TypeRef.Variance.OUT : TypeRef.Variance.INVARIANT;
            TypeRef type = null;
            if (at(MUL)) {
                advance(); // MUL
            } else {
                type = parseTypeRef();
            }
            args.add(new TypeRef.TypeArg(start, variance, type));
            if (!consumeIf(COMMA)) break;
            if (at(GT)) break;
        }
        boolean atGT = consumeIf(GT);
        popNewlines();
        return atGT ? List.copyOf(args) : null;
    }

    /** Type arguments in an expression, e.g. {@code foo<T>()}; null with the position unchanged if absent. */
    private List<TypeRef.TypeArg> tryParseTypeArgumentListInExpression() {
        // Cheap check to avoid throwing in the common case of a less-than comparison.
        TokenKind la = lookahead(1);
        if (la != IDENTIFIER && la != LPAR && la != AT && la != MUL && la != IN_KEYWORD) return null;
        Mark before = mark();
        List<TypeRef.TypeArg> args = attempt(this::tryParseTypeArgumentList);
        if (args == null) reset(before);
        return args;
    }

    /* functionType : (type ".")? "(" parameter{","}? ")" "->" type */
    private TypeRef.Function parseFunctionType(int start, List<NamedType> context, TypeRef receiver) {
        pushNewlines(false);
        expect(LPAR, "Expecting '('");
        List<NamedType> params = new ArrayList<>();
        while (!at(RPAR)) {
            Decl.Param param = tryParseValueParameter(true);
            if (param != null) {
                params.add(new NamedType(param.pos(), param.name(), param.type()));
            } else {
                int paramStart = pos();
                parseModifierList(EnumSet.of(Modifier.OUT, Modifier.VARARG), AnnotationMode.NO_ANNOTATIONS_NO_CONTEXT,
                        NO_MODIFIER_BEFORE_FOR_VALUE_PARAMETER, false);
                params.add(new NamedType(paramStart, null, parseTypeRef()));
            }
            if (at(COMMA)) {
                advance();
            } else if (!at(RPAR)) {
                throw error("Expecting comma or ')'");
            }
        }
        expect(RPAR, "Expecting ')'");
        popNewlines();

        expect(ARROW, "Expecting '->' to specify return type of a function type");
        TypeRef returnType = parseTypeRef();
        return new TypeRef.Function(start, context == null ? List.of() : context, receiver, List.copyOf(params),
                returnType);
    }

    // ==== Expressions ====

    private Expr parseExpression() {
        return parseExpression("Expecting an expression");
    }

    private Expr parseExpression(String messageIfNotExpressionFirst) {
        if (!EXPRESSION_FIRST.contains(tt())) throw error(messageIfNotExpressionFirst);
        return parseBinaryExpression(Precedence.ASSIGNMENT);
    }

    /** Set by parseBinaryExpression: the minimum precedence an enclosing call may continue with. */
    private Precedence binaryMinPrecedence;

    /*
     * element (operation element)*
     *
     * All binary operations are left-associative. "is" and "as" do not parse
     * their right-hand side recursively, so they limit what may follow them
     * through binaryMinPrecedence.
     */
    private Expr parseBinaryExpression(Precedence maxPrecedence) {
        int start = pos();
        Expr expr = parsePrefixExpression();
        Precedence minPrecedence = Precedence.AS;

        while (!interruptedWithNewLine()) {
            TokenKind operation = tt();
            Precedence next = Precedence.of(operation);
            if (next == null || next.ordinal() > maxPrecedence.ordinal() || next.ordinal() < minPrecedence.ordinal()) {
                break;
            }
            String operationText = token().value();
            advance(); // operation

            switch (operation) {
                case AS_KEYWORD, AS_SAFE -> {
                    TypeRef type = parseTypeRefWithoutIntersections();
                    minPrecedence = Precedence.AS;
                    expr = new Expr.As(start, expr, type, operation == AS_SAFE);
                }
                case IS_KEYWORD, NOT_IS -> {
                    TypeRef type = parseTypeRefWithoutIntersections();
                    minPrecedence = Precedence.IN_OR_IS;
                    expr = new Expr.Is(start, expr, type, operation == NOT_IS);
                }
                default -> {
                    Expr right = parseBinaryExpression(next.higherPriority());
                    minPrecedence = binaryMinPrecedence;
                    expr = makeBinary(start, operation, operationText, expr, right);
                }
            }
        }
        binaryMinPrecedence = minPrecedence;
        return expr;
    }

    private static Expr makeBinary(int start, TokenKind operation, String text, Expr left, Expr right) {
        Expr.BinaryOp op = switch (operation) {
            case MUL -> Expr.BinaryOp.MUL;
            case DIV -> Expr.BinaryOp.DIV;
            case PERC -> Expr.BinaryOp.REM;
            case PLUS -> Expr.BinaryOp.PLUS;
            case MINUS -> Expr.BinaryOp.MINUS;
            case RANGE -> Expr.BinaryOp.RANGE;
            case RANGE_UNTIL -> Expr.BinaryOp.RANGE_UNTIL;
            case ELVIS -> Expr.BinaryOp.ELVIS;
            case IN_KEYWORD -> Expr.BinaryOp.IN;
            case NOT_IN -> Expr.BinaryOp.NOT_IN;
            case LT -> Expr.BinaryOp.LT;
            case GT -> Expr.BinaryOp.GT;
            case LTEQ -> Expr.BinaryOp.LE;
            case GTEQ -> Expr.BinaryOp.GE;
            case EQEQ -> Expr.BinaryOp.EQ;
            case EXCLEQ -> Expr.BinaryOp.NE;
            case EQEQEQ -> Expr.BinaryOp.IDENTITY_EQ;
            case EXCLEQEQEQ -> Expr.BinaryOp.IDENTITY_NE;
            case ANDAND -> Expr.BinaryOp.AND;
            case OROR -> Expr.BinaryOp.OR;
            default -> null;
        };
        if (op != null) return new Expr.Binary(start, op, left, right);

        Expr.AssignOp assignOp = switch (operation) {
            case EQ -> Expr.AssignOp.ASSIGN;
            case PLUSEQ -> Expr.AssignOp.PLUS_ASSIGN;
            case MINUSEQ -> Expr.AssignOp.MINUS_ASSIGN;
            case MULTEQ -> Expr.AssignOp.MUL_ASSIGN;
            case DIVEQ -> Expr.AssignOp.DIV_ASSIGN;
            case PERCEQ -> Expr.AssignOp.REM_ASSIGN;
            default -> null;
        };
        if (assignOp != null) return new Expr.Assign(start, assignOp, left, right);

        if (operation == IDENTIFIER) return new Expr.InfixCall(start, left, text, right);
        throw new IllegalStateException("Unexpected binary operation " + operation);
    }

    /* (annotations | label | prefixOperation)* postfixExpression */
    private Expr parsePrefixExpression() {
        int start = pos();
        if (at(AT)) {
            Decl decl = parseLocalDeclaration(false);
            if (decl != null) return wrapDeclaration(decl);
            List<Annotation> annotations = parseAnnotations(AnnotationMode.DEFAULT);
            return new Expr.Annotated(start, annotations, parsePrefixExpression());
        }

        pushJoin(false);
        if (isAtLabelDefinition()) {
            popJoin();
            String label = parseLabelDefinition();
            return new Expr.Labeled(start, label, parsePrefixExpression());
        }
        Expr.PrefixOp op = switch (tt()) {
            case MINUS -> Expr.PrefixOp.MINUS;
            case PLUS -> Expr.PrefixOp.PLUS;
            case MINUSMINUS -> Expr.PrefixOp.DEC;
            case PLUSPLUS -> Expr.PrefixOp.INC;
            case EXCL -> Expr.PrefixOp.NOT;
            default -> null;
        };
        if (op != null) {
            advance(); // operation
            popJoin();
            return new Expr.Prefix(start, op, parsePrefixExpression());
        }
        popJoin();
        return parsePostfixExpression();
    }

    /*
     * postfixUnaryExpression : atomicExpression postfixUnaryOperation*
     *
     * postfixUnaryOperation
     *   : "++" | "--" | "!!"
     *   : typeArguments? valueArguments annotatedLambda*
     *   : typeArguments annotatedLambda*
     *   : arrayAccess
     *   : memberAccessOperation SimpleName
     *   : "::" (SimpleName | "class")
     */
    private Expr parsePostfixExpression() {
        int start = pos();
        Expr expr = at(COLONCOLON) ? parseDoubleColonSuffix(start, null) : parseAtomicExpression();

        while (!interruptedWithNewLine()) {
            if (at(LBRACKET)) {
                expr = new Expr.Index(start, expr, parseArrayAccess());
                continue;
            }
            Expr call = parseCallSuffix(start, expr);
            if (call != null) {
                expr = call;
            } else if (at(DOT) || at(SAFE_ACCESS)) {
                // reference: the selector may be any atomic expression; only names are meaningful.
                boolean safe = at(SAFE_ACCESS);
                advance(); // DOT or SAFE_ACCESS
                expr = new Expr.Member(start, expr, expectIdent("Expecting an identifier"), safe);
            } else if (at(PLUSPLUS) || at(MINUSMINUS) || at(EXCLEXCL)) {
                Expr.PostfixOp op = at(PLUSPLUS) ? Expr.PostfixOp.INC
                        : at(MINUSMINUS) ? Expr.PostfixOp.DEC : Expr.PostfixOp.NOT_NULL;
                advance(); // operation
                expr = new Expr.Postfix(start, op, expr);
            } else {
                skipQuestionMarksBeforeDoubleColon();
                if (!at(COLONCOLON)) break;
                expr = parseDoubleColonSuffix(start, expr);
            }
        }
        return expr;
    }

    /* "::" (SimpleName | "class") */
    private Expr parseDoubleColonSuffix(int start, Expr receiver) {
        advance(); // COLONCOLON
        if (at(CLASS_KEYWORD)) {
            advance(); // CLASS_KEYWORD
            return new Expr.ClassLiteral(start, receiver);
        }
        String name = expectIdent("Expecting an identifier");
        if (at(LT) && tryParseTypeArgumentListInExpression() != null) throw error("Type arguments are not allowed");
        if (at(LPAR) && !newlineBefore()) {
            throw error("This syntax is reserved for future use; to call a reference, enclose it in parentheses");
        }
        return new Expr.CallableRef(start, receiver, name);
    }

    /* reference: "A?::b" is accepted syntactically (and rejected later); the '?' are dropped. */
    private void skipQuestionMarksBeforeDoubleColon() {
        if (!at(QUEST)) return;
        int k = 1;
        while (lookahead(k) == QUEST) k++;
        if (lookahead(k) == COLONCOLON) {
            for (; k > 0; k--) advance();
        }
    }

    /*
     * callSuffix
     *   : typeArguments? valueArguments annotatedLambda*
     *   : typeArguments annotatedLambda*
     *   : annotatedLambda+
     *
     * Returns null, with the position unchanged, if there is no call suffix.
     */
    private Expr parseCallSuffix(int start, Expr callee) {
        List<Expr> lambdas = parseCallWithClosure();
        if (!lambdas.isEmpty()) {
            return new Expr.Call(start, callee, List.of(), List.of(), lambdas);
        }
        if (at(LPAR)) {
            List<ValueArg> args = parseValueArgumentList();
            return new Expr.Call(start, callee, List.of(), args, parseCallWithClosure());
        }
        if (at(LT)) {
            List<TypeRef.TypeArg> typeArgs = tryParseTypeArgumentListInExpression();
            if (typeArgs == null) return null;
            List<ValueArg> args = !newlineBefore() && at(LPAR) ? parseValueArgumentList() : null;
            lambdas = parseCallWithClosure();
            if (args == null && lambdas.isEmpty()) return new Expr.TypeApplication(start, callee, typeArgs);
            return new Expr.Call(start, callee, typeArgs, args == null ? List.of() : args, lambdas);
        }
        return null;
    }

    /* annotatedLambda* */
    private List<Expr> parseCallWithClosure() {
        // In "class A : B by c {", the brace starts the class body, not a lambda argument.
        if (byClauseDepth == newlineDepth) return List.of();
        List<Expr> lambdas = null;
        while (true) {
            Expr lambda = parseAnnotatedLambda(false);
            if (lambda == null) break;
            if (lambdas == null) lambdas = new ArrayList<>();
            lambdas.add(lambda);
        }
        return lambdas == null ? List.of() : List.copyOf(lambdas);
    }

    /*
     * annotatedLambda : ("@" annotationEntry)* labelDefinition? functionLiteral
     *
     * Returns null, with the position unchanged, if not at a lambda.
     */
    private Expr parseAnnotatedLambda(boolean preferBlock) {
        if (!at(AT) && !at(LBRACE) && !isAtLabelDefinition()) return null;
        Mark before = mark();
        int start = pos();
        List<Annotation> annotations = parseAnnotations(AnnotationMode.DEFAULT);
        int labelStart = pos();
        String label = isAtLabelDefinition() ? parseLabelDefinition() : null;
        if (!at(LBRACE)) {
            reset(before);
            return null;
        }
        Expr expr = parseFunctionLiteral(preferBlock);
        if (label != null) expr = new Expr.Labeled(labelStart, label, expr);
        if (!annotations.isEmpty()) expr = new Expr.Annotated(start, annotations, expr);
        return expr;
    }

    private boolean isAtLabelDefinition() {
        return atIdent() && adjacentNextKind() == AT;
    }

    /* IDENTIFIER "@" */
    private String parseLabelDefinition() {
        String label = token().value();
        advance(); // IDENTIFIER
        advance(); // AT
        return label;
    }

    /* ("@" IDENTIFIER)? with no whitespace before the '@' */
    private String parseLabelReferenceWithNoWhitespace() {
        if (!at(AT) || newlineBefore()) return null;
        if (token().spaceBefore()) throw error("There should be no space or comments before '@' in label reference");
        if (adjacentNextKind() != IDENTIFIER) throw error("Label must be named");
        advance(); // AT
        String label = token().value();
        advance(); // IDENTIFIER
        return label;
    }

    /*
     * atomicExpression
     *   : "(" expression ")" | "[" collection "]" | "this" label? | "super" ("<" type ">")? label?
     *   : objectLiteral | jump | if | when | try | loop | literalConstant
     *   : functionLiteral | declaration | SimpleName | stringTemplate
     */
    private Expr parseAtomicExpression() {
        int start = pos();
        switch (tt()) {
            case LPAR -> {
                return parseParenthesizedExpression();
            }
            case LBRACKET -> {
                return new Expr.CollectionLiteral(start, parseBracketedExpressions(true, "Expecting an element"));
            }
            case THIS_KEYWORD -> {
                advance(); // THIS_KEYWORD
                return new Expr.This(start, parseLabelReferenceWithNoWhitespace());
            }
            case SUPER_KEYWORD -> {
                return parseSuperExpression();
            }
            case OBJECT_KEYWORD -> {
                return new Expr.ObjectLiteral(start,
                        parseClassOrObject(start, Modifiers.EMPTY, true, NameMode.PROHIBITED, false, false));
            }
            case THROW_KEYWORD -> {
                advance(); // THROW_KEYWORD
                return new Expr.Throw(start, parseExpression());
            }
            case RETURN_KEYWORD -> {
                advance(); // RETURN_KEYWORD
                String label = parseLabelReferenceWithNoWhitespace();
                Expr value = EXPRESSION_FIRST.contains(tt()) && !atEolOrSemicolon() ? parseExpression() : null;
                return new Expr.Return(start, label, value);
            }
            case CONTINUE_KEYWORD -> {
                advance(); // CONTINUE_KEYWORD
                return new Expr.Continue(start, parseLabelReferenceWithNoWhitespace());
            }
            case BREAK_KEYWORD -> {
                advance(); // BREAK_KEYWORD
                return new Expr.Break(start, parseLabelReferenceWithNoWhitespace());
            }
            case IF_KEYWORD -> {
                return parseIf();
            }
            case WHEN_KEYWORD -> {
                return parseWhen();
            }
            case TRY_KEYWORD -> {
                return parseTry();
            }
            case FOR_KEYWORD -> {
                return parseFor();
            }
            case WHILE_KEYWORD -> {
                advance(); // WHILE_KEYWORD
                Expr condition = parseCondition();
                return new Expr.While(start, condition, parseLoopBody());
            }
            case DO_KEYWORD -> {
                advance(); // DO_KEYWORD
                Expr body = at(WHILE_KEYWORD) ? null : parseLoopBody();
                expect(WHILE_KEYWORD, "Expecting 'while' followed by a post-condition");
                return new Expr.DoWhile(start, body, parseCondition());
            }
            case IDENTIFIER -> {
                // An anonymous function with context parameters.
                if (atSoft("context") && lookahead(1) == LPAR) {
                    Decl decl = parseLocalDeclaration(true);
                    if (decl != null) return wrapDeclaration(decl);
                }
                String name = token().value();
                advance(); // IDENTIFIER
                return new Expr.Name(start, name);
            }
            case LBRACE -> {
                return parseFunctionLiteral(false);
            }
            case INTERPOLATION_PREFIX, OPEN_QUOTE -> {
                return parseStringTemplate();
            }
            case TRUE_KEYWORD, FALSE_KEYWORD -> {
                boolean value = at(TRUE_KEYWORD);
                advance();
                return new Expr.BoolLit(start, value);
            }
            case INTEGER_LITERAL -> {
                String text = text();
                advance();
                return new Expr.IntLit(start, text);
            }
            case FLOAT_LITERAL -> {
                String text = text();
                advance();
                return new Expr.FloatLit(start, text);
            }
            case CHARACTER_LITERAL -> {
                char value = token().value().charAt(0);
                advance();
                return new Expr.CharLit(start, value);
            }
            case NULL_KEYWORD -> {
                advance();
                return new Expr.NullLit(start);
            }
            case CLASS_KEYWORD, INTERFACE_KEYWORD, FUN_KEYWORD, VAL_KEYWORD, VAR_KEYWORD, TYPEALIAS_KEYWORD -> {
                Decl decl = parseLocalDeclaration(newlineBefore());
                if (decl == null) throw error("Expecting an element");
                return wrapDeclaration(decl);
            }
            default -> throw error("Expecting an element");
        }
    }

    /** A declaration in expression position: an anonymous function, or a misplaced declaration. */
    private static Expr wrapDeclaration(Decl decl) {
        if (decl instanceof Decl.Function f && f.name() == null) return new Expr.AnonymousFunction(f.pos(), f);
        return new Expr.DeclExpr(decl.pos(), decl);
    }

    /* stringTemplate : INTERPOLATION_PREFIX? OPEN_QUOTE stringTemplateElement* CLOSING_QUOTE */
    private Expr parseStringTemplate() {
        int start = pos();
        consumeIf(INTERPOLATION_PREFIX);
        advance(); // OPEN_QUOTE

        List<Expr.StringPart> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        while (!at(CLOSING_QUOTE)) {
            switch (tt()) {
                case STRING_PART -> {
                    text.append(token().value());
                    advance();
                }
                case SHORT_TEMPLATE_ENTRY_START -> {
                    flushText(parts, text);
                    advance(); // SHORT_TEMPLATE_ENTRY_START
                    int entryStart = pos();
                    if (at(THIS_KEYWORD)) {
                        advance(); // THIS_KEYWORD
                        parts.add(new Expr.ExprPart(new Expr.This(entryStart, null)));
                    } else {
                        if (HARD_KEYWORD_TEXTS.contains(text())) throw error("Keyword cannot be used as a reference");
                        parts.add(new Expr.ExprPart(new Expr.Name(entryStart, expectIdent("Expecting a name"))));
                    }
                }
                case LONG_TEMPLATE_ENTRY_START -> {
                    flushText(parts, text);
                    advance(); // LONG_TEMPLATE_ENTRY_START
                    parts.add(new Expr.ExprPart(parseExpression()));
                    expect(LONG_TEMPLATE_ENTRY_END, "Expecting '}'");
                }
                default -> throw error("Unexpected token in a string template");
            }
        }
        flushText(parts, text);
        advance(); // CLOSING_QUOTE
        return new Expr.StringTemplate(start, List.copyOf(parts));
    }

    private static void flushText(List<Expr.StringPart> parts, StringBuilder text) {
        if (!text.isEmpty()) {
            parts.add(new Expr.TextPart(text.toString()));
            text.setLength(0);
        }
    }

    /*
     * when
     *   : "when" ("(" (modifiers "val" SimpleName "=")? element ")")? "{" whenEntry* "}"
     */
    private Expr parseWhen() {
        int start = pos();
        advance(); // WHEN_KEYWORD

        Expr subject = null;
        Decl.Property subjectVariable = null;
        pushNewlines(false);
        if (at(LPAR)) {
            advance(); // LPAR
            Mark before = mark();
            int declStart = pos();
            Modifiers annotations = parseModifierList(Set.of(), AnnotationMode.DEFAULT, EnumSet.of(EQ, RPAR), false);
            if (at(VAL_KEYWORD) || at(VAR_KEYWORD)) {
                if (!(parseProperty(declStart, annotations, DeclarationMode.LOCAL) instanceof Decl.Property property)) {
                    throw error("Expecting a variable declaration");
                }
                subjectVariable = property;
            } else {
                reset(before);
                subject = parseExpression();
            }
            expect(RPAR, "Expecting ')'");
        }
        popNewlines();

        pushNewlines(true);
        expect(LBRACE, "Expecting '{'");
        List<Expr.WhenEntry> entries = new ArrayList<>();
        while (!eof() && !at(RBRACE)) {
            entries.add(parseWhenEntry());
        }
        expect(RBRACE, "Expecting '}'");
        popNewlines();

        return new Expr.When(start, subject, subjectVariable, List.copyOf(entries));
    }

    /*
     * whenEntry
     *   : whenCondition{","} whenEntryGuard? "->" element SEMI
     *   : "else" whenEntryGuard? "->" element SEMI
     */
    private Expr.WhenEntry parseWhenEntry() {
        int start = pos();
        List<Expr.WhenCondition> conditions = new ArrayList<>();
        if (at(ELSE_KEYWORD)) {
            advance(); // ELSE_KEYWORD
        } else {
            while (true) {
                conditions.add(parseWhenCondition());
                if (!consumeIf(COMMA)) break;
                if (at(ARROW)) break;
            }
        }

        Expr guard = null;
        if (at(ANDAND)) throw error("Unexpected '&&', use 'if' to introduce additional conditions");
        if (at(IF_KEYWORD)) {
            advance(); // IF_KEYWORD
            guard = parseExpression();
        }

        expect(ARROW, "Expecting '->'");
        if (WHEN_CONDITION_RECOVERY_SET.contains(tt())) throw error("Expecting an element");
        Expr body = parseControlStructureBody();
        consumeIf(SEMICOLON);
        return new Expr.WhenEntry(start, List.copyOf(conditions), guard, body);
    }

    /*
     * whenCondition
     *   : expression
     *   : ("in" | "!in") expression
     *   : ("is" | "!is") type
     */
    private Expr.WhenCondition parseWhenCondition() {
        int start = pos();
        pushNewlines(false);
        Expr.WhenCondition condition = switch (tt()) {
            case IN_KEYWORD, NOT_IN -> {
                boolean negated = at(NOT_IN);
                advance(); // IN_KEYWORD or NOT_IN
                yield new Expr.InCondition(start, parseExpression(), negated);
            }
            case IS_KEYWORD, NOT_IS -> {
                boolean negated = at(NOT_IS);
                advance(); // IS_KEYWORD or NOT_IS
                yield new Expr.IsCondition(start, parseTypeRef(), negated);
            }
            case RBRACE, ELSE_KEYWORD, ARROW, DOT -> throw error("Expecting an expression, is-condition or in-condition");
            default -> new Expr.ExprCondition(start, parseExpression());
        };
        popNewlines();
        return condition;
    }

    /* arrayAccess : "[" element{","} "]" */
    private List<Expr> parseArrayAccess() {
        return parseBracketedExpressions(false, "Expecting an index element");
    }

    private List<Expr> parseBracketedExpressions(boolean canBeEmpty, String missingElementMessage) {
        pushNewlines(false);
        advance(); // LBRACKET
        if (!canBeEmpty && at(RBRACKET)) throw error(missingElementMessage);
        List<Expr> elements = new ArrayList<>();
        while (!at(RBRACKET)) {
            elements.add(parseExpression(missingElementMessage));
            if (!consumeIf(COMMA)) break;
        }
        expect(RBRACKET, "Expecting ']'");
        popNewlines();
        return List.copyOf(elements);
    }

    /*
     * modifiers declarationRest
     *
     * Returns null, with the position unchanged, if there is no declaration here.
     * If rollbackIfDefinitelyNotExpression, only accepts anonymous functions.
     */
    private Decl parseLocalDeclaration(boolean rollbackIfDefinitelyNotExpression) {
        Mark before = mark();
        int start = pos();
        Modifiers modifiers = parseModifierList(EnumSet.noneOf(TokenKind.class), true);

        Decl decl;
        if (rollbackIfDefinitelyNotExpression) {
            decl = at(FUN_KEYWORD) ? parseFunction(start, modifiers, true) : null;
        } else if (at(OBJECT_KEYWORD) && (lookahead(1) == COLON || lookahead(1) == LBRACE)) {
            // An object expression at statement position.
            decl = null;
        } else {
            decl = parseCommonDeclaration(start, modifiers, NameMode.REQUIRED, DeclarationMode.LOCAL);
        }

        if (decl == null) reset(before);
        return decl;
    }

    /*
     * functionLiteral
     *   : "{" statements "}"
     *   : "{" lambdaParameter{","} "->" statements "}"
     *
     * If preferBlock and there is no "->", it is a block rather than a lambda.
     */
    private Expr parseFunctionLiteral(boolean preferBlock) {
        int start = pos();
        pushNewlines(true);
        advance(); // LBRACE

        List<Expr.Binding> params = List.of();
        boolean hasArrow = false;
        if (at(ARROW)) {
            advance(); // ARROW
            hasArrow = true;
        } else if (atIdent() || at(COLON) || at(LPAR) || at(LBRACKET)) {
            // Try to parse a parameter list followed by an arrow: {a -> ...}, {a, b -> ...}, {(a, b) -> ...}
            Mark before = mark();
            TokenKind next = lookahead(1);
            boolean preferParamsToExpressions = next == COMMA || next == COLON;
            List<Expr.Binding> parsed = attempt(this::parseFunctionLiteralParameterList);
            if (parsed != null && at(ARROW)) {
                advance(); // ARROW
                params = parsed;
                hasArrow = true;
            } else if (parsed != null && preferParamsToExpressions && at(RBRACE)) {
                throw error("An -> is expected");
            } else {
                reset(before);
            }
        }

        List<Stmt> statements = parseStatements();
        expect(RBRACE, "Expecting '}'");
        popNewlines();
        if (!hasArrow && preferBlock) return new Expr.Block(start, statements);
        return new Expr.Lambda(start, params, hasArrow, statements);
    }

    /*
     * lambdaParameter{","}, stopping before "->". Returns null if this is not a
     * parameter list.
     *
     * lambdaParameter
     *   : variableDeclarationEntry
     *   : multipleVariableDeclarations (":" type)?
     */
    private List<Expr.Binding> parseFunctionLiteralParameterList() {
        List<Expr.Binding> params = new ArrayList<>();
        while (!eof() && !at(ARROW)) {
            int start = pos();
            String name = null;
            Decl.Destructuring destructuring = null;
            if (at(LPAR) || at(LBRACKET)) {
                boolean squareBrackets = at(LBRACKET);
                List<Decl.DestructuringEntry> entries = parseMultiDeclarationEntry(
                        lookahead(1) == VAL_KEYWORD ? MultiDeclarationMode.FULL_VAL_ONLY : MultiDeclarationMode.SHORT);
                destructuring = new Decl.Destructuring(start, Modifiers.EMPTY, ValOrVar.NONE, squareBrackets, entries, null);
            } else if (atIdent()) {
                name = expectIdent("Expecting parameter name");
            } else {
                return null;
            }
            TypeRef type = null;
            if (consumeIf(COLON)) type = parseTypeRef();
            params.add(new Expr.Binding(start, Modifiers.EMPTY, name, destructuring, type));

            if (at(ARROW)) break;
            if (!consumeIf(COMMA)) return null;
        }
        return List.copyOf(params);
    }

    /* statements : SEMI* statement{SEMI+} SEMI* */
    private List<Stmt> parseStatements() {
        List<Stmt> statements = new ArrayList<>();
        while (at(SEMICOLON)) advance();
        while (!eof() && !at(RBRACE)) {
            if (!STATEMENT_FIRST.contains(tt())) throw error("Expecting an element");
            statements.add(parseStatement());
            if (at(SEMICOLON)) {
                while (at(SEMICOLON)) advance();
            } else if (at(RBRACE)) {
                break;
            } else if (!newlineBefore()) {
                throw error("Unexpected tokens (use ';' to separate expressions on the same line)");
            }
        }
        return List.copyOf(statements);
    }

    /* statement : declaration | blockLevelExpression */
    private Stmt parseStatement() {
        Decl decl = parseLocalDeclaration(false);
        if (decl != null) {
            return decl instanceof Decl.Function f && f.name() == null ? new Expr.AnonymousFunction(f.pos(), f) : decl;
        }
        if (!EXPRESSION_FIRST.contains(tt())) throw error("Expecting a statement");
        return parseBlockLevelExpression();
    }

    /*
     * blockLevelExpression : annotations ("\n")+ expression
     *
     * Annotations followed by a newline annotate the whole expression on the
     * next line; otherwise they are part of the expression.
     */
    private Expr parseBlockLevelExpression() {
        if (at(AT)) {
            Mark before = mark();
            int start = pos();
            List<Annotation> annotations = parseAnnotations(AnnotationMode.DEFAULT);
            if (!newlineBefore()) {
                reset(before);
                return parseExpression();
            }
            return new Expr.Annotated(start, annotations, parseBlockLevelExpression());
        }
        return parseExpression();
    }

    /*
     * for
     *   : "for" "(" annotations ("val" | "var")? (multipleVariableDeclarations | variableDeclarationEntry)
     *       "in" expression ")" expression
     */
    private Expr parseFor() {
        int start = pos();
        advance(); // FOR_KEYWORD
        expect(LPAR, "Expecting '(' to open a loop range");
        pushNewlines(false);
        if (at(RPAR)) throw error("Expecting a variable name");

        int paramStart = pos();
        Modifiers modifiers = at(IN_KEYWORD) ? Modifiers.EMPTY
                : parseModifierList(EnumSet.of(IN_KEYWORD, RPAR, COLON), false);
        if (at(VAL_KEYWORD) || at(VAR_KEYWORD)) advance(); // reference: accepted here, rejected later

        String name = null;
        Decl.Destructuring destructuring = null;
        TypeRef type = null;
        if (at(LPAR) || at(LBRACKET)) {
            int destructuringStart = pos();
            boolean squareBrackets = at(LBRACKET);
            List<Decl.DestructuringEntry> entries = parseMultiDeclarationEntry(
                    lookahead(1) == VAL_KEYWORD ? MultiDeclarationMode.FULL_VAL_ONLY : MultiDeclarationMode.SHORT);
            destructuring = new Decl.Destructuring(destructuringStart, Modifiers.EMPTY, ValOrVar.NONE, squareBrackets,
                    entries, null);
        } else {
            name = expectIdent("Expecting a variable name");
            if (consumeIf(COLON)) type = parseTypeRef();
        }
        Expr.Binding variable = new Expr.Binding(paramStart, modifiers, name, destructuring, type);

        expect(IN_KEYWORD, "Expecting 'in'");
        Expr iterable = parseExpression();
        expect(RPAR, "Expecting ')'");
        popNewlines();

        return new Expr.For(start, variable, iterable, parseLoopBody());
    }

    private Expr parseControlStructureBody() {
        Expr lambda = parseAnnotatedLambda(true);
        return lambda != null ? lambda : parseBlockLevelExpression();
    }

    private Expr parseLoopBody() {
        return at(SEMICOLON) ? null : parseControlStructureBody();
    }

    /*
     * try : "try" block catchBlock* finallyBlock?
     * catchBlock : "catch" "(" annotations SimpleName ":" userType ")" block
     * finallyBlock : "finally" block
     */
    private Expr parseTry() {
        int start = pos();
        advance(); // TRY_KEYWORD
        Expr.Block body = parseBlock();

        List<Expr.Catch> catches = new ArrayList<>();
        while (atSoft("catch")) {
            int catchStart = pos();
            advance(); // catch
            expect(LPAR, "Expecting '('");
            Decl.Param param = parseValueParameter(true);
            consumeIf(COMMA); // trailing comma
            expect(RPAR, "Expecting ')'");
            if (!at(LBRACE)) throw error("Expecting a block: { ... }");
            catches.add(new Expr.Catch(catchStart, param, parseBlock()));
        }

        Expr.Block finallyBlock = null;
        if (atSoft("finally")) {
            advance(); // finally
            finallyBlock = parseBlock();
        }
        if (catches.isEmpty() && finallyBlock == null) throw error("Expecting 'catch' or 'finally'");
        return new Expr.Try(start, body, List.copyOf(catches), finallyBlock);
    }

    /* if : "if" "(" element ")" element SEMI? ("else" element)? */
    private Expr parseIf() {
        int start = pos();
        advance(); // IF_KEYWORD
        Expr condition = parseCondition();

        Expr then = null;
        if (!at(ELSE_KEYWORD) && !at(SEMICOLON)) then = parseControlStructureBody();
        if (at(SEMICOLON) && lookahead(1) == ELSE_KEYWORD) advance(); // SEMICOLON

        Expr otherwise = null;
        // The arrow check avoids capturing a when entry "else ->".
        if (at(ELSE_KEYWORD) && lookahead(1) != ARROW) {
            advance(); // ELSE_KEYWORD
            if (!at(SEMICOLON)) otherwise = parseControlStructureBody();
        }
        return new Expr.If(start, condition, then, otherwise);
    }

    /* "(" element ")" */
    private Expr parseCondition() {
        pushNewlines(false);
        expect(LPAR, "Expecting a condition in parentheses '(...)'");
        Expr condition = parseExpression();
        expect(RPAR, "Expecting ')'");
        popNewlines();
        return condition;
    }

    private Expr parseParenthesizedExpression() {
        int start = pos();
        pushNewlines(false);
        advance(); // LPAR
        if (at(RPAR)) throw error("Expecting an expression");
        Expr expr = parseExpression();
        expect(RPAR, "Expecting ')'");
        popNewlines();
        return new Expr.Paren(start, expr);
    }

    /* "super" ("<" type ">")? label? */
    private Expr parseSuperExpression() {
        int start = pos();
        advance(); // SUPER_KEYWORD
        TypeRef qualifier = null;
        if (at(LT)) {
            // This may be "super < foo" or "super<foo>".
            Mark before = mark();
            pushNewlines(false);
            advance(); // LT
            qualifier = attempt(this::parseTypeRef);
            if (qualifier != null && at(GT)) {
                advance(); // GT
                popNewlines();
            } else {
                qualifier = null;
                reset(before);
            }
        }
        return new Expr.Super(start, qualifier, parseLabelReferenceWithNoWhitespace());
    }

    /* valueArguments : "(" (SimpleName "=")? "*"? element{","} ")" */
    private List<ValueArg> parseValueArgumentList() {
        pushNewlines(false);
        expect(LPAR, "Expecting an argument list");
        List<ValueArg> args = new ArrayList<>();
        while (!at(RPAR)) {
            args.add(parseValueArgument());
            if (at(COLON) && lookahead(1) == IDENTIFIER) throw error("Unexpected type specification");
            if (!at(COMMA)) {
                if (EXPRESSION_FIRST.contains(tt())) throw error("Expecting ','");
                break;
            }
            advance(); // COMMA
        }
        expect(RPAR, "Expecting ')'");
        popNewlines();
        return List.copyOf(args);
    }

    /* (SimpleName "=")? "*"? element */
    private ValueArg parseValueArgument() {
        int start = pos();
        String name = null;
        if (atIdent() && lookahead(1) == EQ) {
            name = token().value();
            advance(); // IDENTIFIER
            advance(); // EQ
        }
        boolean spread = consumeIf(MUL);
        return new ValueArg(start, name, spread, parseExpression("Expecting an argument"));
    }

    /* block : "{" statements "}" */
    private Expr.Block parseBlock() {
        int start = pos();
        pushNewlines(true);
        expect(LBRACE, "Expecting '{' to open a block");
        List<Stmt> statements = parseStatements();
        expect(RBRACE, "Expecting '}'");
        popNewlines();
        return new Expr.Block(start, statements);
    }
}
