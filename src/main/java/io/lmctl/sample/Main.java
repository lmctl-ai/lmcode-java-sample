package io.lmctl.sample;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.Formatting;
import io.lmctl.lmcode.Program;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Reports;
import io.lmctl.lmcode.ResultSink;
import io.lmctl.lmcode.Value;
import io.lmctl.lmcode.VmException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Native host CLI protocol (`--version`, `--lmcode-schema`, `--lmcode-signatures`, `--lmcode FILE`).
 *
 * <p>All output uses explicit UTF-8 and LF (`\n`) bytes so Linux, macOS, and Windows match
 * the canonical corpus byte-for-byte.
 */
public final class Main {
  private static final String VERSION = version();
  private static String version() {
    try (InputStream in = Main.class.getResourceAsStream("/sample-version.txt")) {
      if (in == null) throw new IllegalStateException("missing sample-version.txt");
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
    } catch (IOException e) { throw new IllegalStateException(e); }
  }
  private static final String USAGE =
      "usage: --version | --lmcode-schema [--json] | --lmcode-signatures | --lmcode FILE";

  private Main() {}

  private static byte[] readSchema() throws IOException {
    try (InputStream in = Main.class.getResourceAsStream("/sample.dsl.json")) {
      if (in == null) {
        throw new IOException("missing embedded sample.dsl.json");
      }
      return in.readAllBytes();
    }
  }

  private static void writeLine(String text) {
    byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
    System.out.write(bytes, 0, bytes.length);
    System.out.flush();
  }

  private static int usage() {
    writeLine(
        "{\"ok\": false, \"phase\": \"cli\", \"code\": \"CLI_USAGE\", \"message\": "
            + Reports.escape(USAGE)
            + "}");
    return 2;
  }

  public static int run(String[] args) {
    try {
      Shop host = new Shop();
      if (args.length == 1 && "--version".equals(args[0])) {
        writeLine("lmcode-java-sample " + VERSION);
        return 0;
      }
      if ((args.length == 1 || (args.length == 2 && "--json".equals(args[1])))
          && "--lmcode-schema".equals(args[0])) {
        byte[] schema = readSchema();
        System.out.write(schema, 0, schema.length);
        System.out.flush();
        return 0;
      }
      if (args.length == 1 && "--lmcode-signatures".equals(args[0])) {
        for (String signature : host.registry.signatures()) {
          writeLine(signature);
        }
        return 0;
      }
      if (args.length != 2 || !"--lmcode".equals(args[0])) {
        return usage();
      }

      byte[] bytecode;
      try {
        bytecode = Files.readAllBytes(Path.of(args[1]));
      } catch (IOException | InvalidPathException e) {
        return Reports.appFailure("cannot read sample input");
      }

      Program program;
      try {
        program = Program.load(bytecode, readSchema(), host.registry);
      } catch (VmException error) {
        // Loader messages are normalized so all eight language ports emit identical load errors.
        String message =
            switch (error.code) {
              case "LOAD_SCHEMA_MISMATCH" -> "sample schema mismatch";
              case "LOAD_UNBOUND" -> "sample import mismatch";
              default -> "invalid sample bytecode";
            };
        return Reports.error("load", new VmException(error.code, message));
      }

      // One invocation owns the result across the body and selected rule entries.
      ResultSink sink = new ResultSink();
      host.registry.setResultSink(sink);
      Context context = host.context();
      try {
        program.run(context, host.registry);
      } catch (VmException error) {
        return Reports.error("run", error);
      }

      // Rule entries (DSLB v6): after the body, @item entries run once per shop
      // item in order, @total once with the shop total, other annotations never.
      int entriesCalled = 0;
      List<Program.Entry> programEntries = program.entries();
      try {
        for (int i = 0; i < programEntries.size(); i++) {
          String annotation = programEntries.get(i).annotation();
          if ("item".equals(annotation)) {
            for (Shop.Money item : host.itemHandles()) {
              callEntry(program, i, context, host.registry, Value.object("Money", item));
              entriesCalled++;
            }
          } else if ("total".equals(annotation)) {
            callEntry(program, i, context, host.registry, host.totalHandle());
            entriesCalled++;
          }
        }
      } catch (VmException error) {
        return Reports.error("run", error);
      }

      // Application-level domain validation runs after VM execution succeeds (exit code 4).
      if (host.ledger.balance < 0) {
        return Reports.appFailure("ledger: negative balance");
      }
      ByteArrayOutputStream result = new ByteArrayOutputStream(64);
      result.writeBytes(
          ("{\"balance\": "
                  + host.ledger.balance
                  + ", \"entries\": "
                  + host.ledger.entries
                  + ", \"note\": ")
              .getBytes(StandardCharsets.US_ASCII));
      result.writeBytes(Reports.escapeBytes(host.ledger.note));
      result.writeBytes(
          (", \"entries_called\": " + entriesCalled + "}").getBytes(StandardCharsets.US_ASCII));
      int code = Reports.ok(result.toByteArray());
      if (code != 0) return code;
      Reports.writeResultFrame(System.out, sink);
      return 0;
    } catch (Exception e) {
      return Reports.appFailure("cannot read sample input");
    }
  }

  /** Calls rule entry index with one argument; a non-void result prints `entry NAME = VALUE`. */
  private static void callEntry(
      Program program, int index, Context context, Registry registry, Value argument) {
    Program.Entry entry = program.entries().get(index);
    Value result = program.call(index, context, registry, List.of(argument));
    if (result.kind() != Value.Kind.NONE) {
      ByteArrayOutputStream line = new ByteArrayOutputStream(64);
      line.writeBytes("entry ".getBytes(StandardCharsets.US_ASCII));
      line.writeBytes(entry.name().getBytes(StandardCharsets.UTF_8));
      line.writeBytes(" = ".getBytes(StandardCharsets.US_ASCII));
      line.writeBytes(valueText(result));
      line.write('\n');
      byte[] bytes = line.toByteArray();
      System.out.write(bytes, 0, bytes.length);
      System.out.flush();
    }
  }

  /** Same text as the language's str(): int decimal, float %.15g semantics, bool true/false. */
  private static byte[] valueText(Value value) {
    return switch (value.kind()) {
      case INT -> Long.toString(value.asInt()).getBytes(StandardCharsets.US_ASCII);
      case FLOAT -> Formatting.formatG15(value.asFloat()).getBytes(StandardCharsets.US_ASCII);
      case BOOL -> (value.asBool() ? "true" : "false").getBytes(StandardCharsets.US_ASCII);
      case STR -> value.asString().bytes();
      default -> new byte[0];
    };
  }

  public static void main(String[] args) {
    int code = run(args);
    if (code != 0) {
      System.exit(code);
    }
  }
}
