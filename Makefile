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

.PHONY: all clean test test-golden test-psi corpus update-golden

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

test: test-golden test-psi

# Golden AST dumps under tests/parser.
test-golden: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.GoldenTests tests/parser

update-golden: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.GoldenTests --update tests/parser

# The reference parser's own test data: files whose PSI dump has no error
# elements must parse; files with errors must be rejected.
test-psi: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.PsiTestDataTests $(KOTLIN_SRC)/compiler/psi/psi-impl/testData/psi

# Parse every non-test-data .kt file in the Kotlin tree.
corpus: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(TEST_CP) kotlinbootstrap.test.CorpusTests $(KOTLIN_SRC)

clean:
	rm -rf $(BUILD)
