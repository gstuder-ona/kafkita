package kafkita;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class KafkitaRule implements TestRule {

  public File kafkitaDir = null;
  public Thread shutdownHook = null;
  public Kafkita kafkita = null;

  public KafkitaRule() {}

  @Override
  public Statement apply(Statement base, Description description) {

    try {
      this.kafkitaDir = Files.createTempDirectory("kafkita-").toFile();
      this.shutdownHook = new Thread(() -> FileUtils.deleteQuietly(kafkitaDir));
      Runtime.getRuntime().addShutdownHook(this.shutdownHook);
      this.kafkita = new Kafkita(this.kafkitaDir);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }

    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          base.evaluate();
        } finally {
          kafkita.waitForStop();
          kafkita = null;
          FileUtils.deleteDirectory(kafkitaDir);
          Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
      }
    };
  }
}
