package kafkita;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public class WatchfulSubprocess {

  // Kills the subprocess if the parent disappears
  private static class ParentListenerThread extends Thread {

    public void run() {
      // TODO:
    }
  }

  public static String readResourceAsString(String resourcePath) {
    return new BufferedReader(
            new InputStreamReader(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath),
                StandardCharsets.UTF_8))
        .lines()
        .collect(Collectors.joining("\n"));
  }

  public static void main(String[] args) throws Exception {
    // new ParentListenerThread().start();
    // System.out.println(readResourceAsString(System.getProperty("log4j.configuration")));
    childMain(args[0], Arrays.copyOfRange(args, 1, args.length));
  }

  public static void childMain(String className, String[] args) throws Exception {
    Class.forName(className).getMethod("main", String[].class).invoke(null, (Object) args);
  }
}
