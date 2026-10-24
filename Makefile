# Build for the Kotlin bootstrap compiler.
#
# Deliberately plain: javac + jar, no Java build system.

JAVA      ?= java
JAVAC     ?= javac
JAR       ?= jar
JFLAGS    ?= --release 25 -encoding UTF-8 -Xlint:all,-serial -g

# Where the Kotlin source tree lives (for corpus tests).
KOTLIN_SRC ?= $(HOME)/src/kotlin

BUILD     := build
SRC       := $(shell find src -name '*.java')
TEST_SRC  := $(shell find test -name '*.java')

MAIN_CLASSES := $(BUILD)/classes
TEST_CLASSES := $(BUILD)/test-classes
JARFILE      := $(BUILD)/kbc.jar

# A reference kotlinc, used only as a test oracle; never part of the bootstrap.
KOTLINC_VERSION := 2.4.21
KOTLINC_SHA256  := 7cc140e76daf416a0424a5557f99afd7ca89ebd463ea1101261e3e01c33e75cd
KOTLINC_HOME    := tools/kotlinc-$(KOTLINC_VERSION)
KOTLINC         := $(KOTLINC_HOME)/bin/kotlinc

.PHONY: all clean test test-golden test-resolve test-psi corpus update-golden fetch-kotlinc test-oracle

all: $(JARFILE)

$(MAIN_CLASSES)/.stamp: $(SRC)
	@rm -rf $(MAIN_CLASSES)
	@mkdir -p $(MAIN_CLASSES)
	$(JAVAC) $(JFLAGS) -d $(MAIN_CLASSES) $(SRC)
	@touch $@

$(JARFILE): $(MAIN_CLASSES)/.stamp
	$(JAR) --create --file $@ --main-class kotlinbootstrap.Main -C $(MAIN_CLASSES) .

$(TEST_CLASSES)/.stamp: $(MAIN_CLASSES)/.stamp $(TEST_SRC)
	@rm -rf $(TEST_CLASSES)
	@mkdir -p $(TEST_CLASSES)
	$(JAVAC) $(JFLAGS) -cp $(MAIN_CLASSES) -d $(TEST_CLASSES) $(TEST_SRC)
	@touch $@

TEST_CP := $(MAIN_CLASSES):$(TEST_CLASSES)

ORACLE_SRC     := $(shell find test-oracle -name '*.java')
ORACLE_CLASSES := $(BUILD)/oracle-classes
ORACLE_CP      := $(MAIN_CLASSES):$(KOTLINC_HOME)/lib/kotlin-stdlib.jar:$(KOTLINC_HOME)/lib/kotlin-reflect.jar

$(ORACLE_CLASSES)/.stamp: $(MAIN_CLASSES)/.stamp $(KOTLINC_HOME)/.stamp $(ORACLE_SRC)
	@rm -rf $(ORACLE_CLASSES)
	@mkdir -p $(ORACLE_CLASSES)
	$(JAVAC) $(JFLAGS) -cp $(ORACLE_CP) -d $(ORACLE_CLASSES) $(ORACLE_SRC)
	@touch $@

test: test-golden test-resolve test-psi

# Golden AST dumps under tests/parser.
test-golden: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.GoldenTests tests/parser

# Resolved declarations of tests/resolve, against the stdlib sources.
test-resolve: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.ResolveGoldenTests $(KOTLIN_SRC) tests/resolve

update-golden: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.GoldenTests --update tests/parser
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.ResolveGoldenTests --update $(KOTLIN_SRC) tests/resolve

# The reference parser's own test data: files whose PSI dump has no error
# elements must parse; files with errors must be rejected.
test-psi: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.PsiTestDataTests $(KOTLIN_SRC)/compiler/psi/psi-impl/testData/psi

# Parse every non-test-data .kt file in the Kotlin tree.
corpus: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.CorpusTests $(KOTLIN_SRC)

# Tests against the reference kotlinc; not part of `make test`.
test-oracle: $(ORACLE_CLASSES)/.stamp
	$(JAVA) -cp $(ORACLE_CP):$(ORACLE_CLASSES) kotlinbootstrap.oracle.StdlibSignatureOracle $(KOTLIN_SRC)

fetch-kotlinc: $(KOTLINC_HOME)/.stamp

$(KOTLINC_HOME)/.stamp:
	@mkdir -p tools
	curl -sSL -o tools/kotlin-compiler.zip \
		https://github.com/JetBrains/kotlin/releases/download/v$(KOTLINC_VERSION)/kotlin-compiler-$(KOTLINC_VERSION).zip
	echo "$(KOTLINC_SHA256)  tools/kotlin-compiler.zip" | sha256sum -c -
	rm -rf $(KOTLINC_HOME) tools/kotlinc
	cd tools && unzip -q kotlin-compiler.zip && mv kotlinc kotlinc-$(KOTLINC_VERSION)
	rm tools/kotlin-compiler.zip
	@touch $@

clean:
	rm -rf $(BUILD)
