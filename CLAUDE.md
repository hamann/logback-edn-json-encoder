# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Logback EDN-to-JSON Encoder that converts Clojure EDN format logs into structured JSON for better integration with log aggregation systems like Loki and Grafana. The encoder preserves semantic information while making logs searchable in Grafana.

## Common Commands

### Build Commands

```bash
# Clean target directory
bb clean

# Compile Clojure classes
bb compile

# Build JAR file
bb jar

# Full build (clean, compile, jar)
bb build

# Run tests
bb test

# Run linting
bb lint

# Apply auto-fixes for linting issues
bb lint-fix

# Start development REPL
bb dev

# Run CI pipeline (build + test)
bb ci

# Build and publish to Maven repository (requires SOPS-encrypted secrets.yaml)
bb publish
```

### Loki/Grafana Testing

```bash
# Start local Loki instance
bb loki

# Start local Grafana instance
bb grafana
```

## Project Structure

- `src/logback/edn_json_encoder.clj` - Main encoder implementation
- `test/logback/encoder_test.clj` - Tests for the encoder
- `build.clj` - Build configuration for tools.build
- `bb.edn` - Babashka tasks for development
- `deps.edn` - Clojure dependencies
- `config/` - Configuration files for Loki and Grafana

## Key Components

1. **EdnToJsonEncoder Class**
   - Extends `ch.qos.logback.core.encoder.EncoderBase`
   - Responsible for converting EDN log messages to JSON format

2. **Core Functions**
   - `parse-edn-safely` - Parses EDN strings into structured data
   - `prepare-for-json` - Converts Java types to JSON-compatible formats
   - `safe-get-mdc-with-edn-parsing` - Parses EDN in MDC context
   - `extract-exception-info` - Handles exception information formatting

## Development Environment

The project includes a Nix development environment with all necessary tools. You can start it with:

```bash
nix develop
```

This will provide:
- Babashka
- Clojure with GraalVM
- Loki, Grafana, and Promtail for testing