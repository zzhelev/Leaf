# Builds and installs Leaf.
JAVA_HOME ?= $(HOME)/jdk-25
GRADLE := ./gradlew --no-daemon
SOURCES = $(wildcard app/src/**/*.kt)

.PHONY: all build test clean

all: build

build: $(SOURCES)
	@echo "Building with $(JAVA_HOME)"
	$(GRADLE) build

test:
	JAVA_HOME=$(JAVA_HOME) $(GRADLE) test || (echo "tests failed"; exit 1)

clean:
	rm -rf build/ */build/

ifeq ($(OS),Windows_NT)
  EXE := .exe
else
  EXE :=
endif
