package kafkita;

import java.util.Arrays;

public class WatchfulSubprocess {

  // Kills the subprocess if the parent disappears
  private static class ParentListenerThread extends Thread {

    public void run() {
      // TODO:
    }
  }

  public static void main(String[] args) throws Exception {
    // new ParentListenerThread().start();
    childMain(args[0], Arrays.copyOfRange(args, 1, args.length));
  }

  public static void childMain(String className, String[] args) throws Exception {
    Class.forName(className).getMethod("main", String[].class).invoke(null, (Object) args);
  }
}
