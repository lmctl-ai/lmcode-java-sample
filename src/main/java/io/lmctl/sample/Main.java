package io.lmctl.sample;

import io.lmctl.lmcode.Program;
import io.lmctl.lmcode.Reports;
import io.lmctl.lmcode.VmException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Native host CLI protocol (`--version`, `--dsl-schema`, `--dsl-signatures`, `--dsl FILE`).
 *
 * <p>All output uses explicit UTF-8 and LF (`\n`) bytes so Linux, macOS, and Windows match
 * the canonical corpus byte-for-byte.
 */
public final class Main {
  private static final String VERSION = "0.1.0";
  private static final String USAGE =
      "usage: --version | --dsl-schema [--json] | --dsl-signatures | --dsl FILE";

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
          && "--dsl-schema".equals(args[0])) {
        byte[] schema = readSchema();
        System.out.write(schema, 0, schema.length);
        System.out.flush();
        return 0;
      }
      if (args.length == 1 && "--dsl-signatures".equals(args[0])) {
        for (String signature : host.registry.signatures()) {
          writeLine(signature);
        }
        return 0;
      }
      if (args.length != 2 || !"--dsl".equals(args[0])) {
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

      try {
        program.run(host.context(), host.registry);
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
      result.write('}');
      return Reports.ok(result.toByteArray());
    } catch (Exception e) {
      return Reports.appFailure("cannot read sample input");
    }
  }

  public static void main(String[] args) {
    int code = run(args);
    if (code != 0) {
      System.exit(code);
    }
  }
}
