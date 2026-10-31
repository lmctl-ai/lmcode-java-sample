package io.lmctl.sample;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.Formatting;
import io.lmctl.lmcode.Program;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Reports;
import io.lmctl.lmcode.ResultSink;
import io.lmctl.lmcode.InvocationResults;
import io.lmctl.lmcode.ResultContract;
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
import java.util.ArrayList;
import java.util.Comparator;

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
        for (String signature : new Shop(false).registry.signatures()) {
          writeLine(signature);
        }
        return 0;
      }
      List<String> files = new ArrayList<>();
      List<Integer> priorities = new ArrayList<>();
      for (int i = 0; i < args.length; i += 2) {
        if (i + 1 >= args.length || !(args[i].equals("--lmcode") || args[i].equals("--first") || args[i].equals("--last"))) return usage();
        files.add(args[i + 1]); priorities.add(args[i].equals("--first") ? 0 : args[i].equals("--last") ? 2 : 1);
      }
      if (files.isEmpty()) return usage();
      InvocationResults results = InvocationResults.fromCommand(files);
      // Read and bind the invocation mode before host setup can acquire resources.
      Shop host = new Shop(results.dry());
      List<Integer> order = new ArrayList<>(); for (int i = 0; i < files.size(); i++) order.add(i);
      order.sort(Comparator.comparingInt(priorities::get));
      byte[] schema = readSchema(); ResultContract contract = ResultContract.fromSchema(schema);
      Context context = host.context(); context.setDry(results.dry()); int entriesCalled = 0; boolean failed = false;
      for (int position : order) {
        InvocationResults.Registration part = results.registrations().get(position);
        String phase = "load";
        try {
          Program program;
          try { program = Program.load(Files.readAllBytes(Path.of(part.file())), schema, host.registry); }
          catch (VmException error) {
            String message = switch (error.code) { case "LOAD_SCHEMA_MISMATCH" -> "sample schema mismatch"; case "LOAD_UNBOUND" -> "sample import mismatch"; default -> "invalid sample bytecode"; };
            throw new VmException(error.code, message);
          }
          results.select(part.index(), host.registry, contract); phase = "run"; program.run(context, host.registry);
          for (int i = 0; i < program.entries().size(); i++) {
            String annotation = program.entries().get(i).annotation();
            if ("item".equals(annotation)) for (Shop.Money item : host.itemHandles()) { callEntry(program, i, context, host.registry, Value.object("Money", item)); entriesCalled++; }
            else if ("total".equals(annotation)) { callEntry(program, i, context, host.registry, host.totalHandle()); entriesCalled++; }
          }
          results.complete(part.index());
        } catch (VmException error) { results.fail(part.index(), phase, error); failed = true; break; }
        catch (Exception error) { results.fail(part.index(), "app", new VmException("APP_FAIL", "cannot read sample input")); failed = true; break; }
      }
      if (!failed && host.ledger.balance < 0) { results.fail(null, "app", new VmException("APP_FAIL", "ledger: negative balance")); failed = true; }
      if (!failed) {
        ByteArrayOutputStream summary = new ByteArrayOutputStream(64);
        summary.writeBytes(("{\"host_summary\":{\"balance\": " + host.ledger.balance + ", \"entries\": " + host.ledger.entries + ", \"note\": ").getBytes(StandardCharsets.US_ASCII));
        summary.writeBytes(Reports.escapeBytes(host.ledger.note));
        String preview = results.dry() ? ", \"dry_preview_calls\": " + host.dryPreviewCalls : "";
        summary.writeBytes((", \"entries_called\": " + entriesCalled + preview + "}}\n").getBytes(StandardCharsets.US_ASCII));
        System.out.write(summary.toByteArray());
      }
      return results.writeFrame(System.out);
    } catch (VmException e) {
      return Reports.failureFrame("cli", e);
    } catch (Exception e) {
      return Reports.failureFrame("app", new VmException("APP_FAIL", "cannot read sample input"));
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
